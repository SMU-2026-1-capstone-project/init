package com.shadowfit.service.Exercise;

import com.shadowfit.dto.exercises.VideoRequestDto;
import com.shadowfit.dto.exercises.session.SessionUpdateRequestDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.grpc.*;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseReference;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.repository.exercise.ExerciseReferenceRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.Metadata;
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
import org.springframework.web.reactive.function.client.WebClient;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;


@Service
@RequiredArgsConstructor
@Slf4j
public class ExerciseAnalysisService {
    private final WebClient webClient;
    private final SessionRepository sessionRepository;
    private final ExercisesRepository exercisesRepository;
    private final MemberRepository memberRepository;
    private final SessionService sessionService;
    private final ExerciseReferenceRepository referenceRepository;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    // 자기 주입: completeSession → applyCompleteFromApp 호출이 Spring 프록시를 통과하도록 함.
    @Lazy
    @Autowired
    private ExerciseAnalysisService self;

    @Value("${internal.api.token}")
    private String internalToken;

    @GrpcClient("fastapi-client")
    private ExerciseServiceGrpc.ExerciseServiceStub exerciseAsyncStub;

    // AI가 죽지 않고 그냥 응답을 안 주는(hang) 경우, 데드라인 없이는 onNext/onError
    // 둘 다 안 불려서 서킷브레이커가 그 호출을 영원히 실패/느림으로 못 잡음. 셋 다
    // "빠른 ack" 성격의 제어 호출이라 5초로 통일(실측 튜닝된 값 아닌 보수적 기본값).
    private static final long GRPC_CALL_TIMEOUT_SECONDS = 5;

    // Spring→AI(FastAPI) gRPC 호출 전체가 공유하는 서킷브레이커 — AI가 죽으면
    // 세 호출(추출·분석시작·중단) 모두 같은 상대(AI 서버)로 가는 것이므로 인스턴스 하나로 충분.
    private CircuitBreaker aiCircuitBreaker() {
        return circuitBreakerRegistry.circuitBreaker("aiServer");
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

        getAuthenticatedStub().extractReferenceData(request, new StreamObserver<com.shadowfit.grpc.ExtractResponse>() {
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
        });
    }

