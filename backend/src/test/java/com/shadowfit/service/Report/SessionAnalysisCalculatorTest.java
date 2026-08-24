package com.shadowfit.service.Report;

import com.shadowfit.dto.report.PoseFrameProjection;
import com.shadowfit.dto.report.detailreport.RepSyncRateDto;
import com.shadowfit.dto.report.detailreport.WorstSectionDto;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionAnalysisCalculator 단위 테스트 — 읽기(ReportService)·쓰기(SessionService.applyComplete,
 * precompute-on-write) 양쪽이 공유하는 순수 계산 로직(report-read-path.md §9-1).
 *
 * <p>이슈 #78 로 슬라이딩 윈도우 → rep 단위로 바뀌면서 전면 재작성했다. 픽스처는 실제 데이터
 * 형태를 따른다: <b>같은 rep 의 프레임은 sync_rate 가 같다</b>(ai-server 가 rep 단위로 채점해
 * 복제 전송). 조회도 rep 오름차순이므로 입력을 그렇게 만든다.
 */
@DisplayName("SessionAnalysisCalculator 테스트")
class SessionAnalysisCalculatorTest {

    private SessionAnalysisCalculator calculator;
    private Session session;

    @BeforeEach
    void setUp() {
        calculator = new SessionAnalysisCalculator();

        Category category = Category.builder().id(1L).name("LOWER").build();
        Exercise exercise = Exercise.builder()
                .id(1L)
                .name("스쿼트")
                .category(category)
                .expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00"))
                .syncThresholdAdvanced(new BigDecimal("85.00"))
                .build();

        session = Session.builder()
                .id(1L)
                .exercise(exercise)
                .startTime(LocalDateTime.now())
                .status(Status.COMPLETED)
                .build();
    }

    /**
     * 무릎각이 rep 안에서 일정한 프레임. <b>어느 rep 이 worst 인가</b>만 보는 테스트용이다.
     *
     * <p>각도가 전부 같으면 대표 프레임 선택이 동률이 되어 먼저 나온 프레임이 남는다. 대표 프레임
     * 선택 자체를 검증하는 테스트는 아래 {@link #frame(int, double, Double, Double)} 로 각도를
     * 명시해 따로 둔다 — 실데이터를 반영하지 않는 픽스처가 코드 경로를 덮는 일이 이미 한 번
     * 있었으므로(#79) 두 관심사를 섞지 않는다.
     */
    private PoseFrameProjection frame(int repNumber, double timestampSec, Double syncRate) {
        return frame(repNumber, timestampSec, syncRate, 100.0);
    }

    /** 무릎각을 명시한 프레임. 작을수록 깊게 앉은 것이다(0 = 미상). */
    private PoseFrameProjection frame(
            int repNumber, double timestampSec, Double syncRate, Double smoothedKneeAngle) {
        return new PoseFrameProjection(timestampSec, syncRate, repNumber, smoothedKneeAngle);
    }

    @Test
    @DisplayName("poseFrames가 null이면 null 반환")
    void nullFrames_returnsNull() {
        assertThat(calculator.calculate(session, null)).isNull();
    }

    @Test
    @DisplayName("poseFrames가 비어 있으면 null 반환")
    void emptyFrames_returnsNull() {
        assertThat(calculator.calculate(session, List.of())).isNull();
    }

    @Test
    @DisplayName("싱크로율이 가장 낮은 rep을 worst로 선정")
    void selectsLowestRep() {
        List<PoseFrameProjection> frames = List.of(
                frame(1, 0.0, 90.0),
                frame(1, 0.5, 90.0),
                frame(2, 1.0, 40.0),
                frame(2, 1.5, 40.0),
                frame(3, 2.0, 75.0)
        );

        WorstSectionDto result = calculator.calculate(session, frames);

        assertThat(result).isNotNull();
        assertThat(result.getExerciseName()).isEqualTo("스쿼트");
        assertThat(result.getRepNumber()).isEqualTo(2);
        assertThat(result.getReason()).isEqualTo("2회차 · 싱크로율 40%");
    }

