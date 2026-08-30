package com.shadowfit.integration;

import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.coaching.TrainerAssignment;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.coaching.TrainerAssignmentRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 세션2에서 유닛 레벨로만 검증했던 "미배정 트레이너는 403"을 컨트롤러가 생긴 지금
 * 실제 HTTP 경계에서 확인한다 ({@code trainer-live-monitoring.md} §8 세션3).
 *
 * <p>role 게이트(USER→403)와 소유권 게이트(TRAINER인데 미배정→403)는 둘 다 {@link
 * com.shadowfit.controller.CoachingStreamController#stream}이 {@code SseEmitter}를 반환하기
 * 전에 동기적으로 막히므로 일반 동기 요청처럼 검증한다. 배정된 트레이너의 성공 경로만 실제로
 * 비동기 디스패치가 시작된다({@code request().asyncStarted()}) — 이 코드베이스에 SSE 비동기
 * 컨트롤러 테스트가 처음이라 패턴을 여기 세운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("트레이너 SSE 스트림 권한 통합테스트")
class TrainerStreamAuthorizationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;
    @Autowired private TrainerAssignmentRepository trainerAssignmentRepository;

    private Member user;
    private String userToken;
    private String assignedTrainerToken;
    private String unassignedTrainerToken;

    @BeforeEach
    void setUp() {
        user = memberRepository.saveAndFlush(Member.builder()
                .email("user@test.com").username("u1").password("dummy").role(UserRole.USER).build());
        Member assignedTrainer = memberRepository.saveAndFlush(Member.builder()
                .email("assigned-trainer@test.com").username("t1").password("dummy").role(UserRole.TRAINER).build());
        Member unassignedTrainer = memberRepository.saveAndFlush(Member.builder()
                .email("unassigned-trainer@test.com").username("t2").password("dummy").role(UserRole.TRAINER).build());

        trainerAssignmentRepository.saveAndFlush(
                TrainerAssignment.builder().trainer(assignedTrainer).user(user).build());

        userToken = jwtUtil.createAccessToken(
                CustomUserInfoDto.builder().email(user.getEmail()).role(user.getRole()).build());
        assignedTrainerToken = jwtUtil.createAccessToken(
                CustomUserInfoDto.builder().email(assignedTrainer.getEmail()).role(assignedTrainer.getRole()).build());
        unassignedTrainerToken = jwtUtil.createAccessToken(
                CustomUserInfoDto.builder().email(unassignedTrainer.getEmail()).role(unassignedTrainer.getRole()).build());
    }

    @Test
    @DisplayName("USER 역할이면 403 (role 게이트)")
    void stream_userRole_returns403() throws Exception {
        mockMvc.perform(get("/coaching/trainer/" + user.getId() + "/stream")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TRAINER 역할이지만 미배정이면 403 (소유권 게이트)")
    void stream_unassignedTrainer_returns403() throws Exception {
        mockMvc.perform(get("/coaching/trainer/" + user.getId() + "/stream")
                        .header("Authorization", "Bearer " + unassignedTrainerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("배정된 TRAINER면 연결이 열리고 connected 이벤트를 받는다")
    void stream_assignedTrainer_opensConnection() throws Exception {
        // SseEmitter는 의도적으로 완결되지 않는 연결이라(§8 세션3) asyncDispatch로 "결과"를
        // 기다리면 안 된다 — emitter.send()가 컨트롤러 메서드 안에서 이미 동기적으로 응답
        // 스트림에 써놓은 내용을, 아직 열려 있는 상태 그대로 검사한다.
        MvcResult result = mockMvc.perform(get("/coaching/trainer/" + user.getId() + "/stream")
                        .header("Authorization", "Bearer " + assignedTrainerToken))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("connected");
    }
}
