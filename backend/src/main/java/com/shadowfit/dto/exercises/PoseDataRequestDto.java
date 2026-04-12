package com.shadowfit.dto.exercises;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "AI 분석 좌표 데이터 수신 DTO")
public class PoseDataRequestDto {

    @Schema(description = "운동 세션 ID", example = "1")
    private Long sessionId;

    @Schema(description = "영상 내 시간(초)", example = "1.25")
    private Double timestampSec;

    @Schema(description = "관절 좌표 (JSON 문자열)", example = "{\"nose\": [0.5, 0.5] ...}")
    private String jointCoordinates;

    @Schema(description = "정답 영상과의 일치율", example = "88.5")
    private Double syncRate;

    @Schema(description = "자세 정답 여부")
    private Boolean isCorrect;

    @Schema(description = "피드백 메시지", example = "허리를 더 펴주세요")
    private String feedbackMessage;
}