    /**
     * [STEP 2: 운동 분석 시작 - Entry Point]
     * 앱의 요청을 받아 DB에 세션을 생성하고 즉시 세션 ID를 반환합니다. (응답 속도 최적화)
     */
    @Transactional
    public Long startAnalysis(VideoRequestDto appDto, Long currentMemberId) {
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
        // @Async가 조용히 무시되고 동기 실행되는 문제가 있었음(2026-07-24, 테스트로 발견) —
        // completeSession→applyCompleteFromApp에 이미 쓰던 self 패턴을 여기에도 동일 적용.
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
                        self.sendAnalysisRequestToFastApi(sessionId, appDto, finalUrl, persona);
                    }
                }
        );

        return sessionId;
    }

    /**
     * [STEP 3: 비동기 gRPC 데이터 전송]
     * DB에서 기준 좌표(Reference)를 조회하여 FastAPI 서버로 전송합니다.
     */
    @Async
    @Transactional(readOnly = true)
    public void sendAnalysisRequestToFastApi(Long sessionId, VideoRequestDto appDto, String finalUrl, String persona) {
        log.info("비동기 분석 요청 시작 - 세션 ID: {}", sessionId);

        List<ExerciseReference> referencePoses = referenceRepository.findByExerciseId(appDto.getExerciseId());

        AnalyzeRequest.Builder requestBuilder = AnalyzeRequest.newBuilder()
                .setExerciseId(appDto.getExerciseId())
                .setSessionId(sessionId)
                .setReferenceSource(finalUrl)
                .setPersona(persona);

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
            sessionService.markAsFailedIfStillInProgress(sessionId, LocalDateTime.now());
            return;
        }
        long callStart = System.nanoTime();

        getAuthenticatedStub().startAnalysis(requestBuilder.build(), new StreamObserver<AnalyzeResponse>() {
            @Override
            public void onNext(AnalyzeResponse value) {
                cb.onSuccess(System.nanoTime() - callStart, TimeUnit.NANOSECONDS);
                log.info("FastAPI 응답 수신 - 세션: {}", value.getSessionId());
            }
            @Override
            public void onError(Throwable t) {
                cb.onError(System.nanoTime() - callStart, TimeUnit.NANOSECONDS, t);
                log.error("gRPC 통신 장애: {}", t.getMessage());
                // 이 한 번의 호출이 실패한 것(장애가 죽 이어져 서킷이 OPEN 되기 전이라도)도
                // 사용자 입장에선 응답 없는 세션이므로 동일하게 즉시 FAILED 처리.
                sessionService.markAsFailedIfStillInProgress(sessionId, LocalDateTime.now());
            }
            @Override
            public void onCompleted() {
                log.info("FastAPI 전송 완료");
            }
        });
    }

    /**
     * [STEP 4: AI 분석 중단 신호 송신]
     * SessionService.endSession 의 afterCommit 콜백에서 호출됨 (ET-H, 분기 2.A.ET).
     * 클라가 직접 호출하지 않음 — 단일 endpoint PATCH /sessions/{id}/end → Spring 이 분배.
     * AI 가 누적 결과로 BT-SET final batch + CompleteAnalysis 콜백을 비동기로 처리.
     */
    public void stopAnalysis(Long sessionId) {
        log.info("AI 서버 분석 중단 요청 전송 - sessionId: {}", sessionId);

        com.shadowfit.grpc.StopRequest request = com.shadowfit.grpc.StopRequest.newBuilder()
                .setSessionId(sessionId)
                .build();

        CircuitBreaker cb = aiCircuitBreaker();
        if (!cb.tryAcquirePermission()) {
            log.warn("AI 서버 서킷브레이커 OPEN — 중단 요청 스킵 (세션 ID: {})", sessionId);
            return;
        }
        long callStart = System.nanoTime();

        getAuthenticatedStub().stopAnalysis(request, new io.grpc.stub.StreamObserver<com.shadowfit.grpc.StopResponse>() {
            @Override
            public void onNext(com.shadowfit.grpc.StopResponse value) {
                cb.onSuccess(System.nanoTime() - callStart, TimeUnit.NANOSECONDS);
                log.info("AI 서버 응답: {}", value.getMessage());
            }
            @Override
            public void onError(Throwable t) {
                cb.onError(System.nanoTime() - callStart, TimeUnit.NANOSECONDS, t);
                log.error("AI 서버 중단 실패: {}", t.getMessage());
            }
            @Override
            public void onCompleted() {}
        });
    }

    /**
     * [STEP 5: 분석 결과 영속화 (Callback)]
     * AI 서버가 분석을 마치고 gRPC로 보고해온 최종 결과를 DB에 반영합니다.
     *
     * 낙관적 락 충돌 시(스케줄러가 동시에 FAILED로 변경한 경우) 재조회 후 COMPLETED로 덮어씁니다.
     */
    public void completeSession(Long sessionId, SessionUpdateRequestDto dto) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                self.applyCompleteFromApp(sessionId, dto);
                log.info("세션 {} DB 업데이트 완료", sessionId);
                return;
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt == maxAttempts) {
                    throw e;
                }
                log.warn("세션 {} 완료 처리 충돌 - 재시도 {}/{}", sessionId, attempt, maxAttempts);
            }
        }
    }

    @Transactional
    public void applyCompleteFromApp(Long sessionId, SessionUpdateRequestDto dto) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        // 멱등성: 같은 결과가 재전송된 경우(2-1, 2-2) 첫 완료 시각/기록을 보존하고 즉시 종료
        if (session.getStatus() == Status.COMPLETED) {
            return;
        }

        session.setTotalReps(dto.getTotalReps());
        session.setAvgSyncRate(java.math.BigDecimal.valueOf(dto.getAvgSyncRate()));
        session.setStatus(Status.COMPLETED);
        session.setEndTime(LocalDateTime.now());

        sessionRepository.saveAndFlush(session);
    }
}

