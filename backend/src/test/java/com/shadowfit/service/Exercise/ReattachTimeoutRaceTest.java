package com.shadowfit.service.Exercise;

import com.shadowfit.grpc.PoseDataRequest;
import com.shadowfit.grpc.SessionCompleteRequest;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseCategory;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재부착 검증과 gRPC 사이에 스케줄러가 끼어들면 실제로 무슨 일이 나는가 (이슈 #77).
 *
 * <p><b>이 테스트의 목적은 고치는 게 아니라 결과를 확정하는 것이다.</b> 이슈 #77 은 "창이
 * 존재한다"까지만 확인하고 그 뒤를 미확인으로 남겼다. 창이 좁으므로(타임아웃 경계 직전 5초에
 * 재부착이 걸려야 한다) 결과가 무해하면 감수하고 닫는 게 맞고, 유해하면 그때 고칠 방법을 고른다.
 *
 * <p>레이스를 스레드로 재현하지 않고 <b>순서대로 호출</b>한다. 문제의 본질이 동시 실행이 아니라
 * TOCTOU — "검증했다"와 "부착했다" 사이에 상태가 바뀌는 것 — 이라, 그 사이에 스케줄러 몫을
 * 끼워 넣으면 창에 걸린 것과 같은 상태가 만들어진다. 스레드를 쓰면 타이밍에 기대는 불안정한
 * 테스트가 될 뿐 관측되는 결과는 같다.
 *
 * <p>재현하는 창:
 * <pre>
 *   findReattachableSession()   → 통과 (아직 IN_PROGRESS, 타임아웃 기준 이전)
 *        ↓  markAsFailedIfStillInProgress()  ← 스케줄러가 끼어든다
 *   reattachAnalysis() gRPC     → AI 에는 상태가 만들어진다 (여기선 성공했다고 가정)
 * </pre>
 * gRPC 자체는 AI 서버가 필요해 이 테스트 범위 밖이다({@code SessionReattachTest} 와 같은 경계).
 * 여기서 보는 것은 <b>gRPC 가 성공한 뒤 DB 세션이 FAILED 인 상태에서 이어지는 경로들</b>이다.
 */
@SpringBootTest
@Transactional
@DisplayName("재부착-타임아웃 레이스의 결과 (이슈 #77)")
class ReattachTimeoutRaceTest {

    @Autowired private SessionService sessionService;
    @Autowired private PoseDataService poseDataService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private PoseDataRepository poseDataRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private OutboxEventRepository outboxRepository;

    @Value("${exercise.session.timeout.default-buffer-minutes:30}")
    private int bufferMinutes;

    private static final int EXPECTED_DURATION_MINUTES = 15;

    private Member owner;
    private Exercise exercise;

    @BeforeEach
    void setUp() {
        owner = memberRepository.saveAndFlush(Member.builder()
                .email("race-owner@test.com").username("owner").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(ExerciseCategory.LOWER)
                .expectedDurationMinutes(EXPECTED_DURATION_MINUTES)
                .syncThresholdBeginner(new BigDecimal("60.00"))
                .syncThresholdAdvanced(new BigDecimal("85.00"))
                .analysisSupported(true)
                .build());
    }

    /**
     * 타임아웃 기준을 <b>아슬아슬하게 넘지 않은</b> 세션. 재부착 검증은 통과하지만 바로 다음 순간
     * 스케줄러가 걷어갈 수 있는 지점이다 — 이슈가 말한 "경계 직전 5초 구간"이 이것.
     */
    private Session sessionAtTimeoutEdge() {
        LocalDateTime startTime = LocalDateTime.now()
                .minusMinutes(EXPECTED_DURATION_MINUTES + bufferMinutes)
                .plusSeconds(3);
        return sessionRepository.saveAndFlush(Session.builder()
                .member(owner).exercise(exercise)
                .startTime(startTime).status(Status.IN_PROGRESS).endTime(null)
                .totalReps(0).difficultyLevel(1).build());
    }

