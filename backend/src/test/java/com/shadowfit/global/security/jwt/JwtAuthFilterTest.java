package com.shadowfit.global.security.jwt;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.shadowfit.global.observability.CorrelationIds;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.service.member.CustomUserDetailsService;
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
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JwtAuthFilter 단위테스트 — 인증 필터 자체가 지금까지 무테스트였던 영역. 정상 인증뿐 아니라
 * 무효 토큰·로그아웃 토큰·회원 없음·헤더 없음·/ws 경로 예외 케이스까지 검증.
 */
@DisplayName("JwtAuthFilter 테스트")
class JwtAuthFilterTest {

    @Mock private CustomUserDetailsService customUserDetailsService;
    @Mock private JwtUtil jwtUtil;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        filter = new JwtAuthFilter(customUserDetailsService, jwtUtil);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    /** 체인 실행 «도중» 의 MDC actor 값을 잡아 두는 가짜 체인 — 필터가 끝난 뒤에는 지워지므로. */
    private static FilterChain capturingActor(AtomicReference<String> sink) {
        return (req, res) -> sink.set(MDC.get(CorrelationIds.ACTOR_MDC_KEY));
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
    @DisplayName("로그아웃한 토큰도 서명이 유효하면 잔여 수명 동안 인증된다 — 블랙리스트를 없앤 대가 (#137)")
    void loggedOutToken_stillAuthenticatesUntilExpiry() throws Exception {
        // 이 테스트는 «고쳐야 할 결함» 이 아니라 **의도한 동작**을 고정한다.
        // 블랙리스트를 없애면서(decisions/token-lifecycle.md ㄴ-4) 로그아웃의 의미가
        // «서버가 즉시 끊는다» 에서 «갱신이 끊기고 곧 만료된다» 로 바뀌었다. 그 대가의 크기가
        // 곧 access 수명(30분)이고, 여기가 그 사실이 코드로 남는 자리다.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/sessions/1");
        request.addHeader("Authorization", "Bearer logged-out-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(jwtUtil.isValidToken("logged-out-token")).thenReturn(true);
        when(jwtUtil.getUserEmail("logged-out-token")).thenReturn("test@test.com");
        when(customUserDetailsService.loadUserByUsername("test@test.com")).thenReturn(userDetails());

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
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

    // ─────────────────────────────────────────────────────────────────────────
    // actor MDC (이슈 #395) · 토큰·이메일 로그 제거 (이슈 #411)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("인증되면 체인이 도는 동안 MDC actor 에 member_id 가 들어 있다 (#395)")
    void authenticated_putsMemberIdInMdcDuringChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/admin/exercises/1/thresholds");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        when(jwtUtil.isValidToken("valid-token")).thenReturn(true);
        when(jwtUtil.getUserEmail("valid-token")).thenReturn("test@test.com");
        when(customUserDetailsService.loadUserByUsername("test@test.com")).thenReturn(userDetails());

        filter.doFilter(request, response, capturingActor(seen));

        // member_id 다. 이메일이 아니다 — 로그는 유출 표면이라 되짚을 수만 있으면 된다.
        assertThat(seen.get()).isEqualTo("1");
        assertThat(seen.get()).doesNotContain("@");
    }

    @Test
    @DisplayName("요청이 끝나면 MDC actor 가 지워진다 — 톰캣 워커 재사용으로 다음 요청에 새면 안 됨")
    void afterRequest_actorIsCleared() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/sessions/1");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(jwtUtil.isValidToken("valid-token")).thenReturn(true);
        when(jwtUtil.getUserEmail("valid-token")).thenReturn("test@test.com");
        when(customUserDetailsService.loadUserByUsername("test@test.com")).thenReturn(userDetails());

        filter.doFilter(request, response, mock(FilterChain.class));

        // 이게 깨지면 워커에 남은 actor 가 다음 요청 로그에 찍힌다 — SecurityConfig 가
        // MODE_INHERITABLETHREADLOCAL 을 거부한 것과 같은 종류의 오염이다.
        assertThat(MDC.get(CorrelationIds.ACTOR_MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("인증 안 된 요청은 actor 가 비어 있다 — 로그인·회원가입 경로의 정상 동작")
    void unauthenticated_hasNoActor() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/member/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>("sentinel");

        filter.doFilter(request, response, capturingActor(seen));

        assertThat(seen.get()).isNull();
    }

    @Test
    @DisplayName("이전 요청의 actor 가 남아 있어도 인증 없는 요청이 그 값을 물려받지 않는다")
    void unauthenticated_doesNotInheritStaleActor() throws Exception {
        // 워커 재사용 상황을 직접 만든다: MDC 에 이전 요청 값이 남아 있는 채로 시작.
        MDC.put(CorrelationIds.ACTOR_MDC_KEY, "999");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/member/signup");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>("sentinel");

        filter.doFilter(request, response, capturingActor(seen));

        // 체인이 도는 동안에는 지워져 있어야 한다 (withActor(null) 이 키를 remove 한다)...
        assertThat(seen.get()).isNull();
        // ...끝나면 원래 값으로 복원된다. Scope 의 계약이 «지운다» 가 아니라 «되돌린다» 라서다.
        assertThat(MDC.get(CorrelationIds.ACTOR_MDC_KEY)).isEqualTo("999");
    }

    @Test
    @DisplayName("인증 경로 로그에 토큰 원문도 이메일도 남지 않는다 (#411)")
    void authLogs_containNeitherTokenNorEmail() throws Exception {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Logger filterLogger = (Logger) LoggerFactory.getLogger(JwtAuthFilter.class);
        filterLogger.addAppender(appender);
        appender.start();

        try {
            // 성공 · 무효 토큰 · 예외 — 로그가 나올 수 있는 세 갈래를 모두 태운다.
            MockHttpServletRequest ok = new MockHttpServletRequest("GET", "/sessions/1");
            ok.addHeader("Authorization", "Bearer valid-token");
            when(jwtUtil.isValidToken("valid-token")).thenReturn(true);
            when(jwtUtil.getUserEmail("valid-token")).thenReturn("test@test.com");
            when(customUserDetailsService.loadUserByUsername("test@test.com")).thenReturn(userDetails());
            filter.doFilter(ok, new MockHttpServletResponse(), mock(FilterChain.class));

            MockHttpServletRequest bad = new MockHttpServletRequest("GET", "/sessions/1");
            bad.addHeader("Authorization", "Bearer bad-token");
            when(jwtUtil.isValidToken("bad-token")).thenReturn(false);
            filter.doFilter(bad, new MockHttpServletResponse(), mock(FilterChain.class));

            MockHttpServletRequest boom = new MockHttpServletRequest("GET", "/sessions/1");
            boom.addHeader("Authorization", "Bearer boom-token");
            when(jwtUtil.isValidToken("boom-token"))
                    .thenThrow(new IllegalStateException("token=boom-token 이 섞인 예외 메시지"));
            filter.doFilter(boom, new MockHttpServletResponse(), mock(FilterChain.class));

            String logged = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (a, b) -> a + " | " + b);

            // 토큰 조각이 어디에도 없어야 한다 — 예외 메시지를 그대로 찍던 자리 포함.
            assertThat(logged).doesNotContain("valid-token")
                    .doesNotContain("bad-token")
                    .doesNotContain("boom-token")
                    .doesNotContain("Bearer");
            // 이메일도 마찬가지.
            assertThat(logged).doesNotContain("test@test.com").doesNotContain("@");
        } finally {
            filterLogger.detachAppender(appender);
        }
    }
}
