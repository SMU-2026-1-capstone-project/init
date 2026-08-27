package com.shadowfit.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.context.annotation.Bean;

/**
 * 스케줄러 설정
 *
 * 타임아웃 체크 및 기타 정기 작업을 관리합니다.
 *
 * <p><b>{@code scheduling.enabled=false} 로 통째로 끌 수 있다.</b> 기본값은 켜짐이라 운영·개발
 * 동작은 그대로다. 끌 수 있게 만든 이유는 편의가 아니라 <b>측정 오염</b>이다 —
 * {@code AdminSessionExplainCaptureTest} 같은 {@code @SpringBootTest} 캡처 장치가 전체 컨텍스트를
 * 띄우면, {@code SessionTimeoutScheduler} 가 같이 떠서 <b>측정 대상 데이터를 UPDATE 한다.</b>
 * 실제로 2026-08-06 측정에서 스크래치 DB 의 세션 160건이 IN_PROGRESS → FAILED 로 넘어갔고,
 * 그 양은 테스트가 오래 돌수록 는다({@code docs/decisions/admin-page-scope.md} §4-2 결함 #4).
 *
 * <p>프로퍼티로 스케줄 주기만 늘려서는 막을 수 없다 — {@code checkAndTimeoutSessions} 의
 * {@code initialDelay} 가 30초 고정이라 한 번은 반드시 돈다. 🔄 2026-08-27(#207): 그 한 번이
 * 엔티티를 물던 것은 프로젝션으로 바뀌었지만({@code findTimeoutCandidatesByStatus}), 25만행을
 * 스캔해 자바에서 거르는 것 자체는 그대로다 — 배치 상한(#207 §7-①)은 아직 미채택.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulerConfig {

    /**
     * 스케줄 실행용 스레드 풀 설정
     *
     * maxPoolSize: 스케줄러 작업을 처리할 최대 스레드 수
     * threadNamePrefix: 스레드 이름 접두사 (로그 추적용)
     * waitForTasksToCompleteOnShutdown: 애플리케이션 종료 시 진행 중인 작업 대기
     * awaitTerminationSeconds: 작업 완료 대기 시간
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("shadowfit-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(60);
        scheduler.initialize();
        return scheduler;
    }
}

