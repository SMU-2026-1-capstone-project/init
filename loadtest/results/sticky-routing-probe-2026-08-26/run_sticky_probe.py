"""스티키 라우팅 축소 측정 — 정적 사전분할(C, N 스윕 재현) vs 단일 클라이언트 해시 라우팅(E).

설계: `docs/decisions/ai-sticky-routing-probe.md`
대조군(다른 세션, 참고용): `../proc-count-sweep-2026-08-24/`(N=3, 451.2 rps)

## 왜 C 를 이 세션 안에서 다시 재현하나

N 스윕의 451.2 rps 는 다른 날 다른 판이다. [라운드 간 비재현](../../../docs/decisions/round-to-round-nonreproducibility.md)
(#498)이 걸리므로, **핵심 비교(C↔E)는 반드시 같은 세션 안에서** 한다.

## 팔 둘

    C  정적 사전분할 — rig 3개, 각자 53/53/54세션을 자기 백엔드에만 연다(N 스윕과 같은 모양).
       `overhead_rig.py` 를 그대로 3개 병렬로 부른다.
    E  🆕 단일 rig(`sticky_rig.py`) 하나가 `session_id % 3` 으로 세션마다 스스로 담당
       백엔드를 정해 3개 백엔드 모두를 향해 연다.

두 팔 다 매 판 ai-server 3개를 전부 재기동한다(콜드 아티팩트를 양쪽에 동일하게 물린다 —
N 스윕·`per-process-ceiling-cause.md` §8 과 같은 이유).

## 쓰는 법

    python loadtest/results/sticky-routing-probe-2026-08-26/run_sticky_probe.py \\
        --out <디렉터리>

repo 루트에서 돌린다(rig 이 `ai-server` 를 sys.path 에 넣는다). Windows 로컬에서는
`CpuSampler` 가 `/proc` 이 없어 "no-procfs" 사유만 남긴다 — CPU 요약은 리눅스(EC2) 전용이고,
그 밖의 값(rps·nolease·assigned_dist)은 어디서나 유효하다.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import threading
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))
STICKY_RIG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sticky_rig.py")
OVERHEAD_RIG = os.path.join(
    ROOT, "loadtest", "results", "frame-path-overhead-2026-08-23", "overhead_rig.py")
FRAMES = os.path.join(ROOT, "loadtest", "results", "coresidency-2026-08-15", "frames.json")

VENV_CANDIDATES = (
    os.path.join(ROOT, "ai-server", ".venv", "Scripts", "python.exe"),   # Windows
    os.path.join(ROOT, "ai-server", ".venv", "bin", "python"),           # POSIX
)

PUBLIC_TOKEN = "sticky-probe-public-token"
INTERNAL_TOKEN = "sticky-probe-internal-token"    # 🔴 둘이 같으면 앱이 기동을 거부한다(#230)

N = 3
BASE_HTTP = 8100
BASE_GRPC = 8685

# `per-process-ceiling-cause.md` §8 의 검증된 배열을 그대로 옮긴다(라벨만 B→C, A→E).
PLAN = ["C", "E", "C", "C", "E", "C", "E", "E", "C"]
DISCARD_FIRST = 1

PY = None   # main() 이 정한다


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


def http_addr(idx):
    return f"http://127.0.0.1:{BASE_HTTP + idx}"


def grpc_addr(idx):
    return f"127.0.0.1:{BASE_GRPC + idx}"


def wait_health(addr, deadline_sec=90):
    end = time.monotonic() + deadline_sec
    while time.monotonic() < end:
        try:
            with urllib.request.urlopen(addr + "/health", timeout=3) as r:
                if r.status == 200:
                    return True
        except Exception:
            pass
        time.sleep(1.0)
    return False


def fetch_snapshot(addr):
    req = urllib.request.Request(
        addr + "/api/v1/diag/frame-path",
        headers={"Authorization": "Bearer " + PUBLIC_TOKEN})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.loads(r.read().decode())
    except Exception as e:
        return {"error": repr(e)}


def reset_snapshot(addr):
    req = urllib.request.Request(
        addr + "/api/v1/diag/frame-path/reset", data=b"",
        headers={"Authorization": "Bearer " + PUBLIC_TOKEN}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            r.read()
        return None
    except Exception as e:
        return repr(e)


def measure_floor(addrs, sec):
    """부하 «전» 에 GIL 프로브 바닥을 백엔드마다 걷는다(축 5 계약 1 — 원본은
    `per-process-ceiling-cause.md`). 걷고 나서 반드시 초기화한다."""
    time.sleep(sec)
    out = {}
    for i, addr in enumerate(addrs):
        snap = fetch_snapshot(addr)
        floor = {"sec": sec}
        if isinstance(snap, dict) and "gil_lag" in snap:
            floor.update(snap["gil_lag"])
            floor["loop_lag"] = snap.get("loop_lag")
            floor["requests"] = snap.get("requests")   # 0 이 아니면 「무부하」가 아니다
        else:
            floor["error"] = (snap or {}).get("error", "gil_lag 없음")
        err = reset_snapshot(addr)
        if err:
            floor["reset_error"] = err
        out[i] = floor
    return out


class CpuSampler(threading.Thread):
    """`run_arms.py` 의 것과 동일 — `/proc` 트리 CPU 를 직접 샘플링한다.

    이 스윕에서는 이름이 넷 이상(`ai0`·`ai1`·`ai2`·`rig` 또는 `rig0..2`) 뜬다는 점만
    다르다. `E` 팔의 `rig` CPU 가 이 실험의 판정선 ㄹ(교란 배제)이다 —
    `docs/decisions/ai-sticky-routing-probe.md` §2-1.
    """

    def __init__(self, interval=1.0):
        super().__init__(daemon=True)
        self.interval = interval
        self.roots: dict[str, int] = {}
        self.samples: list[dict] = []
        self.error = None
        self._done = threading.Event()
        self._t0 = None
        self._clk = os.sysconf("SC_CLK_TCK") if hasattr(os, "sysconf") else 100

    @staticmethod
    def _procs():
        out = {}
        for name in os.listdir("/proc"):
            if not name.isdigit():
                continue
            try:
                with open(f"/proc/{name}/stat", "rb") as f:
                    raw = f.read().decode("utf-8", "replace")
            except OSError:
                continue
            try:
                rest = raw[raw.rindex(")") + 2:].split()
                out[int(name)] = (int(rest[1]), int(rest[11]) + int(rest[12]))
            except (ValueError, IndexError):
                continue
        return out

    @staticmethod
    def _box_busy():
        with open("/proc/stat", "rb") as f:
            f0 = f.readline().decode().split()
        v = [int(x) for x in f0[1:]]
        return sum(v) - v[3] - (v[4] if len(v) > 4 else 0)

    def _tree_ticks(self, procs):
        kids: dict[int, list[int]] = {}
        for pid, (ppid, _) in procs.items():
            kids.setdefault(ppid, []).append(pid)
        res = {}
        for name, root in self.roots.items():
            total, stack, seen = 0, [root], set()
            while stack:
                pid = stack.pop()
                if pid in seen:
                    continue
                seen.add(pid)
                if pid in procs:
                    total += procs[pid][1]
                    stack.extend(kids.get(pid, ()))
            res[name] = total
        return res

    def track(self, name, pid):
        self.roots[name] = pid

    def stop(self):
        self._done.set()

    def run(self):
        if not os.path.isdir("/proc"):
            self.error = "no-procfs"
            return
        try:
            prev_p, prev_b, prev_t = self._procs(), self._box_busy(), time.monotonic()
        except Exception as e:                 # noqa: BLE001
            self.error = repr(e)
            return
        self._t0 = prev_t
        while not self._done.wait(self.interval):
            try:
                cur_p, cur_b, cur_t = self._procs(), self._box_busy(), time.monotonic()
            except Exception as e:             # noqa: BLE001
                self.error = repr(e)
                return
            dt = cur_t - prev_t
            if dt <= 0:
                continue
            a, b = self._tree_ticks(prev_p), self._tree_ticks(cur_p)
            row = {"t_rel": round(cur_t - self._t0, 2)}
            for name in self.roots:
                row[name] = round(max(0, b.get(name, 0) - a.get(name, 0)) / self._clk / dt, 3)
            row["box"] = round(max(0, cur_b - prev_b) / self._clk / dt, 3)
            self.samples.append(row)
            prev_p, prev_b, prev_t = cur_p, cur_b, cur_t

    def summary(self, warmup=0.0):
        if self.error:
            return {"error": self.error}
        kept = [s for s in self.samples if s["t_rel"] >= warmup]
        if not kept:
            return {"error": "no-samples", "raw": len(self.samples)}

        def stat(key):
            v = sorted(s[key] for s in kept if key in s)
            if not v:
                return None
            mid = v[len(v) // 2] if len(v) % 2 else (v[len(v) // 2 - 1] + v[len(v) // 2]) / 2
            return {"mean": round(sum(v) / len(v), 2), "p50": round(mid, 2), "peak": v[-1]}

        out = {"unit": "vCPU (100% = 1코어)", "interval": self.interval,
               "warmup_dropped": len(self.samples) - len(kept), "n": len(kept),
               "ncpu": os.cpu_count()}
        for name in list(self.roots) + ["box"]:
            out[name] = stat(name)
        return out


def boot(idx, pool, bind):
    env = dict(os.environ)
    env.update({
        "AI_PUBLIC_TOKEN": PUBLIC_TOKEN,
        "INTERNAL_API_TOKEN": INTERNAL_TOKEN,
        "POSE_DETECTOR_POOL_SIZE": str(pool),
        "AI_GRPC_PORT": str(BASE_GRPC + idx),
        "FRAME_PATH_METRICS": "true",
        "GIL_SWITCH_INTERVAL": "0.0",
        "POSE_NULL_HANDLER": "false",
        "GIL_PROBE_INTERVAL": str(GIL_PROBE),
        "PYTHONUNBUFFERED": "1",
    })
    log = open(os.path.join(OUT, f"server_{TAG}_ai{idx}.log"), "ab")
    p = subprocess.Popen(
        [PY, "-m", "uvicorn", "app.main:app", "--host", bind,
         "--port", str(BASE_HTTP + idx), "--log-level", "warning"],
        cwd=os.path.join(ROOT, "ai-server"), env=env, stdout=log, stderr=log,
    )
    return p, log


def boot_all(pool, bind):
    return [boot(i, pool, bind) for i in range(N)]


def teardown_all(procs_logs):
    for p, log in procs_logs:
        p.terminate()
        try:
            p.wait(timeout=20)
        except subprocess.TimeoutExpired:
            p.kill()
        log.close()


def run_arm_C(label, sessions_total, fps, dur, warmup, sampler):
    """정적 사전분할 — N 스윕과 같은 모양. rig N 개를 병렬로 띄운다.

    나머지(sessions_total % N)를 버리지 않고 앞쪽 rig 부터 하나씩 더 받게
    분배한다 — E 팔(`session_id % N`)과 마찬가지로 sessions_total 을 전부 쓴다.
    (2026-08-26 판은 `sessions_total // N` 을 그대로 써서 160 중 1개를 버렸다.)
    """
    s_each = sessions_total // N
    remainder = sessions_total % N
    procs = []
    for i in range(N):
        s_this = s_each + (1 if i < remainder else 0)
        first_sid = 900001 + i * 1000       # 서로 다른 프로세스라 겹쳐도 무해하지만, 로그
                                              # 가독성을 위해 겹치지 않게 둔다
        out_tsv = os.path.join(OUT, f"raw_{label}_rig{i}.tsv")
        p = subprocess.Popen(
            [PY, OVERHEAD_RIG, "--ai", http_addr(i), "--grpc", grpc_addr(i),
             "--token", PUBLIC_TOKEN, "--internal-token", INTERNAL_TOKEN,
             "--frames", FRAMES, "--sessions", str(s_this), "--fps", str(fps),
             "--dur", str(dur), "--warmup", str(warmup),
             "--first-session-id", str(first_sid),
             "--label", f"{label}_rig{i}", "--out", out_tsv],
            cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, encoding="utf-8",
        )
        if sampler is not None:
            sampler.track(f"rig{i}", p.pid)
        procs.append(p)
    results = []
    for i, p in enumerate(procs):
        out, err = p.communicate()
        if p.returncode != 0:
            results.append({"rig": i, "error": p.returncode, "stderr": (err or "")[-500:]})
            continue
        line = [l for l in (out or "").splitlines() if l.startswith("{")]
        if not line:
            results.append({"rig": i, "error": "no-json", "stderr": (err or "")[-500:]})
            continue
        d = json.loads(line[-1])
        d["rig"] = i
        results.append(d)
    return results


def run_arm_E(label, sessions_total, fps, dur, warmup, sampler):
    """단일 rig, `session_id % N` 해시 라우팅."""
    backends_arg = ",".join(f"{http_addr(i)}|{grpc_addr(i)}" for i in range(N))
    out_tsv = os.path.join(OUT, f"raw_{label}.tsv")
    p = subprocess.Popen(
        [PY, STICKY_RIG, "--backends", backends_arg,
         "--token", PUBLIC_TOKEN, "--internal-token", INTERNAL_TOKEN,
         "--frames", FRAMES, "--sessions", str(sessions_total), "--fps", str(fps),
         "--dur", str(dur), "--warmup", str(warmup),
         "--label", label, "--out", out_tsv],
        cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, encoding="utf-8",
    )
    if sampler is not None:
        sampler.track("rig", p.pid)
    out, err = p.communicate()
    if p.returncode != 0:
        return {"error": p.returncode, "stderr": (err or "")[-500:]}
    line = [l for l in (out or "").splitlines() if l.startswith("{")]
    if not line:
        return {"error": "no-json", "stderr": (err or "")[-500:]}
    d = json.loads(line[-1])
    if err:
        d["stderr_tail"] = err.strip()[-300:]
    return d


def aggregate_C(raw):
    """arm C 의 rig N 개 요약을 하나로 합친다(합계 rps·requests·outcomes)."""
    errors = [r for r in raw if r.get("error")]
    if errors:
        return {"error": "rig-failed", "detail": errors}
    outcomes: dict[str, int] = {}
    for r in raw:
        for k, v in (r.get("outcomes") or {}).items():
            outcomes[k] = outcomes.get(k, 0) + v
    return {
        "rps": round(sum(r.get("rps") or 0 for r in raw), 2),
        "requests": sum(r.get("requests") or 0 for r in raw),
        "outcomes": outcomes,
        "per_rig": [{"rig": r.get("rig"), "rps": r.get("rps"),
                     "sessions": r.get("sessions"), "setup_fail": r.get("setup_fail")}
                    for r in raw],
    }


def main():
    global OUT, TAG, PY, GIL_PROBE
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.dirname(os.path.abspath(__file__)))
    ap.add_argument("--tag", default="run1")
    ap.add_argument("--python", default="", dest="python_bin")
    ap.add_argument("--bind", default="127.0.0.1")
    ap.add_argument("--sessions", type=int, default=160)
    ap.add_argument("--pool-total", type=int, default=201, dest="pool_total")
    ap.add_argument("--fps", type=float, default=3.0)
    ap.add_argument("--dur", type=int, default=90)
    ap.add_argument("--warmup", type=float, default=5.0)
    ap.add_argument("--gil-probe", type=float, default=0.001, dest="gil_probe")
    ap.add_argument("--floor-sec", type=float, default=5.0, dest="floor_sec")
    ap.add_argument("--settle", type=float, default=3.0)
    ap.add_argument("--cpu-interval", type=float, default=1.0, dest="cpu_interval")
    ap.add_argument("--plan", default=",".join(PLAN))
    ap.add_argument("--discard", type=int, default=DISCARD_FIRST)
    a = ap.parse_args()

    OUT, TAG = a.out, a.tag
    PY = resolve_python(a.python_bin)
    GIL_PROBE = a.gil_probe
    plan = [t.strip() for t in a.plan.split(",") if t.strip()]
    for tok in plan:
        if tok not in ("C", "E"):
            raise SystemExit(f"🔴 모르는 팔: {tok!r} — C 또는 E")
    os.makedirs(OUT, exist_ok=True)
    pool_each = a.pool_total // N + 1

    print(f"인터프리터: {PY}", flush=True)
    print(f"풀 {pool_each}개/백엔드 × {N} · 세션 합계 {a.sessions} · {a.fps}fps · "
          f"판당 {a.dur}초 · 판 {len(plan)}개", flush=True)

    results = []
    counts: dict[str, int] = {}
    for i, arm in enumerate(plan):
        counts[arm] = counts.get(arm, 0) + 1
        rn = counts[arm]
        discard = i < a.discard
        label = f"{arm}_r{rn}"
        print(f"[{i + 1}/{len(plan)}] 팔 {arm} r{rn}"
              f"{' (버림)' if discard else ''} — 백엔드 {N}개 기동 중...", flush=True)

        procs_logs = boot_all(pool_each, a.bind)
        addrs = [http_addr(i2) for i2 in range(N)]
        try:
            healthy = all(wait_health(addr) for addr in addrs)
            if not healthy:
                print("  🔴 기동 실패 — 이 판은 무효", flush=True)
                results.append({"n": i + 1, "arm": arm, "discard": discard,
                                 "error": "boot-timeout"})
                continue

            floor = measure_floor(addrs, a.floor_sec) if (a.floor_sec > 0 and GIL_PROBE > 0) else None

            sampler = CpuSampler(a.cpu_interval)
            for i2, (p, _log) in enumerate(procs_logs):
                sampler.track(f"ai{i2}", p.pid)
            sampler.start()

            if arm == "C":
                raw = run_arm_C(label, a.sessions, a.fps, a.dur, a.warmup, sampler)
                r = aggregate_C(raw)
            else:
                r = run_arm_E(label, a.sessions, a.fps, a.dur, a.warmup, sampler)

            sampler.stop()
            sampler.join(timeout=10)
            r["cpu"] = sampler.summary(a.warmup)
            r["frame_path"] = [fetch_snapshot(addr) for addr in addrs]
            if floor is not None:
                r["gil_floor"] = floor
            r.update({"n": i + 1, "arm": arm, "discard": discard, "label": label})
            results.append(r)
            print("  " + json.dumps(
                {k: r.get(k) for k in ("rps", "requests", "outcomes", "error",
                                        "assigned_dist")}, ensure_ascii=False), flush=True)
        finally:
            teardown_all(procs_logs)
            time.sleep(a.settle)

    path = os.path.join(OUT, f"sticky_{TAG}.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"plan": plan, "discard_first": a.discard, "sessions": a.sessions,
                   "pool_total": a.pool_total, "fps": a.fps, "dur": a.dur,
                   "gil_probe": GIL_PROBE, "floor_sec": a.floor_sec, "python": PY,
                   "results": results}, f, ensure_ascii=False, indent=2)

    # 게이트 — N 스윕(`proc-count-sweep-2026-08-24/run_sweep.sh`)과 같은 정신.
    bad = []
    for r in results:
        if r.get("discard"):
            continue
        if r.get("error"):
            bad.append(f"{r.get('label', r.get('n'))}: error={r['error']}")
            continue
        nolease = (r.get("outcomes") or {}).get("nolease", 0)
        if nolease:
            bad.append(f"{r.get('label')}: nolease={nolease} — 판정선 ㄱ 위반")
    print("\n──── 게이트 ────")
    if bad:
        print("🔴 " + "\n🔴 ".join(bad))
    else:
        print(f"🟢 게이트 통과 — {len(results)}판 · 유효 {sum(1 for r in results if not r.get('discard'))}판 · nolease 0")
    print("\n결과:", path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
