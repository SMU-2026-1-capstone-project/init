package com.shadowfit.global.security.jwt;

import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.service.Member.CustomUserDetailsService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JwtAuthFilter 단위테스트 — 인증 필터 자체가 지금까지 무테스트였던 영역. 정상 인증뿐 아니라
 * 무효 토큰·블랙리스트·회원 없음·헤더 없음·/ws 경로 예외 케이스까지 검증.
 */
@DisplayName("JwtAuthFilter 테스트")
class JwtAuthFilterTest {

    @Mock private CustomUserDetailsService customUserDetailsService;
    @Mock private JwtUtil jwtUtil;
    private JwtBlacklist jwtBlacklist; // 순수 인메모리 맵이라 실사용
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        jwtBlacklist = new JwtBlacklist();
        filter = new JwtAuthFilter(customUserDetailsService, jwtUtil, jwtBlacklist);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private CustomUserDetails userDetails() {
        Member member = Member.builder().id(1L).email("test@test.com")
                .username("u").password("pw").role(UserRole.USER).build();
        return new CustomUserDetails(member);
    }

    @Test
    @DisplayName("유효한 Bearer 토큰이면 SecurityContext에 인증 정보가 설정됨")
    void validToken_setsAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/sessions/1");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(jwtUtil.isValidToken("valid-token")).thenReturn(true);
        when(jwtUtil.getUserEmail("valid-token")).thenReturn("test@test.com");
        when(customUserDetailsService.loadUserByUsername("test@test.com")).thenReturn(userDetails());

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("test@test.com");
        verify(chain, times(1)).doFilter(request, response);
    }

    @Test
    @DisplayName("Authorization 헤더가 없으면 인증 없이 체인만 진행")
    void noAuthHeader_skipsAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/sessions/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, times(1)).doFilter(request, response);
        verify(jwtUtil, never()).isValidToken(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("무효 토큰이면 인증 안 됨, 체인은 그대로 진행(에러 응답 아님)")
    void invalidToken_noAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/sessions/1");
        request.addHeader("Authorization", "Bearer bad-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(jwtUtil.isValidToken("bad-token")).thenReturn(false);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, times(1)).doFilter(request, response);
    }

    @Test
    @DisplayName("블랙리스트에 등록된 토큰이면(로그아웃됨) 검증에 유효해도 인증 안 됨")
    void blacklistedToken_noAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/sessions/1");
        request.addHeader("Authorization", "Bearer logged-out-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(jwtUtil.isValidToken("logged-out-token")).thenReturn(true);
        jwtBlacklist.add("logged-out-token", System.currentTimeMillis() + 100000);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, times(1)).doFilter(request, response);
    }

    @Test
    @DisplayName("토큰은 유효하지만 회원을 못 찾으면 예외를 삼키고 체인은 계속 진행")
    void validTokenButUserNotFound_swallowsExceptionAndContinues() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/sessions/1");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(jwtUtil.isValidToken("valid-token")).thenReturn(true);
        when(jwtUtil.getUserEmail("valid-token")).thenReturn("ghost@test.com");
        when(customUserDetailsService.loadUserByUsername("ghost@test.com"))
                .thenThrow(new UsernameNotFoundException("no such user"));

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, times(1)).doFilter(request, response); // 예외가 필터 체인을 끊지 않음
    }

    @Test
    @DisplayName("/ws 로 시작하는 경로는 인증 로직 자체를 건너뜀")
    void wsPath_skipsAuthEntirely() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/somewhere");
        request.addHeader("Authorization", "Bearer whatever");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(jwtUtil, never()).isValidToken(org.mockito.ArgumentMatchers.anyString());
        verify(chain, times(1)).doFilter(request, response);
    }
}
