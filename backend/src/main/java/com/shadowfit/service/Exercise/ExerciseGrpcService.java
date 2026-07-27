package com.shadowfit.service.Exercise;
import com.google.protobuf.Empty;
import com.shadowfit.global.config.InternalAuthInterceptor;
import com.shadowfit.global.observability.CorrelationIds;
import com.shadowfit.grpc.ExerciseServiceGrpc;
import com.shadowfit.grpc.PoseDataBatchRequest;
import com.shadowfit.grpc.PoseDataRequest;
import com.shadowfit.grpc.PoseDataResponse;
import io.grpc.stub.StreamObserver;
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
        try (CorrelationIds.Scope ignored = CorrelationIds.withSession(request.getSessionId())) {
            try {
                log.info("세션 {} : 실시간 데이터 {}개 수신 및 저장 시작",
                        request.getSessionId(), request.getPoseDataCount());

                poseDataService.savePoseDataBatch(request.getSessionId(), request.getPoseDataList());

                com.shadowfit.grpc.PoseDataResponse response = com.shadowfit.grpc.PoseDataResponse.newBuilder()
                        .setSuccess(true)
                        .setSessionId(request.getSessionId())
                        .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();

            } catch (Exception e) {
                log.error("저장 실패: {}", e.getMessage());
                responseObserver.onError(io.grpc.Status.INTERNAL.asRuntimeException());
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
                log.warn("피드백 batch 거부 - session={}, code={}", request.getSessionId(), e.getErrorCode().name());
                responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
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