package com.shadowfit.global.observability;

import com.shadowfit.model.exercise.Status;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Grafana 대시보드가 거는 <b>지표 이름</b>을 고정한다.
 *
 * <p><b>왜 필요한가</b> — 코드의 이름과 Prometheus 로 나가는 이름이 다르다. Micrometer 가
 * {@code shadowfit.outbox.pending} 을 {@code shadowfit_outbox_pending} 으로 바꾸고, 카운터엔
 * {@code _total} 을, 타이머엔 {@code _seconds_*} 를 붙인다. 대시보드 JSON 은 <b>바뀐 쪽</b>
 * 이름을 쓰므로, 여기가 틀리면 패널이 조용히 "No data" 가 된다 — 깨진 게 아니라 <b>빈 것처럼</b>
 * 보이기 때문에 알아채기 어렵다.
 *
 * <p><b>왜 통합테스트로 안 하나</b> — 카운터·타이머는 Micrometer 가 <b>첫 기록 시점에</b>
 * 만든다. 그래서 서버를 띄우고 {@code /actuator/prometheus} 를 긁어도 아직 안 쓰인 지표는
 * 아예 안 나온다(실제로 도입 검증 때 9종 중 게이지 2종만 나왔다). 세션 흐름을 다 태워야
 * 나머지가 나오는데, 그건 AI 서버까지 필요해서 이름 확인 하나에 치르기엔 비싸다.
 * 여기서는 레지스트리를 직접 만들어 <b>전부 한 번씩 기록한 뒤</b> scrape 결과를 본다.
 *
 * <p>⚠️ 이 테스트가 깨지면 고칠 곳이 하나 더 있다 —
 * {@code monitoring/grafana/dashboards/shadowfit-backend.json}.
 */
@DisplayName("SessionMetrics — Prometheus 로 나가는 이름 고정 (대시보드 계약)")
class SessionMetricsExportNamesTest {

    private PrometheusMeterRegistry registry;
    private SessionMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        metrics = new SessionMetrics(registry);

        // 9종 전부 한 번씩 기록한다 — 기록해야 meter 가 생긴다.
        metrics.sessionTransition(Status.COMPLETED, "ai-callback");
        metrics.optimisticLockConflict("timeout-scheduler", "yield");
        metrics.aiStopResult("session-missing");
        metrics.outboxDispatch("sent");
        metrics.outboxLag(Duration.ofMillis(250));
        metrics.poseBatch(300, 60);
        metrics.poseOrphanWindow(Duration.ofMillis(12));
        metrics.registerOutboxPendingGauge(() -> 3);
        metrics.registerPoseOrphanGauge(() -> 0);
    }

    @Test
    @DisplayName("대시보드가 쓰는 지표 이름이 전부 실제로 나간다")
    void exportedNamesMatchDashboard() {
        String scrape = registry.scrape();

        assertThat(scrape)
                .contains("shadowfit_session_transitions_total")
                .contains("shadowfit_session_optimistic_lock_conflicts_total")
                .contains("shadowfit_ai_stop_result_total")
                .contains("shadowfit_outbox_dispatch_total")
                .contains("shadowfit_outbox_pending")
                .contains("shadowfit_outbox_lag_seconds_count")
                .contains("shadowfit_outbox_lag_seconds_sum")
                .contains("shadowfit_pose_orphan_rows")
                .contains("shadowfit_pose_orphan_window_seconds")
                .contains("shadowfit_pose_batch_frames_sum");
    }

    @Test
    @DisplayName("태그 이름도 고정한다 — 대시보드가 legendFormat 과 sum by 에 쓴다")
    void exportedTagsMatchDashboard() {
        String scrape = registry.scrape();

        // 같은 FAILED 라도 source 가 다르면 다른 사건이라, 이 두 태그가 붙어 있어야
        // 대시보드의 `sum by (status, source)` 가 의미를 갖는다.
        assertThat(scrape).contains("status=\"COMPLETED\"").contains("source=\"ai-callback\"");
        assertThat(scrape).contains("outcome=\"yield\"");
        assertThat(scrape).contains("outcome=\"sent\"");
        assertThat(scrape).contains("stage=\"stored\"").contains("stage=\"received\"");
    }

    @Test
    @DisplayName("고아 창은 백분위가 함께 나간다 — 평균으로는 답이 안 나오는 지표라서")
    void orphanWindowPublishesPercentiles() {
        String scrape = registry.scrape();

        // 창이 대부분 짧고 가끔 길다면 확률을 지배하는 건 꼬리다. 대시보드가 p50·p95·p99
        // 를 그리므로 quantile 라벨이 실제로 나가는지 확인한다.
        assertThat(scrape).contains("shadowfit_pose_orphan_window_seconds{quantile=\"0.5\"}");
        assertThat(scrape).contains("shadowfit_pose_orphan_window_seconds{quantile=\"0.95\"}");
        assertThat(scrape).contains("shadowfit_pose_orphan_window_seconds{quantile=\"0.99\"}");
    }
}
