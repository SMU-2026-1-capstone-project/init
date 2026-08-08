#!/usr/bin/env python3
"""
rig A 시딩 템플릿(`_pose_template`) 재생성기 — SQL 출력.

## 왜 있나

`README.md` 의 rig A 는 **세션 601 의 실데이터 ~750행을 cross join** 해 375만 행을 만드는
절차인데, 그 템플릿 원본이 **사라졌다**(2026-08-08 확인: 로컬 `pose_data` 0행).
Flyway 도입(#115)으로 initdb 마운트가 없어지면서 볼륨이 초기화된 것으로 보인다.

문서는 "재현하려면 인프라를 다시 띄워야 함"이라고만 적어놨지만, 실제로는
**인프라뿐 아니라 데이터 원본도 없어져 있었다.** 이 스크립트가 그 구멍을 메운다.

## 원본과 무엇이 같고 다른가

| | 원본 세션 601 | 이 스크립트 |
|---|---|---|
| `joint_coordinates` 크기 | 33 랜드마크 JSON ~2.3KB | 33 랜드마크 JSON **2,076B(≈2.03KB)** — 실측 |
| `joint_coordinates` 모양 | `{"landmarks":[...]}` 로 추정 | **루트가 배열** `[{x,y,z,visibility},...]` |
| `rep_number` | **전부 0** (생기기 전 데이터) | **전부 0** — 일부러 맞춤 |
| `smoothed_knee_angle` | **전부 0** | **전부 0** — 일부러 맞춤 |
| 값 분포 | 단일 세션 복제 | 단일 템플릿 복제 |

⚠️ **정직 단서 1 — 크기**: 원본과 바이트 단위로 같지 않다. MySQL 에 적재한 뒤
`AVG(LENGTH(joint_coordinates))` 로 재면 **2,076B** 로, README 가 적은 원본 ~2.3KB 보다
**약 10% 작다**(같은 지표로 비교한 값이다). 이 스크립트가 콘솔에 찍는 `≈1.77KB/row` 는
MySQL JSON 정규화 **이전의 raw 텍스트** 길이라 비교 기준이 다르니 혼동하지 말 것.

🔴 **정직 단서 2 — 모양이 다르다. `measure_json.sh` 는 이 템플릿으로 못 돌린다.**
`loadtest/measure_json.sh`(트림 33→13 실험, realmysql-experiments §4 ④)는
`JSON_EXTRACT(joint_coordinates,'$.landmarks[i]')` 로 읽는데, 이 템플릿은 루트가 배열이라
그 경로가 **NULL 이다 — 에러가 아니라 조용히 NULL** 이라 결과표가 빈 채로 나온다.
쓰려면 `$.landmarks[i]` → `$[i]` 로 바꿔야 하고, 그러면 2026-06-07 측정치(−60.9%)와
같은 substrate 가 아니게 된다. **이 스크립트가 메우는 건 rig A 의 «부피»(cross join 시딩)
까지이고, 트림 실험의 substrate 까지는 아니다.**

⚠️ 현재 ai-server(`app/api/endpoints/pose.py:33`)는 `index` 키를 포함한 **5키** 배열을 쓴다.
즉 이 템플릿은 원본 601 과도, 지금 프로덕션 경로와도 키 구성이 다르다.

⚠️ `rep_number`·`smoothed_knee_angle` 이 0 인 것은 **버그가 아니라 원본 재현**이다.
README rig A 경고 그대로 — 이 데이터로는 리포트 경로(worst 구간·회차별 추이)가 null 로
떨어진다. 조회 비용 실험에는 영향 없다.

## 실행 검증 (2026-08-08)

로컬 `shadowfit-mysql` 에서 실제로 돌려 확인한 것:

- 750행 적재, `JSON_VALID` 750/750, `JSON_LENGTH` 전 행 33
- `rep_number`·`smoothed_knee_angle` 전 행 0, `timestamp_sec` 0.0~74.9
- README rig A **2단계 cross join 을 3세션에 대해 실행** → `pose_data` 2,250행 적재 성공
  (`decimal(10,3)`·`decimal(5,2)` 캐스팅, 파티션 라우팅 모두 이상 없음). 확인 후 정리함
- ❌ 375만 행 전체 시딩은 **하지 않았다** — 소요 시간·디스크는 여전히 미측정

## 사용

    python gen_pose_template.py --rows 750 --out template.sql
    docker exec -i shadowfit-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit' < template.sql

그 뒤 README rig A 의 **2단계(cross join)를 그대로** 실행하면 된다 — 이 스크립트가
`_pose_template` 을 직접 만들므로 세션 601 원본이 없어도 된다.
"""
import argparse

