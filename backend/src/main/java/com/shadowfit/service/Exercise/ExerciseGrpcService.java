package com.shadowfit.service.Exercise;
import com.google.protobuf.Empty;
import com.shadowfit.global.config.InternalAuthInterceptor;
import com.shadowfit.global.observability.CallAbandonedException;
import com.shadowfit.global.observability.CallCancellation;
import com.shadowfit.global.observability.CorrelationIds;
import com.shadowfit.grpc.ExerciseServiceGrpc;
import com.shadowfit.grpc.PoseDataBatchRequest;
import com.shadowfit.grpc.PoseDataRequest;
import com.shadowfit.grpc.PoseDataResponse;
import com.shadowfit.global.observability.SessionMetrics;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.dao.PessimisticLockingFailureException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.shadowfit.grpc.*;
import net.devh.boot.grpc.server.service.GrpcService;



@Slf4j
@GrpcService(interceptors = {InternalAuthInterceptor.class})
@RequiredArgsConstructor
public class ExerciseGrpcService extends ExerciseServiceGrpc.ExerciseServiceImplBase {
    private final PoseDataService poseDataService;
    private final SessionService sessionService;
    private final FeedbackLogService feedbackLogService;
    private final SessionMetrics sessionMetrics;

    /**
     * 클라이언트가 <b>이미 포기한</b> 요청인가 (#206 결함 B).
     *
     * <p>gRPC 의 deadline 은 호출 사슬을 따라 {@code Context} 로 전파된다. 클라이언트가 포기하면
     * 서버 쪽 {@code Context} 가 취소되는데, 이 저장소의 핸들러는 그것을 <b>한 번도 보지 않았다</b> —
     * 그래서 아무도 안 받을 응답을 만들려고 커넥션·트랜잭션·CPU 를 계속 썼다. 부하가 걸릴수록
     * 손해가 커지는 방향이다(느려서 포기당했는데, 포기당한 작업이 자원을 더 먹는다).
     *
     * <p>여기서 막는 것은 <b>«도착했을 때 이미 죽어 있던» 요청</b>이다 — 큐에 밀렸거나 전송 중에
     * deadline 이 만료된 경우. 이슈의 조치 후보 B-1 이고 가장 싸다.
     *
     * <p><b>핸들러 «안에서» 만료되는 경우는 이 가드로 안 잡힌다</b> — 진입 시점에는 아직 살아
     * 있었기 때문이다. 그쪽은 서비스가 쓰기 직전에 한 번 더 본다
     * ({@link com.shadowfit.global.observability.CallCancellation}). 둘이 서로 다른 절반을 막는다:
     * 여기가 «도착했을 때 이미 죽어 있던» 요청, 저기가 «일하는 동안 죽은» 요청이다.
     *
     * @return 포기당한 요청이라 시작하지 않았으면 {@code true}
     */
    private boolean abortIfClientGaveUp(String rpc, StreamObserver<?> responseObserver) {
        if (!CallCancellation.isAbandoned()) {
            return false;
        }
        log.warn("{} — 클라이언트가 이미 포기한 요청이라 시작하지 않는다 (#206-B)", rpc);
        responseObserver.onError(Status.CANCELLED
                .withDescription("client already gave up before the handler started")
                .asRuntimeException());
        return true;
    }

    // correlation id 자체는 전역 인터셉터(GrpcObservabilityConfig)가 metadata에서 꺼내 MDC에 올려두지만,
    // 세션 id는 metadata가 아니라 메시지 payload 안에 있어 인터셉터가 볼 수 없다. 메서드마다 여기서
    // 얹어줘야 아래 서비스 계층(PoseDataService/SessionService)의 로그까지 세션이 따라붙는다.

