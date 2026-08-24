package com.shadowfit.service.report;

import com.shadowfit.dto.report.weekly.RepCurvePointDto;
import com.shadowfit.dto.report.weekly.WeeklyTotalsDto;
import com.shadowfit.dto.report.weekly.WorstRepFrequencyDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주간 문장 규칙 테스트.
 *
 * <p>규칙이 순수 함수라 스프링이 필요 없다 — 조건 조합만 늘어놓으면 된다.
 *
 * <p><b>여기서 지키려는 것은 문구가 아니라 «말하지 않기» 다.</b> 임계값에 근거가 없으므로
 * 「무너진다」·「좋아요」처럼 정도를 평가하는 말이 문장에 섞이면 안 된다
 * ({@code report-generation-llm.md} §13-3). 문구는 바뀔 수 있고 그때 이 테스트도 바뀌지만,
 * «평가하지 않는다» 는 규칙은 바뀌지 않는다.
 *
 * <p>A층(사실·방향) 테스트는 B층 인자(회차 곡선·worst 분포)를 빈 리스트로 넘긴다 — A층 규칙과
 * 무관하다는 것 자체가 그 규칙들이 서로 독립이라는 뜻이다.
 */
@DisplayName("주간 문장 규칙")
class WeeklySentenceRulesTest {

    private static WeeklyTotalsDto totals(long sessions, long reps, String repWeighted, long activeDays) {
        BigDecimal rw = repWeighted == null ? null : new BigDecimal(repWeighted);
        return new WeeklyTotalsDto(sessions, reps, rw, rw, activeDays);
    }

    /** A층만 보는 테스트를 위한 축약 — B층 재료가 없는 주와 같다. */
    private static List<String> build(WeeklyTotalsDto thisWeek, WeeklyTotalsDto lastWeek) {
        return WeeklySentenceRules.build(thisWeek, lastWeek, List.of(), List.of()).sentences();
    }

    private static RepCurvePointDto point(int repNumber, String avgSyncRate, long sampleCount) {
        return new RepCurvePointDto(repNumber, new BigDecimal(avgSyncRate), sampleCount);
    }

    @Nested
    @DisplayName("기록이 없는 주")
    class Empty {

        @Test
        @DisplayName("한 문장으로 끝내고, 지난주와 비교하지 않는다")
        void 기록없음() {
            List<String> sentences = build(WeeklyTotalsDto.empty(), totals(3, 30, "85.00", 3));

            assertThat(sentences).hasSize(1);
            assertThat(sentences.get(0)).contains("기록이 없어요");
            // 🔴 「지난주보다 줄었어요」를 말하지 않는다 — 사실이지만 벌처럼 읽힌다.
            assertThat(sentences.get(0)).doesNotContain("지난주");
        }

        @Test
        @DisplayName("발화한 규칙은 NO_RECORD 하나뿐이다 — 관측(§13-5)이 «어느 분기인가» 를 구분한다")
        void 발화한_규칙() {
            WeeklySentenceRules.Result result = WeeklySentenceRules.build(
                    WeeklyTotalsDto.empty(), totals(3, 30, "85.00", 3), List.of(), List.of());

            assertThat(result.firedRules()).containsExactly(WeeklySentenceRuleId.NO_RECORD);
        }
    }

    @Nested
    @DisplayName("지난주와 비교")
    class Comparison {

        @Test
        @DisplayName("회차가 늘면 «많아요», 줄면 «적어요» — 방향만 말한다")
        void 회차_방향() {
            WeeklySentenceRules.Result more = WeeklySentenceRules.build(
                    totals(3, 40, "85.00", 3), totals(3, 30, "85.00", 3), List.of(), List.of());
            assertThat(String.join(" ", more.sentences())).contains("10회 많아요");
            assertThat(more.firedRules()).contains(WeeklySentenceRuleId.REP_COUNT_UP);

            WeeklySentenceRules.Result less = WeeklySentenceRules.build(
                    totals(3, 20, "85.00", 3), totals(3, 30, "85.00", 3), List.of(), List.of());
            assertThat(String.join(" ", less.sentences())).contains("10회 적어요");
            assertThat(less.firedRules()).contains(WeeklySentenceRuleId.REP_COUNT_DOWN);
        }

