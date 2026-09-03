package com.shadowfit.global.config;

import com.shadowfit.global.observability.CorrelationIds;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * {@code @Async} 스레드 경계에서 MDC(correlation id)를 잃지 않도록 TaskDecorator를 심는다.
 *
 * <p>MDC는 ThreadLocal이라 {@code @Async}로 워커 스레드에 넘어가는 순간 그냥 사라진다. 이 프로젝트의
 * {@code @Async} 지점은 {@code ExerciseAnalysisService.sendAnalysisRequestToFastApi}(AI 분석 요청)와
 * {@code PoseDataCleanupService}(회원 탈퇴 후처리) — 둘 다 실패 시 추적이 가장 필요한 자리다.
 *
 * <p>#582 — Boot가 자동 구성하는 {@code applicationTaskExecutor}는 {@code @Lazy} 빈이라, 컨텍스트
 * 리프레시 시점 Micrometer 스냅샷에서 빠지고 이후 {@code @Async} 호출로 깨어나도 다시 관찰 대상에
 * 안 들어간다. 그래서 {@code ThreadPoolTaskExecutorCustomizer}(자동구성 빈에 얹는 방식) 대신, Boot가
 * 제공하는 {@link ThreadPoolTaskExecutorBuilder}(= {@code spring.task.execution.*} 설정을 그대로
 * 반영)로 같은 이름의 빈을 여기서 직접(non-lazy) 정의한다 — 풀 설정·기본값은 그대로 유지된다.
 *
 * <p>참고: {@code SchedulerConfig.taskScheduler}는 직접 {@code new}로 만드는 빈이라 이 TaskDecorator가
 * 적용되지 않는다. 스케줄러는 애초에 물려받을 요청 id가 없어 각 tick이 스스로 cid를 발급한다
 * ({@code CorrelationIds.startTask}).
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "applicationTaskExecutor")
    public TaskExecutor applicationTaskExecutor(ThreadPoolTaskExecutorBuilder builder) {
        ThreadPoolTaskExecutor executor = builder.taskDecorator(CorrelationIds::wrap).build();
        return executor;
    }
}
