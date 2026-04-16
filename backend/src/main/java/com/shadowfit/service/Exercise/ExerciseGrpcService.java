package com.shadowfit.service.Exercise;
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
@GrpcService
@RequiredArgsConstructor
public class ExerciseGrpcService extends ExerciseServiceGrpc.ExerciseServiceImplBase {
    private final PoseDataService poseDataService;
    private final SessionService sessionService;

    @Override
    public void savePoseDataBatch(PoseDataBatchRequest request, StreamObserver<PoseDataResponse> responseObserver) {
        try {
            poseDataService.savePoseDataBatchGrpc(request);

            PoseDataRequest lastData = request.getPoseData(request.getPoseDataCount() - 1);
            PoseDataResponse response = PoseDataResponse.newBuilder().setSuccess(true).
                    setSessionId(request.getSessionId())
                    .setTimestampSec(lastData.getTimestampSec())
                    .setJointCoordinates(lastData.getJointCoordinates())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            responseObserver.onError(e);
        }
    }

    // ExerciseGrpcService.java에 추가할 내용
    @Override
    public void extractReferenceData(com.shadowfit.grpc.ExtractRequest request,
                                     io.grpc.stub.StreamObserver<com.shadowfit.grpc.ExtractResponse> responseObserver) {
        try {
            log.info("기준 좌표 추출 데이터 수신 시작 - 운동 ID: {}", request.getExerciseId());

            poseDataService.saveReferencePoses(request.getExerciseId(), request.getExtractedPosesList());

            // [수정] 변수 이름을 'response' 대신 'extractResponse'로 바꿉니다.
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

    @Override
    public void completeAnalysis(com.shadowfit.grpc.SessionCompleteRequest request,
                                 io.grpc.stub.StreamObserver<com.shadowfit.grpc.SessionCompleteResponse> responseObserver) {
        try {
            sessionService.completeSession(request);

            SessionCompleteResponse response = SessionCompleteResponse.newBuilder()
                    .setSessionId(request.getSessionId())
                    .setStatus(com.shadowfit.grpc.SessionStatus.COMPLETED)
                    .setEndTime(com.google.protobuf.util.Timestamps.fromMillis(System.currentTimeMillis()))
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
            log.info("세션 분석 완료 처리 성공 - 세션 ID: {}", request.getSessionId());
        } catch (Exception e) {
            log.error("세션 종료 처리 중 에러: {}", e.getMessage());
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        }
    }
}