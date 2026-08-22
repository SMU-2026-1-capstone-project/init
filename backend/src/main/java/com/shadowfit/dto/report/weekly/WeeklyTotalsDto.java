package com.shadowfit.dto.report.weekly;

import java.math.BigDecimal;

/**
 * 한 기간의 «A층» 집계 — {@code exercise_sessions} 한 표만 읽어서 나오는 값들.
 *
 * <p><b>왜 «A층» 인가</b> — 이 앱의 운동 데이터는 네 층으로 쌓여 있다:
 * 세션 헤더({@code exercise_sessions}, 세션당 1행) → 회차별 값
 * ({@code reports.detailed_analysis} JSON) → 프레임({@code pose_data}) → 관절 좌표(JSON 안).
 * 아래로 갈수록 말할 수 있는 것이 늘지만 부피가 자릿수로 뛴다. 주간 요약의 절반은 <b>맨 위층만
 * 읽어도</b> 나오고, 그게 이 DTO 다. 설계: {@code docs/decisions/report-generation-llm.md} §13.
 *
 * <p><b>평균이 둘인 이유</b> — 「이번 주 싱크로율」의 정의가 하나가 아니다.
 * <ul>
 *   <li>{@link #repWeightedSyncRate} — 회차 하나를 한 표로 센다. 회차를 많이 한 세션이 더 무겁다</li>
 *   <li>{@link #sessionWeightedSyncRate} — 세션 하나를 한 표로 센다. 10회짜리와 30회짜리가 같은 무게다</li>
 * </ul>
 * 세션마다 회차 수가 다르므로 <b>두 값은 다르다.</b> 어느 쪽을 화면에 쓸지 <b>고르지 않고 둘 다
 * 낸다</b> — 카드 B 가 세션 «안에서» 프레임 가중 ↔ rep 가중을 실측으로 갈랐던 것과 같은 수법이다
 * (loadtest/results/card-b-avg-2026-08-20). 차이가 잡음 수준이면 논쟁이 사라지고, 크면 그 크기가
 * 결정의 근거가 된다.
 *
 * <p>🔴 <b>{@code repWeighted} 의 가중치는 근사다.</b> {@code avg_sync_rate} 는 «측정된» 회차들의
 * 평균인데 {@code total_reps} 는 측정 안 된 회차도 센다. 정확한 가중치인 «측정된 회차 수» 는
 * 컬럼에 없고 B층({@code detailed_analysis} 의 {@code repTrend} 길이)에만 있다. B층을 붙이기
 * 전까지는 이 근사를 쓰고, 그 사실을 여기 적어 둔다.
 *
 * @param sessions               완료된 세션 수
 * @param totalReps              총 회차 수 (측정 안 된 회차 포함)
 * @param repWeightedSyncRate    회차 가중 평균. 완료 세션이 없거나 회차가 0이면 null
 * @param sessionWeightedSyncRate 세션 가중 평균. 완료 세션이 없으면 null
 * @param activeDays             운동한 날 수 (같은 날 두 번은 하루로 센다)
 */
public record WeeklyTotalsDto(
        long sessions,
        long totalReps,
        BigDecimal repWeightedSyncRate,
        BigDecimal sessionWeightedSyncRate,
        long activeDays
) {

    /** 기록이 하나도 없는 주. 문장 규칙이 «사실» 만 말하게 하는 분기점이다. */
    public static WeeklyTotalsDto empty() {
        return new WeeklyTotalsDto(0L, 0L, null, null, 0L);
    }

    public boolean isEmpty() {
        return sessions == 0;
    }

    /**
     * 두 평균의 차이(회차 가중 − 세션 가중). 한쪽이라도 없으면 null.
     *
     * <p>이 값이 <b>측정 결과 자체</b>다 — 「정의를 안 정하면 얼마나 어긋나는가」에 답한다.
     */
    public BigDecimal weightingGap() {
        if (repWeightedSyncRate == null || sessionWeightedSyncRate == null) return null;
        return repWeightedSyncRate.subtract(sessionWeightedSyncRate);
    }
}
