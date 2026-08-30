package com.shadowfit.controller;

import com.shadowfit.dto.recommendation.NextSessionRecommendationResponseDto;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.service.recommendation.RecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "운동 추천", description = "다음 스쿼트 세션 강도·볼륨 추천 (BE-08)")
@RestController
@RequestMapping("/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;

    @Operation(summary = "다음 세션 추천",
            description = "최근 완료 세션 평균 싱크로율 + 프로필 기반 강도·볼륨 추천, 근거 한 줄 포함")
    @GetMapping("/next-session")
    public ResponseEntity<NextSessionRecommendationResponseDto> getNextSessionRecommendation(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(
                recommendationService.getNextSessionRecommendation(userDetails.getMember().getId()));
    }
}
