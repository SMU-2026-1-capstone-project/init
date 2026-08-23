#!/usr/bin/env python3
"""ghz 다세션 데이터 생성기 — 병목 귀속 재측정용 (single-hot-session 아티팩트 제거).

단일 session_id=801 에 모든 INSERT 가 몰리면 인덱스 리프 페이지 래치·redo 커밋이
직렬화돼 가짜 천장이 생긴다(귀속 분석 2026-06-12). 실제 DAU 1,000 은 서로 다른
수천 세션이 각자 다른 인덱스 구간에 INSERT → 이 경합이 없다. 그래서 요청을
round-robin 으로 N개 세션에 흩는다.

─────────────────────────────────────────────────────────────────────────
🔴 2026-08-17: 배열을 버리고 **ghz 템플릿**으로 갈아탔다 (#271)

예전에는 «세션 N개짜리 메시지 배열» 을 만들고 ghz 가 `RequestNumber % len` 으로
순환하게 했다. 그런데 그 방식은 **같은 세션에 두 번째로 가는 요청이 첫 요청과
바이트 단위로 같다.** 2026-08-17 에 들어온 멱등 키
(`uk_pose_event(session_id, rep_number, timestamp_sec, created_at)`, #188)가
그 중복을 삼키면서 **세션당 첫 요청만 행을 만들게 됐다** — 그런데
`ON DUPLICATE KEY UPDATE` 는 에러가 아니라 성공이라 `fail=0` 에 RPS 도 정상으로
찍힌다. 표를 봐서는 안 보인다.

그래서 유니크 키 네 열 중 하나(`rep_number`)를 **요청 번호로 움직인다.**
세션 라우팅도 배열 순환에서 템플릿 산술로 옮겼다 — `mod` 가 하던 일이 같아서
결과는 같고, 데이터가 **메시지 1개(≈54KB)** 로 줄어든다.

크기가 왜 중요한가: ghz 는 **데이터에 템플릿이 있으면 캐시를 끄고 요청마다 전체를
다시 파싱한다**(`runner/data.go`). 배열(100세션 ≈ 5.4MB)에 템플릿을 얹으면
649 RPS 에서 3.5GB/s 가 되어 성립하지 않는다.

⚠️ 출력은 **JSON 이 아니라 Go 템플릿**이다. 사람이 열면 `{{ }}` 가 보이고
   `json.load` 로는 안 읽힌다 — 그게 정상이다. ghz 가 실행 후 파싱한다.
설계: docs/decisions/loadtest-payload-uniqueness.md
─────────────────────────────────────────────────────────────────────────

사용:
  python gen_batch_multi.py --sessions 901-1000 --reps 25 --out batch_multi.json
  (901~1000 = seed 세션 100개. DB 에 존재해야 한다 — 없으면 FK 검증에서 전 건 실패)
"""
import argparse
import json

# json.dumps 를 거친 뒤 템플릿으로 바꿔치기할 자리. 값이 **숫자 자리**라 따옴표째 지운다.
SESSION_SLOT = "@@GHZ_SESSION@@"
REPNUM_SLOT = "@@GHZ_REPNUM@@"


FEEDBACK_TYPES = ["", "", "KNEE_OUT", "BACK_BENT", "HIP_HIGH", "KNEE_IN", "", "KNEE_OUT"]


def make_landmarks(seed: int) -> str:
    landmarks = []
    for i in range(33):
        base = (seed * 31 + i * 7) % 1000 / 1000.0
        landmarks.append({
            "x": round(0.30 + base * 0.40, 6),
            "y": round(0.20 + ((base * 17) % 1.0) * 0.60, 6),
            "z": round(-0.25 + ((base * 13) % 1.0) * 0.50, 6),
            "visibility": round(0.85 + ((base * 11) % 1.0) * 0.15, 6),
        })
    return json.dumps(landmarks, separators=(",", ":"))


