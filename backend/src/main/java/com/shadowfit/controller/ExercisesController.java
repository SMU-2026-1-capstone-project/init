package com.shadowfit.controller;

import com.shadowfit.dto.exercises.session.ExercisesResponseDto;
import com.shadowfit.dto.exercises.VideoRequestDto;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.service.Exercise.ExerciseAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.shadowfit.dto.exercises.session.SessionUpdateRequestDto;
import com.shadowfit.dto.exercises.session.SessionUpdateResponseDto;

import java.time.LocalDateTime;

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
                .sessionId(sessionId)
                .exerciseId(dto.getExerciseId())
                .startTime(LocalDateTime.now())
                .status(Status.IN_PROGRESS)
                .build();

        return ResponseEntity.accepted().body(response);
    }


    /**
     * 운동 세션 중단 요청
     *
     * 프론트가 종료 버튼을 누르면 Spring 이 AI 서버로 gRPC StopAnalysis 를
     * 송신한다. AI 는 누적 결과를 정리해 CompleteAnalysis 로 다시 콜백하고,
     * 최종 결과의 DB 영속화는 그 콜백에서 일어난다. 따라서 본 응답은 즉시
     * 202 Accepted 만 반환하며, 프론트는 결과 조회 API 로 폴링/페치한다.
     */
    @Operation(
            summary = "운동 세션 중단",
            description = "프론트에서 종료 버튼을 누르면 AI 서버에 중단 신호를 보내고, AI 가 분석 결과를 비동기로 다시 보고한다."
    )
    @PutMapping("/sessions/{sessionId}/stop")
    public ResponseEntity<Void> stopSession(@PathVariable Long sessionId) {
        log.info("운동 세션 중단 요청 - sessionId: {}", sessionId);
        analysisService.stopAnalysis(sessionId);
        return ResponseEntity.accepted().build();
    }


    /**
     * @deprecated /sessions/{id}/stop 으로 대체. 프론트 마이그레이션 완료 후 제거.
     *     본 경로는 프론트가 자체 카운트한 결과를 DB 에 직접 반영하던 옛 흐름으로,
     *     AI 분석 결과와 권위가 충돌해 일관성이 깨지는 문제가 있었다.
     */
    @Deprecated
    @Operation(
            summary = "[Deprecated] 운동 세션 종료 요청",
            description = "사용 자제. /sessions/{id}/stop 으로 대체될 예정."
    )
    @PutMapping("/sessions/{sessionId}/complete")
    public ResponseEntity<SessionUpdateResponseDto> completeSession(
            @PathVariable Long sessionId,
            @RequestBody SessionUpdateRequestDto updateDto
    ) {
        log.info("운동 세션 종료 요청 (deprecated) - sessionId: {}, totalReps: {}",
                sessionId, updateDto.getTotalReps());

        // 서비스 호출하여 상태 변경 및 결과 업데이트
        analysisService.completeSession(sessionId, updateDto);

        // 응답 생성
        SessionUpdateResponseDto response = SessionUpdateResponseDto.builder()
                .sessionId(sessionId)
                .status(Status.COMPLETED)
                .endTime(LocalDateTime.now())
                .build();

        return ResponseEntity.ok(response);
    }
}