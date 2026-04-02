package com.shadowfit.dto.onboarding;

import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.WorkoutLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Data
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "온보딩 멤버 정보 수정 req dto")
public class OnboardingRequestDto {

    @Schema(description = "페르소나 조회", requiredMode = Schema.RequiredMode.REQUIRED)
    private SelectedPersona selectedPersona;

    @Schema(description = "운동 수준 조회", requiredMode = Schema.RequiredMode.REQUIRED)
    private WorkoutLevel workoutLevel;

    @Schema(description = "키 조회", requiredMode = Schema.RequiredMode.REQUIRED)
    private String height;

    @Schema(description = "몸무게 조회", requiredMode = Schema.RequiredMode.REQUIRED)
    private String weight;
}
