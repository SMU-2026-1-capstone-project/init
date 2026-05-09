package com.shadowfit.repository.exercise;

import com.shadowfit.model.exercise.ExerciseFeedbackTemplate;
import com.shadowfit.model.exercise.FeedbackType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExerciseFeedbackTemplateRepository extends JpaRepository<ExerciseFeedbackTemplate, Long> {

    List<ExerciseFeedbackTemplate> findByExerciseIdOrderByPriorityAsc(Long exerciseId);

    Optional<ExerciseFeedbackTemplate> findByExerciseIdAndFeedbackType(Long exerciseId, FeedbackType feedbackType);
}