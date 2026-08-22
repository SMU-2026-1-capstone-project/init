package com.shadowfit.dto.exercises.session;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.shadowfit.model.exercise.Status;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "운동세션 시작 res dto")
public class ExercisesResponseDto {
    @Schema(description = "세션 id",
            requiredMode = Schema.RequiredMode.REQUIRED)
    public Long sessionId;

    @Schema(description = "운동 id",
            requiredMode = Schema.RequiredMode.REQUIRED)
    public Long exerciseId;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Schema(description = "시작 시간",
            requiredMode = Schema.RequiredMode.REQUIRED)
    public LocalDateTime startTime;

    @Schema(description = "상태",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @Builder.Default
    public Status status = Status.IN_PROGRESS;

    /**
     * 세션 소유권 검증용 비밀값 (#187 안 (d)). <b>이 세션을 만든 클라에게만</b> 나간다.
     *
     * <p>클라는 이 값을 보관했다가 {@code POST /pose} 마다 동봉해야 한다 — AI 가 보관값과
     * 대조해서 «남의 session_id 로 프레임 꽂기» 를 막는다. {@code session_id} 는 순차 정수라
     * 추측되지만 이 값은 안 된다는 것이 방어의 전부다.
     *
     * <p>{@code null} 일 수 있다 — 이 기능 배포 <b>전에</b> 시작된 세션이다. 1단계는 그런 세션을
     * 그대로 통과시킨다(compat).
     *
     * <p>🔴 클라도 이 값을 로그·화면에 남기면 안 된다.
     */
    @Schema(description = "세션 소유권 검증용 비밀값 (#187). POST /pose 에 동봉할 것. null이면 이 기능 배포 전 세션")
    public String sessionNonce;
}
