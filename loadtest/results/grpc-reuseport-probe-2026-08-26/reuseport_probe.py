"""SO_REUSEPORT + gRPC 영속 채널이 실제로 트래픽을 프로세스 하나에 못 박는지 직접 잰다.

## 왜

사용자가 다른 세션(추정: `grpc-conn-reuse-test` EC2 인스턴스)에서 받은 결론을 전해왔다 —
"AI 프로세스 3개 + SO_REUSEPORT는 뜬다. 그런데 Spring이 지금처럼 채널 하나를 계속
재사용하면 처음 연결을 받은 프로세스 하나로 트래픽이 계속 몰리고 나머지는 논다."
이 메커니즘은 `docs/decisions/ai-sticky-routing.md` §5-1의 예측("gRPC는 L4로 안 갈린다 —
채널 하나가 한 인스턴스에 못 박힌다")과 같은 방향이지만, 이 저장소에는 그걸 실측한
커밋·S3 산출물이 없었다. 여기서 직접 잰다.

## 무엇을 재는가

`ai-server/app/grpc/server.py:93`이 `add_insecure_port`를 호출할 때 SO_REUSEPORT
옵션을 명시하지 않는다 — grpc-core의 기본값(활성)에 기댄다. 3개 프로세스를 **같은
gRPC 포트**로 띄우고(HTTP 포트는 각자 다르게 둬서 "어느 프로세스가 세션을 받았는지"를
진단 목적으로만 구분한다), **채널 하나**로 세션 여러 개를 열어본다.

`NO_LEASE`(`pose.py:131~137`)가 "이 프로세스가 이 세션의 StartAnalysis를 못 받았다"는
정확한 신호다 — 세션마다 3개 HTTP 포트를 다 찔러서 어느 포트만 `NO_LEASE`가 아닌지
보면 그 세션이 실제로 어느 프로세스에 붙었는지 알 수 있다. ai-server 코드는 0줄도 안
바뀐다 — 기존 엔드포인트(`/health`·gRPC `StartAnalysis`·`/api/v1/pose`)만 쓴다.

## 판정선 (실행 전)

- **ㄱ 채널 하나 = 프로세스 하나** — 채널1로 연 세션 N개가 전부 같은 HTTP 포트에서만
  `NO_LEASE`가 아니면 🟢 확증. 세션들이 여러 포트에 흩어지면 🔴 반증(SO_REUSEPORT가
  RPC 단위로 재분배한다는 뜻이라 더 조사 필요)
- **ㄴ 새 채널 = 재추첨 기회** — 채널2(새 연결)가 채널1과 **다른** 프로세스에 붙으면
  "연결을 새로 열면 분산될 수 있다"가 선다. 같은 프로세스면 우연이거나 SO_REUSEPORT의
  해시가 이 클라이언트 조건에서 늘 같은 프로세스를 고른다는 뜻 — 반복해서 본다
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))
FRAMES = os.path.join(ROOT, "loadtest", "results", "coresidency-2026-08-15", "frames.json")

VENV_CANDIDATES = (
    os.path.join(ROOT, "ai-server", ".venv", "Scripts", "python.exe"),
    os.path.join(ROOT, "ai-server", ".venv", "bin", "python"),
)

PUBLIC_TOKEN = "reuseport-probe-public-token"
INTERNAL_TOKEN = "reuseport-probe-internal-token"

N = 3
BASE_HTTP = 8100
GRPC_PORT = 8685          # 🔑 셋 다 같은 포트 — SO_REUSEPORT 가 실제로 작동하는지가 이 실험의 전제다
HTTP_PORTS = [BASE_HTTP + i for i in range(N)]


def resolve_python(explicit):
    if explicit:
        return explicit
    for c in VENV_CANDIDATES:
        if os.path.exists(c):
            return c
    raise SystemExit("🔴 ai-server venv 를 못 찾았다 — --python 으로 직접 줄 것")


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


def boot(idx, py, bind):
    env = dict(os.environ)
    env.update({
        "AI_PUBLIC_TOKEN": PUBLIC_TOKEN,
        "INTERNAL_API_TOKEN": INTERNAL_TOKEN,
        "POSE_DETECTOR_POOL_SIZE": "40",
        "AI_GRPC_PORT": str(GRPC_PORT),          # 🔑 3개 프로세스가 전부 같은 값
        "FRAME_PATH_METRICS": "false",
        "GIL_SWITCH_INTERVAL": "0.0",
        "GIL_PROBE_INTERVAL": "0.0",
        "POSE_NULL_HANDLER": "false",
        "PYTHONUNBUFFERED": "1",
    })
    log = open(f"/root/reuseport_ai{idx}.log", "ab") if os.path.isdir("/root") else \
        open(f"reuseport_ai{idx}.log", "ab")
    p = subprocess.Popen(
        [py, "-m", "uvicorn", "app.main:app", "--host", bind,
         "--port", str(HTTP_PORTS[idx]), "--log-level", "warning"],
        cwd=os.path.join(ROOT, "ai-server"), env=env, stdout=log, stderr=log,
    )
    return p, log


def http(url, method="GET", body=None, headers=None, timeout=15):
    data = json.dumps(body).encode() if body is not None else None
    h = {"Content-Type": "application/json"}
    h.update(headers or {})
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return 0, repr(e)


def classify(status, text):
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


def open_channel_and_start(session_ids, exercise_id=1):
    """세션 여러 개를 **채널 하나**로 연다. 반환: (owning_port_guess 없이) 성공한 sid 목록."""
    sys.path.insert(0, os.path.join(ROOT, "ai-server"))
    import grpc                                   # noqa: E402
    import exercise_pb2                           # noqa: E402
    import exercise_pb2_grpc                      # noqa: E402

    md = [("authorization", f"Bearer {INTERNAL_TOKEN}")]
    ch = grpc.insecure_channel(f"127.0.0.1:{GRPC_PORT}")
    stub = exercise_pb2_grpc.ExerciseServiceStub(ch)
    opened = []
    for sid in session_ids:
        req = exercise_pb2.AnalyzeRequest(
            exercise_id=exercise_id, session_id=sid,
            reference_source="reuseport-probe", persona="BEGINNER", session_nonce="")
        try:
            resp = stub.StartAnalysis(req, metadata=md, timeout=15)
            if resp.success:
                opened.append(sid)
        except Exception as e:
            print(f"  ⚠️ 세션 {sid} StartAnalysis 실패: {e!r}")
    ch.close()      # 🔴 채널을 명시적으로 닫는다 — 다음 채널이 반드시 «새 연결»이 되게
    return opened


def probe_owner(session_ids, frames, token):
    """세션마다 HTTP 포트 3개를 다 찔러 어느 포트만 NO_LEASE 가 아닌지 본다."""
    hdr = {"Authorization": "Bearer " + token}
    dist = {p: 0 for p in HTTP_PORTS}
    ambiguous = []
    for i, sid in enumerate(session_ids):
        owners = []
        for port in HTTP_PORTS:
            payload = {
                "image": frames[i % len(frames)], "exercise_type": "squat",
                "session_id": sid, "timestamp_sec": 0.0,
            }
            status, text = http(f"http://127.0.0.1:{port}/api/v1/pose", "POST", payload, hdr)
            outcome = classify(status, text)
            if outcome != "nolease":
                owners.append(port)
        if len(owners) == 1:
            dist[owners[0]] += 1
        else:
            ambiguous.append((sid, owners))
    return dist, ambiguous


def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--python", default="")
    ap.add_argument("--bind", default="127.0.0.1")
    ap.add_argument("--sessions-per-channel", type=int, default=30, dest="n_sessions")
    ap.add_argument("--channels", type=int, default=3)
    a = ap.parse_args()

    py = resolve_python(a.python)
    print(f"인터프리터: {py}")
    print(f"gRPC 포트 {GRPC_PORT} 를 프로세스 {N}개가 공유한다 (HTTP는 {HTTP_PORTS})")

    procs = [boot(i, py, a.bind) for i in range(N)]
    try:
        ok = all(wait_health(f"http://127.0.0.1:{p}") for p in HTTP_PORTS)
        if not ok:
            print("🔴 3개 프로세스가 전부 뜨지 않았다 — 이것 자체가 결과일 수 있다"
                  "(같은 gRPC 포트 바인드가 막혔을 가능성)")
            return 2
        print(f"🟢 프로세스 {N}개가 전부 같은 gRPC 포트 {GRPC_PORT}에서 떴다 — "
              f"SO_REUSEPORT 바인드 자체는 된다")

        frames = json.load(open(FRAMES, encoding="utf-8"))["frames"]
        results = []
        base_sid = 900001
        for c in range(a.channels):
            sids = list(range(base_sid, base_sid + a.n_sessions))
            base_sid += a.n_sessions
            print(f"\n──── 채널 {c + 1}/{a.channels} — 세션 {sids[0]}~{sids[-1]} ────")
            opened = open_channel_and_start(sids)
            print(f"  StartAnalysis 성공: {len(opened)}/{len(sids)}")
            dist, ambiguous = probe_owner(opened, frames, PUBLIC_TOKEN)
            print(f"  포트별 소유 세션 수: {dist}")
            if ambiguous:
                print(f"  ⚠️ 모호(0개 또는 2개 이상 포트가 응답): {ambiguous[:5]}"
                      f"{' ...' if len(ambiguous) > 5 else ''}")
            owning_ports = [p for p, n in dist.items() if n > 0]
            results.append({
                "channel": c + 1, "opened": len(opened), "dist": dist,
                "owning_ports": owning_ports, "ambiguous": len(ambiguous),
            })

        print("\n──── 판정 ────")
        summary_path = "/root/reuseport_summary.json" if os.path.isdir("/root") \
            else "reuseport_summary.json"
        with open(summary_path, "w", encoding="utf-8") as f:
            json.dump({"http_ports": HTTP_PORTS, "grpc_port": GRPC_PORT,
                       "results": results}, f, ensure_ascii=False, indent=2)

        single_owner_channels = [r for r in results if len(r["owning_ports"]) == 1]
        if len(single_owner_channels) == len(results):
            owners = [r["owning_ports"][0] for r in results]
            print(f"🟢 판정선 ㄱ 확증 — 채널마다 세션이 정확히 포트 하나로만 몰렸다: {owners}")
            if len(set(owners)) > 1:
                print("🟢 판정선 ㄴ 확증 — 채널을 새로 열 때마다 다른 프로세스로 갔다"
                      "(SO_REUSEPORT가 새 연결마다 재추첨한다)")
            else:
                print("🟡 판정선 ㄴ 미확증 — 채널을 새로 열어도 같은 프로세스로 갔다"
                      "(이 조건에서는 매번 같은 프로세스가 뽑혔다 — 우연인지 조건인지"
                      " 반복 없이는 못 가른다)")
        else:
            print("🔴 판정선 ㄱ 반증 — 한 채널의 세션들이 포트 하나로 안 몰렸다."
                  " SO_REUSEPORT가 RPC 단위로 재분배했거나, 다른 설명이 필요하다.")
        print(f"\n결과: {summary_path}")
        return 0
    finally:
        for p, log in procs:
            p.terminate()
            try:
                p.wait(timeout=15)
            except subprocess.TimeoutExpired:
                p.kill()
            log.close()


if __name__ == "__main__":
    sys.exit(main())
