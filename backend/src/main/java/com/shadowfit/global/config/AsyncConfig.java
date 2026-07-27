package com.shadowfit.global.config;

import com.shadowfit.global.observability.CorrelationIds;
import org.springframework.boot.task.ThreadPoolTaskExecutorCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code @Async} 스레드 경계에서 MDC(correlation id)를 잃지 않도록 TaskDecorator를 심는다.
 *
 * <p>MDC는 ThreadLocal이라 {@code @Async}로 워커 스레드에 넘어가는 순간 그냥 사라진다. 이 프로젝트의
 * {@code @Async} 지점은 {@code ExerciseAnalysisService.sendAnalysisRequestToFastApi}(AI 분석 요청)와
 * {@code PoseDataCleanupService}(회원 탈퇴 후처리) — 둘 다 실패 시 추적이 가장 필요한 자리다.
 *
 * <p>Boot가 자동 구성하는 {@code applicationTaskExecutor}를 커스터마이즈하는 방식이라 별도
 * executor 빈을 새로 정의하지 않는다(기존 풀 설정·기본값을 그대로 둔다).
 *
 * <p>참고: {@code SchedulerConfig.taskScheduler}는 직접 {@code new}로 만드는 빈이라 이 커스터마이저가
 * 적용되지 않는다. 스케줄러는 애초에 물려받을 요청 id가 없어 각 tick이 스스로 cid를 발급한다
 * ({@code CorrelationIds.startTask}).
 */
@Configuration
public class AsyncConfig {

    @Bean
    public ThreadPoolTaskExecutorCustomizer mdcPropagatingTaskExecutorCustomizer() {
        return executor -> executor.setTaskDecorator(CorrelationIds::wrap);
    }
}
