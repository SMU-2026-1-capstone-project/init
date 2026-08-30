package com.shadowfit.dto.pattern;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /patterns/intensity-trend — 최근 4주 주 단위 평균 syncRate·총 운동 시간 추세 (BE-07).
 *
 * @param sufficientData PeriodicityResponseDto와 같은 기준(가입 4주 경과, 세션7).
 */
public record IntensityTrendResponseDto(
        boolean sufficientData,
        List<WeeklyIntensity> weeklyTrend
) {
    public record WeeklyIntensity(LocalDate weekStart, BigDecimal avgSyncRate, int totalMinutes) {}
}