    @Test
    @DisplayName("★ 행이 3개 미만인 짧은 rep도 worst로 뽑힌다 (#78 결함 2 회귀)")
    void shortRepIsStillEligible() {
        // 이슈 #78 본문의 시나리오 그대로. 예전 3프레임 윈도우로는 rep1(2행)이 자기 값만으로
        // 윈도우를 채우지 못해 [20,20,25]=21.7 이 되고, rep3 내부 윈도우 [21,21,21]=21.0 이
        // 더 낮아 rep3 이 뽑혔다. 실제로 가장 나쁜 건 rep1(20)이다.
        List<PoseFrameProjection> frames = List.of(
                frame(1, 0.0, 20.0),
                frame(1, 0.5, 20.0),          // ← 2행뿐인 짧은 rep
                frame(2, 1.0, 25.0),
                frame(2, 1.5, 25.0),
                frame(2, 2.0, 25.0),
                frame(3, 3.0, 21.0),
                frame(3, 3.5, 21.0),
                frame(3, 4.0, 21.0)
        );

        WorstSectionDto result = calculator.calculate(session, frames);

        assertThat(result).isNotNull();
        assertThat(result.getReason()).isEqualTo("1회차 · 싱크로율 20%");
    }

    @Test
    @DisplayName("★ 보고되는 값은 실재하는 rep의 점수다 — rep 경계를 걸친 평균이 나오지 않는다 (#78 결함 3)")
    void reportedValueBelongsToARealRep() {
        // 예전 윈도우는 rep1 끝 2행 + rep2 첫 1행을 묶어 (30+30+60)/3=40 처럼
        // 어느 rep 의 것도 아닌 값을 낼 수 있었다.
        List<PoseFrameProjection> frames = List.of(
                frame(1, 0.0, 30.0),
                frame(1, 0.5, 30.0),
                frame(2, 1.0, 60.0),
                frame(2, 1.5, 60.0)
        );

        WorstSectionDto result = calculator.calculate(session, frames);

        assertThat(result).isNotNull();
        // 30(rep1) 또는 60(rep2) 중 하나여야 하고, 그 사이 값(40 등)이면 경계를 넘은 것이다
        assertThat(result.getReason()).isEqualTo("1회차 · 싱크로율 30%");
    }

    @Test
    @DisplayName("rep_number가 0(미상)인 행만 있으면 null 반환 — 서로 다른 rep을 뭉뚱그리지 않는다")
    void allRepNumbersUnknown_returnsNull() {
        // 컬럼이 생기기 전 저장분·구버전 AI 의 행. 값이 있어도 어느 rep 의 것인지 말할 수 없다.
        List<PoseFrameProjection> frames = List.of(
                frame(0, 0.0, 30.0),
                frame(0, 0.5, 40.0),
                frame(0, 1.0, 50.0)
        );

        assertThat(calculator.calculate(session, frames)).isNull();
    }

    @Test
    @DisplayName("rep_number가 0인 행은 제외하고 나머지로 계산한다")
    void unknownRepRowsAreExcluded() {
        List<PoseFrameProjection> frames = List.of(
                frame(0, 0.0, 10.0),          // ← 미상. 제일 낮지만 후보가 아니다
                frame(1, 1.0, 55.0),
                frame(2, 2.0, 80.0)
        );

        WorstSectionDto result = calculator.calculate(session, frames);

        assertThat(result).isNotNull();
        assertThat(result.getReason()).isEqualTo("1회차 · 싱크로율 55%");
    }

    @Test
    @DisplayName("syncRate가 전부 null인 rep은 후보에서 배제")
    void repWithAllNullSyncRateIsExcluded() {
        List<PoseFrameProjection> frames = Arrays.asList(
                frame(1, 0.0, null),
                frame(1, 0.5, null),
                frame(2, 1.0, 70.0)
        );

        WorstSectionDto result = calculator.calculate(session, frames);

        assertThat(result).isNotNull();
        assertThat(result.getReason()).isEqualTo("2회차 · 싱크로율 70%");
    }

    @Test
    @DisplayName("모든 rep의 syncRate가 null이면 null 반환")
    void allSyncRatesNull_returnsNull() {
        List<PoseFrameProjection> frames = Arrays.asList(
                frame(1, 0.0, null),
                frame(2, 1.0, null)
        );

        assertThat(calculator.calculate(session, frames)).isNull();
    }

