package com.shadowfit.service.group;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shadowfit.dto.group.CreateInvitationRequestDto;
import com.shadowfit.dto.group.InvitationResponseDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupInvitation;
import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.group.GroupRole;
import com.shadowfit.model.group.InvitationStatus;
import com.shadowfit.model.member.Member;
import com.shadowfit.repository.group.GroupInvitationRepository;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.group.GroupRepository;
import com.shadowfit.repository.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class GroupInvitationService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupInvitationRepository groupInvitationRepository;
    private final MemberRepository memberRepository;
    private final GroupEventService groupEventService;
    private final ObjectMapper objectMapper;

    public InvitationResponseDto invite(Long groupId, Long inviterId, CreateInvitationRequestDto request) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_FOUND));

        if (!groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(groupId, inviterId, GroupMemberStatus.ACTIVE)) {
            throw new BusinessException(ErrorCode.NOT_GROUP_MEMBER);
        }

        Member inviter = memberRepository.findById(inviterId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        Member invitee = memberRepository.findById(request.getInviteeId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(groupId, invitee.getId(), GroupMemberStatus.ACTIVE)) {
            throw new BusinessException(ErrorCode.ALREADY_GROUP_MEMBER);
        }
        if (groupInvitationRepository.existsByGroupIdAndInviteeIdAndStatus(groupId, invitee.getId(), InvitationStatus.PENDING)) {
            throw new BusinessException(ErrorCode.INVITATION_ALREADY_PENDING);
        }

        GroupInvitation invitation = groupInvitationRepository.save(GroupInvitation.builder()
                .group(group)
                .inviter(inviter)
                .invitee(invitee)
                .build());

        return InvitationResponseDto.from(invitation);
    }

    @Transactional(readOnly = true)
    public List<InvitationResponseDto> listMyInvitations(Long memberId) {
        return groupInvitationRepository.findAllByInviteeIdAndStatus(memberId, InvitationStatus.PENDING).stream()
                .map(InvitationResponseDto::from)
                .toList();
    }

    public void accept(Long invitationId, Long inviteeId) {
        GroupInvitation invitation = getRespondableInvitation(invitationId, inviteeId);

        invitation.accept();

        // leaveGroup() 은 기존 group_members 행을 LEFT 로만 남긴다 — 재초대·재수락 시 새 행을
        // 또 넣으면 UNIQUE(group_id, member_id) 위반으로 트랜잭션이 통째로 실패한다(500).
        // 기존 행(LEFT 포함)이 있으면 되살리고, 없을 때만 새로 만든다.
        GroupMember member = groupMemberRepository
                .findByGroupIdAndMemberId(invitation.getGroup().getId(), invitation.getInvitee().getId())
                .map(existing -> { existing.rejoin(); return existing; })
                .orElseGet(() -> groupMemberRepository.save(GroupMember.builder()
                        .group(invitation.getGroup())
                        .member(invitation.getInvitee())
                        .role(GroupRole.MEMBER)
                        .status(GroupMemberStatus.ACTIVE)
                        .build()));

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("memberId", member.getMember().getId());
        payload.put("username", member.getMember().getUsername());
        groupEventService.publish(invitation.getGroup().getId(), null, "MEMBER_JOINED", payload.toString());
    }

    public void decline(Long invitationId, Long inviteeId) {
        GroupInvitation invitation = getRespondableInvitation(invitationId, inviteeId);
        invitation.decline();
    }

    private GroupInvitation getRespondableInvitation(Long invitationId, Long inviteeId) {
        GroupInvitation invitation = groupInvitationRepository.findById(invitationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITATION_NOT_FOUND));

        if (!invitation.getInvitee().getId().equals(inviteeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVITATION_ALREADY_RESPONDED);
        }
        return invitation;
    }
}