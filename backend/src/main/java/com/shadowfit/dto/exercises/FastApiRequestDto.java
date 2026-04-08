package com.shadowfit.dto.exercises;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "운동세션 시작 req dto(스프링->fastapi)")
public class FastApiRequestDto {
    private Long exerciseId;
    private String youtubeUrl;
    private Long sessionId;
}
