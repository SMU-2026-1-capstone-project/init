package com.shadowfit.service.Analysis;

import com.shadowfit.dto.pattern.PeriodicityResponseDto;
import com.shadowfit.dto.pattern.TimeBucket;
import com.shadowfit.repository.exercise.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
}
