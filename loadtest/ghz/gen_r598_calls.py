#!/usr/bin/env python3
"""#598 — extractReferenceData·completeAnalysis·reportFeedbackBatch 용 ghz 페이로드 생성기.

savePoseDataBatch(gen_batch_multi.py)와 달리 멱등 키 회피용 템플릿이 필요 없다 —
세 RPC 모두 페이로드 값 자체가 아니라 "동시에 몇 개가 겹치는가"(in-flight)만 관찰 대상이라,
ghz의 JSON 배열 라운드로빈으로 충분하다(요청마다 배열 다음 원소를 순환).

전제 (격리 스택 shadowfit-iso, 2026-08-31 시딩):
  - exercises id 1~3 (V2 마스터 시드, 격리 스택도 동일)
  - exercise_sessions id 1~200 (member_id=1, exercise_id 1~3 라운드로빈, status=IN_PROGRESS)
    시딩: docker exec -i shadowfit-iso-mysql mysql ... (이 스크립트 실행 전 완료돼 있어야 함)

사용:
  python gen_r598_calls.py --out-dir .
"""
import argparse
import json


def landmarks(seed: int) -> str:
    pts = []
    for i in range(33):
        base = (seed * 31 + i * 7) % 1000 / 1000.0
        pts.append({
            "x": round(0.30 + base * 0.40, 6),
            "y": round(0.20 + ((base * 17) % 1.0) * 0.60, 6),
            "z": round(-0.25 + ((base * 13) % 1.0) * 0.50, 6),
            "visibility": round(0.85 + ((base * 11) % 1.0) * 0.15, 6),
        })
    return json.dumps(pts, separators=(",", ":"))


def gen_extract_reference(n_exercises: int, poses_per_call: int) -> list:
    out = []
    for i in range(n_exercises):
        exercise_id = (i % 3) + 1  # 시딩된 exercises 1~3
        extracted = [
            {
                "timestampSec": round(f * 0.1, 1),
                "jointCoordinates": landmarks(f),
                "syncRate": 0.0,
                "feedbackMessage": "",
                "repNumber": 0,  # 기준 좌표는 rep 개념 없음 (proto 주석)
            }
            for f in range(poses_per_call)
        ]
        out.append({
            "exerciseId": exercise_id,
            "youtubeUrl": f"https://www.youtube.com/watch?v=r598rig{i}",
            "extractedPoses": extracted,
        })
    return out


def gen_complete_analysis(n_sessions: int) -> list:
    out = []
    for i in range(n_sessions):
        session_id = (i % 200) + 1  # 시딩된 exercise_sessions 1~200
        out.append({
            "sessionId": session_id,
            "totalReps": 10 + (i % 5),
            "avgSyncRate": round(60.0 + (i % 30), 2),
            "maxSyncRate": round(80.0 + (i % 15), 2),
            "minSyncRate": round(40.0 + (i % 10), 2),
            "caloriesBurned": round(50.0 + (i % 20), 2),
            "difficultyLevel": 1 + (i % 3),
        })
    return out


def gen_feedback_batch(n_calls: int, events_per_call: int) -> list:
    types = ["KNEE_OUT", "BACK_BENT", "HIP_HIGH", "KNEE_IN"]
    out = []
    for i in range(n_calls):
        session_id = (i % 200) + 1
        events = [
            {
                "feedbackType": types[(i + e) % len(types)],
                "syncRateAtTrigger": round(50.0 + (e % 40), 2),
                "occurredAt": "2026-08-31T00:00:00Z",
                "repNumber": (i % 20) + 1,
            }
            for e in range(events_per_call)
        ]
        out.append({
            "sessionId": session_id,
            "setNo": 1,
            "isFinal": False,
            "events": events,
        })
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default=".")
    ap.add_argument("--n", type=int, default=400, help="배열 길이(라운드로빈 풀 크기)")
    args = ap.parse_args()

    files = {
        "extract_reference.json": gen_extract_reference(args.n, poses_per_call=5),
        "complete_analysis.json": gen_complete_analysis(args.n),
        "feedback_batch.json": gen_feedback_batch(args.n, events_per_call=3),
    }
    for name, data in files.items():
        path = f"{args.out_dir}/{name}"
        with open(path, "w", encoding="utf-8") as fp:
            json.dump(data, fp, separators=(",", ":"))
        print(f"wrote {path}: {len(data)} entries")


if __name__ == "__main__":
    main()
