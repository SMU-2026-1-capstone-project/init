package com.shadowfit.service.group;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.group.CreateInvitationRequestDto;
import com.shadowfit.dto.group.InvitationResponseDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupInvitation;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.group.InvitationStatus;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.group.GroupInvitationRepository;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.group.GroupRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("GroupInvitationService 테스트")
class GroupInvitationServiceTest {

    private static final Long GROUP_ID = 1L;
    private static final Long INVITER_ID = 10L;
    private static final Long INVITEE_ID = 20L;
    private static final Long INVITATION_ID = 100L;

    @Mock private GroupRepository groupRepository;
    @Mock private GroupMemberRepository groupMemberRepository;
    @Mock private GroupInvitationRepository groupInvitationRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private GroupEventService groupEventService;

    private GroupInvitationService service;
    private Group group;
    private Member inviter;
    private Member invitee;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new GroupInvitationService(groupRepository, groupMemberRepository, groupInvitationRepository,
                memberRepository, groupEventService, new ObjectMapper());

        inviter = newMember(INVITER_ID, "inviter");
        invitee = newMember(INVITEE_ID, "invitee");
        group = Group.builder().id(GROUP_ID).name("그룹").createdBy(inviter).build();
    }

    @Test
    @DisplayName("invite — ACTIVE 멤버가 아니면 NOT_GROUP_MEMBER")
    void invite_inviterNotActiveMember_throws() {
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));
        when(groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(GROUP_ID, INVITER_ID, GroupMemberStatus.ACTIVE))
                .thenReturn(false);

        assertThatThrownBy(() -> service.invite(GROUP_ID, INVITER_ID, new CreateInvitationRequestDto(INVITEE_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
    }

    @Test
    @DisplayName("invite — 이미 ACTIVE 멤버인 사용자를 초대하면 ALREADY_GROUP_MEMBER")
    void invite_inviteeAlreadyMember_throws() {
        stubActiveInviter();
        when(memberRepository.findById(INVITER_ID)).thenReturn(Optional.of(inviter));
        when(memberRepository.findById(INVITEE_ID)).thenReturn(Optional.of(invitee));
        when(groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(GROUP_ID, INVITEE_ID, GroupMemberStatus.ACTIVE))
                .thenReturn(true);

        assertThatThrownBy(() -> service.invite(GROUP_ID, INVITER_ID, new CreateInvitationRequestDto(INVITEE_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ALREADY_GROUP_MEMBER);
    }

    @Test
    @DisplayName("invite — 이미 PENDING 초대가 있으면 INVITATION_ALREADY_PENDING")
    void invite_alreadyPendingInvitation_throws() {
        stubActiveInviter();
        when(memberRepository.findById(INVITER_ID)).thenReturn(Optional.of(inviter));
        when(memberRepository.findById(INVITEE_ID)).thenReturn(Optional.of(invitee));
        when(groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(GROUP_ID, INVITEE_ID, GroupMemberStatus.ACTIVE))
                .thenReturn(false);
        when(groupInvitationRepository.existsByGroupIdAndInviteeIdAndStatus(GROUP_ID, INVITEE_ID, InvitationStatus.PENDING))
                .thenReturn(true);

        assertThatThrownBy(() -> service.invite(GROUP_ID, INVITER_ID, new CreateInvitationRequestDto(INVITEE_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVITATION_ALREADY_PENDING);
    }

    @Test
    @DisplayName("invite — 정상 초대는 PENDING 상태로 저장된다")
    void invite_success_savesPendingInvitation() {
        stubActiveInviter();
        when(memberRepository.findById(INVITER_ID)).thenReturn(Optional.of(inviter));
        when(memberRepository.findById(INVITEE_ID)).thenReturn(Optional.of(invitee));
        when(groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(GROUP_ID, INVITEE_ID, GroupMemberStatus.ACTIVE))
                .thenReturn(false);
        when(groupInvitationRepository.existsByGroupIdAndInviteeIdAndStatus(GROUP_ID, INVITEE_ID, InvitationStatus.PENDING))
                .thenReturn(false);
        when(groupInvitationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InvitationResponseDto response = service.invite(GROUP_ID, INVITER_ID, new CreateInvitationRequestDto(INVITEE_ID));

        assertThat(response.getStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(response.getGroupId()).isEqualTo(GROUP_ID);
        assertThat(response.getInviterId()).isEqualTo(INVITER_ID);
    }

    @Test
    @DisplayName("listMyInvitations — PENDING 초대만 반환한다")
    void listMyInvitations_returnsPendingOnly() {
        GroupInvitation invitation = GroupInvitation.builder().group(group).inviter(inviter).invitee(invitee).build();
        when(groupInvitationRepository.findAllByInviteeIdAndStatus(INVITEE_ID, InvitationStatus.PENDING))
                .thenReturn(List.of(invitation));

        assertThat(service.listMyInvitations(INVITEE_ID)).hasSize(1);
    }

    @Test
    @DisplayName("accept — 초대받은 사람이 아니면 ACCESS_DENIED")
    void accept_wrongInvitee_throws() {
        GroupInvitation invitation = GroupInvitation.builder().group(group).inviter(inviter).invitee(invitee).build();
        when(groupInvitationRepository.findById(INVITATION_ID)).thenReturn(Optional.of(invitation));

        assertThatThrownBy(() -> service.accept(INVITATION_ID, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCESS_DENIED);
    }

    @Test
    @DisplayName("accept — 이미 응답한 초대면 INVITATION_ALREADY_RESPONDED")
    void accept_alreadyResponded_throws() {
        GroupInvitation invitation = GroupInvitation.builder().group(group).inviter(inviter).invitee(invitee).build();
        invitation.accept();
        when(groupInvitationRepository.findById(INVITATION_ID)).thenReturn(Optional.of(invitation));

        assertThatThrownBy(() -> service.accept(INVITATION_ID, INVITEE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVITATION_ALREADY_RESPONDED);
    }

    @Test
    @DisplayName("accept — 존재하지 않는 초대면 INVITATION_NOT_FOUND")
    void accept_unknownInvitation_throws() {
        when(groupInvitationRepository.findById(INVITATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.accept(INVITATION_ID, INVITEE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVITATION_NOT_FOUND);
    }

    @Test
    @DisplayName("accept — 성공 시 그룹 멤버로 가입되고 MEMBER_JOINED 이벤트가 발행된다(발신자 없음)")
    void accept_success_joinsAndPublishesEvent() {
        GroupInvitation invitation = GroupInvitation.builder().group(group).inviter(inviter).invitee(invitee).build();
        when(groupInvitationRepository.findById(INVITATION_ID)).thenReturn(Optional.of(invitation));
        when(groupMemberRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.accept(INVITATION_ID, INVITEE_ID);

        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        // 시스템이 발행하는 이벤트라 senderId 는 null 이어야 한다 — GroupEventService.publish 계약.
        verify(groupEventService).publish(eq(GROUP_ID), isNull(), eq("MEMBER_JOINED"), anyString());
    }

    @Test
    @DisplayName("decline — 성공 시 상태만 바뀌고 이벤트는 발행하지 않는다")
    void decline_success_doesNotPublishEvent() {
        GroupInvitation invitation = GroupInvitation.builder().group(group).inviter(inviter).invitee(invitee).build();
        when(groupInvitationRepository.findById(INVITATION_ID)).thenReturn(Optional.of(invitation));

        service.decline(INVITATION_ID, INVITEE_ID);

        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.DECLINED);
        verify(groupEventService, never()).publish(anyLong(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("decline — 초대받은 사람이 아니면 ACCESS_DENIED")
    void decline_wrongInvitee_throws() {
        GroupInvitation invitation = GroupInvitation.builder().group(group).inviter(inviter).invitee(invitee).build();
        when(groupInvitationRepository.findById(INVITATION_ID)).thenReturn(Optional.of(invitation));

        assertThatThrownBy(() -> service.decline(INVITATION_ID, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCESS_DENIED);
    }

    private void stubActiveInviter() {
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));
        when(groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(GROUP_ID, INVITER_ID, GroupMemberStatus.ACTIVE))
                .thenReturn(true);
    }

    private Member newMember(Long id, String username) {
        return Member.builder().id(id).email(username + "@test.com").username(username)
                .password("encoded-password").role(UserRole.USER).build();
    }
}
