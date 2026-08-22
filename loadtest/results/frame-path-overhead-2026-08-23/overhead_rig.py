"""프레임 경로 계측의 «대가» 부하기 — 계측 ON/OFF 한 팔씩.

## 왜 P6 부하기(`load_ai.py`)를 안 쓰나

그건 세션을 **Spring 을 통해** 연다(회원가입 → 로그인 → 온보딩 → 세션 생성 → Spring 이
gRPC `StartAnalysis`). 동거 용량을 재는 rig 이라 그게 맞다. 그런데 여기서 재려는 것은
**계측이 요청 하나에 무는 값**이고, 무대에 MySQL·Spring 이 있으면 2코어를 셋이 나눠 쓰면서
**팔 간 차이보다 큰 잡음**이 들어온다.

그래서 이 rig 은 **AI 하나만 세운다.** 세션은 Spring 이 하던 것과 같은 gRPC `StartAnalysis` 를
**직접** 쳐서 연다 — `pose.py:69` 의 `lease_detector` 가 요구하는 자리가 그렇게 생긴다.

## 이 rig 이 재지 않는 것

- **rep·적재·리포트** — 합성 인체는 무릎 각도가 85~124° 라 「서있음」(155°) 문턱을 못 넘어
  rep 이 안 생긴다(`../coresidency-2026-08-15/frames.json` meta). Spring 콜백도 따라서 없다
- **절대 처리량** — i3-6100(물리 2코어)에서 서버와 부하기가 같은 박스에 산다.
  **팔 사이의 상대 델타만** 읽을 것([[project_loadtest_env_constraint]])

## 쓰는 법

    python overhead_rig.py --ai http://127.0.0.1:8100 --grpc 127.0.0.1:8685 \\
        --token "$AI_PUBLIC_TOKEN" --internal-token "$INTERNAL_API_TOKEN" \\
        --frames ../coresidency-2026-08-15/frames.json \\
        --sessions 8 --fps 6 --dur 45 --label on_r1 --out on_r1.tsv

`--fps 6` 은 의도다 — AI 의 유입 간격 상한이 300ms 라 6fps 면 절반이 `RATE_LIMITED` 로
갈리는데, **그 프레임도 추론은 다 한다**(상한은 판정 앞에서 자른다). 즉 **추론 경로를
포화시키면서** 세션 수는 적게 유지하는 손잡이다 — 검출기 8개면 메모리가 ~790MB 다.
⚠️ 그래서 이 판의 구성 비율은 실사용과 다르다. **팔 둘이 같은 비율을 보므로 대조는 성립**한다.
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request

LOCK = threading.Lock()
ROWS: list[tuple[float, int, float, str]] = []
SETUP_FAIL: list[str] = []
STOP = threading.Event()


def http(url, method="GET", body=None, headers=None, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    h = {"Content-Type": "application/json"}
    h.update(headers or {})
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:                       # 타임아웃·연결끊김을 상태로 바꾼다
        return 0, repr(e)


def classify(status, text):
    """`load_ai.py` 와 같은 분류를 쓴다 — 두 rig 의 표를 나란히 읽을 수 있어야 한다."""
    if status != 200:
        return f"http{status}"
    try:
        j = json.loads(text)
    except Exception:
        return "badjson"
    if j.get("success"):
        return "ok"
    return {
        "NO_LEASE": "nolease",
        "NO_POSE": "nopose",
        "RATE_LIMITED": "ratelimited",
        "LOW_VISIBILITY": "lowvis",
        "SESSION_NOT_FOUND": "nosession",
    }.get(j.get("skip_reason"), "skip:" + str(j.get("skip_reason")))


def open_sessions(grpc_addr, internal_token, session_ids, exercise_id):
    """Spring 이 하던 일을 그대로 한다 — gRPC StartAnalysis 로 풀에 자리를 만든다."""
    sys.path.insert(0, "ai-server")
    import grpc                                   # noqa: E402
    import exercise_pb2                           # noqa: E402
    import exercise_pb2_grpc                      # noqa: E402

    md = [("authorization", f"Bearer {internal_token}")]
    opened = []
    with grpc.insecure_channel(grpc_addr) as ch:
        stub = exercise_pb2_grpc.ExerciseServiceStub(ch)
        for sid in session_ids:
            req = exercise_pb2.AnalyzeRequest(
                exercise_id=exercise_id,
                session_id=sid,
                reference_source="frame-path-overhead-rig",
                persona="BEGINNER",
                session_nonce="",                 # 빈 값 = 소유권 대조 생략(compat 경로)
            )
            try:
                resp = stub.StartAnalysis(req, metadata=md, timeout=30)
            except Exception as e:
                SETUP_FAIL.append(f"세션 {sid}: StartAnalysis 예외 {e!r}")
                continue
            if resp.success:
                opened.append(sid)
            else:
                SETUP_FAIL.append(f"세션 {sid}: StartAnalysis success=False")
    return opened


def worker(idx, sid, ai, token, frames, fps, t0):
    hdr = {"Authorization": "Bearer " + token}
    interval = 1.0 / fps
    i = 0
    while not STOP.is_set():
        due = time.monotonic()
        payload = {
            "image": frames[i % len(frames)],
            "exercise_type": "squat",
            "session_id": sid,
            "timestamp_sec": i * interval,
        }
        s = time.monotonic()
        status, text = http(ai + "/api/v1/pose", "POST", payload, hdr)
        ms = (time.monotonic() - s) * 1000.0
        with LOCK:
            ROWS.append((round(s - t0, 3), idx, round(ms, 2), classify(status, text)))
        i += 1
        # 목표 간격을 «유지» 한다. 밀린 만큼 몰아 쏘면 재는 것이 «몰아치기» 가 된다 —
        # `load_ai.py` 와 같은 규칙이라 두 rig 의 수치가 같은 뜻을 갖는다.
        lag = time.monotonic() - (due + interval)
        if lag < 0:
            STOP.wait(-lag)


def pct(values, q):
    if not values:
        return float("nan")
    o = sorted(values)
    if len(o) == 1:
        return o[0]
    k = (len(o) - 1) * q
    lo = int(k)
    hi = min(lo + 1, len(o) - 1)
    return o[lo] if lo == hi else o[lo] + (o[hi] - o[lo]) * (k - lo)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ai", required=True)
    ap.add_argument("--grpc", required=True)
    ap.add_argument("--token", required=True)
    ap.add_argument("--internal-token", required=True, dest="internal_token")
    ap.add_argument("--frames", required=True)
    ap.add_argument("--sessions", type=int, required=True)
    ap.add_argument("--fps", type=float, default=6.0)
    ap.add_argument("--dur", type=int, default=45)
    ap.add_argument("--warmup", type=float, default=5.0,
                    help="이 시간만큼은 표에서 버린다 — 검출기 지연 생성이 앞판을 먹는다")
    ap.add_argument("--first-session-id", type=int, default=900001, dest="first_sid")
    ap.add_argument("--exercise-id", type=int, default=1, dest="exercise_id")
    ap.add_argument("--label", default="")
    ap.add_argument("--out", default="")
    a = ap.parse_args()

    frames = json.load(open(a.frames, encoding="utf-8"))["frames"]
    sids = list(range(a.first_sid, a.first_sid + a.sessions))

    opened = open_sessions(a.grpc, a.internal_token, sids, a.exercise_id)
    if len(opened) < a.sessions:
        print(f"⚠️ 세션 {len(opened)}/{a.sessions} 만 열렸다", file=sys.stderr)
    if not opened:
        print("🔴 세션이 하나도 안 열렸다 — 판 무효", file=sys.stderr)
        return 2

    t0 = time.monotonic()
    threads = [
        threading.Thread(target=worker, args=(i, sid, a.ai, a.token, frames, a.fps, t0),
                         daemon=True)
        for i, sid in enumerate(opened)
    ]
    for t in threads:
        t.start()
    STOP.wait(a.dur)
    STOP.set()
    for t in threads:
        t.join(timeout=15)

    with LOCK:
        rows = list(ROWS)

    # 🔴 워밍업 구간은 버린다. 검출기가 지연 생성이라 첫 몇 초가 팔이 아니라 «처음» 을 잰다.
    kept = [r for r in rows if r[0] >= a.warmup]
    span = max((r[0] for r in kept), default=0.0) - a.warmup
    outcomes: dict[str, int] = {}
    for r in kept:
        outcomes[r[3]] = outcomes.get(r[3], 0) + 1
    lat = [r[2] for r in kept]
    # 「처리된 프레임」 = 추론까지 간 것. RATE_LIMITED 도 추론은 했다(상한은 판정 앞에서 자른다).
    processed = sum(v for k, v in outcomes.items() if k in ("ok", "ratelimited", "lowvis", "nopose"))

    summary = {
        "label": a.label,
        "sessions": len(opened),
        "fps_target": a.fps,
        "dur": a.dur,
        "warmup_dropped": len(rows) - len(kept),
        "span_sec": round(span, 2),
        "requests": len(kept),
        "processed_fps": round(processed / span, 2) if span > 0 else None,
        "rps": round(len(kept) / span, 2) if span > 0 else None,
        "p50_ms": round(pct(lat, 0.50), 1),
        "p95_ms": round(pct(lat, 0.95), 1),
        "p99_ms": round(pct(lat, 0.99), 1),
        "mean_ms": round(statistics.mean(lat), 1) if lat else None,
        "outcomes": outcomes,
        "setup_fail": SETUP_FAIL,
    }
    print(json.dumps(summary, ensure_ascii=False))

    if a.out:
        with open(a.out, "w", encoding="utf-8", newline="") as f:
            f.write("t_rel\tsession_idx\tms\toutcome\n")
            for r in rows:
                f.write("%s\t%s\t%s\t%s\n" % r)
    return 0


if __name__ == "__main__":
    sys.exit(main())
