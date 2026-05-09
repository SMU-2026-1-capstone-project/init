package com.shadowfit.service.Exercise;

import com.shadowfit.dto.exercises.feedback.FeedbackTemplateDto;
import com.shadowfit.repository.exercise.ExerciseFeedbackTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class FeedbackTemplateService {
    private final ExerciseFeedbackTemplateRepository templateRepository;

    public List<FeedbackTemplateDto> getTemplatesByExercise(Long exerciseId) {
        return templateRepository.findByExerciseIdOrderByPriorityAsc(exerciseId).stream()
                .map(FeedbackTemplateDto::fromEntity)
                .toList();
    }
}