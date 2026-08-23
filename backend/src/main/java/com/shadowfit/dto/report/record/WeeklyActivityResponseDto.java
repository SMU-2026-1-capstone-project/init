package com.shadowfit.dto.report.record;

import com.shadowfit.dto.report.detailreport.ExerciseSessionDto;
import lombok.*;

import com.shadowfit.dto.report.weekly.WeeklySummaryResponseDto;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WeeklyActivityResponseDto {
    private String dateRange;            // "3월 23일 - 29일"
    private int totalWorkouts;           // 4 Workouts
    private int totalMinutes;            // 35 Min
    private int totalCalories;           // 250 Kcal

    private List<DailyLogSummaryDto> dailyLogs; // 요일별 막대 그래프용 데이터
    private List<ExerciseSessionDto> todayDetails; // "3월 23일 운동 상세" 리스트

    /**
     * A층 주간 요약 — 세션수·회차·싱크로율·운동일수 집계와 규칙 문장 (#346).
     *
     * <p>🔵 <b>2026-08-23: 별도 엔드포인트에서 여기로 합쳤다</b> (#352). {@code GET /reports/weekly}
     * 로 따로 나가 있었는데 <b>부르는 곳이 없었다</b> — 같은 base 에 «주간» 이 둘이라
     * (`/reports/weekly-summary` · `/reports/weekly`) 프론트에서 보면 이미 하나 있어서 새것이
     * 안 보였다. 이름이 한 마디 차이인데 응답도 의미도 달랐다.
     *
     * <p><b>필드를 더하는 방향으로 합친 이유</b>: 프론트가 이미 부르던 경로를 그대로 두므로
     * 배선이 안 깨진다. 새 화면은 이 필드를 읽으면 되고, 안 읽는 화면은 영향이 없다.
     */
    private WeeklySummaryResponseDto summary;
}
