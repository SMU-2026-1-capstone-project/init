package com.shadowfit.service.Exercise;

import com.shadowfit.global.observability.CorrelationIds;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * pose_data TTL 자동 만료 — 월별 RANGE 파티션(V1__baseline.sql)에서 보존 기간이 지난 과거
 * 파티션을 DROP PARTITION으로 폐기하고(DELETE 대비 ~625배, realmysql-experiments.md §4②d),
 * pfuture가 실데이터를 떠안지 않도록 미래 파티션을 미리 만들어둔다.
 *
 * [보존 정책] (report-read-path.md §9-B, 2026-07-24 확정)
 * - 이번 달(쓰기 중) + 바로 지난 {@code retentionBufferMonths}개월(버퍼)만 남기고 그 이전은 드롭.
 *   precompute-on-write(SessionService.applyComplete)로 worst 구간이 이미 reports에 저장되므로
 *   pose_data 원본은 그 이후 버퍼일 뿐 — db-deep-dive.md §D.
 * - 아카이빙 없음(완전 폐기). S3 이전은 개인정보보호법 제21조 "지체없이 파기" 취지에 안 맞아 제외.
 * - 안전마진: pfuture는 쿼리 단계에서부터 제외, 나머지도 이름이 pYYYY_MM 패턴과 정확히 일치할 때만
 *   드롭 후보로 인정 — 파싱 실패한 파티션은 건드리지 않고 경고만 남김.
 */
@Slf4j
@Service
public class PoseDataPartitionScheduler {

    private static final String TABLE_NAME = "pose_data";
    private static final Pattern PARTITION_NAME_PATTERN = Pattern.compile("^p(\\d{4})_(\\d{2})$");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter PARTITION_BOUND_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd 00:00:00");

    private final JdbcTemplate jdbcTemplate;
    private final int retentionBufferMonths;
    private final int lookaheadMonths;

    public PoseDataPartitionScheduler(JdbcTemplate jdbcTemplate,
                                       @Value("${pose-data.partition.retention-buffer-months:1}") int retentionBufferMonths,
                                       @Value("${pose-data.partition.lookahead-months:2}") int lookaheadMonths) {
        this.jdbcTemplate = jdbcTemplate;
        this.retentionBufferMonths = retentionBufferMonths;
        this.lookaheadMonths = lookaheadMonths;
    }

    // 월 단위 하우스키핑이라 매일 1회면 충분 — SessionTimeoutScheduler(1분 주기, 분 단위 사용자 영향)와
    // 달리 급하지 않고, 실제 파티션 목록을 매번 다시 조회해 판단하므로 어느 날 실행되든 멱등적으로 안전.
    @Scheduled(cron = "${pose-data.partition.check-cron:0 0 4 * * *}")
    public void checkAndMaintainPartitions() {
        // 요청 원점이 없는 흐름이라 실행 1회를 하나의 cid 로 묶는다 — DROP PARTITION 처럼 되돌릴 수
        // 없는 작업은 "어느 실행이 무엇을 지웠는지"가 사후 추적의 전부다.
        // (스코프를 바깥에 둬야 아래 catch 의 실패 로그도 cid 를 갖는다 — 자원이 catch 보다 먼저 닫힘.)
        try (CorrelationIds.Scope tick = CorrelationIds.startTask("partition-maintenance")) {
            maintainPartitions();
        }
    }

    private void maintainPartitions() {
        try {
            YearMonth currentMonth = YearMonth.now(SEOUL);
            List<PartitionInfo> partitions = fetchNamedPartitions();

            dropExpiredPartitions(partitions, currentMonth);
            ensureFuturePartitions(partitions, currentMonth);
        } catch (Exception e) {
            log.error("pose_data 파티션 유지보수 중 에러 발생", e);
        }
    }

