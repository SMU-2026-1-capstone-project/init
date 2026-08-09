package com.shadowfit.integration;

import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 읽을 수 없는 요청 본문이 400 으로 나가는지 검증한다 (이슈 #180).
 *
 * <p>배경 — {@code GlobalExceptionHandler} 에 {@code HttpMessageNotReadableException} 핸들러가
 * 없어서 {@code @ExceptionHandler(Exception.class)} 로 떨어졌고, <b>본문을 받는 모든 엔드포인트</b>가
 * 깨진 JSON 에 <b>500</b> 을 냈다. {@code AdminQueryParamBindingErrorTest} 가 고정한
 * 파라미터 바인딩 실패(400→500)와 <b>같은 형태의 네 번째 사례</b>다.
 *
 * <p><b>{@code /member/login} 을 주 대상으로 삼은 이유</b>: whitelist 라 <b>인증 없이</b> 닿는다.
 * 즉 이 결함은 인증을 요구하지 않는 자리에서 재현됐고, 그래서 외부에서 ERROR 로그를 확정적으로
 * 만들 수 있었다. 인증 뒤 엔드포인트도 같이 고정해 두 경로가 같이 안 깨지게 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("읽을 수 없는 요청 본문은 400")
class MalformedRequestBodyTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;

    private String accessToken;

    @BeforeEach
    void setUp() {
        Member member = memberRepository.saveAndFlush(Member.builder()
                .email("malformed-body@test.com").username("u").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        accessToken = jwtUtil.createAccessToken(CustomUserInfoDto.builder()
                .email(member.getEmail()).role(member.getRole()).build());
    }

    @Test
    @DisplayName("인증 불필요 엔드포인트 — 본문이 아예 없으면 400")
    void loginWithoutBody() throws Exception {
        mockMvc.perform(post("/member/login").contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("인증 불필요 엔드포인트 — JSON 문법이 깨졌으면 400")
    void loginWithBrokenJson() throws Exception {
        mockMvc.perform(post("/member/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json"))
                .andExpect(status().isBadRequest());
    }

    /**
     * 역직렬화 단계에서 걸린다 — {@code @Valid} 가 도는 {@code MethodArgumentNotValidException}
     * 경로와 다르므로 별도 케이스로 둔다. 값이 객체라 String 필드로 못 읽는다.
     */
    @Test
    @DisplayName("인증 불필요 엔드포인트 — 필드 타입이 맞지 않으면 400")
    void loginWithTypeMismatch() throws Exception {
        mockMvc.perform(post("/member/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":123,\"password\":{}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("인증 필요 엔드포인트 — 본문이 없으면 400")
    void authenticatedEndpointWithoutBody() throws Exception {
        mockMvc.perform(post("/exercises/sessions")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest());
    }

    /**
     * 🔴 파서 메시지가 응답으로 새지 않는지 — 핸들러가 로그에만 남기고 응답은 고정 문구를 쓴다는
     * 계약을 고정한다. Jackson 의 오류 메시지에는 클래스명·필드 경로가 섞일 수 있다.
     */
    @Test
    @DisplayName("응답 본문에 파서 내부 정보가 실리지 않는다")
    void doesNotLeakParserDetails() throws Exception {
        String body = mockMvc.perform(post("/member/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("올바르지 않은 입력값입니다."))
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("Exception")
                .doesNotContain("com.shadowfit")
                .doesNotContain("com.fasterxml");
    }
}