package com.shadowfit.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.dto.login.LoginRequestDto;
import com.shadowfit.dto.login.MemberRequestDto;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.Sex;
import com.shadowfit.model.member.UserRole;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MemberController 통합테스트 — 실제 보안 필터체인(JwtAuthFilter)까지 태워서 HTTP 레벨로 검증.
 * 지금까지 컨트롤러 계층이 전부 무테스트였고, 그중에서도 인증 게이트웨이라 가장 중요.
 * requireSelf(IDOR 방지) 는 지금까지 어떤 테스트도 실제 HTTP 요청으로 확인한 적 없음.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("MemberController 통합테스트")
class MemberControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Member member;
    private String accessToken;

    @BeforeEach
    void setUp() {
        member = memberRepository.saveAndFlush(Member.builder()
                .email("http@test.com").username("httpuser")
                .password(passwordEncoder.encode("password123"))
                .role(UserRole.USER).build());

        CustomUserInfoDto info = CustomUserInfoDto.builder().email(member.getEmail()).role(member.getRole()).build();
        accessToken = jwtUtil.createAccessToken(info);
    }

    @Test
    @DisplayName("회원가입 — 인증 없이 호출 가능, 200 + username 반환")
    void signup_noAuthRequired_returns200() throws Exception {
        MemberRequestDto dto = new MemberRequestDto("newuser", "new@test.com", "pw1234", Sex.MALE, UserRole.USER);

        mockMvc.perform(post("/member/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(content().string("newuser"));
    }

    @Test
    @DisplayName("로그인 성공 — 토큰 반환")
    void login_success_returnsTokens() throws Exception {
        LoginRequestDto dto = new LoginRequestDto(member.getEmail(), "password123");

        mockMvc.perform(post("/member/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists());
    }

    @Test
    @DisplayName("로그인 실패 — 비밀번호 틀리면 401")
    void login_wrongPassword_returns401() throws Exception {
        LoginRequestDto dto = new LoginRequestDto(member.getEmail(), "wrong-password");

        mockMvc.perform(post("/member/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("보호된 엔드포인트를 토큰 없이 호출하면 401")
    void protectedEndpoint_noToken_returns401() throws Exception {
        mockMvc.perform(get("/member/onboarding/" + member.getEmail()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("본인 온보딩 조회 — 200")
    void getOnboarding_self_returns200() throws Exception {
        mockMvc.perform(get("/member/onboarding/" + member.getEmail())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("남의 온보딩 조회 시도 — 403 (requireSelf IDOR 방지)")
    void getOnboarding_otherEmail_returns403() throws Exception {
        mockMvc.perform(get("/member/onboarding/someone-else@test.com")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("본인 탈퇴 — 204")
    void deleteMember_self_returns204() throws Exception {
        mockMvc.perform(delete("/member/" + member.getEmail())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("남의 계정 탈퇴 시도 — 403 (requireSelf IDOR 방지)")
    void deleteMember_otherEmail_returns403() throws Exception {
        mockMvc.perform(delete("/member/someone-else@test.com")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden());
    }
}
