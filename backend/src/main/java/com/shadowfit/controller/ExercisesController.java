package com.shadowfit.controller;

import com.shadowfit.dto.exercises.session.ExercisesResponseDto;
import com.shadowfit.dto.exercises.VideoRequestDto;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.service.Exercise.ExerciseAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;


@Tag(name = "운동 분석", description = "운동 분석 및 세션 관리 API")
@Slf4j
@RestController
@RequestMapping("/exercises")
@RequiredArgsConstructor
public class ExercisesController {

    private final ExerciseAnalysisService analysisService;

    /**
     * ✅ 기준 좌표 추출 (관리자/등록용)
     */
    @Operation(summary="기준 좌표 추출",description = "기준 좌표 추출 요청을 할 수 있음")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{exerciseId}/reference")
    public ResponseEntity<String> extractReference(
            @PathVariable Long exerciseId,
            @RequestParam String youtubeUrl
    ) {
        log.info("기준 좌표 추출 요청 - exerciseId: {}", exerciseId);

        analysisService.extractReferencePoses(exerciseId, youtubeUrl);

        return ResponseEntity.accepted()
                .body("운동 ID [" + exerciseId + "]에 대한 기준 좌표 추출이 시작되었습니다.");
    }


    /**
     * ✅ 운동 세션 시작 (핵심 API)
     * App → Spring → gRPC → FastAPI 흐름 시작점
     */
    @Operation(summary="운동 세션 시작",description = "운동을 시작할 수 있음/ ai서버에서 특정 조건을 달성하면 운동 종료가됨")
    // ⚠️ 2026-08-10: @Valid 가 없어 exerciseId=null 이 500 을 냈다 (이슈 #178).
    // @RequestBody 12곳 중 여기 하나만 빠져 있었다. DTO 의 @NotNull 과 **둘 다** 있어야 한다 —
    // @Valid 만 붙이면 제약이 없어 아무것도 안 걸리고, @NotNull 만 붙이면 트리거가 없어 평가되지 않는다.
    @PostMapping("/sessions")
    public ResponseEntity<ExercisesResponseDto> startAnalysis(
            @Valid @RequestBody VideoRequestDto dto,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        Long memberId = userDetails.getMember().getId();

        log.info("운동 분석 요청 시작 - userId: {}, exerciseId: {}",
                memberId, dto.getExerciseId());

        // 서비스 호출 (내부에서 gRPC 호출까지 이어짐)
        var started = analysisService.startAnalysis(dto, memberId);

        // 응답 DTO 생성
        //
        // sessionNonce 는 이 응답으로만 클라에 나간다 (#187 안 (d)) — 이후 POST /pose 마다
        // 동봉해야 AI 가 «이 세션을 만든 클라» 로 인정한다. 앱이 죽어 이 응답을 잃으면
        // GET /exercises/sessions/active 로 다시 받는다(ActiveSessionResponseDto).
        ExercisesResponseDto response = ExercisesResponseDto.builder()
                .sessionId(started.sessionId())
                .exerciseId(dto.getExerciseId())
                // 🔴 저장된 값을 그대로 싣는다 (#467). 예전엔 여기서 LocalDateTime.now() 를
                //    **새로 읽었다** — 세션을 저장한 시각과 다른 now() 호출이라, 초 경계를
                //    넘으면 클라가 받는 시각이 DB 와 1초 갈렸다(실측: 응답 13:21:04 · DB 05).
                //    표시용 값이 아니라 pose_data 의 멱등 앵커라서(#188 · #392) 어긋나면 안 된다.
                .startTime(started.startTime())
                .status(Status.IN_PROGRESS)
                .sessionNonce(started.sessionNonce())
                .aiWorkerIndex(started.aiWorkerIndex())
                .build();

        return ResponseEntity.accepted().body(response);
    }
}