package com.shadowfit.global.observability;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

/**
 * [Spring → AI] 나가는 gRPC 호출 metadata에 현재 스레드의 correlation id를 실어보낸다.
 * FastAPI 쪽 {@code CorrelationServerInterceptor}가 이걸 받아 자기 로그에 찍으므로, 두 서비스의
 * 로그가 같은 id로 이어진다.
 *
 * <p>MDC가 비어 있는 경우(스케줄러 tick 밖에서 시작된 호출 등)에도 최소한의 추적성을 위해
 * {@code spring-out-*} id를 새로 발급한다 — 무엇으로도 못 잇는 것보다는 낫다.
 */
public class GrpcCorrelationClientInterceptor implements ClientInterceptor {

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                               CallOptions callOptions,
                                                               Channel next) {
        // interceptCall은 호출자 스레드에서 실행되므로 여기서 캡처해야 MDC가 살아 있다.
        // start()는 같은 스레드인 게 보통이지만 보장은 아니라 값을 미리 확정해 둔다.
        String captured = CorrelationIds.current();
        String correlationId = captured != null ? captured : CorrelationIds.newTaskId("spring-out");

        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                headers.put(CorrelationIds.GRPC_HEADER, correlationId);
                super.start(responseListener, headers);
            }
        };
    }
}
