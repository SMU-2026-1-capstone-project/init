package com.shadowfit.dto.admin;

import com.shadowfit.model.exercise.Category;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "카테고리")
public record CategoryResponseDto(

        @Schema(description = "카테고리 ID", example = "1")
        Long id,

        @Schema(description = "카테고리 이름", example = "하체")
        String name,

        @Schema(description = "등록일시")
        LocalDateTime createdAt
) {
    public static CategoryResponseDto fromEntity(Category c) {
        return new CategoryResponseDto(c.getId(), c.getName(), c.getCreatedAt());
    }
}
