package com.shadowfit.dto.goal;

import com.shadowfit.model.goal.GoalType;

import java.time.LocalDateTime;

/**
 * GET/POST/PATCH /goals 공통 응답 (BE-06). currentValue·status는 저장된 값이 아니라
 * GoalService가 조회 시점에 계산해 채운다(rolling window, 2026-08-30 사용자 confirm).
 */
public record GoalResponseDto(
        Long id,
        GoalType goalType,
        int targetValue,
        int currentValue,
        GoalStatus status,
        LocalDateTime createdAt
) {
}
