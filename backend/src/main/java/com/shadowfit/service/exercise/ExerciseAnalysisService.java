package com.shadowfit.service.exercise;

import com.shadowfit.dto.exercises.VideoRequestDto;
import com.shadowfit.dto.exercises.session.ReattachSessionResponseDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.observability.CorrelationIds;
import com.shadowfit.global.observability.GrpcCorrelationClientInterceptor;
import com.shadowfit.global.observability.SessionMetrics;
import com.shadowfit.grpc.*;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseReference;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.outbox.DispatchOutcome;
import com.shadowfit.model.outbox.OutboxEvent;
import com.shadowfit.model.outbox.OutboxEventType;
import com.shadowfit.model.outbox.OutboxStatus;
import com.shadowfit.repository.exercise.ExerciseReferenceRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.outbox.OutboxEventRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;


@Service
@RequiredArgsConstructor
@Slf4j
public class ExerciseAnalysisService {
    private final SessionRepository sessionRepository;
    private final ExercisesRepository exercisesRepository;
    private final MemberRepository memberRepository;
    private final SessionService sessionService;
    private final ExerciseReferenceRepository referenceRepository;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final SessionMetrics sessionMetrics;
    private final OutboxEventRepository outboxEventRepository;
    private final ReattachRequestBuilder reattachRequestBuilder;

    // 자기 주입: startAnalysis → sendAnalysisRequestToFastApi 호출이 Spring 프록시를 통과해
    // @Async가 적용되도록 함(:199 주석 참조).
    @Lazy
    @Autowired
    private ExerciseAnalysisService self;

    @Value("${internal.api.token}")
    private String internalToken;

    // 실측(2026-08-26, EC2 격리 테스트): net.devh 의 단일 채널(@GrpcClient) 은 커넥션 하나를
    // 계속 재사용해, AI 서버를 프로세스 여러 개로 띄워도(SO_REUSEPORT) 트래픽이 그중 하나로만
    // 몰린다는 걸 확인했다. 채널을 sessionId 기준으로 N개 풀에서 골라 쓰면 같은 세션의 호출은
    // 계속 같은 채널(=같은 프로세스)로 가면서, 세션마다는 서로 다른 채널로 흩어진다.
    //
    // 풀 크기는 상수가 아니라 ai.channel-pool-size(= docker-compose 의 AI_WORKER_COUNT 와
    // 같은 소스)에서 읽는다 — entrypoint.sh 가 띄우는 실제 워커 수와 손으로 맞출 필요가 없다
    // (docs/decisions/ai-channel-pool-hardening.md).
    @Value("${ai.channel-pool-size:3}")
    private int aiChannelPoolSize;

    @Value("${grpc.client.fastapi-client.address}")
    private String fastApiAddress; // "static://host:port" 형식

    private final List<ManagedChannel> aiChannelPool = new ArrayList<>();
    // 채널당 스텁을 한 번만 만들어 캐싱한다 — asyncStubFor/blockingStubFor 가 매 호출마다
    // ExerciseServiceGrpc.newStub(channel)을 새로 짓지 않는다. 이래야 단위 테스트가 옛
    // 방식(exerciseAsyncStub/exerciseBlockingStub 필드를 mock으로 reflection 주입) 그대로
    // aiAsyncStubPool/aiBlockingStubPool 을 List.of(mock)으로 주입할 수 있다 — 채널을
    // mock 하려면 Channel.newCall()까지 흉내내야 해서 훨씬 무겁다.
    private final List<ExerciseServiceGrpc.ExerciseServiceStub> aiAsyncStubPool = new ArrayList<>();
    private final List<ExerciseServiceGrpc.ExerciseServiceBlockingStub> aiBlockingStubPool = new ArrayList<>();

    // aiChannelPool 은 net.devh 의 @GrpcClient 관리 밖에서 ManagedChannelBuilder 로 직접 만들어져
    // GrpcObservabilityConfig 의 @GrpcGlobalClientInterceptor 가 안 걸린다(#555) — 인증 헤더처럼
    // 스텁에 수동으로 붙여야 cid 가 AI 로그까지 이어진다. 상태가 없어 인스턴스 하나를 공유해도 안전하다.
    private static final GrpcCorrelationClientInterceptor CORRELATION_INTERCEPTOR = new GrpcCorrelationClientInterceptor();

    // 🔴 실측(2026-08-26)에서 잡힌 버그: 처음엔 채널 3개를 전부 같은 포트로 만들었다.
    //    AI가 SO_REUSEPORT(포트 공유)였을 때는 커널이 그래도 분산시켜줘서 우연히 맞았는데,
    //    AI를 포트별로 분리(8585/8586/8587, entrypoint.sh)한 뒤에는 채널 3개가 전부 8585
    //    (=워커 0)로만 가서 세션 6개가 전부 같은 프로세스로 몰렸다 — 실측: pid=7 6/6.
    //    채널 인덱스 i는 반드시 gRPC 포트 base+i 와 짝을 맞춰야 한다.
    @PostConstruct
    private void initAiChannelPool() {
        String hostPort = fastApiAddress.replaceFirst("^static://", "");
        String host = hostPort.substring(0, hostPort.lastIndexOf(':'));
        int basePort = Integer.parseInt(hostPort.substring(hostPort.lastIndexOf(':') + 1));
        for (int i = 0; i < aiChannelPoolSize; i++) {
            int port = basePort + i;
            ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
            aiChannelPool.add(channel);
            aiAsyncStubPool.add(ExerciseServiceGrpc.newStub(channel));
            aiBlockingStubPool.add(ExerciseServiceGrpc.newBlockingStub(channel));
            log.info("AI gRPC 채널[{}] 초기화 완료 (대상: {}:{})", i, host, port);
            // 워커별 서킷을 여기서 미리 만들어둔다(#556) — 안 만들면 그 워커로 첫 호출이 갈
            // 때까지 /actuator/health 의 circuitBreakers 에 안 잡혀, "워커 하나가 계속
            // 조용하다"를 관측으로 구분할 수 없다.
            CircuitBreaker workerBreaker = circuitBreakerRegistry.circuitBreaker("aiServer-" + i);

            // 서킷브레이커 OPEN 자동 재부착 (#581, ai-channel-pool-hardening.md §3-1 ㄴ).
            // 워커 i 가 죽으면(entrypoint.sh 의 wait -n 로 컨테이너 전체가 같이 죽는다) 그
            // 워커로 가던 호출이 연달아 실패해 이 서킷이 OPEN 된다 — 그 전이 자체를 "장애
            // 감지"로 쓴다. 람다 캡처를 위해 루프 변수를 지역 final 로 복사한다.
            final int workerIndex = i;
            workerBreaker.getEventPublisher().onStateTransition(event -> {
                if (event.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
                    self.enqueueReattachForWorker(workerIndex);
                }
            });
        }
    }

