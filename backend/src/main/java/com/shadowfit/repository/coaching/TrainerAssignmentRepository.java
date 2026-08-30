package com.shadowfit.repository.coaching;

import com.shadowfit.model.coaching.TrainerAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TrainerAssignmentRepository extends JpaRepository<TrainerAssignment, Long> {

    // 세션2(SSE 스트림 인가)의 소유 검증 — "이 트레이너가 이 사용자를 담당하는가"를
    // 한 방 쿼리로 확인한다. exists 로 충분하고 엔티티를 끌어올 필요는 없다.
    boolean existsByTrainerIdAndUserId(Long trainerId, Long userId);

    // 사용자당 배정은 유니크 제약이라 최대 1건 — 담당 트레이너 조회.
    Optional<TrainerAssignment> findByUserId(Long userId);

    // 트레이너 대시보드(향후 확장) — 한 트레이너가 담당하는 전체 사용자 목록.
    List<TrainerAssignment> findAllByTrainerId(Long trainerId);
}
