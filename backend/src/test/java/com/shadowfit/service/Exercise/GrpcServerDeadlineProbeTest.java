package com.shadowfit.service.Exercise;

import com.shadowfit.grpc.ExerciseServiceGrpc;
import com.shadowfit.grpc.PoseDataBatchRequest;
import com.shadowfit.grpc.PoseDataRequest;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseCategory;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 이슈 #206 결함 B <b>회귀 감시</b> — 클라이언트가 deadline 으로 포기하면 서버가 쓰기를 멈추는가.
 *
 * <p>⚠️ <b>이 테스트는 원래 반대를 단언했다.</b> 결함을 재현해 «서버가 계속 일한다»(rows &gt; 0)를
 * 고정하는 것이 처음 역할이었고, {@code CallCancellation} 도입으로 고쳐지면서 기대값을 뒤집었다.
 * 아래 옛 서술은 그 재현이 무엇을 갈랐는지 남겨두려고 그대로 둔다.
 *
 * <p>#206 은 «핸들러 4개 어디에도 {@code Context.current().getDeadline()} 확인이 없다» 를 코드
 * 독해로 적어두고 <b>미검증</b>으로 남겼다. 이 테스트가 그 판정을 실행으로 바꾼다. 이슈 §5 가
 * 반증 조건을 함께 적어뒀다 — <b>grpc-java 가 취소 시 핸들러 스레드를 인터럽트해서 저장이 실제로
 * 중단된다면 이 이슈는 A 만 남는다.</b> 즉 이 테스트는 결함을 «확인» 하러 가는 것이 아니라
 * 두 갈래 중 어느 쪽인지 «가르러» 간다.
 *
 * <p>⚠️ <b>이 테스트는 #280 머지 뒤 main 에서 깨져 있었다</b>(#302). 원인은 서버가 아니라
 * 이 테스트였다 — {@code handlerFinished} 를 «저장이 끝났다» 로 읽고 커밋 전에 행을 셌다.
 * 진단에서 즉시 0, 1초 뒤 2 가 나왔다. 그래서 판정은 «반증 조건 쪽» 으로 보였지만 실제로는
 * <b>서버가 끝까지 저장하고 있었다</b>. 지금은 커밋이 보일 때까지 기다렸다 센다.
 *
 * <p><b>증거는 DB 행이다</b>(이슈 §4). 로그로는 «시작했다» 까지만 알 수 있고 «완주했다» 를
 * 못 본다. 클라이언트가 포기한 배치의 행이 테이블에 남으면 서버가 계속 일한 것이다.
 *
 * <p><b>느리게 만드는 지점</b>은 {@code PoseDataService:68} 의 세션 존재 검증이다. 그 다음 줄부터가
 * 실제 저장이라, 여기서 늦추면 «클라이언트가 포기한 뒤에 저장이 시작되는» 순서를 확정적으로
 * 만들 수 있다. 타이밍 경주에 맡기지 않는다 — {@code PoseDataOrphanRaceTest} 와 같은 방식이다.
 *
 * <p>테스트 전용 포트(16565)로 진짜 서버를 띄운다 — in-process 는 클래스패스 버전 문제로 못 쓴다
 * (상수 주석 참고).
 */
@SpringBootTest(properties = "grpc.server.port=" + GrpcServerDeadlineProbeTest.GRPC_PORT)
@DisplayName("#206-B gRPC 서버가 클라이언트의 포기를 보는가")
class GrpcServerDeadlineProbeTest {

    /**
     * 테스트 전용 포트. in-process 서버는 못 쓴다 — 클래스패스의 grpc-inprocess 와 코어 버전이
     * 어긋나 {@code AbstractMethodError} 로 기동에 실패한다(2026-08-16 실측). deadline 은 어차피
     * 전송 계층이 아니라 {@code Context} 로 전파되므로 실제 포트로 재도 같은 것을 잰다.
     */
    static final int GRPC_PORT = 16565;

