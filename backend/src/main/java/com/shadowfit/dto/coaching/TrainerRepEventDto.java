package com.shadowfit.dto.coaching;

/**
 * 트레이너 SSE로 중계되는 rep 단위 이벤트 ({@code trainer-live-monitoring.md} §8 세션4).
 *
 * <p>{@code danger}·{@code message}는 {@code pose_data.feedback_message}(프레임별 자유
 * 텍스트) 기반이다 — {@code session_feedback_logs.feedback_type}(구조화된 분류)는 별도
 * gRPC 콜백(ReportFeedbackBatch)에서 오고, 그 훅을 새로 여는 것보다 이미 이 배치 안에 있는
 * 데이터로 충분하다고 판단했다(2026-08-30 사용자 confirm).
 */
public record TrainerRepEventDto(
        int repNumber,
        double syncRate,
        boolean danger,
        String message
) {
}
