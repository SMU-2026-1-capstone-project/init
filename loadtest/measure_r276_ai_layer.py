#!/usr/bin/env python3
"""#276 ③ — AI 재시도 계층까지 실제로 통과시켜 «두 겹 소진(exhausted)» 비율을 잰다.

왜 이 rig 인가
────────────────────────────────────────────────────────────────────────
지금까지의 모든 r276 라운드(measure_r276_*.sh)는 ghz 가 Spring gRPC
`SavePoseDataBatch` 를 **직접** 때렸다. 그건 Spring 내부 데드락 재시도(상한 5,
백오프 0ms)만 잰 것이고, AI 서버의 실제 바깥쪽 재시도 계층
(`ai-server/app/grpc/spring_client.py::report_pose_data_batch` — 3회 시도,
1s→3s 백오프, 시도마다 5초 gRPC 데드라인)을 통과시킨 적이 한 번도 없다.

`r276-retry-latency-2026-08-27` 이 Spring 내부 재시도 1회 왕복의 지연을 처음
쟀더니 p99 3.989s · max 4.343s 였다 — AI 쪽 5초 데드라인에 근접/육박한다.
즉 Spring 이 정상적으로 재시도를 도는 중에도 AI 가 먼저 DEADLINE_EXCEEDED 로
포기할 가능성이 있는데, 그 상호작용은 한 번도 실측된 적이 없다. 이 rig 이 그
공백을 메운다.

이 rig 은 ghz 대신 **AI 서버의 실제 프로덕션 함수**
(`app.grpc.spring_client.report_pose_data_batch`)를 동시 스레드로 직접
호출한다. HTTP로 ai-server 전체를 흉내내지 않는다 — 그 함수 자체가 측정
대상이다. 페이로드·중복 조건(rep_number 고정 → 재전송이 원본과 겹침)은
`loadtest/ghz/gen_batch_multi.py --duplicate-keys` 와 동일한 규칙을 그대로
따른다(#271 판정: 같은 값이라야 데드락 조건이 성립한다).

가정 — 새로 고르는 값이 아니라 기존 라운드와 비교 가능하게 유지하려는 재사용:
  · 동시성 16, 중복키 100% — `r276-ceiling-rank-aws-2026-08-26`,
    `r276-retry-latency-2026-08-27` 과 같은 조건
  · 세션 901-1000(100개), 배치당 25프레임 — `measure_r276_app_retry.sh` 기본값과 동일
  · Spring 쪽 상한·백오프는 **배포 기본값 그대로**(건드리지 않는다) — 지금
    이 값이 AI 계층과 만났을 때 무엇을 만드는지가 질문이지, 팔 비교가 아니다

이 rig 은 **관측**이다. 새 임계값을 정하지 않는다
([[feedback_no_arbitrary_threshold_values]]) — `docs/decisions/slo-baseline.md`
§4-9 가 이미 「exhausted 는 1건이라도 = 조사」로 판정선을 확정해 뒀다. 이 rig 은
그 판정선이 처음으로 관측치를 얻게 한다.

한계 (실행 전에 적어둔다):
  · 부하기(이 스크립트)가 대상과 같은 박스에 산다 — 절대 지연·처리량은 이 환경
    고유([[project_loadtest_env_constraint]]). 08-27 라운드와 「같은 등급의
    박스」에서 돌려야 그 라운드의 p99/max 와 비교할 수 있다
  · Python GIL 하에서 스레드 기반 동시성이 실제 uvicorn/gRPC 서버 프로세스의
    동시성 프로파일과 100% 같지는 않다 — 다만 이 함수가 매 attempt 마다
    블로킹 gRPC 호출 + time.sleep 이라 대부분 GIL 을 놓는 I/O 구간이고, 순수
    CPU 바운드가 아니라서 왜곡은 작을 것으로 본다(검증하지 않은 가정)
  · `_is_retryable` 몽키패치는 이 rig 안에서만 살아 있는 관측 훅이다 — 원본
    동작(리턴값)은 그대로 위임하므로 프로덕션 로직을 바꾸지 않는다

사용법 (대상 박스에서, ai-server 의존성이 설치된 venv 안에서):
  BACKEND_GRPC_ADDRESS 인자로 Spring 주소를 주고, --token 으로 Spring/AI
  양쪽에 공통인 INTERNAL_API_TOKEN 값을 준다(.env 에서 그대로 읽어 넘길 것).

  python loadtest/measure_r276_ai_layer.py \\
      --backend-address localhost:6565 \\
      --token "$INTERNAL_API_TOKEN" \\
      --sessions 901-1000 --concurrency 16 --reqs 300 --blocks 6
"""
from __future__ import annotations