# gen_batch.py 의 FEEDBACK_TYPES 와 동일 (스쿼트 결함 8종, 빈 문자열 = 정상)
FEEDBACK_TYPES = [
    "", "", "KNEE_OUT", "BACK_BENT", "HIP_HIGH", "KNEE_IN", "", "KNEE_OUT",
]


def make_landmarks(seed: int) -> str:
    """33 랜드마크 JSON 문자열. gen_batch.py 와 **같은 식**이라 값이 재현된다."""
    parts = []
    for i in range(33):
        base = (seed * 31 + i * 7) % 1000 / 1000.0
        x = round(0.30 + base * 0.40, 6)
        y = round(0.20 + ((base * 17) % 1.0) * 0.60, 6)
        z = round(-0.25 + ((base * 13) % 1.0) * 0.50, 6)
        v = round(0.85 + ((base * 11) % 1.0) * 0.15, 6)
        parts.append(f'{{"x":{x},"y":{y},"z":{z},"visibility":{v}}}')
    return "[" + ",".join(parts) + "]"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--rows", type=int, default=750,
                    help="템플릿 행 수. 원본 세션 601 이 ~750행이었다")
    ap.add_argument("--out", default="template.sql")
    ap.add_argument("--chunk", type=int, default=100,
                    help="INSERT 한 문장에 묶을 행 수 (max_allowed_packet 회피)")
    args = ap.parse_args()

    lines = [
        "-- gen_pose_template.py 산출물 — rig A 시딩 템플릿",
        "-- 원본(세션 601)이 소실돼 합성으로 재생성한 것이다. 헤더 주석 참고.",
        "DROP TABLE IF EXISTS _pose_template;",
        "CREATE TABLE _pose_template (",
        "  timestamp_sec        DOUBLE,",
        "  joint_coordinates    JSON,",
        "  sync_rate            DOUBLE,",
        "  rep_number           INT,",
        "  smoothed_knee_angle  DOUBLE,",
        "  feedback_message     VARCHAR(255)",
        ");",
    ]

    cols = ("INSERT INTO _pose_template "
            "(timestamp_sec, joint_coordinates, sync_rate, rep_number, "
            "smoothed_knee_angle, feedback_message) VALUES")

    values = []
    for f in range(args.rows):
        ts = round(f * 0.1, 1)
        jc = make_landmarks(f).replace("\\", "\\\\").replace("'", "\\'")
        sync = round(45.0 + (f * 7 % 50), 2)
        fb = FEEDBACK_TYPES[f % len(FEEDBACK_TYPES)]
        # rep_number·smoothed_knee_angle 은 0 — 원본 601 재현(헤더 참고)
        values.append(f"({ts},'{jc}',{sync},0,0,'{fb}')")

    for i in range(0, len(values), args.chunk):
        lines.append(cols + "\n" + ",\n".join(values[i:i + args.chunk]) + ";")

    sql = "\n".join(lines) + "\n"
    with open(args.out, "w", encoding="utf-8") as fp:
        fp.write(sql)

    size_mb = len(sql.encode("utf-8")) / 1024 / 1024
    per_row = len(make_landmarks(0).encode("utf-8")) / 1024
    print(f"wrote {args.out}: rows={args.rows} size={size_mb:.2f}MB "
          f"(joint_coordinates≈{per_row:.2f}KB/row)")


if __name__ == "__main__":
    main()
