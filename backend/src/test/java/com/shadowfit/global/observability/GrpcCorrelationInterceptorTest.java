package com.shadowfit.global.observability;

import com.shadowfit.grpc.ExerciseServiceGrpc;
import com.shadowfit.grpc.StopRequest;
import com.shadowfit.grpc.StopResponse;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 프로세스 경계(Spring ↔ FastAPI)를 넘는 correlation id 전파 검증.
 *
 * <p>핵심은 서버 인터셉터가 <b>리스너 콜백 안에서</b> MDC를 세우는지다 — {@code interceptCall}에서만
 * 세우면 정작 서비스 메서드가 도는 grpc-server 워커 스레드에는 아무것도 없다.
 */
@DisplayName("gRPC correlation id 인터셉터 테스트")
class GrpcCorrelationInterceptorTest {

    private static final MethodDescriptor<StopRequest, StopResponse> METHOD =
            ExerciseServiceGrpc.getStopAnalysisMethod();

    private final GrpcCorrelationClientInterceptor clientInterceptor = new GrpcCorrelationClientInterceptor();
    private final GrpcCorrelationServerInterceptor serverInterceptor = new GrpcCorrelationServerInterceptor();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("[송신] 현재 스레드의 cid를 metadata에 실어보낸다")
    void clientAttachesCurrentCorrelationId() {
        Metadata sentHeaders = new Metadata();

        try (CorrelationIds.Scope ignored = CorrelationIds.withCorrelationId("cid-outbound")) {
            clientInterceptor.interceptCall(METHOD, CallOptions.DEFAULT, capturingChannel(sentHeaders))
                    .start(new ClientCall.Listener<>() {
                    }, new Metadata());
        }

        assertThat(sentHeaders.get(CorrelationIds.GRPC_HEADER)).isEqualTo("cid-outbound");
    }

    @Test
    @DisplayName("[송신] MDC가 비어 있어도 id를 발급해 보낸다 — 아무것도 못 잇는 것보단 낫다")
    void clientGeneratesIdWhenMdcEmpty() {
        Metadata sentHeaders = new Metadata();

        clientInterceptor.interceptCall(METHOD, CallOptions.DEFAULT, capturingChannel(sentHeaders))
                .start(new ClientCall.Listener<>() {
                }, new Metadata());

        assertThat(sentHeaders.get(CorrelationIds.GRPC_HEADER)).startsWith("spring-out-");
    }

    @Test
    @DisplayName("[수신] 핸들러가 실제로 실행되는 리스너 콜백 안에서 MDC가 세워진다")
    void serverExposesCorrelationIdDuringHandlerExecution() {
        Metadata headers = new Metadata();
        headers.put(CorrelationIds.GRPC_HEADER, "cid-from-ai");
        AtomicReference<String> seenInHandler = new AtomicReference<>();

        ServerCall.Listener<StopRequest> listener =
                serverInterceptor.interceptCall(mockCall(), headers, handlerCapturingOnHalfClose(seenInHandler));

        // 실제 서비스 메서드는 onHalfClose 시점에 실행된다
        listener.onHalfClose();

        assertThat(seenInHandler.get()).isEqualTo("cid-from-ai");
        // 콜백을 빠져나오면 원복 — grpc-server 워커 스레드는 재사용되므로 누수되면 안 됨
        assertThat(CorrelationIds.current()).isNull();
    }

    @Test
    @DisplayName("[수신] AI가 id를 안 보냈으면 새로 발급한다")
    void serverGeneratesIdWhenHeaderAbsent() {
        AtomicReference<String> seenInHandler = new AtomicReference<>();

        serverInterceptor.interceptCall(mockCall(), new Metadata(), handlerCapturingOnHalfClose(seenInHandler))
                .onHalfClose();

        assertThat(seenInHandler.get()).startsWith("grpc-in-");
    }

    @Test
    @DisplayName("[왕복] 송신이 실은 id를 수신이 그대로 꺼내 — 두 서비스 로그가 한 id로 이어진다")
    void roundTripKeepsSameId() {
        Metadata onTheWire = new Metadata();
        AtomicReference<String> seenOnServer = new AtomicReference<>();

        try (CorrelationIds.Scope ignored = CorrelationIds.withCorrelationId("end-to-end-1")) {
            clientInterceptor.interceptCall(METHOD, CallOptions.DEFAULT, capturingChannel(onTheWire))
                    .start(new ClientCall.Listener<>() {
                    }, new Metadata());
        }

        serverInterceptor.interceptCall(mockCall(), onTheWire, handlerCapturingOnHalfClose(seenOnServer))
                .onHalfClose();

        assertThat(seenOnServer.get()).isEqualTo("end-to-end-1");
    }

    /** start(...)로 넘어온 헤더만 받아 적는 가짜 채널. */
    private Channel capturingChannel(Metadata sink) {
        return new Channel() {
            @Override
            public <Q, S> ClientCall<Q, S> newCall(MethodDescriptor<Q, S> methodDescriptor, CallOptions callOptions) {
                return new ClientCall<>() {
                    @Override
                    public void start(Listener<S> responseListener, Metadata headers) {
                        for (String key : headers.keys()) {
                            if (key.equals(CorrelationIds.GRPC_HEADER.name())) {
                                sink.put(CorrelationIds.GRPC_HEADER, headers.get(CorrelationIds.GRPC_HEADER));
                            }
                        }
                    }

                    @Override
                    public void request(int numMessages) {
                    }

                    @Override
                    public void cancel(String message, Throwable cause) {
                    }

                    @Override
                    public void halfClose() {
                    }

                    @Override
                    public void sendMessage(Q message) {
                    }
                };
            }

            @Override
            public String authority() {
                return "test-authority";
            }
        };
    }

    private ServerCallHandler<StopRequest, StopResponse> handlerCapturingOnHalfClose(AtomicReference<String> sink) {
        return (call, headers) -> new ServerCall.Listener<>() {
            @Override
            public void onHalfClose() {
                sink.set(CorrelationIds.current());
            }
        };
    }

    @SuppressWarnings("unchecked")
    private ServerCall<StopRequest, StopResponse> mockCall() {
        return mock(ServerCall.class);
    }
}