    private PoseDataRequest frame(int repNumber, double timestampSec, double syncRate) {
        return PoseDataRequest.newBuilder()
                .setRepNumber(repNumber)
                .setTimestampSec(timestampSec)
                .setJointCoordinates("{}")
                .setSyncRate(syncRate)
                .setFeedbackMessage("ok")
                .build();
    }

    /** 다운샘플(R=5)을 통과해 최소 1행은 남도록 5프레임 단위로 만든다. */
    private List<PoseDataRequest> batch(int repNumber, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> frame(repNumber, i * 0.33, 70.0))
                .toList();
    }

    private SessionCompleteRequest complete(Long sessionId, int totalReps) {
        return SessionCompleteRequest.newBuilder()
                .setSessionId(sessionId)
                .setTotalReps(totalReps)
                .setAvgSyncRate(70.0).setMaxSyncRate(75.0).setMinSyncRate(65.0)
                .setCaloriesBurned(0.0).setDifficultyLevel(1)
                .build();
    }

    /** 창에 걸린 상태를 만든다 — 검증 통과 직후 스케줄러가 FAILED 로 전환. */
    private Session raceIntoFailed() {
        Session s = sessionAtTimeoutEdge();

        // 1. 재부착 검증 통과 — 이 시점에는 정상적으로 이어할 수 있는 세션이다.
        Session verified = sessionService.findReattachableSession(s.getId(), owner.getId());
        assertThat(verified.getId()).isEqualTo(s.getId());

        // 2. 스케줄러가 끼어든다. gRPC 왕복(≤5초) 중에 타임아웃 기준을 넘었고 틱이 떨어진 상황.
        boolean changed = sessionService.markAsFailedIfStillInProgress(s.getId(), LocalDateTime.now());
        assertThat(changed)
                .as("창이 실제로 존재한다 — 검증을 통과한 세션을 스케줄러가 곧바로 걷어갈 수 있다")
                .isTrue();

        return s;
    }

    @Test
    @DisplayName("창은 실재한다 — 검증을 통과한 세션이 곧바로 FAILED 가 된다")
    void 창_존재_확인() {
        Session s = raceIntoFailed();

        Session after = sessionRepository.findById(s.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Status.FAILED);
        assertThat(after.getEndTime()).isNotNull();
    }

    @Test
    @DisplayName("FAILED 세션에도 pose_data 가 계속 저장된다 — 상태를 보지 않는다")
    void 실패세션에도_포즈데이터_저장됨() {
        Session s = raceIntoFailed();

        // AI 는 재부착에 성공했으므로 클라의 프레임을 받아 계속 콜백한다.
        poseDataService.savePoseDataBatch(s.getId(), batch(1, 10));

        int rows = poseDataRepository.findFramesBySessionId(s.getId()).size();
        assertThat(rows)
                .as("savePoseDataBatch 는 existsById 만 본다 — 세션이 FAILED 여도 막지 않는다")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("종료를 눌러도 아웃박스 행이 안 생긴다 — AI 에 StopAnalysis 가 영영 안 간다")
    void 종료해도_AI통보가_발행되지_않는다() {
        Session s = raceIntoFailed();
        long before = outboxRepository.count();

        // 사용자가 "운동 종료"를 누른다.
        sessionService.endSession(s.getId(), owner.getId());

        assertThat(outboxRepository.count())
                .as("endSession 의 멱등 가드가 endTime != null 인데, 그 endTime 을 스케줄러가 이미 "
                        + "채워놨다 → 조기 return 이라 OutboxEvent.stopAnalysis 가 적재되지 않는다")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("AI 콜백이 오면 FAILED 를 COMPLETED 로 덮어 복구한다")
    void AI콜백은_실패세션을_되살린다() {
        Session s = raceIntoFailed();
        poseDataService.savePoseDataBatch(s.getId(), batch(1, 10));

        sessionService.completeSession(complete(s.getId(), 1));

        Session after = sessionRepository.findById(s.getId()).orElseThrow();
        assertThat(after.getStatus())
                .as("applyComplete 의 멱등 가드는 COMPLETED 만 걸러낸다 — FAILED 는 덮어쓴다")
                .isEqualTo(Status.COMPLETED);
        assertThat(after.getTotalReps()).isEqualTo(1);
    }
}
