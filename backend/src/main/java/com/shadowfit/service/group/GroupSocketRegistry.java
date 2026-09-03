package com.shadowfit.service.group;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 그룹 id → 이 인스턴스가 들고 있는 로컬 WebSocket 세션 집합. 단일 인스턴스 전제라
 * 이 이상의 것(어느 인스턴스가 누굴 들고 있는지)은 필요 없다 — 다중 인스턴스로 갈 때
 * Redis 패턴 구독으로 이 자리를 대체/확장한다.
 *
 * <p><b>느린 소비자 격리(#623)</b>. 예전에는 {@code broadcast()}가 그룹 세션을 순차
 * for-loop로 돌며 세션마다 동기 {@code sendMessage()}를 불렀다 — 느린 멤버 하나가
 * OS 수신 버퍼를 못 비우면 그 send가 블록되고, {@code GroupEventService.publish()}가
 * {@code @Transactional} 커밋 후 콜백에서 이 broadcast를 동기 호출하므로 발행자 세션의
 * 다음 메시지 처리(및 같은 그룹의 나머지 멤버 전달)까지 같이 밀렸다. 지금은:
 * <ol>
 *   <li>세션마다 {@link ConcurrentWebSocketSessionDecorator}로 감싸 전송 시간·버퍼
 *       상한을 둔다 — 값은 Spring이 STOMP 브로드캐스트({@code SubProtocolWebSocketHandler})
 *       에 쓰는 기본값(10s / 512KB)을 그대로 가져왔다. 이 프로젝트에 맞춘 실측 임계값이
 *       아니라 "느린 소비자를 무한정 봐주지 않는다"는 상한선일 뿐이다.
 *   <li>세션마다 전용 단일 스레드로 전송을 넘긴다 — 느린 세션의 블로킹이 그 세션의
 *       전용 스레드에만 갇히고, 발행자 스레드와 다른 세션으로는 새지 않는다.
 * </ol>
 * <b>트레이드오프</b>: 연결마다 스레드 하나를 상시 점유한다(스레드-커넥션 비율 1:1).
 * 그룹 규모가 지금(단일 인스턴스, 소규모)보다 훨씬 커지면 이 자체가 새 캐파시티
 * 질문이 된다 — 그때는 공유 스레드풀 + 세션별 큐로 바꾸는 편이 맞다.
 */
@Slf4j
@Component
public class GroupSocketRegistry {

    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    private record Entry(ConcurrentWebSocketSessionDecorator session, ExecutorService executor) {
    }

    private final Map<Long, Map<String, Entry>> sessionsByGroup = new ConcurrentHashMap<>();

    /**
     * docs/decisions/group-websocket-heartbeat.md §6 — heartbeat 도입 전, "비정상 종료를
     * 감지하는 데 실제로 얼마나 걸리는가"를 실측하기 위한 게이지. deregister()가 불릴 때만
     * 줄어들므로, TCP만으로 감지되는 시간(§9-3의 "60~120초 사이 어딘가")을 이 값이 베이스라인으로
     * 돌아오는 시점으로 직접 관측할 수 있다.
     */
    public GroupSocketRegistry(MeterRegistry registry) {
        Gauge.builder("shadowfit.group.ws.active.sessions", this, GroupSocketRegistry::totalSessionCount)
                .description("이 인스턴스가 들고 있는 그룹 WebSocket 활성 세션 수(전체 그룹 합산)")
                .register(registry);
    }

    private double totalSessionCount() {
        return sessionsByGroup.values().stream().mapToInt(Map::size).sum();
    }

    public void register(Long groupId, WebSocketSession session) {
        Entry entry = new Entry(
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES),
                Executors.newSingleThreadExecutor());
        sessionsByGroup.computeIfAbsent(groupId, id -> new ConcurrentHashMap<>()).put(session.getId(), entry);
    }

    public void deregister(Long groupId, WebSocketSession session) {
        Map<String, Entry> sessions = sessionsByGroup.get(groupId);
        if (sessions == null) {
            return;
        }
        Entry entry = sessions.remove(session.getId());
        if (entry != null) {
            entry.executor().shutdownNow();
        }
        if (sessions.isEmpty()) {
            sessionsByGroup.remove(groupId, sessions);
        }
    }

    public void broadcast(Long groupId, String json) {
        Map<String, Entry> sessions = sessionsByGroup.get(groupId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        TextMessage message = new TextMessage(json);
        // 세션별 전용 스레드로 넘기고 이 호출은 바로 리턴한다 — 발행자 스레드가 느린
        // 세션 하나 때문에 묶이지 않는 게 이 메서드의 핵심 계약이다.
        for (Map.Entry<String, Entry> e : sessions.entrySet()) {
            String sessionId = e.getKey();
            Entry entry = e.getValue();
            try {
                entry.executor().execute(() -> send(groupId, sessionId, entry, message));
            } catch (java.util.concurrent.RejectedExecutionException ex) {
                // deregister()가 이미 shutdownNow()를 부른 직후의 경합 — 이미 정리된 세션이니 무시.
            }
        }
    }

    private void send(Long groupId, String sessionId, Entry entry, TextMessage message) {
        ConcurrentWebSocketSessionDecorator session = entry.session();
        if (!session.isOpen()) {
            removeEntry(groupId, sessionId, entry);
            return;
        }
        try {
            session.sendMessage(message);
        } catch (IOException e) {
            // 실제 끊김(피어 종료)과 데코레이터의 상한 초과(SessionLimitExceededException,
            // IOException의 하위 타입)를 굳이 구분하지 않는다 — 둘 다 "이 세션은 더 이상
            // 정상 전달을 기대할 수 없다"는 결론은 같다.
            log.warn("그룹 브로드캐스트 전송 실패, 세션을 정리한다 (groupId={})", groupId, e);
            removeEntry(groupId, sessionId, entry);
            closeQuietly(session);
        }
    }

    private void removeEntry(Long groupId, String sessionId, Entry entry) {
        Map<String, Entry> sessions = sessionsByGroup.get(groupId);
        if (sessions != null && sessions.remove(sessionId, entry) && sessions.isEmpty()) {
            sessionsByGroup.remove(groupId, sessions);
        }
        // executor 자기 자신 위에서 실행 중인 작업이 shutdownNow()를 부르는 것이라 현재
        // 작업을 인터럽트하지는 않는다 — 다음 작업부터 거부되고, 스레드는 곧 종료된다.
        entry.executor().shutdown();
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (IOException ignored) {
            // 이미 끊긴 세션 정리 중 실패는 무시한다 — 목적은 레지스트리 정리이지 이 close 자체가 아니다.
        }
    }
}