    /**
     * 클라이언트가 기다리는 시간. 아래 지연보다 짧아야 «포기 후 저장» 순서가 확정된다.
     *
     * <p>200ms 로 시작했다가 올렸다 — 워밍업을 넣어도 간헐적으로 <b>핸들러 진입 전에</b> 만료됐고,
     * 그러면 서버가 디스패치조차 안 해 «아무것도 안 잰» 판이 된다. 필요한 것은 «짧은 deadline» 이
     * 아니라 «deadline &lt; 서버 작업시간» 이므로 둘 다 올리는 편이 실험을 안정시킨다.
     */
    private static final long CLIENT_DEADLINE_MS = 1_000;
    /** 존재 검증에서 붙잡아 두는 시간. */
    private static final long SERVER_STALL_MS = 4_000;

    /**
     * 커밋이 보이기를 기다리는 상한 (#302). 「행이 없다」를 판정하기 전에 이만큼은 기다린다.
     *
     * <p>이 값이 짧아서 판정이 뒤집히는 일은 없다 — 진단에서 커밋은 1초 안에 보였고, 여기서
     * 재는 것은 «얼마나 걸리나» 가 아니라 «끝내 안 나타나는가» 다.
     */
    private static final long COMMIT_VISIBLE_TIMEOUT_MS = 5_000;

    @Value("${internal.api.token}")
    private String internalToken;

    // 🔴 지연 지점은 서비스이지 리포지터리가 아니다. SessionRepository 는 인터페이스라
    //    Mockito 의 callRealMethod() 를 쓸 수 없고(PoseDataOrphanRaceTest 주석이 같은 함정을
    //    이미 적어뒀다), 거기서 지연을 걸면 «저장 실패» 로 끝나 실험이 다른 것을 재게 된다.
    @MockitoSpyBean private PoseDataService poseDataService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private ManagedChannel channel;
    private Long sessionId;

    @BeforeEach
    void setUp() {
        Member member = memberRepository.saveAndFlush(Member.builder()
                .email("deadline@test.com").username("데드라인").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        Exercise exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(ExerciseCategory.LOWER).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00"))
                .syncThresholdAdvanced(new BigDecimal("85.00")).build());
        sessionId = sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(LocalDateTime.now()).status(Status.IN_PROGRESS)
                .totalReps(0).difficultyLevel(1).build()).getId();

        channel = ManagedChannelBuilder.forAddress("localhost", GRPC_PORT).usePlaintext().build();
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    @Test
    @DisplayName("클라이언트가 DEADLINE_EXCEEDED 로 포기한 뒤에도 저장이 완주하는지 — DB 행으로 판정")
    void serverKeepsWorkingAfterClientGivesUp() throws Exception {
        // 워밍업 — 첫 호출은 채널 연결·JIT·JPA 초기화에 시간을 쓴다. 그대로 재면 deadline 이
        // 핸들러에 닿기도 전에 만료돼 «아무것도 안 잰» 결과가 나온다(첫 시도에서 실제로 그랬다).
        ExerciseServiceGrpc.newBlockingStub(channel)
                .withCallCredentials(bearer(internalToken))
                .savePoseDataBatch(batchOf(1));
        long rowsAfterWarmup = countRows();

        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch handlerFinished = new CountDownLatch(1);

        // 저장 메서드 전체를 붙잡는다. existsById 만 붙잡으면 «완주» 가 그 한 줄의 완주를 뜻해
        // 오독하게 된다(첫 판에서 실제로 그랬다). 여기서는 서비스 호출 전체의 끝을 잰다.
        java.util.concurrent.atomic.AtomicReference<Throwable> thrown =
                new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(invocation -> {
            handlerEntered.countDown();
            Thread.sleep(SERVER_STALL_MS);
            try {
                return invocation.callRealMethod();
            } catch (Throwable t) {
                thrown.set(t);
                throw t;
            } finally {
                handlerFinished.countDown();
            }
        }).when(poseDataService).savePoseDataBatch(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyList());

        ExerciseServiceGrpc.ExerciseServiceBlockingStub stub =
                ExerciseServiceGrpc.newBlockingStub(channel)
                        .withCallCredentials(bearer(internalToken))
                        .withDeadlineAfter(CLIENT_DEADLINE_MS, TimeUnit.MILLISECONDS);

        assertThatThrownBy(() -> stub.savePoseDataBatch(batchOf(10, 2)))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Code.DEADLINE_EXCEEDED);

