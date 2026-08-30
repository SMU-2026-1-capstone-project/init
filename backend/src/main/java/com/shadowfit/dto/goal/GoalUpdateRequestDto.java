package com.shadowfit.dto.goal;

import jakarta.validation.constraints.Positive;

/** goalType은 수정 대상이 아니다 — 종류를 바꾸려면 삭제 후 새로 만든다(다른 리소스나 다름없음). */
public record GoalUpdateRequestDto(
        @Positive(message = "목표값은 0보다 커야 합니다.")
        int targetValue
) {
}
