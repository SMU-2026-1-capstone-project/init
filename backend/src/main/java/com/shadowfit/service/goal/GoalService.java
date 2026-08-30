package com.shadowfit.service.goal;

import com.shadowfit.dto.goal.GoalCreateRequestDto;
import com.shadowfit.dto.goal.GoalResponseDto;
import com.shadowfit.dto.goal.GoalStatus;
import com.shadowfit.dto.goal.GoalUpdateRequestDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.goal.Goal;
import com.shadowfit.model.member.Member;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.goal.GoalRepository;
import com.shadowfit.repository.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 운동 목표 CRUD + 진척 조회 (BE-06). 세션 완료 시점에 currentValue를 갱신하는 코드는 이 서비스에도
 * {@code SessionService.applyComplete}에도 없다 — rolling window(최근 7일)를 조회 시점마다 직접
 * 계산하는 방식으로 확정했기 때문(goal-domain-design.md §4 (c), 2026-08-30 사용자 confirm:
 * "매 조회 시 7일 넘은 값은 자동 제외" 중 저장·재집계 안 하는 쪽). 세션 완료 이벤트가 건드리는 건
 * dailyLogService·precomputeReport 둘뿐으로 그대로 남는다.
 */
@Service
@RequiredArgsConstructor
public class GoalService {

    private static final int ROLLING_WINDOW_DAYS = 7;

    private final GoalRepository goalRepository;
    private final SessionRepository sessionRepository;
    private final MemberRepository memberRepository;

    @Transactional
    public GoalResponseDto createGoal(Long memberId, GoalCreateRequestDto request) {
        if (goalRepository.existsByMemberIdAndGoalType(memberId, request.goalType())) {
            throw new BusinessException(ErrorCode.GOAL_TYPE_DUPLICATION);
        }
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Goal goal = Goal.builder()
                .member(member)
                .goalType(request.goalType())
                .targetValue(request.targetValue())
                .build();
        goalRepository.save(goal);

        return toResponseDto(goal, currentValueOf(goal, fetchWindow(memberId)));
    }

    @Transactional(readOnly = true)
    public List<GoalResponseDto> getGoals(Long memberId) {
        List<Goal> goals = goalRepository.findByMemberId(memberId);
        if (goals.isEmpty()) {
            return List.of();
        }

        // 목표가 몇 개든 세션 조회는 한 번만 — WEEKLY_SESSIONS·WEEKLY_MINUTES 둘 다 같은
        // 원본(최근 7일 COMPLETED 세션)에서 파생되므로 goalType마다 다시 쿼리할 이유가 없다.
        List<SessionRepository.CompletedSessionWindow> window = fetchWindow(memberId);

        return goals.stream()
                .map(goal -> toResponseDto(goal, currentValueOf(goal, window)))
                .toList();
    }

    @Transactional
    public GoalResponseDto updateGoal(Long memberId, Long goalId, GoalUpdateRequestDto request) {
        Goal goal = goalRepository.findByIdAndMemberId(goalId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GOAL_NOT_FOUND));

        goal.updateTargetValue(request.targetValue());

        return toResponseDto(goal, currentValueOf(goal, fetchWindow(memberId)));
    }

    @Transactional
    public void deleteGoal(Long memberId, Long goalId) {
        Goal goal = goalRepository.findByIdAndMemberId(goalId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GOAL_NOT_FOUND));

        goalRepository.delete(goal);
    }

    private List<SessionRepository.CompletedSessionWindow> fetchWindow(Long memberId) {
        LocalDateTime since = LocalDateTime.now().minusDays(ROLLING_WINDOW_DAYS);
        return sessionRepository.findCompletedSessionWindowsSince(memberId, since);
    }

    private static int currentValueOf(Goal goal, List<SessionRepository.CompletedSessionWindow> window) {
        return switch (goal.getGoalType()) {
            case WEEKLY_SESSIONS -> window.size();
            case WEEKLY_MINUTES -> (int) window.stream()
                    .mapToLong(w -> Duration.between(w.getStartTime(), w.getEndTime()).toMinutes())
                    .sum();
        };
    }

    private static GoalResponseDto toResponseDto(Goal goal, int currentValue) {
        GoalStatus status = currentValue >= goal.getTargetValue() ? GoalStatus.ACHIEVED : GoalStatus.IN_PROGRESS;
        return new GoalResponseDto(
                goal.getId(), goal.getGoalType(), goal.getTargetValue(), currentValue, status, goal.getCreatedAt());
    }
}
