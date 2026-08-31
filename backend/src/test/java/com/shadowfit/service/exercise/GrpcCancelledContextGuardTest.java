package com.shadowfit.service.exercise;

import com.shadowfit.grpc.ExtractRequest;
import com.shadowfit.grpc.FeedbackBatchRequest;
import com.shadowfit.grpc.PoseDataBatchRequest;
import com.shadowfit.grpc.SessionCompleteRequest;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * #206 결함 B — <b>클라이언트가 이미 포기한 요청을 핸들러가 시작조차 하지 않는가</b> (조치 B-1).
 *
 * <p>취소를 {@code deadline} 으로 만들지 않고 {@code Context} 를 직접 취소한다. deadline 으로
 * 만들면 «만료가 핸들러 진입 전이냐 후냐» 가 타이밍 경주가 되는데({@code GrpcServerDeadlineProbeTest}
 * 의 상수 주석이 그 함정을 이미 적어뒀다), B-1 이 막는 것은 <b>진입 시점에 이미 취소된 요청</b>
 * 하나뿐이라 그 경주를 재면 다른 것을 재게 된다. deadline 도 결국 {@code Context} 취소로
 * 전파되므로 이쪽이 같은 것을 확정적으로 잰다.
 *
 * <p>🔴 <b>이 테스트는 «핸들러 안에서» 만료되는 경우를 다루지 않는다.</b> 그건
 * {@code GrpcServerDeadlineProbeTest} 가 재현한 시나리오이고 B-1 로는 안 막힌다 — 서비스 계층까지
 * 취소 신호를 내려야 하는데(B-2) 그건 계층 결정이 선행한다. 두 테스트가 서로 다른 절반을 지킨다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("#206-B 포기당한 요청을 핸들러가 시작하지 않는가")
class GrpcCancelledContextGuardTest {

    @Mock private PoseDataService poseDataService;
    @Mock private SessionService sessionService;
    @Mock private FeedbackLogService feedbackLogService;

    @InjectMocks private ExerciseGrpcService grpcService;

    /** 취소된 컨텍스트 안에서 주어진 호출을 실행한다. */
    private void runCancelled(Runnable call) {
        Context.CancellableContext ctx = Context.current().withCancellation();
        ctx.cancel(new RuntimeException("클라이언트가 포기했다"));
        ctx.run(call);
    }

    private static Status statusOf(StreamObserver<?> observer) {
        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(StatusRuntimeException.class);
        return ((StatusRuntimeException) captor.getValue()).getStatus();
    }

    @Test
    @DisplayName("SavePoseDataBatch — 저장을 시작하지 않고 CANCELLED 로 답한다")
    void savePoseDataBatch() {
        @SuppressWarnings("unchecked")
        StreamObserver<com.shadowfit.grpc.PoseDataResponse> observer = mock(StreamObserver.class);

        runCancelled(() -> grpcService.savePoseDataBatch(
                PoseDataBatchRequest.newBuilder().setSessionId(1L).build(), observer));

        // 결정적 증거는 «서비스가 안 불렸다» 이다. 응답만 보면 «일은 다 하고 응답만 안 준» 것과
        // 구분되지 않는데, #206 이 아까워하는 것은 응답이 아니라 그 일이다.
        verifyNoInteractions(poseDataService);
        assertThat(statusOf(observer).getCode()).isEqualTo(Status.Code.CANCELLED);
        verify(observer, never()).onNext(org.mockito.ArgumentMatchers.any());
        verify(observer, never()).onCompleted();
    }

    @Test
    @DisplayName("CompleteAnalysis — 세션 종료를 시작하지 않는다")
    void completeAnalysis() {
        @SuppressWarnings("unchecked")
        StreamObserver<com.shadowfit.grpc.SessionCompleteResponse> observer = mock(StreamObserver.class);

        runCancelled(() -> grpcService.completeAnalysis(
                SessionCompleteRequest.newBuilder().setSessionId(1L).build(), observer));

        verifyNoInteractions(sessionService);
        assertThat(statusOf(observer).getCode()).isEqualTo(Status.Code.CANCELLED);
    }

    @Test
    @DisplayName("ReportFeedbackBatch — 배치 저장을 시작하지 않는다")
    void reportFeedbackBatch() {
        @SuppressWarnings("unchecked")
        StreamObserver<com.shadowfit.grpc.FeedbackBatchResponse> observer = mock(StreamObserver.class);

        runCancelled(() -> grpcService.reportFeedbackBatch(
                FeedbackBatchRequest.newBuilder().setSessionId(1L).build(), observer));

        verifyNoInteractions(feedbackLogService);
        assertThat(statusOf(observer).getCode()).isEqualTo(Status.Code.CANCELLED);
    }

    @Test
    @DisplayName("ExtractReferenceData — 기준 좌표 저장을 시작하지 않는다")
    void extractReferenceData() {
        @SuppressWarnings("unchecked")
        StreamObserver<com.shadowfit.grpc.ExtractResponse> observer = mock(StreamObserver.class);

        runCancelled(() -> grpcService.extractReferenceData(
                ExtractRequest.newBuilder().setExerciseId(1L).build(), observer));

        verifyNoInteractions(poseDataService);
        assertThat(statusOf(observer).getCode()).isEqualTo(Status.Code.CANCELLED);
    }

    @Test
    @DisplayName("취소되지 않은 요청은 그대로 통과한다 — 가드가 정상 경로를 막지 않는지")
    void notCancelledPassesThrough() {
        @SuppressWarnings("unchecked")
        StreamObserver<com.shadowfit.grpc.PoseDataResponse> observer = mock(StreamObserver.class);

        grpcService.savePoseDataBatch(
                PoseDataBatchRequest.newBuilder().setSessionId(1L).build(), observer);

        verify(poseDataService).savePoseDataBatch(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.anyList());
        verify(observer).onCompleted();
        verify(observer, never()).onError(org.mockito.ArgumentMatchers.any());
    }
}
