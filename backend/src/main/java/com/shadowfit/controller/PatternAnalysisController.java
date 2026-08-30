package com.shadowfit.controller;

import com.shadowfit.dto.pattern.ConsistencyResponseDto;
import com.shadowfit.dto.pattern.IntensityTrendResponseDto;
import com.shadowfit.dto.pattern.PeriodicityResponseDto;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.service.Analysis.PatternAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "운동 패턴 분석", description = "사용자별 운동 요일/시간대·강도 추세·연속성 분석 (BE-07)")
@RestController
@RequestMapping("/patterns")
@RequiredArgsConstructor
public class PatternAnalysisController {

    private final PatternAnalysisService patternAnalysisService;

    @Operation(summary = "요일/시간대 패턴", description = "사용자가 주로 운동하는 요일·시간대 분포")
    @GetMapping("/periodicity")
    public ResponseEntity<PeriodicityResponseDto> getPeriodicity(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(patternAnalysisService.getPeriodicity(
                userDetails.getMember().getId(), userDetails.getMember().getCreatedAt()));
    }

    @Operation(summary = "강도 추세", description = "최근 4주간 주 단위 평균 syncRate·총 운동 시간 추세")
    @GetMapping("/intensity-trend")
    public ResponseEntity<IntensityTrendResponseDto> getIntensityTrend(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(patternAnalysisService.getIntensityTrend(
                userDetails.getMember().getId(), userDetails.getMember().getCreatedAt()));
    }

    @Operation(summary = "연속성", description = "연속 운동일 수, 최근 4주 내 빠진 날 수")
    @GetMapping("/consistency")
    public ResponseEntity<ConsistencyResponseDto> getConsistency(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(patternAnalysisService.getConsistency(
                userDetails.getMember().getId(), userDetails.getMember().getCreatedAt()));
    }
}
