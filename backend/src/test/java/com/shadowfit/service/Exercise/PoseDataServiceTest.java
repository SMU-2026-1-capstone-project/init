package com.shadowfit.service.Exercise;

import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.grpc.PoseDataRequest;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseCategory;
import com.shadowfit.model.exercise.ExerciseReference;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.exercise.SyncStats;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.ExerciseReferenceRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PoseDataService 통합테스트 — savePoseDataBatch(실시간 저장, JdbcTemplate batchUpdate)와
 * saveReferencePoses(관리자용 기준 좌표 저장) 둘 다 실제 DB로 검증.
 *
 * <p>다운샘플이 <b>어느 프레임을 남기는가</b>가 이 클래스의 핵심 관심사다. 남는 프레임이 달라져도
 * {@code sync_rate} 는 그대로지만(rep 단위 상수) {@code joint_coordinates} 는 프레임마다 다르므로,
 * 이 선택이 리포트에 그려질 자세를 결정한다. 버려진 프레임은 DB 에 없어 나중에 되찾을 수 없다.
 */
@SpringBootTest
@Transactional
@DisplayName("PoseDataService 테스트")
class PoseDataServiceTest {

    @Autowired private PoseDataService poseDataService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private ExerciseReferenceRepository referenceRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MeterRegistry meterRegistry;

    private Session session;
    private Exercise exercise;

