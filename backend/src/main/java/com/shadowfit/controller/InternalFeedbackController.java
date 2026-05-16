package com.shadowfit.controller;

import com.shadowfit.dto.exercises.feedback.FeedbackBatchRequestDto;
import com.shadowfit.service.Exercise.FeedbackLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "내부 API - 피드백 로그", description = "FastAPI가 세션 종료 시 발화 이벤트 배치 전송")
@RestController
@RequestMapping("/internal/feedback")
@RequiredArgsConstructor
public class InternalFeedbackController {
    private final FeedbackLogService feedbackLogService;

    @Value("${internal.api.token}")
    private String internalToken;

    @Operation(summary = "피드백 발화 이벤트 배치 저장",
               description = "운동 중 발화된 피드백을 세션 종료 시 한 번에 저장. 실시간 호출 금지.")
    @PostMapping("/batch")
    public ResponseEntity<String> receiveFeedbackBatch(
            @RequestHeader("X-Internal-Token") String token,
            @Valid @RequestBody FeedbackBatchRequestDto request
    ) {
        if (!internalToken.equals(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Forbidden: Invalid Internal Token");
        }
        int saved = feedbackLogService.saveBatch(request);
        return ResponseEntity.ok("Saved " + saved + " feedback events for session " + request.sessionId());
    }
}