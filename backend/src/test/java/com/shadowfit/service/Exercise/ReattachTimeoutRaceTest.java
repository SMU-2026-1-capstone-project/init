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
        //    notifyAi=true 로 부르는 것은 SessionTimeoutScheduler 가 실제로 그렇게 부르기 때문이다
        //    (이슈 #98 수정). 여기서 2-인자 오버로드를 쓰면 테스트만 옛 동작을 재현하게 된다.
        boolean changed = sessionService.markAsFailedIfStillInProgress(s.getId(), LocalDateTime.now(), true);
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
    @DisplayName("FAILED 세션에 도착한 pose_data 는 버려진다 — 고아 행 창을 닫는다 (#187 (b), #77/#87)")
    void 실패세션_포즈데이터_버려짐() {
        Session s = raceIntoFailed();

        // AI 는 재부착에 성공했으므로 클라의 프레임을 받아 계속 콜백한다. 그런데 스케줄러가
        // 이미 이 세션을 FAILED 로 걷어갔다 — 예전에는 이 콜백이 그대로 저장돼 아무도 다시
        // 훑지 않는 고아 행이 됐다(#77/#87). 이제 status 가드(#187 (b))가 그 창을 닫는다.
        poseDataService.savePoseDataBatch(s.getId(), batch(1, 10));

        int rows = poseDataRepository.findFramesBySessionId(s.getId(), s.getStartTime()).size();
        assertThat(rows)
                .as("savePoseDataBatch 는 IN_PROGRESS 가 아니면 버린다 — FAILED 세션에 고아 행이 안 쌓인다")
                .isZero();
    }

    @Test
    @DisplayName("스케줄러가 걷어갈 때 AI 통보가 적재된다 — 종료를 눌러도 중복되지 않는다")
    void 스케줄러가_AI통보를_적재하고_종료는_중복하지_않는다() {
        // 이슈 #98 이전에는 여기서 아무 행도 생기지 않았고, 그래서 AI 상태가 남고 리포트도
        // 만들어지지 않았다. 이제 스케줄러가 상태 전환과 같은 트랜잭션에 통보를 적재한다.
        long before = outboxRepository.count();

        Session s = raceIntoFailed();

        long afterTimeout = outboxRepository.count();
        assertThat(afterTimeout)
                .as("스케줄러가 FAILED 로 전환하면서 StopAnalysis 통보를 같은 트랜잭션에 적재한다")
                .isEqualTo(before + 1);

        // 사용자가 뒤늦게 "운동 종료"를 누른다. endSession 의 멱등 가드(endTime != null)에 걸려
        // 조기 return 하는데, 이제는 그게 옳다 — 통보는 이미 적재돼 있다.
        sessionService.endSession(s.getId(), owner.getId());

        assertThat(outboxRepository.count())
                .as("이미 적재된 통보가 있으므로 두 번 보내지 않는다")
                .isEqualTo(afterTimeout);
    }

    @Test
    @DisplayName("notifyAi=false 인 호출처는 통보를 적재하지 않는다")
    void 통보_비대상_호출처는_행을_만들지_않는다() {
        // 서킷 OPEN(StartAnalysis 를 아예 안 보냄) · failSessionFast(방금 보낸 게 실패해서 걷어내는
        // 중) 두 경로가 쓰는 오버로드다. 여기서 통보하면 각각 도달 불가한 행이 쌓이거나(서킷)
        // 자기호출로 왕복이 한 번 더 늘어난다(failSessionFast).
        Session s = sessionAtTimeoutEdge();
        long before = outboxRepository.count();

        assertThat(sessionService.markAsFailedIfStillInProgress(s.getId(), LocalDateTime.now())).isTrue();

        assertThat(outboxRepository.count())
                .as("2-인자 오버로드는 notifyAi=false 로 위임한다 — 기존 호출처의 동작이 바뀌면 안 된다")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("사용자가 먼저 종료했으면 스케줄러가 통보를 중복 적재하지 않는다")
    void 종료가_먼저면_통보가_중복되지_않는다() {
        // endSession 은 endTime 만 찍고 status 는 IN_PROGRESS 로 둔다(전환은 applyComplete 몫).
        // 그래서 AI 결과가 끝내 오지 않으면 스케줄러가 나중에 같은 세션을 집는다 — 상태 가드를
        // 그대로 통과하므로, 막지 않으면 같은 세션에 StopAnalysis 통보가 두 건이 된다.
        Session s = sessionAtTimeoutEdge();
        long before = outboxRepository.count();

        sessionService.endSession(s.getId(), owner.getId());
        assertThat(outboxRepository.count())
                .as("종료가 통보를 적재한다")
                .isEqualTo(before + 1);

        assertThat(sessionService.markAsFailedIfStillInProgress(s.getId(), LocalDateTime.now(), true))
                .as("status 는 아직 IN_PROGRESS 라 전환 자체는 일어난다")
                .isTrue();

        assertThat(outboxRepository.count())
                .as("통보는 이미 적재돼 있다 — 두 번 보내지 않는다")
                .isEqualTo(before + 1);
    }

    @Test
    @DisplayName("이미 FAILED 인 세션은 통보를 다시 적재하지 않는다")
    void 이미_실패한_세션은_통보를_반복하지_않는다() {
        Session s = raceIntoFailed();
        long after = outboxRepository.count();

        // failSessionFast 가 도는 경우를 흉내낸다 — StopAnalysis 가 실패해 다시 걷어내려는 시도.
        // 상태 가드에 걸려 false 로 빠지므로 통보도 늘지 않는다. 이것이 "자기호출이 한 라운드에서
        // 멈춘다"의 근거다.
        assertThat(sessionService.markAsFailedIfStillInProgress(s.getId(), LocalDateTime.now(), true))
                .as("IN_PROGRESS 가 아니면 아무것도 하지 않는다")
                .isFalse();

        assertThat(outboxRepository.count()).isEqualTo(after);
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
