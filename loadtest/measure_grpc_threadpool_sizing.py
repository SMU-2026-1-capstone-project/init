"""gRPC 서버 스레드풀 max_workers=10 이 실제로 병목인지 직접 잰다 (#593).

## 왜

`app/grpc/server.py:83` — `grpc.server(futures.ThreadPoolExecutor(max_workers=10), ...)`.
`POSE_DETECTOR_POOL_SIZE`(메모리 한도에서 유도하는 공식이 있음)나 `AI_WORKER_COUNT=3`(EC2
실측)과 달리, 이 `10`은 근거·실측이 아무 데도 없는 매직넘버다
(`ai-server/docs/fastapi-tuning.md` §3·§3-1). 이 스크립트는 그 갭 중 하나를 좁힌다 —
"동시에 몇 개까지 겹치는지, 넘치면 무슨 일이 나는지(큐잉? 타임아웃?)".

## 무엇을 재는가

동시성(concurrency)을 스윕하며 `StopAnalysis` RPC를 동시에 N개 쏘고, RPC별 왕복 시간을
잰다. `StopAnalysis`를 고른 이유:

- 존재하지 않는 session_id로 불러도 부작용이 없다(`exercise_servicer.py:305-348`) —
  `registry.remove()`가 `None`을 반환하고 `success=False`로 짧게 끝난다. `StartAnalysis`처럼
  MediaPipe 검출기 풀을 실제로 점유하지 않는다 — 검출기 풀 고갈과 스레드풀 포화를 섞지 않는다.
- 그래도 **완전히 공짜는 아니다** — `get_pool().release(session_id)`가
  `DetectorPool._guard`(`app/core/mediapipe_detector.py:213`, 세션 전체가 공유하는 **단일**
  `threading.Lock`)를 잡는다. 즉 이 프로브는 "ThreadPoolExecutor의 10개 슬롯"과 "그 락 경합"을
  같이 잰다 — 갈라내지 못한다. 임계구역 자체는 dict pop 두 번이라 짧지만, 경합 유무를 이
  스크립트가 증명하진 않는다.

## 🔴 이 측정이 못 가르는 것 (읽기 전에 박아둔다)

- **GIL과 안 갈린다.** 한 프로세스 안에서 `max_workers`개의 OS 스레드를 띄워도,
  `StopAnalysis` 핸들러(dict 연산 + 로깅, 거의 순수 파이썬)는 GIL을 거의 안 놓는다.
  그러니 이 실험에서 관측되는 "동시 처리량"은 **스레드풀 크기**와 **GIL 직렬화** 둘 다의
  결과이고, 이 스크립트 혼자로는 어느 쪽이 얼마인지 배분할 수 없다
  (`docs/decisions/per-process-ceiling-cause.md`가 §6-1에서 명시한 원칙 — "배분하지 않는다").
- **락 경합과도 안 갈린다** — 위 §의 `DetectorPool._guard` 이유.
- 그래서 이 스크립트가 답할 수 있는 것은 좁게: **"동시성이 10을 넘으면 관측 가능한 수준으로
  느려지는가, 그리고 타임아웃/에러가 나는가"** 까지다. "느려지면 그게 스레드풀 탓이다"까지는
  못 간다 — 다음 판(스레드별 CPU 분해, per-process-ceiling-cause.md 축 1과 같은 기법)이 그
  후속이다.

## 무대

로컬(i3-6100 2코어)에서도 된다 — 이 실험은 GIL/코어 스케일과 달리 "물리 코어 수"에 안 묶인다
(위 캐비어트가 바로 그 이유: 애초에 코어를 여러 개 실제로 못 쓰는 작업이라서). 이 프로세스
하나만 띄우면 되고 docker-compose·MySQL 불필요 — `ai-server/.venv`의 python만 있으면 된다.

사용:
    python measure_grpc_threadpool_sizing.py
    python measure_grpc_threadpool_sizing.py --python /path/to/ai-server/.venv/Scripts/python.exe
    python measure_grpc_threadpool_sizing.py --concurrencies 1,5,10,15,20,30,50 --repeats 3
"""

from __future__ import annotations

import argparse
import asyncio
import itertools
import json
import os
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

VENV_CANDIDATES = (
    os.path.join(ROOT, "ai-server", ".venv", "Scripts", "python.exe"),
    os.path.join(ROOT, "ai-server", ".venv", "bin", "python"),
)

