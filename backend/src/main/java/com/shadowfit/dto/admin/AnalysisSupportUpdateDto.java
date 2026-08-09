package com.shadowfit.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 운동 종목의 AI 분석 활성화 여부 변경 요청.
 *
 * <p><b>왜 등록·수정 DTO 가 아니라 전용 엔드포인트인가</b> — 이 플래그는 다른 필드와 성격이
 * 다르다. 이름·설명은 틀려도 화면에 잘못 보이고 끝이지만, 이 값이 {@code true} 가 되면
 * {@code SessionService.createSession} 의 W007 가드가 열려 <b>세션이 실제로 시작된다</b>.
 * 수정 폼이 전 필드를 보내는 형태라, 같은 DTO 에 두면 이름 하나 고치다 딸려 켜질 수 있다.
 * 임계값을 {@code /thresholds} 로 뺀 것과 같은 이유다.
 *
 * <p>{@code Boolean} 에 {@code @NotNull} 을 거는 이유 — {@code boolean} 이면 필드를 안 보냈을 때
 * 조용히 {@code false} 가 된다. "끄겠다"와 "안 보냈다"가 구분돼야 한다.
 */
@Schema(description = "AI 분석 활성화 여부 변경 요청")
public record AnalysisSupportUpdateDto(

        @Schema(description = "true 면 이 종목으로 세션을 시작할 수 있게 된다",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "supported 는 필수입니다")
        Boolean supported
) {
}