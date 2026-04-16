package com.shadowfit.repository;

import com.shadowfit.model.exercise.ExerciseReference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExerciseReferenceRepository extends JpaRepository<ExerciseReference, Long> {
    List<ExerciseReference> findByExerciseId(Long exerciseId);
}