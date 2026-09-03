package com.shadowfit.repository.group;

import com.shadowfit.model.group.GroupEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GroupEventRepository extends JpaRepository<GroupEvent, Long> {

    // 재연결 백필 — afterSeq 이후 이벤트를 오름차순으로 전부 반환한다(페이지네이션 없음,
    // 근거 없는 limit을 넣지 않기로 한 설계 결정 — 계획 문서 참고).
    List<GroupEvent> findAllByGroupIdAndSeqGreaterThanOrderBySeqAsc(Long groupId, Long afterSeq);
}