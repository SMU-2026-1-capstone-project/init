package com.shadowfit.repository.exercise;

import com.shadowfit.model.exercise.ExerciseReference;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExerciseReferenceRepository extends JpaRepository<ExerciseReference, Long> {

    // 이 조회는 saveReferencePoses(쓰기 경로)에서 재사용되지 않고 gRPC 전송용 읽기 전용 경로에서만
    // 쓰이므로, exercises의 findByIdCached와 달리 findByExerciseId 자체에 바로 캐시를 붙여도 안전.
    @Cacheable(cacheNames = "exerciseReferences", key = "#exerciseId")
    List<ExerciseReference> findByExerciseId(Long exerciseId);

    /**
     * 이 종목에 기준 좌표가 하나라도 있는가 — 관리자 분석 활성화 가드
     * ({@code AdminExerciseService.updateAnalysisSupport}).
     *
     * <p><b>일부러 캐시를 안 붙였다.</b> 위 {@code findByExerciseId} 는 gRPC 전송용 읽기 경로라
     * 캐시가 맞지만, 이쪽은 "방금 추출한 기준 좌표가 들어왔는가"를 판정하는 자리다. 캐시된 값을
     * 보면 추출 직후 활성화가 근거 없이 거부될 수 있다.
     */
    boolean existsByExerciseId(Long exerciseId);
}