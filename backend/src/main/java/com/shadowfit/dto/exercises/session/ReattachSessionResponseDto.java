package com.shadowfit.dto.exercises.session;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 세션 재부착 응답 (이슈 #59 2단계).
 *
 * <p>재부착은 AI 프로세스 메모리에만 있던 분석 상태를 DB 값으로 되살리는 것이다. 되살릴 수 있는
 * 것과 없는 것이 명확히 갈리므로(docs/decisions/session-resume-and-ai-state.md §4-0), 그 경계를
 * 응답에 담아 클라가 사용자에게 안내할 수 있게 한다 — 조용히 성공을 반환하면 사용자는 "이어졌다"고
 * 믿는데 실제로는 진행 중이던 rep 이 사라진 상태다.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "세션 재부착 res dto")
public class ReattachSessionResponseDto {

    @Schema(description = "재부착된 세션 id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long sessionId;

    /**
     * 이어서 셀 기준이 되는 rep 수. {@code pose_data} 의 {@code MAX(rep_number)} 에서 복원한다.
     *
     * <p>{@code alreadyActive} 가 true 면 DB 값이 아니라 <b>살아있던 AI 상태의 현재 카운트</b>다 —
     * 진행 중이던 rep 이 아직 pose_data 로 넘어오지 않았을 수 있어 두 값이 다를 수 있고, 이때
     * 진실은 AI 쪽이다.
     */
    @Schema(description = "복원된 rep 수 (여기서부터 이어서 셈)", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer restoredRepCount;

    /**
     * true = AI 상태가 이미 살아있어서 아무것도 되살리지 않았다(멱등 경로).
     *
     * <p>중복 호출·네트워크 재시도·빠른 이탈 후 복귀에서 발생한다. 이 경우 재부착은 성공이지만
     * 실제로 한 일은 없으며, 살아있던 진행 중 rep 과 스무딩 이력이 그대로 보존됐다는 뜻이라
     * <b>손실이 없는 쪽</b>이다.
     */
    @Schema(description = "AI 상태가 이미 살아있어 복원이 불필요했는지 여부",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean alreadyActive;

    /**
     * 재부착으로 복원되지 <b>않는</b> 것이 있는지. {@code alreadyActive} 가 false 일 때만 true 다.
     *
     * <p>rep 카운트는 이어지지만 분석기 내부 상태(rep 진행 단계, 각도 스무딩 이력)는 초기값으로
     * 리셋되고, 재부착 시점에 진행 중이던 rep 의 프레임은 버려진다. 사용자 체감으로는 "횟수는
     * 이어지는데 처음 몇 프레임 동안 자세 판정이 잠깐 흔들릴 수 있다"에 해당한다.
     *
     * <p>이 손실을 보정하지 않고 감수하기로 한 결정이다 — 재개 후 첫 rep 을 집계에서 빼는 대안은
     * 정상적으로 수행한 rep 을 버리게 되어 더 나쁘다(§4-0, 2026-07-31 확정).
     */
    @Schema(description = "분석기 내부 상태가 리셋됐는지 여부 (true면 잠시 자세 판정이 흔들릴 수 있음)",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean analyzerStateReset;

    @Schema(description = "클라가 그대로 노출해도 되는 안내 문구", requiredMode = Schema.RequiredMode.REQUIRED)
    private String message;

    private static final String MSG_ALREADY_ACTIVE = "이미 분석이 진행 중이라 그대로 이어서 진행합니다.";
    private static final String MSG_RESTORED =
            "%d회까지 이어서 진행합니다. 자세 판정이 잠시 흔들릴 수 있습니다.";

    public static ReattachSessionResponseDto of(Long sessionId, int repCount, boolean alreadyActive) {
        return ReattachSessionResponseDto.builder()
                .sessionId(sessionId)
                .restoredRepCount(repCount)
                .alreadyActive(alreadyActive)
                .analyzerStateReset(!alreadyActive)
                .message(alreadyActive ? MSG_ALREADY_ACTIVE : String.format(MSG_RESTORED, repCount))
                .build();
    }
}