    // initAiChannelPool 과 대칭 — 없으면 ManagedChannel 이 쥔 커넥션·스레드가 컨텍스트 종료
    // 후에도 정리되지 않은 채 프로세스 종료에만 기댄다. shutdownNow()로 강제 종료하는
    // 이유: 진행 중인 프레임 요청이 있어도 앱 컨텍스트가 이미 내려가는 중이라 우아하게
    // 끝날 때까지 기다려줄 대상(다음 요청을 받을 서비스)이 없다 — stopAnalysis 의 동기
    // gRPC 호출(getAuthenticatedBlockingStub)이 여기 걸려 있다면 타임아웃(5초)까지 막느니
    // 즉시 끊는 편이 종료를 안 늘린다.
    @PreDestroy
    private void shutdownAiChannelPool() {
        for (int i = 0; i < aiChannelPool.size(); i++) {
            ManagedChannel ch = aiChannelPool.get(i);
            ch.shutdownNow();
            try {
                if (!ch.awaitTermination(3, TimeUnit.SECONDS)) {
                    log.warn("AI gRPC 채널[{}] 3초 내 종료 안 됨 — 그냥 넘어간다", i);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("AI gRPC 채널[{}] 종료 대기 중 인터럽트", i);
            }
        }
        log.info("AI gRPC 채널 풀 {}개 종료 완료", aiChannelPool.size());
    }

    private ExerciseServiceGrpc.ExerciseServiceStub asyncStubFor(long routingKey) {
        return aiAsyncStubPool.get(Math.floorMod(routingKey, aiChannelPoolSize));
    }

    private ExerciseServiceGrpc.ExerciseServiceBlockingStub blockingStubFor(long routingKey) {
        return aiBlockingStubPool.get(Math.floorMod(routingKey, aiChannelPoolSize));
    }

    // AI가 죽지 않고 그냥 응답을 안 주는(hang) 경우, 데드라인 없이는 onNext/onError
    // 둘 다 안 불려서 서킷브레이커가 그 호출을 영원히 실패/느림으로 못 잡음. 셋 다
    // "빠른 ack" 성격의 제어 호출이라 5초로 통일(실측 튜닝된 값 아닌 보수적 기본값).
    private static final long GRPC_CALL_TIMEOUT_SECONDS = 5;

    // 워커별로 서킷을 분리한다 (#556, docs/decisions/circuit-breaker-worker-aggregation.md).
    //
    // 예전엔 인스턴스 하나("aiServer")를 세 호출(추출·분석시작·중단)이 공유했다 — 그때는
    // "워커"가 없었으니 맞는 설계였다. 채널 풀(883f508)이 워커 3개를 들여오면서 전제가
    // 깨졌는데, 실측(JUnit, 위 문서 §2-1)으로 두 가지가 다 확인됐다:
    //   - 균등 트래픽에서 워커 1개가 hang이면 실패율이 33%로 임계값(50%) 아래라 서킷이
    //     "영원히" 안 열려 그 워커가 무방비로 방치된다
    //   - 트래픽이 한 워커로 쏠리면 반대로 그 워커의 실패가 서킷을 열어 정상 워커까지 막는다
    // 라우팅 키(session_id/exercise_id, asyncStubFor/blockingStubFor 와 같은 키)로 채널을
    // 고르는 것과 동일한 방식으로 서킷도 나눠, 워커 하나의 장애가 그 워커의 서킷에만 반영되게
    // 한다. 이름에 워커 수를 박지 않은 이유는 application.yml 의 resilience4j 설정을
    // "configs.default"로 둬서다 — AI_WORKER_COUNT 가 바뀌어도 설정을 안 늘려도 된다.
    int aiChannelIndexFor(long routingKey) {
        return Math.floorMod(routingKey, aiChannelPoolSize);
    }

    private CircuitBreaker aiCircuitBreaker(long routingKey) {
        return circuitBreakerRegistry.circuitBreaker("aiServer-" + aiChannelIndexFor(routingKey));
    }

    /**
     * AI 가 «내려갔다» 가 아니라 «이 요청을 거절했다» 인가.
     *
     * <p>둘을 가르는 이유는 서킷브레이커 집계다. 서킷은 <b>상대의 건강</b>을 재는 장치인데,
     * 요청이 틀려서 거절당한 것은 상대가 멀쩡하다는 증거에 가깝다. 같이 세면 관리자가 잘못
     * 켠 종목(이슈 #147) 하나 때문에 서킷이 열려 <b>정상 종목까지 막힌다</b>.
     *
     * <p>{@code INVALID_ARGUMENT} 하나만 본다. {@code UNAVAILABLE}·{@code DEADLINE_EXCEEDED}
     * 등은 그대로 건강 신호로 남겨야 한다 — 넓게 잡으면 진짜 장애를 서킷이 못 보게 된다.
     *
     * <p>{@code io.grpc.Status} 를 import 하지 않고 완전한 이름을 쓰는 이유 — 이 파일은 이미
     * 세션 상태 {@code com.shadowfit.model.exercise.Status} 를 import 하고 있어 단순 이름이
     * 충돌한다.
     */
    private boolean isClientRejection(Throwable t) {
        return t instanceof StatusRuntimeException e
                && e.getStatus().getCode() == io.grpc.Status.Code.INVALID_ARGUMENT;
    }

    // 토큰 fastapi에게 보내고, 데드라인을 걸어 hang 상태도 onError(DEADLINE_EXCEEDED)로
    // 귀결시킨다 — 이래야 서킷브레이커가 hang도 실패로 기록할 수 있음.
    private ExerciseServiceGrpc.ExerciseServiceStub getAuthenticatedStub(long routingKey) {
        Metadata header = new Metadata();
        Metadata.Key<String> authKey = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
        header.put(authKey, "Bearer " + internalToken);

        // .attachHeaders() 호출 시 명확하게 stub 타입을 맞춰줍니다.
        return asyncStubFor(routingKey).withInterceptors(
                io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(header),
                CORRELATION_INTERCEPTOR
        ).withDeadlineAfter(GRPC_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 블로킹 스텁 버전. 데드라인이 특히 중요하다 — 없으면 AI 가 hang 했을 때 발행기 스레드가
     * 무한정 잡혀 폴링 자체가 멈춘다(비동기였다면 스레드는 안 잡혔을 지점).
     */
    private ExerciseServiceGrpc.ExerciseServiceBlockingStub getAuthenticatedBlockingStub(long routingKey) {
        Metadata header = new Metadata();
        Metadata.Key<String> authKey = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
        header.put(authKey, "Bearer " + internalToken);

        return blockingStubFor(routingKey).withInterceptors(
                io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(header),
                CORRELATION_INTERCEPTOR
        ).withDeadlineAfter(GRPC_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * [STEP 1: 기준 데이터 등록]
     * 사용자가 선택한 유튜브 URL에서 AI가 스켈레톤 좌표를 추출하도록 요청합니다. -- 등록하는건 관리자용
     */
    public void extractReferencePoses(Long exerciseId,String youtubeUrl) {

        Exercise exercise = exercisesRepository.findByIdCached(exerciseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXERCISE_NOT_FOUND));

        if (youtubeUrl == null || youtubeUrl.isEmpty()) {
            log.error("전달된 기준 영상 URL이 없습니다.");
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        com.shadowfit.grpc.ExtractRequest request = com.shadowfit.grpc.ExtractRequest.newBuilder()
                .setExerciseId(exerciseId)
                .setYoutubeUrl(youtubeUrl) // ✅ 직접 삽입된 URL 사용
                .build();

        log.info("FastAPI에게 기준 좌표 추출 요청 전송 - 운동 ID: {}", exerciseId);

        CircuitBreaker cb = aiCircuitBreaker(exerciseId);
        if (!cb.tryAcquirePermission()) {
            log.warn("AI 서버 서킷브레이커 OPEN — 기준 좌표 추출 요청 스킵 (운동 ID: {})", exerciseId);
            return;
        }
        long callStart = System.nanoTime();

        // preserving(): 아래 콜백들은 gRPC 이벤트 루프 스레드에서 실행돼 호출자 MDC가 없다.
        // 감싸지 않으면 정작 실패 로그(onError)에 correlation id 가 안 붙는다.
        //
        // 라우팅 키로 sessionId 가 아니라 exerciseId 를 쓴다 — 실수가 아니다. 이 호출은
        // 세션과 무관한 관리자용 배치 작업(기준 영상에서 좌표 추출)이라 AI 프로세스 상태
        // (검출기·세션 레지스트리)를 전혀 안 쓴다. 즉 스티키가 필요 없어 아무 채널이나
        // 골라도 되고, exerciseId 는 그저 «어느 채널이든 결정적으로 고르는» 용도다.
        getAuthenticatedStub(exerciseId).extractReferenceData(request, CorrelationIds.preserving(new StreamObserver<com.shadowfit.grpc.ExtractResponse>() {
            @Override
            public void onNext(com.shadowfit.grpc.ExtractResponse value) {
                cb.onSuccess(System.nanoTime() - callStart, TimeUnit.NANOSECONDS);
                log.info("FastAPI 추출 시작 응답 수신 - 운동 ID: {}", value.getExerciseId());
            }
            @Override
            public void onError(Throwable t) {
                cb.onError(System.nanoTime() - callStart, TimeUnit.NANOSECONDS, t);
                log.error("좌표 추출 gRPC 통신 장애: {}", t.getMessage());
            }
            @Override
            public void onCompleted() {
                log.info("좌표 추출 gRPC 요청 완료");
            }
        }));
    }

    /**
     * 세션 시작이 클라에 돌려줘야 하는 것.
     *
     * <p>예전엔 {@code Long sessionId} 하나였는데 (#187 안 (d))로 <b>소유권 비밀값</b>이 붙으면서
     * 둘이 됐다. 엔티티를 그대로 반환하지 않는 이유는 {@code open-in-view: false} 라 컨트롤러에서
     * lazy 접근이 터지기 때문이다 — 필요한 두 값만 트랜잭션 안에서 꺼내 담는다.
     *
     * @param sessionNonce {@code null} 이 아니다. 이 경로로 만들어진 세션은 항상 값을 갖는다
     *                     (NULL 인 것은 이 기능 배포 전에 시작된 세션뿐, V8 참조)
     * @param startTime <b>저장된 값</b>이다 (#467). 예전엔 이 필드가 없어서 컨트롤러가 응답을
     *                  만들며 {@code LocalDateTime.now()} 를 <b>새로 읽어</b> 실었고, 그러면
     *                  세션이 저장된 시각과 클라가 받는 시각이 다른 {@code now()} 호출이 되어
     *                  초 경계에서 1초 어긋났다(2026-08-23 실측: 응답 13:21:04 · DB 13:21:05).
     *                  <p>표시용 시각이 아니라서 아프다 — 이 값은 {@code pose_data} 의 멱등
     *                  앵커이자 파티션 키이고(#188 · #392), 리포트·재부착 조회가 <b>등호</b>로
     *                  찾는 바로 그 값이다. 「받은 시각 = 저장된 시각」이 성립해야 한다.
     */
    public record StartedSession(Long sessionId, String sessionNonce, LocalDateTime startTime, int aiWorkerIndex) {}

    /**
     * [STEP 2: 운동 분석 시작 - Entry Point]
     * 앱의 요청을 받아 DB에 세션을 생성하고 즉시 세션 ID와 소유권 비밀값을 반환합니다. (응답 속도 최적화)
     */
    @Transactional
    public StartedSession startAnalysis(VideoRequestDto appDto, Long currentMemberId) {
        Member member = memberRepository.findById(currentMemberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        String finalUrl = member.getPreferredUrl();

        if (finalUrl == null || finalUrl.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        Session savedSession = sessionService.createSession(appDto, currentMemberId, finalUrl);
        Long sessionId = savedSession.getId();
        String persona = member.getSelectedPersona().name();

        // 비동기로 FastAPI에 분석 요청 — self를 거쳐야 @Async가 Spring 프록시를 타고 실제로
        // 비동기 실행됨. this.로 호출하면 자기호출(self-invocation)이라 AOP 프록시를 우회해서
        // @Async가 조용히 무시되고 동기 실행되는 문제가 있었음(2026-07-24, 테스트로 발견).
        //
        // ⚠️ CodeRabbit 지적으로 추가 수정(2026-07-24): self.로 진짜 비동기가 되면서 세션 INSERT가
        // 커밋되기 전에 이 비동기 작업이 먼저 실행될 수 있는 레이스가 새로 생김 — 서킷 OPEN/gRPC
        // 에러 시 sendAnalysisRequestToFastApi가 markAsFailedIfStillInProgress로 세션을 찾는데,
        // 아직 커밋 전이라 못 찾으면 조용히 no-op(스케줄러 30분+ 타임아웃까지 방치). endSession→
        // stopAnalysis와 동일하게 afterCommit 이후로 미뤄서 방지.
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        self.sendAnalysisRequestToFastApi(sessionId, appDto, finalUrl, persona,
                                savedSession.getSessionNonce());
                    }
                }
        );

        // startTime 은 **저장된 엔티티에서** 꺼낸다 (#467). 여기서 now() 를 새로 읽으면
        // 클라가 받는 값이 DB 와 갈린다 — Session 의 @PrePersist 가 초 이하를 자르므로(#446)
        // 이 값은 이미 DB 에 박힌 것과 같은 값이다.
        return new StartedSession(sessionId, savedSession.getSessionNonce(),
                savedSession.getStartTime(), Math.floorMod(sessionId, aiChannelPoolSize));
    }

    /**
     * [STEP 3: 비동기 gRPC 데이터 전송]
     * DB에서 기준 좌표(Reference)를 조회하여 FastAPI 서버로 전송합니다.
     */
    @Async("applicationTaskExecutor")
    @Transactional(readOnly = true)
    public void sendAnalysisRequestToFastApi(Long sessionId, VideoRequestDto appDto, String finalUrl, String persona,
                                             String sessionNonce) {
        // 여기는 이미 @Async 워커 스레드 — cid 는 AsyncConfig 의 TaskDecorator 가 넘겨줬고,
        // 세션 id 는 이 흐름의 시작점인 여기서 얹는다.
        try (CorrelationIds.Scope ignored = CorrelationIds.withSession(sessionId)) {
            log.info("비동기 분석 요청 시작 - 세션 ID: {}", sessionId);

            List<ExerciseReference> referencePoses = referenceRepository.findByExerciseId(appDto.getExerciseId());

            // nonce 는 호출부에서 인자로 받는다 — 여기서 세션을 다시 읽지 않는 것은 쿼리 하나를
            // 아끼려는 게 아니라, «클라에 나간 값» 과 «AI 에 가는 값» 이 같은 한 곳에서 나오게
            // 하려는 것이다. 여기서 다시 읽으면 두 경로가 서로 다른 시점의 행을 볼 수 있다.
            //
            // proto3 라 null 은 못 싣는다. 빈 문자열이 곧 «없음» 이고, AI 는 그것을 compat 통과로
            // 읽는다 (#187 1단계). 이 경로로 만든 세션은 항상 값이 있으므로 실제로는 안 비지만,
            // 재부착 경로에는 배포 전 세션이 올 수 있다.
            AnalyzeRequest.Builder requestBuilder = AnalyzeRequest.newBuilder()
                    .setExerciseId(appDto.getExerciseId())
                    .setSessionId(sessionId)
                    .setReferenceSource(finalUrl)
                    .setPersona(persona)
                    .setSessionNonce(sessionNonce == null ? "" : sessionNonce);

            for (ExerciseReference ref : referencePoses) {
                requestBuilder.addReferencePoses(PoseDataRequest.newBuilder()
                        .setTimestampSec(ref.getTimestampSec())
                        .setJointCoordinates(ref.getJointCoordinates())
                        .build());
            }

            CircuitBreaker cb = aiCircuitBreaker(sessionId);
            if (!cb.tryAcquirePermission()) {
                log.warn("AI 서버 서킷브레이커 OPEN — 분석 시작 요청 스킵 (세션 ID: {})", sessionId);
                // 스킵된 세션을 IN_PROGRESS로 방치하면 SessionTimeoutScheduler 버퍼(기본 30분+)가
                // 돌 때까지 사용자가 응답 없는 세션을 붙들고 있게 됨 — AI가 이미 죽은 걸 아는
                // 상황이니 여기서 바로 FAILED 처리해서 사용자 피드백을 앞당긴다.
                if (sessionService.markAsFailedIfStillInProgress(sessionId, LocalDateTime.now())) {
                    sessionMetrics.sessionTransition(Status.FAILED, "circuit-open");
                }
                return;
            }
            long callStart = System.nanoTime();

            getAuthenticatedStub(sessionId).startAnalysis(requestBuilder.build(), CorrelationIds.preserving(new StreamObserver<AnalyzeResponse>() {
                @Override
                public void onNext(AnalyzeResponse value) {
                    cb.onSuccess(System.nanoTime() - callStart, TimeUnit.NANOSECONDS);
                    log.info("FastAPI 응답 수신 - 세션: {}", value.getSessionId());
                }
                @Override
                public void onError(Throwable t) {
                    // INVALID_ARGUMENT 는 «AI 가 아프다» 가 아니라 «이 요청이 틀렸다» 다.
                    // ai-server 가 분석기 없는 종목을 거절할 때 이 코드로 온다(이슈 #147).
                    // 서킷에 실패로 기록하면 관리자가 잘못 켠 종목을 사용자가 몇 번 시도하는
                    // 것만으로 서킷이 열려(sliding=10·min=5·threshold=50%) **정상 스쿼트 세션까지
                    // 10초간 막힌다.** 건강 신호가 아니므로 권한만 반납하고 집계에서 뺀다.
                    if (isClientRejection(t)) {
                        cb.releasePermission();
                        log.error("AI 가 요청을 거절했다 (세션 {}): {}", sessionId, t.getMessage());
                    } else {
                        cb.onError(System.nanoTime() - callStart, TimeUnit.NANOSECONDS, t);
                        log.error("gRPC 통신 장애: {}", t.getMessage());
                    }
                    // 이 한 번의 호출이 실패한 것(장애가 죽 이어져 서킷이 OPEN 되기 전이라도)도
                    // 사용자 입장에선 응답 없는 세션이므로 동일하게 즉시 FAILED 처리.
                    //
                    // notifyAi=true — gRPC 에러는 "실패"가 아니라 "모름"이다. 연결이 아예 안 됐을
                    // 수도 있지만, AI 가 요청을 받아 SessionState 를 만들고 응답만 못 돌아왔을 수도
                    // 있다. 후자면 통보하지 않는 한 그 상태가 그대로 남는다 (이슈 #98). 헛방이어도
                    // AI 가 success=false 를 주고 TERMINAL_FAILED 로 한 번에 끝나므로 안전한 쪽이다.
                    // (서킷 OPEN 분기는 반대다 — 거긴 아예 보내지 않았으므로 통보하지 않는다.)
                    if (sessionService.markAsFailedIfStillInProgress(sessionId, LocalDateTime.now(), true)) {
                        sessionMetrics.sessionTransition(Status.FAILED, "grpc-error");
                    }
                }
                @Override
                public void onCompleted() {
                    log.info("FastAPI 전송 완료");
                }
            }));
        }
    }

    /**
     * [STEP 3-R: 세션 재부착] 이미 IN_PROGRESS 인 세션의 AI 분석 상태를 DB 값으로 되살린다.
     * (이슈 #59 2단계, docs/decisions/session-resume-and-ai-state.md)
     *
     * <p>[왜 필요한가] 세션 row 는 MySQL 에 있는데 분석 상태는 AI 프로세스 메모리에만 있다. 앱이
     * 재시작하면 클라가 sessionId 를 잃고(1단계 {@code GET /sessions/active} 로 되찾는다), AI 가
     * 재시작하면 상태 자체가 증발한다. 둘 중 어느 쪽이든 DB 는 멀쩡히 IN_PROGRESS 라 클라는 이어할 수
     * 있다고 믿는데 AI 는 프레임을 전부 거부한다.
     *
     * <p>[왜 동기인가] {@code sendAnalysisRequestToFastApi}(시작)는 fire-and-forget 이어도 됐다 —
     * 클라는 어차피 프레임을 보내기 시작하면 되니까. 재부착은 <b>다르다.</b> 클라가 "이어할 수 있는지"를
     * 알아야 프레임을 보낼지 새로 시작할지 정한다. 성공/실패가 곧 응답이라 블로킹 스텁을 쓴다.
     * 사용자 요청 스레드가 최대 {@code GRPC_CALL_TIMEOUT_SECONDS} 대기하지만, 재부착은 세션당 드물게
     * 일어나는 복구 경로라 상시 처리량에 영향을 주지 않는다.
     *
     * <p>[실패 시 세션을 FAILED 로 바꾸지 않는 이유] 시작 경로는 AI 가 죽으면 즉시 FAILED 로 돌려
     * 사용자를 풀어준다(응답 없는 빈 세션을 붙들고 있을 이유가 없으므로). 재부착은 반대다 — 되살릴 수
     * 있는 rep 이 pose_data 에 이미 쌓여 있는데 일시적 gRPC 실패로 세션을 걷어버리면, <b>이 기능이
     * 지키려던 것을 이 기능이 없애는</b> 셈이 된다. 503 으로 돌려주고 세션은 그대로 둔다. 재시도는
     * 멱등하고(AI 쪽 already_active 가드), 방치되더라도 타임아웃 스케줄러가 상한을 준다.
     *
     * <p>[왜 트랜잭션 밖에서 gRPC 를 하는가] DB 작업은 {@link #loadReattachRequest} 안에서 끝내고
     * 커넥션을 반납한 뒤에 gRPC 를 호출한다. 한 트랜잭션 안에서 호출하면 커넥션을 쥔 채로 최대
     * {@code GRPC_CALL_TIMEOUT_SECONDS} 를 기다리게 되어, AI 가 느려지는 순간 <b>재부착과 무관한
     * 요청까지</b> 풀 고갈로 막힌다(풀 15, connection-timeout 30초). 재부착은 드물지만 <b>몰릴 때
     * 몰린다</b> — AI 재시작 직후에는 살아있던 세션들이 한꺼번에 들어온다. 이슈 #76.
     *
     * @return 복원 결과. {@code alreadyActive} 면 AI 상태가 살아있어 아무것도 하지 않은 것이다.
     * @throws BusinessException 검증 실패는 {@code SessionService.findReattachableSession} 계약을 따르고,
     *                           AI 연결 실패는 {@code SESSION_REATTACH_UNAVAILABLE}
     */
    public ReattachSessionResponseDto reattachSession(Long sessionId, Long currentMemberId) {
        try (CorrelationIds.Scope ignored = CorrelationIds.withSession(sessionId)) {
            // ReattachRequestBuilder 는 별도 빈이라 @Transactional 이 그 빈 자신의 프록시를 타고
            // 정상 적용된다 — self 주입 없이도 걸린다(이슈 #175).
            ReattachRequest request = reattachRequestBuilder.build(sessionId, currentMemberId);
            // ↑ 여기서 트랜잭션이 끝나고 커넥션이 반납된다. 아래 gRPC 는 커넥션을 쥐지 않는다.

            CircuitBreaker cb = aiCircuitBreaker(sessionId);
            if (!cb.tryAcquirePermission()) {
                log.warn("AI 서버 서킷브레이커 OPEN — 재부착 실패 (세션 ID: {})", sessionId);
                throw new BusinessException(ErrorCode.SESSION_REATTACH_UNAVAILABLE);
            }

            long callStart = System.nanoTime();
            ReattachResponse response;
            try {
                response = getAuthenticatedBlockingStub(sessionId).reattachAnalysis(request);
            } catch (StatusRuntimeException e) {
                cb.onError(System.nanoTime() - callStart, TimeUnit.NANOSECONDS, e);
                log.error("재부착 gRPC 통신 장애 - 세션 ID: {}, 사유: {}", sessionId, e.getMessage());
                throw new BusinessException(ErrorCode.SESSION_REATTACH_UNAVAILABLE);
            }
            cb.onSuccess(System.nanoTime() - callStart, TimeUnit.NANOSECONDS);

            if (!response.getSuccess()) {
                // AI 가 요청은 받았으나 상태를 못 만든 경우(기준 좌표 파싱 실패 등). 통신은 성공했으므로
                // 서킷에는 실패로 치지 않되, 사용자에겐 같은 "지금은 못 이어한다"로 보인다.
                log.warn("AI 가 재부착을 거절 - 세션 ID: {}, 사유: {}", sessionId, response.getMessage());
                throw new BusinessException(ErrorCode.SESSION_REATTACH_UNAVAILABLE);
            }

            log.info("세션 재부착 완료 - 세션 ID: {}, rep: {}, 이미활성: {}",
                    sessionId, response.getRepCount(), response.getAlreadyActive());

            // rep 수는 AI 응답을 신뢰한다 — already_active 면 살아있던 상태의 현재 값이 진실이고,
            // 그때 DB 값은 아직 넘어오지 않은 진행 중 rep 만큼 뒤처져 있을 수 있다.
            // 클라가 세션을 잃었다 재부착으로 돌아오는 경우가 있으므로 nonce 도 같이 돌려준다.
            // 값의 출처는 방금 조립한 요청이다 — DB 를 다시 읽지 않아야 AI 에 보낸 값과 같음이 보장된다.
            // 빈 문자열(배포 전 세션)은 null 로 돌려준다 — «없음» 을 JSON 에서 빈 문자열로 흉내내면
            // 클라가 그걸 동봉해 «틀린 nonce» 가 된다.
            String reattachNonce = request.getSessionNonce().isEmpty() ? null : request.getSessionNonce();
            return ReattachSessionResponseDto.of(
                    sessionId, response.getRepCount(), response.getAlreadyActive(), reattachNonce,
                    Math.floorMod(sessionId, aiChannelPoolSize));
        }
    }

    /**
     * [STEP 4: AI 분석 중단 신호 송신] — 아웃박스 발행기가 호출하는 <b>동기</b> 송신.
     *
     * <p>[왜 동기인가] 이전에는 {@code endSession} 의 afterCommit 에서 fire-and-forget 으로 불렀고,
     * 호출자가 <b>사용자 요청 스레드</b>였으므로 비동기가 맞았다(응답을 AI 만큼 기다릴 수 없다).
     * 아웃박스가 들어오면서 호출자가 {@code @Scheduled} 발행기 스레드로 바뀌었고, 발행기는 결과를
     * 알아야 행 상태를 정한다(SENT / 재시도 / 터미널). fire-and-forget 으로는 아무것도 못 받는다.
     * 발행기 스레드는 대기해도 뺏길 일이 없어 블로킹 비용이 사실상 0이고, 순차 처리가 재시도·상태전이를
     * 한 곳에 모아준다. (docs/decisions/outbox-reliable-messaging.md §4-2-1)
     *
     * <p>처리량 상한은 "1 / AI 응답시간"이다. 부족해지면 논블로킹 재설계가 아니라 <b>발행기 다중화</b>가
     * 먼저다 — {@code SKIP LOCKED} 가 이미 행 단위 분배를 지원한다.
     *
     * @param possiblyRedelivered 이 행이 <b>이미 한 번 나갔을 수 있는</b> 회수분인가 (이슈 #152).
     *        아웃박스는 at-least-once 라 발행기가 송신 직후·결과 기록 전에 죽으면 lease 만료 후
     *        같은 {@code StopAnalysis} 가 다시 나간다. AI 수신부는 멱등하지 않아서(첫 호출이 세션
     *        상태를 제거한다) <b>두 번째 호출은 반드시 {@code success=false}</b> 로 답한다. 그 값이
     *        "AI 가 세션을 잃었다"가 아니라 "첫 호출이 이미 정상 처리했다"는 뜻일 수 있으므로,
     *        회수분에서는 세션을 FAILED 로 걷어내지 않는다. 아래 분기 참고.
     * @return 발행기가 행 상태로 옮길 결과 3분류. 예외를 던지지 않는다 — 모든 실패가 분류돼 나온다.
     */
    public DispatchOutcome stopAnalysis(Long sessionId, boolean possiblyRedelivered) {
        try (CorrelationIds.Scope ignored = CorrelationIds.withSession(sessionId)) {
            log.info("AI 서버 분석 중단 요청 전송 - sessionId: {}", sessionId);

            StopRequest request = StopRequest.newBuilder().setSessionId(sessionId).build();

            CircuitBreaker cb = aiCircuitBreaker(sessionId);
            if (!cb.tryAcquirePermission()) {
                // 이전에는 여기서 그냥 return 해 통보를 통째로 버렸다(E1 의 두 번째 유실 경로).
                // 하필 AI 가 죽어 통보가 가장 많이 쌓이는 구간이었다. 이제는 행이 PENDING 으로 남아
                // 서킷이 닫힌 뒤 전달된다 — 서킷(빠른 실패)과 아웃박스(지연 후 전달)는 보완재다.
                log.warn("AI 서버 서킷브레이커 OPEN — 중단 요청 보류 (세션 ID: {})", sessionId);
                sessionMetrics.aiStopResult("skipped-circuit-open");
                return DispatchOutcome.RETRY;
            }

            long callStart = System.nanoTime();
            StopResponse response;
            try {
                response = getAuthenticatedBlockingStub(sessionId).stopAnalysis(request);
            } catch (StatusRuntimeException e) {
                cb.onError(System.nanoTime() - callStart, TimeUnit.NANOSECONDS, e);
                sessionMetrics.aiStopResult("grpc-error");
                log.error("AI 서버 중단 실패 - sessionId: {}, status: {}", sessionId, e.getStatus());
                return DispatchOutcome.RETRY;
            } catch (RuntimeException e) {
                // gRPC 실패는 StatusRuntimeException 으로 오지만, 인터셉터·직렬화 등 그 바깥에서 나는
                // 예외도 있다. 여기서 안 잡으면 "예외를 던지지 않는다"는 이 메서드의 계약이 깨지고,
                // 발행기는 결과를 못 받아 행을 PROCESSING 으로 방치한 채 lease 만료까지(60초)
                // 불필요하게 기다리게 된다. 원인이 무엇이든 "지금은 실패, 나중에 재시도"가 맞다.
                cb.onError(System.nanoTime() - callStart, TimeUnit.NANOSECONDS, e);
                sessionMetrics.aiStopResult("error");
                log.error("AI 서버 중단 요청 중 예기치 못한 오류 - sessionId: {}", sessionId, e);
                return DispatchOutcome.RETRY;
            }

            // 서킷브레이커에는 성공으로 기록하는 게 맞다 — 판단 대상은 "AI 서비스가 살아있나"이지
            // "이 세션이 있었나"가 아니다. 세션을 잃은 AI도 새 분석은 정상 처리하므로, 여기서
            // 서킷을 열면 신규 startAnalysis 까지 막혀 더 나빠진다.
            cb.onSuccess(System.nanoTime() - callStart, TimeUnit.NANOSECONDS);

            // 전송 층(응답이 왔나)과 업무 층(그 응답이 성공인가)은 별개다. AI는 세션 상태를 못 찾으면
            // gRPC 에러가 아니라 success=false 인 정상 응답을 준다(exercise_servicer.py StopAnalysis).
            if (response.getSuccess()) {
                sessionMetrics.aiStopResult("ok");
                log.info("AI 서버 응답: {}", response.getMessage());
                return DispatchOutcome.SENT;
            }

            // 회수분이면 이 success=false 를 «유실» 로 읽을 수 없다 (이슈 #152). 두 가지가 겹쳐
            // 있는데 응답만으로는 안 갈린다:
            //   (가) 첫 송신이 이미 정상 처리됐다 → 완료 콜백이 오는 중이다. 걷어내면 안 된다
            //   (나) AI 가 재시작 등으로 세션을 정말 잃었다 → 걷어내는 게 맞다
            //
            // 2026-08-12(#191): AI 가 종료한 세션 id 를 66초간 기억하게 되면서, 그 안에 온
            // 재송신은 여기까지 오지 않는다 — success=true 로 위 분기에서 SENT 로 끝난다.
            // 여기 남는 것은 «보유 기간을 넘겨 도착한 (가)» 와 «진짜 (나)» 이고, 둘은 여전히
            // 응답만으로 안 갈린다. 그래서 이 보수 분기를 떼면 안 된다 — 떼는 순간 늦게 온
            // 재송신이 정상 세션을 FAILED 로 뒤집는다.
            // (가) 에서 걷어내면 정상 세션이 FAILED 로 잠깐 뒤집히고, 무엇보다 «실패» 지표가
            // 거짓으로 올라간다. (나) 를 놓쳐도 타임아웃 스케줄러가 여전히 잡으므로 — 빠른 실패라는
            // 최적화만 포기하는 것이지 안전망이 사라지지는 않는다. 그래서 안 걷어내는 쪽이 안전하다.
            if (possiblyRedelivered) {
                sessionMetrics.aiStopResult("session-missing-redelivery");
                log.info("회수분 재송신에 세션 상태 없음 — 첫 송신이 이미 처리됐을 수 있어 세션을 "
                        + "건드리지 않는다 (sessionId: {}, 응답: {})", sessionId, response.getMessage());
                return DispatchOutcome.TERMINAL_FAILED;
            }

            sessionMetrics.aiStopResult("session-missing");
            log.warn("AI 에 세션 상태 없음 — 분석 결과 회수 불가 (sessionId: {}, 응답: {})",
                    sessionId, response.getMessage());
            failSessionFast(sessionId, "ai-session-missing");

            // 재시도해도 AI 는 그 세션을 영영 모른다 — 터미널이다. SENT 로 찍으면 실제 결과 유실을
            // "전송 성공"으로 위장하게 된다.
            return DispatchOutcome.TERMINAL_FAILED;
        }
    }

    /**
     * 워커 {@code workerIndex} 의 서킷브레이커가 OPEN 으로 전이하는 순간 호출된다(#581).
     * 그 워커로 라우팅되던(= {@code Math.floorMod(sessionId, aiChannelPoolSize) == workerIndex})
     * IN_PROGRESS 세션마다 {@code REATTACH_ANALYSIS} 아웃박스 행을 하나씩 남긴다 — 실제 gRPC
     * 재부착 호출은 여기서 하지 않는다(서킷이 막 열린 시점이라 어차피 실패한다). 발행기가
     * 폴링하며 재시도·백오프를 처리하다가, 컨테이너가 재기동돼 서킷이 다시 닫히면 그때 성공한다.
     *
     * <p>IN_PROGRESS 전체를 훑고 자바에서 걸러내는 이유는 {@code findTimeoutCandidatesByStatus}
     * (스케줄러)와 같다 — 이 규모(DAU 1,000 가정 최대 116)에서 워커별 전용 쿼리를 새로 만들
     * 값어치가 없다.
     *
     * <p>서킷브레이커는 CLOSED↔OPEN↔HALF_OPEN 을 짧은 시간에 여러 번 오갈 수 있어(플래핑),
     * 같은 세션에 중복으로 큐잉하지 않도록 이미 PENDING/PROCESSING 인 행이 있으면 건너뛴다.
     */
    @Transactional
    public void enqueueReattachForWorker(int workerIndex) {
        try (CorrelationIds.Scope task = CorrelationIds.startTask("ai-cb-open-w" + workerIndex)) {
            List<Long> targets = sessionRepository.findIdsByStatus(Status.IN_PROGRESS).stream()
                    .filter(id -> Math.floorMod(id, aiChannelPoolSize) == workerIndex)
                    .toList();

            int queued = 0;
            for (Long sessionId : targets) {
                if (outboxEventRepository.existsByAggregateIdAndEventTypeAndStatusIn(
                        sessionId, OutboxEventType.REATTACH_ANALYSIS,
                        List.of(OutboxStatus.PENDING, OutboxStatus.PROCESSING))) {
                    continue;
                }
                outboxEventRepository.save(OutboxEvent.reattachAnalysis(sessionId, CorrelationIds.current()));
                queued++;
            }
            if (queued > 0) {
                log.warn("AI 워커[{}] 서킷 OPEN — IN_PROGRESS {}건 중 {}건 재부착 큐잉(나머지는 이미 대기 중)",
                        workerIndex, targets.size(), queued);
            }
        }
    }

    /**
     * 아웃박스 발행기가 호출하는 재부착 실행부(#581). {@link #reattachSession}(사용자 요청,
     * 동기, 예외로 실패를 알림)과 대칭이지만 계약이 다르다 — 여기는 예외를 던지지 않고
     * {@link DispatchOutcome} 으로만 답한다({@link #stopAnalysis} 와 같은 이유).
     *
     * @return SENT(재부착 성공, already_active 포함) · RETRY(서킷 OPEN·gRPC 오류, 나중에 재시도) ·
     *         TERMINAL_FAILED(더 이상 이어붙일 대상이 아니거나, AI 가 명시적으로 거절)
     */
    public DispatchOutcome reattachFromOutbox(Long sessionId) {
        try (CorrelationIds.Scope ignored = CorrelationIds.withSession(sessionId)) {
            ReattachRequest request;
            try {
                request = reattachRequestBuilder.buildById(sessionId);
            } catch (BusinessException e) {
                // SESSION_NOT_FOUND(이미 끝남·삭제됨) 또는 SESSION_REATTACH_EXPIRED(타임아웃
                // 스케줄러가 먼저 걷어감) — 재시도해도 결과가 같다.
                sessionMetrics.aiReattachResult("not-reattachable");
                log.info("자동 재부착 대상 아님 - 세션 ID: {}, 사유: {}", sessionId, e.getMessage());
                return DispatchOutcome.TERMINAL_FAILED;
            }

            CircuitBreaker cb = aiCircuitBreaker(sessionId);
            if (!cb.tryAcquirePermission()) {
                sessionMetrics.aiReattachResult("circuit-open");
                return DispatchOutcome.RETRY;
            }

            long callStart = System.nanoTime();
            ReattachResponse response;
            try {
                response = getAuthenticatedBlockingStub(sessionId).reattachAnalysis(request);
            } catch (StatusRuntimeException e) {
                cb.onError(System.nanoTime() - callStart, TimeUnit.NANOSECONDS, e);
                sessionMetrics.aiReattachResult("grpc-error");
                log.warn("자동 재부착 gRPC 실패 - 세션 ID: {}, 사유: {}", sessionId, e.getMessage());
                return DispatchOutcome.RETRY;
            }
            cb.onSuccess(System.nanoTime() - callStart, TimeUnit.NANOSECONDS);

            if (!response.getSuccess()) {
                // 통신은 성공했는데 AI 가 상태를 못 만든 경우(기준 좌표 파싱 실패 등) — 재시도해도
                // 같은 결과다. reattachSession(사용자 경로)과 대칭 판단.
                sessionMetrics.aiReattachResult("ai-rejected");
                log.warn("AI 가 자동 재부착을 거절 - 세션 ID: {}, 사유: {}", sessionId, response.getMessage());
                return DispatchOutcome.TERMINAL_FAILED;
            }

            sessionMetrics.aiReattachResult(response.getAlreadyActive() ? "already-active" : "ok");
            log.info("자동 재부착 완료 - 세션 ID: {}, rep: {}, 이미활성: {}",
                    sessionId, response.getRepCount(), response.getAlreadyActive());
            return DispatchOutcome.SENT;
        }
    }

    /**
     * CompleteAnalysis 가 오지 않는 게 확정된 세션을 즉시 FAILED 로 걷어낸다 — 타임아웃 스케줄러
     * (시작시간+예상시간+버퍼)를 기다릴 이유가 없다. startAnalysis 가 같은 상황에서 하는 처리와 대칭.
     *
     * @param source 지표 태그 — "같은 FAILED라도 어느 흐름이 걷었는지"가 운영상 다른 사건이므로
     *               호출부마다 구분한다({@link SessionMetrics#sessionTransition}).
     */
    private void failSessionFast(Long sessionId, String source) {
        try {
            if (sessionService.markAsFailedIfStillInProgress(sessionId, LocalDateTime.now())) {
                sessionMetrics.sessionTransition(Status.FAILED, source);
            }
        } catch (ObjectOptimisticLockingFailureException e) {
            // 늦게 도착한 완료 콜백이 같은 세션을 동시에 갱신한 것 — 결과 데이터가 더 가치있으므로
            // 양보한다(markAsFailedIfStillInProgress 의 계약: "호출 측이 catch 하고 양보",
            // SessionService:248-249. 스케줄러도 같은 정책).
            sessionMetrics.optimisticLockConflict(source, "yield");
            log.info("세션 FAILED 처리 양보 — 완료 콜백 우선 (sessionId: {}, source: {})", sessionId, source);
        }
    }

}

