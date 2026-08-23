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
import threading
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
RESPONSE_MODE = "model"   # main() 이 --response-mode 로 덮는다
TRANSPORT = "urllib"      # main() 이 --transport 로 덮는다 (§12 의 팔)
NULL_HANDLER = False      # main() 이 --null-handler 로 덮는다 (축 3)
GIL_PROBE = 0.0           # main() 이 --gil-probe 로 덮는다 (축 5) · 0 = 안 띄운다
FLOOR_SEC = 0.0           # 부하 «전» 에 프로브 바닥을 걷는 초 · 0 = 안 걷는다
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


def reset_snapshot():
    """판 사이 초기화. 실패해도 판은 계속하되 **사유를 값으로 돌려준다.**"""
    req = urllib.request.Request(
        AI + "/api/v1/diag/frame-path/reset", data=b"",
        headers={"Authorization": "Bearer " + PUBLIC_TOKEN}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            r.read()
        return None
    except Exception as e:
        return repr(e)


def measure_floor(sec):
    """🔴 **부하를 걸기 «전»** 에 GIL 프로브 바닥을 걷는다 (축 5 계약 1).

    프로브가 재는 `sleep` 초과분에는 **OS 타이머 해상도**가 부하와 무관하게 깔려 있다.
    그 바닥을 안 빼면 GIL 대기를 과대평가한다 — 절대값으로 「GIL 대기 N ms」 라고
    읽으면 틀린다는 것이 `per-process-ceiling-cause.md` 축 5 의 계약이다.

    🔑 **같은 프로세스·같은 박스·같은 판에서** 걷는다. 다른 박스나 다른 날의 바닥을
       빌려오면 그 자체가 #498(라운드 간 비재현)을 판정에 끌어들이는 것이 된다.
       ⚠️ **#255 가 아니다** — 그건 부하기 샘플러 음수 결함이고 이미 닫혔다.
       정본은 `docs/decisions/round-to-round-nonreproducibility.md` 다.

    ⚠️ 걷고 나서 **반드시 초기화한다.** 안 그러면 바닥 표본이 부하 표본에 섞여
       평균을 끌어내린다 — 그러면 「GIL 대기가 작다」로 잘못 읽힌다.
    """
    time.sleep(sec)
    snap = fetch_snapshot()
    floor = {"sec": sec}
    if isinstance(snap, dict) and "gil_lag" in snap:
        floor.update(snap["gil_lag"])
        floor["loop_lag"] = snap.get("loop_lag")
        floor["requests"] = snap.get("requests")   # 🔴 0 이 아니면 «무부하» 가 아니다
    else:
        floor["error"] = (snap or {}).get("error", "gil_lag 없음")
    err = reset_snapshot()
    if err:
        floor["reset_error"] = err
    return floor


class CpuSampler(threading.Thread):
    """프로세스 트리 CPU 를 `/proc` 에서 직접 샘플링한다.

    ## 왜 이게 필요한가

    기준 관측 「346 RPS 에 **8.69 vCPU**」는 `docker stats` 로 걷힌 값이다. 그런데 R10 은
    **도커 없이 venv 로** 띄우므로 그 명령이 없다 — 대체 수단이 없으면 라운드가 «구간 비율»
    만 답하고 **제목의 숫자를 못 만진다**(#400 ⑤).

    ## 눈금을 맞춘다

    `docker stats` 의 `CPU %` 는 **100% = 1 vCPU** 다. 여기서도 같은 뜻으로 낸다 —
    `Δ(utime+stime) / SC_CLK_TCK / Δ실시간`. 표를 나란히 놓을 수 있어야 한다.

    ## 부하기도 같이 잰다

    R10-a 는 **1대 동거**라 부하기가 같은 박스의 CPU 를 먹는다. 그 크기는
    `r10-loadgen-topology.md` §8 이 **「잰 적이 없다」**로 열어둔 자리다 — 다른 박스에서
    걷힌 0.5~1.4 vCPU 는 «그 박스에서» 잰 값이라 그대로 못 옮긴다. 여기서 처음 걷는다.

    🔴 **트리로 센다.** 루트 프로세스만 보면 자식이 쓴 CPU 가 빠지는데, rig 은 부하기를
    자식 프로세스로 띄운다. 매 틱 `/proc/*/stat` 를 훑어 부모-자식을 이어 합산한다.
    """

    def __init__(self, interval=1.0):
        super().__init__(daemon=True)
        self.interval = interval
        self.roots: dict[str, int] = {}
        # 🔑 스레드까지 쪼갤 루트. 「이벤트 루프 스레드가 1코어에 붙었나」를 보는 자리다
        #    (설계: docs/decisions/per-process-ceiling-cause.md 축 1).
        self.thread_roots: dict[str, int] = {}
        self.samples: list[dict] = []
        self.error = None
        # 🔴 이름이 `_stop` 이면 안 된다 — `threading.Thread._stop()` 을 덮어써서
        #    `join()` 이 «Event object is not callable» 로 죽는다(리눅스에서 실측).
        self._done = threading.Event()
        self._t0 = None
        self._clk = os.sysconf("SC_CLK_TCK") if hasattr(os, "sysconf") else 100

    # ── /proc 읽기 ──────────────────────────────────────────────────────
    @staticmethod
    def _procs():
        """{pid: (ppid, utime+stime ticks)}. 읽는 중 사라지는 프로세스는 건너뛴다."""
        out = {}
        for name in os.listdir("/proc"):
            if not name.isdigit():
                continue
            try:
                with open(f"/proc/{name}/stat", "rb") as f:
                    raw = f.read().decode("utf-8", "replace")
            except OSError:
                continue                      # 샘플 사이에 죽은 프로세스 — 정상이다
            # comm 에 공백·괄호가 들어갈 수 있어 마지막 ')' 뒤부터 자른다
            try:
                rest = raw[raw.rindex(")") + 2:].split()
                out[int(name)] = (int(rest[1]), int(rest[11]) + int(rest[12]))
            except (ValueError, IndexError):
                continue
        return out

    @staticmethod
    def _threads(pid):
        """{tid: ticks} — 그 프로세스의 스레드별 utime+stime.

        읽는 중 죽는 스레드는 건너뛴다(워커풀은 계속 뜨고 진다).
        """
        out = {}
        try:
            tids = os.listdir(f"/proc/{pid}/task")
        except OSError:
            return out
        for tid in tids:
            try:
                with open(f"/proc/{pid}/task/{tid}/stat", "rb") as f:
                    raw = f.read().decode("utf-8", "replace")
                rest = raw[raw.rindex(")") + 2:].split()
                out[int(tid)] = int(rest[11]) + int(rest[12])
            except (OSError, ValueError, IndexError):
                continue
        return out

    @staticmethod
    def _box_busy():
        """박스 전체가 «일한» 누적 tick (idle·iowait 제외)."""
        with open("/proc/stat", "rb") as f:
            f0 = f.readline().decode().split()
        v = [int(x) for x in f0[1:]]
        # user nice system idle iowait irq softirq steal ...
        return sum(v) - v[3] - (v[4] if len(v) > 4 else 0)

    def _tree_ticks(self, procs):
        """루트별 자손 합. 부모→자식 맵을 만들어 훑는다."""
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

    # ── 수명 ────────────────────────────────────────────────────────────
    def track(self, name, pid, threads=False):
        """`threads=True` 면 그 프로세스의 **스레드별** CPU 도 쪼갠다.

        🔑 **메인 스레드는 tid == pid 다**(리눅스 보장). uvicorn 은 `--workers` 없이
        이벤트 루프를 **메인 스레드에서** 돌리므로, 그 한 줄이 곧 «루프가 얼마나 쓰나» 다.
        CPython 은 OS 스레드 이름을 안 붙이는 경우가 많아 `comm` 으로는 못 가른다 —
        **tid == pid 가 유일하게 믿을 수 있는 표식**이다.
        """
        self.roots[name] = pid
        if threads:
            self.thread_roots[name] = pid

    def stop(self):
        self._done.set()

    def run(self):
        if not os.path.isdir("/proc"):
            # 🔴 값이 아니라 «사유» 를 남긴다. 로컬(Windows)에서는 이 샘플러가 못 돈다.
            self.error = "no-procfs"
            return
        try:
            prev_p, prev_b, prev_t = self._procs(), self._box_busy(), time.monotonic()
            prev_th = {n: self._threads(pid) for n, pid in self.thread_roots.items()}
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

            # 🔑 스레드 분해 — 「이벤트 루프(메인)가 1코어에 붙었나」
            cur_th = {}
            for name, pid in self.thread_roots.items():
                cur_th[name] = self._threads(pid)
                p0, p1 = prev_th.get(name, {}), cur_th[name]
                main = worker = 0
                for tid, t1 in p1.items():
                    d = max(0, t1 - p0.get(tid, t1))   # 새로 뜬 스레드는 0 으로 센다
                    if tid == pid:
                        main += d                       # 🔑 tid == pid = 메인 = 이벤트 루프
                    else:
                        worker += d
                row[f"{name}_main"] = round(main / self._clk / dt, 3)
                row[f"{name}_workers"] = round(worker / self._clk / dt, 3)
                row[f"{name}_nthreads"] = len(p1)
            prev_th = cur_th

            self.samples.append(row)
            prev_p, prev_b, prev_t = cur_p, cur_b, cur_t

    # ── 요약 ────────────────────────────────────────────────────────────
    def summary(self, warmup=0.0):
        """단위는 **vCPU**(= docker stats 의 100%). 워밍업 구간은 버린다.

        🔴 워밍업을 안 버리면 **검출기 지연 생성**이 평균에 섞인다 — 그 구간은
        «부하를 처리하는 CPU» 가 아니라 «검출기를 만드는 CPU» 다.
        """
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

        out = {"unit": "vCPU (100% = 1코어, docker stats 와 같은 눈금)",
               "interval": self.interval, "warmup_dropped": len(self.samples) - len(kept),
               "n": len(kept), "ncpu": os.cpu_count()}
        for name in list(self.roots) + ["box"]:
            out[name] = stat(name)
        # 🔑 스레드 분해 — 설계 §2 축 1 의 판정선 ㄱ 이 읽는 자리다.
        #    메인(=이벤트 루프)이 1.0 vCPU 근처에 평평하면 «루프가 천장» 지지.
        for name in self.thread_roots:
            th = {k: stat(f"{name}_{k}") for k in ("main", "workers")}
            nt = [s2[f"{name}_nthreads"] for s2 in kept if f"{name}_nthreads" in s2]
            th["nthreads_max"] = max(nt) if nt else None
            out[f"{name}_threads"] = th
        out["series"] = kept
        return out


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
        # 응답 생성 방식(`ai-process-ceiling-cause.md` §11 의 팔). 기본은 현행 `model`.
        # 🔴 판마다 바꾸려면 **재기동이 필요하다** — `response_model` 이 데코레이터 시점에
        #    굳기 때문이다. 이 rig 은 어차피 판마다 uvicorn 을 다시 띄우므로 맞물린다.
        "RESPONSE_MODE": RESPONSE_MODE,
        # 축 3 — 널 핸들러 팔. 켜면 본문만 받고 즉시 반환한다(디코딩·추론·분석 없음).
        # 🔴 응답이 가짜다. 「빨라졌다」가 아니라 「계산을 뺐다」로 읽어야 한다.
        "POSE_NULL_HANDLER": "true" if NULL_HANDLER else "false",
        # 축 5 — GIL 지연 프로브 주기(초). 0 이면 안 띄운다.
        # 🔴 `FRAME_PATH_METRICS` 가 꺼진 팔에서는 담을 자리가 없어 안 뜬다(앱이 경고한다).
        "GIL_PROBE_INTERVAL": str(GIL_PROBE),
        "PYTHONUNBUFFERED": "1",
    })
    log = open(os.path.join(OUT, f"server_{TAG}.log"), "ab")
    p = subprocess.Popen(
        [PY, "-m", "uvicorn", "app.main:app", "--host", bind,
         "--port", str(HTTP_PORT), "--log-level", "warning"],
        cwd=os.path.join(ROOT, "ai-server"), env=env, stdout=log, stderr=log,
    )
    return p, log