def make_pose_data(reps: int) -> list:
    pose = []
    for f in range(reps):
        pose.append({
            "timestampSec": round(f * 0.1, 1),
            "jointCoordinates": make_landmarks(f),
            "syncRate": round(45.0 + (f * 7 % 50), 2),
            "feedbackMessage": FEEDBACK_TYPES[f % len(FEEDBACK_TYPES)],
            # 🔴 멱등 키를 움직이는 열이다(#271). 프레임끼리는 같은 값이어도 된다 —
            #    같은 요청 안에서는 timestamp_sec 이 이미 서로 다르기 때문이다.
            #    요청 사이에서 달라지는 것이 요점이다.
            "repNumber": REPNUM_SLOT,
        })
    return pose


def parse_sessions(spec: str) -> list:
    lo, hi = spec.split("-")
    return list(range(int(lo), int(hi) + 1))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--sessions", default="901-1900", help="세션 id 범위 lo-hi (DB 존재 필수)")
    ap.add_argument("--reps", type=int, default=25, help="batch 내 프레임 수 = R")
    ap.add_argument("--out", default="batch_multi.json")
    # 🔴 #276 ② 전용. 기본값(끄기)이면 위 #271 설명 그대로 «요청마다 새 키» 다.
    #    켜면 rep_number 를 0 으로 **고정**해서, 같은 세션으로 가는 요청이 바이트 단위로
    #    같아진다 = 재전송이 원본과 겹치는 조건. 그게 데드락이 열리는 유일한 조건이라는 것이
    #    2026-08-23 라운드의 판정이다(loadtest/results/r276-newkeys-aws-2026-08-23).
    #    ⚠️ 이 페이로드로 잰 «저장된 행» 은 세션당 R 행에서 멈춘다 — 그게 정상이고,
    #       #271 이 고친 «조용한 유실» 과 같은 모양이다. 여기서는 그것이 측정 대상이다.
    ap.add_argument("--duplicate-keys", action="store_true",
                    help="rep_number 를 0 으로 고정해 재전송(중복) 조건을 만든다 (#276 ②)")
    args = ap.parse_args()

    sessions = parse_sessions(args.sessions)
    lo, n = sessions[0], len(sessions)

    # 메시지는 **하나**다. 세션은 배열 순환이 아니라 템플릿이 고른다.
    message = {"sessionId": SESSION_SLOT, "poseData": make_pose_data(args.reps)}
    payload = json.dumps(message, separators=(",", ":"))

    # 자리를 템플릿으로 바꾼다. 따옴표까지 포함해 지워야 **숫자**가 된다 —
    # `"{{ ... }}"` 로 남으면 jsonpb 가 int64 필드에 문자열을 넣으려다 죽는다.
    #
    # `mod` 로 세션을 고르는 것이 예전 배열 순환(`RequestNumber % len`)과 같은 일이다.
    # 레벨 1 이면 `mod .RequestNumber 1` = 0 이라 항상 첫 세션 — «단일 핫세션» 조건이
    # 특수 처리 없이 그대로 나온다.
    payload = payload.replace(f'"{SESSION_SLOT}"', f"{{{{ add {lo} (mod .RequestNumber {n}) }}}}")
    if args.duplicate_keys:
        payload = payload.replace(f'"{REPNUM_SLOT}"', "0")
    else:
        payload = payload.replace(f'"{REPNUM_SLOT}"', "{{ .RequestNumber }}")

    with open(args.out, "w", encoding="utf-8") as fp:
        fp.write(payload)

    size_kb = len(payload.encode("utf-8")) / 1024
    mode = "중복(rep_number 고정 0)" if args.duplicate_keys else "신규 키(rep_number = RequestNumber)"
    print(f"wrote {args.out}: sessions={n} ({sessions[0]}~{sessions[-1]}, 템플릿 라우팅) "
          f"reps={args.reps} size={size_kb:.1f}KB mode={mode}")
    print("  ⚠️ 이 파일은 JSON 이 아니라 ghz 템플릿이다 — json.load 로는 안 읽힌다(#271)")


if __name__ == "__main__":
    main()
