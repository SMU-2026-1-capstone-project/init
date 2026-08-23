package com.shadowfit.service.Exercise;

import com.shadowfit.dto.exercises.VideoRequestDto;
import com.shadowfit.dto.exercises.session.ReattachSessionResponseDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.observability.CorrelationIds;
import com.shadowfit.global.observability.SessionMetrics;
import com.shadowfit.grpc.*;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseReference;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.outbox.DispatchOutcome;
import com.shadowfit.repository.exercise.ExerciseReferenceRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
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
    // 재부착 시 MAX(rep_number) 복원 전용 (이슈 #59 2단계)
    private final PoseDataRepository poseDataRepository;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final SessionMetrics sessionMetrics;

    // 자기 주입: startAnalysis → sendAnalysisRequestToFastApi 호출이 Spring 프록시를 통과해
    // @Async가 적용되도록 함(:199 주석 참조).
    @Lazy
    @Autowired
    private ExerciseAnalysisService self;

    @Value("${internal.api.token}")
    private String internalToken;

    @GrpcClient("fastapi-client")
    private ExerciseServiceGrpc.ExerciseServiceStub exerciseAsyncStub;

    // 아웃박스 발행기 전용. 나머지 호출(추출·분석시작)은 결과를 안 쓰는 fire-and-forget 이라
    // 비동기 스텁 그대로 두고, 결과가 행 상태를 정하는 중단 송신만 블로킹으로 받는다.
    @GrpcClient("fastapi-client")
    private ExerciseServiceGrpc.ExerciseServiceBlockingStub exerciseBlockingStub;

    // AI가 죽지 않고 그냥 응답을 안 주는(hang) 경우, 데드라인 없이는 onNext/onError
    // 둘 다 안 불려서 서킷브레이커가 그 호출을 영원히 실패/느림으로 못 잡음. 셋 다
    // "빠른 ack" 성격의 제어 호출이라 5초로 통일(실측 튜닝된 값 아닌 보수적 기본값).
    private static final long GRPC_CALL_TIMEOUT_SECONDS = 5;

    // Spring→AI(FastAPI) gRPC 호출 전체가 공유하는 서킷브레이커 — AI가 죽으면
    // 세 호출(추출·분석시작·중단) 모두 같은 상대(AI 서버)로 가는 것이므로 인스턴스 하나로 충분.
    private CircuitBreaker aiCircuitBreaker() {
        return circuitBreakerRegistry.circuitBreaker("aiServer");
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
    private ExerciseServiceGrpc.ExerciseServiceStub getAuthenticatedStub() {
        Metadata header = new Metadata();
        Metadata.Key<String> authKey = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
        header.put(authKey, "Bearer " + internalToken);

        // .attachHeaders() 호출 시 명확하게 stub 타입을 맞춰줍니다.
        return exerciseAsyncStub.withInterceptors(
                io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(header)
        ).withDeadlineAfter(GRPC_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 블로킹 스텁 버전. 데드라인이 특히 중요하다 — 없으면 AI 가 hang 했을 때 발행기 스레드가
     * 무한정 잡혀 폴링 자체가 멈춘다(비동기였다면 스레드는 안 잡혔을 지점).
     */
    private ExerciseServiceGrpc.ExerciseServiceBlockingStub getAuthenticatedBlockingStub() {
        Metadata header = new Metadata();
        Metadata.Key<String> authKey = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
        header.put(authKey, "Bearer " + internalToken);

        return exerciseBlockingStub.withInterceptors(
                io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(header)
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

        CircuitBreaker cb = aiCircuitBreaker();
        if (!cb.tryAcquirePermission()) {
            log.warn("AI 서버 서킷브레이커 OPEN — 기준 좌표 추출 요청 스킵 (운동 ID: {})", exerciseId);
            return;
        }
        long callStart = System.nanoTime();

        // preserving(): 아래 콜백들은 gRPC 이벤트 루프 스레드에서 실행돼 호출자 MDC가 없다.
        // 감싸지 않으면 정작 실패 로그(onError)에 correlation id 가 안 붙는다.
        getAuthenticatedStub().extractReferenceData(request, CorrelationIds.preserving(new StreamObserver<com.shadowfit.grpc.ExtractResponse>() {
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
    public record StartedSession(Long sessionId, String sessionNonce, LocalDateTime startTime) {}

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
                savedSession.getStartTime());
    }

    /**
     * [STEP 3: 비동기 gRPC 데이터 전송]
     * DB에서 기준 좌표(Reference)를 조회하여 FastAPI 서버로 전송합니다.
     */
    @Async
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

            CircuitBreaker cb = aiCircuitBreaker();
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

            getAuthenticatedStub().startAnalysis(requestBuilder.build(), CorrelationIds.preserving(new StreamObserver<AnalyzeResponse>() {
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
            // self 를 거쳐야 프록시를 타고 @Transactional 이 실제로 걸린다(this. 로 부르면 자기호출이라
            // 트랜잭션 없이 실행됨 — 이 클래스가 @Async 에서 이미 겪은 함정, 2026-07-24).
            ReattachRequest request = self.loadReattachRequest(sessionId, currentMemberId);
            // ↑ 여기서 트랜잭션이 끝나고 커넥션이 반납된다. 아래 gRPC 는 커넥션을 쥐지 않는다.

            CircuitBreaker cb = aiCircuitBreaker();
            if (!cb.tryAcquirePermission()) {
                log.warn("AI 서버 서킷브레이커 OPEN — 재부착 실패 (세션 ID: {})", sessionId);
                throw new BusinessException(ErrorCode.SESSION_REATTACH_UNAVAILABLE);
            }

            long callStart = System.nanoTime();
            ReattachResponse response;
            try {
                response = getAuthenticatedBlockingStub().reattachAnalysis(request);
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
                    sessionId, response.getRepCount(), response.getAlreadyActive(), reattachNonce);
        }
    }

    /**
     * [재부착 준비] 재부착 검증 + gRPC 요청 조립까지의 <b>DB 작업 전부</b>를 한 트랜잭션에 가둔다
     * (이슈 #76).
     *
     * <p>이 메서드가 반환되는 시점에 트랜잭션이 끝나고 커넥션이 풀로 돌아간다. 호출부
     * {@link #reattachSession} 은 그 뒤에 gRPC 를 호출하므로 외부 지연이 커넥션 점유로 번지지 않는다.
     *
     * <p><b>lazy 접근을 여기서 끝내야 한다</b> — {@code session.getExercise()}, {@code getMember()} 는
     * 지연 로딩이고 {@code open-in-view: false} 라, 트랜잭션 밖으로 엔티티를 들고 나가면
     * {@code LazyInitializationException} 이 난다. 그래서 엔티티가 아니라 <b>값이 다 채워진</b>
     * {@code ReattachRequest} 를 반환한다.
     *
     * <p>public 인 이유는 {@code self.} 프록시 호출 대상이어야 해서다 — 외부에서 직접 부를 API 가
     * 아니라 {@link #reattachSession} 의 1단계다.
     */
    @Transactional(readOnly = true)
    public ReattachRequest loadReattachRequest(Long sessionId, Long currentMemberId) {
        Session session = sessionService.findReattachableSession(sessionId, currentMemberId);
        Long exerciseId = session.getExercise().getId();

        // 완료된 rep 은 세션 진행 중에 이미 pose_data 로 넘어와 있다(§3-2). AI 메모리가 날아가도
        // 여기서 되찾을 수 있다는 것이 재부착이 성립하는 근거다.
        int restoredRepCount = poseDataRepository.findMaxRepNumberBySessionId(sessionId, session.getStartTime());

        // 시간 축도 rep 축과 똑같이 이어붙여야 한다 (이슈 #156). AI 는 프레임 시각을 «첫 프레임
        // 도착부터의 경과» 로 만드는데, 재부착으로 상태를 새로 만들면 그 기준이 재부착 시점이 되어
        // 이후 프레임의 timestamp_sec 이 0 부터 다시 시작한다. 그러면 리포트의 «최악 구간 시각» 이
        // 세션 앞부분과 겹치는 값으로 표시된다 — 여기서 이미 흐른 시간을 실어 보내 메운다.
        //
        // 값을 **pose_data 에서 되읽는** 것이 핵심이다. 바로 위 rep 축과 같은 데이터원이고, 그래서
        // 원점도 같다 — 저장된 timestamp_sec 자체가 AI 가 «첫 프레임» 을 0 으로 잡아 만든 값이다.
        //
        // session.start_time 기준 경과로 계산하면 안 된다(초판이 그렇게 했다가 고쳤다). 그건 원점이
        // «세션 생성» 이라, AI 가 «운동 시간이 아니다» 라며 의도적으로 뺀 자세 잡는 시간이 다시
        // 들어간다. 준비에 20초 걸린 세션이면 재부착 이후 시각이 통째로 20초 앞서 표시된다.
        double elapsedSec = poseDataRepository.findMaxTimestampSecBySessionId(sessionId, session.getStartTime());

        // AI 는 세션 종료 뒤 상태를 버린다 — 재부착으로 상태를 다시 세울 때 nonce 도 같이
        // 실어야 «검증은 켜졌는데 보관값이 없는» 상태가 안 된다 (#187 (d)).
        // 배포 전에 시작된 세션은 여기가 null 이고, 빈 문자열로 나가 compat 통과가 된다.
        String sessionNonce = session.getSessionNonce();

        ReattachRequest.Builder requestBuilder = ReattachRequest.newBuilder()
                .setSessionId(sessionId)
                .setExerciseId(exerciseId)
                .setPersona(session.getMember().getSelectedPersona().name())
                .setInitialRepCount(restoredRepCount)
                .setElapsedSec(elapsedSec)
                .setSessionNonce(sessionNonce == null ? "" : sessionNonce);

        // 기준 좌표는 AI 가 보관하지 않는다 — 시작 때와 똑같이 Spring 이 DB 에서 읽어 실어 보낸다.
        for (ExerciseReference ref : referenceRepository.findByExerciseId(exerciseId)) {
            requestBuilder.addReferencePoses(PoseDataRequest.newBuilder()
                    .setTimestampSec(ref.getTimestampSec())
                    .setJointCoordinates(ref.getJointCoordinates())
                    .build());
        }

        return requestBuilder.build();
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

            CircuitBreaker cb = aiCircuitBreaker();
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
                response = getAuthenticatedBlockingStub().stopAnalysis(request);
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
            failSessionFast(sessionId);

            // 재시도해도 AI 는 그 세션을 영영 모른다 — 터미널이다. SENT 로 찍으면 실제 결과 유실을
            // "전송 성공"으로 위장하게 된다.
            return DispatchOutcome.TERMINAL_FAILED;
        }
    }

    /**
     * CompleteAnalysis 가 오지 않는 게 확정된 세션을 즉시 FAILED 로 걷어낸다 — 타임아웃 스케줄러
     * (시작시간+예상시간+버퍼)를 기다릴 이유가 없다. startAnalysis 가 같은 상황에서 하는 처리와 대칭.
     */
    private void failSessionFast(Long sessionId) {
        try {
            if (sessionService.markAsFailedIfStillInProgress(sessionId, LocalDateTime.now())) {
                sessionMetrics.sessionTransition(Status.FAILED, "ai-session-missing");
            }
        } catch (ObjectOptimisticLockingFailureException e) {
            // 늦게 도착한 완료 콜백이 같은 세션을 동시에 갱신한 것 — 결과 데이터가 더 가치있으므로
            // 양보한다(markAsFailedIfStillInProgress 의 계약: "호출 측이 catch 하고 양보",
            // SessionService:248-249. 스케줄러도 같은 정책).
            sessionMetrics.optimisticLockConflict("ai-session-missing", "yield");
            log.info("세션 FAILED 처리 양보 — 완료 콜백 우선 (sessionId: {})", sessionId);
        }
    }

}

