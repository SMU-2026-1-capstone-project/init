package com.shadowfit.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.group.CreateInvitationRequestDto;
import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupEvent;
import com.shadowfit.model.group.GroupInvitation;
import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.group.GroupRole;
import com.shadowfit.model.group.InvitationStatus;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.group.GroupEventRepository;
import com.shadowfit.repository.group.GroupInvitationRepository;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.group.GroupRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GroupInvitationController 통합테스트 — 초대 발송·수락·거절을 HTTP 레벨로 검증한다.
 * accept()가 실제로 {@code group_events}에 MEMBER_JOINED를 남기는지까지 확인한다 —
 * 그게 재연결 백필({@code GroupControllerIntegrationTest}의 이웃 기능)의 유일한 근거라서다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("GroupInvitationController 통합테스트")
class GroupInvitationControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private GroupInvitationRepository groupInvitationRepository;
    @Autowired private GroupEventRepository groupEventRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Member owner;
    private Member invitee;
    private Group group;
    private String ownerToken;
    private String inviteeToken;

    @BeforeEach
    void setUp() {
        owner = memberRepository.saveAndFlush(newMember("owner@test.com", "owner"));
        invitee = memberRepository.saveAndFlush(newMember("invitee@test.com", "invitee"));
        group = groupRepository.saveAndFlush(Group.builder().name("그룹").createdBy(owner).build());
        groupMemberRepository.saveAndFlush(GroupMember.builder()
                .group(group).member(owner).role(GroupRole.OWNER).status(GroupMemberStatus.ACTIVE).build());
        ownerToken = tokenFor(owner);
        inviteeToken = tokenFor(invitee);
    }

    @Test
    @DisplayName("초대 발송 — ACTIVE 멤버가 보내면 201, PENDING 상태")
    void invite_byActiveMember_returns201() throws Exception {
        mockMvc.perform(post("/groups/" + group.getId() + "/invitations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateInvitationRequestDto(invitee.getId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.groupId").value(group.getId()));
    }

    @Test
    @DisplayName("초대 발송 — 그룹 멤버가 아니면 403")
    void invite_byNonMember_returns403() throws Exception {
        mockMvc.perform(post("/groups/" + group.getId() + "/invitations")
                        .header("Authorization", "Bearer " + inviteeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateInvitationRequestDto(owner.getId()))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("초대 발송 — 이미 PENDING 초대가 있으면 409")
    void invite_alreadyPending_returns409() throws Exception {
        sendInvitation();

        mockMvc.perform(post("/groups/" + group.getId() + "/invitations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateInvitationRequestDto(invitee.getId()))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("내게 온 초대 목록 — PENDING 초대가 보인다")
    void listMyInvitations_showsPendingInvitation() throws Exception {
        sendInvitation();

        mockMvc.perform(get("/invitations/mine").header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].groupId").value(group.getId()))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    @DisplayName("초대 수락 — 200, 멤버로 가입되고 MEMBER_JOINED 이벤트가 seq 1로 남는다")
    void accept_joinsGroupAndRecordsEvent() throws Exception {
        GroupInvitation invitation = sendInvitation();

        mockMvc.perform(post("/invitations/" + invitation.getId() + "/accept")
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isOk());

        GroupMember membership = groupMemberRepository.findByGroupIdAndMemberId(group.getId(), invitee.getId())
                .orElseThrow();
        assertThat(membership.getStatus()).isEqualTo(GroupMemberStatus.ACTIVE);
        assertThat(membership.getRole()).isEqualTo(GroupRole.MEMBER);

        var events = groupEventRepository.findAllByGroupIdAndSeqGreaterThanOrderBySeqAsc(group.getId(), 0L);
        assertThat(events).hasSize(1);
        GroupEvent event = events.get(0);
        assertThat(event.getSeq()).isEqualTo(1L);
        assertThat(event.getEventType()).isEqualTo("MEMBER_JOINED");
        assertThat(event.getSender()).isNull();
        assertThat(event.getPayload()).contains(String.valueOf(invitee.getId()));
    }

    @Test
    @DisplayName("초대 수락 — 초대받은 사람이 아니면 403")
    void accept_wrongInvitee_returns403() throws Exception {
        GroupInvitation invitation = sendInvitation();

        mockMvc.perform(post("/invitations/" + invitation.getId() + "/accept")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("초대 거절 — 200, 상태만 DECLINED로 바뀌고 그룹에 가입되지 않는다")
    void decline_marksDeclinedWithoutJoining() throws Exception {
        GroupInvitation invitation = sendInvitation();

        mockMvc.perform(post("/invitations/" + invitation.getId() + "/decline")
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isOk());

        assertThat(groupInvitationRepository.findById(invitation.getId()).orElseThrow().getStatus())
                .isEqualTo(InvitationStatus.DECLINED);
        assertThat(groupMemberRepository.findByGroupIdAndMemberId(group.getId(), invitee.getId())).isEmpty();
    }

    private GroupInvitation sendInvitation() {
        return groupInvitationRepository.saveAndFlush(
                GroupInvitation.builder().group(group).inviter(owner).invitee(invitee).build());
    }

    private Member newMember(String email, String username) {
        return Member.builder().email(email).username(username)
                .password(passwordEncoder.encode("password123")).role(UserRole.USER).build();
    }

    private String tokenFor(Member member) {
        CustomUserInfoDto info = CustomUserInfoDto.builder().email(member.getEmail()).role(member.getRole()).build();
        return jwtUtil.createAccessToken(info);
    }
}
