package com.shadowfit.service.Report;

import com.shadowfit.dto.report.weekly.WeeklySummaryResponseDto;
import com.shadowfit.dto.report.weekly.WeeklyTotalsDto;
import com.shadowfit.repository.report.WeeklySummaryQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 주간 요약 서비스 테스트 — <b>주 경계</b>가 이 서비스의 실질적인 로직 전부다.
 *
 * <p>경계를 반열린 구간(월요일 00:00 포함 ~ 다음 월요일 00:00 미포함)으로 잡는 이유:
 * 닫힌 구간으로 하면 일요일 23:59:59 이후의 세션이 어느 주에도 안 들어가거나 두 주에 겹친다.
 * 그 실수는 «주간 합계가 조용히 틀리는» 형태로 나타나 사후에 안 보인다.
 */
@DisplayName("주간 요약 서비스")
class WeeklySummaryServiceTest {

    @Mock
    private WeeklySummaryQueryRepository repository;

    private WeeklySummaryService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new WeeklySummaryService(repository);
        when(repository.totalsBetween(any(), any(), any())).thenReturn(WeeklyTotalsDto.empty());
    }

    @Test
    @DisplayName("수요일을 기준일로 주면 그 주 월요일부터 다음 월요일까지를 잡는다")
    void 주_경계() {
        // 2026-08-19 는 수요일
        service.getWeeklySummary(7L, LocalDate.of(2026, 8, 19));

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository, times(2)).totalsBetween(eq(7L), from.capture(), to.capture());

        // 첫 호출 = 이번 주 (월 08-17 00:00 ~ 월 08-24 00:00)
        assertThat(from.getAllValues().get(0)).isEqualTo(LocalDate.of(2026, 8, 17).atStartOfDay());
        assertThat(to.getAllValues().get(0)).isEqualTo(LocalDate.of(2026, 8, 24).atStartOfDay());

        // 둘째 호출 = 지난주 (월 08-10 00:00 ~ 월 08-17 00:00)
        assertThat(from.getAllValues().get(1)).isEqualTo(LocalDate.of(2026, 8, 10).atStartOfDay());
        assertThat(to.getAllValues().get(1)).isEqualTo(LocalDate.of(2026, 8, 17).atStartOfDay());
    }

    @Test
    @DisplayName("월요일을 기준일로 주면 그 날이 주의 시작이다 — 한 주 앞으로 밀리지 않는다")
    void 월요일_경계() {
        // 2026-08-17 은 월요일. previousOrSame 이라 그대로여야 한다
        service.getWeeklySummary(7L, LocalDate.of(2026, 8, 17));

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository, times(2)).totalsBetween(eq(7L), from.capture(), any());

        assertThat(from.getAllValues().get(0)).isEqualTo(LocalDate.of(2026, 8, 17).atStartOfDay());
    }

    @Test
    @DisplayName("일요일을 기준일로 주면 그 주에 남는다 — 다음 주로 넘어가지 않는다")
    void 일요일_경계() {
        // 2026-08-23 은 일요일. 이 날의 운동은 08-17 주에 속해야 한다
        service.getWeeklySummary(7L, LocalDate.of(2026, 8, 23));

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository, times(2)).totalsBetween(eq(7L), from.capture(), any());

        assertThat(from.getAllValues().get(0)).isEqualTo(LocalDate.of(2026, 8, 17).atStartOfDay());
    }

    @Test
    @DisplayName("두 기간의 구간이 맞닿는다 — 겹치지도, 벌어지지도 않는다")
    void 구간이_맞닿는다() {
        service.getWeeklySummary(7L, LocalDate.of(2026, 8, 19));

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository, times(2)).totalsBetween(eq(7L), from.capture(), to.capture());

        // 지난주의 끝 == 이번 주의 시작. 이 등식이 깨지면 세션이 새거나 두 번 세어진다.
        assertThat(to.getAllValues().get(1)).isEqualTo(from.getAllValues().get(0));
    }

    @Test
    @DisplayName("기록이 없어도 예외를 던지지 않고 빈 집계와 문장을 돌려준다")
    void 기록_없음은_정상이다() {
        WeeklySummaryResponseDto response = service.getWeeklySummary(7L, LocalDate.of(2026, 8, 19));

        assertThat(response.thisWeek().isEmpty()).isTrue();
        assertThat(response.sentences()).isNotEmpty();
        assertThat(response.periodStart()).isEqualTo(LocalDate.of(2026, 8, 17));
        assertThat(response.periodEnd()).isEqualTo(LocalDate.of(2026, 8, 24));
    }

    @Test
    @DisplayName("두 평균의 차이를 응답이 그대로 들고 있다 — 「이번 주 평균」의 정의를 나중에 재서 정하기 위해")
    void 가중치_차이가_보존된다() {
        WeeklyTotalsDto thisWeek = new WeeklyTotalsDto(
                3, 35, new BigDecimal("85.71"), new BigDecimal("85.00"), 3);
        when(repository.totalsBetween(any(), any(), any())).thenReturn(thisWeek);

        WeeklySummaryResponseDto response = service.getWeeklySummary(7L, LocalDate.of(2026, 8, 19));

        // 🔴 두 평균이 다르다는 사실 자체가 측정 결과다(report-generation-llm.md §4).
        //    응답에서 둘 다 보이지 않으면 나중에 어느 쪽을 쓸지 «재서» 정할 수가 없다.
        assertThat(response.thisWeek().repWeightedSyncRate()).isEqualByComparingTo("85.71");
        assertThat(response.thisWeek().sessionWeightedSyncRate()).isEqualByComparingTo("85.00");
        assertThat(response.thisWeek().weightingGap()).isEqualByComparingTo("0.71");
    }

    @Test
    @DisplayName("기준일을 안 주면 오늘이 속한 주를 잡는다")
    void 기준일_없음() {
        service.getWeeklySummary(7L, null);

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository, times(2)).totalsBetween(eq(7L), from.capture(), any());

        LocalDate expected = LocalDate.now()
                .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        assertThat(from.getAllValues().get(0)).isEqualTo(expected.atStartOfDay());
    }

    @Test
    @DisplayName("문장은 «사실·방향» 만 말한다 — 평가하는 말이 섞이지 않는다")
    void 평가하지_않는다() {
        List<String> sentences = service.getWeeklySummary(7L, LocalDate.of(2026, 8, 19)).sentences();

        String joined = String.join(" ", sentences);
        assertThat(joined).doesNotContain("무너");
        assertThat(joined).doesNotContain("좋아요");
    }
}