    @BeforeEach
    void setUp() {
        Member member = memberRepository.saveAndFlush(Member.builder()
                .email("posedata@test.com").username("u").password("dummy").role(UserRole.USER).build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(ExerciseCategory.LOWER).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .build());
        session = sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise).startTime(LocalDateTime.now())
                .status(Status.IN_PROGRESS).totalReps(0).difficultyLevel(1).build());
    }

    private PoseDataRequest frame(double timestampSec, double syncRate) {
        return frame(timestampSec, syncRate, 0, 0.0);
    }

    private PoseDataRequest frame(double timestampSec, double syncRate, int repNumber) {
        return frame(timestampSec, syncRate, repNumber, 0.0);
    }

    private PoseDataRequest frame(
            double timestampSec, double syncRate, int repNumber, double smoothedKneeAngle) {
        return PoseDataRequest.newBuilder()
                .setTimestampSec(timestampSec)
                .setJointCoordinates("{}")
                .setSyncRate(syncRate)
                .setRepNumber(repNumber)
                .setSmoothedKneeAngle(smoothedKneeAngle)
                .setFeedbackMessage("ok")
                .build();
    }

    /**
     * 실제 AI 가 보내는 모양의 배치 — <b>한 배치 = 한 rep</b> 이고 그 안의 프레임은 모두 같은
     * {@code syncRate}·{@code repNumber} 를 갖는다. 싱크로율이 rep 단위로 채점돼 프레임마다
     * 복제되기 때문이다(ai-server {@code pose.py:111}), 그리고 콜백은 rep 이 완성될 때만 나간다
     * ({@code pose.py:98-118}).
     *
     * <p>이 헬퍼를 따로 둔 이유: 예전 테스트들이 한 배치 안에 서로 다른 sync_rate 를 넣어
     * <b>실제로는 존재하지 않는 입력</b>으로 다운샘플 로직을 검증하고 있었다(이슈 #79).
     *
     * <p><b>무릎각은 스쿼트 한 회의 모양대로 넣는다</b> — 서 있다(150°)가 내려가고 바닥(90°)을
     * 찍고 다시 올라온다. {@code syncRate} 와 달리 이 값은 프레임마다 실제로 다르고, 다운샘플이
     * 어느 프레임을 남길지가 여기서 갈린다(§4-ㄹ). 상수로 채우면 선택 로직이 동률로 퇴화해
     * 검증되지 않으므로 그렇게 하지 않는다.
     */
    private List<PoseDataRequest> realisticBatch(int repNumber, int frameCount, double syncRate) {
        List<PoseDataRequest> frames = new java.util.ArrayList<>();
        for (int i = 0; i < frameCount; i++) {
            // 앞 절반은 내려가고 뒤 절반은 올라온다 — V 자 궤적의 꼭짓점이 바닥이다.
            int fromBottom = Math.abs(i - frameCount / 2);
            double kneeAngle = 90.0 + fromBottom * 15.0;
            // i * 0.1 은 3 에서 0.30000000000000004 가 되어 리터럴 0.3 과 다른 double 이 된다.
            frames.add(frame(i / 10.0, syncRate, repNumber, kneeAngle));
        }
        return frames;
    }

    @Test
    @DisplayName("정상 batch — sync_rate가 그대로 저장된다 (is_correct는 2026-08-01 삭제)")
    void savePoseDataBatch_success_persistsSyncRate() {
        // 한 배치 = 한 rep 이라 sync_rate 는 배치 안에서 상수다(아래 realisticBatch 주석)
        poseDataService.savePoseDataBatch(session.getId(), realisticBatch(1, 2, 30.0));

        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT sync_rate FROM pose_data WHERE session_id = ? ORDER BY timestamp_sec", session.getId());

        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0).get("SYNC_RATE")).doubleValue()).isEqualTo(30.0);
    }

    @Test
    @DisplayName("다운샘플 — 윈도우(5)마다 1행. 행 수는 그대로 1/5 이다 (R≈5 유지)")
    void savePoseDataBatch_downsamples_keepsRatio() {
        // 7프레임 → 2행. 어느 프레임이 남는지와 별개로 **몇 개가 남는지**는 안 바뀐다는 것을 고정한다.
        poseDataService.savePoseDataBatch(session.getId(), realisticBatch(1, 7, 65.0));

        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT timestamp_sec, sync_rate FROM pose_data WHERE session_id = ? ORDER BY timestamp_sec", session.getId());

        assertThat(rows).hasSize(2);
        // rep 안에서 sync_rate 는 상수 — 어느 프레임이 남든 이 값은 같다
        assertThat(rows).allSatisfy(row ->
                assertThat(((Number) row.get("SYNC_RATE")).doubleValue()).isEqualTo(65.0));
    }

    @Test
    @DisplayName("★ 다운샘플은 윈도우마다 가장 깊은 프레임을 남긴다 — 첫 프레임이 아니다 (§4-ㄹ)")
    void savePoseDataBatch_downsampleKeepsDeepestFrame() {
        // 7프레임 V 자 궤적: 무릎각 135·120·105·90·105·120·135 (인덱스 3 이 바닥).
        // 윈도우 [0..4] 의 최소는 인덱스 3(0.3s, 90°), 윈도우 [5..6] 의 최소는 인덱스 5(0.5s, 120°).
        // 예전 균등 샘플링이었다면 0.0s·0.5s 가 남아 **바닥 프레임이 통째로 버려졌다.**
        poseDataService.savePoseDataBatch(session.getId(), realisticBatch(1, 7, 65.0));

        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT timestamp_sec, smoothed_knee_angle FROM pose_data WHERE session_id = ? ORDER BY timestamp_sec",
                session.getId());

        assertThat(rows).hasSize(2);
        assertThat(((Number) rows.get(0).get("TIMESTAMP_SEC")).doubleValue()).isEqualTo(0.3);
        assertThat(((Number) rows.get(0).get("SMOOTHED_KNEE_ANGLE")).doubleValue()).isEqualTo(90.0);
        assertThat(((Number) rows.get(1).get("TIMESTAMP_SEC")).doubleValue()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("무릎각이 전부 0(미상)이면 예전처럼 윈도우 첫 프레임 — 구버전 AI 하위호환")
    void savePoseDataBatch_downsampleFallsBackWhenAngleUnknown() {
        // proto3 라 구버전 AI 는 이 필드를 보내지 않는다. 고를 근거가 없으면 균등 샘플링으로
        // 떨어지므로 배포 순서를 맞추지 않아도 동작이 깨지지 않는다.
        List<PoseDataRequest> legacy = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            legacy.add(frame(i / 10.0, 65.0, 1)); // smoothedKneeAngle = 0.0
        }
        poseDataService.savePoseDataBatch(session.getId(), legacy);

        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT timestamp_sec FROM pose_data WHERE session_id = ? ORDER BY timestamp_sec", session.getId());

        assertThat(rows).hasSize(2);
        assertThat(((Number) rows.get(0).get("TIMESTAMP_SEC")).doubleValue()).isEqualTo(0.0);
        assertThat(((Number) rows.get(1).get("TIMESTAMP_SEC")).doubleValue()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("미상(0)과 유효값이 섞인 윈도우에서는 유효값 중 최소 — 0이 이기지 않는다")
    void savePoseDataBatch_downsampleIgnoresUnknownAngleWhenValidExists() {
        // 배포 전환기에 두 세대가 섞일 수 있다. 0 을 그냥 최소값으로 다루면 미상 프레임이 항상
        // 이겨 실제로 가장 깊은 프레임이 버려진다.
        poseDataService.savePoseDataBatch(session.getId(), List.of(
                frame(0.0, 65.0, 1, 0.0),     // 미상 — 후보 아님
                frame(0.1, 65.0, 1, 130.0),
                frame(0.2, 65.0, 1, 95.0),    // ← 유효값 중 최소
                frame(0.3, 65.0, 1, 0.0),     // 미상
                frame(0.4, 65.0, 1, 120.0)
        ));

        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT timestamp_sec FROM pose_data WHERE session_id = ? ORDER BY timestamp_sec", session.getId());

        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0).get("TIMESTAMP_SEC")).doubleValue()).isEqualTo(0.2);
    }

    /**
     * rep 하나치 프레임. {@link #realisticBatch}와 같은 V 자 궤적이되 <b>rep 마다 시각이 겹치지
     * 않도록</b> 오프셋을 준다 — 여러 rep 을 이어 붙여 비교하는 아래 두 테스트에서 행을
     * 시각으로 식별해야 하기 때문이다.
     */
    private List<PoseDataRequest> repFrames(int repNumber, int frameCount, double syncRate, double tOffset) {
        List<PoseDataRequest> frames = new java.util.ArrayList<>();
        for (int i = 0; i < frameCount; i++) {
            int fromBottom = Math.abs(i - frameCount / 2);
            frames.add(frame(tOffset + i / 10.0, syncRate, repNumber, 90.0 + fromBottom * 5.0));
        }
        return frames;
    }

    private List<java.util.Map<String, Object>> savedRows() {
        return jdbcTemplate.queryForList(
                "SELECT timestamp_sec, smoothed_knee_angle, rep_number FROM pose_data "
                        + "WHERE session_id = ? ORDER BY timestamp_sec", session.getId());
    }

    private void clearRows() {
        jdbcTemplate.update("DELETE FROM pose_data WHERE session_id = ?", session.getId());
    }

    /**
     * ★ <b>커밋 횟수 실험(ㄱ안)의 전제를 고정하는 테스트다.</b>
     *
     * <p>{@code docs/decisions/commit-count-and-mysql-metrics.md} §2-1 이 *"25프레임×1요청과
     * 125프레임×1요청은 같은 행을 쓴다 — 바뀌는 것은 커밋 횟수뿐"* 이라고 적었고, ③ 실험의
     * 조작이 «커밋 횟수 하나» 라는 주장이 여기 걸려 있다. <b>이게 깨지면 그 실험은 두 가지를
     * 동시에 바꾸는 것이 되어 설계가 무너진다.</b>
     *
     * <p>성립하는 이유는 {@code downsample} 의 윈도우가 <b>리스트 시작점 기준으로 정렬</b>되기
     * 때문이다 — rep 당 프레임 수가 윈도우(5)의 배수면 이어 붙여도 경계가 같은 자리에 떨어진다.
     */
    @Test
    @DisplayName("★ 다운샘플은 배치 경계에 무관하다 — rep 당 프레임이 윈도우(5)의 배수일 때")
    void downsample_isBatchInvariant_whenFramesPerRepIsMultipleOfWindow() {
        final int framesPerRep = 25; // 5의 배수
        final int repCount = 5;

        // A: 지금 구조 — rep 마다 한 요청(= 한 트랜잭션, 커밋 5회)
        for (int r = 0; r < repCount; r++) {
            poseDataService.savePoseDataBatch(session.getId(),
                    repFrames(r + 1, framesPerRep, 70.0, r * 10.0));
        }
        List<java.util.Map<String, Object>> perRep = savedRows();

        clearRows();

        // B: ㄱ안 — 5 rep 을 한 요청으로(= 커밋 1회)
        List<PoseDataRequest> merged = new java.util.ArrayList<>();
        for (int r = 0; r < repCount; r++) {
            merged.addAll(repFrames(r + 1, framesPerRep, 70.0, r * 10.0));
        }
        poseDataService.savePoseDataBatch(session.getId(), merged);
        List<java.util.Map<String, Object>> batched = savedRows();

        // 행 수뿐 아니라 «어느 프레임이 남았는가» 까지 같아야 한다. 행 수만 보면 대표 프레임이
        // 바뀌어도 통과해버리고, 그러면 실험은 «커밋 횟수» 말고 «저장된 자세» 도 바꾼 것이 된다.
        assertThat(batched).hasSize(repCount * framesPerRep / 5);
        assertThat(batched).isEqualTo(perRep);
    }

    /**
     * ★ 위 불변성의 <b>경계 조건</b>. 같은 문서 §2-1 이 ㄱ안을 «측정용» 으로만 추천하고
     * 채택 판단을 분리한 근거가 이것이다.
     *
     * <p>실제 rep 의 프레임 수는 사용자가 얼마나 천천히 앉았는지에 따라 달라지므로 <b>5의 배수라는
     * 보장이 없다.</b> 배수가 아니면 윈도우가 rep 경계를 넘어가고, 그때 남는 대표 프레임은 서로
     * 다른 두 rep 에서 뽑힐 수 있다 — 저장되는 {@code rep_number} 도 그 승자의 것이 된다.
     *
     * <p>즉 ghz rig(`--reps 25`)에서는 성립하지만 <b>운영에 채택하면 성립하지 않는다.</b>
     * 이 테스트는 그 차이를 «나중에 발견» 하지 않으려고 지금 고정해둔다.
     */
    @Test
    @DisplayName("★ 경계 — rep 당 프레임이 윈도우의 배수가 아니면 배치 경계가 결과를 바꾼다")
    void downsample_isNotBatchInvariant_whenFramesPerRepIsNotMultipleOfWindow() {
        final int framesPerRep = 23; // 5의 배수가 아니다 — 실제 rep 은 이쪽이 정상이다
        final int repCount = 5;

        for (int r = 0; r < repCount; r++) {
            poseDataService.savePoseDataBatch(session.getId(),
                    repFrames(r + 1, framesPerRep, 70.0, r * 10.0));
        }
        List<java.util.Map<String, Object>> perRep = savedRows();

        clearRows();

        List<PoseDataRequest> merged = new java.util.ArrayList<>();
        for (int r = 0; r < repCount; r++) {
            merged.addAll(repFrames(r + 1, framesPerRep, 70.0, r * 10.0));
        }
        poseDataService.savePoseDataBatch(session.getId(), merged);
        List<java.util.Map<String, Object>> batched = savedRows();

        // rep 마다 마지막 윈도우가 3프레임짜리 자투리로 끝나던 것이, 이어 붙이면 다음 rep 의
        // 프레임으로 채워진다. 행 수부터 다르다: 5 × ceil(23/5)=25 vs ceil(115/5)=23
        assertThat(perRep).hasSize(25);
        assertThat(batched).hasSize(23);
        assertThat(batched).isNotEqualTo(perRep);
    }

    @Test
    @DisplayName("배치 지표 — 수신 프레임 수와 다운샘플 후 저장 행수가 stage 태그로 기록됨")
    void savePoseDataBatch_recordsFrameMetrics() {
        // 공유 컨텍스트라 다른 테스트가 이미 올려둔 값이 있을 수 있어 절대값이 아니라 증분으로 본다
        DistributionSummary received = meterRegistry.summary("shadowfit.pose.batch.frames", "stage", "received");
        DistributionSummary stored = meterRegistry.summary("shadowfit.pose.batch.frames", "stage", "stored");
        double receivedBefore = received.totalAmount();
        double storedBefore = stored.totalAmount();

        // 7프레임 → 윈도우(5) 기준 2행으로 다운샘플
        poseDataService.savePoseDataBatch(session.getId(), List.of(
                frame(0.0, 90.0), frame(0.1, 80.0), frame(0.2, 10.0), frame(0.3, 70.0), frame(0.4, 60.0),
                frame(0.5, 50.0), frame(0.6, 20.0)
        ));

        // 실제 저장 행수와 지표가 어긋나면 운영 중 다운샘플 비율을 잘못 읽게 된다
        assertThat(received.totalAmount() - receivedBefore).isEqualTo(7.0);
        assertThat(stored.totalAmount() - storedBefore).isEqualTo(2.0);
    }

    @Test
    @DisplayName("종료된(COMPLETED) 세션에 도착한 배치는 조용히 버린다 — 삽입 없음 (#187 (b))")
    void savePoseDataBatch_terminalSession_dropsWithoutInsert() {
        session.complete(3, SyncStats.none(), null, LocalDateTime.now());  // status → COMPLETED
        sessionRepository.saveAndFlush(session);

        // 던지지 않는다 — #280 재시도가 영영 거절될 배치를 다시 던지지 않게, 조용히 반환한다
        poseDataService.savePoseDataBatch(session.getId(), List.of(frame(0.0, 50.0)));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pose_data WHERE session_id = ?", Integer.class, session.getId());
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("FAILED 세션도 마찬가지로 버린다 (#187 (b))")
    void savePoseDataBatch_failedSession_dropsWithoutInsert() {
        session.fail(LocalDateTime.now());  // status → FAILED
        sessionRepository.saveAndFlush(session);

        poseDataService.savePoseDataBatch(session.getId(), List.of(frame(0.0, 50.0)));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pose_data WHERE session_id = ?", Integer.class, session.getId());
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("존재하지 않는 세션이면 SESSION_NOT_FOUND, 아무 것도 삽입 안 함")
    void savePoseDataBatch_unknownSession_throwsAndInsertsNothing() {
        assertThatThrownBy(() -> poseDataService.savePoseDataBatch(999999L, List.of(frame(0.0, 50.0))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_NOT_FOUND);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pose_data WHERE session_id = 999999", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("빈 리스트/null이면 조용히 반환, 삽입 없음")
    void savePoseDataBatch_emptyOrNull_noop() {
        poseDataService.savePoseDataBatch(session.getId(), List.of());
        poseDataService.savePoseDataBatch(session.getId(), null);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pose_data WHERE session_id = ?", Integer.class, session.getId());
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("기준 좌표 저장 — 정상 등록")
    void saveReferencePoses_success() {
        poseDataService.saveReferencePoses(exercise.getId(), List.of(frame(0.0, 100.0), frame(0.1, 100.0)));

        List<ExerciseReference> refs = referenceRepository.findByExerciseId(exercise.getId());
        assertThat(refs).hasSize(2);
    }

    @Test
    @DisplayName("기준 좌표 저장 — 존재하지 않는 운동이면 EXERCISE_NOT_FOUND")
    void saveReferencePoses_unknownExercise_throws() {
        assertThatThrownBy(() -> poseDataService.saveReferencePoses(999999L, List.of(frame(0.0, 100.0))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXERCISE_NOT_FOUND);
    }

    @Test
    @DisplayName("기준 좌표 저장 — 빈 리스트면 조용히 반환")
    void saveReferencePoses_empty_noop() {
        poseDataService.saveReferencePoses(exercise.getId(), List.of());

        assertThat(referenceRepository.findByExerciseId(exercise.getId())).isEmpty();
    }

    // --- 멱등 (#188) ---
    //
    // AI 재시도가 붙으면 같은 배치가 두 번 도착한다. 그때 행이 늘어나면 리포트 집계(평균
    // sync·worst 구간)가 중복 수만큼 왜곡된다 — 즉 유실을 막으려다 다른 오염을 들이는 셈이다.
    //
    // ⚠️ 이 검증이 성립하는 이유는 엔티티에 uk_pose_event 가 선언돼 있어서다. 테스트는 H2 +
    //    ddl-auto 라 Flyway(V6)을 보지 않으므로, 마이그레이션에만 넣었다면 제약 없는 스키마
    //    위에서 초록불이 났을 것이다(test/resources/application.yml 의 경고와 같은 함정).

    @Test
    @DisplayName("멱등 — 같은 배치를 두 번 보내도 행 수가 그대로다")
    void savePoseDataBatch_resend_isIdempotent() {
        List<PoseDataRequest> batch = realisticBatch(1, 10, 70.0);

        poseDataService.savePoseDataBatch(session.getId(), batch);
        Integer afterFirst = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pose_data WHERE session_id = ?", Integer.class, session.getId());

        poseDataService.savePoseDataBatch(session.getId(), batch);
        Integer afterSecond = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pose_data WHERE session_id = ?", Integer.class, session.getId());

        assertThat(afterFirst).isPositive();
        assertThat(afterSecond).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("멱등 — created_at 은 적재 시각이 아니라 세션 시작 시각이다")
    void savePoseDataBatch_createdAt_isSessionAnchor() {
        poseDataService.savePoseDataBatch(session.getId(), realisticBatch(1, 10, 70.0));

        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT DISTINCT created_at FROM pose_data WHERE session_id = ?", session.getId());

        // 한 세션의 모든 행이 값 하나를 공유한다 — 이게 재전송에도 같은 값이 나오는 근거이고,
        // 세션이 파티션 경계에서 쪼개지지 않는 이유다.
        assertThat(rows).hasSize(1);

        // 초 단위로 비교하는 것은 편의가 아니라 **DB 컬럼 정밀도** 때문이다. 운영 MySQL 의
        // created_at 은 TIMESTAMP(소수 이하 0자리)이고 H2 는 마이크로초까지 잡는데, 자바
        // LocalDateTime.now() 는 나노초를 갖는다. 어느 쪽이든 잘림은 **결정론적**이라 멱등에는
        // 무해하다 — 재전송 때도 같은 값이 저장된다.
        assertThat(((java.sql.Timestamp) rows.get(0).get("CREATED_AT")).toLocalDateTime()
                        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
                .isEqualTo(session.getStartTime().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("멱등 — rep 이 다르면 같은 timestamp_sec 이어도 별개 행이다")
    void savePoseDataBatch_differentRep_notAbsorbed() {
        poseDataService.savePoseDataBatch(session.getId(), realisticBatch(1, 10, 70.0));
        Integer afterRep1 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pose_data WHERE session_id = ?", Integer.class, session.getId());

        // 같은 세션·같은 timestamp_sec·같은 created_at 이지만 rep 이 다르다. 키에 rep_number 가
        // 있으므로 흡수되면 안 된다 — 흡수되면 2rep 째 프레임이 통째로 사라진다.
        poseDataService.savePoseDataBatch(session.getId(), realisticBatch(2, 10, 70.0));
        Integer afterRep2 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pose_data WHERE session_id = ?", Integer.class, session.getId());

        assertThat(afterRep2).isEqualTo(afterRep1 * 2);
    }
}
