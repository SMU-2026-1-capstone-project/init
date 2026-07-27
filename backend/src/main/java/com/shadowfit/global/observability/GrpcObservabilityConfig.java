package com.shadowfit.global.observability;

import io.grpc.ClientInterceptor;
import io.grpc.ServerInterceptor;
import net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * correlation id 전파용 gRPC 인터셉터를 <b>전역</b>으로 등록한다.
 *
 * <p>서비스별 {@code @GrpcService(interceptors = ...)}에 나열하지 않고 전역으로 두는 이유:
 * (1) 새 gRPC 서비스가 생겨도 자동 적용되고, (2) 최고 우선순위로 인증 인터셉터
 * ({@code InternalAuthInterceptor})보다 먼저 돌아 <b>인증 거부된 호출의 로그에도</b> cid가 남는다.
 */
@Configuration
public class GrpcObservabilityConfig {

    @Bean
    @GrpcGlobalServerInterceptor
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public ServerInterceptor grpcCorrelationServerInterceptor() {
        return new GrpcCorrelationServerInterceptor();
    }

    @Bean
    @GrpcGlobalClientInterceptor
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public ClientInterceptor grpcCorrelationClientInterceptor() {
        return new GrpcCorrelationClientInterceptor();
    }
}
