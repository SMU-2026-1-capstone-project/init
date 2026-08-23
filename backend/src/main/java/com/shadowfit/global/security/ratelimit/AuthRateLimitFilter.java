package com.shadowfit.global.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.error.ErrorResponseDto;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * <b>IP 단위</b> 인증 경로 시도 제한 (이슈 #394).
 *
 * <p>[무엇을 막나] 한 곳에서 여러 계정을 훑는 것 — 계정 대량 생성(가입 스팸) 과
 * 계정 열거·크리덴셜 스터핑. 반대 방향(여러 IP → 한 계정)은 {@link LoginAttemptLimiter} 가 받는다.
 *
 * <p>[왜 시큐리티 체인이 아니라 서블릿 필터인가] {@code CorrelationIdFilter} 와 같은 이유다 —
 * 대상 경로가 전부 {@code permitAll} 이라 체인 안쪽이든 바깥쪽이든 인증은 안 걸리는데,
 * 바깥쪽에 두면 <b>거부 응답에도 cid 가 붙는다</b>(그 필터가 더 앞이다). 그리고 SecurityConfig
 * 를 안 건드려도 된다.
 *
 * <p>🔴 <b>클라이언트 IP 를 {@code getRemoteAddr()} 로 읽는 것은 의도다.</b>
 * {@code X-Forwarded-For} 를 믿지 않는다 — {@code application-prod.yml} 의
 * {@code forward-headers-strategy} 기본값이 {@code none} 이고, 그 주석이 이유를 적고 있다:
 * 프록시가 그 헤더를 <b>덮어쓰도록</b> 설정돼 있지 않으면 <b>아무나 자기 IP 를 위조</b>할 수
 * 있다. 위조 가능한 값을 제한 키로 쓰면 이 장치는 <b>있으나 마나</b>가 된다.
 *
 * <p>🔴 <b>그 대신 반대쪽 함정이 생긴다.</b> 프록시를 앞에 세우고 {@code FORWARD_HEADERS_STRATEGY}
 * 를 안 켜면 <b>모든 요청의 remote addr 이 프록시 IP 하나</b>가 되어, IP 한도가 사실상
 * <b>전역 한도</b>로 붕괴한다 — 정상 사용자 전체가 분당 {@code ipPerWindow} 회를 나눠 쓴다.
 * 그 조합을 기동 시 경고로 잡는다({@link #warnIfProxyMisconfigured()}). 프록시를 세우는 날
 * 반드시 같이 볼 것 ({@code docs/decisions/reverse-proxy-and-tls.md}).
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)   // CorrelationIdFilter(HIGHEST) 다음 — 거부 로그도 cid 를 갖게
public class AuthRateLimitFilter extends OncePerRequestFilter {

    /**
     * 제한 대상. 셋 다 {@code security.whitelist} 의 {@code permitAll} 경로다 —
     * <b>인증 없이 부를 수 있다는 것이 곧 제한이 필요한 이유</b>다.
     *
     * <p>{@code /member/reissue} 는 앞 둘보다 심각도가 낮다(회전 + 재사용 탐지가 이미 받는다,
     * #135). 그래도 넣는 이유는 «무효 토큰을 계속 던져 서명 검증 비용을 태우는» 형태가
     * 남아 있어서고, 같은 버킷이 아니라 <b>경로별 버킷</b>이라 서로 예산을 안 뺏는다.
     */
    private static final Set<String> PROTECTED_PATHS =
            Set.of("/member/login", "/member/signup", "/member/reissue");

    private final AuthRateLimitProperties properties;
    private final FixedWindowCounter counter;
    private final ObjectMapper objectMapper;

    @Value("${server.forward-headers-strategy:none}")
    private String forwardHeadersStrategy;

    public AuthRateLimitFilter(AuthRateLimitProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.counter = new FixedWindowCounter(properties.getWindowSeconds(), properties.getMaxKeys());
    }

    /**
     * 위 🔴 두 번째 함정을 기동 시 한 번 짚는다. 판정할 수 없는 것("프록시가 앞에 있는가")은
     * 단정하지 않고, <b>설정 조합이 그 함정에 해당한다</b>는 사실만 남긴다.
     */
    @PostConstruct
    void warnIfProxyMisconfigured() {
        if (properties.isEnabled() && "none".equalsIgnoreCase(forwardHeadersStrategy)) {
            log.info("인증 경로 시도 제한 ON — IP 키는 remote addr 기준. "
                    + "🔴 앞에 리버스 프록시를 세웠다면 FORWARD_HEADERS_STRATEGY 를 같이 켤 것. "
                    + "안 켜면 모든 요청이 프록시 IP 하나로 보여 IP 한도가 전역 한도로 붕괴한다.");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!properties.isEnabled() || !isProtected(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = request.getRequestURI() + "|" + request.getRemoteAddr();
        if (counter.increment(key) > properties.getIpPerWindow()) {
            reject(request, response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** POST 만 본다 — 같은 경로의 preflight(OPTIONS)까지 세면 브라우저가 한도를 절반 먹는다. */
    private boolean isProtected(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && PROTECTED_PATHS.contains(request.getRequestURI());
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ErrorCode code = ErrorCode.TOO_MANY_AUTH_REQUESTS;
        // 🔴 IP 를 로그에 찍지 않는다. 이 줄은 «제한이 걸렸다» 를 알리는 용도고,
        //    누구였는지가 필요하면 같은 줄의 cid 로 접근 로그를 되짚는다.
        log.warn("인증 경로 시도 제한 초과 — path={}, 창 {}초, 한도 {}회",
                request.getRequestURI(), properties.getWindowSeconds(), properties.getIpPerWindow());

        response.setStatus(code.getStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 429 는 «언제 다시 오라» 를 못 주면 반쪽이다. 클라가 재시도 간격을 정할 수 있게 한다.
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(counter.retryAfterSeconds()));

        // 본문 형식을 GlobalExceptionHandler 와 맞춘다 — 이 필터는 MVC 밖이라 그 핸들러가
        // 안 도는데, 클라 입장에서 «어떤 실패는 모양이 다르다» 가 되면 안 된다.
        response.getWriter().write(objectMapper.writeValueAsString(
                ErrorResponseDto.builder()
                        .status(code.getStatus())
                        .message(code.getMessage())
                        .timestamp(LocalDateTime.now())
                        .build()));
    }
}
