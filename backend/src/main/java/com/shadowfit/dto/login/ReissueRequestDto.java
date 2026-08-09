package com.shadowfit.dto.login;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * access token 재발급 요청 (이슈 #135).
 *
 * <p><b>refresh token 하나만 받는다.</b> access token 을 같이 받지 않는 것은 의도다 — 이
 * 엔드포인트는 access 가 <b>만료된 뒤</b>에 불리는 것이 정상 경로라, 있으나 마나 한 값을
 * 요구하면 클라가 «만료된 것을 굳이 보내야 하나» 를 매번 판단하게 된다.
 *
 * <p>신원은 refresh token <b>자신의 서명</b>에서 나온다. 이 요청은 인증 필터를 통과하지 못하므로
 * ({@code SecurityContext} 가 비어 있다) 요청자를 물어볼 곳이 거기밖에 없다 —
 * {@code decisions/token-lifecycle.md} §4-2.
 */
@Getter
@AllArgsConstructor
@Builder
@NoArgsConstructor
@Schema(description = "토큰 재발급 req dto")
public class ReissueRequestDto {
    @Schema(description = "발급받은 리프레시 토큰", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "리프레시 토큰은 필수입니다.")
    private String refreshToken;
}
