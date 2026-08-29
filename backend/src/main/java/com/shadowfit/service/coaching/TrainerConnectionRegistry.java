package com.shadowfit.service.coaching;

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

    /** 세션4(AI 콜백 중계)가 쓸 조회 — 지금은 아무도 안 부른다. */
    public List<SseEmitter> getConnections(Long userId) {
        return connectionsByUserId.getOrDefault(userId, new CopyOnWriteArrayList<>());
    }
}