    @Test
    @DisplayName("rep 안에 null이 섞이면 non-null만으로 평균 — rep 자체를 버리지 않는다")
    void partialNullSyncRate_averagesNonNullOnly() {
        List<PoseFrameProjection> frames = Arrays.asList(
                frame(1, 0.0, 40.0),
                frame(1, 0.5, null),          // 무시
                frame(1, 1.0, 40.0),
                frame(2, 2.0, 90.0)
        );

        WorstSectionDto result = calculator.calculate(session, frames);

        assertThat(result).isNotNull();
        assertThat(result.getReason()).isEqualTo("1회차 · 싱크로율 40%");
    }

    @Test
    @DisplayName("★ 대표 timestamp는 worst rep에서 가장 깊게 앉은 프레임 (§4-ㄹ)")
    void representativeTimestamp_isDeepestFrameOfWorstRep() {
        // 중앙(1:15)이 아니라 무릎각 최소(1:20)가 뽑혀야 한다. 예전 코드는 배열 중앙을 골랐는데
        // 그게 스쿼트의 바닥이라는 근거가 없었다 — 올라오는 속도가 다르면 중앙은 바닥이 아니다.
        List<PoseFrameProjection> frames = List.of(
                frame(1, 10.0, 90.0, 150.0),
                frame(2, 70.0, 50.0, 130.0),  // 1:10 — 내려가는 중
                frame(2, 75.0, 50.0, 115.0),  // 1:15 — 중앙이지만 바닥 아님
                frame(2, 80.0, 50.0, 95.0),   // 1:20 — 바닥(대표)
                frame(2, 85.0, 50.0, 120.0)   // 1:25 — 올라오는 중
        );

        WorstSectionDto result = calculator.calculate(session, frames);

        assertThat(result.getTimeStamp()).isEqualTo("01:20");
    }

    /**
     * 이 계산기는 {@code timestamp_sec} 이 <b>세션 시작 기준 경과 초</b> 라는 전제 위에 있다 (#156).
     *
     * <p>예전에는 그 전제가 깨져 있었다. 실시간 경로의 클라가 {@code Date.now() / 1000}(epoch)를
     * 보냈고, 변환이 한 군데도 없어 그 값이 그대로 여기까지 왔다. 재현해보니 {@code "01:15"} 대신
     * <b>{@code "29770991:08"}</b> 이 나왔다 — 에러가 아니라 <b>조용히 틀린 표시</b>다.
     *
     * <p>지금은 서버(ai-server)가 도착 시각으로 경과를 만든다. 이 테스트는 그 계약을 <b>소비처
     * 쪽에서</b> 고정한다 — 생산자가 다시 epoch 를 흘려보내면 여기서 걸린다.
     */
    @Test
    @DisplayName("★ 시각은 세션 시작 기준 경과 초다 — epoch 가 들어오면 표시가 깨진다 (#156)")
    void timestampIsElapsedSeconds_notEpoch() {
        // 정상: 서버가 만든 경과 초
        List<PoseFrameProjection> elapsed = List.of(
                frame(1, 70.0, 50.0, 130.0),
                frame(1, 75.0, 50.0, 95.0),   // 바닥 = 대표
                frame(1, 80.0, 50.0, 120.0)
        );
        assertThat(calculator.calculate(session, elapsed).getTimeStamp()).isEqualTo("01:15");

        // 회귀 감시: 같은 순간을 epoch 로 주면 «분» 자리가 세션 길이와 무관한 크기가 된다.
        // 어떤 운동도 2천만 분을 넘지 않으므로, 이 검사가 곧 «origin 이 뒤바뀌었다» 의 탐지기다.
        double epochBase = 1_786_259_393.0;
        List<PoseFrameProjection> epoch = List.of(
                frame(1, epochBase + 70.0, 50.0, 130.0),
                frame(1, epochBase + 75.0, 50.0, 95.0),
                frame(1, epochBase + 80.0, 50.0, 120.0)
        );
        int minutes = Integer.parseInt(
                calculator.calculate(session, epoch).getTimeStamp().split(":")[0]);
        assertThat(minutes)
                .as("epoch 가 흘러들면 분 자리가 폭발한다 — 이 값이 정상 범위면 origin 이 섞인 것이다")
                .isGreaterThan(1_000_000);
    }