def run_load(arm, round_no, sessions, fps, dur, warmup, sampler=None):
    label = f"{arm_slug(arm)}_r{round_no}"
    out_tsv = os.path.join(OUT, f"raw_{label}.tsv")
    # 🔴 run() 이 아니라 Popen 이다 — **부하기 자신의 pid** 가 필요하다. 1대 동거에서는
    #    부하기가 재려는 박스의 CPU 를 먹고, 그 크기가 아직 «미측정» 이다(무대 문서 §8).
    proc = subprocess.Popen(
        [PY, RIG, "--ai", AI, "--grpc", f"127.0.0.1:{GRPC_PORT}",
         "--token", PUBLIC_TOKEN, "--internal-token", INTERNAL_TOKEN,
         "--frames", FRAMES, "--sessions", str(sessions), "--fps", str(fps),
         "--dur", str(dur), "--warmup", str(warmup), "--label", label, "--out", out_tsv,
         "--transport", TRANSPORT],
        cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, encoding="utf-8",
    )
    if sampler is not None:
        sampler.track("rig", proc.pid)
    out, err = proc.communicate()
    if proc.returncode != 0:
        return {"label": label, "error": proc.returncode, "stderr": (err or "")[-500:]}
    line = [l for l in (out or "").splitlines() if l.startswith("{")]
    if not line:
        return {"label": label, "error": "no-json", "stderr": (err or "")[-500:]}
    d = json.loads(line[-1])
    if err:
        d["stderr_tail"] = err.strip()[-300:]
    return d


