package com.shadowfit.dto.pattern;

import java.time.DayOfWeek;
import java.util.List;

/** GET /patterns/periodicity — 요일·시간대별 세션 분포 (BE-07). */
public record PeriodicityResponseDto(
        List<DayOfWeekCount> byDayOfWeek,
        List<HourOfDayCount> byHourOfDay
) {
    public record DayOfWeekCount(DayOfWeek dayOfWeek, long sessionCount) {}

    public record HourOfDayCount(int hour, long sessionCount) {}
}
