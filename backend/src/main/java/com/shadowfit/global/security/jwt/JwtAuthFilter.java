package com.shadowfit.global.security.jwt;

import com.shadowfit.service.Member.CustomUserDetailsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final CustomUserDetailsService customUserDetailsService;
    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/ws")) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        // [로그 1] 헤더가 들어오는지 확인
        log.info("Request URI: {}, Authorization Header: {}", path, authHeader);

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            try {
                // 블랙리스트 대조가 여기 있었다 (이슈 #137, decisions/token-lifecycle.md ㄴ-4).
                // 없앤 이유: 로그아웃이 refresh 를 확실히 지워 **갱신 경로가 끊기고**, access 수명이
                // 30분이라 남는 노출이 그 잔여 수명뿐이기 때문이다. 저장소를 고르는 대신 저장할
                // 대상을 없앴다 — 재기동 부활도, 다중 인스턴스 동기화도 같이 사라진다.
                if (jwtUtil.isValidToken(token)) {
                    String userEmail = jwtUtil.getUserEmail(token);

                    if (userEmail != null) {
                        UserDetails userDetails = customUserDetailsService.loadUserByUsername(userEmail);

                        if (userDetails != null) {
                            UsernamePasswordAuthenticationToken auth =
                                    new UsernamePasswordAuthenticationToken(
                                            userDetails,
                                            null,
                                            userDetails.getAuthorities()
                                    );
                            SecurityContextHolder.getContext().setAuthentication(auth);
                            // [로그 2] 인증 성공 확인
                            log.info("인증 성공: email = {}", userEmail);
                        }
                    } else {
                        log.warn("토큰의 Subject(Email)가 비어있습니다.");
                    }
                } else {
                    log.warn("유효하지 않은 토큰입니다.");
                }
            } catch (Exception e) {
                log.error("JWT 인증 에러: {}", e.getMessage());
            }
        } else {
            // [로그 3] 토큰이 없는 경우
            log.warn("Authorization 헤더가 없거나 형식이 잘못되었습니다.");
        }

        filterChain.doFilter(request, response);
    }
}