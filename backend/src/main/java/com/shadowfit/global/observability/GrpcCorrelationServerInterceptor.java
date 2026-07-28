package com.shadowfit.global.observability;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

/**
 * [AI → Spring] 들어오는 gRPC 호출의 metadata에서 correlation id를 꺼내 MDC에 얹는다.
 * FastAPI가 안 실어보냈으면 여기서 새로 발급해, 콜백 흐름도 최소한 자기 id는 갖게 한다.
 *
 * <p>[왜 리스너를 감싸나] {@code interceptCall}은 RPC가 도착한 스레드에서 한 번 불리지만, 실제
 * 서비스 메서드는 grpc-server 워커 스레드의 {@code onHalfClose}에서 실행된다. interceptCall
 * 안에서 MDC.put만 하면 정작 핸들러가 도는 스레드에는 아무것도 없다 — 그래서 리스너 콜백마다
 * MDC를 세우고 빠져나올 때 되돌린다.
 */
public class GrpcCorrelationServerInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                 Metadata headers,
                                                                 ServerCallHandler<ReqT, RespT> next) {
        String inbound = CorrelationIds.sanitize(headers.get(CorrelationIds.GRPC_HEADER));
        String correlationId = inbound != null ? inbound : CorrelationIds.newTaskId("grpc-in");

        ServerCall.Listener<ReqT> delegate;
        try (CorrelationIds.Scope ignored = CorrelationIds.withCorrelationId(correlationId)) {
            delegate = next.startCall(call, headers);
        }

        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
            @Override
            public void onMessage(ReqT message) {
                restored(() -> super.onMessage(message));
            }

            @Override
            public void onHalfClose() {
                restored(super::onHalfClose);
            }

            @Override
            public void onCancel() {
                restored(super::onCancel);
            }

            @Override
            public void onComplete() {
                restored(super::onComplete);
            }

            @Override
            public void onReady() {
                restored(super::onReady);
            }

            private void restored(Runnable body) {
                try (CorrelationIds.Scope ignored = CorrelationIds.withCorrelationId(correlationId)) {
                    body.run();
                }
            }
        };
    }
}
