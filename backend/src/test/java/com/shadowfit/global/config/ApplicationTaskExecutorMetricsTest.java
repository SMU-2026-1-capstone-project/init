package com.shadowfit.global.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #582 회귀 방지 — {@code applicationTaskExecutor}가 Micrometer에 잡히는지 확인한다.
 *
 * <p>Boot 자동구성이 만드는 동일 이름 빈은 {@code @Lazy}라 컨텍스트 리프레시 시점 스냅샷에서
 * 빠지고, 이후 {@code @Async} 호출로 실제로 깨어나도 다시 관찰 대상에 들어가지 않는다
 * ({@link AsyncConfig} 참고). 그래서 이 테스트는 <b>한 번도 {@code @Async}를 호출하지 않은
 * 상태</b>에서 지표가 이미 존재하는지를 본다 — 이게 원래 이슈가 재현한 증상이다.
 */
@SpringBootTest
@DisplayName("applicationTaskExecutor Micrometer 바인딩 — #582")
class ApplicationTaskExecutorMetricsTest {

    @Autowired private MeterRegistry meterRegistry;

    @Test
    @DisplayName("@Async를 한 번도 호출하지 않아도 executor.pool.core 게이지가 잡힌다")
    void executorPoolCoreGaugeIsBoundWithoutAnyAsyncCall() {
        Gauge gauge = meterRegistry.find("executor.pool.core")
                .tag("name", "applicationTaskExecutor")
                .gauge();

        assertThat(gauge).as("executor.pool.core{name=applicationTaskExecutor} 게이지").isNotNull();
    }
}