        @Test
        @DisplayName("싱크로율은 방향과 차이만 말하고 «좋다/나쁘다» 로 평가하지 않는다")
        void 싱크로율_평가하지_않는다() {
            WeeklySentenceRules.Result up = WeeklySentenceRules.build(
                    totals(3, 30, "88.50", 3), totals(3, 30, "85.00", 3), List.of(), List.of());

            String joined = String.join(" ", up.sentences());
            assertThat(joined).contains("3.5점 올랐어요");
            // 🔴 임계값이 필요한 말은 나오면 안 된다
            assertThat(joined).doesNotContain("무너");
            assertThat(joined).doesNotContain("좋아요");
            assertThat(joined).doesNotContain("잘하고");
            assertThat(up.firedRules()).contains(WeeklySentenceRuleId.SYNC_RATE_UP);

            WeeklySentenceRules.Result down = WeeklySentenceRules.build(
                    totals(3, 30, "80.00", 3), totals(3, 30, "85.00", 3), List.of(), List.of());
            assertThat(String.join(" ", down.sentences())).contains("내려갔어요");
            assertThat(down.firedRules()).contains(WeeklySentenceRuleId.SYNC_RATE_DOWN);
        }

        @Test
        @DisplayName("지난주가 비어 있으면 비교하지 않고 사실만 말한다")
        void 지난주_없음() {
            WeeklySentenceRules.Result result = WeeklySentenceRules.build(
                    totals(2, 20, "85.00", 2), WeeklyTotalsDto.empty(), List.of(), List.of());

            String joined = String.join(" ", result.sentences());
            assertThat(joined).contains("20회");
            assertThat(joined).doesNotContain("지난주");
            assertThat(result.firedRules()).contains(
                    WeeklySentenceRuleId.REP_COUNT_NO_LAST_WEEK, WeeklySentenceRuleId.SYNC_RATE_NO_LAST_WEEK);
        }

        @Test
        @DisplayName("값이 같으면 «같아요» 로 말한다 — 0.0 올랐다고 하지 않는다")
        void 변화_없음() {
            WeeklySentenceRules.Result result = WeeklySentenceRules.build(
                    totals(3, 30, "85.00", 3), totals(3, 30, "85.00", 3), List.of(), List.of());

            String joined = String.join(" ", result.sentences());
            assertThat(joined).contains("지난주와 같아요");
            assertThat(joined).doesNotContain("올랐");
            assertThat(joined).doesNotContain("내려");
            assertThat(result.firedRules())
                    .contains(WeeklySentenceRuleId.REP_COUNT_SAME, WeeklySentenceRuleId.SYNC_RATE_SAME);
        }
    }

    @Nested
    @DisplayName("표본이 작을 때")
    class SmallSample {

        @Test
        @DisplayName("세션이 1건이면 흐름을 말하지 않는다고 밝힌다")
        void 한_건() {
            WeeklySentenceRules.Result result = WeeklySentenceRules.build(
                    totals(1, 10, "85.00", 1), totals(3, 30, "80.00", 3), List.of(), List.of());

            assertThat(String.join(" ", result.sentences())).contains("흐름을 보기에는");
            assertThat(result.firedRules()).contains(WeeklySentenceRuleId.SMALL_SAMPLE);
        }

        @Test
        @DisplayName("세션이 여럿이면 그 문장은 안 붙는다")
        void 여러_건() {
            WeeklySentenceRules.Result result = WeeklySentenceRules.build(
                    totals(4, 40, "85.00", 4), totals(3, 30, "80.00", 3), List.of(), List.of());

            assertThat(String.join(" ", result.sentences())).doesNotContain("흐름을 보기에는");
            assertThat(result.firedRules()).doesNotContain(WeeklySentenceRuleId.SMALL_SAMPLE);
        }
    }

    @Nested
    @DisplayName("평균이 없는 주")
    class NoAverage {

