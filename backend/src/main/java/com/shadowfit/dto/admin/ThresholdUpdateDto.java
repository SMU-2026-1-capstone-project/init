package com.shadowfit.dto.admin;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ThresholdUpdateDto(
        @NotNull
        @DecimalMin(value = "0.0", message = "초보자 임계값은 0 이상이어야 합니다")
        @DecimalMax(value = "100.0", message = "초보자 임계값은 100 이하여야 합니다")
        BigDecimal beginner,

        @NotNull
        @DecimalMin(value = "0.0", message = "고급자 임계값은 0 이상이어야 합니다")
        @DecimalMax(value = "100.0", message = "고급자 임계값은 100 이하여야 합니다")
        BigDecimal advanced
) {
}