package com.shadowfit.service.Exercise;

import com.shadowfit.global.observability.CorrelationIds;
import com.shadowfit.global.observability.SessionMetrics;
import com.shadowfit.model.outbox.DispatchOutcome;
import com.shadowfit.model.outbox.OutboxEvent;
import com.shadowfit.model.outbox.OutboxStatus;
import com.shadowfit.repository.outbox.OutboxEventRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 아웃박스 발행기 — {@code PENDING} 행을 집어 AI 에 실제로 송신하고 결과를 행 상태로 되돌린다.
 *
 * <p>[전체 그림] {@code endSession} 은 세션 변경과 통보 행 INSERT 를 한 트랜잭션에 커밋하고 끝난다
 * (gRPC 없음). 전달 책임은 여기가 진다 — 실패하면 행이 남아 다음 tick 에 다시 시도되므로,
 * 인스턴스가 죽어도 통보가 증발하지 않는다(at-least-once).
 *
 * <p>[송신을 트랜잭션 밖에서 하는 이유] gRPC 는 최대 {@code GRPC_CALL_TIMEOUT_SECONDS} 만큼 걸린다.
 * 그 시간 동안 DB 트랜잭션을 열어두면 커넥션을 외부 I/O 시간만큼 점유한다. 그래서
 * <b>선점(트랜잭션) → 송신(트랜잭션 밖) → 결과 기록(트랜잭션)</b> 세 단계로 나눈다.
 *
 * <p>[그래서 소유권이 필요하다] 트랜잭션이 끝나면 행 락도 풀리므로, 송신 중인 행을 다른 발행기가
 * 집지 않게 하려면 락이 아니라 <b>상태</b>로 표시해야 한다 — 선점 시 {@link OutboxStatus#PROCESSING}
 * 으로 바꾸고 만료 시각을 박는다. 송신 도중 죽으면 만료 후 회수된다.
 * (docs/decisions/outbox-reliable-messaging.md §4-3-1)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository outboxRepository;
    private final ExerciseAnalysisService analysisService;
    private final SessionMetrics sessionMetrics;

    // 자기 주입 — 아래 @Transactional 메서드들이 Spring 프록시를 거치게 한다. 자기호출(this.)은
    // AOP 프록시를 우회해 @Transactional 이 조용히 무시된다(startAnalysis 에서 실제로 밟았던 함정).
    // 생성자 주입으로는 자기 자신을 못 받는다(순환) — @Lazy 필드 주입이어야 한다.
    @Lazy
    @Autowired
    private OutboxPublisher self;

    /** 한 tick 에 처리할 최대 건수. 순차 송신이라 이 값 × AI 응답시간이 tick 소요의 상한이다. */
    @Value("${outbox.publisher.batch-size:20}")
    private int batchSize;

    /**
     * 선점 유효 시간. gRPC 데드라인(5초)보다 넉넉해야 한다 — 아직 송신 중인 행을 다른 발행기가
     * "죽은 것"으로 보고 회수하면 불필요한 중복이 난다.
     */
    @Value("${outbox.publisher.lock-timeout-seconds:60}")
    private long lockTimeoutSeconds;

    /** 이 횟수를 넘기면 독 메시지로 보고 종료 상태로 보낸다. */
    @Value("${outbox.publisher.max-retry:10}")
    private int maxRetry;

    /** 백오프 상한. 서킷브레이커가 빠른 실패를 담당하므로 발행기는 느긋해도 된다. */
    @Value("${outbox.publisher.max-backoff-seconds:300}")
    private long maxBackoffSeconds;

    /** 어느 인스턴스가 선점했는지 — 회수된 행을 사후에 추적할 때 쓴다. */
    private final String publisherId = "pub-" + UUID.randomUUID().toString().substring(0, 8);

    @PostConstruct
    void registerGauge() {
        sessionMetrics.registerOutboxPendingGauge(() -> outboxRepository.countByStatus(OutboxStatus.PENDING));
    }

    /**
     * 종료 훅 — 이 인스턴스가 들고 있던 lease 를 반납한다 (#208 조치 후보 2, 2026-08-27 채택).
     *
     * <p>graceful shutdown(server.shutdown: graceful)이 켜져 있어도 그건 <b>웹 요청</b>만
     * 보호한다 — {@code @Scheduled} 작업은 별도 설정
     * ({@code spring.task.scheduling.shutdown.await-termination})이 없으면 여기 안 걸린다.
     * 즉 {@link #dispatchPending} 이 tick 중간(배치 안 다른 행을 아직 못 보낸 상태)에
     * 컨텍스트 종료를 맞을 수 있고, 그 행들은 이 훅이 없으면 lease 만료(기본 60초)까지
     * 아무도 못 건드린다 — #208 이 실측으로 확인한 지연이 정확히 이 자리다.
     *
     * <p>SIGKILL(예: {@code docker kill})처럼 훅 자체가 안 도는 죽음에는 이 메서드가
     * 안 불린다 — 그때는 기존 lease 만료 경로가 그대로 안전망이다. 이 훅은 그 경로를
     * 대체하지 않고, "정상 종료인데도 60초를 그냥 흘리는" 낭비만 없앤다.
     */
    @PreDestroy
    void releaseLeaseOnShutdown() {
        int released = self.releaseOwnedLeases(publisherId);
        if (released > 0) {
            log.info("종료 — 보유 lease {}건 반납(PENDING 복귀), publisherId={}", released, publisherId);
        }
    }

    /**
     * 폴링 tick. 간격이 곧 통보 지연의 하한이라 짧게 잡는다 — 사용자가 이 통보를 기다리며
     * 블록되지는 않지만, 결과 회수가 늦어질 이유도 없다.
     */
    @Scheduled(fixedDelayString = "${outbox.publisher.poll-interval-ms:1000}",
               initialDelayString = "${outbox.publisher.initial-delay-ms:10000}")
    public void dispatchPending() {
        // 스케줄러는 물려받을 요청이 없어 tick 1회를 하나의 흐름으로 보고 cid 를 스스로 발급한다
        // (SessionTimeoutScheduler 가 확립한 패턴). 행별 cid 는 아래에서 원 요청 것으로 덮어쓴다.
        try (CorrelationIds.Scope tick = CorrelationIds.startTask("outbox-dispatch")) {
            try {
                // 회수분을 먼저 — 이미 한 번 실패(또는 크래시)한 건이라 더 오래 기다린 쪽이다.
                //
                // 두 번째 인자는 «이 행은 이미 한 번 나갔을 수 있다» 는 뜻이다(이슈 #152). 회수분은
                // 이전 발행기가 송신 «도중» 죽었을 수 있어 중복 배달이 될 수 있는데, 그 사실이
                // 지금까지 수신 결과를 해석하는 쪽에 전달되지 않았다.
                dispatchBatch(self.claimStale(), true);
                dispatchBatch(self.claimPending(), false);
            } catch (Exception e) {
                // tick 하나가 죽어도 다음 tick 은 돌아야 한다. 여기서 안 잡으면 스케줄러가
                // 해당 작업을 영구 중단시킨다.
                log.error("아웃박스 발행 tick 실패", e);
            }
        }
    }

    private void dispatchBatch(List<OutboxEvent> claimed, boolean possiblyRedelivered) {
        for (OutboxEvent event : claimed) {
            // 행에 적힌 cid 로 복원 — MDC 는 스레드에 매달려 죽지만 DB 에 적힌 cid 는 재시작을 견딘다.
            try (CorrelationIds.Scope perRow = CorrelationIds.withCorrelationId(event.getCorrelationId());
                 CorrelationIds.Scope session = CorrelationIds.withSession(event.getAggregateId())) {
                dispatchOne(event, possiblyRedelivered);
            } catch (Exception e) {
                // 한 건의 실패가 배치 전체를 멈추면 안 된다. 상태를 못 바꾸고 빠져도 행은
                // PROCESSING 으로 남아 lock 만료 후 회수되므로 유실되지 않는다.
                log.error("아웃박스 행 처리 실패 - id: {}", event.getId(), e);
            }
        }
    }

    /** 송신은 트랜잭션 <b>밖</b>에서, 결과 기록만 짧은 트랜잭션으로. */
    private void dispatchOne(OutboxEvent event, boolean possiblyRedelivered) {
        DispatchOutcome outcome = switch (event.getEventType()) {
            case STOP_ANALYSIS -> analysisService.stopAnalysis(event.getAggregateId(), possiblyRedelivered);
        };

        switch (outcome) {
            case SENT -> {
                LocalDateTime now = LocalDateTime.now();
                if (!owned(self.recordSent(event.getId(), publisherId, now), event)) {
                    return;
                }
                sessionMetrics.outboxDispatch("sent");
                if (event.getCreatedAt() != null) {
                    sessionMetrics.outboxLag(Duration.between(event.getCreatedAt(), now));
                }
            }
            case TERMINAL_FAILED -> {
                // 재시도가 원리상 무의미한 실패 — 한도와 무관하게 즉시 종료 상태로 보낸다.
                if (!owned(self.recordFailed(event.getId(), publisherId), event)) {
                    return;
                }
                sessionMetrics.outboxDispatch("failed");
                log.warn("아웃박스 전달 종료(재시도 무의미) - id: {}, sessionId: {}",
                        event.getId(), event.getAggregateId());
            }
            case RETRY -> {
                int attempts = event.getRetryCount() + 1;
                if (attempts > maxRetry) {
                    if (!owned(self.recordFailed(event.getId(), publisherId), event)) {
                        return;
                    }
                    sessionMetrics.outboxDispatch("failed");
                    log.error("아웃박스 재시도 한도 초과 — 독 메시지로 종료 (id: {}, sessionId: {}, 시도: {})",
                            event.getId(), event.getAggregateId(), attempts);
                    return;
                }
                LocalDateTime nextAt = LocalDateTime.now().plusSeconds(backoffSeconds(attempts));
                if (!owned(self.recordRetry(event.getId(), publisherId, nextAt), event)) {
                    return;
                }
                sessionMetrics.outboxDispatch("retry");
            }
        }
    }

    /**
     * 상태 전이가 실제로 반영됐는지 — 0 행이면 <b>lease 가 만료돼 다른 발행기가 이 행을 회수해 간
     * 것</b>이다. 그 경우 우리 결과를 쓰면 새 소유자의 진행을 덮어쓰므로 조용히 물러난다.
     *
     * <p>지표도 올리지 않는다. 우리가 보낸 건 사실이지만 그 행의 결말은 새 소유자가 정하므로,
     * 여기서 세면 같은 행이 두 번 집계된다.
     */
    private boolean owned(int updated, OutboxEvent event) {
        if (updated > 0) {
            return true;
        }
        sessionMetrics.outboxDispatch("lease-lost");
        log.warn("선점을 잃은 뒤 결과를 기록하려 함 — 다른 발행기가 회수했다 (id: {}, sessionId: {}). "
                + "lease({}s)가 gRPC 데드라인 대비 너무 짧지 않은지 확인 필요",
                event.getId(), event.getAggregateId(), lockTimeoutSeconds);
        return false;
    }

    /** 지수 백오프 1s → 2s → 4s … 상한까지. {@code 1L << n} 이 넘치지 않도록 지수를 먼저 자른다. */
    private long backoffSeconds(int attempts) {
        int exponent = Math.min(attempts - 1, 20);
        return Math.min(1L << exponent, maxBackoffSeconds);
    }

    // ---------------------------------------------------------------------
    // 트랜잭션 경계 — self 를 거쳐 호출해야 프록시를 타고 @Transactional 이 적용된다.
    // (자기호출은 AOP 프록시를 우회해 조용히 무시된다 — startAnalysis 에서 한 번 밟은 함정)
    // ---------------------------------------------------------------------

    /**
     * 신규·재시도분 선점. 조회와 상태 전이가 <b>한 트랜잭션</b>이어야 한다 — SKIP LOCKED 로 잡은
     * 락은 트랜잭션과 함께 풀리므로, 상태를 안 바꾸고 커밋하면 다른 발행기가 같은 행을 집는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimPending() {
        return claim(outboxRepository.lockPendingBatch(LocalDateTime.now(), batchSize));
    }

    /** 선점 후 만료된 {@code PROCESSING} 회수 — 송신 도중 죽은 발행기가 남긴 행이다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimStale() {
        List<OutboxEvent> stale = outboxRepository.lockStaleProcessingBatch(LocalDateTime.now(), batchSize);
        if (!stale.isEmpty()) {
            log.warn("만료된 선점 행 회수 - {}건 (이전 발행기가 송신 중 종료됐을 수 있음)", stale.size());
        }
        return claim(stale);
    }

    private List<OutboxEvent> claim(List<OutboxEvent> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        outboxRepository.markProcessing(
                rows.stream().map(OutboxEvent::getId).toList(),
                publisherId,
                LocalDateTime.now().plusSeconds(lockTimeoutSeconds));
        return rows;
    }

    /** @return 갱신된 행 수. 0 이면 소유권을 잃은 것({@link #owned} 참고). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordSent(Long id, String lockedBy, LocalDateTime sentAt) {
        return outboxRepository.markSent(id, lockedBy, sentAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordRetry(Long id, String lockedBy, LocalDateTime nextRetryAt) {
        return outboxRepository.markForRetry(id, lockedBy, nextRetryAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordFailed(Long id, String lockedBy) {
        return outboxRepository.markFailed(id, lockedBy);
    }

    /** @return 반납된 행 수. {@link #releaseLeaseOnShutdown} 전용 — self 를 거쳐 트랜잭션을 태운다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int releaseOwnedLeases(String lockedBy) {
        return outboxRepository.releaseOwnedLeases(lockedBy);
    }
}
