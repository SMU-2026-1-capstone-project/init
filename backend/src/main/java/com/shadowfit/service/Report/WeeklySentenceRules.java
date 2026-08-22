package com.shadowfit.service.Report;

import com.shadowfit.dto.report.weekly.WeeklyTotalsDto;

import java.math.BigDecimal;
import java.util.ArrayList;
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
 *   <li><b>순위</b> — 「가장 ~한 것은 X」 (비교만 한다)</li>
 *   <li><b>분포</b> — 「{@code n}번 중 {@code m}번」 (세기만 한다)</li>
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
     * @param thisWeek 이번 주 집계
     * @param lastWeek 지난주 집계 (기록이 없으면 {@link WeeklyTotalsDto#empty()})
     * @return 우선순위 순 문장들. 기록이 없으면 그 사실을 말하는 문장 하나
     */
    public static List<String> build(WeeklyTotalsDto thisWeek, WeeklyTotalsDto lastWeek) {
        List<String> sentences = new ArrayList<>();

        // ── 기록 없음 — 여기서 끝낸다.
        //    「0회 했어요」·「지난주보다 줄었어요」 같은 문장은 사실이지만 읽는 사람에게 벌처럼
        //    들린다. 운동 앱에서 «안 한 주» 는 흔하고, 그 주에 굳이 말을 얹지 않는다.
        if (thisWeek.isEmpty()) {
            sentences.add("이번 주에는 완료된 운동 기록이 없어요.");
            return sentences;
        }

        // ── ① 사실 — 얼마나 했나
        sentences.add("이번 주 %d일 동안 %d번 운동했어요.".formatted(thisWeek.activeDays(), thisWeek.sessions()));

        // ── ② 사실 + 방향 — 회차 수
        long repDiff = thisWeek.totalReps() - lastWeek.totalReps();
        if (lastWeek.isEmpty()) {
            sentences.add("총 %d회를 채웠어요.".formatted(thisWeek.totalReps()));
        } else if (repDiff > 0) {
            sentences.add("총 %d회로 지난주보다 %d회 많아요.".formatted(thisWeek.totalReps(), repDiff));
        } else if (repDiff < 0) {
            sentences.add("총 %d회로 지난주보다 %d회 적어요.".formatted(thisWeek.totalReps(), -repDiff));
        } else {
            sentences.add("총 %d회로 지난주와 같아요.".formatted(thisWeek.totalReps()));
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
            } else if (direction < 0) {
                sentences.add("싱크로율은 %s점으로 지난주보다 %s점 내려갔어요.".formatted(plain(now), plain(gap)));
            } else {
                sentences.add("싱크로율은 %s점으로 지난주와 같아요.".formatted(plain(now)));
            }
        } else if (now != null) {
            sentences.add("싱크로율은 %s점이에요.".formatted(plain(now)));
        }

        // ── ④ 사실 — 한 판만 있는 주에는 그렇게 말한다.
        //    🔴 이 앱에서 세션 하나는 «점» 이고 추세가 아니다. 「나아지고 있다」를 n=1 로 말하면
        //    안 된다 — 그건 이 프로젝트가 측정에서 지키는 규칙과 같은 규칙이다.
        if (thisWeek.sessions() == 1) {
            sentences.add("기록이 1번뿐이라 흐름을 보기에는 조금 일러요.");
        }

        return sentences;
    }

    /** 소수점 뒤 0 을 떼서 「85점」·「85.7점」처럼 읽히게 한다. */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
