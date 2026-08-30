package com.shadowfit.dto.recommendation;

import java.math.BigDecimal;

/**
 * GET /recommendations/next-session 응답 (BE-08). 저장된 값이 아니라 매 호출마다
 * {@code RecommendationService}가 최근 완료 세션·회원 프로필로 그때그때 계산한다 —
 * 순수 함수라 캐싱·별도 상태 없음(recommendation-algorithm.md §10 실측 근거).
 */
public record NextSessionRecommendationResponseDto(
        int difficultyLevel,
        int targetReps,
        BigDecimal targetSyncRate,
        int restTimeSec,
        String reason
) {
}
