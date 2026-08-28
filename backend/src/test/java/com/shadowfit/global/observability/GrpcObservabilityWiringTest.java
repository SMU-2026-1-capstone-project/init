package com.shadowfit.global.observability;

import net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전역 gRPC 인터셉터 등록 검증.
 *
 * <p>인터셉터 로직 자체가 맞아도 <b>등록이 안 되면 조용히 아무 일도 안 일어난다</b>(로그에 cid가
 * 안 붙을 뿐 기능은 정상 동작하므로 눈치채기 어렵다). net.devh 가 전역 인터셉터를 찾는 방식이
 * {@code getBeansWithAnnotation} 이라, 같은 경로로 발견되는지를 확인한다.
 */
@SpringBootTest
@DisplayName("gRPC 전역 인터셉터 등록 테스트")
class GrpcObservabilityWiringTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("[수신] correlation 서버 인터셉터가 전역으로 등록돼 있다")
    void serverInterceptorIsRegisteredGlobally() {
        assertThat(applicationContext.getBeansWithAnnotation(GrpcGlobalServerInterceptor.class).values())
                .anyMatch(GrpcCorrelationServerInterceptor.class::isInstance);
    }

    @Test
    @DisplayName("[송신] correlation 클라이언트 인터셉터가 전역으로 등록돼 있다")
    void clientInterceptorIsRegisteredGlobally() {
        assertThat(applicationContext.getBeansWithAnnotation(GrpcGlobalClientInterceptor.class).values())
                .anyMatch(GrpcCorrelationClientInterceptor.class::isInstance);
    }

    @Test
    @DisplayName("[#598] in-flight 호출 수 서버 인터셉터가 전역으로 등록돼 있다")
    void inflightCallInterceptorIsRegisteredGlobally() {
        assertThat(applicationContext.getBeansWithAnnotation(GrpcGlobalServerInterceptor.class).values())
                .anyMatch(GrpcInflightCallInterceptor.class::isInstance);
    }
}
