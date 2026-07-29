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
 * <p>[왜 두 갈래를 따로 조회하나] 폴링 조건이 둘(신규·재시도 / 유실 회수)이고 성격이 다르다.
 * {@code OR} 한 방으로 합치면 옵티마이저의 index_merge 에 맡기게 돼 실행계획이 흔들린다.
 *
 * <p>[인덱스는 하나뿐이다] 처음엔 두 쿼리에 각각 인덱스를 두려 했으나, 실측 결과 <b>둘 다 선두
 * 컬럼이 {@code status} 라 옵티마이저가 구분하지 못하고 아무거나 골라 양쪽 다 status 프리픽스만
 * 쓰는</b> 역효과가 났다(key_len 1, filtered 33~40%). 회수용 인덱스를 지우자 신규·재시도 쿼리가
 * 정상화됐다(key_len 7, filtered 100%). 그래서 {@link #lockStaleProcessingBatch} 는 인덱스를
 * 제대로 타지 못하는데, {@code PROCESSING} 행이 구조적으로 수십 건을 넘지 않아 감수한다.
 * 자세한 근거는 schema.sql 의 {@code outbox_events} 주석.
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

    /**
     * 전달 성공. 선점 흔적을 지워 회수 대상에서 완전히 빠지게 한다.
     *
     * <p>[왜 {@code lockedBy} 를 조건에 거나] 소유권을 잃은 발행기가 뒤늦게 쓰는 것을 막는다.
     * GC 스톱 등으로 lease 만 만료되면 다른 발행기가 이 행을 회수해 다시 보내는 중일 수 있는데,
     * 그때 원래 발행기가 깨어나 상태를 덮으면 <b>새 소유자의 진행을 망가뜨린다</b>(예: 아직 송신
     * 중인 행을 PENDING 으로 되돌려 세 번째 발행기가 또 집게 만든다). 조건이 안 맞으면 0 을
     * 돌려주므로 호출자가 "소유권을 잃었다"를 알 수 있다 — 조건부 갱신(CAS)에 의한 경량 펜싱.
     *
     * <p>중복 <b>송신</b> 자체를 막지는 못한다(그건 이미 나간 뒤다). 막는 것은 <b>상태 오염</b>이다.
     * 중복 송신은 수신측 멱등성이 흡수한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE OutboxEvent e
               SET e.status = com.shadowfit.model.outbox.OutboxStatus.SENT,
                   e.sentAt = :sentAt,
                   e.lockedBy = NULL,
                   e.lockExpiresAt = NULL
             WHERE e.id = :id
               AND e.lockedBy = :lockedBy
               AND e.status = com.shadowfit.model.outbox.OutboxStatus.PROCESSING
            """)
    int markSent(@Param("id") Long id, @Param("lockedBy") String lockedBy,
                 @Param("sentAt") LocalDateTime sentAt);

    /** 재시도 예약 — {@code PENDING} 으로 되돌리고 백오프를 건다. 소유권 조건은 {@link #markSent} 와 동일. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE OutboxEvent e
               SET e.status = com.shadowfit.model.outbox.OutboxStatus.PENDING,
                   e.retryCount = e.retryCount + 1,
                   e.nextRetryAt = :nextRetryAt,
                   e.lockedBy = NULL,
                   e.lockExpiresAt = NULL
             WHERE e.id = :id
               AND e.lockedBy = :lockedBy
               AND e.status = com.shadowfit.model.outbox.OutboxStatus.PROCESSING
            """)
    int markForRetry(@Param("id") Long id, @Param("lockedBy") String lockedBy,
                     @Param("nextRetryAt") LocalDateTime nextRetryAt);

    /** 종료 상태의 실패(재시도 한도 초과 / AI 가 세션을 잃어 재시도가 무의미한 경우). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE OutboxEvent e
               SET e.status = com.shadowfit.model.outbox.OutboxStatus.FAILED,
                   e.lockedBy = NULL,
                   e.lockExpiresAt = NULL
             WHERE e.id = :id
               AND e.lockedBy = :lockedBy
               AND e.status = com.shadowfit.model.outbox.OutboxStatus.PROCESSING
            """)
    int markFailed(@Param("id") Long id, @Param("lockedBy") String lockedBy);

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
