package com.shadowfit.service.report;

import com.shadowfit.dto.report.weekly.RepCurvePointDto;
import com.shadowfit.dto.report.weekly.WeeklyTotalsDto;
import com.shadowfit.dto.report.weekly.WorstRepFrequencyDto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 주간 요약 문장 카탈로그 — 집계값을 한국어 문장으로 바꾼다.
 *
 * <p><b>순수 함수다.</b> 입력이 같으면 출력이 같고 의존이 없다. 그래서 테스트가 조건 조합만
 * 늘어놓으면 되고, 나중에 LLM 이 이 문장들을 다듬게 되더라도 <b>이쪽은 폴백으로 그대로 남는다</b>
 * ({@code report-generation-llm.md} §9 — LLM 은 필수 경로에 두지 않는다).
 *
 * <h2>🔴 임계값을 쓰지 않는다</h2>
 *
 * 「{@code n}점 이상 떨어지면 <b>무너진다</b>」 같은 문장이 자연스럽지만, 그 {@code n} 에 근거가
 * 없다. 이 프로젝트는 근거 없는 기준값을 넣지 않는다 — baseline(사실)도 threshold(약속)도 아직
 * 없으므로 <b>비운다</b>. 그래서 1차 규칙은 네 종류만 쓴다:
 *
 * <ul>
 *   <li><b>사실</b> — 「{@code n}일 {@code m}세션 운동했어요」</li>
 *   <li><b>방향</b> — 「지난주보다 올랐어요/내렸어요」 (부호만 본다)</li>
 *   <li><b>순위</b> — 「가장 ~한 것은 X」 (비교만 한다, B층 회차 곡선)</li>
 *   <li><b>분포</b> — 「{@code n}번 중 {@code m}번」 (세기만 한다, B층 worst 회차)</li>
 * </ul>
 *
 * 「얼마나 심한가」를 말하는 문장은 <b>보류</b>다. 실사용 데이터가 쌓여 분포에서 기준값을 뽑을
 * 수 있게 되면 그때 추가한다({@code report-generation-llm.md} §13-3).
 *
 * <h2>어느 평균을 인용하나</h2>
 *
 * 화면에 쓰는 값은 <b>회차 가중</b>이다 — AI 가 세션 점수를 낼 때 쓰는 기준과 같아서, 두 시스템이
 * 다른 수를 말하지 않는다. 다만 그 선택 자체가 아직 미결정이므로({@code §4}) 여기서는
 * <b>정의를 문장에 담지 않고</b> 값만 말한다. 정의가 정해지면 문구를 그때 붙인다.
 */
public final class WeeklySentenceRules {

    private WeeklySentenceRules() {
    }

    /**
     * {@link #build} 의 결과 — 문장과, 그 문장들을 만든 규칙의 식별자.
     *
     * <p>식별자를 같이 돌려주는 이유는 오직 관측이다({@code report-generation-llm.md} §13-5) —
     * 호출부가 «어느 규칙이 발화했는가» 를 문장 텍스트 파싱 없이 지표로 셀 수 있게 한다.
     * 이 레코드 자체는 여전히 순수 데이터라 {@link #build} 의 순수성을 해치지 않는다.
     *
     * @param sentences  우선순위 순 문장들
     * @param firedRules 그 문장들을 낸 규칙, 발화 순서대로
     */
    public record Result(List<String> sentences, List<WeeklySentenceRuleId> firedRules) {
    }

