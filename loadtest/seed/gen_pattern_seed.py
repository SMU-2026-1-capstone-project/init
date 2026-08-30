#!/usr/bin/env python3
"""
BE-07(패턴 분석) 검증용 "패턴이 있는" 합성 세션 시더 — SQL 출력.

## 왜 있나

`docs/decisions/pattern-analysis-implementation.md` §2: 기존 부하테스트 시딩(`seed_sessions.sql`
등)은 세션을 날짜·회원에 **균등 분산**한다 — 그런데 패턴 분석 3 endpoint(periodicity·
intensity-trend·consistency)의 존재 이유가 정확히 "분포가 불균일하다"는 것이다. 균등 데이터로는
세 endpoint 모두 "고르게 분포함"이라는 재미없는 결과만 내고, 그 자체로 검증도 안 된다.

이 스크립트는 **목적이 다른 별도 스크립트**다 (loadtest/seed/의 나머지는 RealMySQL 실험용
행수·payload 스케일링이 목적) — 여기 있는 건 부피가 아니라 **분포 모양**이다.

## 만드는 패턴 (전용 계정 1개, 최근 28일)

- **periodicity**: 화·목·토 저녁(19~21시)에 세션이 몰린다 (offset 7~27, 최근 1주 제외)
- **intensity-trend**: 4주 전→최근 주로 갈수록 avg_sync_rate가 오른다
  (약 60 → 68 → 76 → 88, 주차별 구간 평균)
- **consistency**: 최근 7일(offset 0~6)은 매일 세션이 있어 스트릭을 만들고, 그 앞
  3주는 화·목·토만 있어 나머지 요일이 결측일로 잡힌다

세 endpoint가 참조하는 컬럼(start_time·end_time·avg_sync_rate·status)만 정확히 채운다 —
total_reps·calories_burned 등 나머지는 화면 표시용 더미값이다.

⚠️ **day_offset은 "스크립트를 생성한 시점의 오늘"을 기준으로 요일을 계산해 고정한다** —
SQL 자체는 `CURDATE() - INTERVAL n DAY`로 실행 시점 기준 상대 날짜를 쓰지만, 어떤 offset이
화/목/토에 해당하는지는 생성 시점 기준이다. 생성한 날 바로 실행할 것 — 며칠 지나 실행하면
요일이 밀려 "화목토 집중"이 아니게 된다.

## 전제 — 계정은 이 스크립트가 만들지 않는다

`member_id`는 `(SELECT id FROM users WHERE email='<email>')` 서브쿼리로 해석한다.
계정 생성은 `POST /member/signup`으로 먼저 해둘 것(비밀번호를 이 스크립트가 알 필요가
없다 — 로그인은 검증 단계에서 별도로 함).

## 사용

    python gen_pattern_seed.py --out pattern_seed.sql
    docker exec -i shadowfit-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit' < pattern_seed.sql
"""
import argparse
import datetime

WEEKDAY_KOR = ["월", "화", "수", "목", "금", "토", "일"]

# 화(1)·목(3)·토(5) — Python weekday(): 월=0 ... 일=6
PATTERN_WEEKDAYS = {1, 3, 5}

# 주차별 avg_sync_rate 구간 (offset 0~6=최근주 ... 21~27=4주전) — 최근으로 올수록 상승
WEEK_SYNC_RANGE = {
    0: (85.0, 91.0),   # 최근 7일(스트릭)
    1: (75.0, 81.0),   # offset 7~13
    2: (68.0, 74.0),   # offset 14~20
    3: (58.0, 65.0),   # offset 21~27
}


def week_bucket(offset: int) -> int:
    return offset // 7


def sync_rate_for(offset: int, i: int) -> float:
    lo, hi = WEEK_SYNC_RANGE[week_bucket(offset)]
    # 같은 주 안에서도 살짝 들쭉날쭉하게(단조 증가 아님) — 완전히 매끈하면 합성 티가 남
    step = (hi - lo) / 4
    return round(lo + step * (i % 5), 2)


