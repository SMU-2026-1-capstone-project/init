package com.shadowfit.service.Exercise;

import com.shadowfit.dto.exercises.VideoRequestDto;
import com.shadowfit.dto.report.record.CalendarMainResponseDto;
import com.shadowfit.dto.report.record.DailyActivityResponseDto;
import com.shadowfit.dto.report.record.WeeklyActivityResponseDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseCategory;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * SessionService 통합테스트 — 오늘 다룬 deleteSession/applyComplete(precompute) 외에 지금까지
 * 무테스트였던 나머지 메서드들: createSession(활성세션 락), getWeeklyActivity/getCalendarMain/
 * getDailyActivity(집계 조회), endSession(자체 단위 검증 + afterCommit AI 통보 트리거).
 */
@SpringBootTest
@Transactional
@DisplayName("SessionService 테스트")
class SessionServiceTest {

    @Autowired private SessionService sessionService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @MockitoBean private ExerciseAnalysisService analysisService; // endSession의 afterCommit 트리거 격리 검증용

    private Member member;
    private Exercise exercise;

    @BeforeEach
    void setUp() {
        member = memberRepository.saveAndFlush(Member.builder()
                .email("sessionsvc@test.com").username("u").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(ExerciseCategory.LOWER).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .build());
    }

    @Nested
    @DisplayName("createSession")
    class CreateSession {

        @Test
        @DisplayName("정상 생성 — IN_PROGRESS 세션 저장됨")
        void createSession_success() {
            VideoRequestDto dto = VideoRequestDto.builder().exerciseId(exercise.getId()).build();

            Session result = sessionService.createSession(dto, member.getId(), "https://youtu.be/dummy");

            assertThat(result.getId()).isNotNull();
            assertThat(result.getStatus()).isEqualTo(Status.IN_PROGRESS);
            assertThat(result.getMember().getId()).isEqualTo(member.getId());
        }

