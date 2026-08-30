package com.shadowfit.repository.group;

import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupInvitation;
import com.shadowfit.model.group.InvitationStatus;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@DisplayName("GroupInvitationRepository 테스트")
class GroupInvitationRepositoryTest {

    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupInvitationRepository groupInvitationRepository;
    @Autowired private MemberRepository memberRepository;

    private Group group;
    private Member inviter;
    private Member invitee;

    @BeforeEach
    void setUp() {
        inviter = memberRepository.saveAndFlush(newMember("inviter@test.com", "inviter"));
        invitee = memberRepository.saveAndFlush(newMember("invitee@test.com", "invitee"));
        group = groupRepository.saveAndFlush(Group.builder().name("그룹").createdBy(inviter).build());
    }

    @Test
    @DisplayName("PENDING 초대가 있으면 existsByGroupIdAndInviteeIdAndStatus(PENDING) 가 true — 중복 초대 방지 근거")
    void existsPending_afterInvite_isTrue() {
        groupInvitationRepository.saveAndFlush(newInvitation());

        assertThat(groupInvitationRepository.existsByGroupIdAndInviteeIdAndStatus(
                group.getId(), invitee.getId(), InvitationStatus.PENDING)).isTrue();
    }

    @Test
    @DisplayName("초대가 ACCEPTED 로 바뀌면 existsByGroupIdAndInviteeIdAndStatus(PENDING) 가 false — 재초대 가능해짐")
    void existsPending_afterAccepted_isFalse() {
        GroupInvitation invitation = groupInvitationRepository.saveAndFlush(newInvitation());
        invitation.accept();
        groupInvitationRepository.saveAndFlush(invitation);

        assertThat(groupInvitationRepository.existsByGroupIdAndInviteeIdAndStatus(
                group.getId(), invitee.getId(), InvitationStatus.PENDING)).isFalse();
    }

    @Test
    @DisplayName("findAllByInviteeIdAndStatus(PENDING) — 응답 완료된 초대는 제외한다")
    void findAllByInviteeIdAndStatus_excludesRespondedInvitations() {
        GroupInvitation pending = groupInvitationRepository.saveAndFlush(newInvitation());

        Member otherInviter = memberRepository.saveAndFlush(newMember("other-inviter@test.com", "other-inviter"));
        Group otherGroup = groupRepository.saveAndFlush(Group.builder().name("그룹2").createdBy(otherInviter).build());
        GroupInvitation declined = groupInvitationRepository.saveAndFlush(GroupInvitation.builder()
                .group(otherGroup).inviter(otherInviter).invitee(invitee).build());
        declined.decline();
        groupInvitationRepository.saveAndFlush(declined);

        assertThat(groupInvitationRepository.findAllByInviteeIdAndStatus(invitee.getId(), InvitationStatus.PENDING))
                .extracting(GroupInvitation::getId)
                .containsExactly(pending.getId());
    }

    private GroupInvitation newInvitation() {
        return GroupInvitation.builder().group(group).inviter(inviter).invitee(invitee).build();
    }

    private Member newMember(String email, String username) {
        return Member.builder().email(email).username(username)
                .password("encoded-password").role(UserRole.USER).build();
    }
}
