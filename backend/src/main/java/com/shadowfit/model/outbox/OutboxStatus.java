package com.shadowfit.model.outbox;

/**
 * 아웃박스 행의 전달 상태.
 *
 * <p>{@code PROCESSING} 이 있는 이유: {@code SELECT ... FOR UPDATE SKIP LOCKED} 만으로는 중복 송신이
 * 막히지 않는다. 행 락은 <b>트랜잭션 수명</b>만큼인데 실제 gRPC 송신은 그 트랜잭션 <b>밖에서</b> 일어나므로,
 * 송신 도중 발행기가 죽으면 행은 {@code PENDING} 인 채 남아 다른 발행기가 또 집는다. 그래서 소유권을
 * "상태 + 만료 시각"으로 표현한다 — SKIP LOCKED 는 작업 <i>분배</i>, 중복 방지는 이 상태가 담당한다.
 * (docs/decisions/outbox-reliable-messaging.md §4-3-1)
 */
public enum OutboxStatus {

    /** 전달 대기. 신규 INSERT 및 재시도 대상. */
    PENDING,

    /**
     * 발행기가 선점해 송신 중. {@code lock_expires_at} 이 지나면 회수 대상이 된다
     * (= 송신 중 크래시한 발행기의 행).
     */
    PROCESSING,

    /** 전달 성공(응답 {@code success=true}). 종료 상태. */
    SENT,

    /**
     * 종료 상태의 실패. 두 경로로 도달한다:
     * <ul>
     *   <li>재시도 한도 초과(독 메시지)</li>
     *   <li>AI 가 {@code success=false} 를 반환 — 그 세션 상태를 잃어 재시도가 원리상 무의미</li>
     * </ul>
     * <b>SENT 로 뭉뚱그리지 않는다.</b> 실제 결과 유실을 "전송 성공"으로 위장하게 되기 때문.
     */
    FAILED
}
