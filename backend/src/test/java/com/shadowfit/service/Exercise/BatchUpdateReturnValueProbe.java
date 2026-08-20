package com.shadowfit.service.Exercise;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

/**
 * #219 ③ 선행 실측 — {@code batchUpdate} 반환값이 SQL 문법과 드라이버 옵션에 따라 어떻게 달라지는가.
 *
 * <p>{@code FeedbackLogService:92-94} 는 반환 배열로 삽입 건수를 센다:
 * <pre>for (int r : results) if (r &gt; 0) inserted++;</pre>
 * {@code INSERT IGNORE} → {@code ON DUPLICATE KEY UPDATE id = id} 로 바꿔도 이 계산이 유지되는지를
 * 확인하는 것이 목적이다. 유지되지 않으면 «수정 방향» 자체가 달라진다.
 *
 * <p><b>운영 URL 에는 {@code rewriteBatchedStatements=true} 가 있다</b>(application.yml:17).
 * 이 옵션이 붙으면 드라이버가 batch 를 multi-row SQL 한 방으로 재작성하므로 반환값 규약이
 * 달라질 수 있다. 그래서 두 URL 을 나란히 잰다 — 운영 조건으로 재지 않으면 잰 의미가 없다.
 *
 * <p>실행:
 * <pre>./gradlew :backend:test --tests '*BatchUpdateReturnValueProbe' -Drace.mysql=true</pre>
 */
@EnabledIfSystemProperty(named = "race.mysql", matches = "true",
        disabledReason = "실측 프로브 — MySQL(3310) 필요")
@DisplayName("#219 batchUpdate 반환값 실측")
class BatchUpdateReturnValueProbe {

    private static final String BASE = "jdbc:mysql://localhost:3310/shadowfit"
            + "?serverTimezone=Asia/Seoul&characterEncoding=UTF-8";

    private static final String COLS =
            " INTO session_feedback_logs (session_id, feedback_type, sync_rate_at_trigger, occurred_at, created_at)"
            + " VALUES (?, ?, ?, ?, ?)";
    private static final String SQL_IGNORE = "INSERT IGNORE" + COLS;
    private static final String SQL_ON_DUP = "INSERT" + COLS + " ON DUPLICATE KEY UPDATE id = id";

    @Test
    void probe() {
        for (boolean rewrite : new boolean[]{false, true}) {
            for (String[] variant : new String[][]{{"INSERT IGNORE", SQL_IGNORE},
                                                   {"ON DUPLICATE KEY UPDATE id=id", SQL_ON_DUP}}) {
                run(rewrite, variant[0], variant[1]);
            }
        }
        System.out.println("\n=== FK 위반 거동 (세션이 사라진 경우) ===");
        for (String[] variant : new String[][]{{"INSERT IGNORE", SQL_IGNORE},
                                               {"ON DUPLICATE KEY UPDATE id=id", SQL_ON_DUP}}) {
            fkProbe(variant[0], variant[1]);
        }
    }

    /** 재전송 시나리오: 2건을 먼저 넣고, 그 2건 + 새 1건 = 3건짜리 배치를 다시 보낸다. */
    private void run(boolean rewrite, String label, String sql) {
        JdbcTemplate jdbc = template(rewrite);
        jdbc.update("DELETE FROM session_feedback_logs");

        LocalDateTime t0 = LocalDateTime.of(2026, 8, 16, 10, 0, 0);
        jdbc.batchUpdate(sql, Arrays.asList(
                row(t0, "KNEE_OUT"), row(t0.plusSeconds(5), "KNEE_IN")));

        int[] results = jdbc.batchUpdate(sql, Arrays.asList(
                row(t0, "KNEE_OUT"),                 // 중복
                row(t0.plusSeconds(5), "KNEE_IN"),   // 중복
                row(t0.plusSeconds(9), "BACK_ROUND") // 신규
        ));

        int inserted = 0;
        for (int r : results) if (r > 0) inserted++;
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM session_feedback_logs", Integer.class);

        System.out.printf("%n[rewriteBatchedStatements=%s] %s%n", rewrite, label);
        System.out.printf("  반환배열      : %s%n", Arrays.toString(results));
        System.out.printf("  r>0 로 센 값  : inserted=%d, skipped=%d%n", inserted, 3 - inserted);
        System.out.printf("  실제 DB 행수  : %d  (기대: 3 — 중복 2건은 흡수)%n", rows);
        System.out.printf("  판정          : %s%n",
                (rows != null && rows == 3 && inserted == 1) ? "OK — r>0 계산이 실제와 일치"
                        : "🔴 어긋남 — 집계 방식을 바꿔야 함");
    }

    /** 부모 세션이 없는 session_id 로 넣어 FK 위반을 만든다. */
    private void fkProbe(String label, String sql) {
        JdbcTemplate jdbc = template(true);
        try {
            jdbc.batchUpdate(sql, Collections.singletonList(
                    new Object[]{999L, "KNEE_OUT", 72.5,
                            Timestamp.valueOf(LocalDateTime.of(2026, 8, 16, 11, 0, 0)),
                            Timestamp.valueOf(LocalDateTime.now())}));
            System.out.printf("  %-30s → 예외 없음 (행은 사라진다)%n", label);
        } catch (Exception e) {
            System.out.printf("  %-30s → %s%n", label, e.getClass().getSimpleName());
        }
    }

    private Object[] row(LocalDateTime occurredAt, String type) {
        return new Object[]{1L, type, 72.5, Timestamp.valueOf(occurredAt),
                Timestamp.valueOf(LocalDateTime.now())};
    }

    private JdbcTemplate template(boolean rewrite) {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                BASE + (rewrite ? "&rewriteBatchedStatements=true" : ""), "root", "probe");
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return new JdbcTemplate(ds);
    }
}
