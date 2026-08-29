package com.shadowfit.service.Analysis;

import com.shadowfit.dto.pattern.ConsistencyResponseDto;
import com.shadowfit.dto.pattern.IntensityTrendResponseDto;
import com.shadowfit.dto.pattern.PeriodicityResponseDto;
import com.shadowfit.dto.pattern.TimeBucket;
import com.shadowfit.repository.exercise.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PatternAnalysisService {

    private static final int PERIODICITY_WINDOW_WEEKS = 4;
    private static final int INTENSITY_TREND_WEEKS = 4;

    private final SessionRepository sessionRepository;

    // 요일·시간대 그룹핑 집계. 최근 4주 고정(2026-08-30 사용자 확인 — intensity-trend와 창을 맞춰
    // 세 endpoint의 "최근 패턴"이라는 취지를 일관되게 유지).
    public PeriodicityResponseDto getPeriodicity(Long memberId) {
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusWeeks(PERIODICITY_WINDOW_WEEKS);

        List<LocalDateTime> startTimes = sessionRepository.findStartTimesByMemberAndRange(memberId, start, end);

        Map<DayOfWeek, Long> byDay = startTimes.stream()
                .collect(Collectors.groupingBy(LocalDateTime::getDayOfWeek, Collectors.counting()));
        Map<TimeBucket, Long> byBucket = startTimes.stream()
                .collect(Collectors.groupingBy(t -> TimeBucket.of(t.toLocalTime()), Collectors.counting()));

        List<PeriodicityResponseDto.DayOfWeekCount> dayCounts = byDay.entrySet().stream()
                .map(e -> new PeriodicityResponseDto.DayOfWeekCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(PeriodicityResponseDto.DayOfWeekCount::dayOfWeek))
                .toList();
        List<PeriodicityResponseDto.TimeOfDayCount> bucketCounts = byBucket.entrySet().stream()
                .map(e -> new PeriodicityResponseDto.TimeOfDayCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(PeriodicityResponseDto.TimeOfDayCount::bucket))
                .toList();

        return new PeriodicityResponseDto(dayCounts, bucketCounts);
    }

    // 주 단위 평균 syncRate·총 운동 시간 추세. 월요일 시작 주 4개 고정 배열(2026-08-30 사용자
    // 확인) — 진행 중인 이번 주(월~오늘)를 마지막 버킷으로 포함한다. syncRate가 null인 세션(미완료·
    // rep 미측정)은 두 지표 모두에서 제외 — findIntensitySamplesByMemberAndRange가 DB에서 이미 거른다.
    public IntensityTrendResponseDto getIntensityTrend(Long memberId) {
        LocalDate thisMonday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate windowStartDate = thisMonday.minusWeeks(INTENSITY_TREND_WEEKS - 1L);
        LocalDateTime windowStart = windowStartDate.atStartOfDay();
        LocalDateTime windowEnd = LocalDateTime.now();

        List<SessionRepository.IntensitySample> samples =
                sessionRepository.findIntensitySamplesByMemberAndRange(memberId, windowStart, windowEnd);

        Map<LocalDate, List<SessionRepository.IntensitySample>> byWeek = samples.stream()
                .collect(Collectors.groupingBy(s -> weekStartOf(s.getStartTime())));

        // 세션 없는 주도 avgSyncRate=null·totalMinutes=0으로 채워 항상 4칸을 반환한다
        // (2026-08-30 사용자 확인 — null과 0을 구분하는 이 프로젝트 관례 유지, SessionReportResponseDto 참고).
        List<IntensityTrendResponseDto.WeeklyIntensity> weeklyTrend = new ArrayList<>();
        for (int i = 0; i < INTENSITY_TREND_WEEKS; i++) {
            LocalDate weekStart = windowStartDate.plusWeeks(i);
            List<SessionRepository.IntensitySample> weekSamples = byWeek.getOrDefault(weekStart, List.of());
            weeklyTrend.add(new IntensityTrendResponseDto.WeeklyIntensity(
                    weekStart, averageSyncRate(weekSamples), totalMinutes(weekSamples)));
        }
        return new IntensityTrendResponseDto(weeklyTrend);
    }

    private static LocalDate weekStartOf(LocalDateTime time) {
        return time.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static BigDecimal averageSyncRate(List<SessionRepository.IntensitySample> samples) {
        if (samples.isEmpty()) {
            return null;
        }
        BigDecimal sum = samples.stream()
                .map(SessionRepository.IntensitySample::getAvgSyncRate)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(samples.size()), 2, RoundingMode.HALF_UP);
    }

    private static int totalMinutes(List<SessionRepository.IntensitySample> samples) {
        return (int) samples.stream()
                .mapToLong(s -> Duration.between(s.getStartTime(), s.getEndTime()).toMinutes())
                .sum();
    }

    // 세션 4 — 골격만. 연속일수·결측일 계산은 다음 세션에서 구현.
    public ConsistencyResponseDto getConsistency(Long memberId) {
        return new ConsistencyResponseDto(0, 0);
    }
}
