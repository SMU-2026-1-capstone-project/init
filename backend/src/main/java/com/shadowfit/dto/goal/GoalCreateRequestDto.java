package com.shadowfit.dto.goal;

import com.shadowfit.model.goal.GoalType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record GoalCreateRequestDto(
        @NotNull(message = "목표 종류는 필수입니다.")
        GoalType goalType,

        @Positive(message = "목표값은 0보다 커야 합니다.")
        int targetValue
) {
}
