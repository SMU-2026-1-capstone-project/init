package com.shadowfit.global.observability;

import com.shadowfit.service.Report.WeeklySentenceRuleId;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주간 요약 관측 지표(설계 §13-5)가 실제로 나가는지 확인한다.
 *
 * <p>{@code SessionMetricsExportNamesTest} 와 같은 이유로 레지스트리를 직접 만들어 기록 후
 * scrape 한다 — 카운터·타이머는 첫 기록 시점에야 생기기 때문이다. 이 지표들은 아직 Grafana
 * 대시보드에 패널이 없으므로(§13-5 는 방금 붙었다) 이름을 «계약» 으로 고정하진 않는다 —
 * 그건 패널이 생긴 뒤 {@code SessionMetricsExportNamesTest} 처럼 다룰 일이다.
 */
@DisplayName("WeeklyReportMetrics")
class WeeklyReportMetricsTest {

    private PrometheusMeterRegistry registry;
    private WeeklyReportMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        metrics = new WeeklyReportMetrics(registry);
    }

    @Test
    @DisplayName("조회 지연이 백분위와 함께 나간다")
    void 조회_지연() {
        metrics.queryLatency(Duration.ofMillis(42));

        String scrape = registry.scrape();
        assertThat(scrape).contains("shadowfit_report_weekly_query_latency_seconds");
        assertThat(scrape).contains("quantile=\"0.5\"");
        assertThat(scrape).contains("quantile=\"0.99\"");
    }

    @Test
    @DisplayName("조회 1건의 세션 수가 분포로 나간다")
    void 세션_수_분포() {
        metrics.sessionsInWindow(7);

        assertThat(registry.scrape()).contains("shadowfit_report_weekly_sessions_sum");
    }

    @Test
    @DisplayName("규칙별 발화가 rule 태그로 갈라져 나간다")
    void 규칙별_발화() {
        metrics.ruleFired(WeeklySentenceRuleId.NO_RECORD);
        metrics.ruleFired(WeeklySentenceRuleId.CURVE_DROP_RANK);

        String scrape = registry.scrape();
        assertThat(scrape).contains("shadowfit_report_weekly_rule_fired_total");
        assertThat(scrape).contains("rule=\"NO_RECORD\"");
        assertThat(scrape).contains("rule=\"CURVE_DROP_RANK\"");
    }
}
