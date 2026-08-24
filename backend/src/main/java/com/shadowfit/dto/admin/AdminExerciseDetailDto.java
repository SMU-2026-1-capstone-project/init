package com.shadowfit.dto.admin;

import com.shadowfit.model.exercise.Exercise;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 관리자 운동 상세 — 등록·수정·상세조회의 공통 응답.
 *
 * <p>목록({@link AdminExerciseListItemDto})과 달리 <b>엔티티 전체</b>를 담는다. 상세는 한 건이라
 * 큰 컬럼을 읽어도 목록처럼 행 수만큼 곱해지지 않고, 관리자가 수정 폼을 채우려면 전부 필요하다.
 *
 * <p>임계값 4종을 여기 포함하는 이유 — 값을 <b>바꾸는</b> 경로는
 * {@code PATCH /admin/exercises/{id}/thresholds} 로 따로 있지만, <b>보는</b> 경로가 상세뿐이다.
 * 수정 화면에서 현재값을 보여주려면 상세가 실어와야 한다.
 */
@Schema(description = "관리자 운동 상세")
public record AdminExerciseDetailDto(

        @Schema(description = "운동 ID", example = "1")
        Long id,

        @Schema(description = "운동명", example = "스쿼트")
        String name,

        @Schema(description = "부위 카테고리 ID")
        Long categoryId,

        @Schema(description = "부위 카테고리 이름")
        String categoryName,

        @Schema(description = "설명")
        String description,

        @Schema(description = "대표 영상 URL")
        String preferredUrl,

        @Schema(description = "분석 대상 관절 (JSON 문자열)")
        String targetJoints,

        @Schema(description = "초보자 싱크로율 임계값")
        BigDecimal syncThresholdBeginner,

        @Schema(description = "고급자 싱크로율 임계값")
        BigDecimal syncThresholdAdvanced,

        @Schema(description = "다이어트 싱크로율 임계값")
        BigDecimal syncThresholdDiet,

        @Schema(description = "재활 싱크로율 임계값")
        BigDecimal syncThresholdRehab,

        @Schema(description = "예상 운동시간(분)", example = "15")
        Integer expectedDurationMinutes,

        @Schema(description = "AI 분석 지원 여부. 이 API 로는 바꿀 수 없다 — 클래스 주석 참고")
        Boolean analysisSupported,

        @Schema(description = "등록일시")
        LocalDateTime createdAt
) {
    public static AdminExerciseDetailDto fromEntity(Exercise e) {
        return new AdminExerciseDetailDto(
                e.getId(),
                e.getName(),
                e.getCategory().getId(),
                e.getCategory().getName(),
                e.getDescription(),
                e.getPreferredUrl(),
                e.getTargetJoints(),
                e.getSyncThresholdBeginner(),
                e.getSyncThresholdAdvanced(),
                e.getSyncThresholdDiet(),
                e.getSyncThresholdRehab(),
                e.getExpectedDurationMinutes(),
                e.getAnalysisSupported(),
                e.getCreatedAt()
        );
    }
}
