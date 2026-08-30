package com.shadowfit.service.goal;

import com.shadowfit.dto.goal.GoalCreateRequestDto;
import com.shadowfit.dto.goal.GoalResponseDto;
import com.shadowfit.dto.goal.GoalStatus;
import com.shadowfit.dto.goal.GoalUpdateRequestDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.goal.Goal;
import com.shadowfit.model.goal.GoalType;
import com.shadowfit.model.member.Member;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.goal.GoalRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("GoalService 테스트")
class GoalServiceTest {

    @Mock private GoalRepository goalRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private MemberRepository memberRepository;
    private GoalService service;

    private static final Long MEMBER_ID = 1L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new GoalService(goalRepository, sessionRepository, memberRepository);
    }

    @Test
    @DisplayName("createGoal — 같은 goalType이 없으면 생성한다")
    void createGoal_success() {
        Member member = mock(Member.class);
        when(goalRepository.existsByMemberIdAndGoalType(MEMBER_ID, GoalType.WEEKLY_SESSIONS)).thenReturn(false);
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(sessionRepository.findCompletedSessionWindowsSince(eq(MEMBER_ID), any())).thenReturn(List.of());

        GoalResponseDto result = service.createGoal(MEMBER_ID, new GoalCreateRequestDto(GoalType.WEEKLY_SESSIONS, 5));

        assertThat(result.goalType()).isEqualTo(GoalType.WEEKLY_SESSIONS);
        assertThat(result.targetValue()).isEqualTo(5);
        assertThat(result.currentValue()).isZero();
        verify(goalRepository).save(any(Goal.class));
    }

    @Test
    @DisplayName("createGoal — 같은 goalType이 이미 있으면 GOAL_TYPE_DUPLICATION")
    void createGoal_duplicateGoalType_throws() {
        when(goalRepository.existsByMemberIdAndGoalType(MEMBER_ID, GoalType.WEEKLY_SESSIONS)).thenReturn(true);

        assertThatThrownBy(() -> service.createGoal(MEMBER_ID, new GoalCreateRequestDto(GoalType.WEEKLY_SESSIONS, 5)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.GOAL_TYPE_DUPLICATION);
        verify(goalRepository, never()).save(any());
    }

    @Test
    @DisplayName("getGoals — 목표가 없으면 빈 리스트, 세션 조회도 안 한다")
    void getGoals_noGoals_returnsEmptyWithoutQueryingSessions() {
        when(goalRepository.findByMemberId(MEMBER_ID)).thenReturn(List.of());

        List<GoalResponseDto> result = service.getGoals(MEMBER_ID);

        assertThat(result).isEmpty();
        verify(sessionRepository, never()).findCompletedSessionWindowsSince(any(), any());
    }

    @Test
    @DisplayName("getGoals — WEEKLY_SESSIONS의 currentValue는 최근 7일 COMPLETED 세션 수")
    void getGoals_weeklySessions_countsCompletedSessions() {
        Goal goal = Goal.builder().id(10L).goalType(GoalType.WEEKLY_SESSIONS).targetValue(3).build();
        when(goalRepository.findByMemberId(MEMBER_ID)).thenReturn(List.of(goal));

        LocalDateTime now = LocalDateTime.now();
        SessionRepository.CompletedSessionWindow w1 = mockWindow(now.minusDays(1), now.minusDays(1).plusMinutes(20));
        SessionRepository.CompletedSessionWindow w2 = mockWindow(now.minusDays(2), now.minusDays(2).plusMinutes(30));
        when(sessionRepository.findCompletedSessionWindowsSince(eq(MEMBER_ID), any())).thenReturn(List.of(w1, w2));

        List<GoalResponseDto> result = service.getGoals(MEMBER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).currentValue()).isEqualTo(2);
        assertThat(result.get(0).status()).isEqualTo(GoalStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("getGoals — WEEKLY_MINUTES의 currentValue는 최근 7일 세션들의 총 분")
    void getGoals_weeklyMinutes_sumsMinutes() {
        Goal goal = Goal.builder().id(11L).goalType(GoalType.WEEKLY_MINUTES).targetValue(60).build();
        when(goalRepository.findByMemberId(MEMBER_ID)).thenReturn(List.of(goal));

        LocalDateTime now = LocalDateTime.now();
        SessionRepository.CompletedSessionWindow w1 = mockWindow(now.minusDays(1), now.minusDays(1).plusMinutes(20));
        SessionRepository.CompletedSessionWindow w2 = mockWindow(now.minusDays(2), now.minusDays(2).plusMinutes(45));
        when(sessionRepository.findCompletedSessionWindowsSince(eq(MEMBER_ID), any())).thenReturn(List.of(w1, w2));

        List<GoalResponseDto> result = service.getGoals(MEMBER_ID);

        assertThat(result.get(0).currentValue()).isEqualTo(65);
        assertThat(result.get(0).status()).isEqualTo(GoalStatus.ACHIEVED);
    }

    @Test
    @DisplayName("getGoals — 목표가 여러 개여도 세션 조회는 한 번뿐")
    void getGoals_multipleGoals_queriesSessionsOnce() {
        Goal g1 = Goal.builder().id(1L).goalType(GoalType.WEEKLY_SESSIONS).targetValue(3).build();
        Goal g2 = Goal.builder().id(2L).goalType(GoalType.WEEKLY_MINUTES).targetValue(60).build();
        when(goalRepository.findByMemberId(MEMBER_ID)).thenReturn(List.of(g1, g2));
        when(sessionRepository.findCompletedSessionWindowsSince(eq(MEMBER_ID), any())).thenReturn(List.of());

        service.getGoals(MEMBER_ID);

        verify(sessionRepository, times(1)).findCompletedSessionWindowsSince(any(), any());
    }

    @Test
    @DisplayName("getGoals — currentValue == targetValue도 ACHIEVED(경계값)")
    void getGoals_exactMatch_isAchieved() {
        Goal goal = Goal.builder().id(12L).goalType(GoalType.WEEKLY_SESSIONS).targetValue(2).build();
        when(goalRepository.findByMemberId(MEMBER_ID)).thenReturn(List.of(goal));
        LocalDateTime now = LocalDateTime.now();
        // mockWindow() 자체가 when().thenReturn()을 쓰므로, 바깥 when(...).thenReturn(...) 사이의
        // 인자 자리에서 바로 호출하면 Mockito 스터빙 상태가 꼬인다(UnfinishedStubbingException) —
        // 리스트를 먼저 완성한 뒤에 스터빙한다.
        List<SessionRepository.CompletedSessionWindow> windows =
                List.of(mockWindow(now, now.plusMinutes(10)), mockWindow(now, now.plusMinutes(10)));
        when(sessionRepository.findCompletedSessionWindowsSince(eq(MEMBER_ID), any())).thenReturn(windows);

        List<GoalResponseDto> result = service.getGoals(MEMBER_ID);

        assertThat(result.get(0).currentValue()).isEqualTo(2);
        assertThat(result.get(0).status()).isEqualTo(GoalStatus.ACHIEVED);
    }

    @Test
    @DisplayName("updateGoal — 본인 목표면 targetValue를 바꾼다")
    void updateGoal_success() {
        Goal goal = Goal.builder().id(20L).goalType(GoalType.WEEKLY_SESSIONS).targetValue(3).build();
        when(goalRepository.findByIdAndMemberId(20L, MEMBER_ID)).thenReturn(Optional.of(goal));
        when(sessionRepository.findCompletedSessionWindowsSince(eq(MEMBER_ID), any())).thenReturn(List.of());

        GoalResponseDto result = service.updateGoal(MEMBER_ID, 20L, new GoalUpdateRequestDto(10));

        assertThat(result.targetValue()).isEqualTo(10);
    }

    @Test
    @DisplayName("updateGoal — 본인 것이 아니거나 없으면 GOAL_NOT_FOUND")
    void updateGoal_notOwnedOrMissing_throws() {
        when(goalRepository.findByIdAndMemberId(99L, MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateGoal(MEMBER_ID, 99L, new GoalUpdateRequestDto(10)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.GOAL_NOT_FOUND);
    }

    @Test
    @DisplayName("deleteGoal — 본인 목표면 삭제한다")
    void deleteGoal_success() {
        Goal goal = Goal.builder().id(30L).goalType(GoalType.WEEKLY_SESSIONS).targetValue(3).build();
        when(goalRepository.findByIdAndMemberId(30L, MEMBER_ID)).thenReturn(Optional.of(goal));

        service.deleteGoal(MEMBER_ID, 30L);

        verify(goalRepository).delete(goal);
    }

    @Test
    @DisplayName("deleteGoal — 본인 것이 아니거나 없으면 GOAL_NOT_FOUND, delete 호출 안 함")
    void deleteGoal_notOwnedOrMissing_throws() {
        when(goalRepository.findByIdAndMemberId(99L, MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteGoal(MEMBER_ID, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.GOAL_NOT_FOUND);
        verify(goalRepository, never()).delete(any());
    }

    private static SessionRepository.CompletedSessionWindow mockWindow(LocalDateTime start, LocalDateTime end) {
        SessionRepository.CompletedSessionWindow w = mock(SessionRepository.CompletedSessionWindow.class);
        when(w.getStartTime()).thenReturn(start);
        when(w.getEndTime()).thenReturn(end);
        return w;
    }
}