        assertThat(handlerEntered.await(5, TimeUnit.SECONDS))
                .as("핸들러가 실제로 시작은 했어야 한다 — 아니면 이 실험이 아무것도 안 잰 것이다")
                .isTrue();

        // 서버가 취소를 보고 스스로 그만두는지, 아니면 끝까지 가는지. 여기서 갈린다.
        boolean finishedOnItsOwn = handlerFinished.await(SERVER_STALL_MS * 3, TimeUnit.MILLISECONDS);

        // 🔴 커밋이 보일 때까지 기다렸다 센다 (#302).
        //
        // handlerFinished 는 «저장이 끝났다» 가 아니라 «스파이가 실제 메서드에서 돌아왔다» 다.
        // 스파이가 @Transactional 프록시 «안쪽» 이라 커밋은 그 뒤에 일어난다 — 그대로 세면
        // 커밋 전 값을 읽고, 결과가 «행 0 · 예외 없음» 으로 나온다. 그게 «서버가 멈췄다» 와
        // 구분이 안 돼서 이 테스트가 main 에서 깨진 채로 있었다(#302 진단: 즉시 0, 1초 뒤 2).
        long rows = awaitRowsSince(rowsAfterWarmup, COMMIT_VISIBLE_TIMEOUT_MS);

        System.out.printf("%n=== #206-B 관측 ===%n");
        System.out.printf("  클라이언트   : DEADLINE_EXCEEDED (%dms)%n", CLIENT_DEADLINE_MS);
        System.out.printf("  핸들러 진입  : yes%n");
        System.out.printf("  저장 완주    : %s%n", finishedOnItsOwn);
        System.out.printf("  워밍업 행    : %d  (측정 수단이 도는지 확인용 — 0 이면 카운트가 고장난 것)%n",
                rowsAfterWarmup);
        System.out.printf("  pose_data 행 : %d  (포기당한 배치의 것, rep=2 라 워밍업과 안 겹친다)%n", rows);
        Throwable t = thrown.get();
        System.out.printf("  서버 예외    : %s%n",
                t == null ? "없음" : t.getClass().getName() + " / msg=" + t.getMessage());
        System.out.printf("  판정        : %s%n",
                rows > 0 ? "🔴 서버가 포기를 무시하고 끝까지 저장했다 (#206-B 회귀)"
                         : "상한까지 기다려도 행이 없다 — 서버가 쓰기를 시작하지 않았다 (기대값)");

        // 지연이 끝난 뒤 서비스 호출 자체는 «반환» 된다 — 다만 CallAbandonedException 을 던지고
        // 나오므로 쓰기는 안 일어난다. 스파이의 finally 가 이 래치를 내리므로 여전히 true 다.
        assertThat(finishedOnItsOwn)
                .as("스파이가 지연 뒤 실제 메서드까지 갔는가 — false 면 이 실험이 다른 것을 잰 것이다")
                .isTrue();
        assertThat(rowsAfterWarmup)
                .as("워밍업 호출이 행을 남겼어야 한다 — 아니면 이 실험의 측정 수단 자체가 고장난 것")
                .isPositive();

        // 🔴 기대값이 뒤집힌 자리다. 이 테스트는 원래 «결함이 있다»(rows > 0)를 고정했고,
        //    #206-B 를 고치면서 «없다»(rows == 0)로 바꿨다. 이제 회귀 감시가 이 테스트의 역할이다.
        //
        // 📌 이 0 은 «아직 안 보인다» 가 아니다. awaitRowsSince 가 상한(5초)까지 기다린 뒤의
        //    값이라, #302 가 고친 그 함정(커밋 전에 세기)에는 다시 안 걸린다. 수정 전이라면
        //    같은 자리에서 2 가 나온다 — 그게 #302 가 되살린 판정이다.
        assertThat(rows)
                .as("상한까지 기다려도 포기당한 배치의 행이 안 나타나는가 — 나타나면 서버가 또 계속 일한 것 (#206-B 회귀)")
                .isZero();

