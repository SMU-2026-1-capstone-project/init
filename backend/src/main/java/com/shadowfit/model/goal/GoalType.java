package com.shadowfit.model.goal;

/**
 * 목표 종류 (BE-06). {@code TARGET_WEIGHT}·운동 종목별 목표는 스코프 제외
 * (goal-domain-design.md §3 — 세션 완료와 무관한 별도 트리거가 필요하거나 squat-first와 충돌).
 */
public enum GoalType {
    WEEKLY_SESSIONS,
    WEEKLY_MINUTES
}
