package com.shadowfit.repository.group;

import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {

    // WebSocket 핸드셰이크 인가·그룹 조회 인가 — "이 사용자가 이 그룹의 ACTIVE 멤버인가".
    boolean existsByGroupIdAndMemberIdAndStatus(Long groupId, Long memberId, GroupMemberStatus status);

    Optional<GroupMember> findByGroupIdAndMemberId(Long groupId, Long memberId);

    // GET /groups/{groupId} 의 멤버 목록.
    List<GroupMember> findAllByGroupIdAndStatus(Long groupId, GroupMemberStatus status);

    // GET /groups/mine.
    List<GroupMember> findAllByMemberIdAndStatus(Long memberId, GroupMemberStatus status);
}