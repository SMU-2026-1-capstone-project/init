package com.shadowfit.global.security.config;

import com.shadowfit.global.security.jwt.JwtAuthFilter;
import com.shadowfit.global.security.jwt.JwtBlacklist;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.service.Member.CustomUserDetailsService;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@AllArgsConstructor
public class SecurityConfig {
    private final CustomUserDetailsService customUserDetailsService;
    private final JwtUtil jwtUtil;
    private final CustomAccessDeniedHandler accessDeniedHandler;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final SecurityPathConfig securityPathConfig;

    @PostConstruct
    public void setupSecurityContext() {
        // 비동기 스레드(워커 스레드)로 SecurityContext를 전파하는 설정
        SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtBlacklist jwtBlacklist) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        http.authorizeHttpRequests(authorize -> authorize

                // 화이트리스트 (배열을 그대로 전달)
                .requestMatchers(securityPathConfig.getWhiteListArray()).permitAll()

                // 그 외 모든 요청 인증 필요
                .anyRequest().authenticated());

        http.securityContext(context -> context
                .requireExplicitSave(false));


        // JWT 필터 추가
        http.addFilterBefore(new JwtAuthFilter(customUserDetailsService, jwtUtil, jwtBlacklist),
                UsernamePasswordAuthenticationFilter.class);

        http.exceptionHandling(exception -> exception
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler));

        return http.build();
    }
}