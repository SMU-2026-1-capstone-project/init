package com.shadowfit.service.Exercise;

import com.shadowfit.dto.admin.ExerciseThresholdResponseDto;
import com.shadowfit.dto.admin.ThresholdUpdateDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.repository.exercise.ExercisesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminExerciseService {
    private final ExercisesRepository exercisesRepository;

    @Transactional
    public ExerciseThresholdResponseDto updateThresholds(Long exerciseId, ThresholdUpdateDto dto) {
        if (dto.beginner().compareTo(dto.advanced()) >= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        Exercise exercise = exercisesRepository.findById(exerciseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXERCISE_NOT_FOUND));

        log.info("운동 {} 임계값 변경: beginner {} -> {}, advanced {} -> {}",
                exerciseId,
                exercise.getSyncThresholdBeginner(), dto.beginner(),
                exercise.getSyncThresholdAdvanced(), dto.advanced());

        exercise.setSyncThresholdBeginner(dto.beginner());
        exercise.setSyncThresholdAdvanced(dto.advanced());

        return ExerciseThresholdResponseDto.fromEntity(exercise);
    }
}