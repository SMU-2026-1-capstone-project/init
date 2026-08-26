package com.shadowfit.global.observability;

import com.shadowfit.service.Report.WeeklySentenceRuleId;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 주간 요약(«A층·B층» 집계 + 규칙 문장)의 관측 지표.
 *
 * <p>설계: {@code report-generation-llm.md} §13-5. 세 개를 잰다:
 * <ul>
 *   <li>{@link #queryLatency} — 조회 1건의 지연. B층은 LLM 없이도 세션마다 JSON_TABLE 을
 *       펼치는 조회라, 세션이 많은 회원에서 얼마나 느려지는지가 «캐시/저장이 필요한가»의
 *       근거다(설계 §13-0 이 뒤로 미룬 판단)</li>
 *   <li>{@link #sessionsInWindow} — 조회 1건이 본 이번 주 세션 수 분포. 위와 같은 목적의
 *       재료를 다른 축(부하가 아니라 «어느 회원이 무거운가»)에서 본다</li>
 *   <li>{@link #ruleFired} — 규칙별 발화 횟수. {@link WeeklySentenceRuleId} 문서 참고 —
 *       코드가 있어도 실사용에서 안 밟히는 분기가 있을 수 있다(#193 이 준 교훈)</li>
 * </ul>
 */
@Component
public class WeeklyReportMetrics {

    private static final String QUERY_LATENCY = "shadowfit.report.weekly.query.latency";
    private static final String SESSIONS_IN_WINDOW = "shadowfit.report.weekly.sessions";
    private static final String RULE_FIRED = "shadowfit.report.weekly.rule.fired";

    private final MeterRegistry registry;

    public WeeklyReportMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 조회 1건의 지연(A층+B층 포함, 문장 조립까지). 평균이 아니라 백분위로 본다 — 세션 수가 회원마다 갈려 꼬리가 길 수 있다. */
    public void queryLatency(Duration duration) {
        Timer.builder(QUERY_LATENCY)
                .description("주간 요약 조회 1건의 지연")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(registry)
                .record(duration);
    }

    /** 조회 1건이 본 이번 주(완료) 세션 수. 캐시/저장이 필요해지는 지점을 찾는 재료다. */
    public void sessionsInWindow(long sessions) {
        DistributionSummary.builder(SESSIONS_IN_WINDOW)
                .description("주간 요약 조회 1건이 본 이번 주 세션 수")
                .register(registry)
                .record(sessions);
    }

    /** @param ruleId 발화한 규칙. tags: rule(규칙 식별자) */
    public void ruleFired(WeeklySentenceRuleId ruleId) {
        registry.counter(RULE_FIRED, "rule", ruleId.name()).increment();
    }
}
