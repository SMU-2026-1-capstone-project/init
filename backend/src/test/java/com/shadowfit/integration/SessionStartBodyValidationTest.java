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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 세션 시작 API 의 요청 본문 검증이 400 으로 나가는지 고정한다 (이슈 #178).
 *
 * <p>배경 — {@code POST /exercises/sessions} 는 {@code @RequestBody} 12곳 중 <b>유일하게</b>
 * {@code @Valid} 가 없었고, {@code VideoRequestDto.exerciseId} 에도 제약이 없었다.
 * {@code @Schema(requiredMode = REQUIRED)} 는 Swagger 문서에만 반영되므로 런타임에는 아무 효과가 없다.
 *
 * <p>그 결과 404 가 아니라 <b>500</b> 이 났다. {@code ExercisesRepository} 의
 * {@code @Cacheable(key = "#id")} 가 키 생성 단계에서 {@code IllegalArgumentException} 을 던져
 * JPQL 이 실행조차 되지 않기 때문이다 — "매치 0건 → 404" 경로에 도달하지 못한다.
 *
 * <p>⚠️ <b>회원에 {@code preferredUrl} 이 반드시 있어야 한다.</b> {@code ExerciseAnalysisService}
 * 가 {@code preferredUrl} 을 먼저 검사해 없으면 {@code INVALID_INPUT_VALUE}(400)로 끝내므로,
 * 온보딩 안 한 회원으로 치면 <b>검증이 없어도 400 이 나와 테스트가 통과한다</b> — 즉 아무것도 못 잡는다.
 * 이 함정이 이슈 본문에 명시돼 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("세션 시작 — 본문 검증 실패는 400")
class SessionStartBodyValidationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;

    private String userToken;

    @BeforeEach
    void setUp() {
        // preferredUrl 이 있는 = 온보딩을 마친 회원. 위 주석의 함정 참조.
        Member member = memberRepository.saveAndFlush(Member.builder()
                .email("session-validation@test.com")
                .username("검증테스트")
                .password("dummy")
                .preferredUrl("https://youtu.be/dummy")
                .selectedPersona(SelectedPersona.BEGINNER)
                .role(UserRole.USER)
                .build());
        userToken = jwtUtil.createAccessToken(CustomUserInfoDto.builder()
                .email(member.getEmail()).role(member.getRole()).build());
    }

    @Test
    @DisplayName("빈 객체 {} 는 400")
    void emptyBody() throws Exception {
        mockMvc.perform(post("/exercises/sessions")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("exerciseId 가 명시적 null 이면 400")
    void explicitNullExerciseId() throws Exception {
        mockMvc.perform(post("/exercises/sessions")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exerciseId\": null}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * 대조군 — 검증 추가가 정상 경로를 바꾸지 않았는지.
     * 존재하지 않는 ID 는 캐시 키 생성을 통과해 JPQL 까지 가고, 매치 0건이라 404 여야 한다.
     */
    @Test
    @DisplayName("존재하지 않는 exerciseId 는 그대로 404")
    void unknownExerciseIdStillNotFound() throws Exception {
        mockMvc.perform(post("/exercises/sessions")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exerciseId\": 999999}"))
                .andExpect(status().isNotFound());
    }
}