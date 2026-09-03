package com.shadowfit.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.group.CreateGroupRequestDto;
import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.security.jwt.JwtUtil;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GroupController 통합테스트 — 실제 보안 필터체인까지 태워서 HTTP 레벨로 검증한다
 * ({@code MemberControllerIntegrationTest}와 같은 방식). WebSocket 핸드셰이크 자체는
 * {@code JwtHandshakeInterceptorTest}에서 별도로 다루고, 여기서는 REST 쪽(그룹 CRUD·
 * 재연결 백필)만 다룬다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("GroupController 통합테스트")
class GroupControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Member owner;
    private Member outsider;
    private String ownerToken;
    private String outsiderToken;

    @BeforeEach
    void setUp() {
        owner = memberRepository.saveAndFlush(newMember("owner@test.com", "owner"));
        outsider = memberRepository.saveAndFlush(newMember("outsider@test.com", "outsider"));
        ownerToken = tokenFor(owner);
        outsiderToken = tokenFor(outsider);
    }

    @Test
    @DisplayName("그룹 생성 — 201, 생성자가 OWNER로 가입된다")
    void createGroup_returns201AndJoinsCreatorAsOwner() throws Exception {
        mockMvc.perform(post("/groups")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateGroupRequestDto("헬스 메이트"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("헬스 메이트"))
                .andExpect(jsonPath("$.createdById").value(owner.getId()));

        // 응답만이 아니라 실제로 ACTIVE·OWNER 멤버십이 생겼는지 DB로 확인한다.
        Group saved = groupRepository.findAll().stream()
                .filter(g -> g.getName().equals("헬스 메이트")).findFirst().orElseThrow();
        GroupMember membership = groupMemberRepository.findByGroupIdAndMemberId(saved.getId(), owner.getId())
                .orElseThrow();
        org.assertj.core.api.Assertions.assertThat(membership.getRole()).isEqualTo(GroupRole.OWNER);
        org.assertj.core.api.Assertions.assertThat(membership.getStatus()).isEqualTo(GroupMemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("그룹 생성 — 인증 없이 호출하면 401")
    void createGroup_noAuth_returns401() throws Exception {
        mockMvc.perform(post("/groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateGroupRequestDto("헬스 메이트"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("그룹 생성 — 이름이 비어있으면 400")
    void createGroup_blankName_returns400() throws Exception {
        mockMvc.perform(post("/groups")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateGroupRequestDto(""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("내 그룹 목록 — ACTIVE 멤버인 그룹만 반환한다")
    void listMyGroups_returnsOnlyActiveMemberships() throws Exception {
        Group group = createGroupWithOwner();

        mockMvc.perform(get("/groups/mine").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(group.getId()));

        mockMvc.perform(get("/groups/mine").header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("그룹 상세 조회 — 멤버는 200, 멤버 아니면 403")
    void getGroupDetail_memberVsNonMember() throws Exception {
        Group group = createGroupWithOwner();

        mockMvc.perform(get("/groups/" + group.getId()).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].memberId").value(owner.getId()));

        mockMvc.perform(get("/groups/" + group.getId()).header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("그룹 상세 조회 — 존재하지 않는 그룹이면 404")
    void getGroupDetail_unknownGroup_returns404() throws Exception {
        mockMvc.perform(get("/groups/999999").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("그룹 탈퇴 — 200, 이후 상세 조회는 403")
    void leaveGroup_thenDetailForbidden() throws Exception {
        Group group = createGroupWithOwner();

        mockMvc.perform(delete("/groups/" + group.getId() + "/members/me")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/groups/" + group.getId()).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("재연결 백필 — afterSeq 이후 이벤트가 없으면 빈 배열, 멤버 아니면 403")
    void getEventsAfter_emptyWhenNoNewEvents_forbiddenForNonMember() throws Exception {
        Group group = createGroupWithOwner();

        mockMvc.perform(get("/groups/" + group.getId() + "/events")
                        .param("afterSeq", "0")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(get("/groups/" + group.getId() + "/events")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());
    }

    private Group createGroupWithOwner() {
        Group group = groupRepository.saveAndFlush(Group.builder().name("그룹").createdBy(owner).build());
        groupMemberRepository.saveAndFlush(GroupMember.builder()
                .group(group).member(owner).role(GroupRole.OWNER).status(GroupMemberStatus.ACTIVE).build());
        return group;
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
