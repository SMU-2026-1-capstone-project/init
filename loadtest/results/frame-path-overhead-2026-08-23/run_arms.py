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

## 2026-08-23 이후 — R10(EC2) 을 위해 늘린 손잡이

08-23 판은 **이 박스에만** 맞춰져 있었다. 從 R10 은 `c7i.4xlarge`(리눅스·16 vCPU)에서
**160세션 규모**로 돌면서 **GIL 간격을 팔로** 써야 하는데, 그 셋이 다 막혀 있었다.
**기본값은 안 바꿨다** — 아래를 아무것도 안 주면 08-23 판과 같은 판이 돈다(README §6).

    --python <경로>    인터프리터를 직접 준다. 안 주면 venv 를 OS 별로 찾는다
                       (Windows 는 `Scripts` 아래 · POSIX 는 `bin` 아래)
    --bind 0.0.0.0     uvicorn 이 들을 주소. 부하기를 다른 박스에 둘 때
    --http-port / --grpc-port
    --pool N           검출기 풀. 0(기본)이면 세션 수 + 4
    --plan "A,B,A,B"   팔 순서. `@<초>` 를 붙이면 GIL 스위치 간격을 같이 건다
                       예) GIL 팔:  --plan "A,A@0.001,A,A@0.001"
    --discard N        앞에서 버릴 판 수
    --settle <초>      판 사이 대기

🔴 **이 rig 은 서버와 부하기를 같은 박스에 둔다.** R10 이 재려는 것이 「16 vCPU 중 얼마를
쓰나」라서 그 동거가 그대로 조작 변수가 된다 — 부하기를 떼려면 `--bind 0.0.0.0` 만으로는
부족하고 «팔 전환(재기동)» 을 원격으로 하는 배선이 따로 필요하다. **아직 없다.**
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
FRAMES = os.path.join(ROOT, "loadtest", "results", "coresidency-2026-08-15", "frames.json")

# 🔴 venv 의 인터프리터 자리는 OS 마다 다르다 — Windows 는 `Scripts\python.exe`,
#    리눅스(EC2)는 `bin/python`. 08-23 판은 앞엣것만 박아 두고 있어서 **EC2 에서 첫 줄에
#    죽는다.** 둘 다 찾아보고, 못 찾으면 값이 아니라 «사유» 를 들고 멈춘다.
VENV_CANDIDATES = (
    os.path.join(ROOT, "ai-server", ".venv", "Scripts", "python.exe"),   # Windows
    os.path.join(ROOT, "ai-server", ".venv", "bin", "python"),           # POSIX
)

# 포트·주소·인터프리터는 main() 이 정한다(박스마다 다르다).
PY = None
HTTP_PORT = 8100          # 8000 은 08-23 로컬 박스에서 이미 쓰이고 있었다
GRPC_PORT = 8685
AI = None


def resolve_python(explicit):
    if explicit:
        if not os.path.exists(explicit):
            raise SystemExit(f"🔴 --python 이 가리키는 파일이 없다: {explicit}")
        return explicit
    for cand in VENV_CANDIDATES:
        if os.path.exists(cand):
            return cand
    tried = os.linesep.join("  " + c for c in VENV_CANDIDATES)
    raise SystemExit(f"""🔴 ai-server venv 를 못 찾았다. 찾아본 자리:
{tried}
   만들어 두거나 --python 으로 인터프리터를 직접 줄 것.""")


def parse_arm(tok):
    """팔 표기를 «계측 ON/OFF» 와 «GIL 스위치 간격» 둘로 푼다.

    `A` = 계측 OFF · `B` = 계측 ON. 뒤에 `@<초>` 를 붙이면 `GIL_SWITCH_INTERVAL` 을
    같이 건다 — `A@0.001` · `B@0.02`. 안 붙이면 **0 = 파이썬 기본을 안 건드린다.**

    🔴 이 자리가 R10 의 판정선 넷 중 하나다(설계 §12) — 그 값을 흔들어 처리량이 움직이면
    「서비스 경로 GIL」이 1순위로 확정된다. 08-23 판은 이 값을 `"0"` 으로 **고정**해서
    그 팔을 아예 못 돌렸다.
    """
    base, _, gil = tok.partition("@")
    if base not in ("A", "B"):
        raise SystemExit(f"🔴 모르는 팔: {tok!r} — A(계측 OFF) 또는 B(계측 ON) 로 시작한다")
    try:
        g = float(gil) if gil else 0.0
    except ValueError:
        raise SystemExit(f"🔴 GIL 간격이 숫자가 아니다: {tok!r}")
    if g < 0:
        raise SystemExit(f"🔴 GIL 간격이 음수다: {tok!r}")
    return base == "B", g


def arm_slug(tok):
    """파일 이름에 쓸 수 있는 꼴 — `B@0.001` → `B_gil0p001`."""
    return tok.replace("@", "_gil").replace(".", "p")

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


