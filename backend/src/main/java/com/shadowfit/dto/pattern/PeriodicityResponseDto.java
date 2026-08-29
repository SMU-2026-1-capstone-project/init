package com.shadowfit.dto.pattern;

import java.time.DayOfWeek;
import java.util.List;

/**
 * GET /patterns/periodicity — 요일·시간대별 세션 분포 (BE-07). 최근 4주 고정 윈도우.
 * 한 건도 없는 요일·시간대는 목록에서 빠진다(SessionRepository.countGroupedByStatus와 같은 관례
 * — 호출부/프론트가 0으로 채운다).
 */
public record PeriodicityResponseDto(
        List<DayOfWeekCount> byDayOfWeek,
        List<TimeOfDayCount> byTimeOfDay
) {
    public record DayOfWeekCount(DayOfWeek dayOfWeek, long sessionCount) {}

    public record TimeOfDayCount(TimeBucket bucket, long sessionCount) {}
}
