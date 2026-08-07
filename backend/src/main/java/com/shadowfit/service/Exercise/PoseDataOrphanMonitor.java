package com.shadowfit.service.Exercise;

import com.shadowfit.global.observability.CorrelationIds;
import com.shadowfit.global.observability.SessionMetrics;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 고아 {@code pose_data} 행 관측 (이슈 #87).
 *
 * <p><b>왜 세기만 하고 지우지 않나.</b> {@code PoseDataService.savePoseDataBatch} 는 세션 존재
 * 검증과 배치 INSERT 사이에 보호가 없어, 그 틈에 회원 탈퇴가 커밋되면 정리
 * ({@code PoseDataCleanupService})가 지나간 뒤에 INSERT 가 착지해 고아 행이 남는다. FK 를 뗀
 * 상태라 DB 도 막지 않는다({@code V1__baseline.sql} 의 pose_data 정의, 이슈 #41).
 *
 * <p>수정안은 세 갈래다 — 비관적 락(예방) / sweep(사후 청소) / 감수. 그런데 <b>셋 중 무엇이
 * 맞는지는 이 결함이 실제로 얼마나 나는지에 달려 있고, 그 숫자가 지금 없다.</b> 락은 비용이
 * 확실한데(배치 저장 전건) 이득이 불확실하고(드문 결함), sweep 은 창을 못 막는 데다 소량 반복
 * DELETE 의 파편화가 이 프로젝트에서 미검증이다. 그래서 <b>고치기 전에 세는 것</b>이 먼저다.
 * 이 클래스가 sweep 으로 승격되면 같은 쿼리에 {@code DELETE} 만 붙이면 된다.
 *
 * <p><b>조회 범위를 최근 몇 달로 자르는 이유.</b> anti-join 은 대용량 테이블에서 비싸다.
 * {@code PoseDataPartitionScheduler} 가 이번 달 + 지난 1개월만 남기고 드롭하므로 살아 있는 행은
 * 어차피 그 범위 안에 있고, {@code created_at} 하한을 주면 파티션 pruning 도 걸린다. 그보다
 * 오래된 파티션에 고아가 있어도 곧 통째로 드롭될 행이라 굳이 세지 않는다.
 *
 * <p><b>다중 인스턴스에서 중복 실행돼도 안전하다</b> — 읽기 전용이라 중복은 쿼리 낭비일 뿐
 * 결과를 바꾸지 않는다. 상태를 바꾸는 {@code PoseDataPartitionScheduler} 와는 다른 성격이다.
 */
@Slf4j
@Service
public class PoseDataOrphanMonitor {

    /**
     * 세션이 사라진 pose_data 행 수. {@code NOT EXISTS} 는 세션이 지워진 뒤라 인덱스로 좁힐
     * 대상이 없어 범위 스캔이 된다 — 그래서 {@code created_at} 하한이 사실상 유일한 방어다.
     */
    private static final String COUNT_ORPHANS_SQL =
            "SELECT COUNT(*) FROM pose_data p " +
            "WHERE p.created_at >= ? " +
            "  AND NOT EXISTS (SELECT 1 FROM exercise_sessions s WHERE s.id = p.session_id)";

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbcTemplate;
    private final SessionMetrics sessionMetrics;
    private final int lookbackMonths;

    /** 게이지가 읽는 값. 스케줄러가 채우고 스크레이프는 이 값만 본다(SessionMetrics 주석 참고). */
    private final AtomicLong lastCount = new AtomicLong(0);

    public PoseDataOrphanMonitor(JdbcTemplate jdbcTemplate,
                                 SessionMetrics sessionMetrics,
                                 @Value("${pose-data.orphan-monitor.lookback-months:2}") int lookbackMonths) {
        this.jdbcTemplate = jdbcTemplate;
        this.sessionMetrics = sessionMetrics;
        this.lookbackMonths = lookbackMonths;
    }

    @PostConstruct
    void registerGauge() {
        sessionMetrics.registerPoseOrphanGauge(lastCount::get);
    }

    /**
     * 기본 주기는 매일 새벽 4시 30분 — {@code PoseDataPartitionScheduler}(4시)가 만료 파티션을
     * 드롭한 <b>뒤에</b> 세야, 곧 사라질 행까지 세어 값이 부풀지 않는다.
     */
    @Scheduled(cron = "${pose-data.orphan-monitor.check-cron:0 30 4 * * *}")
    public void refresh() {
        // 요청 원점이 없는 흐름이라 실행 1회를 하나의 cid 로 묶는다(SessionTimeoutScheduler 패턴).
        try (CorrelationIds.Scope tick = CorrelationIds.startTask("pose-orphan-scan")) {
            try {
                long count = countOrphans();
                lastCount.set(count);

                if (count > 0) {
                    // 0 이 정상이고 0 초과는 그 자체로 사건이다 — 이슈 #87 의 레이스가 실제로
                    // 일어났다는 첫 직접 증거이므로 사람이 봐야 한다.
                    log.warn("고아 pose_data 행 관측 - {}건 (최근 {}개월 범위, 이슈 #87)",
                            count, lookbackMonths);
                } else {
                    log.debug("고아 pose_data 행 없음 (최근 {}개월 범위)", lookbackMonths);
                }
            } catch (Exception e) {
                // 관측 실패가 서비스에 번지면 안 된다 — 다음 tick 에 다시 센다.
                log.error("고아 pose_data 행 관측 실패", e);
            }
        }
    }

    /** 관측 시점의 고아 행 수. 테스트가 스케줄 대기 없이 직접 부를 수 있게 열어둔다. */
    long countOrphans() {
        LocalDateTime from = YearMonth.now(SEOUL).minusMonths(lookbackMonths - 1L).atDay(1).atStartOfDay();
        Long count = jdbcTemplate.queryForObject(COUNT_ORPHANS_SQL, Long.class, from);
        return count != null ? count : 0L;
    }

    /** 게이지가 마지막으로 노출한 값. */
    long lastCount() {
        return lastCount.get();
    }
}