def boot(arm, pool, bind):
    metrics, gil = parse_arm(arm)
    env = dict(os.environ)
    env.update({
        "AI_PUBLIC_TOKEN": PUBLIC_TOKEN,
        "INTERNAL_API_TOKEN": INTERNAL_TOKEN,
        # 풀은 세션 수보다 넉넉히. 0 이면 cgroup 에서 «유도» 하는데 컨테이너 밖(venv)에는
        # 한도가 없어 기동을 거부한다(mediapipe_detector.get_pool — 근거 없는 기본값을 안 박는다).
        "POSE_DETECTOR_POOL_SIZE": str(pool),
        "AI_GRPC_PORT": str(GRPC_PORT),
        "FRAME_PATH_METRICS": "true" if metrics else "false",
        "GIL_SWITCH_INTERVAL": str(gil),
        "PYTHONUNBUFFERED": "1",
    })
    log = open(os.path.join(OUT, f"server_{TAG}.log"), "ab")
    p = subprocess.Popen(
        [PY, "-m", "uvicorn", "app.main:app", "--host", bind,
         "--port", str(HTTP_PORT), "--log-level", "warning"],
        cwd=os.path.join(ROOT, "ai-server"), env=env, stdout=log, stderr=log,
    )
    return p, log


def run_load(arm, round_no, sessions, fps, dur):
    label = f"{arm_slug(arm)}_r{round_no}"
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
    global OUT, TAG, PY, AI, HTTP_PORT, GRPC_PORT
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.dirname(os.path.abspath(__file__)))
    ap.add_argument("--sessions", type=int, default=8)
    ap.add_argument("--fps", type=float, default=6.0)
    ap.add_argument("--dur", type=int, default=45)
    ap.add_argument("--tag", default="run1")
    ap.add_argument("--python", default="", dest="python_bin",
                    help="ai-server 를 띄울 인터프리터. 비우면 venv 를 OS 별로 찾는다")
    ap.add_argument("--bind", default="127.0.0.1",
                    help="uvicorn 이 들을 주소. 부하기를 다른 박스에 두면 0.0.0.0")
    ap.add_argument("--http-port", type=int, default=HTTP_PORT, dest="http_port")
    ap.add_argument("--grpc-port", type=int, default=GRPC_PORT, dest="grpc_port")
    ap.add_argument("--pool", type=int, default=0,
                    help="검출기 풀 크기. 0 이면 세션 수 + 4")
    ap.add_argument("--plan", default=",".join(PLAN),
                    help="팔 순서(쉼표). A=계측 OFF · B=계측 ON · @<초>로 GIL 간격. "
                         "예: B,A,B,B,A,B,A,A,B  또는  A,A@0.001,A,A@0.001")
    ap.add_argument("--discard", type=int, default=DISCARD_FIRST,
                    help="앞에서 버릴 판 수")
    ap.add_argument("--settle", type=float, default=3.0,
                    help="판 사이 대기(초) — 포트·검출기 정리 여유")
    a = ap.parse_args()
    OUT, TAG = a.out, a.tag
    HTTP_PORT, GRPC_PORT = a.http_port, a.grpc_port
    AI = f"http://127.0.0.1:{HTTP_PORT}"
    PY = resolve_python(a.python_bin)
    plan = [t.strip() for t in a.plan.split(",") if t.strip()]
    for tok in plan:
        parse_arm(tok)                      # 🔴 한 판이라도 돌기 전에 팔 표기를 다 검증한다
    pool = a.pool or (a.sessions + 4)
    os.makedirs(OUT, exist_ok=True)

    # 검출기 1개 = 98.7MB 는 실측값이다(`../detector-memory-2026-08-11/`). 판정이 아니라
    # 대조용으로 찍는다 — 박스 RAM 과 맞는지는 사람이 본다.
    print(f"인터프리터: {PY}", flush=True)
    print(f"풀 {pool}개 × 98.7MB(실측) ≈ {pool * 98.7 / 1024:.1f}GB · "
          f"세션 {a.sessions} · {a.fps}fps · 판당 {a.dur}초 · 판 {len(plan)}개", flush=True)

    results = []
    counts: dict[str, int] = {}
    for i, arm in enumerate(plan):
        counts[arm] = counts.get(arm, 0) + 1
        rn = counts[arm]
        discard = i < a.discard
        print(f"[{i + 1}/{len(plan)}] 팔 {arm} r{rn}"
              f"{' (버림)' if discard else ''} — 기동 중...", flush=True)
        p, log = boot(arm, pool, a.bind)
        try:
            if not wait_health():
                print("  🔴 기동 실패 — 이 판은 무효", flush=True)
                results.append({"n": i + 1, "arm": arm, "discard": discard,
                                "error": "boot-timeout"})
                continue
            r = run_load(arm, rn, a.sessions, a.fps, a.dur)
            # 🔴 서버를 내리기 **전에** 계측을 걷는다. 이건 프로세스 메모리라 종료와 함께
            #    사라진다 — 첫 라운드(run1)가 정확히 이걸 안 해서 B 판 넷의 구간 분포를 버렸다.
            r["frame_path"] = fetch_snapshot() if parse_arm(arm)[0] else None
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
            time.sleep(a.settle)   # 포트·검출기 정리 여유

    path = os.path.join(OUT, f"arms_{TAG}.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"plan": plan, "discard_first": a.discard,
                   "sessions": a.sessions, "fps": a.fps, "dur": a.dur,
                   "pool": pool, "bind": a.bind, "python": PY,
                   "results": results}, f, ensure_ascii=False, indent=2)
    print("\n결과:", path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
