package com.shadowfit.repository.report;

import com.shadowfit.dto.report.weekly.RepCurvePointDto;
import com.shadowfit.dto.report.weekly.WeeklyTotalsDto;
import com.shadowfit.dto.report.weekly.WorstRepFrequencyDto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 주간 요약 집계 — 두 층으로 갈린다.
 *
 * <p>설계: {@code docs/decisions/report-generation-llm.md} §13-2.
 * <ul>
 *   <li><b>A층</b>({@link #totalsBetween}) — {@code exercise_sessions} 한 표만 읽는다</li>
 *   <li><b>B층</b>({@link #repCurveBetween}, {@link #worstRepDistributionBetween}) —
 *       {@code reports.detailed_analysis} 를 {@code JSON_TABLE} 로 펼친다</li>
 * </ul>
 */
public interface WeeklySummaryQueryRepository {

    /**
     * 한 회원의 한 기간 집계 (A층).
     *
     * @param memberId 대상 회원
     * @param from     시작(<b>포함</b>)
     * @param to       끝(<b>미포함</b>) — 주 경계에서 하루가 두 주에 겹치지 않게 반열린 구간으로 받는다
     * @return 완료 세션이 없으면 {@link WeeklyTotalsDto#empty()} (null 을 돌려주지 않는다)
     */
    WeeklyTotalsDto totalsBetween(Long memberId, LocalDateTime from, LocalDateTime to);

    /**
     * 회차 위치별 평균 싱크로율 곡선 (B층 Q2). 「몇 번째부터 흔들리나」의 재료.
     *
     * @return 회차 오름차순. 측정된 회차가 하나도 없으면 빈 리스트
     */
    List<RepCurvePointDto> repCurveBetween(Long memberId, LocalDateTime from, LocalDateTime to);

    /**
     * worst 회차 분포 (B층 Q3). 「어느 회차가 반복해서 약한가」의 재료.
     *
     * @return 빈도 내림차순(동률은 회차 번호 오름차순으로 깬다). worst 가 기록된 세션이 하나도
     *         없으면 빈 리스트
     */
    List<WorstRepFrequencyDto> worstRepDistributionBetween(Long memberId, LocalDateTime from, LocalDateTime to);
}
