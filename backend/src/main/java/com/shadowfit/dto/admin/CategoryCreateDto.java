package com.shadowfit.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "카테고리 등록 요청")
public record CategoryCreateDto(

        @Schema(description = "카테고리 이름", example = "하체", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "카테고리 이름은 필수입니다")
        @Size(max = 50, message = "카테고리 이름은 50자를 넘을 수 없습니다")
        String name
) {
}
