package com.shadowfit.dto.preference;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

public record TtsPreferenceUpdateDto(
        Boolean ttsEnabled,

        @DecimalMin(value = "0.5", message = "TTS 속도는 0.5 이상이어야 합니다")
        @DecimalMax(value = "2.0", message = "TTS 속도는 2.0 이하여야 합니다")
        BigDecimal ttsSpeed
) {
}