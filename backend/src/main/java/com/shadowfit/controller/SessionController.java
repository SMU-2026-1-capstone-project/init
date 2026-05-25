package com.shadowfit.controller;

import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.service.Exercise.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 운동 세션 라이프사이클 (분기 2.A.ET ET-A).
 * 클라는 종료 시 본 endpoint + AI 측 종료 신호 양쪽 호출. 부분 실패 허용.
 */
@Tag(name = "운동 세션", description = "세션 라이프사이클 (시작은 운동 시작 API 에서, 종료는 본 controller)")
@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
public class SessionController {
    private final SessionService sessionService;

    @Operation(summary = "세션 종료 (사용자 명시 / 목표 달성 자동)",
               description = "클라가 운동 종료 시 호출 → endTime 기록. 본인 세션 아니면 403, 이미 종료된 세션 재호출은 멱등 (200 OK).")
    @PatchMapping("/{sessionId}/end")
    public ResponseEntity<Void> endSession(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        sessionService.endSession(sessionId, userDetails.getMember().getId());
        return ResponseEntity.ok().build();
    }
}
