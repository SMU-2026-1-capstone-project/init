package com.shadowfit.global.util;

/**
 * 세트 요약 문자열("1세트 x 12회") 생성 — 리포트/주간/일별 응답이 같은 표기를 쓰도록 한 곳으로 모음.
 *
 * <p>세트 개념이 아직 스키마에 없어 세트 수는 1로 고정한다. 표기 규칙 출처는
 * {@code docs/decisions/report-aggregation.md} 결정 5.
 *
 * <p>과거 {@code ReportService}는 "1세트", {@code SessionService}는 "0세트"로 각자 리터럴을 들고 있어
 * 같은 세션이 화면마다 다르게 보이는 결함이 있었다(#69). BE-09(세트 도입) 때 {@code Session.setCount}
 * 로 교체할 자리도 여기 한 곳이 되도록 유지할 것.
 */
public class SetSummaryFormatter {
    private static final int FIXED_SET_COUNT = 1;

    private SetSummaryFormatter() {
    }

    /**
     * @param totalReps 총 반복 수. {@code exercise_sessions.total_reps}가 nullable이라 null이 올 수 있음
     *                  (nullable 컬럼 + JPA 로딩 경로). null이면 0회로 표기한다.
     */
    public static String format(Integer totalReps) {
        int reps = totalReps == null ? 0 : totalReps;
        return String.format("%d세트 x %d회", FIXED_SET_COUNT, reps);
    }
}
