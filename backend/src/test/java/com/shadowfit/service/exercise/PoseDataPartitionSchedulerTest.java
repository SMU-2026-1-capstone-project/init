package com.shadowfit.service.exercise;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * PoseDataPartitionScheduler 단위 테스트 — TTL 보존 정책(report-read-path.md §9-B) 결정 로직.
 * jdbcTemplate.query()의 ResultSet 매핑은 건드리지 않고, "주어진 파티션 목록 + 현재 월"에서
 * 뭘 드롭하고 뭘 새로 만드는지 순수 결정 로직만 검증한다(fetchNamedPartitions는 얇은 JDBC 배선).
 */
@DisplayName("pose_data 파티션 TTL 스케줄러 테스트")
class PoseDataPartitionSchedulerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private PoseDataPartitionScheduler scheduler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // 보존 1개월, lookahead 2개월 (2026-07-24 확정값)
        scheduler = new PoseDataPartitionScheduler(jdbcTemplate, 1, 2);
    }

    private PoseDataPartitionScheduler.PartitionInfo partition(String yyyymm, long rows) {
        YearMonth month = YearMonth.parse(yyyymm.substring(0, 4) + "-" + yyyymm.substring(4));
        return new PoseDataPartitionScheduler.PartitionInfo("p" + yyyymm.substring(0, 4) + "_" + yyyymm.substring(4), month, rows);
    }

    @Test
    @DisplayName("이번 달 + 버퍼 1개월은 보존, 그보다 오래된 파티션만 드롭")
    void dropsOnlyPartitionsOlderThanBuffer() {
        YearMonth currentMonth = YearMonth.of(2026, 7);
        List<PoseDataPartitionScheduler.PartitionInfo> partitions = List.of(
                partition("202601", 100),
                partition("202605", 200),
                partition("202606", 300), // 버퍼(지난 1개월) — 보존
                partition("202607", 400)  // 이번 달 — 보존
        );

        scheduler.dropExpiredPartitions(partitions, currentMonth);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).execute(sqlCaptor.capture());
        List<String> executed = sqlCaptor.getAllValues();
        assertThat(executed).anySatisfy(sql -> assertThat(sql).contains("DROP PARTITION p2026_01"));
        assertThat(executed).anySatisfy(sql -> assertThat(sql).contains("DROP PARTITION p2026_05"));
        assertThat(executed).noneMatch(sql -> sql.contains("p2026_06"));
        assertThat(executed).noneMatch(sql -> sql.contains("p2026_07"));
    }

    @Test
    @DisplayName("드롭 대상이 없으면 DROP PARTITION을 실행하지 않음")
    void noDropWhenNothingExpired() {
        YearMonth currentMonth = YearMonth.of(2026, 7);
        List<PoseDataPartitionScheduler.PartitionInfo> partitions = List.of(
                partition("202606", 100),
                partition("202607", 200)
        );

        scheduler.dropExpiredPartitions(partitions, currentMonth);

        verify(jdbcTemplate, never()).execute(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("미래 파티션이 lookahead보다 부족하면 REORGANIZE로 채움")
    void reorganizesWhenLookaheadInsufficient() {
        YearMonth currentMonth = YearMonth.of(2026, 7);
        // 마지막 실명 파티션이 2026-07(이번 달)까지만 있음 — lookahead 2개월(2026-09)까지 부족
        List<PoseDataPartitionScheduler.PartitionInfo> partitions = List.of(
                partition("202606", 100),
                partition("202607", 200)
        );

        scheduler.ensureFuturePartitions(partitions, currentMonth);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("REORGANIZE PARTITION pfuture INTO");
        assertThat(sql).contains("PARTITION p2026_08 VALUES LESS THAN (UNIX_TIMESTAMP('2026-09-01 00:00:00'))");
        assertThat(sql).contains("PARTITION p2026_09 VALUES LESS THAN (UNIX_TIMESTAMP('2026-10-01 00:00:00'))");
        assertThat(sql).contains("PARTITION pfuture VALUES LESS THAN MAXVALUE");
    }

    @Test
    @DisplayName("이미 lookahead만큼 미래 파티션이 있으면 REORGANIZE 안 함")
    void noReorganizeWhenLookaheadAlreadySatisfied() {
        YearMonth currentMonth = YearMonth.of(2026, 7);
        // 2026-09(currentMonth+2)까지 이미 있음
        List<PoseDataPartitionScheduler.PartitionInfo> partitions = List.of(
                partition("202607", 100),
                partition("202608", 100),
                partition("202609", 100)
        );

        scheduler.ensureFuturePartitions(partitions, currentMonth);

        verify(jdbcTemplate, never()).execute(org.mockito.ArgumentMatchers.anyString());
    }
}
