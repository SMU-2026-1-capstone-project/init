package com.shadowfit.service.Exercise;

import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.grpc.ExtractRequest;
import com.shadowfit.grpc.ExtractResponse;
import com.shadowfit.grpc.FeedbackBatchRequest;
import com.shadowfit.grpc.FeedbackBatchResponse;
import com.shadowfit.grpc.PoseDataBatchRequest;
import com.shadowfit.grpc.PoseDataResponse;
import com.shadowfit.grpc.SessionCompleteRequest;
import com.shadowfit.grpc.SessionCompleteResponse;
import com.shadowfit.grpc.SessionStatus;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ExerciseGrpcService 단위테스트 — gRPC 서비스 구현체가 서비스 계층 예외를 올바른 gRPC
 * Status로 매핑하는지 검증(BusinessException → INVALID_ARGUMENT, 그 외 → INTERNAL).
 */
@DisplayName("ExerciseGrpcService 테스트")
class ExerciseGrpcServiceTest {

    @Mock private PoseDataService poseDataService;
    @Mock private SessionService sessionService;
    @Mock private FeedbackLogService feedbackLogService;
    private ExerciseGrpcService grpcService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        grpcService = new ExerciseGrpcService(poseDataService, sessionService, feedbackLogService);
    }

    @Test
    @DisplayName("savePoseDataBatch 성공 — onNext + onCompleted")
    void savePoseDataBatch_success() {
        PoseDataBatchRequest request = PoseDataBatchRequest.newBuilder().setSessionId(1L).build();
        @SuppressWarnings("unchecked")
        StreamObserver<PoseDataResponse> obs = mock(StreamObserver.class);

        grpcService.savePoseDataBatch(request, obs);

        ArgumentCaptor<PoseDataResponse> captor = ArgumentCaptor.forClass(PoseDataResponse.class);
        verify(obs).onNext(captor.capture());
        verify(obs).onCompleted();
        verify(obs, never()).onError(any());
        assertThat(captor.getValue().getSuccess()).isTrue();
    }

    @Test
    @DisplayName("savePoseDataBatch 실패 — 서비스 예외는 INTERNAL로 매핑")
    void savePoseDataBatch_serviceThrows_mapsToInternal() {
        PoseDataBatchRequest request = PoseDataBatchRequest.newBuilder().setSessionId(1L).build();
        @SuppressWarnings("unchecked")
        StreamObserver<PoseDataResponse> obs = mock(StreamObserver.class);
        doThrow(new BusinessException(ErrorCode.SESSION_NOT_FOUND))
                .when(poseDataService).savePoseDataBatch(anyLong(), anyList());

        grpcService.savePoseDataBatch(request, obs);

        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(obs).onError(captor.capture());
        assertThat(((StatusRuntimeException) captor.getValue()).getStatus().getCode())
                .isEqualTo(Status.Code.INTERNAL);
        verify(obs, never()).onNext(any());
    }

    @Test
    @DisplayName("extractReferenceData 성공")
    void extractReferenceData_success() {
        ExtractRequest request = ExtractRequest.newBuilder().setExerciseId(1L).build();
        @SuppressWarnings("unchecked")
        StreamObserver<ExtractResponse> obs = mock(StreamObserver.class);

        grpcService.extractReferenceData(request, obs);

        ArgumentCaptor<ExtractResponse> captor = ArgumentCaptor.forClass(ExtractResponse.class);
        verify(obs).onNext(captor.capture());
        verify(obs).onCompleted();
        assertThat(captor.getValue().getSuccess()).isTrue();
    }

    @Test
    @DisplayName("completeAnalysis 성공 — COMPLETED 상태로 응답")
    void completeAnalysis_success() {
        SessionCompleteRequest request = SessionCompleteRequest.newBuilder().setSessionId(1L).build();
        @SuppressWarnings("unchecked")
        StreamObserver<SessionCompleteResponse> obs = mock(StreamObserver.class);

        grpcService.completeAnalysis(request, obs);

        ArgumentCaptor<SessionCompleteResponse> captor = ArgumentCaptor.forClass(SessionCompleteResponse.class);
        verify(obs).onNext(captor.capture());
        verify(obs).onCompleted();
        assertThat(captor.getValue().getStatus()).isEqualTo(SessionStatus.COMPLETED);
    }

    @Test
    @DisplayName("completeAnalysis 실패 — 서비스 예외는 INTERNAL로 매핑")
    void completeAnalysis_serviceThrows_mapsToInternal() {
        SessionCompleteRequest request = SessionCompleteRequest.newBuilder().setSessionId(1L).build();
        @SuppressWarnings("unchecked")
        StreamObserver<SessionCompleteResponse> obs = mock(StreamObserver.class);
        doThrow(new BusinessException(ErrorCode.SESSION_NOT_FOUND)).when(sessionService).completeSession(any());

        grpcService.completeAnalysis(request, obs);

        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(obs).onError(captor.capture());
        assertThat(((StatusRuntimeException) captor.getValue()).getStatus().getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    @DisplayName("reportFeedbackBatch 성공")
    void reportFeedbackBatch_success() {
        FeedbackBatchRequest request = FeedbackBatchRequest.newBuilder().setSessionId(1L).build();
        @SuppressWarnings("unchecked")
        StreamObserver<FeedbackBatchResponse> obs = mock(StreamObserver.class);
        when(feedbackLogService.saveBatch(request)).thenReturn(3);

        grpcService.reportFeedbackBatch(request, obs);

        ArgumentCaptor<FeedbackBatchResponse> captor = ArgumentCaptor.forClass(FeedbackBatchResponse.class);
        verify(obs).onNext(captor.capture());
        verify(obs).onCompleted();
        assertThat(captor.getValue().getSavedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("reportFeedbackBatch — BusinessException은 INVALID_ARGUMENT로 매핑(그 외 예외와 구분)")
    void reportFeedbackBatch_businessException_mapsToInvalidArgument() {
        FeedbackBatchRequest request = FeedbackBatchRequest.newBuilder().setSessionId(1L).build();
        @SuppressWarnings("unchecked")
        StreamObserver<FeedbackBatchResponse> obs = mock(StreamObserver.class);
        when(feedbackLogService.saveBatch(request)).thenThrow(new BusinessException(ErrorCode.INVALID_INPUT_VALUE));

        grpcService.reportFeedbackBatch(request, obs);

        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(obs).onError(captor.capture());
        assertThat(((StatusRuntimeException) captor.getValue()).getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    @DisplayName("reportFeedbackBatch — 예상 못한 예외는 INTERNAL로 매핑")
    void reportFeedbackBatch_unexpectedException_mapsToInternal() {
        FeedbackBatchRequest request = FeedbackBatchRequest.newBuilder().setSessionId(1L).build();
        @SuppressWarnings("unchecked")
        StreamObserver<FeedbackBatchResponse> obs = mock(StreamObserver.class);
        when(feedbackLogService.saveBatch(request)).thenThrow(new RuntimeException("boom"));

        grpcService.reportFeedbackBatch(request, obs);

        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(obs).onError(captor.capture());
        assertThat(((StatusRuntimeException) captor.getValue()).getStatus().getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }
}
