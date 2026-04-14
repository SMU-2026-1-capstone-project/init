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
import lombok.RequiredArgsConstructor;
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

    @Override
    public void completeAnalysis(SessionCompleteRequest request, StreamObserver<SessionCompleteResponse> responseObserver){
        try{
            sessionService.completeSession(request);

            SessionCompleteResponse response = SessionCompleteResponse.newBuilder()
                    .setSessionId(request.getSessionId())
                    .setStatus(SessionStatus.COMPLETED)
                    .setEndTime(com.google.protobuf.util.Timestamps.fromMillis(System.currentTimeMillis()))
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e){
            log.error("세션 종료 처리 중 에러: {}",e.getMessage());
            responseObserver.onError(e);
        }

    }
    }