package com.shadowfit.service.Analysis;

import com.shadowfit.dto.pattern.ConsistencyResponseDto;
import com.shadowfit.dto.pattern.IntensityTrendResponseDto;
import com.shadowfit.dto.pattern.PeriodicityResponseDto;
import com.shadowfit.dto.pattern.TimeBucket;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.repository.exercise.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("PatternAnalysisService 테스트")
class PatternAnalysisServiceTest {

    @Mock private SessionRepository sessionRepository;
    private PatternAnalysisService service;

    private static final Long MEMBER_ID = 1L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new PatternAnalysisService(sessionRepository);
    }

    @Test
    @DisplayName("getPeriodicity — 요일별로 세션 수를 집계한다")
    void getPeriodicity_groupsByDayOfWeek() {
        // 같은 화요일 2건, 목요일 1건
        List<LocalDateTime> startTimes = List.of(
                LocalDateTime.of(2026, 8, 4, 7, 0),  // 화
                LocalDateTime.of(2026, 8, 11, 7, 0), // 화
                LocalDateTime.of(2026, 8, 6, 7, 0)   // 목
        );
        when(sessionRepository.findStartTimesByMemberAndRange(eq(MEMBER_ID), any(), any()))
                .thenReturn(startTimes);

        PeriodicityResponseDto result = service.getPeriodicity(MEMBER_ID);

        assertThat(result.byDayOfWeek()).hasSize(2);
        assertThat(result.byDayOfWeek())
                .filteredOn(d -> d.dayOfWeek() == DayOfWeek.TUESDAY)
                .extracting(PeriodicityResponseDto.DayOfWeekCount::sessionCount)
                .containsExactly(2L);
        assertThat(result.byDayOfWeek())
                .filteredOn(d -> d.dayOfWeek() == DayOfWeek.THURSDAY)
                .extracting(PeriodicityResponseDto.DayOfWeekCount::sessionCount)
                .containsExactly(1L);
    }

    @Test
    @DisplayName("getPeriodicity — 시간대 4구간(아침/오후/저녁/밤)으로 세션 수를 집계한다")
    void getPeriodicity_groupsByTimeBucket() {
        List<LocalDateTime> startTimes = List.of(
                LocalDateTime.of(2026, 8, 4, 6, 30),   // 아침(05~11)
                LocalDateTime.of(2026, 8, 5, 13, 0),   // 오후(11~17)
                LocalDateTime.of(2026, 8, 6, 19, 0),   // 저녁(17~22)
                LocalDateTime.of(2026, 8, 7, 23, 30),  // 밤(22~05)
                LocalDateTime.of(2026, 8, 8, 4, 0)     // 밤(22~05)
        );
        when(sessionRepository.findStartTimesByMemberAndRange(eq(MEMBER_ID), any(), any()))
                .thenReturn(startTimes);

        PeriodicityResponseDto result = service.getPeriodicity(MEMBER_ID);

        assertThat(result.byTimeOfDay()).hasSize(4);
        assertThat(result.byTimeOfDay())
                .filteredOn(t -> t.bucket() == TimeBucket.NIGHT)
                .extracting(PeriodicityResponseDto.TimeOfDayCount::sessionCount)
                .containsExactly(2L);
        assertThat(result.byTimeOfDay())
                .filteredOn(t -> t.bucket() == TimeBucket.MORNING)
                .extracting(PeriodicityResponseDto.TimeOfDayCount::sessionCount)
                .containsExactly(1L);
    }

    @Test
    @DisplayName("getPeriodicity — 세션이 없으면 두 분포 모두 빈 리스트")
    void getPeriodicity_noSessions_returnsEmpty() {
        when(sessionRepository.findStartTimesByMemberAndRange(eq(MEMBER_ID), any(), any()))
                .thenReturn(List.of());

        PeriodicityResponseDto result = service.getPeriodicity(MEMBER_ID);

        assertThat(result.byDayOfWeek()).isEmpty();
        assertThat(result.byTimeOfDay()).isEmpty();
    }

    @Test
    @DisplayName("getPeriodicity — 조회 윈도우는 현재 시각 기준 최근 4주다")
    void getPeriodicity_queriesLast4Weeks() {
        when(sessionRepository.findStartTimesByMemberAndRange(eq(MEMBER_ID), any(), any()))
                .thenReturn(List.of());

        LocalDateTime before = LocalDateTime.now();
        service.getPeriodicity(MEMBER_ID);
        LocalDateTime after = LocalDateTime.now();

        ArgumentCaptor<LocalDateTime> startCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.Mockito.verify(sessionRepository)
                .findStartTimesByMemberAndRange(eq(MEMBER_ID), startCaptor.capture(), endCaptor.capture());

        assertThat(endCaptor.getValue()).isBetween(before, after);
        assertThat(startCaptor.getValue()).isBetween(before.minusWeeks(4), after.minusWeeks(4));
    }

    @Test
    @DisplayName("getIntensityTrend — 항상 4주 고정 배열을 월요일 시작으로 오름차순 반환한다")
    void getIntensityTrend_returnsFourWeeksInOrder() {
        when(sessionRepository.findIntensitySamplesByMemberAndRange(eq(MEMBER_ID), any(), any()))
                .thenReturn(List.of());

        IntensityTrendResponseDto result = service.getIntensityTrend(MEMBER_ID);

        assertThat(result.weeklyTrend()).hasSize(4);
        LocalDate thisMonday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        assertThat(result.weeklyTrend().get(3).weekStart()).isEqualTo(thisMonday);
        assertThat(result.weeklyTrend().get(0).weekStart()).isEqualTo(thisMonday.minusWeeks(3));
        for (int i = 1; i < 4; i++) {
            assertThat(result.weeklyTrend().get(i).weekStart())
                    .isEqualTo(result.weeklyTrend().get(i - 1).weekStart().plusWeeks(1));
        }
    }

    @Test
    @DisplayName("getIntensityTrend — 세션 없는 주는 avgSyncRate=null, totalMinutes=0")
    void getIntensityTrend_emptyWeek_nullAvgZeroMinutes() {
        when(sessionRepository.findIntensitySamplesByMemberAndRange(eq(MEMBER_ID), any(), any()))
                .thenReturn(List.of());

        IntensityTrendResponseDto result = service.getIntensityTrend(MEMBER_ID);

        assertThat(result.weeklyTrend()).allSatisfy(w -> {
            assertThat(w.avgSyncRate()).isNull();
            assertThat(w.totalMinutes()).isZero();
        });
    }

    @Test
    @DisplayName("getIntensityTrend — 같은 주 샘플들의 syncRate 평균과 총 분을 집계한다")
    void getIntensityTrend_aggregatesWithinWeek() {
        LocalDate thisMonday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDateTime day1 = thisMonday.atTime(7, 0);
        LocalDateTime day2 = thisMonday.plusDays(2).atTime(7, 0);

        SessionRepository.IntensitySample s1 = mock(SessionRepository.IntensitySample.class);
        when(s1.getStartTime()).thenReturn(day1);
        when(s1.getEndTime()).thenReturn(day1.plusMinutes(20));
        when(s1.getAvgSyncRate()).thenReturn(new BigDecimal("80.00"));

        SessionRepository.IntensitySample s2 = mock(SessionRepository.IntensitySample.class);
        when(s2.getStartTime()).thenReturn(day2);
        when(s2.getEndTime()).thenReturn(day2.plusMinutes(30));
        when(s2.getAvgSyncRate()).thenReturn(new BigDecimal("90.00"));

        when(sessionRepository.findIntensitySamplesByMemberAndRange(eq(MEMBER_ID), any(), any()))
                .thenReturn(List.of(s1, s2));

        IntensityTrendResponseDto result = service.getIntensityTrend(MEMBER_ID);

        IntensityTrendResponseDto.WeeklyIntensity thisWeek = result.weeklyTrend().get(3);
        assertThat(thisWeek.weekStart()).isEqualTo(thisMonday);
        assertThat(thisWeek.avgSyncRate()).isEqualByComparingTo("85.00");
        assertThat(thisWeek.totalMinutes()).isEqualTo(50);
    }

    @Test
    @DisplayName("getConsistency — COMPLETED만 활동일로 카운트한다(Status.COMPLETED로 조회)")
    void getConsistency_queriesCompletedOnly() {
        when(sessionRepository.findDistinctActiveDates(eq(MEMBER_ID), eq(List.of(Status.COMPLETED)), any(), any()))
                .thenReturn(List.of());

        service.getConsistency(MEMBER_ID);

        org.mockito.Mockito.verify(sessionRepository)
                .findDistinctActiveDates(eq(MEMBER_ID), eq(List.of(Status.COMPLETED)), any(), any());
    }

    @Test
    @DisplayName("getConsistency — 오늘까지 연속이면 그 일수를 스트릭으로 반환한다")
    void getConsistency_streakEndingToday() {
        LocalDate today = LocalDate.now();
        List<java.sql.Date> activeDates = List.of(
                java.sql.Date.valueOf(today),
                java.sql.Date.valueOf(today.minusDays(1)),
                java.sql.Date.valueOf(today.minusDays(2))
        );
        when(sessionRepository.findDistinctActiveDates(eq(MEMBER_ID), eq(List.of(Status.COMPLETED)), any(), any()))
                .thenReturn(activeDates);

        ConsistencyResponseDto result = service.getConsistency(MEMBER_ID);

        assertThat(result.currentStreakDays()).isEqualTo(3);
    }

    @Test
    @DisplayName("getConsistency — 오늘 활동이 없어도 어제까지 이어졌으면 스트릭을 유지한다")
    void getConsistency_lenientOnToday() {
        LocalDate today = LocalDate.now();
        List<java.sql.Date> activeDates = List.of(
                java.sql.Date.valueOf(today.minusDays(1)),
                java.sql.Date.valueOf(today.minusDays(2))
        );
        when(sessionRepository.findDistinctActiveDates(eq(MEMBER_ID), eq(List.of(Status.COMPLETED)), any(), any()))
                .thenReturn(activeDates);

        ConsistencyResponseDto result = service.getConsistency(MEMBER_ID);

        assertThat(result.currentStreakDays()).isEqualTo(2);
    }

    @Test
    @DisplayName("getConsistency — 오늘도 어제도 활동이 없으면 스트릭은 0")
    void getConsistency_gapBreaksStreak() {
        LocalDate today = LocalDate.now();
        List<java.sql.Date> activeDates = List.of(java.sql.Date.valueOf(today.minusDays(3)));
        when(sessionRepository.findDistinctActiveDates(eq(MEMBER_ID), eq(List.of(Status.COMPLETED)), any(), any()))
                .thenReturn(activeDates);

        ConsistencyResponseDto result = service.getConsistency(MEMBER_ID);

        assertThat(result.currentStreakDays()).isZero();
    }

    @Test
    @DisplayName("getConsistency — 결측일은 28에서 활동일 수를 뺀 값이다")
    void getConsistency_missedDaysIsWindowMinusActive() {
        LocalDate today = LocalDate.now();
        List<java.sql.Date> activeDates = List.of(
                java.sql.Date.valueOf(today),
                java.sql.Date.valueOf(today.minusDays(5)),
                java.sql.Date.valueOf(today.minusDays(10))
        );
        when(sessionRepository.findDistinctActiveDates(eq(MEMBER_ID), eq(List.of(Status.COMPLETED)), any(), any()))
                .thenReturn(activeDates);

        ConsistencyResponseDto result = service.getConsistency(MEMBER_ID);

        assertThat(result.missedDaysInLast4Weeks()).isEqualTo(28 - 3);
    }
}