import argparse
import collections
import json
import os
import subprocess
import sys
import threading
import time
import urllib.request


def parse_range(spec: str) -> list[int]:
    lo, hi = spec.split("-")
    return list(range(int(lo), int(hi) + 1))


def build_arg_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ai-server-dir", default="ai-server", help="ai-server 저장소 루트 (exercise_pb2.py 가 있는 곳)")
    ap.add_argument("--backend-address", required=True, help="Spring gRPC 주소 (예: localhost:6565)")
    ap.add_argument("--token", required=True, help="INTERNAL_API_TOKEN — Spring·AI 양쪽과 같은 값이어야 한다")
    ap.add_argument("--timeout-sec", type=float, default=5.0, help="AI→Spring 호출 데드라인 (기본 5.0s, 배포 기본값)")
    ap.add_argument("--sessions", default="901-1000")
    ap.add_argument("--reps", type=int, default=25, help="배치당 프레임 수")
    ap.add_argument("--concurrency", type=int, default=16)
    ap.add_argument("--reqs", type=int, default=300, help="블록당 요청 수")
    ap.add_argument("--blocks", type=int, default=6, help="반복 블록 수 (첫 블록은 warm-up으로 버린다)")
    ap.add_argument("--db-container", default="shadowfit-mysql")
    ap.add_argument("--db-name", default="shadowfit")
    ap.add_argument("--db-password", default=os.environ.get("MYSQL_ROOT_PASSWORD", "1234"))
    ap.add_argument("--actuator", default="http://localhost:9090/actuator/prometheus")
    ap.add_argument("--out", default=None, help="결과 디렉터리 (기본: loadtest/results/r276-ai-layer-<오늘날짜>)")
    return ap


def make_pose_frames(pb2_module, reps: int, rep_number: int):
    frames = []
    feedback_types = ["", "", "KNEE_OUT", "BACK_BENT", "HIP_HIGH", "KNEE_IN", "", "KNEE_OUT"]
    for f in range(reps):
        landmarks = []
        for i in range(33):
            base = (f * 31 + i * 7) % 1000 / 1000.0
            landmarks.append({
                "x": round(0.30 + base * 0.40, 6),
                "y": round(0.20 + ((base * 17) % 1.0) * 0.60, 6),
                "z": round(-0.25 + ((base * 13) % 1.0) * 0.50, 6),
                "visibility": round(0.85 + ((base * 11) % 1.0) * 0.15, 6),
            })
        frames.append(pb2_module.PoseDataRequest(
            timestamp_sec=round(f * 0.1, 1),
            joint_coordinates=json.dumps(landmarks, separators=(",", ":")),
            sync_rate=round(45.0 + (f * 7 % 50), 2),
            feedback_message=feedback_types[f % len(feedback_types)],
            # 🔴 duplicate-keys 조건: 고정값. 요청마다 달라지면 uk_pose_event 충돌이 안 생긴다(#271).
            rep_number=rep_number,
        ))
    return frames


