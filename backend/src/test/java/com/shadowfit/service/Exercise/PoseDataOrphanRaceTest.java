package com.shadowfit.service.Exercise;

import com.shadowfit.grpc.PoseDataRequest;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseCategory;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

/**
 * {@code PoseDataService.savePoseDataBatch} 의 TOCTOU 레이스 재현 — 세션 존재 검증(①)과
 * 배치 INSERT(②) 사이에 회원 탈퇴가 끼어들면 <b>고아 pose_data 행</b>이 남는다.
 *
 * <pre>
 *   [AI 콜백 스레드]                          [탈퇴 API 스레드]
 *   ① existsById(sessionId) → true
 *                                             exercise_sessions 삭제 (users CASCADE) → 커밋
 *                                             afterCommit → cleanupBySessionIds → pose_data 삭제
 *                                             (이 시점 ②의 행은 아직 없어 정리 대상이 아니다)
 *   ② batchUpdate INSERT → 착지
 *   → 세션은 없는데 pose_data 는 남음
 * </pre>
 *
 * <p><b>왜 결함인가.</b> {@code pose_data} 는 파티셔닝을 위해 FK(ON DELETE CASCADE)를 뗐고
 * ({@code docs/decisions/pose-data-partition-fk-tradeoff.md}), 그 대체가 ①의 애플리케이션 체크와
 * {@code PoseDataCleanupService} 두 가지다. {@code PoseDataService:61-66} 주석이 스스로 ①을
 * "참조무결성을 보장하는 <i>유일한</i> 장치"라고 적어뒀는데, 그 장치가 check-then-act 라 뚫린다.
 * 남은 고아 행은 어느 정리 경로도 다시 훑지 않으므로 <b>파티션 TTL(보존 2개월)이 그 달을 드롭할
 * 때까지 살아남는다</b> — 탈퇴 회원의 좌표를 곧바로 지운다는 {@code PoseDataCleanupService}
 * 의 목적이 그만큼 깨진다.
 *
 * <p><b>왜 H2(기본 테스트 프로파일)로는 안 되는가.</b> 기본 프로파일은 {@code ddl-auto: create-drop}
 * 이라 Hibernate 가 {@code PoseData} 엔티티의 {@code @ManyToOne @JoinColumn(session_id)} 에서
 * FK 를 만들어버린다. 프로덕션에는 없는 FK 다. 그러면 ②가 참조무결성 위반으로 <b>터져서</b>
 * 고아가 안 생긴다 — 재현이 안 되는 게 아니라 결과가 반대로 나온다. "FK 가 없다"는 전제 위에
 *   for f in backend/src/main/resources/db/migration/V*.sql; do
 *     docker exec -i shadowfit-race-mysql mysql -uroot -pracetest shadowfit &lt; "$f"; done
 *   (V1 하나만 적재하면 안 된다 — 마이그레이션이 V8 까지 왔다. #342)
 * 그래서 {@code application-race.yml}(3307 일회용 컨테이너)을 쓴다.
 *
 * <p><b>실행법</b> — 시스템 프로퍼티가 없으면 통째로 건너뛰므로 CI 는 영향받지 않는다:
 * <pre>
 *   docker run -d --name shadowfit-race-mysql -e MYSQL_ROOT_PASSWORD=racetest \
 *     -e MYSQL_DATABASE=shadowfit -p 3307:3306 mysql:8.0 \
 *     --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
 *   for f in backend/src/main/resources/db/migration/V*.sql; do
 *     docker exec -i shadowfit-race-mysql mysql -uroot -pracetest shadowfit &lt; "$f"; done
 *   (V1 하나만 적재하면 안 된다 — 마이그레이션이 V8 까지 왔다. #342)
 *   ./gradlew :backend:test --tests '*PoseDataOrphanRaceTest' -Drace.mysql=true
 * </pre>
 *
 * <p><b>레이스를 타이밍에 맡기지 않는 이유.</b> ①과 ② 사이는 다운샘플 계산뿐이라 실제 창은
 * 마이크로초 단위다. 그냥 두 스레드를 경주시키면 대부분 재현되지 않아 "결함 없음"으로 오독된다.
 * 그래서 {@code existsById} 를 스파이로 잡아 <b>체크가 통과한 바로 그 지점</b>에서 멈춰 세우고
 * 탈퇴를 끼워 넣는다. 창의 폭을 넓힐 뿐 순서는 실제로 일어날 수 있는 그대로다 —
 * 창이 좁다는 것은 발생 확률의 문제지 존재 여부의 문제가 아니다.
 */