        @Test
        @DisplayName("측정된 회차가 없어 평균이 null 이면 싱크로율 문장을 만들지 않는다")
        void 평균_없음() {
            // 세션은 있는데 avg_sync_rate 가 전부 null 인 경우 — 측정 전에 끝난 세션들
            WeeklyTotalsDto noAvg = new WeeklyTotalsDto(2, 0, null, null, 2);

            List<String> sentences = build(noAvg, totals(3, 30, "85.00", 3));

            String joined = String.join(" ", sentences);
            assertThat(joined).contains("2일");
            // 🔴 0점으로 둔갑시키지 않는다 — 측정 안 됨은 0 이 아니다
            assertThat(joined).doesNotContain("싱크로율");
            assertThat(joined).doesNotContain("0점");
        }
    }

    @Nested
    @DisplayName("B층 — 회차 곡선의 순위")
    class RepCurveRanking {

        @Test
        @DisplayName("최댓값 대비 낙폭이 가장 큰 회차를 짚고, CURVE_DROP_RANK 로 발화가 잡힌다")
        void 낙폭이_가장_큰_회차() {
            List<RepCurvePointDto> curve = List.of(
                    point(1, "90.00", 3), point(2, "70.00", 3), point(3, "85.00", 3));

            WeeklySentenceRules.Result result = WeeklySentenceRules.build(
                    totals(3, 30, "82.00", 3), WeeklyTotalsDto.empty(), curve, List.of());

            assertThat(String.join(" ", result.sentences())).contains("2회차");
            assertThat(result.firedRules()).contains(WeeklySentenceRuleId.CURVE_DROP_RANK);
        }

        @Test
        @DisplayName("점이 하나뿐이면 «낙폭» 을 말하지 않는다")
        void 점이_하나면_말하지_않는다() {
            List<RepCurvePointDto> curve = List.of(point(1, "90.00", 3));

            WeeklySentenceRules.Result result = WeeklySentenceRules.build(
                    totals(1, 10, "90.00", 1), WeeklyTotalsDto.empty(), curve, List.of());

            assertThat(String.join(" ", result.sentences())).doesNotContain("낙폭");
            assertThat(result.firedRules()).doesNotContain(WeeklySentenceRuleId.CURVE_DROP_RANK);
        }

        @Test
        @DisplayName("곡선이 완전히 평평하면 «가장 큰» 이라는 거짓 우열을 말하지 않는다")
        void 평평하면_말하지_않는다() {
            List<RepCurvePointDto> curve = List.of(
                    point(1, "85.00", 3), point(2, "85.00", 3), point(3, "85.00", 3));

            WeeklySentenceRules.Result result = WeeklySentenceRules.build(
                    totals(3, 30, "85.00", 3), WeeklyTotalsDto.empty(), curve, List.of());

            assertThat(String.join(" ", result.sentences())).doesNotContain("낙폭");
            assertThat(result.firedRules()).doesNotContain(WeeklySentenceRuleId.CURVE_DROP_RANK);
        }
    }

    @Nested
    @DisplayName("B층 — worst 회차 분포")
    class WorstDistribution {

        @Test
        @DisplayName("가장 자주 worst 였던 회차와 횟수를 말하고, WORST_REP_DISTRIBUTION 으로 발화가 잡힌다")
        void 최다_worst_회차() {
            List<WorstRepFrequencyDto> distribution = List.of(
                    new WorstRepFrequencyDto(4, 2), new WorstRepFrequencyDto(1, 1));

            WeeklySentenceRules.Result result = WeeklySentenceRules.build(
                    totals(3, 30, "82.00", 3), WeeklyTotalsDto.empty(), List.of(), distribution);

            String joined = String.join(" ", result.sentences());
            assertThat(joined).contains("3세션 중 2번은 4회차가 가장 약했어요");
            assertThat(result.firedRules()).contains(WeeklySentenceRuleId.WORST_REP_DISTRIBUTION);
        }

        @Test
        @DisplayName("worst 기록이 없으면 분포 문장을 만들지 않는다")
        void 분포_없음() {
            WeeklySentenceRules.Result result = WeeklySentenceRules.build(
                    totals(3, 30, "82.00", 3), WeeklyTotalsDto.empty(), List.of(), List.of());

            assertThat(String.join(" ", result.sentences())).doesNotContain("가장 약했어요");
            assertThat(result.firedRules()).doesNotContain(WeeklySentenceRuleId.WORST_REP_DISTRIBUTION);
        }
    }
}
