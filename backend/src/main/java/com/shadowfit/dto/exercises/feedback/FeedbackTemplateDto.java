package com.shadowfit.dto.exercises.feedback;

import com.shadowfit.model.exercise.ExerciseFeedbackTemplate;
import com.shadowfit.model.exercise.FeedbackType;

public record FeedbackTemplateDto(
        FeedbackType feedbackType,
        String message,
        Integer priority
) {
    public static FeedbackTemplateDto fromEntity(ExerciseFeedbackTemplate t) {
        return new FeedbackTemplateDto(
                t.getFeedbackType(),
                t.getMessage(),
                t.getPriority()
        );
    }
}