@SpringBootTest
@ActiveProfiles("race")
@EnabledIfSystemProperty(named = "race.mysql", matches = "true",
        disabledReason = "실제 MySQL(3307)이 필요 — 클래스 주석의 docker 명령 참고")
@DisplayName("pose_data 고아 행 레이스")
class PoseDataOrphanRaceTest {

    private static final int FRAME_COUNT = 10; // DOWNSAMPLE_WINDOW=5 → 2행 저장

    @Autowired private PoseDataService poseDataService;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PoseDataOrphanMonitor orphanMonitor;

    // PoseDataService 가 주입받는 그 빈을 스파이로 바꿔치기해 ①의 반환 직후를 붙잡는다.
    @MockitoSpyBean private SessionRepository sessionRepository;

    private Long memberId;
    private Long sessionId;
    /** ① 에서 서비스에 돌려줄 엔티티. 스파이라 실제 조회를 대신 부를 수 없어 시딩 때 붙잡아 둔다. */
    private Session seededSession;

    @AfterEach
    void tearDown() {
        // ddl-auto: none + 스레드 경계라 @Transactional 롤백을 못 쓴다 — 남긴 것은 직접 지운다.
        // 고아 행은 정의상 세션이 없어 CASCADE 로도 안 지워지므로 session_id 로 직접 지운다.
        if (sessionId != null) {
            jdbcTemplate.update("DELETE FROM pose_data WHERE session_id = ?", sessionId);
        }
        if (memberId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", memberId);
        }
    }

    @Test
    @DisplayName("세션 검증 통과 후 탈퇴가 끼어들면 pose_data 가 고아로 남는다")
    void orphanRowsSurviveWithdrawal() throws Exception {
        seedSession();

        CountDownLatch checkPassed = new CountDownLatch(1);
        CountDownLatch withdrawalDone = new CountDownLatch(1);

        doAnswer(invocation -> {
            // 🔴 2026-08-22: 이음매를 existsById → findById 로 옮겼다.
            //   #280(커밋 4e1d64c, 2026-08-20)이 적재 경로를 findById 로 바꿨는데(start_time 이
            //   필요해서다 — PoseDataService:91) 이 테스트는 existsById 를 계속 잡고 있었다.
            //   목이 안 불리니 래치가 안 풀리고 «예외도 안 나서» 이틀간 조용히 실패했다.
            //   CI 는 이 테스트를 건너뛰므로 아무도 못 봤다 (#342).
            //
            // invocation.callRealMethod() 는 여기서 못 쓴다 — SessionRepository 는 인터페이스라
            // Mockito 가 "Cannot call abstract real method" 로 거절한다. 그래서 ① 시점에 세션이
            // <b>실제로 존재했다</b>는 사실은 SQL 로 직접 확인하고(그게 확인돼야 나중에 남는 행을
            // "고아"라고 부를 수 있다), 서비스에 돌려줄 엔티티는 시딩 때 붙잡아 둔 것을 쓴다.
            Integer found = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM exercise_sessions WHERE id = ?", Integer.class, sessionId);
            if (found == null || found == 0) {
                checkPassed.countDown();
                return Optional.empty();               // 세션이 없으면 SESSION_NOT_FOUND 로 간다
            }
            checkPassed.countDown();                    // "①을 통과했다" 를 알리고
            withdrawalDone.await(10, TimeUnit.SECONDS); // 탈퇴가 끝날 때까지 ②를 미룬다
            return Optional.of(seededSession);
        }).when(sessionRepository).findById(sessionId);

        AtomicReference<Throwable> ingestError = new AtomicReference<>();
        Thread ingest = new Thread(() -> {
            try {
                poseDataService.savePoseDataBatch(sessionId, oneRep());
            } catch (Throwable t) {
                ingestError.set(t);
            }
        }, "pose-ingest");
        ingest.start();

        if (!checkPassed.await(10, TimeUnit.SECONDS)) {
            // 래치만 보고 실패하면 진짜 원인(ingest 스레드에서 터진 예외)이 묻힌다 — 스레드를
            // 풀어주고 그 예외를 실패 메시지에 실어 보낸다.
            withdrawalDone.countDown();
            ingest.join(TimeUnit.SECONDS.toMillis(5));
            throw new AssertionError(
                    "①(findById)까지 도달하지 못했다. ingest 스레드 예외: " + ingestError.get(),
                    ingestError.get());
        }

        simulateWithdrawal();
        withdrawalDone.countDown();
        ingest.join(TimeUnit.SECONDS.toMillis(15));

        assertThat(ingestError.get())
                .as("②는 아무 저항 없이 성공한다 — FK 가 없어 DB 가 막지 않는다").isNull();

        Integer remainingSessions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exercise_sessions WHERE id = ?", Integer.class, sessionId);
        Integer orphanRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pose_data WHERE session_id = ?", Integer.class, sessionId);

        assertThat(remainingSessions).as("탈퇴로 세션은 사라진 상태여야 한다").isZero();
        assertThat(orphanRows)
                .as("결함: 세션이 없는데 pose_data 가 %d행 남았다 — 어느 정리 경로도 다시 훑지 않으므로 "
                        + "파티션 TTL 이 이 달을 드롭할 때까지 살아남는다", orphanRows)
                .isPositive();

        // 탐지기(PoseDataOrphanMonitor)가 이 고아를 실제로 집어내는지 같은 무대에서 확인한다.
        // 관측 대상을 만들 수 있는 곳이 여기뿐이라(H2 는 FK 때문에 고아 자체가 안 생긴다),
        // 탐지 쿼리의 검증도 이 테스트가 겸한다. 다른 고아가 섞여 있을 수 있어 하한만 본다.
        assertThat(orphanMonitor.countOrphans())
                .as("탐지기가 방금 만든 고아 %d행을 세야 한다", orphanRows)
                .isGreaterThanOrEqualTo(orphanRows.longValue());
    }

