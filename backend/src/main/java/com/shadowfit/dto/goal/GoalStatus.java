package com.shadowfit.dto.goal;

/**
 * FAILED가 없다 — rolling window(기간 마감 개념 없음) 채택으로 "실패 확정"이라는 사건 자체가
 * 사라졌다(goal-domain-design.md §5). 매 조회 시점의 스냅샷일 뿐이라 언제든 ACHIEVED로 바뀔 수
 * 있다.
 */
public enum GoalStatus {
    IN_PROGRESS,
    ACHIEVED
}
