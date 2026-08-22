#!/usr/bin/env python3
"""
ghz 데이터 템플릿 생성기 — PoseDataBatchRequest 1건(= rep 1회 프레임들) JSON 생성.

부하 테스트 ② 백엔드 격리(load-test-strategy.md §3.2)용. MediaPipe 없이
SavePoseDataBatch gRPC 에 합성 batch 를 주입할 때 ghz --data-file 로 먹인다.

- reps(프레임 수) = R 값 (§4.5). 기본 25 는 추정 중앙값. 실측 후 --reps 로 교체.
- joint_coordinates = 33 랜드마크 JSON 문자열 (~2KB) — Spring 은 String 으로 opaque 저장이라
  내부 포맷 정확성은 부하 측정에 무관하나, payload 크기는 현실값(1~3KB)에 맞춤.
- 출력 필드명은 proto3 JSON canonical(camelCase). ghz/jsonpb 가 파싱.

🔴 2026-08-17: `repNumber` 가 **ghz 템플릿**이 됐다 (#271).
   멱등 키 `uk_pose_event(session_id, rep_number, timestamp_sec, created_at)` 가 들어오면서,
   같은 프레임을 반복해 보내는 이 페이로드는 **첫 요청만 행을 만들고 나머지는 no-op** 이 됐다.
   `ON DUPLICATE KEY UPDATE` 는 에러가 아니라 성공이라 `fail=0` 에 RPS 도 정상으로 찍힌다 —
   표를 봐서는 안 보인다. 그래서 유니크 키 네 열 중 하나를 요청 번호로 움직인다.
   session 은 **일부러 고정**이다: 이 생성기의 목적이 단일 핫세션 조건이다.
   ⚠️ 출력은 JSON 이 아니라 Go 템플릿이다 — `json.load` 로는 안 읽힌다. ghz 가 실행 후 파싱한다.
   설계: docs/decisions/loadtest-payload-uniqueness.md

사용:
  python gen_batch.py --session 801 --reps 25 --out batch.json
"""
import argparse
import json

# json.dumps 뒤에 템플릿으로 바꿔치기할 자리. 숫자 자리라 따옴표째 지운다.
REPNUM_SLOT = "@@GHZ_REPNUM@@"

# 스쿼트 결함 8종 (session_feedback_logs feedback_type 과 동일 분포 의도)
FEEDBACK_TYPES = [
    "", "", "KNEE_OUT", "BACK_BENT", "HIP_HIGH", "KNEE_IN", "", "KNEE_OUT",
]


def make_landmarks(seed: int) -> str:
    """33 랜드마크 {x,y,z,visibility} JSON 문자열 (~2KB). 결정적(seed 기반)."""
    landmarks = []
    for i in range(33):
        # 결정적 의사난수 — 실측 재현성 위해 random 미사용
        base = (seed * 31 + i * 7) % 1000 / 1000.0
        landmarks.append({
            "x": round(0.30 + base * 0.40, 6),
            "y": round(0.20 + ((base * 17) % 1.0) * 0.60, 6),
            "z": round(-0.25 + ((base * 13) % 1.0) * 0.50, 6),
            "visibility": round(0.85 + ((base * 11) % 1.0) * 0.15, 6),
        })
    return json.dumps(landmarks, separators=(",", ":"))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--session", type=int, default=801,
                    help="session_id (DB 에 row 존재해야 함, data.sql 더미 801)")
    ap.add_argument("--reps", type=int, default=25,
                    help="batch 내 프레임 수 = R 값 (§4.5, 추정 20~30)")
    ap.add_argument("--out", default="batch.json")
    args = ap.parse_args()

    pose_data = []
    for f in range(args.reps):
        pose_data.append({
            "timestampSec": round(f * 0.1, 1),               # 10fps 가정
            "jointCoordinates": make_landmarks(f),
            "syncRate": round(45.0 + (f * 7 % 50), 2),
            "feedbackMessage": FEEDBACK_TYPES[f % len(FEEDBACK_TYPES)],
            "repNumber": REPNUM_SLOT,   # 요청마다 달라진다 — 멱등 키를 움직이는 열 (#271)
        })

    batch = {"sessionId": args.session, "poseData": pose_data}
    payload = json.dumps(batch, separators=(",", ":"))
    # 따옴표까지 지워야 숫자가 된다. `"{{ ... }}"` 로 남으면 jsonpb 가 int32 필드에
    # 문자열을 넣으려다 죽는다.
    payload = payload.replace(f'"{REPNUM_SLOT}"', "{{ .RequestNumber }}")

    with open(args.out, "w", encoding="utf-8") as fp:
        fp.write(payload)

    size_kb = len(payload.encode("utf-8")) / 1024
    print(f"wrote {args.out}: session={args.session} reps={args.reps} "
          f"size={size_kb:.1f}KB (frame≈{size_kb/args.reps:.2f}KB)")
    print("  ⚠️ 이 파일은 JSON 이 아니라 ghz 템플릿이다 — json.load 로는 안 읽힌다(#271)")


if __name__ == "__main__":
    main()
