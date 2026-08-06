package com.shadowfit.model.exercise;

/**
 * 세션 상태. {@code @Enumerated(EnumType.STRING)} 이라 <b>상수 이름이 그대로</b>
 * {@code exercise_sessions.status} ENUM 에 저장된다 — 이름과 스키마 ENUM 값은 같아야 한다.
 * {@code SchemaEnumConsistencyTest} 가 그 일치를 단언한다.
 *
 * <p>실제로 저장되는 값은 셋뿐이다: {@code IN_PROGRESS}(생성) · {@code COMPLETED} ·
 * {@code FAILED}(타임아웃). {@code CANCELLED} 는 <b>대입하는 코드가 없는 죽은 상태</b>다 —
 * 사용자 취소가 API 표면에 없기 때문이고, 이 상태를 남길지 없앨지는 아직 미결이다
 * ({@code docs/decisions/session-lifecycle-checklist.md} §"CANCELED 죽은 상태").
 */
public enum Status {
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    FAILED  // 네트워크 장애로 인한 타임아웃
}
