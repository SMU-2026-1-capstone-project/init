"""팔 교대 드라이버 — 계측 OFF(A) ↔ ON(B) 를 위치 균형으로 돌린다.

## 왜 판마다 서버를 다시 띄우나

`FRAME_PATH_METRICS` 는 **기동 시** 미들웨어를 붙일지 정한다. 그래서 팔을 바꾸려면 재시작이
강제되는데, 재시작은 그 자체로 결과를 흔든다는 관측이 있다
(`ai-receive-path-scaling.md` §9-4 — 판마다 재시작하면 −24%).

**그래서 두 팔 다 매판 재시작한다.** 재시작 비용을 없앨 수는 없으니 **양쪽에 똑같이 물린다.**
그리고 순서 효과가 팔에 얹히지 않도록 **ABBA / BAAB** 로 위치를 맞추고, 맨 앞에 **버림판**을
하나 둔다([[feedback_measure_design_needs_repeats]]).

    판 순서:  버림(B) · A B B A · B A A B
              └ 각 팔 4판, 앞뒤 위치 합이 같다

## 쓰는 법

    python loadtest/results/frame-path-overhead-2026-08-23/run_arms.py --out <디렉터리>

repo 루트에서 돌린다(rig 이 `ai-server` 를 sys.path 에 넣는다).
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))
RIG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "overhead_rig.py")
PY = os.path.join(ROOT, "ai-server", ".venv", "Scripts", "python.exe")
FRAMES = os.path.join(ROOT, "loadtest", "results", "coresidency-2026-08-15", "frames.json")

HTTP_PORT = 8100          # 8000 은 이 박스에서 이미 쓰이고 있었다
GRPC_PORT = 8685
AI = f"http://127.0.0.1:{HTTP_PORT}"

PUBLIC_TOKEN = "overhead-rig-public-token"
INTERNAL_TOKEN = "overhead-rig-internal-token"    # 🔴 둘이 같으면 앱이 기동을 거부한다(#230)

# 팔: A = 계측 OFF(현행 운영 기본값) · B = 계측 ON
PLAN = ["B", "A", "B", "B", "A", "B", "A", "A", "B"]   # 첫 판은 버림
DISCARD_FIRST = 1


def wait_health(deadline_sec=90):
    end = time.monotonic() + deadline_sec
    while time.monotonic() < end:
        try:
            with urllib.request.urlopen(AI + "/health", timeout=3) as r:
                if r.status == 200:
                    return True
        except Exception:
            pass
        time.sleep(1.0)
    return False


def fetch_snapshot():
    """계측 스냅샷을 서버가 살아 있을 때 회수한다. 실패는 값이 아니라 사유로 남긴다."""
    req = urllib.request.Request(
        AI + "/api/v1/diag/frame-path",
        headers={"Authorization": "Bearer " + PUBLIC_TOKEN})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.loads(r.read().decode())
    except Exception as e:
        return {"error": repr(e)}


def boot(arm, sessions):
    env = dict(os.environ)
    env.update({
        "AI_PUBLIC_TOKEN": PUBLIC_TOKEN,
        "INTERNAL_API_TOKEN": INTERNAL_TOKEN,
        # 풀은 세션 수보다 넉넉히. 0 이면 cgroup 에서 «유도» 하는데 로컬엔 한도가 없어
        # 기동을 거부한다(mediapipe_detector.get_pool — 근거 없는 기본값을 안 박는다).
        "POSE_DETECTOR_POOL_SIZE": str(sessions + 4),
        "AI_GRPC_PORT": str(GRPC_PORT),
        "FRAME_PATH_METRICS": "true" if arm == "B" else "false",
        "GIL_SWITCH_INTERVAL": "0",               # 이 판이 흔드는 것은 계측 하나뿐이다
        "PYTHONUNBUFFERED": "1",
    })
    log = open(os.path.join(OUT, f"server_{TAG}.log"), "ab")
    p = subprocess.Popen(
        [PY, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1",
         "--port", str(HTTP_PORT), "--log-level", "warning"],
        cwd=os.path.join(ROOT, "ai-server"), env=env, stdout=log, stderr=log,
    )
    return p, log


def run_load(arm, round_no, sessions, fps, dur):
    label = f"{arm}_r{round_no}"
    out_tsv = os.path.join(OUT, f"raw_{label}.tsv")
    cp = subprocess.run(
        [PY, RIG, "--ai", AI, "--grpc", f"127.0.0.1:{GRPC_PORT}",
         "--token", PUBLIC_TOKEN, "--internal-token", INTERNAL_TOKEN,
         "--frames", FRAMES, "--sessions", str(sessions), "--fps", str(fps),
         "--dur", str(dur), "--label", label, "--out", out_tsv],
        cwd=ROOT, capture_output=True, text=True, encoding="utf-8",
    )
    if cp.returncode != 0:
        return {"label": label, "error": cp.returncode, "stderr": (cp.stderr or "")[-500:]}
    line = [l for l in (cp.stdout or "").splitlines() if l.startswith("{")]
    if not line:
        return {"label": label, "error": "no-json", "stderr": (cp.stderr or "")[-500:]}
    d = json.loads(line[-1])
    if cp.stderr:
        d["stderr_tail"] = cp.stderr.strip()[-300:]
    return d


def main():
    global OUT, TAG
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.dirname(os.path.abspath(__file__)))
    ap.add_argument("--sessions", type=int, default=8)
    ap.add_argument("--fps", type=float, default=6.0)
    ap.add_argument("--dur", type=int, default=45)
    ap.add_argument("--tag", default="run1")
    a = ap.parse_args()
    OUT, TAG = a.out, a.tag
    os.makedirs(OUT, exist_ok=True)

    results = []
    counts = {"A": 0, "B": 0}
    for i, arm in enumerate(PLAN):
        counts[arm] += 1
        rn = counts[arm]
        discard = i < DISCARD_FIRST
        print(f"[{i + 1}/{len(PLAN)}] 팔 {arm} r{rn}"
              f"{' (버림)' if discard else ''} — 기동 중...", flush=True)
        p, log = boot(arm, a.sessions)
        try:
            if not wait_health():
                print("  🔴 기동 실패 — 이 판은 무효", flush=True)
                results.append({"n": i + 1, "arm": arm, "discard": discard,
                                "error": "boot-timeout"})
                continue
            r = run_load(arm, rn, a.sessions, a.fps, a.dur)
            # 🔴 서버를 내리기 **전에** 계측을 걷는다. 이건 프로세스 메모리라 종료와 함께
            #    사라진다 — 첫 라운드(run1)가 정확히 이걸 안 해서 B 판 넷의 구간 분포를 버렸다.
            r["frame_path"] = fetch_snapshot() if arm == "B" else None
            r.update({"n": i + 1, "arm": arm, "discard": discard})
            results.append(r)
            print("  " + json.dumps(
                {k: r.get(k) for k in ("processed_fps", "rps", "p50_ms", "p95_ms",
                                       "requests", "error")}, ensure_ascii=False), flush=True)
        finally:
            p.terminate()
            try:
                p.wait(timeout=20)
            except subprocess.TimeoutExpired:
                p.kill()
            log.close()
            time.sleep(3)          # 포트·검출기 정리 여유

    path = os.path.join(OUT, f"arms_{TAG}.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"plan": PLAN, "discard_first": DISCARD_FIRST,
                   "sessions": a.sessions, "fps": a.fps, "dur": a.dur,
                   "results": results}, f, ensure_ascii=False, indent=2)
    print("\n결과:", path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
