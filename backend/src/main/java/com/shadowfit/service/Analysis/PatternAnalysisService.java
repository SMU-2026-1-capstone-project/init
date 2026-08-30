package com.shadowfit.service.Analysis;

import com.shadowfit.dto.pattern.ConsistencyResponseDto;
import com.shadowfit.dto.pattern.IntensityTrendResponseDto;
import com.shadowfit.dto.pattern.PeriodicityResponseDto;
import com.shadowfit.dto.pattern.TimeBucket;
import com.shadowfit.model.exercise.Status;
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
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PatternAnalysisService {

    private static final int PERIODICITY_WINDOW_WEEKS = 4;
    private static final int INTENSITY_TREND_WEEKS = 4;
    private static final int CONSISTENCY_WINDOW_DAYS = 28;

    // 세 endpoint 공통 "데이터 충분" 기준(세션7, 2026-08-30 사용자 확인) — 각 endpoint의 조회
    // 윈도우(4주=28일)와 같은 길이. 가입일 기준이라 실제 세션 수와는 무관하다: 가입 4주 미만이면
    // 세션이 많아도 false(창 자체가 아직 안 채워짐), 4주 이상이면 세션이 0건이어도 true
    // (그건 "충분히 오래 썼는데 활동이 없다"는 별개의 사실이라 결측일·스트릭 0으로 그대로 표현).
    private static final int MIN_ACCOUNT_AGE_DAYS_FOR_SUFFICIENT_DATA = 28;

    private final SessionRepository sessionRepository;

    private static boolean hasSufficientData(LocalDateTime memberCreatedAt) {
        return memberCreatedAt.isBefore(LocalDateTime.now().minusDays(MIN_ACCOUNT_AGE_DAYS_FOR_SUFFICIENT_DATA));
    }

    // 요일·시간대 그룹핑 집계. 최근 4주 고정(2026-08-30 사용자 확인 — intensity-trend와 창을 맞춰
    // 세 endpoint의 "최근 패턴"이라는 취지를 일관되게 유지).
    public PeriodicityResponseDto getPeriodicity(Long memberId, LocalDateTime memberCreatedAt) {
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

        return new PeriodicityResponseDto(hasSufficientData(memberCreatedAt), dayCounts, bucketCounts);
    }

    // 주 단위 평균 syncRate·총 운동 시간 추세. 월요일 시작 주 4개 고정 배열(2026-08-30 사용자
    // 확인) — 진행 중인 이번 주(월~오늘)를 마지막 버킷으로 포함한다. syncRate가 null인 세션(미완료·
    // rep 미측정)은 두 지표 모두에서 제외 — findIntensitySamplesByMemberAndRange가 DB에서 이미 거른다.
    public IntensityTrendResponseDto getIntensityTrend(Long memberId, LocalDateTime memberCreatedAt) {
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
        return new IntensityTrendResponseDto(hasSufficientData(memberCreatedAt), weeklyTrend);
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

    // 연속 운동일 수·결측일. "활동일"은 COMPLETED만 카운트(2026-08-30 사용자 확인 — periodicity의
    // 전체 status 관례와 달리, 시작만 하고 중단한 세션까지 "운동함"으로 잡으면 스트릭이 부풀어
    // consistency 취지와 안 맞음). 윈도우는 rolling 28일(오늘 포함, 사용자 확인).
    //
    // ⚠️ 윈도우가 28일이라 streak도 최대 28로 캡된다 — 28일 내내 매일 COMPLETED면 실제로 더 길게
    // 이어졌어도 이 endpoint에서는 28로 보인다. BE-07 원 문서가 애초에 "최소 4주" 프레임을 전제해
    // 이 endpoint의 다른 두 지표(periodicity·intensity-trend)와 창을 맞췄다.
    public ConsistencyResponseDto getConsistency(Long memberId, LocalDateTime memberCreatedAt) {
        LocalDate today = LocalDate.now();
        LocalDate windowStartDate = today.minusDays(CONSISTENCY_WINDOW_DAYS - 1L);
        LocalDateTime windowStart = windowStartDate.atStartOfDay();
        LocalDateTime windowEnd = LocalDateTime.now();

        List<java.sql.Date> activeDates = sessionRepository.findDistinctActiveDates(
                memberId, List.of(Status.COMPLETED), windowStart, windowEnd);
        Set<LocalDate> activeDaySet = activeDates.stream()
                .map(java.sql.Date::toLocalDate)
                .collect(Collectors.toSet());

        int missedDays = CONSISTENCY_WINDOW_DAYS - activeDaySet.size();
        int streak = calculateStreak(activeDaySet, today);

        return new ConsistencyResponseDto(hasSufficientData(memberCreatedAt), streak, missedDays);
    }

    // 오늘 활동이 없어도 어제까지 이어졌으면 스트릭을 유지한다(관대한 정의, 2026-08-30 사용자 확인)
    // — 조회 시점(아침/밤)에 따라 스트릭이 끊긴 것처럼 보이는 걸 방지.
    private static int calculateStreak(Set<LocalDate> activeDaySet, LocalDate today) {
        LocalDate anchor;
        if (activeDaySet.contains(today)) {
            anchor = today;
        } else if (activeDaySet.contains(today.minusDays(1))) {
            anchor = today.minusDays(1);
        } else {
            return 0;
        }
        int streak = 0;
        LocalDate cursor = anchor;
        while (activeDaySet.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }
}
