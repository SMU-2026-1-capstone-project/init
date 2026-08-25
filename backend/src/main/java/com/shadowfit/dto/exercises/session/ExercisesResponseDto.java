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

    /**
     * AI 서버 워커 인덱스 (2026-08-26, N=3 프로세스 분리 도입).
     *
     * <p>AI 를 프로세스 여러 개로 분리하면서(GIL 병목 회피) 세션 상태가 프로세스 로컬 메모리에
     * 있게 됐다. Spring&#8594;AI 제어 호출(gRPC)은 sessionId 기준 채널 풀로 같은 워커에
     * 고정되지만, 프론트&#8594;AI 프레임 경로(POST /pose, Spring 안 거침)는 그 라우팅을 모른다.
     * 이 값을 nginx 앞단에 {@code X-AI-Worker} 헤더로 실어 보내야 같은 워커로 간다 — 안 그러면
     * 커널이 무작위로 분산시켜 세션의 2/3 가 NO_LEASE 로 거절된다(2026-08-26 실측: 6건 중 4건).
     */
    @Schema(description = "AI 워커 인덱스(0~N-1). POST /pose 호출 시 X-AI-Worker 헤더로 동봉할 것")
    public Integer aiWorkerIndex;
}
