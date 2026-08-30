package com.shadowfit.repository.group;

import com.shadowfit.model.group.GroupInvitation;
import com.shadowfit.model.group.InvitationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GroupInvitationRepository extends JpaRepository<GroupInvitation, Long> {

    // 초대 생성 시 중복 초대 방지 — 이미 PENDING 인 초대가 있으면 재초대를 막는다.
    boolean existsByGroupIdAndInviteeIdAndStatus(Long groupId, Long inviteeId, InvitationStatus status);

    // GET /invitations/mine.
    List<GroupInvitation> findAllByInviteeIdAndStatus(Long inviteeId, InvitationStatus status);
}