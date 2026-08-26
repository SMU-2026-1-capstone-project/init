package com.shadowfit.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 운동 종목 등록 요청.
 *
 * <p><b>여기에 {@code analysisSupported} 가 없는 것은 누락이 아니다.</b> 그 값은 서버가
 * {@code false} 로 고정한다(엔티티 기본값). 종목 행이 먼저 생기고 ai-server 분석기가 나중에
 * 붙는 순서라, 등록자가 {@code true} 를 실어 보낼 수 있으면 <b>분석기가 없는 종목의 세션이
 * 열린다</b> — 그리고 그건 W007 가드가 막으려던 바로 그 상황이다({@code Exercise.java} 주석).
 *
 * <p><b>임계값 4종도 없다.</b> 엔티티 기본값(60/85/70/50)으로 시작하고, 바꾸려면 등록 후
 * {@code PATCH /admin/exercises/{id}/thresholds} 를 쓴다. 그쪽에는
 * {@code beginner < advanced} 불변식 검증이 이미 있어서, 등록에도 실으면 같은 검증을 두 곳에서
 * 하게 된다.
 */
@Schema(description = "운동 종목 등록 요청")
public record ExerciseCreateDto(

        @Schema(description = "운동명", example = "데드리프트", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "운동명은 필수입니다")
        @Size(max = 100, message = "운동명은 100자를 넘을 수 없습니다")
        String name,

        @Schema(description = "부위 카테고리 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "카테고리는 필수입니다")
        Long categoryId,

        @Schema(description = "설명")
        String description,

        @Schema(description = "대표 영상 URL")
        @Size(max = 500, message = "URL 은 500자를 넘을 수 없습니다")
        String preferredUrl,

        @Schema(description = "분석 대상 관절 (JSON 문자열). 형식이 JSON 이 아니면 400")
        String targetJoints,

        @Schema(description = "예상 운동시간(분). 생략하면 엔티티 기본값(15)", example = "15")
        @Min(value = 1, message = "예상 운동시간은 1분 이상이어야 합니다")
        Integer expectedDurationMinutes
) {
}