    // package-private: 테스트가 jdbcTemplate 없이 순수 결정 로직(뭘 드롭/생성할지)만 검증할 수 있게.
    void dropExpiredPartitions(List<PartitionInfo> partitions, YearMonth currentMonth) {
        // 이번 달 + retentionBufferMonths 개월까지는 보존. 그보다 엄격히 이전인 월만 드롭 대상.
        YearMonth keepFrom = currentMonth.minusMonths(retentionBufferMonths);

        for (PartitionInfo partition : partitions) {
            if (!partition.month().isBefore(keepFrom)) {
                continue; // 보존 대상 — 이번 달 또는 버퍼 월
            }
            log.warn("pose_data 파티션 만료 — {} 드롭 (예상 행수: {}, 보존기준: {} 이전)",
                    partition.name(), partition.approxRows(), keepFrom);
            jdbcTemplate.execute("ALTER TABLE " + TABLE_NAME + " DROP PARTITION " + partition.name());
        }
    }

    void ensureFuturePartitions(List<PartitionInfo> partitions, YearMonth currentMonth) {
        YearMonth lastNamedMonth = partitions.stream()
                .map(PartitionInfo::month)
                .max(YearMonth::compareTo)
                .orElse(currentMonth.minusMonths(1));
        YearMonth targetMonth = currentMonth.plusMonths(lookaheadMonths);

        if (!lastNamedMonth.isBefore(targetMonth)) {
            return; // 이미 충분히 미래까지 실명 파티션이 있음 — pfuture는 비어있는 상태 유지
        }

        StringBuilder sql = new StringBuilder("ALTER TABLE ").append(TABLE_NAME)
                .append(" REORGANIZE PARTITION pfuture INTO (");
        for (YearMonth cursor = lastNamedMonth.plusMonths(1); !cursor.isAfter(targetMonth); cursor = cursor.plusMonths(1)) {
            LocalDate upperBound = cursor.plusMonths(1).atDay(1);
            sql.append("PARTITION ").append(partitionName(cursor))
                    .append(" VALUES LESS THAN (UNIX_TIMESTAMP('")
                    .append(upperBound.format(PARTITION_BOUND_FORMAT))
                    .append("')), ");
        }
        sql.append("PARTITION pfuture VALUES LESS THAN MAXVALUE)");

        log.info("pose_data 미래 파티션 확장 — {} 까지: {}", targetMonth, sql);
        jdbcTemplate.execute(sql.toString());
    }

    private List<PartitionInfo> fetchNamedPartitions() {
        return jdbcTemplate.query(
                "SELECT partition_name, table_rows FROM information_schema.partitions " +
                "WHERE table_schema = DATABASE() AND table_name = ? AND partition_name IS NOT NULL " +
                "AND partition_name <> 'pfuture'",
                (rs, rowNum) -> {
                    String name = rs.getString("partition_name");
                    long rows = rs.getLong("table_rows");
                    return parsePartition(name, rows);
                },
                TABLE_NAME
        ).stream().filter(Objects::nonNull).toList();
    }

    private PartitionInfo parsePartition(String name, long approxRows) {
        Matcher m = PARTITION_NAME_PATTERN.matcher(name);
        if (!m.matches()) {
            log.warn("pose_data 파티션 이름이 예상 패턴(pYYYY_MM)과 다름 — 안전을 위해 유지보수 대상에서 제외: {}", name);
            return null;
        }
        try {
            YearMonth month = YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            return new PartitionInfo(name, month, approxRows);
        } catch (java.time.DateTimeException e) {
            // 패턴은 맞지만 월 값이 유효 범위(01~12) 밖인 경우(예: p2026_13) — 스케줄러 전체가
            // 죽지 않도록 패턴 불일치와 동일하게 유지보수 대상에서만 제외
            log.warn("pose_data 파티션 이름의 월 값이 유효하지 않음 — 유지보수 대상에서 제외: {}", name, e);
            return null;
        }
    }

    private String partitionName(YearMonth month) {
        return String.format("p%04d_%02d", month.getYear(), month.getMonthValue());
    }

    record PartitionInfo(String name, YearMonth month, long approxRows) {}
}
