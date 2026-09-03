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
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.group.GroupRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("GroupService 테스트")
class GroupServiceTest {

    private static final Long GROUP_ID = 1L;
    private static final Long MEMBER_ID = 10L;

    @Mock private GroupRepository groupRepository;
    @Mock private GroupMemberRepository groupMemberRepository;
    @Mock private MemberRepository memberRepository;

    private GroupService groupService;
    private Member creator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        groupService = new GroupService(groupRepository, groupMemberRepository, memberRepository);
        creator = newMember(MEMBER_ID, "creator");
    }

    @Test
    @DisplayName("createGroup — 생성자를 OWNER·ACTIVE로 자동 가입시킨다")
    void createGroup_addsCreatorAsOwner() {
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(creator));
        when(groupRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(groupMemberRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GroupResponseDto response = groupService.createGroup(MEMBER_ID, new CreateGroupRequestDto("그룹1"));

        assertThat(response.getName()).isEqualTo("그룹1");
        assertThat(response.getCreatedById()).isEqualTo(MEMBER_ID);

        ArgumentCaptor<GroupMember> captor = ArgumentCaptor.forClass(GroupMember.class);
        verify(groupMemberRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(GroupRole.OWNER);
        assertThat(captor.getValue().getStatus()).isEqualTo(GroupMemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("createGroup — 존재하지 않는 사용자면 USER_NOT_FOUND")
    void createGroup_unknownCreator_throws() {
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> groupService.createGroup(MEMBER_ID, new CreateGroupRequestDto("그룹1")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("getGroupDetail — 그룹이 없으면 GROUP_NOT_FOUND")
    void getGroupDetail_unknownGroup_throws() {
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> groupService.getGroupDetail(GROUP_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.GROUP_NOT_FOUND);
    }

    @Test
    @DisplayName("getGroupDetail — ACTIVE 멤버가 아니면 NOT_GROUP_MEMBER (그룹은 존재해도)")
    void getGroupDetail_notActiveMember_throws() {
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(newGroup(creator)));
        when(groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(GROUP_ID, MEMBER_ID, GroupMemberStatus.ACTIVE))
                .thenReturn(false);

        assertThatThrownBy(() -> groupService.getGroupDetail(GROUP_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
    }

    @Test
    @DisplayName("getGroupDetail — ACTIVE 멤버면 멤버 목록을 포함해 반환한다")
    void getGroupDetail_activeMember_returnsDetail() {
        Group group = newGroup(creator);
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));
        when(groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(GROUP_ID, MEMBER_ID, GroupMemberStatus.ACTIVE))
                .thenReturn(true);
        GroupMember membership = GroupMember.builder()
                .group(group).member(creator).role(GroupRole.OWNER).status(GroupMemberStatus.ACTIVE).build();
        when(groupMemberRepository.findAllByGroupIdAndStatus(GROUP_ID, GroupMemberStatus.ACTIVE))
                .thenReturn(List.of(membership));

        GroupDetailResponseDto detail = groupService.getGroupDetail(GROUP_ID, MEMBER_ID);

        assertThat(detail.getMembers()).hasSize(1);
        assertThat(detail.getMembers().get(0).getMemberId()).isEqualTo(MEMBER_ID);
    }

    @Test
    @DisplayName("leaveGroup — ACTIVE 멤버십을 LEFT로 전환한다")
    void leaveGroup_activeMembership_transitionsToLeft() {
        Group group = newGroup(creator);
        GroupMember membership = GroupMember.builder()
                .group(group).member(creator).role(GroupRole.MEMBER).status(GroupMemberStatus.ACTIVE).build();
        when(groupMemberRepository.findByGroupIdAndMemberId(GROUP_ID, MEMBER_ID)).thenReturn(Optional.of(membership));

        groupService.leaveGroup(GROUP_ID, MEMBER_ID);

        assertThat(membership.getStatus()).isEqualTo(GroupMemberStatus.LEFT);
    }

    @Test
    @DisplayName("leaveGroup — 가입 이력이 없으면 NOT_GROUP_MEMBER")
    void leaveGroup_noMembership_throws() {
        when(groupMemberRepository.findByGroupIdAndMemberId(GROUP_ID, MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> groupService.leaveGroup(GROUP_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
    }

    @Test
    @DisplayName("leaveGroup — 이미 LEFT 상태면 다시 탈퇴할 수 없다(NOT_GROUP_MEMBER)")
    void leaveGroup_alreadyLeft_throws() {
        Group group = newGroup(creator);
        GroupMember membership = GroupMember.builder()
                .group(group).member(creator).role(GroupRole.MEMBER).status(GroupMemberStatus.LEFT).build();
        when(groupMemberRepository.findByGroupIdAndMemberId(GROUP_ID, MEMBER_ID)).thenReturn(Optional.of(membership));

        assertThatThrownBy(() -> groupService.leaveGroup(GROUP_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
    }

    @Test
    @DisplayName("assertActiveMember — ACTIVE 멤버면 예외 없이 통과한다")
    void assertActiveMember_activeMember_passes() {
        when(groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(GROUP_ID, MEMBER_ID, GroupMemberStatus.ACTIVE))
                .thenReturn(true);

        assertThatCode(() -> groupService.assertActiveMember(GROUP_ID, MEMBER_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("assertActiveMember — ACTIVE 멤버가 아니면 NOT_GROUP_MEMBER")
    void assertActiveMember_notActiveMember_throws() {
        when(groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(GROUP_ID, MEMBER_ID, GroupMemberStatus.ACTIVE))
                .thenReturn(false);

        assertThatThrownBy(() -> groupService.assertActiveMember(GROUP_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
    }

    private Group newGroup(Member creator) {
        return Group.builder().id(GROUP_ID).name("그룹").createdBy(creator).build();
    }

    private Member newMember(Long id, String username) {
        return Member.builder().id(id).email(username + "@test.com").username(username)
                .password("encoded-password").role(UserRole.USER).build();
    }
}