def plan_sessions(today: datetime.date) -> list[dict]:
    plan = []
    i = 0

    # 1) 최근 7일(offset 0~6) — 매일 1세션, consistency 스트릭용. 요일 편중 목적과는
    #    별개로 "최근 들어 매일 하는 습관이 생겼다"는 서사로 둔다.
    for offset in range(0, 7):
        d = today - datetime.timedelta(days=offset)
        hour, minute = 19, 30 - (offset % 3) * 10  # 19:00~19:30 사이 소폭 변주
        plan.append({
            "offset": offset, "hour": hour, "minute": max(minute, 0),
            "duration_min": 35, "status": "COMPLETED",
            "avg_sync": sync_rate_for(offset, i),
        })
        i += 1

    # 2) offset 7~27(3주) — 화·목·토만, 저녁 시간대(19~21시) 집중.
    for offset in range(7, 28):
        d = today - datetime.timedelta(days=offset)
        if d.weekday() not in PATTERN_WEEKDAYS:
            continue
        hour = 19 + (i % 3)  # 19/20/21시로 분산 — periodicity의 "시간대" 축도 같이 보여줌
        plan.append({
            "offset": offset, "hour": hour, "minute": 0,
            "duration_min": 40, "status": "COMPLETED",
            "avg_sync": sync_rate_for(offset, i),
        })
        i += 1

    # 3) 노이즈 — 화목토가 아닌 날에도 아주 가끔(2건) 세션이 있어야 "완전히 균일하게
    #    0"인 것보다 현실적이다. periodicity의 "그래도 화목토가 지배적"이라는 결론은
    #    안 흔들리는 양(전체 대비 소수)으로 제한한다.
    for offset in (10, 19):
        d = today - datetime.timedelta(days=offset)
        if d.weekday() in PATTERN_WEEKDAYS:
            continue  # 이미 위에서 만들었으면 건너뜀
        plan.append({
            "offset": offset, "hour": 8, "minute": 0,
            "duration_min": 20, "status": "COMPLETED",
            "avg_sync": sync_rate_for(offset, i),
        })
        i += 1

    return plan


def render_row(email: str, exercise_id: int, s: dict) -> str:
    start_expr = (f"TIMESTAMP(CURDATE() - INTERVAL {s['offset']} DAY, "
                  f"'{s['hour']:02d}:{s['minute']:02d}:00')")
    end_expr = f"({start_expr} + INTERVAL {s['duration_min']} MINUTE)"
    avg = s["avg_sync"]
    max_sync = round(min(avg + 6.0, 100.0), 2)
    min_sync = round(max(avg - 9.0, 0.0), 2)
    member_expr = f"(SELECT id FROM users WHERE email='{email}')"
    return (f"({member_expr}, {exercise_id}, 'pattern-seed', {start_expr}, {end_expr}, "
            f"25, {avg}, {max_sync}, {min_sync}, '{s['status']}', 0, {end_expr})")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--email", default="pattern-seed@test.com",
                    help="세션을 걸 계정 이메일 — 사전에 /member/signup 으로 만들어둘 것")
    ap.add_argument("--exercise-id", type=int, default=1, help="1=스쿼트(이 프로젝트 관례)")
    ap.add_argument("--out", default="pattern_seed.sql")
    args = ap.parse_args()

    today = datetime.date.today()
    plan = plan_sessions(today)

    cols = ("INSERT INTO exercise_sessions "
            "(member_id, exercise_id, reference_source, start_time, end_time, "
            "total_reps, avg_sync_rate, max_sync_rate, min_sync_rate, status, version, "
            "last_active_at) VALUES")
    rows = [render_row(args.email, args.exercise_id, s) for s in plan]

    lines = [
        "-- gen_pattern_seed.py 산출물 — BE-07 패턴 분석 검증용 (요일 편중·강도 상승·스트릭)",
        f"-- 생성 시점 기준 오늘: {today.isoformat()} ({WEEKDAY_KOR[today.weekday()]}) — "
        "요일 편중 offset은 이 날짜 기준으로 고정됐다. 며칠 지나 재실행하면 어긋난다.",
        f"-- 대상 계정: {args.email} (사전에 /member/signup 으로 생성돼 있어야 함)",
        "-- 기존 세션을 지우지 않는다 — dev-seed.sql 과 달리 이 계정 몫만 추가한다.",
        "DELETE s FROM exercise_sessions s JOIN users u ON s.member_id = u.id "
        f"WHERE u.email = '{args.email}' AND s.reference_source = 'pattern-seed';",
        cols + "\n" + ",\n".join(rows) + ";",
    ]

    sql = "\n".join(lines) + "\n"
    with open(args.out, "w", encoding="utf-8") as fp:
        fp.write(sql)

    weekday_counts: dict[str, int] = {}
    for s in plan:
        d = today - datetime.timedelta(days=s["offset"])
        k = WEEKDAY_KOR[d.weekday()]
        weekday_counts[k] = weekday_counts.get(k, 0) + 1
    print(f"wrote {args.out}: {len(plan)} sessions, 요일 분포={weekday_counts}")


if __name__ == "__main__":
    main()
