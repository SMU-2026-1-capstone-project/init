package com.shadowfit.global.observability;

import com.shadowfit.global.config.AsyncConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관측성에서 가장 깨지기 쉬운 지점 — MDC는 ThreadLocal이라 {@code @Async} 경계를 그냥은 못 넘는다.
 *
 * <p>테스트가 재구현이 아니라 <b>실제 프로덕션 배선</b>({@link AsyncConfig#applicationTaskExecutor}이
 * 정의하는 빈)을 그대로 적용해서 검증한다. 이 테스트가 깨지면 비동기 로그에서 correlation id가
 * 통째로 사라진다.
 */
@DisplayName("@Async MDC 전파 테스트")
class AsyncMdcPropagationTest {

    private ThreadPoolTaskExecutor executor;

    private ThreadPoolTaskExecutor newDecoratedExecutor() {
        // 풀 크기 1·1 — 스레드 재사용을 강제해 MDC 누수까지 검증
        ThreadPoolTaskExecutorBuilder builder = new ThreadPoolTaskExecutorBuilder()
                .corePoolSize(1)
                .maxPoolSize(1);
        ThreadPoolTaskExecutor created = (ThreadPoolTaskExecutor) new AsyncConfig().applicationTaskExecutor(builder);
        created.initialize();
        return created;
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("제출 시점의 correlation id가 워커 스레드까지 따라간다")
    void propagatesCorrelationIdToWorkerThread() throws Exception {
        executor = newDecoratedExecutor();
        AtomicReference<String> seenInWorker = new AtomicReference<>();
        AtomicReference<String> workerThreadName = new AtomicReference<>();
        String callerThreadName = Thread.currentThread().getName();

        try (CorrelationIds.Scope ignored = CorrelationIds.withCorrelationId("cid-from-caller")) {
            Future<?> done = executor.submit(() -> {
                seenInWorker.set(CorrelationIds.current());
                workerThreadName.set(Thread.currentThread().getName());
            });
            done.get(5, TimeUnit.SECONDS);
        }

        assertThat(seenInWorker.get()).isEqualTo("cid-from-caller");
        // 진짜로 다른 스레드였는지 확인 — 같은 스레드였다면 이 테스트는 아무것도 증명하지 못한다
        assertThat(workerThreadName.get()).isNotEqualTo(callerThreadName);
    }

    @Test
    @DisplayName("작업이 끝나면 워커 스레드 MDC가 원복돼 다음 작업에 새어나가지 않는다")
    void doesNotLeakIntoNextTaskOnReusedThread() throws Exception {
        executor = newDecoratedExecutor();
        AtomicReference<String> seenInSecondTask = new AtomicReference<>();

        try (CorrelationIds.Scope ignored = CorrelationIds.withCorrelationId("first-request")) {
            executor.submit(() -> {
            }).get(5, TimeUnit.SECONDS);
        }

        // MDC 없이 제출된 두 번째 작업 — 풀 크기가 1이라 같은 스레드가 재사용된다
        CountDownLatch finished = new CountDownLatch(1);
        executor.submit(() -> {
            seenInSecondTask.set(CorrelationIds.current());
            finished.countDown();
        });
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(seenInSecondTask.get()).isNull();
    }

    @Test
    @DisplayName("세션 id도 함께 전파된다 — 비동기 분석 요청 로그가 세션을 잃지 않아야 함")
    void propagatesSessionId() throws Exception {
        executor = newDecoratedExecutor();
        AtomicReference<String> seenInWorker = new AtomicReference<>();

        try (CorrelationIds.Scope ignored = CorrelationIds.withSession(5821L)) {
            executor.submit(() -> seenInWorker.set(MDC.get(CorrelationIds.SESSION_MDC_KEY)))
                    .get(5, TimeUnit.SECONDS);
        }

        assertThat(seenInWorker.get()).isEqualTo("5821");
    }
}
