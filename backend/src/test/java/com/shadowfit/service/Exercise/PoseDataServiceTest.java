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
 * saveReferencePoses(관리자용 기준 좌표 저장) 둘 다 실제 DB로 검증. e2e에 곁다리로만 검증되던
 * savePoseDataBatch의 is_correct 임계값(40.0) 로직도 여기서 직접 확인한다.
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
        return frame(timestampSec, syncRate, 0);
    }

    private PoseDataRequest frame(double timestampSec, double syncRate, int repNumber) {
        return PoseDataRequest.newBuilder()
                .setTimestampSec(timestampSec)
                .setJointCoordinates("{}")
                .setSyncRate(syncRate)
                .setRepNumber(repNumber)
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
     */
    private List<PoseDataRequest> realisticBatch(int repNumber, int frameCount, double syncRate) {
        List<PoseDataRequest> frames = new java.util.ArrayList<>();
        for (int i = 0; i < frameCount; i++) {
            frames.add(frame(i * 0.1, syncRate, repNumber));
        }
        return frames;
    }

    @Test
    @DisplayName("정상 batch — is_correct는 저장된 프레임의 sync_rate(40 기준)로 계산")
    void savePoseDataBatch_success_computesIsCorrect() {
        // 한 배치 = 한 rep 이라 sync_rate 는 배치 안에서 상수다(아래 realisticBatch 주석)
        poseDataService.savePoseDataBatch(session.getId(), realisticBatch(1, 2, 30.0));

        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT sync_rate, is_correct FROM pose_data WHERE session_id = ? ORDER BY timestamp_sec", session.getId());

        assertThat(rows).hasSize(1);
        assertThat((Boolean) rows.get(0).get("IS_CORRECT")).isFalse();
        assertThat(((Number) rows.get(0).get("SYNC_RATE")).doubleValue()).isEqualTo(30.0);
    }

    @Test
    @DisplayName("다운샘플 — 윈도우(5)마다 첫 프레임만 남기는 균등 샘플링 (#79)")
    void savePoseDataBatch_downsamples_uniformly() {
        // 7프레임 → 인덱스 0·5 가 남는다(0.0s, 0.5s). 남는 게 "가장 나쁜 프레임"이 아니라
        // "주기의 첫 프레임"이라는 것을 값으로 고정한다.
        poseDataService.savePoseDataBatch(session.getId(), realisticBatch(1, 7, 65.0));

        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT timestamp_sec, sync_rate FROM pose_data WHERE session_id = ? ORDER BY timestamp_sec", session.getId());

        assertThat(rows).hasSize(2);
        assertThat(((Number) rows.get(0).get("TIMESTAMP_SEC")).doubleValue()).isEqualTo(0.0);
        assertThat(((Number) rows.get(1).get("TIMESTAMP_SEC")).doubleValue()).isEqualTo(0.5);
        // rep 안에서 sync_rate 는 상수 — 어느 프레임이 남든 이 값은 같다
        assertThat(rows).allSatisfy(row ->
                assertThat(((Number) row.get("SYNC_RATE")).doubleValue()).isEqualTo(65.0));
    }

    @Test
    @DisplayName("★ 남는 프레임은 sync_rate와 무관하다 — 순서만 바뀌어도 결과가 달라진다 (#79)")
    void savePoseDataBatch_selectionIsPositional_notWorstSync() {
        // 이 테스트의 픽스처는 **실제로는 일어나지 않는 모양**이다(한 배치 안 sync_rate 가 제각각).
        // 예전 테스트가 바로 이런 픽스처를 써서 "최저 프레임을 고른다"를 증명했는데, 그 입력이
        // 실데이터에 존재하지 않아 죽은 코드가 살아 있는 것처럼 보였다. 여기서는 반대로,
        // 선택이 값이 아니라 **위치**로 이뤄진다는 것을 드러내는 용도로만 쓴다.
        poseDataService.savePoseDataBatch(session.getId(), List.of(
                frame(0.0, 90.0), frame(0.1, 80.0), frame(0.2, 10.0), frame(0.3, 70.0), frame(0.4, 60.0),
                frame(0.5, 50.0), frame(0.6, 20.0)
        ));

        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT timestamp_sec, sync_rate FROM pose_data WHERE session_id = ? ORDER BY timestamp_sec", session.getId());

        assertThat(rows).hasSize(2);
        // 최저값 10.0(인덱스 2)·20.0(인덱스 6)이 아니라 각 윈도우의 첫 프레임이 남는다
        assertThat(((Number) rows.get(0).get("SYNC_RATE")).doubleValue()).isEqualTo(90.0);
        assertThat(((Number) rows.get(1).get("SYNC_RATE")).doubleValue()).isEqualTo(50.0);
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
