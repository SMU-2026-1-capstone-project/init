package com.shadowfit.controller;

import com.shadowfit.dto.goal.GoalCreateRequestDto;
import com.shadowfit.dto.goal.GoalResponseDto;
import com.shadowfit.dto.goal.GoalUpdateRequestDto;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.service.goal.GoalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "운동 목표", description = "주간 운동 목표 CRUD + 진척 조회 (BE-06)")
@RestController
@RequestMapping("/goals")
@RequiredArgsConstructor
public class GoalController {

    private final GoalService goalService;

    @Operation(summary = "목표 생성", description = "goalType당 1개만 허용(중복 시 409)")
    @PostMapping
    public ResponseEntity<GoalResponseDto> createGoal(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody GoalCreateRequestDto request
    ) {
        GoalResponseDto response = goalService.createGoal(userDetails.getMember().getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "목표 목록 조회", description = "본인 목표 전체 — 최근 7일 진척 포함")
    @GetMapping
    public ResponseEntity<List<GoalResponseDto>> getGoals(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(goalService.getGoals(userDetails.getMember().getId()));
    }

    @Operation(summary = "목표 수정", description = "targetValue만 변경 가능 — goalType은 불변")
    @PatchMapping("/{goalId}")
    public ResponseEntity<GoalResponseDto> updateGoal(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long goalId,
            @Valid @RequestBody GoalUpdateRequestDto request
    ) {
        return ResponseEntity.ok(goalService.updateGoal(userDetails.getMember().getId(), goalId, request));
    }

    @Operation(summary = "목표 삭제")
    @DeleteMapping("/{goalId}")
    public ResponseEntity<Void> deleteGoal(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long goalId
    ) {
        goalService.deleteGoal(userDetails.getMember().getId(), goalId);
        return ResponseEntity.noContent().build();
    }
}
