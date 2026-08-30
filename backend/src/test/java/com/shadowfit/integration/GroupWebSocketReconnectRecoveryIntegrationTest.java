package com.shadowfit.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.group.CreateGroupRequestDto;
import com.shadowfit.dto.group.CreateInvitationRequestDto;
import com.shadowfit.dto.group.GroupEventResponseDto;
import com.shadowfit.dto.group.GroupResponseDto;
import com.shadowfit.dto.group.InvitationResponseDto;
import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupEvent;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.group.GroupEventRepository;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.group.GroupRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/decisions/group-websocket-capacity-deep-dive.md §1-4 — "설계한 대로 동작하나"를
 * 실제 임베디드 서버(RANDOM_PORT)에 진짜 WebSocket으로 붙어 확인한다. {@code JwtHandshakeInterceptorTest}
 * 등 기존 유닛테스트는 개별 컴포넌트를 목으로 검증했을 뿐, "실제로 연결이 끊긴 채 이벤트가
 * 쌓이고, 재연결 후 백필로 전부 회수되는가"를 엔드투엔드로 확인한 적은 없었다.
 *
 * <p><b>{@code @Transactional}을 안 쓰는 이유</b> — WebSocket은 임베디드 서버의 실제
 * 네트워크 스레드에서 처리되므로, 테스트 스레드에 바인딩된 트랜잭션 롤백은 그 스레드에서
 * 보이지 않는다({@code SignupUsernameRaceTest}가 같은 이유로 수동 정리를 쓰는 것과 같다).
 * 그래서 여기도 매 테스트 후 직접 지운다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@DisplayName("그룹 WebSocket 재연결·복구 통합테스트 (§1-4)")
class GroupWebSocketReconnectRecoveryIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private GroupEventRepository groupEventRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final List<Long> createdGroupIds = new CopyOnWriteArrayList<>();
    private final List<Long> createdMemberIds = new CopyOnWriteArrayList<>();

    @AfterEach
    void cleanUp() {
        // group_members·group_invitations·group_events는 workout_groups에 ON DELETE
        // CASCADE로 걸려 있다(V12 마이그레이션) — 그룹만 지우면 DB가 나머지를 정리한다.
        createdGroupIds.forEach(id -> groupRepository.findById(id).ifPresent(groupRepository::delete));
        createdMemberIds.forEach(id -> memberRepository.findById(id).ifPresent(memberRepository::delete));
    }

    @Test
    @DisplayName("연결이 끊긴 동안 쌓인 이벤트를 재연결 후 백필로 순서대로·누락 없이 회수한다")
    void backfill_recoversEventsMissedWhileDisconnected() throws Exception {
        Member owner = signup("recon-owner");
        Member member = signup("recon-member");
        String ownerToken = tokenFor(owner);
        String memberToken = tokenFor(member);

        Long groupId = createGroup(ownerToken, "재연결 테스트 그룹");
        Long invitationId = invite(ownerToken, groupId, member.getId());
        accept(memberToken, invitationId); // seq=1 (MEMBER_JOINED) — member는 아직 연결 전이라 못 받는다

        // member가 "오프라인"이었다는 것을 재현 — 연결한 적 없이 afterSeq=0부터 시작한다.
        // owner는 온라인으로 두고 REP_COMPLETED 3건을 실제 WS 경로로 발행한다(seq=2,3,4).
        try (TestWsClient ownerWs = TestWsClient.connect(port, groupId, ownerToken)) {
            for (int i = 0; i < 3; i++) {
                ownerWs.sendJson("REP_COMPLETED", "{\"rep\":" + i + "}");
            }
            awaitEventCount(groupId, 4);
        }

        var mvcResult = mockMvc.perform(get("/groups/" + groupId + "/events")
                        .param("afterSeq", "0")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andReturn();
        List<GroupEventResponseDto> backfilled = objectMapper.readValue(
                mvcResult.getResponse().getContentAsString(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, GroupEventResponseDto.class));

        assertThat(backfilled).extracting(GroupEventResponseDto::getSeq).containsExactly(1L, 2L, 3L, 4L);
        assertThat(backfilled).extracting(GroupEventResponseDto::getType)
                .containsExactly("MEMBER_JOINED", "REP_COMPLETED", "REP_COMPLETED", "REP_COMPLETED");
    }

    @Test
    @DisplayName("탈퇴한 멤버가 같은 토큰으로 재연결을 시도하면 핸드셰이크가 403으로 거부된다")
    void handshake_rejectsReconnectAfterLeave() throws Exception {
        Member owner = signup("leave-owner");
        String ownerToken = tokenFor(owner);
        Long groupId = createGroup(ownerToken, "탈퇴 재연결 테스트 그룹");

        // 탈퇴 전에는 정상적으로 붙는다는 것부터 확인 — 이후 403이 "애초에 안 되던 것"이 아님을 보장.
        try (TestWsClient before = TestWsClient.connect(port, groupId, ownerToken)) {
            assertThat(before.isOpen()).isTrue();
        }

        mockMvc.perform(delete("/groups/" + groupId + "/members/me")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        assertThatThrownBy(() -> TestWsClient.connect(port, groupId, ownerToken))
                .isInstanceOfSatisfying(ExecutionException.class, e ->
                        assertThat(e.getCause()).isInstanceOfSatisfying(WebSocketHandshakeException.class,
                                wshe -> assertThat(wshe.getResponse().statusCode()).isEqualTo(403)));
    }

    private void awaitEventCount(Long groupId, int expected) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (groupEventRepository.findAllByGroupIdAndSeqGreaterThanOrderBySeqAsc(groupId, 0L).size() >= expected) {
                return;
            }
            Thread.sleep(100);
        }
    }

    private Member signup(String username) throws Exception {
        String email = username + "-" + System.nanoTime() + "@test.local";
        Member member = memberRepository.saveAndFlush(Member.builder()
                .email(email).username(username + System.nanoTime())
                .password(passwordEncoder.encode("password123")).role(UserRole.USER).build());
        createdMemberIds.add(member.getId());
        return member;
    }

    private String tokenFor(Member member) {
        CustomUserInfoDto info = CustomUserInfoDto.builder().email(member.getEmail()).role(member.getRole()).build();
        return jwtUtil.createAccessToken(info);
    }

    private Long createGroup(String token, String name) throws Exception {
        var result = mockMvc.perform(post("/groups")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateGroupRequestDto(name))))
                .andExpect(status().isCreated())
                .andReturn();
        Long groupId = objectMapper.readValue(result.getResponse().getContentAsString(), GroupResponseDto.class).getId();
        createdGroupIds.add(groupId);
        return groupId;
    }

    private Long invite(String token, Long groupId, Long inviteeId) throws Exception {
        var result = mockMvc.perform(post("/groups/" + groupId + "/invitations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateInvitationRequestDto(inviteeId))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), InvitationResponseDto.class).getId();
    }

    private void accept(String token, Long invitationId) throws Exception {
        mockMvc.perform(post("/invitations/" + invitationId + "/accept")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    /** JDK 내장 {@link WebSocket}으로 최소한만 감싼 테스트 전용 클라이언트. */
    private static final class TestWsClient implements AutoCloseable {
        private final WebSocket socket;

        private TestWsClient(WebSocket socket) {
            this.socket = socket;
        }

        static TestWsClient connect(int port, Long groupId, String token) throws ExecutionException, InterruptedException, TimeoutException {
            HttpClient client = HttpClient.newHttpClient();
            URI uri = URI.create("ws://localhost:" + port + "/ws/groups/" + groupId + "?token=" + token);
            CompletableFuture<WebSocket> future = client.newWebSocketBuilder()
                    .buildAsync(uri, new WebSocket.Listener() {
                    });
            WebSocket ws = future.get(5, TimeUnit.SECONDS);
            return new TestWsClient(ws);
        }

        boolean isOpen() {
            return !socket.isOutputClosed() && !socket.isInputClosed();
        }

        void sendJson(String type, String payloadJson) {
            String frame = "{\"type\":\"" + type + "\",\"payload\":" + payloadJson + "}";
            socket.sendText(frame, true).join();
        }

        @Override
        public void close() {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "test done").join();
        }
    }
}
