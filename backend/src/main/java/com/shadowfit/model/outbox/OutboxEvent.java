package com.shadowfit.model.outbox;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 트랜잭셔널 아웃박스 — 보내야 할 통보를 도메인 변경과 <b>같은 트랜잭션</b>에 기록한 행.
 *
 * <p>{@code endSession} 은 "MySQL 커밋"과 "AI 에 gRPC 송신" 두 곳에 쓰는 dual-write 라, 두 번째
 * 쓰기가 실패하면(gRPC 오류 / 서킷 OPEN 스킵) 복구 수단이 없었다(at-most-once). 통보를 이 테이블에
 * INSERT 하면 도메인 커밋과 원자적으로 묶이고, 전달 책임은 별도 발행기가 진다(at-least-once).
 * 수신측 멱등성과 합쳐 <b>통보 전달</b>은 effectively exactly-once 가 된다.
 *
 * <p>⚠️ 보장 범위는 "통보의 전달"까지다. AI 프로세스가 재시작해 세션 상태를 잃으면 통보는 정확히
 * 전달되지만 분석 결과는 회수되지 않는다 — outbox 의 결함이 아니라 경계다.
 *
 * <p>설계 근거: docs/decisions/outbox-reliable-messaging.md
 *
 * <p>낙관적 락({@code @Version})을 두지 않는다 — 동시성 제어는 {@code SELECT ... FOR UPDATE
 * SKIP LOCKED} 로 행을 선점하고 {@link OutboxStatus#PROCESSING} 으로 소유권을 넘기는 방식이라
 * 버전 충돌로 감지할 경합 자체가 생기지 않는다.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 애그리거트 종류 라벨(현재 {@code "SESSION"}). 조회·디버깅용이라 enum 으로 닫지 않는다
     * ({@link OutboxEventType} 주석 참고).
     */
    @Column(nullable = false, length = 50)
    private String aggregateType;

    /**
     * 애그리거트 식별자(현재 session_id). <b>FK 를 걸지 않는다</b> — 걸면 outbox 가 특정 애그리거트에
     * 종속돼 다른 이벤트 타입으로 확장할 수 없고, 세션 삭제 시 CASCADE 로 통보 이력까지 사라진다.
     */
    @Column(nullable = false)
    private Long aggregateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private OutboxEventType eventType;

    /**
     * 통보 본문 JSON 문자열. 예: {@code {"sessionId":42}}
     *
     * <p>{@code columnDefinition = "JSON"} 을 쓰지 않는다 — 그러면 그 문자열이 <b>모든 방언의 생성
     * DDL 에 그대로</b> 박혀서, 엔티티로 스키마를 만드는 테스트(H2, {@code ddl-auto: create-drop})까지
     * 영향을 받는다. 운영은 {@code ddl-auto: none} 이라 실제 컬럼 타입은 schema.sql 의 {@code JSON}
     * 이 정하고, JDBC 는 이 String 을 그대로 바인딩·조회한다(MySQL 이 유효성 검사 후 캐스팅).
     * 여기서의 {@code length} 는 테스트 생성 DDL 에만 쓰인다.
     */
    @Column(nullable = false, length = 1000)
    private String payload;

    /**
     * 이 통보를 만든 원 요청의 correlation id.
     *
     * <p>발행기는 {@code @Scheduled} 스레드라 MDC 가 비어 있고, outbox 는 스레드가 아니라
     * <b>시간·프로세스 경계</b>를 넘으므로 런타임 캡처({@code CorrelationIds.wrap})로는 원리상 이을 수
     * 없다. 행에 저장해야 원 요청과 이어진다 — MDC 와 달리 이 값은 인스턴스 재시작을 견딘다.
     */
    @Column(length = 64)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    /** 지수 백오프 다음 시도 시각. {@code null} 이면 즉시 대상. */
    private LocalDateTime nextRetryAt;

    /** 이 행을 선점한 발행기 식별자(인스턴스 ID). 회수된 행을 사후에 추적할 때 쓴다. */
    @Column(length = 64)
    private String lockedBy;

    /** 선점 만료 시각. 이 시각이 지난 {@code PROCESSING} 은 송신 중 크래시로 보고 회수한다. */
    private LocalDateTime lockExpiresAt;

    private LocalDateTime sentAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 세션 종료 통보 한 건을 만든다 — 도메인 트랜잭션 안에서 호출된다. */
    public static OutboxEvent stopAnalysis(Long sessionId, String correlationId) {
        return OutboxEvent.builder()
                .aggregateType(AGGREGATE_TYPE_SESSION)
                .aggregateId(sessionId)
                .eventType(OutboxEventType.STOP_ANALYSIS)
                .payload("{\"sessionId\":" + sessionId + "}")
                .correlationId(correlationId)
                .build();
    }

    /**
     * 재부착 통보 한 건을 만든다 — 서킷브레이커 OPEN 감지 시(요청 트랜잭션 밖) 호출된다.
     * {@code stopAnalysis} 와 달리 도메인 커밋에 얹히지 않는다 — 이 행 자체가 "장애 감지"라는
     * 사건의 기록이다.
     */
    public static OutboxEvent reattachAnalysis(Long sessionId, String correlationId) {
        return OutboxEvent.builder()
                .aggregateType(AGGREGATE_TYPE_SESSION)
                .aggregateId(sessionId)
                .eventType(OutboxEventType.REATTACH_ANALYSIS)
                .payload("{\"sessionId\":" + sessionId + "}")
                .correlationId(correlationId)
                .build();
    }

    public static final String AGGREGATE_TYPE_SESSION = "SESSION";
}
