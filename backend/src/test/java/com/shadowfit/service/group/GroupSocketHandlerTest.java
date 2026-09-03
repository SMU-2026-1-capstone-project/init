package com.shadowfit.service.group;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.global.security.ws.JwtHandshakeInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 인증·인가는 {@link JwtHandshakeInterceptor}가 핸드셰이크에서 이미 끝내고 세션
 * attributes에 groupId/memberId를 심어둔다는 전제 위에서, 이 핸들러가 그 값을 그대로
 * 읽어 등록·해제·발행에 넘기는지만 검증한다.
 */
@DisplayName("GroupSocketHandler 테스트")
class GroupSocketHandlerTest {

    private static final Long GROUP_ID = 1L;
    private static final Long MEMBER_ID = 10L;

    @Mock private GroupSocketRegistry groupSocketRegistry;
    @Mock private GroupEventService groupEventService;

    private GroupSocketHandler handler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new GroupSocketHandler(groupSocketRegistry, groupEventService, new ObjectMapper());
    }

    @Test
    @DisplayName("연결 수립 시 세션 attributes의 groupId로 레지스트리에 등록한다")
    void afterConnectionEstablished_registersSession() {
        WebSocketSession session = sessionWithAttributes(GROUP_ID, MEMBER_ID);

        handler.afterConnectionEstablished(session);

        verify(groupSocketRegistry).register(GROUP_ID, session);
    }

    @Test
    @DisplayName("연결 종료 시 레지스트리에서 해제한다")
    void afterConnectionClosed_deregistersSession() {
        WebSocketSession session = sessionWithAttributes(GROUP_ID, MEMBER_ID);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(groupSocketRegistry).deregister(GROUP_ID, session);
    }

    @Test
    @DisplayName("type·payload가 있는 정상 프레임은 groupId·senderId와 함께 발행한다")
    void handleTextMessage_validFrame_publishesEvent() throws Exception {
        WebSocketSession session = sessionWithAttributes(GROUP_ID, MEMBER_ID);

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"REP_COMPLETED\",\"payload\":{\"rep\":1}}"));

        verify(groupEventService).publish(GROUP_ID, MEMBER_ID, "REP_COMPLETED", "{\"rep\":1}");
    }

    @Test
    @DisplayName("payload가 없는 프레임은 빈 객체 \"{}\"로 발행한다")
    void handleTextMessage_missingPayload_publishesEmptyObject() throws Exception {
        WebSocketSession session = sessionWithAttributes(GROUP_ID, MEMBER_ID);

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"PING\"}"));

        verify(groupEventService).publish(GROUP_ID, MEMBER_ID, "PING", "{}");
    }

    @Test
    @DisplayName("type이 없는 프레임은 발행하지 않는다")
    void handleTextMessage_missingType_doesNotPublish() throws Exception {
        WebSocketSession session = sessionWithAttributes(GROUP_ID, MEMBER_ID);

        handler.handleTextMessage(session, new TextMessage("{\"payload\":{}}"));

        verify(groupEventService, never()).publish(any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("JSON 파싱에 실패한 프레임은 예외 없이 무시되고 발행하지 않는다")
    void handleTextMessage_malformedJson_isIgnored() throws Exception {
        WebSocketSession session = sessionWithAttributes(GROUP_ID, MEMBER_ID);

        handler.handleTextMessage(session, new TextMessage("not-json"));

        verify(groupEventService, never()).publish(any(), any(), anyString(), anyString());
    }

    private WebSocketSession sessionWithAttributes(Long groupId, Long memberId) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(JwtHandshakeInterceptor.ATTR_GROUP_ID, groupId);
        attributes.put(JwtHandshakeInterceptor.ATTR_MEMBER_ID, memberId);
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }
}