        @Test
        @DisplayName("이미 진행 중인 세션이 있으면 SESSION_ALREADY_IN_PROGRESS")
        void createSession_activeSessionExists_throws() {
            VideoRequestDto dto = VideoRequestDto.builder().exerciseId(exercise.getId()).build();
            sessionService.createSession(dto, member.getId(), "https://youtu.be/dummy");

            assertThatThrownBy(() -> sessionService.createSession(dto, member.getId(), "https://youtu.be/dummy"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.SESSION_ALREADY_IN_PROGRESS);
        }

        @Test
        @DisplayName("존재하지 않는 회원이면 USER_NOT_FOUND")
        void createSession_unknownMember_throws() {
            VideoRequestDto dto = VideoRequestDto.builder().exerciseId(exercise.getId()).build();

            assertThatThrownBy(() -> sessionService.createSession(dto, 999999L, "https://youtu.be/dummy"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("존재하지 않는 운동이면 EXERCISE_NOT_FOUND")
        void createSession_unknownExercise_throws() {
            VideoRequestDto dto = VideoRequestDto.builder().exerciseId(999999L).build();

            assertThatThrownBy(() -> sessionService.createSession(dto, member.getId(), "https://youtu.be/dummy"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXERCISE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("endSession")
    class EndSession {

        private Session inProgressSession() {
            return sessionRepository.saveAndFlush(Session.builder()
                    .member(member).exercise(exercise).startTime(LocalDateTime.now().minusMinutes(10))
                    .status(Status.IN_PROGRESS).totalReps(0).difficultyLevel(1).build());
        }

        @Test
        @DisplayName("본인 세션 종료 — endTime 기록됨")
        void endSession_self_setsEndTime() {
            Session session = inProgressSession();

            sessionService.endSession(session.getId(), member.getId());

            assertThat(sessionRepository.findById(session.getId()).orElseThrow().getEndTime()).isNotNull();
        }

        @Test
        @DisplayName("본인 세션이 아니면 ACCESS_DENIED")
        void endSession_notOwner_throws() {
            Session session = inProgressSession();

            assertThatThrownBy(() -> sessionService.endSession(session.getId(), 999999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ACCESS_DENIED);
        }

        @Test
        @DisplayName("존재하지 않는 세션이면 SESSION_NOT_FOUND")
        void endSession_unknownSession_throws() {
            assertThatThrownBy(() -> sessionService.endSession(999999L, member.getId()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.SESSION_NOT_FOUND);
        }

        @Test
        @DisplayName("이미 종료된 세션 재호출은 멱등 — endTime 안 바뀜, AI 재통보도 정확히 1회만(중복 등록 안 됨)")
        void endSession_alreadyEnded_isIdempotent() {
            Session session = inProgressSession();
            sessionService.endSession(session.getId(), member.getId());
            LocalDateTime firstEndTime = sessionRepository.findById(session.getId()).orElseThrow().getEndTime();

            sessionService.endSession(session.getId(), member.getId()); // 멱등 경로 — 동기화 재등록 안 해야 함

            assertThat(sessionRepository.findById(session.getId()).orElseThrow().getEndTime()).isEqualTo(firstEndTime);

            // CodeRabbit 지적 반영(2026-07-24): 이전엔 afterCommit을 시뮬레이션 안 해서
            // stopAnalysis가 애초에 호출될 일이 없어 "재통보 없음"을 증명하지 못했음 — 커밋
            // 시뮬레이션 후 정확히 1회(첫 endSession분)만 호출됐는지 직접 검증.
            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            syncs.forEach(TransactionSynchronization::afterCommit);

            verify(analysisService, times(1)).stopAnalysis(session.getId());
        }

        @Test
        @DisplayName("커밋 시점에만 AI 분석 중단(stopAnalysis)이 트리거됨")
        void endSession_triggersStopAnalysisOnlyAfterCommit() {
            Session session = inProgressSession();

            sessionService.endSession(session.getId(), member.getId());

            verify(analysisService, never()).stopAnalysis(session.getId()); // 커밋 전이라 아직

            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            syncs.forEach(TransactionSynchronization::afterCommit); // 커밋 시뮬레이션

            verify(analysisService, times(1)).stopAnalysis(session.getId());
        }
    }

    @Nested
    @DisplayName("조회 집계 (getWeeklyActivity / getCalendarMain / getDailyActivity)")
    class Aggregation {

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

        @Test
        @DisplayName("getWeeklyActivity — 이번 주 세션 합산")
        void getWeeklyActivity_aggregatesThisWeek() {
            LocalDate today = LocalDate.now();
            completedSessionOn(today, 80.0, 100.0, 20);

            WeeklyActivityResponseDto result = sessionService.getWeeklyActivity(member.getId());

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

            CalendarMainResponseDto result = sessionService.getCalendarMain(member.getId(), today.getYear(), today.getMonthValue());

            assertThat(result.getMonthlyExerciseDays()).isEqualTo(1);
            assertThat(result.getTotalAvgSyncRate()).isEqualTo(80);
            assertThat(result.getRecords()).hasSize(1);
            assertThat(result.getRecords().get(0).isHasRecord()).isTrue();
        }

        @Test
        @DisplayName("getDailyActivity — 특정 날짜의 세션만 반환, 빈 날은 빈 리스트")
        void getDailyActivity_returnsOnlyThatDate() {
            LocalDate today = LocalDate.now();
            completedSessionOn(today, 80.0, 100.0, 20);

            DailyActivityResponseDto todayResult = sessionService.getDailyActivity(member.getId(), today);
            DailyActivityResponseDto yesterdayResult = sessionService.getDailyActivity(member.getId(), today.minusDays(1));

            assertThat(todayResult.getTotalWorkouts()).isEqualTo(1);
            assertThat(yesterdayResult.getTotalWorkouts()).isZero();
            assertThat(yesterdayResult.getSessions()).isEmpty();
        }
    }
}
