package com.shadowfit.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.login.LoginRequestDto;
import com.shadowfit.dto.login.ReissueRequestDto;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.RefreshToken;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.member.RefreshTokenRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 토큰 재발급·회전·재사용 탐지 (이슈 #135 · #136, decisions/token-lifecycle.md).
 *
 * <p>세 갈래를 전부 고정한다 — <b>정상 회전</b> / <b>재시도 유예</b> / <b>폐기 구본 거절</b>.
 * 가운데가 빠지면 «네트워크가 끊기면 로그아웃되는» 앱이 되고, 마지막이 빠지면 회전을 넣은
 * 의미가 없다. 둘 다 조용히 깨지는 종류라 테스트가 아니면 드러나지 않는다.
 *
 * <p>⚠️ 유예 만료 케이스는 <b>여기서 검증하지 않는다.</b> 10초를 실제로 기다리는 테스트는
 * 스위트를 느리게 만들고, 시계를 주입 가능하게 바꾸는 것은 이 변경의 범위 밖이다. 대신
 * <b>두 세대 전</b> 토큰으로 «유예에 걸리지 않는 구본» 을 만들어 거절 경로를 태운다 —
 * 유예 조건이 {@code ver == current - 1} 이라 두 세대 전은 시간과 무관하게 탈락한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("토큰 재발급 — 회전·유예·재사용 탐지")
class TokenReissueIntegrationTest {

    private static final String EMAIL = "reissue@test.com";
    private static final String PASSWORD = "password123";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MemberRepository memberRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private com.shadowfit.global.security.jwt.RefreshTokenHasher refreshTokenHasher;

    private Member member;

    @BeforeEach
    void setUp() {
        member = memberRepository.saveAndFlush(Member.builder()
                .email(EMAIL).username("reissueuser")
                .password(passwordEncoder.encode(PASSWORD))
                .role(UserRole.USER).build());
    }

    private String login() throws Exception {
        String body = mockMvc.perform(post("/member/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto(EMAIL, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("refreshToken").asText();
    }

    private String reissueOk(String refreshToken) throws Exception {
        String body = mockMvc.perform(post("/member/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReissueRequestDto(refreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("refreshToken").asText();
    }

    @Test
    @DisplayName("인증 없이 호출된다 — access 가 만료된 뒤 부르는 API 라 permitAll 이 전제다")
    void reissue_requiresNoAuthentication() throws Exception {
        String refresh = login();

        // Authorization 헤더를 아예 안 붙인다. 401 이 나면 이 엔드포인트는 존재 이유를 잃는다.
        reissueOk(refresh);
    }

    @Test
    @DisplayName("정상 회전 — refresh 도 새 것으로 바뀌고 세대가 오른다")
    void reissue_rotatesRefreshToken() throws Exception {
        String first = login();
        long versionAfterLogin = refreshTokenRepository.findById(member.getId()).orElseThrow().getTokenVersion();

        String second = reissueOk(first);

        assertThat(second)
                .as("회전인데 같은 토큰이 돌아오면 refresh 탈취 창이 안 줄어든다")
                .isNotEqualTo(first);

        RefreshToken row = refreshTokenRepository.findById(member.getId()).orElseThrow();
        assertThat(row.getToken())
                .as("DB 에는 원문이 아니라 해시가 있어야 한다 — 덤프가 유출돼도 자격증명이 아니다 (#185)")
                .isNotEqualTo(second)
                .isEqualTo(refreshTokenHasher.hash(second));
        assertThat(row.getTokenVersion()).isEqualTo(versionAfterLogin + 1);
        assertThat(row.getRotatedAt())
                .as("유예 판정의 기준 시각이라 회전 때 반드시 채워져야 한다")
                .isNotNull();
    }

    @Test
    @DisplayName("직전 토큰 재시도 — 유예 안이면 새 토큰을 회전 발급한다 (#185 ㄱ)")
    void reissue_withImmediatelyPreviousToken_reissuesInGrace() throws Exception {
        String first = login();
        String second = reissueOk(first);
        long versionBefore = refreshTokenRepository.findById(member.getId()).orElseThrow().getTokenVersion();

        // 클라가 second 를 못 받았다고 가정하고 first(직전 세대)로 다시 온다.
        String retried = reissueOk(first);

        // 🔴 ㄱ: 해시 저장이라 «저장된 토큰» 을 되돌려줄 수 없다. 대신 새 토큰을 회전 발급한다.
        // 그래서 예전 계약(«second 를 그대로 준다»)과 달리 retried 는 second 도 first 도 아니다.
        assertThat(retried).isNotEqualTo(second).isNotEqualTo(first);

        RefreshToken row = refreshTokenRepository.findById(member.getId()).orElseThrow();
        assertThat(row.getToken())
                .as("유예 재발급도 해시로 저장한다")
                .isEqualTo(refreshTokenHasher.hash(retried));
        assertThat(row.getTokenVersion())
                .as("ㄱ 은 유예에서도 회전하므로 세대가 오른다 (예전엔 그대로였다)")
                .isEqualTo(versionBefore + 1);
    }

    @Test
    @DisplayName("재시도 응답이 또 유실되면 — 같은 직전 토큰의 두 번째 재시도는 유예 밖이라 끊긴다 (#185 ㄱ의 대가)")
    void reissue_repeatedLossFallsOutOfGrace() throws Exception {
        String first = login();
        reissueOk(first);          // second 발급 (유실 가정)
        reissueOk(first);          // 유예 재발급 → 세대 +1 (이 응답도 유실 가정)

        // first 는 이제 두 세대 전이다. ㄱ 은 반복 유실을 못 견딘다 — revoke.
        mockMvc.perform(post("/member/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReissueRequestDto(first))))
                .andExpect(status().isUnauthorized());

        assertThat(refreshTokenRepository.findById(member.getId()))
                .as("반복 유실은 유예 밖이라 세션이 끊긴다 — ㄱ 이 감수한 꼬리다")
                .isEmpty();
    }

    @Test
    @DisplayName("두 세대 전 토큰 — 유예 밖이라 세션을 끊는다 (A006)")
    void reissue_withStaleToken_revokesSession() throws Exception {
        String first = login();
        String second = reissueOk(first);
        reissueOk(second);   // first 는 이제 두 세대 전이다

        mockMvc.perform(post("/member/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReissueRequestDto(first))))
                .andExpect(status().isUnauthorized())
                // ⚠️ 코드("A006")로 단언하지 못한다 — ErrorResponseDto 에 code 필드가 없어
                //    응답에는 status·message·timestamp 만 나간다. 즉 A004(단순 무효)와 A006 의
                //    구분은 **지금 서버 안에만 있고 클라는 못 본다.** message 로 대신 고정한다.
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("다시 로그인")));

        assertThat(refreshTokenRepository.findById(member.getId()))
                .as("탈취로 판정했으면 세션이 남아 있으면 안 된다 — 남으면 경고만 하고 통과시킨 셈이다")
                .isEmpty();
    }

    @Test
    @DisplayName("1인 1세션 — 두 번째 로그인이 첫 세션을 무효화한다 (#136)")
    void secondLogin_invalidatesFirstSession() throws Exception {
        String firstDevice = login();
        String secondDevice = login();

        assertThat(secondDevice).isNotEqualTo(firstDevice);
        assertThat(refreshTokenRepository.findById(member.getId()).orElseThrow().getToken())
                .as("행이 하나뿐이므로 나중 로그인이 앞 기기 토큰을 대체한다 — 저장값은 해시다 (#185)")
                .isEqualTo(refreshTokenHasher.hash(secondDevice));

        assertThat(refreshTokenRepository.findById(member.getId()).orElseThrow().getRotatedAt())
                .as("로그인은 유예를 열면 안 된다 — 열면 앞 기기가 재발급으로 새 세션 토큰을 받아간다")
                .isNull();

        // 앞 기기가 재발급을 시도하면 거절된다.
        mockMvc.perform(post("/member/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReissueRequestDto(firstDevice))))
                .andExpect(status().isUnauthorized())
                // ⚠️ 코드("A006")로 단언하지 못한다 — ErrorResponseDto 에 code 필드가 없어
                //    응답에는 status·message·timestamp 만 나간다. 즉 A004(단순 무효)와 A006 의
                //    구분은 **지금 서버 안에만 있고 클라는 못 본다.** message 로 대신 고정한다.
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("다시 로그인")));

        // ⚠️ **여기서 두 번째 기기의 세션까지 끊긴다.** 서버는 «탈취» 와 «낡은 기기» 를 구분하지
        // 못하고(행이 하나뿐이라 정보가 없다) 보수적인 쪽을 택했기 때문이다
        // (decisions/token-lifecycle.md §4-3). 감수한 대가를 테스트에 드러내 둔다 — 뒤집으려면
        // MemberService.reissue 의 (3) 분기 하나만 바꾸면 되고, 이 단언이 그때 같이 바뀐다.
        assertThat(refreshTokenRepository.findById(member.getId()))
                .as("보수적 선택의 실제 결과 — 낡은 기기의 시도가 현재 세션까지 끊는다")
                .isEmpty();
    }
}
