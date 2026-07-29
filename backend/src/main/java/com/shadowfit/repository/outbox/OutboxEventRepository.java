package com.shadowfit.repository.outbox;

import com.shadowfit.model.outbox.OutboxEvent;
import com.shadowfit.model.outbox.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 아웃박스 발행기용 조회·상태전이 쿼리.
 *
 * <p>[왜 네이티브 쿼리인가] {@code FOR UPDATE SKIP LOCKED} 는 JPQL 에 없다. 그리고 이 락은
 * <b>비관적 락을 걸되 이미 잠긴 행은 건너뛴다</b>는 의미라 {@code @Lock(PESSIMISTIC_WRITE)} 로
 * 대체되지 않는다 — 그건 다른 발행기를 <i>대기</i>시켜 직렬화해 버린다. 여러 발행기가 서로 다른 행을
 * 동시에 집게 하는 게 목적이므로 SKIP LOCKED 가 필수다.
 *
 * <p>[왜 두 갈래를 따로 조회하나] 폴링 조건이 두 개(신규·재시도 / 유실 회수)인데 이를 {@code OR}
 * 한 방으로 짜면 옵티마이저의 index_merge 에 맡기게 돼 EXPLAIN 이 흔들린다. 쿼리를 나눠 각자
 * {@code idx_outbox_dispatch}, {@code idx_outbox_stale} 를 타게 한다(schema.sql 주석과 같은 전제).
 *
 * <p>설계 근거: docs/decisions/outbox-reliable-messaging.md §4-3-1
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * ① 신규·재시도분 선점. 호출자는 <b>같은 트랜잭션 안에서</b> 곧바로
     * {@link #markProcessing}로 소유권을 넘기고 커밋해야 한다 — 락은 트랜잭션과 함께 풀리므로,
     * 상태를 안 바꾸고 트랜잭션을 끝내면 다른 발행기가 같은 행을 집는다.
     *
     * <p>{@code ORDER BY id} 는 대체로 등록 순서 전달을 의도한 것이지, 순서 보장이 아니다 —
     * 재시도 백오프가 걸린 행은 뒤로 밀리므로 전역 FIFO 는 성립하지 않는다.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING'
              AND (next_retry_at IS NULL OR next_retry_at <= :now)
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockPendingBatch(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * ② 유실 회수분 선점 — 선점 후 만료된 {@code PROCESSING}.
     *
     * <p>이 행들은 <b>송신이 실제로 나갔는지 알 수 없다</b>(발행기가 송신 도중 죽었을 수 있다).
     * 그래서 재전송은 중복을 만들 수 있으며, 이는 at-least-once 의 본질이라 수신측 멱등성이
     * 흡수한다.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PROCESSING'
              AND lock_expires_at <= :now
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockStaleProcessingBatch(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 선점한 행들의 소유권을 넘긴다. 회수분도 같은 메서드로 다시 선점한다(만료 시각 갱신). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE OutboxEvent e
               SET e.status = com.shadowfit.model.outbox.OutboxStatus.PROCESSING,
                   e.lockedBy = :lockedBy,
                   e.lockExpiresAt = :expiresAt
             WHERE e.id IN :ids
            """)
    int markProcessing(@Param("ids") Collection<Long> ids,
                       @Param("lockedBy") String lockedBy,
                       @Param("expiresAt") LocalDateTime expiresAt);

    /** 전달 성공. 선점 흔적을 지워 회수 대상에서 완전히 빠지게 한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE OutboxEvent e
               SET e.status = com.shadowfit.model.outbox.OutboxStatus.SENT,
                   e.sentAt = :sentAt,
                   e.lockedBy = NULL,
                   e.lockExpiresAt = NULL
             WHERE e.id = :id
            """)
    int markSent(@Param("id") Long id, @Param("sentAt") LocalDateTime sentAt);

    /** 재시도 예약 — {@code PENDING} 으로 되돌리고 백오프를 건다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE OutboxEvent e
               SET e.status = com.shadowfit.model.outbox.OutboxStatus.PENDING,
                   e.retryCount = e.retryCount + 1,
                   e.nextRetryAt = :nextRetryAt,
                   e.lockedBy = NULL,
                   e.lockExpiresAt = NULL
             WHERE e.id = :id
            """)
    int markForRetry(@Param("id") Long id, @Param("nextRetryAt") LocalDateTime nextRetryAt);

    /** 종료 상태의 실패(재시도 한도 초과 / AI 가 세션을 잃어 재시도가 무의미한 경우). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE OutboxEvent e
               SET e.status = com.shadowfit.model.outbox.OutboxStatus.FAILED,
                   e.lockedBy = NULL,
                   e.lockExpiresAt = NULL
             WHERE e.id = :id
            """)
    int markFailed(@Param("id") Long id);

    /**
     * 적체 감시 게이지용. 계수기가 없으면 "PENDING 이 조용히 쌓이는 것"을 아무도 모른다 —
     * outbox 의 대표적 실패 양상이다.
     */
    long countByStatus(OutboxStatus status);

    /** 보존 정책 정리용 — SENT 는 짧게, 터미널 FAILED 는 길게 두고 각각 다른 기준으로 호출한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.createdAt < :threshold")
    int deleteByStatusAndCreatedAtBefore(@Param("status") OutboxStatus status,
                                         @Param("threshold") LocalDateTime threshold);
}
