package com.shadowfit.service.Report;

import com.shadowfit.dto.report.weekly.WeeklyTotalsDto;
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
 */
@DisplayName("주간 문장 규칙")
class WeeklySentenceRulesTest {

    private static WeeklyTotalsDto totals(long sessions, long reps, String repWeighted, long activeDays) {
        BigDecimal rw = repWeighted == null ? null : new BigDecimal(repWeighted);
        return new WeeklyTotalsDto(sessions, reps, rw, rw, activeDays);
    }

    @Nested
    @DisplayName("기록이 없는 주")
    class Empty {

        @Test
        @DisplayName("한 문장으로 끝내고, 지난주와 비교하지 않는다")
        void 기록없음() {
            List<String> sentences = WeeklySentenceRules.build(
                    WeeklyTotalsDto.empty(), totals(3, 30, "85.00", 3));

            assertThat(sentences).hasSize(1);
            assertThat(sentences.get(0)).contains("기록이 없어요");
            // 🔴 「지난주보다 줄었어요」를 말하지 않는다 — 사실이지만 벌처럼 읽힌다.
            assertThat(sentences.get(0)).doesNotContain("지난주");
        }
    }

    @Nested
    @DisplayName("지난주와 비교")
    class Comparison {

        @Test
        @DisplayName("회차가 늘면 «많아요», 줄면 «적어요» — 방향만 말한다")
        void 회차_방향() {
            List<String> more = WeeklySentenceRules.build(
                    totals(3, 40, "85.00", 3), totals(3, 30, "85.00", 3));
            assertThat(String.join(" ", more)).contains("10회 많아요");

            List<String> less = WeeklySentenceRules.build(
                    totals(3, 20, "85.00", 3), totals(3, 30, "85.00", 3));
            assertThat(String.join(" ", less)).contains("10회 적어요");
        }

        @Test
        @DisplayName("싱크로율은 방향과 차이만 말하고 «좋다/나쁘다» 로 평가하지 않는다")
        void 싱크로율_평가하지_않는다() {
            List<String> up = WeeklySentenceRules.build(
                    totals(3, 30, "88.50", 3), totals(3, 30, "85.00", 3));

            String joined = String.join(" ", up);
            assertThat(joined).contains("3.5점 올랐어요");
            // 🔴 임계값이 필요한 말은 나오면 안 된다
            assertThat(joined).doesNotContain("무너");
            assertThat(joined).doesNotContain("좋아요");
            assertThat(joined).doesNotContain("잘하고");
        }

        @Test
        @DisplayName("지난주가 비어 있으면 비교하지 않고 사실만 말한다")
        void 지난주_없음() {
            List<String> sentences = WeeklySentenceRules.build(
                    totals(2, 20, "85.00", 2), WeeklyTotalsDto.empty());

            String joined = String.join(" ", sentences);
            assertThat(joined).contains("20회");
            assertThat(joined).doesNotContain("지난주");
        }

        @Test
        @DisplayName("값이 같으면 «같아요» 로 말한다 — 0.0 올랐다고 하지 않는다")
        void 변화_없음() {
            List<String> sentences = WeeklySentenceRules.build(
                    totals(3, 30, "85.00", 3), totals(3, 30, "85.00", 3));

            String joined = String.join(" ", sentences);
            assertThat(joined).contains("지난주와 같아요");
            assertThat(joined).doesNotContain("올랐");
            assertThat(joined).doesNotContain("내려");
        }
    }

    @Nested
    @DisplayName("표본이 작을 때")
    class SmallSample {

        @Test
        @DisplayName("세션이 1건이면 흐름을 말하지 않는다고 밝힌다")
        void 한_건() {
            List<String> sentences = WeeklySentenceRules.build(
                    totals(1, 10, "85.00", 1), totals(3, 30, "80.00", 3));

            assertThat(String.join(" ", sentences)).contains("흐름을 보기에는");
        }

        @Test
        @DisplayName("세션이 여럿이면 그 문장은 안 붙는다")
        void 여러_건() {
            List<String> sentences = WeeklySentenceRules.build(
                    totals(4, 40, "85.00", 4), totals(3, 30, "80.00", 3));

            assertThat(String.join(" ", sentences)).doesNotContain("흐름을 보기에는");
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

            List<String> sentences = WeeklySentenceRules.build(noAvg, totals(3, 30, "85.00", 3));

            String joined = String.join(" ", sentences);
            assertThat(joined).contains("2일");
            // 🔴 0점으로 둔갑시키지 않는다 — 측정 안 됨은 0 이 아니다
            assertThat(joined).doesNotContain("싱크로율");
            assertThat(joined).doesNotContain("0점");
        }
    }
}
