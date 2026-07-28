package com.shadowfit.global.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * HTTP 진입점의 correlation id 발급/수용 필터.
 *
 * <p>클라이언트(또는 앞단 프록시)가 {@code X-Request-Id}를 보내면 검증 후 그대로 이어받고,
 * 없으면 새로 발급한다. 응답에도 같은 헤더를 실어줘서 사용자가 "이 요청 id로 로그 봐달라"고
 * 말할 수 있게 한다.
 *
 * <p>[등록 방식] Spring Security 체인에 끼우지 않고 {@code @Component} + 최고 우선순위로 서블릿
 * 컨테이너에 직접 등록한다. Security 체인(기본 order -100)보다 먼저 실행되므로 <b>인증 실패
 * 응답·Actuator·에러 페이지까지</b> cid를 갖는다 — 체인 안에 넣으면 인증 거부 로그가 cid 없이
 * 남는 사각지대가 생긴다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String inbound = CorrelationIds.sanitize(request.getHeader(CorrelationIds.HTTP_HEADER));
        String correlationId = inbound != null ? inbound : CorrelationIds.newId();

        response.setHeader(CorrelationIds.HTTP_HEADER, correlationId);

        try (CorrelationIds.Scope ignored = CorrelationIds.withCorrelationId(correlationId)) {
            filterChain.doFilter(request, response);
        }
    }
}
