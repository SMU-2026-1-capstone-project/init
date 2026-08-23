package com.shadowfit.global.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 고정 창(fixed window) 카운터. 키별로 «이 창에서 몇 번»을 세고, 창이 지나면 알아서 사라진다.
 *
 * <p><b>왜 Caffeine 인가.</b> 이미 의존성에 있다({@code spring.cache.type: caffeine}).
 * Bucket4j 를 새로 들이는 것보다, 만료를 캐시에 맡기고 값만 세는 쪽이 작다 —
 * {@code expireAfterWrite} 가 곧 창 길이라 «창을 비우는 스케줄러»가 필요 없다.
 *
 * <p>🔴 <b>고정 창의 알려진 한계 — 경계에서 최대 2배가 통과한다.</b> 창이 바뀌는 순간에
 * 몰아치면 {@code limit} 을 두 창에 걸쳐 연속으로 쓸 수 있다(창 끝에 limit, 창 시작에 limit).
 * 슬라이딩 창이면 안 그렇지만 상태를 더 들고 있어야 한다. <b>여기서는 감수한다</b> —
 * 이 장치의 목적은 «정확한 요금 부과»가 아니라 «무제한을 유한으로 바꾸는 것»이고,
 * 2배도 여전히 유한이다.
 *
 * <p>🔴 <b>인스턴스 로컬이다.</b> 백엔드가 여러 대가 되면 각자 세므로 실질 한도가 인스턴스
 * 수만큼 곱해진다. 지금은 1대라({@code docker-compose.prod.yml}) 문제가 아니고, 여러 대가
 * 되는 날 필요한 것은 이 클래스의 수정이 아니라 <b>공유 저장소</b>다.
 */
public final class FixedWindowCounter {

    private final Cache<String, AtomicInteger> windows;
    private final int windowSeconds;

    public FixedWindowCounter(int windowSeconds, long maxKeys) {
        this.windowSeconds = windowSeconds;
        this.windows = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(windowSeconds))
                .maximumSize(maxKeys)
                .build();
    }

    /** 이 키의 현재 창 카운트를 1 올리고 <b>올린 뒤의 값</b>을 돌려준다. */
    public int increment(String key) {
        return windows.get(key, k -> new AtomicInteger()).incrementAndGet();
    }

    /**
     * 한도에 여유가 있을 때만 <b>원자적으로</b> 한 칸을 잡는다. 잡았으면 {@code true}.
     *
     * <p>🔴 <b>{@code current() } 로 보고 나중에 {@code increment()} 하는 것과 다르다.</b>
     * 그 둘 사이에 다른 스레드가 끼어들 수 있어서, 한도가 3이고 현재 2일 때 동시에 도착한
     * 요청이 <b>전부 검사를 통과</b>한다 — 한 배치의 동시성만큼 한도가 초과된다. 로그인
     * 브루트포스는 정확히 그 형태로 오므로, 검사와 예약이 갈라지면 이 장치가 반쪽이 된다.
     * (CodeRabbit 지적, PR #423)
     *
     * <p>CAS 루프인 이유: {@code incrementAndGet} 후 초과면 되돌리는 방식은 되돌리기 전에
     * 다른 요청이 그 값을 보고 거절당한다 — 한도가 잠깐 낮아진다.
     */
    public boolean tryAcquire(String key, int limit) {
        AtomicInteger counter = windows.get(key, k -> new AtomicInteger());
        while (true) {
            int now = counter.get();
            if (now >= limit) {
                return false;
            }
            if (counter.compareAndSet(now, now + 1)) {
                return true;
            }
        }
    }

    /**
     * 잡아둔 한 칸을 되돌린다. 0 아래로는 안 내려간다.
     *
     * <p>«이 시도는 셀 대상이 아니었다» 가 밝혀졌을 때 쓴다 — 예컨대 DB 장애로 로그인이
     * 실패한 경우. 그걸 실패로 세면 <b>인프라 흔들림이 사용자 한도를 갉아먹는다.</b>
     */
    public void release(String key) {
        AtomicInteger counter = windows.getIfPresent(key);
        if (counter != null) {
            counter.updateAndGet(v -> v > 0 ? v - 1 : 0);
        }
    }

    /** 올리지 않고 현재 값만 본다. 창이 지났으면 0. */
    public int current(String key) {
        AtomicInteger counter = windows.getIfPresent(key);
        return counter == null ? 0 : counter.get();
    }

    /** 이 키의 창을 즉시 비운다 (로그인 성공 등 «정상이었다»가 밝혀졌을 때). */
    public void reset(String key) {
        windows.invalidate(key);
    }

    /**
     * 클라이언트에게 알려줄 대기 시간(초).
     *
     * <p>⚠️ <b>정확한 잔여가 아니라 상한이다.</b> 창이 언제 시작됐는지를 되묻지 않고 창 길이를
     * 그대로 준다. 틀리는 방향이 «필요보다 오래 기다린다»(안전)이지 «너무 일찍 재시도한다»가
     * 아니라서 이렇게 둔다.
     */
    public int retryAfterSeconds() {
        return windowSeconds;
    }
}