def main():
    global OUT, TAG, PY, AI, HTTP_PORT, GRPC_PORT, RESPONSE_MODE, TRANSPORT
    global NULL_HANDLER, GIL_PROBE, FLOOR_SEC
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
    ap.add_argument("--response-mode", default="model", dest="response_mode",
                    choices=("model", "dict", "json"),
                    help="응답 생성 방식 — model(현행) · dict(검증 제거) · json(JSONResponse 직접). "
                         "§11 의 팔이다. 🔴 dict·json 은 **응답 계약을 바꾼다**(측정용)")
    ap.add_argument("--transport", default="urllib",
                    choices=("urllib", "new", "keepalive"),
                    help="부하기 전송 방식(§12 의 팔). urllib=현행 · new=매번 새 연결 · "
                         "keepalive=재사용. 🔴 사본을 만들지 않고 **같은 rig 의 팔**로 넣었다 — "
                         "예전 시도가 사본이 세션을 못 붙여 무효였다")
    ap.add_argument("--null-handler", action="store_true", dest="null_handler",
                    help="축 3 — POST /pose 가 본문만 받고 즉시 반환한다(계산 전부 없음). "
                         "🔴 응답이 가짜다. 실행 단위로 걸린다(판마다가 아니다)")
    ap.add_argument("--gil-probe", type=float, default=0.0, dest="gil_probe",
                    help="축 5 — GIL 지연 프로브 주기(초). 0 이면 안 띄운다. 권장 0.001")
    ap.add_argument("--floor-sec", type=float, default=0.0, dest="floor_sec",
                    help="부하 «전» 에 프로브 바닥을 걷는 초. 🔴 --gil-probe 를 쓰면 "
                         "이걸 안 주는 것은 «절대값으로 읽겠다» 는 뜻이고 축 5 계약 위반이다")
    ap.add_argument("--settle", type=float, default=3.0,
                    help="판 사이 대기(초) — 포트·검출기 정리 여유")
    ap.add_argument("--warmup", type=float, default=5.0,
                    help="부하기가 표에서 버릴 앞 구간(초). CPU 요약도 같은 구간을 버린다")
    ap.add_argument("--cpu-interval", type=float, default=1.0, dest="cpu_interval",
                    help="CPU 샘플링 간격(초). /proc 가 없으면 샘플러는 사유만 남긴다")
    a = ap.parse_args()
    OUT, TAG = a.out, a.tag
    HTTP_PORT, GRPC_PORT = a.http_port, a.grpc_port
    AI = f"http://127.0.0.1:{HTTP_PORT}"
    PY = resolve_python(a.python_bin)
    RESPONSE_MODE = a.response_mode
    TRANSPORT = a.transport
    NULL_HANDLER = a.null_handler
    GIL_PROBE = a.gil_probe
    FLOOR_SEC = a.floor_sec
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
            # 🔴 바닥은 **부하 전** 이고 **샘플러 전** 이다 — 이 구간은 이 판의 값이
            #    아니다(축 5 계약 1). 걷고 나서 계측을 초기화하므로 아래 스냅샷에는 안 섞인다.
            floor = measure_floor(FLOOR_SEC) if (FLOOR_SEC > 0 and GIL_PROBE > 0) else None
            # CPU 샘플러는 **헬스 통과 뒤** 시작한다 — 기동·모델 로드는 이 판의 부하가 아니다.
            sampler = CpuSampler(a.cpu_interval)
            sampler.track("ai", p.pid, threads=True)   # 🔑 루프/워커를 쪼갠다
            sampler.start()
            r = run_load(arm, rn, a.sessions, a.fps, a.dur, a.warmup, sampler)
            sampler.stop()
            sampler.join(timeout=10)
            # 🔴 샘플러의 t0 는 «부하기 프로세스 시작» 이고, 부하기의 t0 는 «세션을 다 연 뒤» 다.
            #    그 사이(setup_sec)를 안 빼면 세션 여는 구간이 평균에 섞인다. 부하기가 그 값을
            #    돌려주므로 추론하지 않고 받아서 쓴다.
            r["cpu"] = sampler.summary((r.get("setup_sec") or 0.0) + a.warmup)
            # 🔴 서버를 내리기 **전에** 계측을 걷는다. 이건 프로세스 메모리라 종료와 함께
            #    사라진다 — 첫 라운드(run1)가 정확히 이걸 안 해서 B 판 넷의 구간 분포를 버렸다.
            r["frame_path"] = fetch_snapshot() if parse_arm(arm)[0] else None
            if floor is not None:
                r["gil_floor"] = floor
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
                   "pool": pool,
        "response_mode": RESPONSE_MODE,
        "transport": TRANSPORT, "bind": a.bind, "python": PY,
        "null_handler": NULL_HANDLER, "gil_probe": GIL_PROBE, "floor_sec": FLOOR_SEC,
                   "results": results}, f, ensure_ascii=False, indent=2)
    print("\n결과:", path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
