package com.shadowfit.controller;

import com.shadowfit.dto.exercises.session.ActiveSessionResponseDto;
import com.shadowfit.dto.exercises.session.ReattachSessionResponseDto;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.service.Exercise.ExerciseAnalysisService;
import com.shadowfit.service.Exercise.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 운동 세션 라이프사이클 (분기 2.A.ET ET-H, 단일 endpoint 분배자 패턴).
 * 클라는 종료 시 본 endpoint **한 번만** 호출. Spring 이 endTime 기록 + afterCommit 으로 AI 에 gRPC StopAnalysis 송신.
 */
@Tag(name = "운동 세션", description = "세션 라이프사이클 (시작은 운동 시작 API 에서, 종료는 본 controller)")
@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
public class SessionController {
    private final SessionService sessionService;
    // 재부착은 gRPC 송신이라 분석 서비스 몫 — SessionService 는 순환 의존 때문에 gRPC 의존을 갖지 않는다.
    private final ExerciseAnalysisService exerciseAnalysisService;

    @Operation(summary = "진행 중인 세션 조회",
               description = "이 회원의 IN_PROGRESS 세션을 반환. 클라가 앱 재시작 후 sessionId를 복원하는 경로 — "
                       + "없으면 204 No Content(정상 상태이지 오류가 아니므로 404를 쓰지 않음). 이슈 #59 1단계.")
    @GetMapping("/active")
    public ResponseEntity<ActiveSessionResponseDto> getActiveSession(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return sessionService.getActiveSession(userDetails.getMember().getId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(summary = "세션 재부착 (이어하기)",
               description = "AI 프로세스 메모리에만 있던 분석 상태를 DB 값으로 되살려, 진행 중이던 세션을 이어할 수 있게 한다. "
                       + "클라는 GET /sessions/active 로 sessionId를 복원한 뒤 프레임 전송 전에 이 API를 호출한다. "
                       + "멱등 — AI 상태가 이미 살아있으면 아무것도 하지 않고 alreadyActive=true로 200을 준다(재시도 안전). "
                       + "본인 세션이 아니거나 이미 끝났거나 종료 요청된 세션은 404, 타임아웃 기준을 지났으면 410, "
                       + "AI 서버에 연결할 수 없으면 503(세션은 그대로 두므로 잠시 후 재시도 가능). 이슈 #59 2단계.")
    @PostMapping("/{sessionId}/reattach")
    public ResponseEntity<ReattachSessionResponseDto> reattachSession(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(
                exerciseAnalysisService.reattachSession(sessionId, userDetails.getMember().getId()));
    }

    @Operation(summary = "세션 종료 (사용자 명시 / 목표 달성 자동)",
               description = "클라가 운동 종료 시 호출 → endTime 기록 + AI gRPC 통보. 본인 세션 아니면 403, 이미 종료된 세션 재호출은 멱등 (200 OK).")
    @PatchMapping("/{sessionId}/end")
    public ResponseEntity<Void> endSession(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        sessionService.endSession(sessionId, userDetails.getMember().getId());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "세션 삭제",
               description = "세션 1건 삭제(pose_data 포함). 본인 세션 아니거나 존재하지 않으면 404, 진행 중(IN_PROGRESS)인 세션은 409 — 먼저 종료 후 삭제 가능.")
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> deleteSession(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        sessionService.deleteSession(sessionId, userDetails.getMember().getId());
        return ResponseEntity.noContent().build();
    }
}
