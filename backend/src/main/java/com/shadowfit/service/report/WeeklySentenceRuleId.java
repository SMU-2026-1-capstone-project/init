package com.shadowfit.service.report;

/**
 * {@link WeeklySentenceRules} 가 낼 수 있는 문장 하나하나의 식별자.
 *
 * <p><b>왜 필요한가</b> — 설계 §13-5 관측: "규칙별 발화 횟수 → 한 번도 안 터지는 규칙을
 * 찾아낸다." 코드가 있어도 실사용에서 그 분기를 아무도 안 밟을 수 있다는 것은 이미 [#193]이
 * 준 교훈이다. 문장 텍스트(포맷된 문자열)로는 "어느 분기가 발화했는가"를 지표 태그로 셀 수
 * 없어서 — 숫자가 문장 안에 섞여 있다 — 분기마다 안정된 이름을 따로 둔다.
 *
 * <p>이름은 {@link WeeklySentenceRules#build} 안의 번호 주석(①②③④⑤⑥)과 1:1 대응한다.
 * 규칙 문구가 바뀌어도(문구는 잠정이다) 이 이름은 «어느 분기인가» 만 가리키므로 바뀌지 않는다.
 */
public enum WeeklySentenceRuleId {
    /** 이번 주 기록이 없어 그 사실 하나로 끝낸 경우. */
    NO_RECORD,
    /** ① 사실 — 운동한 날·세션 수. 기록이 있으면 항상 발화한다. */
    FACT_ACTIVE_DAYS,
    /** ② 사실 — 지난주 기록이 없어 비교하지 않고 총 회차만 말한 경우. */
    REP_COUNT_NO_LAST_WEEK,
    /** ② 방향 — 회차 수가 지난주보다 늘었다. */
    REP_COUNT_UP,
    /** ② 방향 — 회차 수가 지난주보다 줄었다. */
    REP_COUNT_DOWN,
    /** ② 방향 — 회차 수가 지난주와 같다. */
    REP_COUNT_SAME,
    /** ③ 사실 — 지난주 평균이 없어 방향 없이 이번 주 값만 말한 경우. */
    SYNC_RATE_NO_LAST_WEEK,
    /** ③ 방향 — 싱크로율이 지난주보다 올랐다. */
    SYNC_RATE_UP,
    /** ③ 방향 — 싱크로율이 지난주보다 내려갔다. */
    SYNC_RATE_DOWN,
    /** ③ 방향 — 싱크로율이 지난주와 같다. */
    SYNC_RATE_SAME,
    /** ④ 사실 — 세션이 1건뿐이라 흐름을 말하지 않는다고 밝히는 경우. */
    SMALL_SAMPLE,
    /** ⑤ 순위(B층) — 회차 곡선에서 최댓값 대비 낙폭이 가장 큰 지점. */
    CURVE_DROP_RANK,
    /** ⑥ 분포(B층) — 가장 자주 worst 였던 회차와 그 횟수. */
    WORST_REP_DISTRIBUTION
}
