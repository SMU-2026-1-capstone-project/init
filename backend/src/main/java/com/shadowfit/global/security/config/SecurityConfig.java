package com.shadowfit.global.security.config;

import com.shadowfit.global.security.jwt.JwtAuthFilter;
import com.shadowfit.global.security.jwt.JwtBlacklist;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.service.Member.CustomUserDetailsService;
import lombok.AllArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
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

    // 🔴 SecurityContextHolder 의 저장 전략을 건드리지 않는다 (이슈 #177, 2026-08-12).
    //
    // 여기 MODE_INHERITABLETHREADLOCAL 을 거는 @PostConstruct 가 있었다. "비동기 스레드로
    // SecurityContext 를 전파" 가 목적이었는데, 스레드풀에서는 그 목적을 달성하지 못한다 —
    // InheritableThreadLocal 은 값을 **자식 스레드가 생성되는 순간** 복사하지 작업이 제출될
    // 때 복사하지 않는다. ThreadPoolTaskExecutor 는 스레드를 한 번 만들어 재사용하므로,
    // 풀이 커질 때 우연히 거기 있던 요청의 신원이 워커에 박히고 이후 다른 요청의 작업이
    // 같은 워커에서 돌아도 그대로 남는다. 즉 전파가 아니라 **오염**이다.
    //
    // 지금 사고가 안 나는 이유는 아무도 안 읽기 때문이다 — SecurityContextHolder 를 쓰는
    // 곳은 JwtAuthFilter(요청 스레드에서 set)뿐이고, @Async 경로(ExerciseAnalysisService ·
    // PoseDataCleanupService)는 인증 주체를 보지 않는다. 나중에 비동기에서 신원이 필요해지면
    // 전략을 바꾸는 게 아니라 필요한 값을 인자로 넘기거나 DelegatingSecurityContextExecutor
    // 처럼 **제출 시점에 복사하는** 장치를 쓸 것.

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