    /**
     * 탈퇴 시퀀스 재현. 프로덕션 순서 그대로 <b>세션 삭제가 먼저, pose_data 정리가 나중</b>이다
     * ({@code MemberService.deleteAccount} 커밋 → {@code afterCommit} → {@code @Async}
     * {@code PoseDataCleanupService.cleanupBySessionIds}). 이 순서가 결함의 핵심이다 — 정리가
     * 돌 때 ②의 행은 아직 존재하지 않아 정리 대상에 잡히지 않는다.
     *
     * <p>{@code @Async} 를 그대로 태우지 않고 같은 스레드에서 SQL 로 재현하는 이유는 결정성이다.
     * 이 테스트의 관심사는 정리가 <i>언제</i> 도느냐가 아니라 <b>정리가 끝난 뒤에 INSERT 가
     * 착지할 수 있다</b>는 사실이고, 회원 탈퇴 CASCADE 자체는
     * {@code MemberDeletionCascadeIntegrationTest} 가 이미 따로 검증한다.
     */
    private void simulateWithdrawal() {
        jdbcTemplate.update("DELETE FROM exercise_sessions WHERE id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM pose_data WHERE session_id = ?", sessionId);
    }

    private void seedSession() {
        Member member = memberRepository.saveAndFlush(Member.builder()
                .email("orphan-race@test.com").username("고아레이스").password("dummy")
                .role(UserRole.USER).build());
        memberId = member.getId();

        Exercise exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(ExerciseCategory.LOWER).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00"))
                .syncThresholdAdvanced(new BigDecimal("85.00"))
                .build());

        // status 는 IN_PROGRESS 여야 한다 — #304 가 붙인 가드가 그 외 상태의 배치를 «조용히»
        // 버리므로(PoseDataService:110), 다른 상태면 이 테스트는 아무 일도 안 하고 통과한다.
        Session session = sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise).startTime(LocalDateTime.now())
                .status(Status.IN_PROGRESS).totalReps(0).difficultyLevel(1).build());
        sessionId = session.getId();
        seededSession = session;
    }

    /**
     * 한 배치 = 한 rep — rep 안에서 {@code syncRate}·{@code repNumber} 는 상수이고
     * 무릎각만 스쿼트 한 회의 모양(150° → 90° → 150°)으로 움직인다
     * ({@code PoseDataServiceTest} 와 같은 전제, 이슈 #79).
     */
    private List<PoseDataRequest> oneRep() {
        List<PoseDataRequest> frames = new ArrayList<>();
        for (int i = 0; i < FRAME_COUNT; i++) {
            int half = FRAME_COUNT / 2;
            double kneeAngle = i <= half ? 150.0 - (60.0 * i / half)
                                         : 90.0 + (60.0 * (i - half) / half);
            frames.add(PoseDataRequest.newBuilder()
                    .setTimestampSec(i * 0.1)
                    .setJointCoordinates("{}")
                    .setSyncRate(72.5)
                    .setRepNumber(1)
                    .setSmoothedKneeAngle(kneeAngle)
                    .setFeedbackMessage("ok")
                    .build());
        }
        return frames;
    }
}