def main() -> int:
    args = build_arg_parser().parse_args()

    # app.config.Settings 가 임포트 시점에 환경변수를 읽으므로, ai-server 모듈을 불러오기
    # *전에* 환경을 맞춰야 한다.
    os.environ["BACKEND_GRPC_ADDRESS"] = args.backend_address
    os.environ["BACKEND_GRPC_TIMEOUT_SECONDS"] = str(args.timeout_sec)
    os.environ["INTERNAL_API_TOKEN"] = args.token
    # AI_PUBLIC_TOKEN 은 이 rig 이 안 쓰지만, config.py 의 _assert_tokens_separated() 가
    # 두 토큰이 «같으면» 기동을 거부한다(#230) — 다르게만 채운다.
    os.environ.setdefault("AI_PUBLIC_TOKEN", "unused-by-r276-ai-layer-rig")
    # 검출기 풀 등 무거운 자원 산정을 건드리지 않도록, 이 rig 이 실제로 쓰는 모듈 밖의
    # 나머지 설정은 기본값 그대로 둔다.

    sys.path.insert(0, os.path.abspath(args.ai_server_dir))

    try:
        import exercise_pb2  # noqa: E402
        from app.grpc import spring_client  # noqa: E402
        from app.observability import metrics as ai_metrics  # noqa: E402
    except Exception as exc:  # pragma: no cover
        print(f"🔴 ai-server 모듈 임포트 실패 — --ai-server-dir 와 venv 의존성을 확인할 것: {exc}", file=sys.stderr)
        return 1

    # ── 관측 훅 — 프로덕션 코드는 안 건드리고, 매 attempt 의 grpc 상태코드만 훔쳐본다.
    #    _is_retryable 은 report_pose_data_batch·report_complete_analysis 양쪽에서
    #    grpc.RpcError 를 받을 때마다 호출되므로, 여기가 «모든 attempt 의 코드」를 보는
    #    유일한 공용 지점이다. 원래 리턴값은 그대로 위임한다 — 동작을 바꾸지 않는다.
    code_counts: collections.Counter = collections.Counter()
    code_lock = threading.Lock()
    _orig_is_retryable = spring_client._is_retryable

    def _watched_is_retryable(e):
        with code_lock:
            code_counts[e.code().name if e.code() else "UNKNOWN"] += 1
        return _orig_is_retryable(e)

    spring_client._is_retryable = _watched_is_retryable

    sessions = parse_range(args.sessions)
    lo, hi = sessions[0], sessions[-1]
    want = len(sessions)

    def db(sql: str) -> str:
        p = subprocess.run(
            ["docker", "exec", "-i", "-e", f"MYSQL_PWD={args.db_password}", args.db_container,
             "mysql", "-uroot", "-N", args.db_name, "-e", sql],
            capture_output=True, text=True,
        )
        if p.returncode != 0:
            raise RuntimeError(f"DB 명령 실패: {p.stderr.strip()}")
        return p.stdout.strip()

    print("## [0] 사전 확인")
    seeded = int(db(f"SELECT COUNT(*) FROM exercise_sessions WHERE id BETWEEN {lo} AND {hi};") or "0")
    if seeded != want:
        print(f"🔴 세션 시드가 {want}개가 아니다 ({seeded}) — bootstrap 의 세션 시드 범위를 확인할 것", file=sys.stderr)
        return 1
    print(f"  세션 {lo}~{hi} · {seeded}개")

    uk = db(f"""SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema='{args.db_name}' AND table_name='pose_data' AND index_name='uk_pose_event';""")
    if uk.strip() != "4":
        print(f"🔴 uk_pose_event 가 4열이 아니다 ({uk}) — 이 조건에서는 데드락이 안 난다", file=sys.stderr)
        return 1
    print("  uk_pose_event 4열 ✅")

    inprog = int(db(f"SELECT COUNT(*) FROM exercise_sessions WHERE id BETWEEN {lo} AND {hi} AND status='IN_PROGRESS';") or "0")
    if inprog != want:
        print(f"  IN_PROGRESS 가 {inprog}/{want} 다 — 무대를 세운다")
        db(f"""UPDATE exercise_sessions SET status='IN_PROGRESS', end_time=NULL, last_active_at=NOW()
               WHERE id BETWEEN {lo} AND {hi};""")
    print("  세션 상태 IN_PROGRESS ✅")

    print("## [1] 페이로드 — 중복 조건 (세션당 고정 pose_data, rep_number=0)")
    pose_by_session = {s: make_pose_frames(exercise_pb2, args.reps, rep_number=0) for s in sessions}
    print(f"  세션 {want}개 × 프레임 {args.reps}개 준비 완료")

    def reset_stage() -> None:
        db(f"DELETE FROM pose_data WHERE session_id BETWEEN {lo} AND {hi};")
        db(f"""UPDATE exercise_sessions SET last_active_at=NOW()
               WHERE id BETWEEN {lo} AND {hi};""")

    def ai_counter(outcome: str, rpc: str = "SavePoseDataBatch") -> float:
        return ai_metrics.spring_callback_total.labels(rpc=rpc, outcome=outcome)._value.get()

    def spring_counter(outcome: str):
        try:
            text = urllib.request.urlopen(args.actuator, timeout=5).read().decode("utf-8", "replace")
        except Exception:
            return None
        needle = f'outcome="{outcome}"'
        for line in text.splitlines():
            if line.startswith("shadowfit_pose_batch_deadlock_retries_total{") and needle in line:
                try:
                    return float(line.rsplit(" ", 1)[-1])
                except ValueError:
                    return None
        return 0.0

    def worker_task(i: int) -> None:
        sess = sessions[i % len(sessions)]
        try:
            spring_client.report_pose_data_batch(sess, pose_by_session[sess])
        except Exception as exc:  # 이 함수는 return 으로 끝나는 것이 정상 계약이다 — 여기 걸리면 별도 결함이다
            print(f"🔴 worker 예외(정상 계약 위반) session={sess}: {exc!r}", file=sys.stderr)

    from concurrent.futures import ThreadPoolExecutor

    print(f"## [2] 스윕 — 동시성 {args.concurrency} × {args.blocks}블록(첫 블록 버림) · 판당 {args.reqs}요청")
    results = []
    ai_outcomes = ("ok", "retried", "rejected", "exhausted")
    spring_outcomes = ("retried", "recovered", "exhausted")

    for b in range(args.blocks):
        reset_stage()
        ai_before = {o: ai_counter(o) for o in ai_outcomes}
        sp_before = {o: spring_counter(o) for o in spring_outcomes}
        code_counts.clear()

        t0 = time.time()
        with ThreadPoolExecutor(max_workers=args.concurrency) as ex:
            list(ex.map(worker_task, range(args.reqs)))
        elapsed = time.time() - t0

        ai_after = {o: ai_counter(o) for o in ai_outcomes}
        sp_after = {o: spring_counter(o) for o in spring_outcomes}
        rows = int(db(f"SELECT COUNT(*) FROM pose_data WHERE session_id BETWEEN {lo} AND {hi};") or "0")

        block = {
            "block": b,
            "dropped": b == 0,
            "elapsed_sec": round(elapsed, 1),
            "ai_delta": {o: (ai_after[o] - ai_before[o]) for o in ai_outcomes},
            "spring_delta": {
                o: (None if (sp_after[o] is None or sp_before[o] is None) else sp_after[o] - sp_before[o])
                for o in spring_outcomes
            },
            "rpc_status_codes": dict(code_counts),
            "rows": rows,
        }
        results.append(block)
        tag = " (버림)" if b == 0 else ""
        print(f"  블록 {b}{tag}: {json.dumps(block, ensure_ascii=False)}")

    out_dir = args.out or f"loadtest/results/r276-ai-layer-{time.strftime('%Y-%m-%d')}"
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "raw.json"), "w", encoding="utf-8") as fp:
        json.dump({
            "condition": {
                "sessions": args.sessions,
                "reps": args.reps,
                "concurrency": args.concurrency,
                "reqs_per_block": args.reqs,
                "blocks": args.blocks,
                "backend_grpc_timeout_sec": args.timeout_sec,
            },
            "blocks": results,
        }, fp, ensure_ascii=False, indent=2)
    with open(os.path.join(out_dir, "ai_metrics_snapshot.prom"), "wb") as fp:
        fp.write(ai_metrics.render())

    print(f"## [3] 완료 — {out_dir}/raw.json · ai_metrics_snapshot.prom")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
