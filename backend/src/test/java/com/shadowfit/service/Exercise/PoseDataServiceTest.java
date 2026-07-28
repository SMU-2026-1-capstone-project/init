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
        return PoseDataRequest.newBuilder()
                .setTimestampSec(timestampSec)
                .setJointCoordinates("{}")
                .setSyncRate(syncRate)
                .setFeedbackMessage("ok")
                .build();
    }

    @Test
    @DisplayName("정상 batch — 다운샘플 윈도우(5) 안이면 sync_rate 최저(자세 최악) 프레임 1개만 대표로 저장, is_correct는 그 프레임 기준")
    void savePoseDataBatch_success_computesIsCorrect() {
        poseDataService.savePoseDataBatch(session.getId(), List.of(
                frame(0.0, 50.0),  // is_correct = true, 대표 아님(최악 아님)
                frame(0.1, 30.0)   // is_correct = false, 윈도우 내 최저 sync_rate → 대표로 저장
        ));

        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT sync_rate, is_correct FROM pose_data WHERE session_id = ? ORDER BY timestamp_sec", session.getId());

        assertThat(rows).hasSize(1);
        assertThat((Boolean) rows.get(0).get("IS_CORRECT")).isFalse();
        assertThat(((Number) rows.get(0).get("SYNC_RATE")).doubleValue()).isEqualTo(30.0);
    }

    @Test
    @DisplayName("다운샘플 — 윈도우(5)마다 sync_rate 최저 프레임만 남기고 나머지는 버림")
    void savePoseDataBatch_downsamples_keepsWorstSyncRatePerWindow() {
        // 첫 윈도우(0~4): 최저 sync_rate=10.0(3번째) / 둘째 윈도우(5~6, 부분): 최저=20.0(2번째)
        poseDataService.savePoseDataBatch(session.getId(), List.of(
                frame(0.0, 90.0), frame(0.1, 80.0), frame(0.2, 10.0), frame(0.3, 70.0), frame(0.4, 60.0),
                frame(0.5, 50.0), frame(0.6, 20.0)
        ));

        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT timestamp_sec, sync_rate FROM pose_data WHERE session_id = ? ORDER BY timestamp_sec", session.getId());

        assertThat(rows).hasSize(2);
        assertThat(((Number) rows.get(0).get("TIMESTAMP_SEC")).doubleValue()).isEqualTo(0.2);
        assertThat(((Number) rows.get(0).get("SYNC_RATE")).doubleValue()).isEqualTo(10.0);
        assertThat(((Number) rows.get(1).get("TIMESTAMP_SEC")).doubleValue()).isEqualTo(0.6);
        assertThat(((Number) rows.get(1).get("SYNC_RATE")).doubleValue()).isEqualTo(20.0);
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
