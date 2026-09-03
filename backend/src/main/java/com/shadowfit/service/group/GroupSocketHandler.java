package com.shadowfit.service.group;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.global.security.ws.JwtHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 그룹 WebSocket 연결 하나의 생명주기·수신 메시지 처리. 인증·인가는
 * {@link JwtHandshakeInterceptor}가 핸드셰이크 단계에서 이미 끝내고 {@code groupId}·
 * {@code memberId}를 세션 attributes에 심어준다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupSocketHandler extends TextWebSocketHandler {

    private final GroupSocketRegistry groupSocketRegistry;
    private final GroupEventService groupEventService;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        groupSocketRegistry.register(groupId(session), session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        groupSocketRegistry.deregister(groupId(session), session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long groupId = groupId(session);
        Long senderId = memberId(session);

        JsonNode frame;
        try {
            frame = objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            log.info("그룹 소켓 수신 프레임 파싱 실패 (groupId={}, memberId={})", groupId, senderId, e);
            return;
        }

        String type = frame.path("type").asText(null);
        if (type == null || type.isBlank()) {
            log.info("그룹 소켓 수신 프레임에 type이 없음 (groupId={}, memberId={})", groupId, senderId);
            return;
        }

        JsonNode payloadNode = frame.path("payload");
        String payload = payloadNode.isMissingNode() ? "{}" : payloadNode.toString();

        groupEventService.publish(groupId, senderId, type, payload);
    }

    private Long groupId(WebSocketSession session) {
        return (Long) session.getAttributes().get(JwtHandshakeInterceptor.ATTR_GROUP_ID);
    }

    private Long memberId(WebSocketSession session) {
        return (Long) session.getAttributes().get(JwtHandshakeInterceptor.ATTR_MEMBER_ID);
    }
}