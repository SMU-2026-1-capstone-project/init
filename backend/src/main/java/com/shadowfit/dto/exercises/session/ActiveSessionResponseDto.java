package com.shadowfit.dto.exercises.session;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 진행 중(IN_PROGRESS) 세션 조회 응답.
 *
 * <p>클라가 앱 재시작 후 세션을 복원하는 데 필요한 최소 정보만 담는다 — 어떤 운동을(exerciseId,
 * exerciseName) 언제부터(startTime) 하고 있었는지. 통계 필드(totalReps 등)는 세션이 끝나야
 * 채워지므로 넣지 않는다.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "진행 중인 운동 세션 res dto")
public class ActiveSessionResponseDto {

    @Schema(description = "세션 id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long sessionId;

    @Schema(description = "운동 id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long exerciseId;

    @Schema(description = "운동 이름", requiredMode = Schema.RequiredMode.REQUIRED)
    private String exerciseName;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Schema(description = "시작 시간", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime startTime;

    @Schema(description = "상태 (항상 IN_PROGRESS)", requiredMode = Schema.RequiredMode.REQUIRED)
    private Status status;

    /**
     * 종료 요청 시각. <b>null 이면 아직 운동 중(이어하기 가능), 값이 있으면 사용자가 종료를 눌렀고
     * AI 결과 콜백을 기다리는 중</b>이다. 후자를 "이어하기"로 안내하면 이미 끝낸 운동을 다시
     * 시작하라고 하는 셈이라, 클라는 이 필드로 두 상태를 구분해야 한다.
     *
     * <p>endSession 은 endTime 만 기록하고 status 는 그대로 IN_PROGRESS 로 둔다 — COMPLETED 전환은
     * AI 의 CompleteAnalysis 콜백(applyComplete)이 한다. 그래서 status 만으로는 구분이 안 된다.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Schema(description = "종료 요청 시각. null이면 운동 중(이어하기 가능), 값이 있으면 결과 처리 대기 중")
    private LocalDateTime endTime;

    /**
     * exercise 는 호출부에서 JOIN FETCH 로 미리 가져온 상태여야 한다 —
     * open-in-view: false 라 트랜잭션 밖에서 lazy 접근하면 터진다.
     */
    public static ActiveSessionResponseDto from(Session session) {
        return ActiveSessionResponseDto.builder()
                .sessionId(session.getId())
                .exerciseId(session.getExercise().getId())
                .exerciseName(session.getExercise().getName())
                .startTime(session.getStartTime())
                .status(session.getStatus())
                .endTime(session.getEndTime())
                .build();
    }
}
