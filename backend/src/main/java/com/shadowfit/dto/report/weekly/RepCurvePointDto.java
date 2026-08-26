package com.shadowfit.dto.report.weekly;

import java.math.BigDecimal;

/**
 * 주간 «B층» — 회차(rep) 위치별 평균 싱크로율 한 점.
 *
 * <p>{@code reports.detailed_analysis} 의 {@code repTrend} 를 기간 내 세션 전체에서 모아
 * {@code repNumber} 별로 묶은 값이다({@code JSON_TABLE}, 설계 §13-2 Q2). A층과 달리
 * <b>측정된 회차만</b> 모으므로 {@link #sampleCount} 가 그 회차의 «진짜» 표본 수다.
 *
 * @param repNumber    회차 번호 (1부터)
 * @param avgSyncRate  그 회차 위치의 평균 싱크로율
 * @param sampleCount  그 회차가 몇 세션에서 측정됐는가
 */
public record RepCurvePointDto(int repNumber, BigDecimal avgSyncRate, long sampleCount) {
}
