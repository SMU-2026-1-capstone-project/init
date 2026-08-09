package com.shadowfit.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

//CORS 설정
@Configuration
public class WebConfig implements  WebMvcConfigurer{
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                // PATCH 는 실제 엔드포인트 6곳이 쓴다 (세션 종료 · TTS 설정 · 온보딩 · 관리자 운동 3종).
                // 빠져 있으면 브라우저가 preflight 단계에서 본 요청을 아예 보내지 않는다.
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE")
                .allowCredentials(true);
    }
}