    @Test
    @DisplayName("무릎각이 전부 0(미상)이면 예전처럼 중앙 프레임 — 고를 근거가 없을 때는 임의로 고르지 않는다")
    void representativeTimestamp_fallsBackToMiddleWhenAngleUnknown() {
        // 구버전 AI 가 보낸 행, 그리고 컬럼 도입 이전 행이 여기 해당한다.
        List<PoseFrameProjection> frames = List.of(
                frame(1, 70.0, 50.0, 0.0),    // 1:10
                frame(1, 75.0, 50.0, 0.0),    // 1:15 ← 중앙
                frame(1, 80.0, 50.0, 0.0)     // 1:20
        );

        WorstSectionDto result = calculator.calculate(session, frames);

        assertThat(result.getTimeStamp()).isEqualTo("01:15");
    }

    @Test
    @DisplayName("미상(0)과 유효값이 섞이면 유효값 중 최소 — 0이 '가장 깊은 값'으로 이기지 않는다")
    void representativeTimestamp_ignoresUnknownAngleWhenValidExists() {
        // 0 을 그냥 최소값으로 다루면 배포 전환기(구버전·신버전 행 혼재)에 미상 프레임이 항상 이겨
        // 실제로 가장 깊은 프레임이 밀려난다.
        List<PoseFrameProjection> frames = List.of(
                frame(1, 70.0, 50.0, 0.0),    // 1:10 — 미상. 후보 아님
                frame(1, 75.0, 50.0, 95.0),   // 1:15 — 유효값 중 최소(대표)
                frame(1, 80.0, 50.0, 120.0)   // 1:20
        );

        WorstSectionDto result = calculator.calculate(session, frames);

        assertThat(result.getTimeStamp()).isEqualTo("01:15");
    }

    @Test
    @DisplayName("무릎각이 동률이면 먼저 나온(시간이 이른) 프레임 — 같은 입력이면 결과가 항상 같다")
    void representativeTimestamp_tiePrefersEarlierFrame() {
        // 바닥에서 잠깐 멈춰 같은 각도가 이어지는 경우다.
        List<PoseFrameProjection> frames = List.of(
                frame(1, 70.0, 50.0, 95.0),   // 1:10 ← 먼저 나온 쪽
                frame(1, 75.0, 50.0, 95.0),
                frame(1, 80.0, 50.0, 95.0)
        );

        WorstSectionDto result = calculator.calculate(session, frames);

        assertThat(result.getTimeStamp()).isEqualTo("01:10");
    }

    @Test
    @DisplayName("동률이면 먼저 나온(번호가 작은) rep을 고른다 — 같은 입력이면 결과가 항상 같다")
    void tie_prefersEarlierRep() {
        List<PoseFrameProjection> frames = List.of(
                frame(1, 0.0, 45.0),
                frame(2, 1.0, 45.0),
                frame(3, 2.0, 45.0)
        );

        WorstSectionDto result = calculator.calculate(session, frames);

        assertThat(result.getReason()).isEqualTo("1회차 · 싱크로율 45%");
    }

    @Test
    @DisplayName("reason에 싱크로율의 동어반복이 붙지 않는다 (#80)")
    void reason_hasNoTautologicalFeedback() {
        // 예전엔 feedback_message 최빈값을 덧붙여 "싱크로율 21% · 즉시 자세 수정 필요" 였는데,
        // 그 메시지가 sync_rate 를 임계값과 비교한 결과라 뒷부분이 앞부분의 함수였다.
        List<PoseFrameProjection> frames = List.of(
                frame(1, 0.0, 21.0),
                frame(1, 0.5, 21.0)
        );

        WorstSectionDto result = calculator.calculate(session, frames);

        assertThat(result.getReason()).isEqualTo("1회차 · 싱크로율 21%");
        assertThat(result.getReason())
                .doesNotContain("자세 양호")
                .doesNotContain("자세 보정 필요")
                .doesNotContain("즉시 자세 수정 필요");
    }

    // ── 회차별 추이 (calculateRepTrend) ──────────────────────────────────────

    @Test
    @DisplayName("추이는 rep 오름차순으로 회차마다 한 점")
    void repTrend_isOnePointPerRepInOrder() {
        List<PoseFrameProjection> frames = List.of(
                frame(1, 0.0, 80.0),
                frame(1, 0.5, 80.0),
                frame(2, 1.0, 75.0),
                frame(3, 2.0, 91.5)
        );

        List<RepSyncRateDto> trend = calculator.calculateRepTrend(frames);

        assertThat(trend).extracting(RepSyncRateDto::getRepNumber).containsExactly(1, 2, 3);
        assertThat(trend).extracting(RepSyncRateDto::getSyncRate).containsExactly(80.0, 75.0, 91.5);
    }

