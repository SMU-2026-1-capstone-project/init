"""VM 간 세션 어피니티 검증 — docs/decisions/ai-cross-vm-affinity-probe.md 실행판.

`docker exec`로 VM B의 shadowfit-ai 컨테이너 안에서 돈다(grpc·exercise_pb2가 이미 컨테이너
이미지에 있다). B=localhost, A=private IP로 session_id % 2 해시 라우팅.

실제 2026-08-28 라운드에서 쓴 순서(수동, 오케스트레이터 스크립트 없음 — 이 판은 규모가
작아서 그럴 필요가 없었다):

    # 0. VM B 호스트에 frames.json 이 있어야 한다(git 체크아웃에 이미 있음) — 컨테이너
    #    안에는 COPY 시점 스냅샷이라 없다. 호스트→컨테이너로 복사:
    docker cp /opt/shadowfit/loadtest/results/coresidency-2026-08-15/frames.json \
        shadowfit-ai:/app/frames.json
    # 이 스크립트도 컨테이너에 복사(docker cp), 그 다음:
    docker exec shadowfit-ai python affinity_probe.py open
    docker exec shadowfit-ai python affinity_probe.py frames pre
    # (이 시점에 다른 SSH 세션에서 VM A: docker kill shadowfit-ai)
    docker exec shadowfit-ai python affinity_probe.py frames post

FRAMES_PATH 는 컨테이너 안 기준 `/app/frames.json` 로 이미 맞춰뒀다(원본 판에서는 스크립트
안 상수를 직접 sed 로 고쳤다 — 여기 미리 반영해둔다).
"""
import base64
import json
import sys
import time

import grpc
import exercise_pb2
import exercise_pb2_grpc
import urllib.request
import urllib.error

INTERNAL_TOKEN = "cross-vm-affinity-internal"
PUBLIC_TOKEN = "cross-vm-affinity-public"

BACKENDS = {
    "A": {"grpc": "172.31.42.106:8585", "http": "http://172.31.42.106:8000"},
    "B": {"grpc": "localhost:8585", "http": "http://localhost:8000"},
}

BASE_SID = 910000001
N = 20
MANIFEST = "/tmp/affinity_manifest.json"
FRAMES_PATH = "/app/frames.json"  # docker cp로 미리 넣어둘 것 — 위 사용법 참고


def load_frame():
    with open(FRAMES_PATH) as f:
        return json.load(f)["frames"][0]


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


def cmd_open():
    sids = list(range(BASE_SID, BASE_SID + N))
    assignment = {}
    results = []
    md = (("authorization", f"Bearer {INTERNAL_TOKEN}"),)
    for sid in sids:
        key = "A" if sid % 2 == 0 else "B"
        assignment[str(sid)] = key
        ch = grpc.insecure_channel(BACKENDS[key]["grpc"])
        stub = exercise_pb2_grpc.ExerciseServiceStub(ch)
        req = exercise_pb2.AnalyzeRequest(
            exercise_id=1, session_id=sid,
            reference_source="cross-vm-affinity-probe", persona="BEGINNER", session_nonce="")
        try:
            resp = stub.StartAnalysis(req, metadata=md, timeout=15)
            results.append((sid, key, bool(resp.success)))
        except Exception as e:
            results.append((sid, key, f"ERROR:{e!r}"))
        ch.close()

    with open(MANIFEST, "w") as f:
        json.dump(assignment, f)

    ok = sum(1 for _, _, s in results if s is True)
    print(f"세션 열기: {ok}/{len(results)} 성공")
    for sid, key, s in results:
        if s is not True:
            print(f"  실패 session={sid} backend={key} -> {s}")


def cmd_frames(phase):
    with open(MANIFEST) as f:
        assignment = json.load(f)
    frame_b64 = load_frame()
    hdr = {"Authorization": f"Bearer {PUBLIC_TOKEN}"}

    outcomes = {"A": [], "B": []}
    for sid_str, key in assignment.items():
        sid = int(sid_str)
        url = BACKENDS[key]["http"] + "/api/v1/pose"
        payload = {"image": frame_b64, "exercise_type": "squat",
                   "session_id": sid, "timestamp_sec": 0.0}
        status, text = http(url, "POST", payload, hdr)
        outcome = classify(status, text)
        outcomes[key].append((sid, outcome))

    print(f"=== phase={phase} ===")
    for key in ("A", "B"):
        n_ok = sum(1 for _, o in outcomes[key] if o == "ok")
        n_total = len(outcomes[key])
        others = [o for _, o in outcomes[key] if o != "ok"]
        print(f"  backend {key}: {n_ok}/{n_total} ok, 나머지={others}")
    with open(f"/tmp/affinity_result_{phase}.json", "w") as f:
        json.dump(outcomes, f)


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "open":
        cmd_open()
    elif cmd == "frames":
        cmd_frames(sys.argv[2])
    else:
        raise SystemExit(f"unknown cmd {cmd}")
