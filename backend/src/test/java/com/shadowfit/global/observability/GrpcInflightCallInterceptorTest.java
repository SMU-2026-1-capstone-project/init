package com.shadowfit.global.observability;

import com.shadowfit.grpc.ExerciseServiceGrpc;
import com.shadowfit.grpc.StopRequest;
import com.shadowfit.grpc.StopResponse;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [#598] in-flight 게이지가 호출 시작에 오르고 종료(정상/취소)에 내려가는지 검증한다.
 *
 * <p>진짜 {@link SimpleMeterRegistry}로 잰다 — 게이지 등록 자체가 안 되는 것과 값이 틀린 것은
 * 다른 실패라 목으로는 못 가른다({@link GrpcCorrelationInterceptorTest}와 같은 이유).
 */
@DisplayName("gRPC in-flight 호출 수 인터셉터 테스트")
class GrpcInflightCallInterceptorTest {

    private static final MethodDescriptor<StopRequest, StopResponse> METHOD =
            ExerciseServiceGrpc.getStopAnalysisMethod();

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GrpcInflightCallInterceptor interceptor = new GrpcInflightCallInterceptor(registry);

    @Test
    @DisplayName("호출이 시작되면 게이지가 오르고, 정상 종료(onComplete)되면 내려간다")
    void gaugeTracksNormalCompletion() {
        ServerCall.Listener<StopRequest> listener =
                interceptor.interceptCall(mockCall(), new Metadata(), noopHandler());

        assertThat(gauge()).isEqualTo(1.0);

        listener.onComplete();

        assertThat(gauge()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("호출이 취소(onCancel)돼도 게이지가 내려간다")
    void gaugeTracksCancellation() {
        ServerCall.Listener<StopRequest> listener =
                interceptor.interceptCall(mockCall(), new Metadata(), noopHandler());

        listener.onCancel();

        assertThat(gauge()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("동시 호출 여러 건이면 게이지가 그만큼 쌓인다 — 각자 독립적으로 내려간다")
    void gaugeTracksConcurrentCalls() {
        ServerCall.Listener<StopRequest> first =
                interceptor.interceptCall(mockCall(), new Metadata(), noopHandler());
        ServerCall.Listener<StopRequest> second =
                interceptor.interceptCall(mockCall(), new Metadata(), noopHandler());

        assertThat(gauge()).isEqualTo(2.0);

        first.onComplete();
        assertThat(gauge()).isEqualTo(1.0);

        second.onComplete();
        assertThat(gauge()).isEqualTo(0.0);
    }

    private double gauge() {
        return registry.get("shadowfit.grpc.server.inflight")
                .tag("method", "StopAnalysis")
                .gauge()
                .value();
    }

    private ServerCallHandler<StopRequest, StopResponse> noopHandler() {
        return (call, headers) -> new ServerCall.Listener<>() {
        };
    }

    @SuppressWarnings("unchecked")
    private ServerCall<StopRequest, StopResponse> mockCall() {
        ServerCall<StopRequest, StopResponse> call = mock(ServerCall.class);
        when(call.getMethodDescriptor()).thenReturn(METHOD);
        return call;
    }
}
