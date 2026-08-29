package com.shadowfit.service.Analysis;

import com.shadowfit.dto.pattern.ConsistencyResponseDto;
import com.shadowfit.dto.pattern.IntensityTrendResponseDto;
import com.shadowfit.dto.pattern.PeriodicityResponseDto;
import com.shadowfit.dto.pattern.TimeBucket;
import com.shadowfit.repository.exercise.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PatternAnalysisService {

    private static final int PERIODICITY_WINDOW_WEEKS = 4;

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

    // 세션 3 — 골격만. 4주 윈도우 주 단위 집계는 다음 세션에서 구현.
    public IntensityTrendResponseDto getIntensityTrend(Long memberId) {
        return new IntensityTrendResponseDto(List.of());
    }

    // 세션 4 — 골격만. 연속일수·결측일 계산은 다음 세션에서 구현.
    public ConsistencyResponseDto getConsistency(Long memberId) {
        return new ConsistencyResponseDto(0, 0);
    }
}
