package com.shadowfit.repository.report;

import com.shadowfit.dto.report.weekly.WeeklyTotalsDto;

import java.time.LocalDateTime;

/**
 * 주간 요약의 «A층» 집계 — {@code exercise_sessions} 한 표만 읽는다.
 *
 * <p>설계: {@code docs/decisions/report-generation-llm.md} §13-2.
 * B층({@code reports.detailed_analysis} 를 {@code JSON_TABLE} 로 펼치는 회차별 곡선)은 여기 없다 —
 * 계획을 재보기 전에는 붙이지 않는다.
 */
public interface WeeklySummaryQueryRepository {

    /**
     * 한 회원의 한 기간 집계.
     *
     * @param memberId 대상 회원
     * @param from     시작(<b>포함</b>)
     * @param to       끝(<b>미포함</b>) — 주 경계에서 하루가 두 주에 겹치지 않게 반열린 구간으로 받는다
     * @return 완료 세션이 없으면 {@link WeeklyTotalsDto#empty()} (null 을 돌려주지 않는다)
     */
    WeeklyTotalsDto totalsBetween(Long memberId, LocalDateTime from, LocalDateTime to);
}
