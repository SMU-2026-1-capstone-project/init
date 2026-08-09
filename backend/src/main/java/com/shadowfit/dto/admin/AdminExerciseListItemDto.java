package com.shadowfit.dto.admin;

import com.shadowfit.model.exercise.ExerciseCategory;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 관리자 운동 목록 한 줄.
 *
 * <p><b>엔티티가 아니라 이 DTO 로 직접 조회한다(프로젝션).</b> {@code Exercise} 를 통째로 읽으면
 * {@code description}(TEXT)·{@code targetJoints}(JSON) 까지 따라오는데, 둘 다 목록 화면에
 * 쓰지 않으면서 행마다 크기가 큰 컬럼이다. 상세({@link AdminExerciseDetailDto})에서만 읽는다.
 *
 * <p>{@code analysisSupported} 를 목록에 넣는 이유 — 이 값이 {@code false} 인 종목은 세션 생성이
 * W007 로 차단된다({@code SessionService.createSession}). 즉 "행은 있는데 못 쓰는 종목"이
 * 정상적으로 존재하며, 목록에서 그게 안 보이면 관리자가 이유를 알 수 없다.
 */
@Schema(description = "관리자 운동 목록 항목")
public record AdminExerciseListItemDto(

        @Schema(description = "운동 ID", example = "1")
        Long id,

        @Schema(description = "운동명", example = "스쿼트")
        String name,

        @Schema(description = "부위 카테고리")
        ExerciseCategory category,

        @Schema(description = "AI 분석 지원 여부. false 면 이 종목으로 세션을 시작할 수 없다(W007)")
        Boolean analysisSupported,

        @Schema(description = "예상 운동시간(분)", example = "15")
        Integer expectedDurationMinutes,

        @Schema(description = "등록일시")
        LocalDateTime createdAt
) {
}