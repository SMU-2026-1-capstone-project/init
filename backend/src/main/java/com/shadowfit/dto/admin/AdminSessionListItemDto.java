package com.shadowfit.dto.admin;

import com.shadowfit.model.exercise.Status;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 관리자 세션 목록 한 줄.
 *
 * <p><b>엔티티가 아니라 이 DTO 로 직접 조회한다(프로젝션).</b> 회원 목록과 같은 이유이되,
 * 여기서는 이유가 하나 더 있다 — {@code Session} 엔티티를 받아 {@code session.getMember()
 * .getUsername()} 을 꺼내면 지연 로딩이 <b>행마다</b> 터진다. 20건이면 회원 조회 20번,
 * 운동 조회 20번이다. 조인으로 한 번에 가져와 DTO 로 채우면 쿼리가 하나로 끝난다.
 *
 * <p>{@code referenceSource}·{@code caloriesBurned}·{@code maxSyncRate} 등은 목록에 쓰지
 * 않으므로 읽지 않는다.
 */
@Schema(description = "관리자 세션 목록 항목")
public record AdminSessionListItemDto(

        @Schema(description = "세션 ID", example = "1")
        Long id,

        @Schema(description = "회원 ID", example = "1")
        Long memberId,

        @Schema(description = "회원명", example = "hong")
        String username,

        @Schema(description = "운동 종목 ID", example = "1")
        Long exerciseId,

        @Schema(description = "운동 종목명", example = "스쿼트")
        String exerciseName,

        @Schema(description = "세션 상태")
        Status status,

        @Schema(description = "시작 시각")
        LocalDateTime startTime,

        @Schema(description = "종료 시각. 진행 중이거나 타임아웃 처리 전이면 null")
        LocalDateTime endTime,

        @Schema(description = "총 반복 수", example = "30")
        Integer totalReps,

        @Schema(description = "평균 싱크로율. 분석 결과가 회수되기 전이면 null", example = "75.00")
        BigDecimal avgSyncRate
) {
}
