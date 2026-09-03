package com.shadowfit.service.group;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * broadcast()는 세션마다 전용 스레드로 전송을 넘기므로(#623 — 느린 소비자 격리를 위해
 * {@link com.shadowfit.service.group.GroupSocketRegistry} 클래스 주석 참고) 여기서 확인하는
 * 결과는 전부 비동기적으로 나타난다 — {@code verify(mock, timeout(ms))}로 기다린다.
 */
@DisplayName("GroupSocketRegistry 테스트")
class GroupSocketRegistryTest {

    private static final Long GROUP_ID = 1L;
    private static final long AWAIT_MS = 2000;

    private final GroupSocketRegistry registry = new GroupSocketRegistry(new SimpleMeterRegistry());
    private final AtomicInteger idSeq = new AtomicInteger();

    @Test
    @DisplayName("register 후 broadcast — 등록된 세션에 메시지가 전달된다")
    void broadcast_sendsToRegisteredSession() throws IOException {
        WebSocketSession session = openSession();
        registry.register(GROUP_ID, session);

        registry.broadcast(GROUP_ID, "{\"type\":\"REP_COMPLETED\"}");

        verify(session, timeout(AWAIT_MS)).sendMessage(new TextMessage("{\"type\":\"REP_COMPLETED\"}"));
    }

    @Test
    @DisplayName("broadcast — 같은 그룹의 세션 전부에 전달된다")
    void broadcast_sendsToAllSessionsInGroup() throws IOException {
        WebSocketSession session1 = openSession();
        WebSocketSession session2 = openSession();
        registry.register(GROUP_ID, session1);
        registry.register(GROUP_ID, session2);

        registry.broadcast(GROUP_ID, "payload");

        verify(session1, timeout(AWAIT_MS)).sendMessage(any(TextMessage.class));
        verify(session2, timeout(AWAIT_MS)).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("broadcast — 다른 그룹의 세션에는 전달되지 않는다")
    void broadcast_doesNotLeakToOtherGroups() throws IOException {
        WebSocketSession session = openSession();
        registry.register(2L, session);

        registry.broadcast(GROUP_ID, "payload");

        // 다른 그룹으로 보낸 뒤, 이 그룹에도 정상 전송해 "느려서 아직 안 왔다"와
        // "애초에 안 갔다"를 구분한다 — 이 확인이 끝나면 위 broadcast가 갈 일이 없었다는 뜻.
        registry.broadcast(2L, "own-group-payload");
        verify(session, timeout(AWAIT_MS)).sendMessage(any(TextMessage.class));
        verify(session, never()).sendMessage(new TextMessage("payload"));
    }

    @Test
    @DisplayName("broadcast — 등록된 세션이 없는 그룹이면 조용히 아무 일도 안 한다")
    void broadcast_unknownGroup_isNoop() {
        registry.broadcast(999L, "payload");
        // 예외 없이 끝나면 통과. 검증할 세션 자체가 없다.
    }

    @Test
    @DisplayName("deregister된 세션은 이후 broadcast에서 제외된다")
    void broadcast_afterDeregister_excludesSession() throws IOException {
        WebSocketSession session = openSession();
        registry.register(GROUP_ID, session);
        registry.deregister(GROUP_ID, session);

        registry.broadcast(GROUP_ID, "payload");

        // deregister는 동기적으로 맵에서 지우므로(실행 큐에 아예 안 들어감) 곧바로 확인해도 된다.
        verify(session, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("이미 닫힌 세션은 전송 시도 없이 정리된다")
    void broadcast_closedSession_isCleanedUpWithoutSendAttempt() throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(nextId());
        when(session.isOpen()).thenReturn(false);
        registry.register(GROUP_ID, session);

        registry.broadcast(GROUP_ID, "payload");

        verify(session, timeout(AWAIT_MS)).isOpen();
        verify(session, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("전송 중 IOException이 나면 세션을 정리하고 SERVER_ERROR로 닫는다")
    void broadcast_sendFailure_deregistersAndClosesSession() throws IOException {
        WebSocketSession session = openSession();
        org.mockito.Mockito.doThrow(new IOException("broken pipe")).when(session).sendMessage(any(TextMessage.class));
        registry.register(GROUP_ID, session);

        registry.broadcast(GROUP_ID, "payload");

        verify(session, timeout(AWAIT_MS)).close(CloseStatus.SERVER_ERROR);
    }

    @Test
    @DisplayName("전송이 막힌 채로 대기열이 다 차면(짧은 버스트는 견디되) 그 세션을 정리한다"
            + " (#623 후속 — 무제한 대기열 대신 유한 큐, 클래스 주석 \"대기열 상한\" 참고)")
    void broadcast_queueSaturated_evictsSlowSession() throws Exception {
        WebSocketSession session = openSession();
        CountDownLatch firstSendStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstSend = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            firstSendStarted.countDown();
            releaseFirstSend.await();
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        registry.register(GROUP_ID, session);

        registry.broadcast(GROUP_ID, "in-flight"); // 세션 전용 워커가 이걸 받아 즉시 실행·블록한다
        org.assertj.core.api.Assertions.assertThat(
                firstSendStarted.await(AWAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)).isTrue();

        // 짧은 버스트(대기열 용량보다 훨씬 적은 수)는 거부 없이 큐에 쌓여야 한다 — 이게
        // SynchronousQueue(대기 자리 0)에서 유한 큐로 바꾼 이유다(재연결 복구 통합테스트가
        // 3건 연속 발행으로 이미 이 조건을 요구한다).
        for (int i = 0; i < 3; i++) {
            registry.broadcast(GROUP_ID, "burst-" + i);
        }
        verify(session, never()).close(any(CloseStatus.class));

        // 대기열을 실제로 채워야(용량 이상) 거부·정리가 일어난다.
        for (int i = 0; i < 64; i++) {
            registry.broadcast(GROUP_ID, "overflow-" + i);
        }

        // 거부되면 이 세션을 registry에서 빼고 SERVER_ERROR로 닫는다(전송 실패와 같은 정리 경로).
        verify(session, timeout(AWAIT_MS)).close(CloseStatus.SERVER_ERROR);

        releaseFirstSend.countDown(); // 첫 전송을 마저 끝내 워커 스레드를 정리한다(테스트 정리)
    }

    private WebSocketSession openSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(nextId());
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private String nextId() {
        return "session-" + idSeq.incrementAndGet();
    }
}
