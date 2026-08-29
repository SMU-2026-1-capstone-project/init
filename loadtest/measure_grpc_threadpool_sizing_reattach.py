"""gRPC 스레드풀 max_workers=10 — ReattachAnalysis 동시성 스윕 (#593 후속).

## 왜 StopAnalysis 판(`measure_grpc_threadpool_sizing.py`)으로는 부족한가

그 판의 결론(README: `results/grpc-threadpool-sizing-593/README.md`)은 "동시성 50까지
타임아웃 0건"이었다. 그런데 #593이 실제로 걱정한 시나리오 — 서킷브레이커 OPEN 시
IN_PROGRESS 세션을 자동 재부착하는 경로(#581) — 는 `StopAnalysis`가 아니라
**`ReattachAnalysis`**다. 그 판 README §4가 이미 이 갭을 적어뒀다: "이 판은 그 정확한
경로를 재지 않았다."

## StopAnalysis 판과 무엇이 다른가 — 이게 이 판을 더 무겁고 더 현실적으로 만든다

`StopAnalysis`(존재하지 않는 session_id)는 dict pop 두 번짜리라 "거의 공짜"였다.
`ReattachAnalysis`는 성공 경로를 타면 훨씬 무겁다(`exercise_servicer.py:198-303`):

1. `resolve_exercise_type` — dict 조회 (싸다)
2. `_parse_reference_poses` → `extract_angles` — 기준 좌표 JSON 파싱 + 각도 계산 (파이썬 루프)
3. **`get_pool().acquire(session_id)`** — 처음 보는 세션이면 `PoseDetector()`를 **새로
   생성한다**(MediaPipe 그래프 초기화, C++ 쪽 작업 — 메모리는 0.1MB뿐이지만 CPU 비용은
   dict pop과 차원이 다르다)
4. `get_registry().create_if_absent` — 세션 상태 딕셔너리 생성

즉 이 판은 StopAnalysis 판보다 **"gRPC 스레드풀이 실제로 뭘 큐잉시키는가"에 더 가깝다** —
운영에서 재부착이 몰릴 때 스레드 10개가 붙잡고 있는 일이 dict 연산이 아니라 MediaPipe
초기화이기 때문이다. 대신 StopAnalysis 판이 노렸던 "순수 스레드풀만 격리해서 보기"는
포기한다 — 이제 검출기 생성 비용이 섞인다. 이 판은 그 트레이드오프를 의도적으로 택했다:
**격리된 신호보다 현실에 가까운 신호.**

## 🔴 검출기 풀 크기를 크게 잡은 이유 — 또 다른 confound를 피하려고

매 호출마다 **새 session_id**를 쓴다(재부착이 몰리는 실제 시나리오 — 같은 세션이 반복
재부착되는 게 아니라 서로 다른 세션 N개가 동시에 재부착됨). `acquire()`는 세션마다 풀
자리를 하나씩 영구히 먹는다(이 스크립트는 `StopAnalysis`/`CompleteAnalysis`로 반납하지
않는다 — 반납 자체가 별도 RPC라 이 판의 관심사가 아니다). 그래서 `POSE_DETECTOR_POOL_SIZE`를
이 실행에서 나올 수 있는 총 세션 수보다 넉넉히 크게 잡는다 — 안 그러면 스윕 후반부에서
"동시 세션 상한" 실패로 응답이 갑자기 싸지면서 스레드풀 신호와 풀 고갈 신호가 섞인다.

## 🔴 이 측정이 못 가르는 것 (StopAnalysis 판과 같은 한계 + 하나 더)

- GIL과 못 가른다, 안 갈린다는 원칙은 그대로다(StopAnalysis 판 docstring 참고).
- **여기 하나 더**: 느려지는 원인이 "스레드풀 대기"인지 "`PoseDetector()` 생성 자체가 원래
  느리다"인지도 이 판은 못 가른다. 동시성 1에서의 절대값(순수 생성 비용의 근사치)과 비교하는
  것으로 방향만 잡을 수 있다 — 배분은 안 한다([[feedback_no_arbitrary_threshold_values]]와
  같은 원칙, `per-process-ceiling-cause.md` §6-1 "배분하지 않는다").

## 무대

로컬에서 된다(StopAnalysis 판과 같은 이유 — 코어 스케일링 실험이 아니다). docker-compose·
MySQL 불필요.

사용:
    python measure_grpc_threadpool_sizing_reattach.py
    python measure_grpc_threadpool_sizing_reattach.py --python /path/to/ai-server/.venv/Scripts/python.exe
    python measure_grpc_threadpool_sizing_reattach.py --concurrencies 1,5,10,15,20,30,50 --repeats 3
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

PUBLIC_TOKEN = "reattach-sizing-probe-public-token"
INTERNAL_TOKEN = "reattach-sizing-probe-internal-token"

HTTP_PORT = 8310
GRPC_PORT = 8786

DEPLOYED_MAX_WORKERS = 10  # 표시용 — server.py를 건드리지 않는다
EXERCISE_ID = 1            # analyzer_registry.py: 1 = squat (유일하게 분석기가 있는 종목)

# 검출기 풀 크기 — 이 실행이 만들어낼 수 있는 총 세션 수(discard 포함)보다 훨씬 크게.
# 실제 계산은 main()에서 스윕 크기로 하지만, 상한을 여기 넉넉히 박아둔다.
POOL_SIZE_MARGIN = 4000


def _synthetic_reference_poses():
    """33개 MediaPipe 랜드마크 전부를 채운 기준 좌표 1프레임.

    실제 자세일 필요는 없다 — `extract_angles`가 참조하는 인덱스가 EXERCISE_ANGLES
    정의마다 달라질 수 있어서, 어떤 인덱스를 참조하든 KeyError가 안 나게 0~32 전부 채운다.
    좌표를 살짝씩 벌려서 각도 계산의 벡터가 0이 되는 것만 피한다(값 자체는 이 실험의
    관심사가 아니다 — 재는 것은 "성공 경로가 도는 속도"지 "각도가 맞는가"가 아니다).
    """
    landmarks = [
        {"index": i, "x": 0.5 + 0.01 * i, "y": 0.4 + 0.015 * i, "z": 0.0, "visibility": 1.0}
        for i in range(33)
    ]
    return json.dumps(landmarks)


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


def boot(py: str, bind: str, pool_size: int):
    env = dict(os.environ)
    env.update({
        "AI_PUBLIC_TOKEN": PUBLIC_TOKEN,
        "INTERNAL_API_TOKEN": INTERNAL_TOKEN,
        "POSE_DETECTOR_POOL_SIZE": str(pool_size),
        "AI_GRPC_PORT": str(GRPC_PORT),
        "FRAME_PATH_METRICS": "false",
        "GIL_SWITCH_INTERVAL": "0.0",
        "GIL_PROBE_INTERVAL": "0.0",
        "POSE_NULL_HANDLER": "false",
        "PYTHONUNBUFFERED": "1",
    })
    log_path = "grpc_threadpool_sizing_reattach_ai.log"
    log = open(log_path, "ab")
    p = subprocess.Popen(
        [py, "-m", "uvicorn", "app.main:app", "--host", bind,
         "--port", str(HTTP_PORT), "--log-level", "warning"],
        cwd=os.path.join(ROOT, "ai-server"), env=env, stdout=log, stderr=log,
    )
    return p, log, log_path


async def fire_batch(stub, n: int, id_gen, ref_poses_json: str, timeout_sec: float,
                      batch_tag: str = "", call_log: list | None = None):
    """n개의 ReattachAnalysis를 동시에 쏘고 (지연ms 목록, 결과분류 목록)을 반환한다.

    call_log가 주어지면 호출마다 벽시계 타임스탬프까지 남긴다(#613 — 채널 상태 전환과
    개별 호출을 시간축으로 맞춰보기 위해서다. 집계 통계만으로는 "몇 시에 무슨 일이
    있었는지"를 못 본다).
    """
    import grpc
    import exercise_pb2

    async def one():
        sid = next(id_gen)
        req = exercise_pb2.ReattachRequest(
            exercise_id=EXERCISE_ID,
            session_id=sid,
            reference_poses=[exercise_pb2.PoseDataRequest(joint_coordinates=ref_poses_json)],
            persona="BEGINNER",
            initial_rep_count=0,
            elapsed_sec=0.0,
            session_nonce="",
        )
        md = (("authorization", f"Bearer {INTERNAL_TOKEN}"),)
        wall0 = time.time()
        t0 = time.perf_counter()
        try:
            resp = await stub.ReattachAnalysis(req, metadata=md, timeout=timeout_sec)
            ms = (time.perf_counter() - t0) * 1000
            outcome = "ok" if resp.success else f"fail:{resp.message}"
        except grpc.aio.AioRpcError as e:
            ms = (time.perf_counter() - t0) * 1000
            outcome = f"rpcerror:{e.code().name}"
        if call_log is not None:
            call_log.append({"batch": batch_tag, "session_id": sid, "wall0": wall0,
                              "latency_ms": ms, "outcome": outcome})
        return ms, outcome

    results = await asyncio.gather(*(one() for _ in range(n)))
    latencies = [r[0] for r in results]
    outcomes = [r[1] for r in results]
    return latencies, outcomes


async def watch_channel_state(ch, state_log: list):
    """채널 상태 전환을 폴링 없이(`wait_for_state_change`) 놓치지 않고 전부 기록한다.

    #613의 가설 — success 합계보다 서버가 완료한 요청이 더 많다(응답이 클라이언트에서
    버려진다) — 이 채널이 TRANSIENT_FAILURE 로 플래핑하는지를 직접 확인하려는 것이다.
    """
    state = ch.get_state(try_to_connect=False)
    state_log.append({"t": time.time(), "state": state.name})
    while True:
        await ch.wait_for_state_change(state)
        state = ch.get_state(try_to_connect=False)
        state_log.append({"t": time.time(), "state": state.name})


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


async def run_sweep(concurrencies: list[int], repeats: int, timeout_sec: float, host: str = "127.0.0.1"):
    import grpc
    import exercise_pb2_grpc  # noqa: F401

    ch = grpc.aio.insecure_channel(f"{host}:{GRPC_PORT}")
    stub = exercise_pb2_grpc.ExerciseServiceStub(ch)
    id_gen = itertools.count(800_000_001)  # StopAnalysis 판과 겹치지 않는 별도 범위
    ref_poses_json = _synthetic_reference_poses()

    state_log: list = []
    call_log: list = []
    watcher = asyncio.create_task(watch_channel_state(ch, state_log))

    # 버림판
    await fire_batch(stub, max(concurrencies), id_gen, ref_poses_json, timeout_sec,
                      "discard", call_log)

    runs: dict[int, list[list[float]]] = {c: [] for c in concurrencies}
    all_outcomes: dict[int, list[str]] = {c: [] for c in concurrencies}
    order = list(concurrencies)
    for r in range(repeats):
        seq = order if r % 2 == 0 else list(reversed(order))
        for c in seq:
            tag = f"r{r}_c{c}"
            latencies, outcomes = await fire_batch(stub, c, id_gen, ref_poses_json, timeout_sec,
                                                     tag, call_log)
            runs[c].append(latencies)
            all_outcomes[c].extend(outcomes)

    watcher.cancel()
    try:
        await watcher
    except asyncio.CancelledError:
        pass
    await ch.close()
    return runs, all_outcomes, state_log, call_log


def report_and_save(runs, all_outcomes, concurrencies: list[int], a, pool_size: int | None,
                     state_log: list | None = None, call_log: list | None = None) -> int:
    """스윕 결과를 표로 찍고 JSON으로 저장한다. 서버 동거/분리 두 모드가 공유한다."""
    print()
    print(f"| {'동시성':>6} | {'n':>5} | {'평균ms':>8} | {'p50':>8} | {'p95':>8} "
          f"| {'최대ms':>8} | {'success':>8} |")
    print("|" + "-" * 8 + "|" + "-" * 7 + "|" + "-" * 10 + "|" + "-" * 10 + "|"
          + "-" * 10 + "|" + "-" * 10 + "|" + "-" * 10 + "|")

    summary = []
    baseline_p50 = None
    for c in concurrencies:
        flat = [x for batch in runs[c] for x in batch]
        st = stat(flat)
        if baseline_p50 is None:
            baseline_p50 = st["p50"]
        ok = sum(1 for o in all_outcomes[c] if o == "ok")
        total = len(all_outcomes[c])
        mark = " 🔴" if c > DEPLOYED_MAX_WORKERS and baseline_p50 \
            and st["p50"] > baseline_p50 * 1.5 else ""
        print(f"| {c:>6} | {st['n']:>5} | {st['mean']:>8.2f} | {st['p50']:>8.2f} "
              f"| {st['p95']:>8.2f} | {st['mx']:>8.2f} | {ok:>4}/{total:<3} |{mark}")
        non_ok = sorted(set(o for o in all_outcomes[c] if o != "ok"))
        summary.append({"concurrency": c, **st, "success": ok, "total": total,
                         "non_ok_kinds": non_ok})

    print()
    print(f"기준(동시성 {concurrencies[0]}) p50: {baseline_p50:.2f}ms")
    print(f"🔴 표시 = p50이 기준의 1.5배를 넘고 max_workers({DEPLOYED_MAX_WORKERS})를 "
          f"넘는 동시성 — 큐잉 신호 후보. docstring의 '못 가르는 것' 절을 반드시 같이 읽을 것.")
    any_fail = any(s["success"] < s["total"] for s in summary)
    if any_fail:
        print("🔴 success가 total보다 작은 동시성이 있다 — 어느 동시성부터인지, "
              "non_ok_kinds가 rpcerror(타임아웃 등)인지 fail(정상 거절)인지 JSON에서 확인할 것.")
    else:
        print(f"실패(비-success) 없음 — 타임아웃({a.timeout_sec}s 상한) 포함 전부 success.")

    out_path = "grpc_threadpool_sizing_reattach_result.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump({
            "deployed_max_workers": DEPLOYED_MAX_WORKERS,
            "pool_size": pool_size,
            "target_host": getattr(a, "target_host", "") or "127.0.0.1(동거)",
            "concurrencies": concurrencies,
            "repeats": a.repeats,
            "timeout_sec": a.timeout_sec,
            "results": summary,
        }, f, ensure_ascii=False, indent=2)
    print(f"\n결과: {out_path}")

    # #613 — 채널 상태 전환·개별 호출 타임스탬프. 집계표만으로는 "몇 시에 무슨 일이
    # 있었는지"를 못 보여준다. TRANSIENT_FAILURE 플래핑 가설을 직접 확인하려는 계측.
    if state_log is not None:
        state_path = "grpc_channel_state_log.json"
        with open(state_path, "w", encoding="utf-8") as f:
            json.dump(state_log, f, ensure_ascii=False, indent=2)
        transitions = [s["state"] for s in state_log]
        n_transient = sum(1 for s in transitions if s == "TRANSIENT_FAILURE")
        print(f"채널 상태 전환: {len(state_log)}회 (TRANSIENT_FAILURE 진입 {n_transient}회) "
              f"— {state_path}")
    if call_log is not None:
        call_path = "grpc_call_log.json"
        with open(call_path, "w", encoding="utf-8") as f:
            json.dump(call_log, f, ensure_ascii=False, indent=2)
        print(f"개별 호출 {len(call_log)}건 — {call_path}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--python", default="")
    ap.add_argument("--bind", default="127.0.0.1")
    ap.add_argument("--concurrencies", default="1,5,8,10,12,15,20,30,50")
    ap.add_argument("--repeats", type=int, default=3)
    ap.add_argument("--timeout-sec", type=float, default=10.0)
    # 2대 분리 판(#613 — 클라이언트·서버 동거가 교란 요인인지 가른다).
    ap.add_argument("--serve-only", action="store_true",
                     help="서버만 띄우고 대기한다(스윕 없음) — 대상 박스에서 쓴다")
    ap.add_argument("--pool-size", type=int, default=0,
                     help="--serve-only 전용. 0이면 5000(여유 큰 고정값)")
    ap.add_argument("--target-host", default="",
                     help="주어지면 서버를 안 띄우고 이 호스트(:%d)로 붙는다 — 부하기 박스에서 쓴다" % GRPC_PORT)
    a = ap.parse_args()

    py = resolve_python(a.python)

    # ── 대상 박스: 서버만 띄우고 대기 ──────────────────────────────────
    if a.serve_only:
        pool_size = a.pool_size or 5000
        print(f"[serve-only] 인터프리터: {py}")
        print(f"[serve-only] bind={a.bind} · gRPC {GRPC_PORT}(HTTP {HTTP_PORT}) · pool={pool_size}")
        p, log, log_path = boot(py, a.bind, pool_size)
        try:
            if not wait_health(f"http://127.0.0.1:{HTTP_PORT}"):
                print(f"🔴 서버가 안 떴다 — 로그 확인: {log_path}")
                return 2
            print("SERVER READY — Ctrl+C 로 종료")
            p.wait()
            return 0
        except KeyboardInterrupt:
            return 0
        finally:
            p.terminate()
            try:
                p.wait(timeout=15)
            except subprocess.TimeoutExpired:
                p.kill()
            log.close()

    concurrencies = [int(x) for x in a.concurrencies.split(",")]
    sys.path.insert(0, os.path.join(ROOT, "ai-server"))

    # ── 부하기 박스: 서버 없이 원격으로 붙는다 ─────────────────────────
    if a.target_host:
        print(f"[target-host={a.target_host}] gRPC {GRPC_PORT}(HTTP {HTTP_PORT}) 로 붙는다"
              f" — 이 박스는 서버를 안 띄운다")
        print(f"동시성 스윕: {concurrencies} · {a.repeats}판(+버림판 1) · 판마다 순서를 뒤집는다")
        if not wait_health(f"http://{a.target_host}:{HTTP_PORT}"):
            print(f"🔴 대상 서버({a.target_host})가 응답하지 않는다 — --serve-only 로 먼저 띄웠는지,"
                  f" 보안그룹이 {HTTP_PORT}/{GRPC_PORT} 를 열었는지 확인할 것")
            return 2
        runs, all_outcomes, state_log, call_log = asyncio.run(
            run_sweep(concurrencies, a.repeats, a.timeout_sec, host=a.target_host))
        return report_and_save(runs, all_outcomes, concurrencies, a, pool_size=None,
                                state_log=state_log, call_log=call_log)

    # ── 기본: 한 박스에 서버·클라이언트 동거(기존 동작) ────────────────
    total_calls = max(concurrencies) + (a.repeats * sum(concurrencies))
    pool_size = total_calls + POOL_SIZE_MARGIN

    print(f"인터프리터: {py}")
    print(f"gRPC 포트 {GRPC_PORT} (HTTP {HTTP_PORT}) · 서버 실제 max_workers={DEPLOYED_MAX_WORKERS}"
          f"(server.py를 건드리지 않는다)")
    print(f"POSE_DETECTOR_POOL_SIZE={pool_size} (예상 총 호출 {total_calls} + 여유"
          f" {POOL_SIZE_MARGIN} — 풀 고갈이 신호에 안 섞이게)")
    print(f"동시성 스윕: {concurrencies} · {a.repeats}판(+버림판 1) · 판마다 순서를 뒤집는다")

    p, log, log_path = boot(py, a.bind, pool_size)
    try:
        if not wait_health(f"http://127.0.0.1:{HTTP_PORT}"):
            print(f"🔴 서버가 안 떴다 — 로그 확인: {log_path}")
            return 2

        runs, all_outcomes, state_log, call_log = asyncio.run(
            run_sweep(concurrencies, a.repeats, a.timeout_sec))
        return report_and_save(runs, all_outcomes, concurrencies, a, pool_size=pool_size,
                                state_log=state_log, call_log=call_log)
    finally:
        p.terminate()
        try:
            p.wait(timeout=15)
        except subprocess.TimeoutExpired:
            p.kill()
        log.close()


if __name__ == "__main__":
    sys.exit(main())
