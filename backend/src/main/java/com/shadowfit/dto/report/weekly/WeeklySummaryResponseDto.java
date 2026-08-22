package com.shadowfit.dto.report.weekly;

import java.time.LocalDate;
import java.util.List;

/**
 * 주간 요약 응답 — 숫자(집계)와 문장(규칙)을 <b>같이</b> 내려보낸다.
 *
 * <p><b>왜 문장을 서버가 만드나</b> — 화면마다 다르게 쓰면 같은 데이터가 다른 말을 하게 된다.
 * 그리고 이 문장들은 나중에 LLM 이 다듬게 될 «원문» 이기도 하다: LLM 이 죽거나 한도를 넘으면
 * 여기 있는 문장이 그대로 나간다({@code report-generation-llm.md} §9 — LLM 은 필수 경로에 두지
 * 않는다). 즉 이 필드는 <b>폴백이자 기준선</b>이다.
 *
 * <p><b>이 응답은 저장되지 않는다.</b> 조회 시점에 계산한다 — 저장이 필요한 조건(비싸거나
 * 비결정적이거나)에 둘 다 해당하지 않기 때문이다(§13-0). 세션 20~30행 집계이고, 같은 입력이면
 * 같은 문장이 나온다. 저장은 LLM 을 붙여 «비결정적» 이 되는 시점에 필요해진다.
 *
 * @param periodStart  주 시작일(포함)
 * @param periodEnd    주 종료일(<b>미포함</b>) — 경계 조건을 이름에 담아 호출부가 헷갈리지 않게 한다
 * @param thisWeek     이번 주 집계
 * @param lastWeek     지난주 집계. 비교 문장의 근거이고, 기록이 없으면 {@link WeeklyTotalsDto#empty()}
 * @param sentences    규칙이 만든 한국어 문장들(우선순위 순). 없으면 빈 리스트
 */
public record WeeklySummaryResponseDto(
        LocalDate periodStart,
        LocalDate periodEnd,
        WeeklyTotalsDto thisWeek,
        WeeklyTotalsDto lastWeek,
        List<String> sentences
) {
}
