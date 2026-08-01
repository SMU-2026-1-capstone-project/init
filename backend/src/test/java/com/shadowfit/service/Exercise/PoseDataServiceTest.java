package com.shadowfit.service.Exercise;

import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.grpc.PoseDataRequest;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseCategory;
import com.shadowfit.model.exercise.ExerciseReference;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
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
}
