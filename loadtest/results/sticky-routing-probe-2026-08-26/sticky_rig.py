"""스티키 라우팅 축소 측정 rig — 단일 클라이언트가 N개 AI 프로세스에 세션을 해시로 고정한다.

`docs/decisions/per-process-ceiling-cause.md` §9-4 가 열어둔 캐비엇을 닫는다: N 스윕
(`../proc-count-sweep-2026-08-24/`)은 rig 도 N 개라 세션을 사람이 미리 나눠 각자 자기
프로세스에만 세션을 열었다. 실배포에서는 "이 세션이 어느 프로세스인가"를 **하나의 결정
지점**이 정해야 하고(`docs/decisions/ai-sticky-routing.md` §5-1 ㉮=ㄱ 추천 — Spring 이
세션 생성 응답에 실어준다), 이 rig 은 그 결정 지점을 한 프로세스 안에서 흉내낸다.

## 라우팅 규칙

`backend_idx = session_id % len(backends)`. CPython 은 정수 해시가 `hash(n) == n`
(오버플로·음수 등 예외 제외)이라 `hash(session_id) % N` 과 값이 같다 — `PYTHONHASHSEED`
에 안 흔들리는 결정적 규칙이라 이걸 그대로 쓴다(문자열 해시였다면 안 됐다).

## 이 rig 이 재지 않는 것

- **홉 지연** — 추천 설계(㉮=ㄱ)에는 프레임마다 도는 프록시가 없다. 세션 시작 때 한 번
  정해지고 끝이다. 이 rig 도 세션마다 한 번만 backend 를 결정하고 그 뒤로는 직행한다
- **부하기 자체의 GIL 병목 가능성** — 이 rig **하나**가 N=3 백엔드 전체(합계 160세션)를
  몬다. N 스윕은 그걸 프로세스 3개로 나눠 몰았다. 오케스트레이터(`run_sticky_probe.py`)가
  이 프로세스의 CPU 를 밖에서 같이 재고, 1 vCPU 근처면 이 교란이 배제된다 — 그 판정은
  여기가 아니라 오케스트레이터 쪽이다

## 쓰는 법 (오케스트레이터가 대신 부른다 — 손으로 돌릴 때 참고)

    python sticky_rig.py \\
        --backends "http://127.0.0.1:8100|127.0.0.1:8685,http://127.0.0.1:8101|127.0.0.1:8686,http://127.0.0.1:8102|127.0.0.1:8687" \\
        --token "$AI_PUBLIC_TOKEN" --internal-token "$INTERNAL_API_TOKEN" \\
        --frames ../coresidency-2026-08-15/frames.json \\
        --sessions 160 --fps 3.0 --dur 90 --label E_r1 --out E_r1.tsv

`overhead_rig.py` 와 거의 같은 모양이다 — 다른 점은 백엔드가 여럿이고, 세션마다 어디로
갈지를 이 rig 스스로 정한다는 것뿐이다.
"""

from __future__ import annotations

import argparse
import json
import os
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
    """`overhead_rig.py`·`load_ai.py` 와 같은 분류 — 세 rig 의 표를 나란히 읽을 수 있다."""
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


def open_sessions_hashed(backends, internal_token, session_ids, exercise_id):
    """세션마다 `session_id % len(backends)` 로 담당 백엔드를 정하고 그 gRPC 로 연다.

    Spring 이 하던 일(gRPC `StartAnalysis`)을 그대로 하되, **어느 채널로 칠지를 이 함수가
    정한다** — 그게 이 rig 전체의 요점이다.

    반환: {session_id: backend_idx} — 연 것만. 실패는 SETUP_FAIL 에 사유로 남는다.
    """
    repo = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(
        os.path.abspath(__file__)))))
    sys.path.insert(0, os.path.join(repo, "ai-server"))
    import grpc                                   # noqa: E402
    import exercise_pb2                           # noqa: E402
    import exercise_pb2_grpc                      # noqa: E402

    md = [("authorization", f"Bearer {internal_token}")]
    n = len(backends)
    channels = [grpc.insecure_channel(grpc_addr) for _, grpc_addr in backends]
    stubs = [exercise_pb2_grpc.ExerciseServiceStub(ch) for ch in channels]
    assigned: dict[int, int] = {}
    try:
        for sid in session_ids:
            b = sid % n                           # 🔑 라우팅 규칙 — 모듈 docstring §라우팅 규칙
            req = exercise_pb2.AnalyzeRequest(
                exercise_id=exercise_id,
                session_id=sid,
                reference_source="sticky-probe-rig",
                persona="BEGINNER",
                session_nonce="",                 # 빈 값 = 소유권 대조 생략(compat 경로)
            )
            try:
                resp = stubs[b].StartAnalysis(req, metadata=md, timeout=30)
            except Exception as e:
                SETUP_FAIL.append(f"세션 {sid}(→b{b}): StartAnalysis 예외 {e!r}")
                continue
            if resp.success:
                assigned[sid] = b
            else:
                SETUP_FAIL.append(f"세션 {sid}(→b{b}): StartAnalysis success=False")
    finally:
        for ch in channels:
            ch.close()
    return assigned


