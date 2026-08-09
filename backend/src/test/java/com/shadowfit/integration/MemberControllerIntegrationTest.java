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
        MemberRequestDto dto = new MemberRequestDto("newuser", "new@test.com", "pw1234", Sex.MALE);

        mockMvc.perform(post("/member/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(content().string("newuser"));
    }

    @Test
    @DisplayName("회원가입 — sex 가 실제로 저장된다 (받기만 하고 버리던 것 수정)")
    void signup_persistsSex() throws Exception {
        // FEMALE 로 검증한다 — 이 상수가 스키마 ENUM 과 어긋나 있던(FEAMALE) 값이라
        // 철자 정정이 되돌려지면 여기가 같이 깨진다. 스키마 대조는 SchemaEnumConsistencyTest.
        MemberRequestDto dto = new MemberRequestDto("sexuser", "sex@test.com", "pw1234", Sex.FEMALE);

        mockMvc.perform(post("/member/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk());

        Member saved = memberRepository.findByEmail("sex@test.com").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(saved.getSex()).isEqualTo(Sex.FEMALE);
    }

    /**
     * 이슈 #138 — 공개 회원가입으로 관리자가 될 수 없다.
     *
     * <p><b>DTO 가 아니라 raw JSON 을 보내는 이유.</b> {@code MemberRequestDto} 에는 이제 role 필드가
     * 없어서, DTO 를 직렬화하면 «공격자가 보내는 요청» 을 재현할 수 없다. 공격은 HTTP 본문에 서버가
     * 모르는 필드를 끼워 넣는 것이므로, 그 형태 그대로 보내야 검증이 된다.
     *
     * <p>동시에 이것이 <b>구버전 앱 호환</b>도 확인한다 — 프론트가 아직 {@code role:"USER"} 를 보내고
     * 있는데(login.tsx), 서버가 모르는 필드로 400 을 내면 기존 앱의 가입이 통째로 깨진다.
     * 200 을 기대하는 것은 «무시한다»가 의도된 동작이기 때문이다.
     */
    @Test
    @DisplayName("#138 role:ADMIN 을 보내도 무시되고 USER 로 저장된다 (구버전 앱 호환 위해 400 이 아니다)")
    void signup_withAdminRole_isIgnored() throws Exception {
        String rawJsonWithRole = """
                {"username":"attacker","email":"attacker@test.com","password":"pw1234",
                 "sex":"MALE","role":"ADMIN"}
                """;

        mockMvc.perform(post("/member/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawJsonWithRole))
                .andExpect(status().isOk());

        Member saved = memberRepository.findByEmail("attacker@test.com").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(saved.getRole()).isEqualTo(UserRole.USER);
    }

    /**
     * 이슈 #138 의 「미검증」을 닫는 테스트 — 정적 판독이 아니라 실제로 관리자 API 를 쳐서 확인한다.
     *
     * <p>이슈는 세 조각(가입이 role 을 받는다 · CustomUserDetails 가 "ROLE_"+role 로 권한을 만든다 ·
     * @PreAuthorize 가 그것을 믿는다)을 이어 읽어 «뚫린다»고 판정했지만 재현은 하지 않았다.
     * 이 테스트는 그 사슬 전체를 통과시켜, <b>수정 후에 실제로 막히는지</b>를 HTTP 로 확인한다.
     */
    @Test
    @DisplayName("#138 가입으로 만든 계정은 관리자 API 에 접근할 수 없다 (403)")
    void signup_withAdminRole_cannotAccessAdminApi() throws Exception {
        String rawJsonWithRole = """
                {"username":"attacker2","email":"attacker2@test.com","password":"pw1234",
                 "sex":"MALE","role":"ADMIN"}
                """;
        mockMvc.perform(post("/member/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawJsonWithRole))
                .andExpect(status().isOk());

        // 가입한 계정으로 직접 토큰을 만든다 — 로그인 경로를 타든 여기서 만들든 토큰에 담기는
        // 권한은 DB 의 role 에서 온다(MemberService.login:66). 저장된 값이 무엇인지가 관건이다.
        Member attacker = memberRepository.findByEmail("attacker2@test.com").orElseThrow();
        CustomUserInfoDto info = CustomUserInfoDto.builder()
                .email(attacker.getEmail()).role(attacker.getRole()).build();
        String attackerToken = jwtUtil.createAccessToken(info);

        mockMvc.perform(get("/admin/members").header("Authorization", "Bearer " + attackerToken))
                .andExpect(status().isForbidden());
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