    /**
     * [AI -> Spring] 운동 분석 중 생성된 포즈 데이터들을 배치(Batch)로 저장합니다.
     * 분석 도중 발생하는 방대한 좌표 데이터를 효율적으로 DB에 기록합니다.
     */
    @Override
    public void savePoseDataBatch(PoseDataBatchRequest request, StreamObserver<PoseDataResponse> responseObserver) {
        // try-with-resources 를 바깥에 두고 try/catch 를 안에 둔 이유: 자원은 catch 보다 먼저 닫히므로
        // 한 겹으로 합치면 정작 실패 로그에서 세션 id 가 빠진다.
        if (abortIfClientGaveUp("SavePoseDataBatch", responseObserver)) {
            return;
        }
        try (CorrelationIds.Scope ignored = CorrelationIds.withSession(request.getSessionId())) {
            try {
                log.info("세션 {} : 실시간 데이터 {}개 수신 및 저장 시작",
                        request.getSessionId(), request.getPoseDataCount());

                savePoseDataBatchWithDeadlockRetry(request);

                com.shadowfit.grpc.PoseDataResponse response = com.shadowfit.grpc.PoseDataResponse.newBuilder()
                        .setSuccess(true)
                        .setSessionId(request.getSessionId())
                        .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();

            } catch (CallAbandonedException e) {
                // 서비스가 «시작 안 함» 을 알려온 것이다 (#206-B). 우리가 아픈 게 아니라 상대가
                // 안 기다리기로 한 것이므로 INTERNAL 이 아니라 CANCELLED 로 답한다 — 어차피 받을
                // 사람은 없지만, 이 구분이 로그·지표에서 «장애» 와 «취소» 를 가른다.
                log.warn("세션 {} : 호출자가 포기해 {}", request.getSessionId(), e.getMessage());
                responseObserver.onError(io.grpc.Status.CANCELLED
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            } catch (Exception e) {
                // 🔴 클래스명을 같이 찍는다. 예전엔 메시지만 찍었는데, 그 탓에 «데드락인데 재시도가
                //    안 돌았다» 를 로그 한 줄로 못 닫고 라운드를 하나 태워서야 알았다 (#416).
                log.error("저장 실패: {} — {}", e.getClass().getSimpleName(), e.getMessage());
                responseObserver.onError(io.grpc.Status.INTERNAL.asRuntimeException());
            }
        }
    }

    /**
     * 데드락 재시도 상한. <b>고른 값이 아니라 실측값이다</b> —
     * {@code loadtest/results/r276-retry-2026-08-20/} 이 최대 재시도 0·1·2·3 을 라틴 방격 16판으로
     * 대조해 <b>0회 37.8% · 1회 3.8% · 2회 0.0% · 3회 0.0%</b> 를 얻었다. 3회는 이 조건에서
     * 얻는 것이 없어 2 로 둔다. 비용도 같이 쟀다 — 요청당 실제 시도가 1.20~1.30 이라 DB 일이
     * 20~30% 는다.
     *
     * <p>유도식({@code p^(n+1)})은 1회에 14.3% 를 예측해 <b>실측의 약 4배로 비관</b>이었다.
     * 두 번째 시도쯤이면 상대 트랜잭션이 이미 커밋돼 있어 그 행이 {@code ON DUPLICATE KEY UPDATE}
     * 로 접히고, 접히는 경로는 데드락이 0% 이기 때문이다(#276).
     *
     * <p>🔴 <b>이 값이 기대는 조건</b>: 실측은 <b>워커 8 한 점</b>이고, 같은 라운드가 데드락 확률이
     * 동시성의 함수임을 보였다(워커 2 에서 1.2%, 16 에서 59.5%). 더 높은 동시성에서도 2회로
     * 0% 인지는 <b>안 쟀다</b>. 그래서 {@code shadowfit.pose.batch.deadlock.retries} 로 관측한다.
     */
    private static final int DEADLOCK_MAX_RETRIES = 2;

    /**
     * pose_data 배치 저장을 데드락에 한해 다시 던진다.
     *
     * <p><b>왜 여기인가</b>: 데드락은 트랜잭션을 통째로 되감으므로 {@code @Transactional} 안에서는
     * 다시 던질 수 없다. 이 gRPC 핸들러는 트랜잭션 밖이라 매 시도가 새 트랜잭션이 된다.
     * 자기주입(@Lazy self)으로 서비스 안에서 푸는 길도 있으나 그 함정은 이미 등록돼 있다(#175).
     *
     * <p><b>왜 안전한가</b>: 재시도가 중복 행을 만들지 않는 것은 이 PR 이 세운 {@code uk_pose_event}
     * + {@code ON DUPLICATE KEY UPDATE} 때문이다(#188). 멱등이 먼저 서지 않았다면 재시도는
     * 유실을 중복으로 바꾸는 일이었다.
     *
     * <p>🔴 <b>실측과 다른 점</b>: 실측은 저장 프로시저 안에서 재시도했고 여기는 앱이라
     * <b>시도마다 왕복이 하나 더</b> 붙는다. 데드락 비율에는 영향이 없겠지만 지연 프로필은 다르다.
     * 간격도 실측 그대로 <b>0(즉시)</b> 이다 — 백오프는 재보지 않았고, 넣으면 좋아질 수도
     * 나빠질 수도 있다(상대에게 커밋할 시간을 주지만, 요청이 오래 살아 동시성을 올린다).
     *
     * <p>상한을 다 써도 실패하면 그대로 던진다. 그 위에 AI 쪽 재전송(3회 · 1s→3s)이 한 겹 더 있다.
     *
     * <p>🔴 <b>왜 {@code PessimisticLockingFailureException} 인가</b> (#416). 처음엔
     * {@code DeadlockLoserDataAccessException} 만 잡았는데, <b>그 예외는 실사용에서 오지 않는다</b> —
     * Spring 6 의 기본 번역기 {@code SQLExceptionSubclassTranslator} 는 그 클래스를 아예 만들지 않고,
     * MySQL 데드락(1213 / SQLState 40001)이 올려보내는 {@code SQLTransactionRollbackException} 을
     * {@code CannotAcquireLockException} 계열로 바꾼다. 둘은 이 예외의 <b>형제</b>라 좁게 잡으면 빗나간다.
     *
     * <p>그래서 이 루프는 2026-08-23 까지 <b>한 번도 돌지 않았다.</b> 앱 경로 라운드가 그것을 실측으로
     * 잡았다 — 데드락 61건에 재시도 로그 0줄, 잔여 실패율이 «재시도 없던» 시절과 같은 자리
     * ({@code loadtest/results/r276-app-retry-aws-2026-08-23/}).
     *
     * <p>부모로 넓히면 잠금 획득 실패(락 대기 타임아웃 등)까지 재시도 대상이 된다. <b>그래도 되는</b>
     * 이유는 이 경로가 멱등이기 때문이다({@code uk_pose_event} + ODKU, #188) — 다시 던져도 행이 안 는다.
     */
    private void savePoseDataBatchWithDeadlockRetry(PoseDataBatchRequest request) {
        int retries = 0;
        while (true) {
            try {
                poseDataService.savePoseDataBatch(request.getSessionId(), request.getPoseDataList());
                if (retries > 0) {
                    sessionMetrics.poseBatchDeadlockRetry("recovered");
                    log.info("세션 {} : 데드락 재시도 {}회 만에 저장 성공 (#276)",
                            request.getSessionId(), retries);
                }
                return;
            } catch (PessimisticLockingFailureException e) {
                if (retries >= DEADLOCK_MAX_RETRIES) {
                    sessionMetrics.poseBatchDeadlockRetry("exhausted");
                    log.warn("세션 {} : 데드락 재시도 {}회를 다 썼다 — AI 재전송으로 넘긴다 (#276)",
                            request.getSessionId(), DEADLOCK_MAX_RETRIES);
                    throw e;
                }
                retries++;
                sessionMetrics.poseBatchDeadlockRetry("retried");
                log.warn("세션 {} : 배치 INSERT 데드락 — {}/{}회째 다시 던진다 (#276)",
                        request.getSessionId(), retries, DEADLOCK_MAX_RETRIES);
            }
        }
    }

    /**
     * [AI -> Spring] 유튜브 영상에서 추출된 '정석 기준 좌표' 전체를 수신하여 저장합니다.
     * 운동 종목 등록 시 AI 서버가 추출한 좌표 데이터를 DB에 영속화하는 역할을 합니다.
     */
    @Override
    public void extractReferenceData(com.shadowfit.grpc.ExtractRequest request,
                                     io.grpc.stub.StreamObserver<com.shadowfit.grpc.ExtractResponse> responseObserver) {
        if (abortIfClientGaveUp("ExtractReferenceData", responseObserver)) {
            return;
        }
        try {
            log.info("기준 좌표 추출 데이터 수신 시작 - 운동 ID: {}", request.getExerciseId());

            poseDataService.saveReferencePoses(request.getExerciseId(), request.getExtractedPosesList());

            ExtractResponse extractResponse = ExtractResponse.newBuilder()
                    .setSuccess(true)
                    .setExerciseId(request.getExerciseId())
                    .build();

            responseObserver.onNext(extractResponse);
            responseObserver.onCompleted();
            log.info("기준 좌표 저장 완료 - 운동 ID: {}", request.getExerciseId());
        } catch (Exception e) {
            log.error("기준 좌표 저장 중 에러: {}", e.getMessage());
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("DB 저장 실패: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * [AI -> Spring] 최종 분석 완료 보고 (핵심 종료 지점)
     * AI 서버가 모든 연산을 마치고 최종 결과(횟수, 일치율 등)를 스프링에 전달할 때 호출됩니다.
     */
    @Override
    public void completeAnalysis(com.shadowfit.grpc.SessionCompleteRequest request,
                                 io.grpc.stub.StreamObserver<com.shadowfit.grpc.SessionCompleteResponse> responseObserver) {
        if (abortIfClientGaveUp("CompleteAnalysis", responseObserver)) {
            return;
        }
        try (CorrelationIds.Scope ignored = CorrelationIds.withSession(request.getSessionId())) {
            try {
                // AI 서버가 보내온 gRPC 데이터를 SessionService를 통해 DB에 반영
                sessionService.completeSession(request);

                SessionCompleteResponse response = SessionCompleteResponse.newBuilder()
                        .setSessionId(request.getSessionId())
                        .setStatus(com.shadowfit.grpc.SessionStatus.COMPLETED)
                        .setEndTime(com.google.protobuf.util.Timestamps.fromMillis(System.currentTimeMillis()))
                        .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();
                log.info("AI 서버 gRPC에 의한 세션 종료 성공 - 세션 ID: {}", request.getSessionId());
            } catch (CallAbandonedException e) {
                // 서비스가 «시작 안 함» 을 알려온 것이다 (#206-B). 우리가 아픈 게 아니라 상대가
                // 안 기다리기로 한 것이므로 INTERNAL 이 아니라 CANCELLED 로 답한다 — 어차피 받을
                // 사람은 없지만, 이 구분이 로그·지표에서 «장애» 와 «취소» 를 가른다.
                log.warn("세션 {} : 호출자가 포기해 {}", request.getSessionId(), e.getMessage());
                responseObserver.onError(io.grpc.Status.CANCELLED
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            } catch (Exception e) {
                log.error("세션 종료 gRPC 처리 중 에러: {}", e.getMessage());
                responseObserver.onError(io.grpc.Status.INTERNAL.asRuntimeException());
            }
        }
    }

    /**
     * [AI -> Spring] TTS 피드백 발화 이벤트 batch 저장 (BT-SET, 분기 2.A.BT).
     * 기존 REST POST /internal/feedback/batch 를 gRPC 로 단일화 (gRPC 통일 결정, 2026-05-25).
     */
    @Override
    public void reportFeedbackBatch(FeedbackBatchRequest request,
                                    StreamObserver<FeedbackBatchResponse> responseObserver) {
        if (abortIfClientGaveUp("ReportFeedbackBatch", responseObserver)) {
            return;
        }
        try (CorrelationIds.Scope ignored = CorrelationIds.withSession(request.getSessionId())) {
            try {
                int saved = feedbackLogService.saveBatch(request);

                FeedbackBatchResponse response = FeedbackBatchResponse.newBuilder()
                        .setSessionId(request.getSessionId())
                        .setSavedCount(saved)
                        .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } catch (com.shadowfit.global.error.BusinessException e) {
                // 🔴 세션 소멸과 입력 오류를 <b>상태코드로</b> 가른다 (#238 리뷰 A-2).
                //
                // 재전송 설계(feedback-batch-retransmission.md 축 C)는 이 둘을 다른 행으로 두고
                // 처리를 가른다 — 세션 소멸이면 버퍼를 버리고, 입력 오류면 그 건만 격리한다.
                // 그런데 둘 다 INVALID_ARGUMENT 로 나가면 AI 가 구분할 방법이 description
                // 문자열 파싱뿐이다. 그건 계약이 아니라 관습이고 문구가 바뀌면 조용히 깨진다.
                //
                // SESSION_NOT_FOUND 는 NOT_FOUND 가 맞기도 하다 — INVALID_ARGUMENT 는
                // 「클라이언트가 잘못된 인자를 보냈다」는 뜻인데, 세션이 사라진 것은 AI 잘못이 아니다.
                io.grpc.Status status =
                        e.getErrorCode() == com.shadowfit.global.error.ErrorCode.SESSION_NOT_FOUND
                                ? io.grpc.Status.NOT_FOUND
                                : io.grpc.Status.INVALID_ARGUMENT;
                log.warn("피드백 batch 거부 - session={}, code={}, grpcStatus={}",
                        request.getSessionId(), e.getErrorCode().name(), status.getCode());
                responseObserver.onError(status
                        .withDescription(e.getErrorCode().name() + ": " + e.getErrorCode().getMessage())
                        .asRuntimeException());
            } catch (Exception e) {
                log.error("피드백 batch 처리 중 에러: {}", e.getMessage());
                responseObserver.onError(io.grpc.Status.INTERNAL
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            }
        }
    }
}