def worker(idx, sid, http_addr, token, frames, fps, t0):
    """`overhead_rig.py` 의 worker() 와 같다 — 전송 방식은 urllib 하나뿐이다.

    (§12 의 keepalive/new 축은 이미 답이 난 별개 질문이라 여기서 다시 안 연다.)
    """
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
        status, text = http(http_addr + "/api/v1/pose", "POST", payload, hdr)
        ms = (time.monotonic() - s) * 1000.0
        with LOCK:
            ROWS.append((round(s - t0, 3), idx, round(ms, 2), classify(status, text)))
        i += 1
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


def parse_backends(spec):
    """`'http://host:port|grpc_host:grpc_port,...'` → `[(http_addr, grpc_addr), ...]`."""
    out = []
    for tok in spec.split(","):
        tok = tok.strip()
        if not tok:
            continue
        http_addr, sep, grpc_addr = tok.partition("|")
        if not sep:
            raise SystemExit(f"🔴 --backends 항목에 '|' 가 없다: {tok!r}")
        out.append((http_addr.rstrip("/"), grpc_addr))
    if not out:
        raise SystemExit("🔴 --backends 가 비어 있다")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--backends", required=True,
                    help="쉼표 구분, 각 항목 'http://host:port|grpc_host:grpc_port'")
    ap.add_argument("--token", required=True)
    ap.add_argument("--internal-token", required=True, dest="internal_token")
    ap.add_argument("--frames", required=True)
    ap.add_argument("--sessions", type=int, required=True)
    ap.add_argument("--fps", type=float, default=3.0)
    ap.add_argument("--dur", type=int, default=90)
    ap.add_argument("--warmup", type=float, default=5.0,
                    help="이 시간만큼은 표에서 버린다 — 검출기 지연 생성이 앞판을 먹는다")
    ap.add_argument("--first-session-id", type=int, default=900001, dest="first_sid")
    ap.add_argument("--exercise-id", type=int, default=1, dest="exercise_id")
    ap.add_argument("--label", default="")
    ap.add_argument("--out", default="")
    a = ap.parse_args()

    backends = parse_backends(a.backends)
    n = len(backends)

    proc_t0 = time.monotonic()
    frames = json.load(open(a.frames, encoding="utf-8"))["frames"]
    sids = list(range(a.first_sid, a.first_sid + a.sessions))

    assigned = open_sessions_hashed(backends, a.internal_token, sids, a.exercise_id)
    if len(assigned) < a.sessions:
        print(f"⚠️ 세션 {len(assigned)}/{a.sessions} 만 열렸다", file=sys.stderr)
    if not assigned:
        print("🔴 세션이 하나도 안 열렸다 — 판 무효", file=sys.stderr)
        return 2

    # 판정선 ㄷ — 배정이 백엔드 사이에 고른가(설계 §3-4).
    dist: dict[int, int] = {}
    for b in assigned.values():
        dist[b] = dist.get(b, 0) + 1

    t0 = time.monotonic()
    threads = [
        threading.Thread(
            target=worker,
            args=(i, sid, backends[b][0], a.token, frames, a.fps, t0),
            daemon=True,
        )
        for i, (sid, b) in enumerate(assigned.items())
    ]
    for t in threads:
        t.start()
    STOP.wait(a.dur)
    STOP.set()
    for t in threads:
        t.join(timeout=15)

    with LOCK:
        rows = list(ROWS)

    kept = [r for r in rows if r[0] >= a.warmup]
    span = max((r[0] for r in kept), default=0.0) - a.warmup
    outcomes: dict[str, int] = {}
    for r in kept:
        outcomes[r[3]] = outcomes.get(r[3], 0) + 1
    lat = [r[2] for r in kept]
    processed = sum(v for k, v in outcomes.items() if k in ("ok", "ratelimited", "lowvis", "nopose"))

    summary = {
        "label": a.label,
        "sessions": len(assigned),
        "backends": n,
        "assigned_dist": {str(k): v for k, v in sorted(dist.items())},  # 판정선 ㄷ
        "fps_target": a.fps,
        "dur": a.dur,
        "warmup_dropped": len(rows) - len(kept),
        "setup_sec": round(t0 - proc_t0, 2),
        "span_sec": round(span, 2),
        "requests": len(kept),
        "processed_fps": round(processed / span, 2) if span > 0 else None,
        "rps": round(len(kept) / span, 2) if span > 0 else None,
        "p50_ms": round(pct(lat, 0.50), 1),
        "p95_ms": round(pct(lat, 0.95), 1),
        "p99_ms": round(pct(lat, 0.99), 1),
        "mean_ms": round(statistics.mean(lat), 1) if lat else None,
        "outcomes": outcomes,                    # 판정선 ㄱ — outcomes["nolease"] 가 0 이어야 한다
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
