package com.shadowfit.dto.exercises.feedback;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * AI 가 세트 경계마다 batch 송신 (BT-SET, 분기 2.A.BT).
 * snake_case payload: {session_id, set_no, is_final, events:[...]}
 * setNo / isFinal 은 nullable — AI 가 Phase 1 BT-NONE 으로 보낼 때 호환 (갱신 13·14).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FeedbackBatchRequestDto(
        @NotNull Long sessionId,
        Integer setNo,
        Boolean isFinal,
        @NotEmpty @Valid List<FeedbackEventDto> events
) {
}