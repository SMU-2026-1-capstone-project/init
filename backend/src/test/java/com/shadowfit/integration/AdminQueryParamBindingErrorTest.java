package com.shadowfit.integration;

import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 목록 API 의 잘못된 쿼리 파라미터가 400 으로 나가는지 검증한다.
 *
 * <p>배경 — {@code GlobalExceptionHandler} 는 {@code MethodArgumentNotValidException}(@Valid 실패)만
 * 400 으로 잡고 있었다. 그런데 관리자 목록은 <b>@Valid 를 안 쓴다.</b> 필터는 전부 선택 항목이라
 * 검증할 제약이 없고, 대신 <b>타입 변환</b>이 검증 역할을 한다 — {@code sort=BOGUS} 는 enum 에
 * 없으므로 바인딩 단계에서 터진다. 그 예외는 위 핸들러에 안 걸려
 * {@code @ExceptionHandler(Exception.class)} 로 떨어지고 <b>500</b> 이 나갔다.
 *
 * <p>이건 같은 클래스의 {@code AccessDeniedException} 버그(핸들러 주석 2026-07-24)와 형태가 같다 —
 * "핸들러가 없어서 500 이 대신 나간다". 그때는 403 이 500 이 됐고, 여기서는 400 이 500 이 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("관리자 목록 — 쿼리 파라미터 바인딩 실패는 400")
class AdminQueryParamBindingErrorTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;

    private String adminToken;

    @BeforeEach
    void setUp() {
        Member admin = memberRepository.saveAndFlush(Member.builder()
                .email("admin-binding@test.com").username("a").password("dummy")
                .role(UserRole.ADMIN).build());
        adminToken = jwtUtil.createAccessToken(CustomUserInfoDto.builder()
                .email(admin.getEmail()).role(admin.getRole()).build());
    }

    /** @RequestParam enum 변환 실패 → MethodArgumentTypeMismatchException */
    @Test
    @DisplayName("세션 목록 — 정렬 키가 enum 에 없는 값이면 400")
    void sessionSortKeyUnknown() throws Exception {
        mockMvc.perform(get("/admin/sessions").param("sort", "BOGUS")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("회원 목록 — 정렬 키가 enum 에 없는 값이면 400")
    void memberSortKeyUnknown() throws Exception {
        mockMvc.perform(get("/admin/members").param("sort", "BOGUS")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    /** @RequestParam int 변환 실패 — 같은 예외지만 enum 이 아닌 경로 */
    @Test
    @DisplayName("세션 목록 — page 가 숫자가 아니면 400")
    void sessionPageNotNumeric() throws Exception {
        mockMvc.perform(get("/admin/sessions").param("page", "abc")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    /**
     * @ModelAttribute 바인딩 실패 → BindException.
     * 위 셋과 예외 타입이 다르다 — 검색조건 record 안의 필드라 @RequestParam 경로를 안 탄다.
     */
    @Test
    @DisplayName("세션 목록 — 상태 필터가 enum 에 없는 값이면 400")
    void sessionStatusUnknown() throws Exception {
        mockMvc.perform(get("/admin/sessions").param("status", "BOGUS")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("세션 목록 — 날짜 형식이 ISO 가 아니면 400")
    void sessionDateMalformed() throws Exception {
        mockMvc.perform(get("/admin/sessions").param("startedFrom", "2026/01/01")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }
}