    @Test
    @DisplayName("★ worstSection.repNumber로 추이의 worst 점을 찾을 수 있다 — 문자열 파싱 불필요")
    void worstIsLinkedToTrendByRepNumber() {
        List<PoseFrameProjection> frames = List.of(
                frame(1, 0.0, 80.0),
                frame(2, 1.0, 75.0),
                frame(3, 2.0, 91.5)
        );

        WorstSectionDto worst = calculator.calculate(session, frames);
        List<RepSyncRateDto> trend = calculator.calculateRepTrend(frames);

        RepSyncRateDto matched = trend.stream()
                .filter(point -> point.getRepNumber() == worst.getRepNumber())
                .findFirst()
                .orElseThrow();

        // worst 는 정의상 추이의 최솟값이어야 한다 — 두 계산이 같은 그룹핑을 쓰므로 어긋날 수 없다
        assertThat(matched.getSyncRate())
                .isEqualTo(trend.stream().mapToDouble(RepSyncRateDto::getSyncRate).min().orElseThrow());
        assertThat(matched.getTimeStamp()).isEqualTo(worst.getTimeStamp());
    }

    @Test
    @DisplayName("추이의 timeStamp도 그 rep에서 가장 깊게 앉은 프레임 — worst와 같은 기준")
    void repTrend_timestampIsDeepestFrame() {
        // worst 와 다른 기준을 쓰면 같은 rep 인데 두 곳의 시각이 어긋난다. 같은 헬퍼를 쓴다.
        List<PoseFrameProjection> frames = List.of(
                frame(1, 70.0, 50.0, 130.0),  // 1:10
                frame(1, 75.0, 50.0, 115.0),  // 1:15 — 중앙
                frame(1, 80.0, 50.0, 95.0)    // 1:20 — 바닥(대표)
        );

        assertThat(calculator.calculateRepTrend(frames))
                .singleElement()
                .satisfies(point -> assertThat(point.getTimeStamp()).isEqualTo("01:20"));
    }

    @Test
    @DisplayName("syncRate가 전부 null인 rep은 추이에 점을 찍지 않는다 (구멍으로 남김)")
    void repTrend_skipsRepWithoutSyncRate() {
        List<PoseFrameProjection> frames = Arrays.asList(
                frame(1, 0.0, 60.0),
                frame(2, 1.0, null),    // ← 점 없음
                frame(3, 2.0, 70.0)
        );

        assertThat(calculator.calculateRepTrend(frames))
                .extracting(RepSyncRateDto::getRepNumber).containsExactly(1, 3);
    }

    @Test
    @DisplayName("rep_number가 0(미상)인 행은 추이에서도 제외")
    void repTrend_excludesUnknownRep() {
        List<PoseFrameProjection> frames = List.of(
                frame(0, 0.0, 10.0),
                frame(1, 1.0, 55.0)
        );

        assertThat(calculator.calculateRepTrend(frames))
                .extracting(RepSyncRateDto::getRepNumber).containsExactly(1);
    }

    @Test
    @DisplayName("측정된 rep이 없으면 빈 리스트 (null 아님)")
    void repTrend_emptyWhenNothingMeasured() {
        assertThat(calculator.calculateRepTrend(null)).isEmpty();
        assertThat(calculator.calculateRepTrend(List.of())).isEmpty();
        assertThat(calculator.calculateRepTrend(List.of(frame(0, 0.0, 50.0)))).isEmpty();
    }

    @Test
    @DisplayName("syncRate는 소수 1자리로 반올림")
    void repTrend_roundsToOneDecimal() {
        // rep 안 평균이 (70.0 + 71.0 + 71.0) / 3 = 70.666... → 70.7
        List<PoseFrameProjection> frames = List.of(
                frame(1, 0.0, 70.0),
                frame(1, 0.5, 71.0),
                frame(1, 1.0, 71.0)
        );

        assertThat(calculator.calculateRepTrend(frames))
                .singleElement()
                .satisfies(point -> assertThat(point.getSyncRate()).isEqualTo(70.7));
    }
}
