package com.shadowfit.repository.goal;

import com.shadowfit.model.goal.Goal;
import com.shadowfit.model.goal.GoalType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GoalRepository extends JpaRepository<Goal, Long> {

    List<Goal> findByMemberId(Long memberId);

    // 소유권을 WHERE절에 박아 IDOR 차단 — SessionRepository.findByIdAndMemberId와 같은 관례.
    Optional<Goal> findByIdAndMemberId(Long goalId, Long memberId);

    // 회원당 goalType 하나(중복 생성 방지, GoalService.createGoal). 여러 목표를 동시에 두려면
    // (예: "이번 주 10회"와 "이번 주 15회" 동시 운영) 이 제약부터 풀어야 한다 — 지금은 그 요구가
    // 없어 단순하게 간다.
    boolean existsByMemberIdAndGoalType(Long memberId, GoalType goalType);
}