PUBLIC_TOKEN = "threadpool-sizing-probe-public-token"
INTERNAL_TOKEN = "threadpool-sizing-probe-internal-token"

HTTP_PORT = 8300
GRPC_PORT = 8785

# 서버 쪽 실제 값 — 여기서 건드리지 않는다. 이 상수를 스윕에 표시용으로만 쓴다
# (건드리면 "실제 배포값"이 아니라 "우리가 고른 값"을 재는 게 된다).
DEPLOYED_MAX_WORKERS = 10


def resolve_python(explicit: str) -> str:
    if explicit:
        return explicit
    for c in VENV_CANDIDATES:
        if os.path.exists(c):
            return c
    raise SystemExit(
        "🔴 ai-server venv를 못 찾았다 — 이 스크립트는 워크트리 안에 있어서 "
        "ai-server/.venv가 이 경로엔 없을 수 있다. --python으로 원본 체크아웃의 "
        "venv(예: E:/init/ai-server/.venv/Scripts/python.exe)를 직접 줄 것."
    )


def wait_health(addr: str, deadline_sec: float = 90) -> bool:
    end = time.monotonic() + deadline_sec
    while time.monotonic() < end:
        try:
            with urllib.request.urlopen(addr + "/health", timeout=3) as r:
                if r.status == 200:
                    return True
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(1.0)
    return False


def boot(py: str, bind: str):
    env = dict(os.environ)
    env.update({
        "AI_PUBLIC_TOKEN": PUBLIC_TOKEN,
        "INTERNAL_API_TOKEN": INTERNAL_TOKEN,
        "POSE_DETECTOR_POOL_SIZE": "5",   # 이 실험은 검출기를 안 쓴다 — 최솟값으로 충분
        "AI_GRPC_PORT": str(GRPC_PORT),
        "FRAME_PATH_METRICS": "false",
        "GIL_SWITCH_INTERVAL": "0.0",
        "GIL_PROBE_INTERVAL": "0.0",
        "POSE_NULL_HANDLER": "false",
        "PYTHONUNBUFFERED": "1",
    })
    log_path = "grpc_threadpool_sizing_ai.log"
    log = open(log_path, "ab")
    p = subprocess.Popen(
        [py, "-m", "uvicorn", "app.main:app", "--host", bind,
         "--port", str(HTTP_PORT), "--log-level", "warning"],
        cwd=os.path.join(ROOT, "ai-server"), env=env, stdout=log, stderr=log,
    )
    return p, log, log_path


async def fire_batch(stub, n: int, id_gen, timeout_sec: float):
    """n개의 StopAnalysis를 동시에 쏘고 (지연ms 목록, 에러 목록)을 반환한다."""
    import grpc
    import exercise_pb2

    async def one():
        sid = next(id_gen)
        req = exercise_pb2.StopRequest(session_id=sid)
        md = (("authorization", f"Bearer {INTERNAL_TOKEN}"),)
        t0 = time.perf_counter()
        try:
            await stub.StopAnalysis(req, metadata=md, timeout=timeout_sec)
            return (time.perf_counter() - t0) * 1000, None
        except grpc.aio.AioRpcError as e:
            return (time.perf_counter() - t0) * 1000, e.code().name

    results = await asyncio.gather(*(one() for _ in range(n)))
    latencies = [r[0] for r in results]
    errors = [r[1] for r in results if r[1] is not None]
    return latencies, errors