        // 왜 안 남았는지까지 못박는다. 행이 0 인 이유가 «취소를 봤다» 가 아니라 «저장이 그냥
        // 실패했다» 일 수도 있는데, 그러면 같은 0 이어도 전혀 다른 사건이다.
        assertThat(thrown.get())
                .as("쓰기를 안 한 이유가 «호출자가 포기했다» 여야 한다 — 다른 예외면 이 판은 무효다")
                .isInstanceOf(com.shadowfit.global.observability.CallAbandonedException.class);
    }

    /**
     * {@code baseline} 이후로 늘어난 행 수. 하나라도 보이면 바로 돌려주고, 끝내 안 보이면
     * 상한까지 기다린 뒤 0 을 돌려준다 (#302).
     *
     * <p>«기다렸는데도 0» 과 «아직 커밋이 안 보여서 0» 을 가르는 것이 이 메서드의 전부다.
     * 앞의 것만이 «서버가 일을 안 했다» 는 뜻이다.
     */
    private long awaitRowsSince(long baseline, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (true) {
            long rows = countRows() - baseline;
            if (rows > 0 || System.nanoTime() >= deadline) {
                return rows;
            }
            Thread.sleep(50);
        }
    }

    /**
     * {@code baseline} 이후로 늘어난 행 수. 하나라도 보이면 바로 돌려주고, 끝내 안 보이면
     * 상한까지 기다린 뒤 0 을 돌려준다 (#302).
     *
     * <p>«기다렸는데도 0» 과 «아직 커밋이 안 보여서 0» 을 가르는 것이 이 메서드의 전부다.
     * 앞의 것만이 «서버가 일을 안 했다» 는 뜻이다.
     */
    private long awaitRowsSince(long baseline, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (true) {
            long rows = countRows() - baseline;
            if (rows > 0 || System.nanoTime() >= deadline) {
                return rows;
            }
            Thread.sleep(50);
        }
    }

    /**
     * 행 수를 직접 센다. {@code PoseDataRepository.countSince} 는 쓸 수 없다 — 그 쿼리는
     * {@code created_at > :since} 조건인데, 운영 스키마에는 {@code DEFAULT CURRENT_TIMESTAMP} 가
     * 있지만(V1:205) H2 테스트 스키마는 엔티티에서 만들어지고 그 컬럼이
     * {@code insertable = false} 라 <b>기본값이 없다</b>. 그래서 JdbcTemplate 직삽입 행의
     * created_at 이 NULL 이 되고 비교가 전부 거짓이 된다(2026-08-16, 이 실험에서 실측).
     * 운영 결함은 아니지만, H2 위에서 그 메서드로 무언가를 판정하면 조용히 0 을 얻는다.
     */
    private long countRows() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pose_data WHERE session_id = ?", Long.class, sessionId);
        return n == null ? 0 : n;
    }

    private PoseDataBatchRequest batchOf(int frames) {
        return batchOf(frames, 1);
    }

    /**
     * {@code repNumber} 를 받는 이유 (#302). 멱등 키가 {@code uk_pose_event (session_id,
     * rep_number, timestamp_sec, created_at)} 이고 {@code created_at} 은 세션 앵커라 상수다 —
     * 즉 워밍업과 본 배치가 같은 rep 을 쓰면 <b>겹치는 timestamp 가 통째로 흡수된다</b>.
     * 그러면 「행이 없다」가 «서버가 멈췄다» 인지 «멱등에 먹혔다» 인지 안 갈린다.
     */
    private PoseDataBatchRequest batchOf(int frames, int repNumber) {
        List<PoseDataRequest> list = new ArrayList<>();
        for (int i = 0; i < frames; i++) {
            list.add(PoseDataRequest.newBuilder()
                    .setTimestampSec(i * 0.1)
                    .setJointCoordinates("{}")
                    .setSyncRate(70.0)
                    .setRepNumber(repNumber)
                    .setSmoothedKneeAngle(120.0)
                    .setFeedbackMessage("ok")
                    .build());
        }
        return PoseDataBatchRequest.newBuilder()
                .setSessionId(sessionId)
                .addAllPoseData(list)
                .build();
    }

    /** InternalAuthInterceptor 가 Authorization 헤더를 요구한다. */
    private CallCredentials bearer(String token) {
        return new CallCredentials() {
            @Override
            public void applyRequestMetadata(RequestInfo requestInfo, Executor appExecutor,
                                             MetadataApplier applier) {
                Metadata headers = new Metadata();
                headers.put(Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER),
                        "Bearer " + token);
                applier.apply(headers);
            }
        };
    }
}
