package com.shadowfit.service.Exercise;

import com.shadowfit.dto.report.record.CalendarMainResponseDto;
import com.shadowfit.dto.report.record.DailyActivityResponseDto;
import com.shadowfit.dto.report.record.WeeklyActivityResponseDto;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code SessionService} 의 조회 집계 3개(getWeeklyActivity/getCalendarMain/getDailyActivity)를
 * {@link SessionActivityQueryService} 로 분리하면서(#176) {@code SessionServiceTest.Aggregation}
 * 에서 그대로 옮겨왔다 — 순수 이동이라 케이스 내용은 바뀌지 않았다.
 */
@SpringBootTest
@Transactional
@DisplayName("SessionActivityQueryService 테스트")
class SessionActivityQueryServiceTest {

    @Autowired private SessionActivityQueryService sessionActivityQueryService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private com.shadowfit.repository.exercise.CategoryRepository categoryRepository;

    private Member member;
    private Exercise exercise;

    @BeforeEach
    void setUp() {
        member = memberRepository.saveAndFlush(Member.builder()
                .email("activityquery@test.com").username("u").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        Category category = categoryRepository.save(Category.builder().name("LOWER").build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(category).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .analysisSupported(true)
                .build());
    }

    private Session completedSessionOn(LocalDate date, double avgSyncRate, double calories, int minutes) {
        LocalDateTime start = date.atTime(9, 0);
        return sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(start).endTime(start.plusMinutes(minutes))
                .status(Status.COMPLETED).totalReps(10)
                .avgSyncRate(BigDecimal.valueOf(avgSyncRate))
                .caloriesBurned(BigDecimal.valueOf(calories))
                .build());
    }

    private Session sessionOn(LocalDate date, Status status) {
        LocalDateTime start = date.atTime(9, 0);
        return sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(start).endTime(start.plusMinutes(10))
                .status(status).totalReps(10)
                .build());
    }

    @Test
    @DisplayName("getWeeklyActivity — 이번 주 세션 합산")
    void getWeeklyActivity_aggregatesThisWeek() {
        LocalDate today = LocalDate.now();
        completedSessionOn(today, 80.0, 100.0, 20);

        WeeklyActivityResponseDto result = sessionActivityQueryService.getWeeklyActivity(member.getId());

        assertThat(result.getTotalWorkouts()).isEqualTo(1);
        assertThat(result.getTotalMinutes()).isEqualTo(20);
        assertThat(result.getTotalCalories()).isEqualTo(100);
        assertThat(result.getTodayDetails()).hasSize(1);
    }

    @Test
    @DisplayName("getCalendarMain — 이번 달 운동일수·평균 싱크로율 계산")
    void getCalendarMain_aggregatesThisMonth() {
        LocalDate today = LocalDate.now();
        completedSessionOn(today, 80.0, 100.0, 20);

        CalendarMainResponseDto result = sessionActivityQueryService.getCalendarMain(
                member.getId(), today.getYear(), today.getMonthValue());

        assertThat(result.getMonthlyExerciseDays()).isEqualTo(1);
        assertThat(result.getTotalAvgSyncRate()).isEqualTo(80);
        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).isHasRecord()).isTrue();
        assertThat(result.getConsecutiveDays()).isEqualTo(1);
    }

    @Test
    @DisplayName("getCalendarMain — 연속일수는 COMPLETED가 아닌 세션도 센다 (#541 인덱스 수정이 " +
            "status IN 절에 전체 상태값을 태우지만, 그건 계획 최적화일 뿐 필터링 의미가 바뀌면 안 된다)")
    void getCalendarMain_consecutiveDaysCountsAllStatuses() {
        LocalDate today = LocalDate.now();
        completedSessionOn(today, 80.0, 100.0, 20);
        sessionOn(today.minusDays(1), Status.FAILED);
        sessionOn(today.minusDays(2), Status.IN_PROGRESS);

        CalendarMainResponseDto result = sessionActivityQueryService.getCalendarMain(
                member.getId(), today.getYear(), today.getMonthValue());

        assertThat(result.getConsecutiveDays()).isEqualTo(3);
    }

    @Test
    @DisplayName("getCalendarMain — avg_sync_rate가 null인 세션은 0점이 아니라 평균에서 제외된다")
    void getCalendarMain_nullSyncRateExcludedFromAverage() {
        LocalDate today = LocalDate.now();
        completedSessionOn(today, 80.0, 100.0, 20);
        // 분석 전/실패라 값이 없는 세션 — 0으로 치면 월평균이 40으로 반토막 난다
        LocalDateTime start = today.atTime(11, 0);
        sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(start).endTime(start.plusMinutes(10))
                .status(Status.COMPLETED).totalReps(5)
                .avgSyncRate(null)
                .caloriesBurned(BigDecimal.valueOf(50))
                .build());

        CalendarMainResponseDto result = sessionActivityQueryService.getCalendarMain(
                member.getId(), today.getYear(), today.getMonthValue());

        assertThat(result.getTotalAvgSyncRate()).isEqualTo(80);
        assertThat(result.getRecords().get(0).getDailyAvgSyncRate()).isEqualTo(80.0);
    }

    @Test
    @DisplayName("getCalendarMain — 모든 세션의 sync_rate가 null이면 평균 0으로 떨어진다")
    void getCalendarMain_allNullSyncRate_fallsBackToZero() {
        LocalDate today = LocalDate.now();
        LocalDateTime start = today.atTime(11, 0);
        sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(start).endTime(start.plusMinutes(10))
                .status(Status.COMPLETED).totalReps(5)
                .avgSyncRate(null).caloriesBurned(BigDecimal.valueOf(50))
                .build());

        CalendarMainResponseDto result = sessionActivityQueryService.getCalendarMain(
                member.getId(), today.getYear(), today.getMonthValue());

        // 평균 낼 값이 하나도 없을 때의 fallback — 운동일수는 그대로 1일로 잡혀야 한다
        assertThat(result.getTotalAvgSyncRate()).isZero();
        assertThat(result.getMonthlyExerciseDays()).isEqualTo(1);
    }

    @Test
    @DisplayName("getDailyActivity — 특정 날짜의 세션만 반환, 빈 날은 빈 리스트")
    void getDailyActivity_returnsOnlyThatDate() {
        LocalDate today = LocalDate.now();
        completedSessionOn(today, 80.0, 100.0, 20);

        DailyActivityResponseDto todayResult = sessionActivityQueryService.getDailyActivity(member.getId(), today);
        DailyActivityResponseDto yesterdayResult = sessionActivityQueryService.getDailyActivity(member.getId(), today.minusDays(1));

        assertThat(todayResult.getTotalWorkouts()).isEqualTo(1);
        assertThat(yesterdayResult.getTotalWorkouts()).isZero();
        assertThat(yesterdayResult.getSessions()).isEmpty();
    }
}
