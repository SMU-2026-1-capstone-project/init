package com.shadowfit.model.exercise;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.DoubleSummaryStatistics;

/**
 * 세션의 싱크로율 통계 셋(avg·max·min).
 *
 * <p><b>왜 셋을 묶는가</b> — 이 세 값은 항상 같이 정해지고 같이 저장된다. 따로 두면 "avg 만 쓰고
 * max/min 은 안 썼다" 가 가능해지는데, 그게 실제로 일어났던 결함이다 — AI 가 proto 로 max/min 을
 * 보내는데 저장할 때 {@code set} 을 안 해 컬럼이 항상 NULL 이었다(이슈 #75). 묶어두면 그 누락이
 * 컴파일 단계에서 불가능해진다.
 *
 * <p><b>"측정 안 됨" 은 0 이 아니라 {@link #none()} 이다.</b> 0 으로 저장하면 "싱크로율 0%" 라는
 * 실제 값이 되어 월 평균을 끌어내린다. 읽는 쪽의 방어({@code filter(Objects::nonNull)})는 null 만
 * 걸러내므로 저장된 0.0 은 못 막는다 — 그래서 애초에 0 을 쓰지 않는다(#75, 커밋 {@code 0914082}).
 */
public record SyncStats(BigDecimal avg, BigDecimal max, BigDecimal min) {

    private static final SyncStats NONE = new SyncStats(null, null, null);

    /** 측정된 rep 이 없다. 세 컬럼 모두 {@code null} 로 남는다. */
    public static SyncStats none() {
        return NONE;
    }

    public static SyncStats of(double avg, double max, double min) {
        return new SyncStats(scale(avg), scale(max), scale(min));
    }

    /** rep 별 평균들의 요약 통계로부터. 빈 통계에는 쓰지 말 것 — 호출부가 먼저 걸러야 한다. */
    public static SyncStats from(DoubleSummaryStatistics stats) {
        return of(stats.getAverage(), stats.getMax(), stats.getMin());
    }

    /** 컬럼이 {@code DECIMAL(5,2)} 라 저장 전에 맞춰 둔다 — 반올림 위치를 DB 에 맡기지 않는다. */
    private static BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
