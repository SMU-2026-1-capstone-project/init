package com.shadowfit.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 운동 종목 수정 요청 — <b>부분 수정(PATCH)</b>.
 *
 * <p><b>null = "이 필드는 건드리지 않는다".</b> 모든 필드가 nullable 인 이유이고, 값을 보낸
 * 필드만 갱신된다.
 *
 * <p>⚠️ <b>그래서 nullable 컬럼을 다시 비우는 것은 이 API 로 표현할 수 없다.</b>
 * ({@code description} 을 지우고 싶어도 null 을 보내면 "안 바꿈"이 된다.) 관리자 화면이 폼
 * 전체를 보내는 형태라 실제로 걸리는 경우가 없어 그대로 뒀다 — 필요해지면 "빈 문자열을 null 로
 * 해석" 같은 규약을 <b>정하고</b> 넣어야지, 조용히 바꾸면 안 되는 자리다.
 *
 * <p>{@code name}·{@code category} 는 DB 가 NOT NULL 이라 <b>보냈다면</b> 유효해야 한다.
 * {@code @NotBlank} 대신 {@code @Size} 만 건 이유가 이것이다 — 안 보내는 것은 허용하고,
 * 보낸 값이 빈 문자열인 것만 서비스에서 막는다.
 *
 * <p>{@code analysisSupported} 와 임계값 4종이 없는 이유는 {@link ExerciseCreateDto} 주석과 같다.
 */
@Schema(description = "운동 종목 수정 요청 (보낸 필드만 갱신)")
public record ExerciseUpdateDto(

        @Schema(description = "운동명. 생략하면 안 바뀐다", example = "데드리프트")
        @Size(max = 100, message = "운동명은 100자를 넘을 수 없습니다")
        String name,

        @Schema(description = "부위 카테고리 ID. 생략하면 안 바뀐다")
        Long categoryId,

        @Schema(description = "설명")
        String description,

        @Schema(description = "대표 영상 URL")
        @Size(max = 500, message = "URL 은 500자를 넘을 수 없습니다")
        String preferredUrl,

        @Schema(description = "분석 대상 관절 (JSON 문자열). 형식이 JSON 이 아니면 400")
        String targetJoints,

        @Schema(description = "예상 운동시간(분)", example = "20")
        @Min(value = 1, message = "예상 운동시간은 1분 이상이어야 합니다")
        Integer expectedDurationMinutes
) {
}