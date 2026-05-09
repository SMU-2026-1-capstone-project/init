package com.shadowfit.controller;

import com.shadowfit.dto.admin.ExerciseThresholdResponseDto;
import com.shadowfit.dto.admin.ThresholdUpdateDto;
import com.shadowfit.service.Exercise.AdminExerciseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "관리자 - 운동 설정", description = "운동 임계값 등 운영자 전용")
@RestController
@RequestMapping("/admin/exercises")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminExerciseController {
    private final AdminExerciseService adminExerciseService;

    @Operation(summary = "운동 싱크로율 임계값 변경",
               description = "초보자/고급자 임계값을 즉시 갱신. 신규 세션부터 적용. beginner < advanced 필수.")
    @PatchMapping("/{exerciseId}/thresholds")
    public ResponseEntity<ExerciseThresholdResponseDto> updateThresholds(
            @PathVariable Long exerciseId,
            @Valid @RequestBody ThresholdUpdateDto dto) {
        return ResponseEntity.ok(adminExerciseService.updateThresholds(exerciseId, dto));
    }
}