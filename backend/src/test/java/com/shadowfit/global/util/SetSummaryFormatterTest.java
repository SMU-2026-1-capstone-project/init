package com.shadowfit.global.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 세트 요약 표기 단위 테스트 — #69 회귀 방지.
 *
 * <p>리포트(ReportService)와 주간/일별(SessionService)이 각자 리터럴을 들고 있다가
 * "1세트"/"0세트"로 갈렸던 결함이 있었다. 두 호출부가 이 포매터 하나만 쓰도록 모았으므로
 * 표기 규칙 검증도 여기 한 곳에서 한다.
 */
class SetSummaryFormatterTest {

    @Test
    @DisplayName("세트 수는 1로 고정된다 — BE-09 전까지 세트 개념이 스키마에 없음")
    void 세트수는_1로_고정() {
        assertThat(SetSummaryFormatter.format(12)).isEqualTo("1세트 x 12회");
    }

    @Test
    @DisplayName("totalReps가 null이면 0회로 표기한다 — total_reps 컬럼이 nullable")
    void null이면_0회() {
        assertThat(SetSummaryFormatter.format(null)).isEqualTo("1세트 x 0회");
    }

    @Test
    @DisplayName("반복 0회도 그대로 표기된다")
    void 반복_0회() {
        assertThat(SetSummaryFormatter.format(0)).isEqualTo("1세트 x 0회");
    }
}