    /**
     * @param thisWeek        이번 주 집계 (A층)
     * @param lastWeek        지난주 집계 (A층, 기록이 없으면 {@link WeeklyTotalsDto#empty()})
     * @param repCurve        이번 주 회차 위치별 평균 싱크로율 곡선 (B층, 회차 오름차순)
     * @param worstDistribution 이번 주 worst 회차 분포 (B층, 빈도 내림차순)
     * @return 우선순위 순 문장들과 발화한 규칙. 기록이 없으면 그 사실을 말하는 문장 하나
     */
    public static Result build(WeeklyTotalsDto thisWeek, WeeklyTotalsDto lastWeek,
                                List<RepCurvePointDto> repCurve,
                                List<WorstRepFrequencyDto> worstDistribution) {
        List<String> sentences = new ArrayList<>();
        List<WeeklySentenceRuleId> fired = new ArrayList<>();

        // ── 기록 없음 — 여기서 끝낸다.
        //    「0회 했어요」·「지난주보다 줄었어요」 같은 문장은 사실이지만 읽는 사람에게 벌처럼
        //    들린다. 운동 앱에서 «안 한 주» 는 흔하고, 그 주에 굳이 말을 얹지 않는다.
        if (thisWeek.isEmpty()) {
            sentences.add("이번 주에는 완료된 운동 기록이 없어요.");
            fired.add(WeeklySentenceRuleId.NO_RECORD);
            return new Result(sentences, fired);
        }

        // ── ① 사실 — 얼마나 했나
        sentences.add("이번 주 %d일 동안 %d번 운동했어요.".formatted(thisWeek.activeDays(), thisWeek.sessions()));
        fired.add(WeeklySentenceRuleId.FACT_ACTIVE_DAYS);

        // ── ② 사실 + 방향 — 회차 수
        long repDiff = thisWeek.totalReps() - lastWeek.totalReps();
        if (lastWeek.isEmpty()) {
            sentences.add("총 %d회를 채웠어요.".formatted(thisWeek.totalReps()));
            fired.add(WeeklySentenceRuleId.REP_COUNT_NO_LAST_WEEK);
        } else if (repDiff > 0) {
            sentences.add("총 %d회로 지난주보다 %d회 많아요.".formatted(thisWeek.totalReps(), repDiff));
            fired.add(WeeklySentenceRuleId.REP_COUNT_UP);
        } else if (repDiff < 0) {
            sentences.add("총 %d회로 지난주보다 %d회 적어요.".formatted(thisWeek.totalReps(), -repDiff));
            fired.add(WeeklySentenceRuleId.REP_COUNT_DOWN);
        } else {
            sentences.add("총 %d회로 지난주와 같아요.".formatted(thisWeek.totalReps()));
            fired.add(WeeklySentenceRuleId.REP_COUNT_SAME);
        }

        // ── ③ 방향 — 싱크로율. 🔴 «얼마나» 가 아니라 «어느 쪽» 만 말한다.
        //    차이값을 같이 보여주는 것은 사실 전달이라 괜찮지만, 그 크기를 «좋다/나쁘다» 로
        //    평가하지는 않는다 — 그러려면 기준값이 필요하고 아직 없다.
        BigDecimal now = thisWeek.repWeightedSyncRate();
        BigDecimal before = lastWeek.repWeightedSyncRate();
        if (now != null && before != null) {
            int direction = now.compareTo(before);
            BigDecimal gap = now.subtract(before).abs();
            if (direction > 0) {
                sentences.add("싱크로율은 %s점으로 지난주보다 %s점 올랐어요.".formatted(plain(now), plain(gap)));
                fired.add(WeeklySentenceRuleId.SYNC_RATE_UP);
            } else if (direction < 0) {
                sentences.add("싱크로율은 %s점으로 지난주보다 %s점 내려갔어요.".formatted(plain(now), plain(gap)));
                fired.add(WeeklySentenceRuleId.SYNC_RATE_DOWN);
            } else {
                sentences.add("싱크로율은 %s점으로 지난주와 같아요.".formatted(plain(now)));
                fired.add(WeeklySentenceRuleId.SYNC_RATE_SAME);
            }
        } else if (now != null) {
            sentences.add("싱크로율은 %s점이에요.".formatted(plain(now)));
            fired.add(WeeklySentenceRuleId.SYNC_RATE_NO_LAST_WEEK);
        }

        // ── ④ 사실 — 한 판만 있는 주에는 그렇게 말한다.
        //    🔴 이 앱에서 세션 하나는 «점» 이고 추세가 아니다. 「나아지고 있다」를 n=1 로 말하면
        //    안 된다 — 그건 이 프로젝트가 측정에서 지키는 규칙과 같은 규칙이다.
        if (thisWeek.sessions() == 1) {
            sentences.add("기록이 1번뿐이라 흐름을 보기에는 조금 일러요.");
            fired.add(WeeklySentenceRuleId.SMALL_SAMPLE);
        }

        // ── ⑤ 순위 (B층) — 곡선에서 최댓값 대비 낙폭이 가장 큰 지점.
        //    점이 하나면 «낙폭» 이 성립하지 않는다. 그리고 곡선이 완전히 평평하면(낙폭이 전부
        //    0) «가장 큰» 이 거짓 우열을 만든다 — 그럴 땐 문장을 내지 않는다.
        if (repCurve.size() >= 2) {
            BigDecimal peak = repCurve.stream()
                    .map(RepCurvePointDto::avgSyncRate)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
            RepCurvePointDto biggestDrop = repCurve.stream()
                    .max(Comparator.comparing(p -> peak.subtract(p.avgSyncRate())))
                    .orElse(null);
            if (biggestDrop != null && peak.subtract(biggestDrop.avgSyncRate()).signum() > 0) {
                sentences.add("곡선의 최댓값 대비 낙폭이 가장 큰 지점은 %d회차예요.".formatted(biggestDrop.repNumber()));
                fired.add(WeeklySentenceRuleId.CURVE_DROP_RANK);
            }
        }

        // ── ⑥ 분포 (B층) — worst 회차가 반복되는가. «정도» 가 아니라 «횟수» 만 말한다.
        if (!worstDistribution.isEmpty()) {
            WorstRepFrequencyDto top = worstDistribution.get(0);
            sentences.add("%d세션 중 %d번은 %d회차가 가장 약했어요."
                    .formatted(thisWeek.sessions(), top.count(), top.repNumber()));
            fired.add(WeeklySentenceRuleId.WORST_REP_DISTRIBUTION);
        }

        return new Result(sentences, fired);
    }

    /** 소수점 뒤 0 을 떼서 「85점」·「85.7점」처럼 읽히게 한다. */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
