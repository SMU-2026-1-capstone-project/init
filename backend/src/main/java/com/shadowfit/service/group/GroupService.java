package com.shadowfit.service.group;

import com.shadowfit.dto.group.CreateGroupRequestDto;
import com.shadowfit.dto.group.GroupDetailResponseDto;
import com.shadowfit.dto.group.GroupResponseDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.group.GroupRole;
import com.shadowfit.model.member.Member;
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
public class GroupService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final MemberRepository memberRepository;

    public GroupResponseDto createGroup(Long creatorId, CreateGroupRequestDto request) {
        Member creator = memberRepository.findById(creatorId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Group group = groupRepository.save(Group.builder()
                .name(request.getName())
                .createdBy(creator)
                .build());

        groupMemberRepository.save(GroupMember.builder()
                .group(group)
                .member(creator)
                .role(GroupRole.OWNER)
                .status(GroupMemberStatus.ACTIVE)
                .build());

        return GroupResponseDto.from(group);
    }

    @Transactional(readOnly = true)
    public List<GroupResponseDto> listMyGroups(Long memberId) {
        return groupMemberRepository.findAllByMemberIdAndStatus(memberId, GroupMemberStatus.ACTIVE).stream()
                .map(GroupMember::getGroup)
                .map(GroupResponseDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public GroupDetailResponseDto getGroupDetail(Long groupId, Long requesterId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_FOUND));

        assertActiveMember(groupId, requesterId);

        List<GroupMember> members = groupMemberRepository.findAllByGroupIdAndStatus(groupId, GroupMemberStatus.ACTIVE);
        return GroupDetailResponseDto.from(group, members);
    }

    public void leaveGroup(Long groupId, Long memberId) {
        GroupMember membership = groupMemberRepository.findByGroupIdAndMemberId(groupId, memberId)
                .filter(gm -> gm.getStatus() == GroupMemberStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_GROUP_MEMBER));

        membership.leave();
    }

    // 백필 등 다른 컨트롤러 엔드포인트에서도 "그룹 멤버만 접근 가능"을 재사용한다.
    @Transactional(readOnly = true)
    public void assertActiveMember(Long groupId, Long memberId) {
        if (!groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(groupId, memberId, GroupMemberStatus.ACTIVE)) {
            throw new BusinessException(ErrorCode.NOT_GROUP_MEMBER);
        }
    }
}