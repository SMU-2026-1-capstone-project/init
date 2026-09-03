package com.shadowfit.global.config;

import com.shadowfit.global.security.ws.JwtHandshakeInterceptor;
import com.shadowfit.service.group.GroupSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 그룹 실시간 동기화 WebSocket 엔드포인트 등록. STOMP가 아니라 raw
 * {@link org.springframework.web.socket.WebSocketHandler}를 쓰는 이유는
 * {@code docs/decisions/sse-vs-websocket-review-depth.md}에 이미 정리돼 있다 —
 * Redis는 순수 pub/sub이지 STOMP 브로커가 아니라, 나중에 다중 인스턴스로 갈 때도
 * STOMP 브로커 릴레이보다 직접 연동이 더 맞는 방향이다.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final GroupSocketHandler groupSocketHandler;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(groupSocketHandler, "/ws/groups/{groupId}")
                .addInterceptors(jwtHandshakeInterceptor)
                // SockJS 폴백 없음 — 클라이언트는 프론트(React Native/Expo 등) 자체 WebSocket
                // 클라이언트를 쓰므로 브라우저 구형 폴백이 필요 없다.
                .setAllowedOriginPatterns("*");
    }
}