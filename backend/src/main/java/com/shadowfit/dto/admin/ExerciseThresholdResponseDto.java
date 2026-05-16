package com.shadowfit.dto.admin;

import com.shadowfit.model.exercise.Exercise;

import java.math.BigDecimal;

public record ExerciseThresholdResponseDto(
        Long exerciseId,
        String name,
        BigDecimal syncThresholdBeginner,
        BigDecimal syncThresholdAdvanced
) {
    public static ExerciseThresholdResponseDto fromEntity(Exercise e) {
        return new ExerciseThresholdResponseDto(
                e.getId(),
                e.getName(),
                e.getSyncThresholdBeginner(),
                e.getSyncThresholdAdvanced()
        );
    }
}