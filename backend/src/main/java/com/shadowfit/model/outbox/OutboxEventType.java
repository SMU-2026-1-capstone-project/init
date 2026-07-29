package com.shadowfit.model.outbox;

/**
 * 아웃박스가 나르는 통보 종류.
 *
 * <p>String 이 아니라 enum 인 이유: 이 값은 <b>발행기의 분기 대상</b>이다(타입마다 보낼 gRPC 호출이
 * 다르다). 오타가 나면 그 행은 아무도 처리하지 못한 채 재시도만 반복하다 FAILED 로 떨어지는데,
 * 컴파일 시점에 잡히지 않으면 운영에서야 발견된다. 닫힌 집합이므로 enum 이 맞다.
 *
 * <p>반면 {@code aggregate_type} 은 String 으로 둔다 — 분기에 쓰이지 않고 조회·디버깅용 라벨이라
 * 닫아둘 이유가 없다.
 */
public enum OutboxEventType {

    /**
     * 세션 종료 → AI 에 분석 중단 통보(gRPC {@code StopAnalysis}).
     * payload: {@code { "sessionId": 42 }}
     */
    STOP_ANALYSIS
}
