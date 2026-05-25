package com.shadowfit.dto.exercises.feedback;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.shadowfit.model.exercise.FeedbackType;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FeedbackEventDto(
        @NotNull FeedbackType feedbackType,
        BigDecimal syncRateAtTrigger,
        @NotNull LocalDateTime occurredAt
) {
}