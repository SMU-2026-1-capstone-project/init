package com.shadowfit.service.coaching;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * "이 사용자를 보고 있는 트레이너 연결들" 을 담는 프로세스 로컬 레지스트리
 * ({@code trainer-live-monitoring.md} §8 세션3).
 *
 * <p>키는 {@code userId} 하나다(2026-08-30 확정) — 사용자당 담당 트레이너가 유니크 제약으로
 * 1명뿐이라 트레이너를 키에 더할 필요가 없다. 값이 리스트인 이유는 **같은 트레이너의 다중
 * 디바이스 동시 접속을 허용**하기로 했기 때문(2026-08-30 확정, 재연결 시 기존 연결을 끊지
 * 않음) — 트레이너가 폰·PC에서 동시에 같은 사용자를 볼 수 있다.
 *
 * <p>인스턴스가 여럿이 되면(현재는 해당 없음, 1:1이라 스티키가 필요 없다는 게
 * {@code multiuser-realtime-sync.md}와의 비교점) 이 레지스트리도 인스턴스별로 갈라져 다른
 * 인스턴스에 붙은 연결은 못 찾는다 — 지금 규모(DAU 1,000)에서는 해당하지 않는 제약이라
 * 여기서는 다루지 않는다.
 */
@Slf4j
@Component
public class TrainerConnectionRegistry {

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> connectionsByUserId = new ConcurrentHashMap<>();

    public void register(Long userId, SseEmitter emitter) {
        connectionsByUserId.computeIfAbsent(userId, id -> new CopyOnWriteArrayList<>()).add(emitter);
    }

    public void remove(Long userId, SseEmitter emitter) {
        connectionsByUserId.computeIfPresent(userId, (id, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }

    public List<SseEmitter> getConnections(Long userId) {
        return connectionsByUserId.getOrDefault(userId, new CopyOnWriteArrayList<>());
    }

    /**
     * {@code userId}를 보고 있는 모든 트레이너 연결에 이벤트를 보낸다 (세션4).
     *
     * <p>실패는 이 메서드 밖으로 절대 나가지 않는다 — 호출자(PoseDataService)는 저장이 이미
     * 끝난 뒤 커밋 후 훅에서 이 메서드를 부르므로, 트레이너 화면 갱신이 실패해도 저장된 데이터엔
     * 영향이 없어야 한다. 실패한 연결은 죽은 것으로 보고 레지스트리에서 제거한다(백프레셔 정책:
     * 큐잉 없이 실패 시 드롭, §8 세션5).
     */
    public void broadcast(Long userId, String eventName, Object payload) {
        List<SseEmitter> emitters = connectionsByUserId.get(userId);
        if (emitters == null || emitters.isEmpty()) return;

        for (SseEmitter emitter : emitters) {
            sendSafely(userId, emitter, SseEmitter.event().name(eventName).data(payload));
        }
    }

    /**
     * 모든 연결에 주기적으로 빈 하트비트를 보낸다 (§8 세션5). half-open TCP 좀비 — 클라이언트가
     * FIN 없이 사라져 이 프로세스는 연결이 살아있다고 믿는 상태 — 는 다음 rep 이 그 사용자에게
     * 나올 때까지 {@link #broadcast} 로도 못 잡는다. 그 사용자가 한동안 운동을 안 하면(트레이너가
     * 화면만 켜두고 대기) 죽은 연결이 무기한 레지스트리에 남을 수 있어, broadcast 를 기다리지
     * 않고 능동적으로 찔러본다.
     *
     * <p>{@code .comment(...)} 만 쓰고 {@code data}/{@code name} 은 안 준다 — SSE 주석 줄(:로
     * 시작)은 스펙상 파서가 무시하므로, 아직 없는 프론트 EventSource 리스너가 이 프레임 때문에
     * "heartbeat" 라는 이벤트 타입을 미리 알아야 할 필요가 없다. 순수 전송계층 keep-alive.
     *
     * <p>주기(기본 30초)는 실측이 아니라 SSE 하트비트 업계 관행값이다 — 이 프로젝트엔 아직
     * 리버스 프록시가 없어 "프록시 idle timeout 을 피한다"는 흔한 근거가 성립하지 않는다
     * (reverse-proxy-and-tls.md, 미결정 상태). {@code coaching.trainer-stream.heartbeat-interval-seconds}
     * 로 뺀 이유도 그래서다 — 프록시가 정해지거나 §7~8 캐파시티 실측이 나오면 다시 잡을 값.
     */
    @Scheduled(fixedRateString = "${coaching.trainer-stream.heartbeat-interval-seconds:30}s")
    public void heartbeat() {
        for (Map.Entry<Long, CopyOnWriteArrayList<SseEmitter>> entry : connectionsByUserId.entrySet()) {
            Long userId = entry.getKey();
            for (SseEmitter emitter : entry.getValue()) {
                sendSafely(userId, emitter, SseEmitter.event().comment("heartbeat"));
            }
        }
    }

    /**
     * 실패는 밖으로 던지지 않고 죽은 연결로 보고 제거한다 — {@link #broadcast} 와
     * {@link #heartbeat} 가 공유하는 유일한 전송 경로다.
     *
     * <p>{@code synchronized(emitter)} 인 이유: {@code SseEmitter.send} 는 스레드 안전하지
     * 않다(Spring 문서) — heartbeat 는 스케줄러 스레드에서, broadcast 는 PoseDataService 의
     * 커밋 후 훅(대개 gRPC 핸들러 스레드)에서 부르므로, 같은 사용자의 rep 완성이 하필 하트비트
     * 틱과 겹치면 같은 emitter 에 서로 다른 스레드가 동시에 쓸 수 있다 — 그대로 두면 SSE 스트림이
     * 인터리빙되어 깨진다.
     */
    private void sendSafely(Long userId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            synchronized (emitter) {
                emitter.send(event);
            }
        } catch (Exception e) {
            log.warn("트레이너 SSE 전송 실패, 연결 제거: userId={}", userId, e);
            remove(userId, emitter);
            emitter.completeWithError(e);
        }
    }
}
