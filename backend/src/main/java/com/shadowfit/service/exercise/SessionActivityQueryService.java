package com.shadowfit.service.exercise;

import com.shadowfit.dto.report.detailreport.ExerciseSessionDto;
import com.shadowfit.dto.report.record.CalendarDayDto;
import com.shadowfit.dto.report.record.CalendarMainResponseDto;
import com.shadowfit.dto.report.record.DailyActivityResponseDto;
import com.shadowfit.dto.report.record.DailyLogSummaryDto;
import com.shadowfit.dto.report.record.WeeklyActivityResponseDto;
import com.shadowfit.global.util.SetSummaryFormatter;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.repository.exercise.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link SessionService} 에서 조회 전용 6개(주간/캘린더/일별 집계)를 뗀 클래스 (#176).
 *
 * <p>원래 있던 자리에서도 라이프사이클(쓰기) 로직과 공유하는 것이 {@code sessionRepository}
 * 하나뿐이었다 — 낙관적 락·아웃박스·타임아웃 판정 같은 {@link SessionService} 의 핵심 관심사와
 * 무관해서 뗐다. 같은 패키지에 이미 조회를 분리한 선례가 있다 — {@code SessionFeedbackQueryService}
 * (피드백 조회 전용) · {@code SessionQueryRepositoryImpl}(QueryDSL 동적 조회 전용).
 *
 * <p>순수 이동이라 로직은 바뀌지 않았다 — 원래 있던 주석도 그대로 옮겼다.
 */
@Service
@RequiredArgsConstructor
public class SessionActivityQueryService {
    private final SessionRepository sessionRepository;

    @Transactional(readOnly = true)
    public WeeklyActivityResponseDto getWeeklyActivity(Long memberId) {

        // 1. 이번 주 시작일(월)과 종료일(일) 계산
        LocalDate today = LocalDate.now();
        LocalDate startOfWeek = today.with(java.time.DayOfWeek.MONDAY);
        LocalDate endOfWeek = today.with(java.time.DayOfWeek.SUNDAY);

        // 2. 이번 주 모든 세션 조회 — exercise fetch join으로 N+1 방지
        List<Session> weeklySessions = sessionRepository.findWeeklySessionsWithExercise(
                memberId, startOfWeek.atStartOfDay(), endOfWeek.atTime(23, 59, 59));

        // 3. 통계 계산 (Duration 계산 시 NPE 방어)
        int totalMinutes = weeklySessions.stream()
                .mapToInt(s -> {
                    if (s.getStartTime() == null || s.getEndTime() == null) return 0;
                    return (int) java.time.Duration.between(s.getStartTime(), s.getEndTime()).toMinutes();
                })
                .sum();

        // BigDecimal -> Double 변환 최적화
        double totalCalories = weeklySessions.stream()
                .map(s -> s.getCaloriesBurned() != null ? s.getCaloriesBurned() : java.math.BigDecimal.ZERO)
                .mapToDouble(java.math.BigDecimal::doubleValue)
                .sum();

        // 4. 요일별 그래프 데이터 가공
        List<DailyLogSummaryDto> dailyLogs = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate date = startOfWeek.plusDays(i);
            int dailyMins = weeklySessions.stream()
                    .filter(s -> s.getStartTime() != null && s.getStartTime().toLocalDate().equals(date))
                    .mapToInt(this::calculateDuration) // endTime == null(진행중 세션) NPE 방어 — totalMinutes 블록과 동일 가드
                    .sum();

            dailyLogs.add(new DailyLogSummaryDto(
                    date.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.KOREAN),
                    dailyMins,
                    date.equals(today)
            ));
        }

        List<ExerciseSessionDto> todayDetails = weeklySessions.stream()
                .filter(s -> s.getStartTime() != null && s.getStartTime().toLocalDate().equals(today))
                .map(this::toSessionDto)
                .collect(Collectors.toList());

        return WeeklyActivityResponseDto.builder()
                .dateRange(String.format("%d월 %d일 - %d일",
                        startOfWeek.getMonthValue(), startOfWeek.getDayOfMonth(), endOfWeek.getDayOfMonth()))
                .totalWorkouts(weeklySessions.size())
                .totalMinutes(totalMinutes)
                .totalCalories((int) totalCalories)
                .dailyLogs(dailyLogs)
                .todayDetails(todayDetails)
                .build();
    }

    @Transactional(readOnly = true)
    public CalendarMainResponseDto getCalendarMain(Long memberId, int year, int month) {
        // 1. 해당 월의 모든 세션 조회
        LocalDate startOfMonth = LocalDate.of(year, month, 1);
        LocalDate endOfMonth = startOfMonth.with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());

        List<Session> monthlySessions = sessionRepository.findByMemberIdAndStartTimeBetween(
                memberId, startOfMonth.atStartOfDay(), endOfMonth.atTime(23, 59, 59));

        // 2. 상단 카드 데이터 계산 (평균 싱크로율)
        // avg_sync_rate 는 nullable — 분석 전/실패 세션은 값이 없다. 예전엔 null 을 0.0 으로
        // 치환해 평균에 넣었는데, 그러면 "측정 안 됨"이 "싱크로율 0%"로 집계돼 사용자에게
        // 보이는 평균이 실제보다 낮아진다. 값이 있는 세션만으로 평균을 낸다.
        double avgSyncRate = monthlySessions.stream()
                .map(Session::getAvgSyncRate)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(java.math.BigDecimal::doubleValue)
                .average()
                .orElse(0.0);

        // 3. 달력 날짜별 기록 여부 표시
        List<CalendarDayDto> dayDtos = monthlySessions.stream()
                .map(s -> s.getStartTime().toLocalDate())
                .distinct()
                .map(date -> {
                    CalendarDayDto dto = new CalendarDayDto();
                    dto.setDate(date.toString());
                    dto.setHasRecord(true);

                    // 월 평균과 같은 이유로 값 없는 세션은 평균에서 제외한다(0점으로 치지 않음)
                    double dailyAvg = monthlySessions.stream()
                            .filter(s -> s.getStartTime().toLocalDate().equals(date))
                            .map(Session::getAvgSyncRate)
                            .filter(java.util.Objects::nonNull)
                            .mapToDouble(java.math.BigDecimal::doubleValue)
                            .average()
                            .orElse(0.0);

                    dto.setDailyAvgSyncRate(dailyAvg);

                    return dto;
                })
                .collect(Collectors.toList());

        CalendarMainResponseDto response = new CalendarMainResponseDto();
        response.setMonthlyExerciseDays((int) monthlySessions.stream().map(s -> s.getStartTime().toLocalDate()).distinct().count());
        response.setTotalAvgSyncRate((int) avgSyncRate);
        response.setConsecutiveDays(calculateConsecutiveDays(memberId)); // 연속 일수 계산 유틸 호출

        response.setYear(year);   // 파라미터로 받은 year 세팅
        response.setMonth(month); // 파라미터로 받은 month 세팅

        response.setRecords(dayDtos);

        return response;
    }

    // 달력에서 특정 날짜 클릭 시 그 날의 운동 목록 조회.
    // 주간 요약의 todayDetails 와 동일한 매핑(toSessionDto)을 재사용 — 오늘/과거 날짜 구분 없이 일관.
    @Transactional(readOnly = true)
    public DailyActivityResponseDto getDailyActivity(Long memberId, LocalDate date) {
        List<Session> sessions = sessionRepository.findByMemberIdAndStartTimeBetween(
                memberId, date.atStartOfDay(), date.atTime(23, 59, 59));

        List<ExerciseSessionDto> details = sessions.stream()
                .filter(s -> s.getStartTime() != null)
                .map(this::toSessionDto)
                .collect(Collectors.toList());

        return DailyActivityResponseDto.builder()
                .date(date.toString())
                .totalWorkouts(details.size())
                .sessions(details)
                .build();
    }

    // Session → ExerciseSessionDto 공용 매핑 (주간 todayDetails / 일별 조회 공유)
    private ExerciseSessionDto toSessionDto(Session s) {
        ExerciseSessionDto detail = new ExerciseSessionDto();
        detail.setSessionId(s.getId());
        detail.setExerciseName(s.getExercise().getName());
        // 세트 표기는 SetSummaryFormatter 한 곳에서만 만든다 — 과거 여기만 "0세트"로 어긋나
        // 같은 세션이 화면마다 0/1세트로 다르게 보였음(#69).
        detail.setSetSummary(SetSummaryFormatter.format(s.getTotalReps()));
        detail.setSyncRate(s.getAvgSyncRate() != null ? s.getAvgSyncRate().doubleValue() : 0.0);
        return detail;
    }

    private int calculateDuration(Session session) {
        if (session.getStartTime() == null || session.getEndTime() == null) {
            return 0;
        }
        return (int) java.time.Duration.between(session.getStartTime(), session.getEndTime()).toMinutes();
    }

    private int calculateConsecutiveDays(Long memberId) {
        LocalDate today = LocalDate.now();

        // 최근 100일치 활동 날짜를 한 번에 조회 (루프 N+1 → 쿼리 1방)
        // status 전부(List.of(Status.values()))를 넘긴다 — 결과를 좁히려는 게 아니라
        // idx_session_member_status_start 가 status 등치 없이는 start_time 을 seek 못 해
        // 회원 전체 이력을 읽던 걸 막으려는 것(#541). 필터링 의미는 그대로다.
        Set<LocalDate> activeDates = sessionRepository.findDistinctActiveDates(
                        memberId,
                        List.of(Status.values()),
                        today.minusDays(100).atStartOfDay(),
                        today.atTime(23, 59, 59)
                ).stream()
                .map(java.sql.Date::toLocalDate)
                .collect(Collectors.toSet());

        // 오늘 기록 없으면 어제부터 체크 (오늘 아직 안 했을 수도 있으니)
        LocalDate checkDate = activeDates.contains(today) ? today : today.minusDays(1);

        int consecutiveDays = 0;
        while (activeDates.contains(checkDate)) {
            consecutiveDays++;
            checkDate = checkDate.minusDays(1);
        }
        return consecutiveDays;
    }
}
