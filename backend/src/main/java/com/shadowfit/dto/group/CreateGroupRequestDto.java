package com.shadowfit.dto.group;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "그룹 생성 req dto")
public class CreateGroupRequestDto {
    @NotBlank
    @Schema(description = "그룹 이름", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;
}