package com.shadowfit.dto.admin;

import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.WorkoutLevel;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 관리자 회원 목록 한 줄.
 *
 * <p><b>엔티티가 아니라 이 DTO 로 직접 조회한다(프로젝션).</b> 목록에 필요한 건 7개 컬럼인데
 * {@code Member} 를 통째로 읽으면 {@code password}, {@code ttsSpeed}, {@code height} 등
 * 화면에 쓰지 않는 값까지 따라온다. 특히 {@code password} 는 목록 조회 경로에 애초에
 * 실리지 않는 편이 안전하다 — 응답에서 빼는 것과 읽지 않는 것은 다르다.
 */
@Schema(description = "관리자 회원 목록 항목")
public record AdminMemberListItemDto(

        @Schema(description = "회원 ID", example = "1")
        Long id,

        @Schema(description = "회원명", example = "hong")
        String username,

        @Schema(description = "이메일", example = "hong@example.com")
        String email,

        @Schema(description = "페르소나")
        SelectedPersona selectedPersona,

        @Schema(description = "운동 레벨. 온보딩 전이면 null")
        WorkoutLevel workoutLevel,

        @Schema(description = "온보딩 완료 여부")
        boolean onboardingCompleted,

        @Schema(description = "가입일시")
        LocalDateTime createdAt
) {
}
