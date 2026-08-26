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
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE");
                // allowCredentials(true)를 뺐다 — 인증은 JWT Bearer 헤더뿐이고 쿠키를 어디도
                // 안 쓴다(Set-Cookie·withCredentials 전수 grep 0건). allowedOriginPatterns("*")
                // 는 Spring이 allowedOrigins("*")+credentials 조합처럼 기동 시 막아주지 않아서,
                // 이 조합이면 요청 Origin을 그대로 반사(reflect)해 "자격증명 포함 요청을 아무
                // 오리진에서나 허용"이 된다 — ai-server(FastAPI)에서 고친 것과 같은 결함이었다.
    }
}
