package com.shadowfit.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 카테고리 이름 수정. {@link CategoryCreateDto}와 달리 필드가 하나뿐이라 "생략하면 유지"
 * 규약이 필요 없다 — 보내는 순간 그 이름으로 바뀐다({@code ExerciseUpdateDto}의 부분수정
 * 규약과는 다른 자리, 바꿀 필드가 이름 하나뿐이라 부분/전체 수정의 구분 자체가 없다).
 */
@Schema(description = "카테고리 수정 요청")
public record CategoryUpdateDto(

        @Schema(description = "카테고리 이름", example = "하체", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "카테고리 이름은 필수입니다")
        @Size(max = 50, message = "카테고리 이름은 50자를 넘을 수 없습니다")
        String name
) {
}
