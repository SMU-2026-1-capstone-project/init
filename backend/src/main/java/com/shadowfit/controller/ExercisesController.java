package com.shadowfit.controller;

import com.shadowfit.dto.exercises.VideoRequestDto;
import com.shadowfit.service.Exercise.ExerciseAnalysisService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name="운동 분석", description="기준 영상 좌표 다운로드/업로드")
@Slf4j
@RestController
@RequestMapping("/exercises")
@RequiredArgsConstructor
public class ExercisesController {
    private final ExerciseAnalysisService analysisService;

    @PostMapping("/sessions")
    public ResponseEntity<String> startAnalysis(@RequestBody VideoRequestDto dto,
                                                @AuthenticationPrincipal String memberId){
        Long sessionId = analysisService.sendToAnalysisServer(dto,memberId);

        return ResponseEntity.accepted().body("분석이 시작되었습니다. 작업 ID: "+sessionId);
    }

}
