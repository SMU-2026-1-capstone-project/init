package com.shadowfit.dto.pattern;

/** GET /patterns/consistency — 연속 운동일 수, 최근 4주 내 빠진 날 수 (BE-07). */
public record ConsistencyResponseDto(
        int currentStreakDays,
        int missedDaysInLast4Weeks
) {}