def stat(ms: list[float]) -> dict:
    s = sorted(ms)
    n = len(s)
    return dict(
        n=n,
        mean=statistics.mean(s),
        p50=s[n // 2],
        p95=s[int(n * 0.95)] if n > 1 else s[0],
        mx=s[-1],
    )


async def run_sweep(concurrencies: list[int], repeats: int, timeout_sec: float):
    import grpc
    import exercise_pb2_grpc  # noqa: F401  (존재 확인 겸 임포트 실패를 여기서 빨리 드러낸다)

    ch = grpc.aio.insecure_channel(f"127.0.0.1:{GRPC_PORT}")
    stub = exercise_pb2_grpc.ExerciseServiceStub(ch)
    id_gen = itertools.count(700_000_001)  # 실행 내내 겹치지 않는 session_id — 재사용 방지

    # 버림판 — JIT/캐시 워밍업, 결과에 안 넣는다 (measure_ai_concurrency.py와 같은 관례)
    await fire_batch(stub, max(concurrencies), id_gen, timeout_sec)

    runs: dict[int, list[list[float]]] = {c: [] for c in concurrencies}
    all_errors: dict[int, list[str]] = {c: [] for c in concurrencies}
    order = list(concurrencies)
    for r in range(repeats):
        seq = order if r % 2 == 0 else list(reversed(order))  # 판 순서 confound 방지
        for c in seq:
            latencies, errors = await fire_batch(stub, c, id_gen, timeout_sec)
            runs[c].append(latencies)
            all_errors[c].extend(errors)

    await ch.close()
    return runs, all_errors


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--python", default="")
    ap.add_argument("--bind", default="127.0.0.1")
    ap.add_argument("--concurrencies", default="1,5,8,10,12,15,20,30,50")
    ap.add_argument("--repeats", type=int, default=3)
    ap.add_argument("--timeout-sec", type=float, default=10.0)
    a = ap.parse_args()

    concurrencies = [int(x) for x in a.concurrencies.split(",")]
    py = resolve_python(a.python)
    print(f"인터프리터: {py}")
    print(f"gRPC 포트 {GRPC_PORT} (HTTP {HTTP_PORT}) · 서버 실제 max_workers={DEPLOYED_MAX_WORKERS}"
          f"(server.py를 건드리지 않는다)")
    print(f"동시성 스윕: {concurrencies} · {a.repeats}판(+버림판 1) · 판마다 순서를 뒤집는다")

    p, log, log_path = boot(py, a.bind)
    try:
        if not wait_health(f"http://127.0.0.1:{HTTP_PORT}"):
            print(f"🔴 서버가 안 떴다 — 로그 확인: {log_path}")
            return 2

        sys.path.insert(0, os.path.join(ROOT, "ai-server"))
        runs, all_errors = asyncio.run(run_sweep(concurrencies, a.repeats, a.timeout_sec))

        print()
        print(f"| {'동시성':>6} | {'n':>5} | {'평균ms':>8} | {'p50':>8} | {'p95':>8} "
              f"| {'최대ms':>8} | {'에러':>6} |")
        print("|" + "-" * 8 + "|" + "-" * 7 + "|" + "-" * 10 + "|" + "-" * 10 + "|"
              + "-" * 10 + "|" + "-" * 10 + "|" + "-" * 8 + "|")

        summary = []
        baseline_p50 = None
        for c in concurrencies:
            flat = [x for batch in runs[c] for x in batch]
            st = stat(flat)
            if baseline_p50 is None:
                baseline_p50 = st["p50"]
            mark = " 🔴" if len(concurrencies) and c > DEPLOYED_MAX_WORKERS and baseline_p50 \
                and st["p50"] > baseline_p50 * 1.5 else ""
            n_err = len(all_errors[c])
            print(f"| {c:>6} | {st['n']:>5} | {st['mean']:>8.2f} | {st['p50']:>8.2f} "
                  f"| {st['p95']:>8.2f} | {st['mx']:>8.2f} | {n_err:>6} |{mark}")
            summary.append({"concurrency": c, **st, "errors": n_err,
                             "error_codes": sorted(set(all_errors[c]))})

        print()
        print(f"기준(동시성 {concurrencies[0]}) p50: {baseline_p50:.2f}ms")
        print(f"🔴 표시 = p50이 기준의 1.5배를 넘고 max_workers({DEPLOYED_MAX_WORKERS})를 "
              f"넘는 동시성 — 큐잉 신호 후보. 위 '못 가르는 것' 절을 반드시 같이 읽을 것.")
        any_errors = any(s["errors"] for s in summary)
        if any_errors:
            print("🔴 타임아웃/에러가 관측됐다 — 어느 동시성부터인지 위 표의 '에러' 열을 볼 것.")
        else:
            print(f"타임아웃({a.timeout_sec}s 상한) 없음 — 이 스윕 범위에선 실패가 아니라 "
                  f"'느려지기만' 했다(있었다면).")

        out_path = "grpc_threadpool_sizing_result.json"
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump({
                "deployed_max_workers": DEPLOYED_MAX_WORKERS,
                "concurrencies": concurrencies,
                "repeats": a.repeats,
                "timeout_sec": a.timeout_sec,
                "results": summary,
            }, f, ensure_ascii=False, indent=2)
        print(f"\n결과: {out_path}")
        return 0
    finally:
        p.terminate()
        try:
            p.wait(timeout=15)
        except subprocess.TimeoutExpired:
            p.kill()
        log.close()


if __name__ == "__main__":
    sys.exit(main())
