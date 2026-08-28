package com.shadowfit.global.observability;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * [#598] Spring gRPC 서버(AI → Spring 콜백) 핸들러별 in-flight 호출 수.
 *
 * <p>이 인터셉터는 스레드풀에 상한을 걸지 않는다 — #598 에서 사용자가 확정한 순서(2026-08-28)가
 * "근거 없는 숫자를 먼저 박지 말고 실제 동시 유입량부터 잰다"라서, 이 클래스는 그 측정 수단이다.
 * 상한값은 이 지표로 실제 피크(예: 세션 분산도 스윕류 부하)를 관측한 뒤에 정한다.
 *
 * <p>메서드별로 태그를 나눈다 — {@code savePoseDataBatch}·{@code completeAnalysis} 등 넷이 전부
 * 같은 스레드풀을 나눠 쓰므로, 전체 합만 보면 "어느 핸들러가 몰릴 때 아픈지"가 안 보인다. 태그
 * 카디널리티는 서비스 메서드 수(현재 4개)로 고정돼 있어 안전하다.
 *
 * <p>전역 인터셉터로 등록해(코드는 {@link GrpcObservabilityConfig}) 인증 통과 여부와 무관하게 모든
 * 유입을 센다 — 인증 거부되는 호출도 grpc 서버 스레드를 잠깐 점유하므로, 스레드풀 용량 산정에는
 * 인증 이후 트래픽만 보면 과소평가된다({@link GrpcCorrelationServerInterceptor}가 인증보다 먼저
 * 도는 이유와 같다).
 */
public class GrpcInflightCallInterceptor implements ServerInterceptor {

    private static final String METRIC_NAME = "shadowfit.grpc.server.inflight";

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    public GrpcInflightCallInterceptor(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                  Metadata headers,
                                                                  ServerCallHandler<ReqT, RespT> next) {
        String method = call.getMethodDescriptor().getBareMethodName();
        AtomicInteger counter = counters.computeIfAbsent(method, this::registerGauge);
        counter.incrementAndGet();

        ServerCall.Listener<ReqT> delegate = next.startCall(call, headers);

        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
            // grpc 계약상 onCancel/onComplete 중 정확히 하나만 불린다(ServerCall.Listener 문서) —
            // 그래서 카운터는 딱 한 번만 내려가면 되고 별도 가드가 필요 없다.
            @Override
            public void onCancel() {
                counter.decrementAndGet();
                super.onCancel();
            }

            @Override
            public void onComplete() {
                counter.decrementAndGet();
                super.onComplete();
            }
        };
    }

    private AtomicInteger registerGauge(String method) {
        AtomicInteger counter = new AtomicInteger(0);
        Gauge.builder(METRIC_NAME, counter, AtomicInteger::get)
                .description("gRPC 서버 핸들러 동시 처리 중 호출 수 (#598 — 스레드풀 상한을 정하기 전 실제 유입량 관측용)")
                .tag("method", method)
                .register(registry);
        return counter;
    }
}
