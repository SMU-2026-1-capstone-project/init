package com.shadowfit.dto.report.detailreport;

import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.report.Report;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SessionReportResponseDto {
    private Long sessionId;
    private int avgSyncRate;
    private int totalReps;
    private int workoutMinutes;
    private int caloriesBurned;
    private String aiSafetyReport;

    private WorstSectionDto worstSection;
    private List<ExerciseSyncRateDto> syncRateDetails;

    private ComparisonWithPreviousDto comparisonWithPrevious;

    /**
     * <p><b>null 을 0 으로 접는 이유</b>: 이 DTO 의 수치 필드는 원시형(int)이라 "값 없음"을 표현할
     * 수단이 없다. 그런데 세션 쪽 컬럼은 전부 nullable 이다 — 특히 {@code avgSyncRate} 는
     * <b>측정된 rep 이 없으면 의도적으로 null 로 저장한다</b>(이슈 #75,
     * {@code SessionService.applySyncStats}). 0 을 쓰면 "측정 안 됨"이 "싱크로율 0%"로 둔갑해
     * 월 평균 집계를 끌어내리기 때문이다.
     *
     * <p>집계에서는 그 구분이 반드시 필요하지만 <b>단건 리포트 화면에서는 필요 없다</b> — 측정된
     * rep 이 없는 세션은 어차피 {@code totalReps} 도 0 이라 "0회 / 0%"로 자기모순 없이 읽힌다.
     * 그래서 응답 계약(항상 숫자)은 그대로 두고 여기서만 접는다.
     *
     * <p>접기 전에는 {@code getAvgSyncRate().intValue()} 라 null 인 순간 NPE 로 <b>리포트 조회가
     * 500</b> 이 됐다. 지금까지 안 터진 건 AI 가 늘 0.0 을 보내 실제 null 이 없었기 때문이고,
     * #75 수정으로 null 이 실재하게 되면서 드러났다. {@code totalReps}·{@code caloriesBurned} 도
     * 같은 형태의 잠복 NPE 라 함께 막는다.
     */
    public static SessionReportResponseDto of(Session session, Report report) {
        SessionReportResponseDto dto = new SessionReportResponseDto();
        dto.setSessionId(session.getId());
        dto.setAvgSyncRate(intOrZero(session.getAvgSyncRate()));
        dto.setTotalReps(session.getTotalReps() == null ? 0 : session.getTotalReps());
        dto.setWorkoutMinutes((int) java.time.Duration.between(session.getStartTime(), session.getEndTime()).toMinutes());
        dto.setCaloriesBurned(intOrZero(session.getCaloriesBurned()));
        dto.setAiSafetyReport(report.getImprovementTips());
        return dto;
    }

    private static int intOrZero(java.math.BigDecimal value) {
        return value == null ? 0 : value.intValue();
    }
}
