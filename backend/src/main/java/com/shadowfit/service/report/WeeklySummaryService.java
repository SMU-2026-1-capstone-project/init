package com.shadowfit.service.report;

import com.shadowfit.dto.report.weekly.RepCurvePointDto;
import com.shadowfit.dto.report.weekly.WeeklySummaryResponseDto;
import com.shadowfit.dto.report.weekly.WeeklyTotalsDto;
import com.shadowfit.dto.report.weekly.WorstRepFrequencyDto;
import com.shadowfit.global.observability.WeeklyReportMetrics;
import com.shadowfit.repository.report.WeeklySummaryQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * 주간 요약 — A층·B층 집계 + 규칙 문장. <b>LLM 없음, 저장 없음, 외부 의존 없음.</b>
 *
 * <p>설계: {@code docs/decisions/report-generation-llm.md} §13.
 * 이 서비스가 내는 문장은 나중에 LLM 이 다듬게 될 «원문» 이자, LLM 이 죽었을 때 그대로 나갈
 * <b>폴백</b>이다. 그래서 LLM 을 붙이기 <b>전에</b> 만든다 — 이것만으로 충분한지가 드러나야
 * 「LLM 이 값을 하는가」를 추측이 아니라 실물로 판단할 수 있다.
 *
 * <p><b>저장하지 않는 이유</b> — 저장은 «비싸거나 비결정적일 때» 필요한데 둘 다 아니다.
 * 세션 20~30행 집계이고, 같은 입력이면 같은 문장이 나온다. 지금 저장을 만들면
 * {@code reports.session_id NOT NULL} 을 풀어야 하는데, 그 벽은 LLM 을 붙일 때 만나면 된다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WeeklySummaryService {

    private final WeeklySummaryQueryRepository weeklySummaryQueryRepository;
    private final WeeklyReportMetrics weeklyReportMetrics;

    /**
     * 한 주의 요약. 기준일이 속한 주(월요일 시작)를 잡는다.
     *
     * <p><b>주 경계</b> — 월요일 00:00(포함) ~ 다음 월요일 00:00(미포함). 반열린 구간이라
     * 일요일 23:59 의 세션이 두 주에 겹치거나 빠지지 않는다.
     *
     * @param memberId  대상 회원
     * @param anyDayOfWeek 기준일. 그 날이 속한 주를 잡는다. null 이면 오늘
     */
    @Transactional(readOnly = true)
    public WeeklySummaryResponseDto getWeeklySummary(Long memberId, LocalDate anyDayOfWeek) {
        long callStart = System.nanoTime();

        LocalDate baseDate = anyDayOfWeek != null ? anyDayOfWeek : LocalDate.now();
        LocalDate start = baseDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate end = start.plusWeeks(1);
        LocalDate previousStart = start.minusWeeks(1);

        WeeklyTotalsDto thisWeek = weeklySummaryQueryRepository.totalsBetween(
                memberId, start.atStartOfDay(), end.atStartOfDay());
        WeeklyTotalsDto lastWeek = weeklySummaryQueryRepository.totalsBetween(
                memberId, previousStart.atStartOfDay(), start.atStartOfDay());

        // 🔴 두 평균이 얼마나 어긋나는지를 로그로 남긴다. 「이번 주 싱크로율」의 정의(회차 가중
        //    ↔ 세션 가중)는 아직 미결정이고(report-generation-llm.md §4), 그 결정을 «고르는» 게
        //    아니라 «재서» 하려면 실사용에서 둘의 차이가 얼마인지를 알아야 한다.
        //    합성 데이터로는 알 수 없는 값이라 실사용 로그가 유일한 출처다.
        if (thisWeek.weightingGap() != null) {
            log.info("주간 요약 가중치 차이 - 회원 {} · 기간 {} · 회차가중 {} · 세션가중 {} · 차이 {}",
                    memberId, start, thisWeek.repWeightedSyncRate(),
                    thisWeek.sessionWeightedSyncRate(), thisWeek.weightingGap());
        }

        // B층(회차 곡선·worst 분포)은 이번 주 회차가 없으면 어차피 빈 결과라 그때는 안 부른다 —
        // reports 는 세션당 여러 회차를 JSON_TABLE 로 펼치는 조회라, 부를 이유가 없는 주에도 매번
        // 돌리면 A층이 이미 «완료 세션 0» 이라 답한 것과 같은 결론을 한 번 더 스캔해서 얻는 셈이다.
        List<RepCurvePointDto> repCurve = thisWeek.isEmpty()
                ? List.of()
                : weeklySummaryQueryRepository.repCurveBetween(memberId, start.atStartOfDay(), end.atStartOfDay());
        List<WorstRepFrequencyDto> worstDistribution = thisWeek.isEmpty()
                ? List.of()
                : weeklySummaryQueryRepository.worstRepDistributionBetween(memberId, start.atStartOfDay(), end.atStartOfDay());

        WeeklySentenceRules.Result result = WeeklySentenceRules.build(thisWeek, lastWeek, repCurve, worstDistribution);

        // 관측(설계 §13-5) — 지연·세션 수 분포·규칙별 발화. 셋 다 이 조회 자체를 위해 추가
        // 스캔을 만들지 않는다(이미 손에 있는 값을 잰다).
        weeklyReportMetrics.sessionsInWindow(thisWeek.sessions());
        result.firedRules().forEach(weeklyReportMetrics::ruleFired);
        weeklyReportMetrics.queryLatency(Duration.ofNanos(System.nanoTime() - callStart));

        return new WeeklySummaryResponseDto(start, end, thisWeek, lastWeek, result.sentences());
    }
}
