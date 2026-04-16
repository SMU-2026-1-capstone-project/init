package com.shadowfit.controller;

import com.shadowfit.dto.exercises.ExercisesResponseDto;
import com.shadowfit.dto.exercises.VideoRequestDto;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.service.Exercise.ExerciseAnalysisService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@Tag(name = "운동 분석", description = "운동 분석 및 세션 관리 API")
@Slf4j
@RestController
@RequestMapping("/api/exercises")
@RequiredArgsConstructor
public class ExercisesController {

    private final ExerciseAnalysisService analysisService;

    /**
     * ✅ 운동 세션 시작 (핵심 API)
     * App → Spring → gRPC → FastAPI 흐름 시작점
     */
    @PostMapping("/sessions")
    public ResponseEntity<ExercisesResponseDto> startAnalysis(
            @RequestBody VideoRequestDto dto,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        Long memberId = userDetails.getMember().getId();

        log.info("운동 분석 요청 시작 - userId: {}, exerciseId: {}",
                memberId, dto.getExerciseId());

        // 서비스 호출 (내부에서 gRPC 호출까지 이어짐)
        Long sessionId = analysisService.startAnalysis(dto, memberId);

        // 응답 DTO 생성
        ExercisesResponseDto response = ExercisesResponseDto.builder()
                .sessionId(sessionId.intValue()) // 👉 Long → Integer 변환 (DTO 맞춤)
                .exerciseId(dto.getExerciseId().intValue())
                .startTime(LocalDateTime.now())
                .status(Status.IN_PROGRESS)
                .build();

        return ResponseEntity.accepted().body(response);
    }

    /**
     * ✅ 기준 좌표 추출 (관리자/등록용)
     */
    @PostMapping("/{exerciseId}/reference")
    public ResponseEntity<String> extractReference(
            @PathVariable Long exerciseId,
            @RequestBody String youtubeUrl
    ) {
        log.info("기준 좌표 추출 요청 - exerciseId: {}", exerciseId);

        analysisService.extractReferencePoses(exerciseId, youtubeUrl);

        return ResponseEntity.accepted()
                .body("기준 좌표 추출 요청이 전달되었습니다.");
    }
}