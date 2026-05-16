package com.shadowfit.dto.exercises.feedback;

import com.shadowfit.model.exercise.FeedbackType;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record FeedbackEventDto(
        @NotNull FeedbackType feedbackType,
        BigDecimal syncRateAtTrigger,
        @NotNull LocalDateTime occurredAt
) {
}