package com.shadowfit.repository.exercise;

import com.shadowfit.model.exercise.Exercise;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ExercisesRepository extends JpaRepository<Exercise,Long> {

    // 읽기 전용 캐시 조회. findById(관리자 쓰기 경로)와 분리 — 캐시가 반환하는 detached 엔티티에
    // setter로 값을 바꿔도 dirty-checking이 안 걸려 저장이 조용히 무시되기 때문에, 임계값을
    // 갱신하는 AdminExerciseService.updateThresholds는 이 메서드를 쓰면 안 됨(findById 유지).
    @Cacheable(cacheNames = "exercises", key = "#id")
    @Query("SELECT e FROM Exercise e WHERE e.id = :id")
    Optional<Exercise> findByIdCached(@Param("id") Long id);

    // 카테고리 삭제 전 사용 여부 확인용 (AdminCategoryService, #CATEGORY_IN_USE).
    boolean existsByCategoryId(Long categoryId);
}
