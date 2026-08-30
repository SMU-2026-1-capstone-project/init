package com.shadowfit.dto.pattern;

/**
 * GET /patterns/consistency — 연속 운동일 수, 최근 4주 내 빠진 날 수 (BE-07).
 *
 * @param sufficientData PeriodicityResponseDto와 같은 기준(가입 4주 경과, 세션7).
 */
public record ConsistencyResponseDto(
        boolean sufficientData,
        int currentStreakDays,
        int missedDaysInLast4Weeks
) {}
