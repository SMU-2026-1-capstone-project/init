"""그룹 WebSocket 핸드셰이크 DB 조회 비용 — 그룹 전원이 몰려 접속하는 순간 병목인가.

배경: docs/decisions/group-websocket-capacity-deep-dive.md §1-5.

    JwtHandshakeInterceptor.beforeHandshake()는 핸드셰이크마다 두 번의 DB 조회를 한다:
        1) MemberRepository.findByEmail(email)                              -- users.email UNIQUE 인덱스
        2) GroupMemberRepository.existsByGroupIdAndMemberIdAndStatus(...)    -- (group_id, member_id) UNIQUE 인덱스
    둘 다 스키마상 인덱스로 커버되는 단순 조회라 "낮을 것이다"로 추측할 수는 있지만,
    이 프로젝트의 규칙상 추측은 확인이 아니다 — "그룹 운동 시작" 같은 트리거로 참여자
    전원이 짧은 시간에 한꺼번에 접속하는 시나리오를 실제로 만들어 확인한다.

방법: 같은 그룹의 서로 다른 멤버 M명이 threading.Barrier로 동시에 핸드셰이크를 시도하고,
    각자 "GET 전송 → 101 응답 수신"까지의 왕복 시간을 잰다. M을 스윕해서 핸드셰이크
    지연이 M에 비례해 커지는지, 아니면 평탄한지를 본다.

환경: project_loadtest_env_constraint. 절대값이 아니라 M에 따른 증가 여부(방향성)를 본다.

실행:
    python measure_group_handshake_concurrency.py [--concurrency 5,10,20] [--reps 5]
"""

import argparse
import base64
import json
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

HOST = "localhost"
PORT = 8080
BASE_URL = f"http://{HOST}:{PORT}"
REPO_ROOT = Path(__file__).resolve().parent.parent


def http_json(method, path, body=None, token=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE_URL + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            raw = resp.read()
    except urllib.error.HTTPError as e:
        return e.code, _maybe_json(e.read())
    return resp.status, _maybe_json(raw)


def _maybe_json(raw):
    if not raw:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return raw.decode("utf-8", errors="replace")


def _env():
    env = {}
    for line in (REPO_ROOT / ".env").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.strip().startswith("#"):
            k, _, v = line.partition("=")
            env[k.strip()] = v.strip()
    return env


def mysql_query(sql):
    env = _env()
    cmd = ["docker", "exec", "-i", "shadowfit-mysql", "mysql",
           "-uroot", f"-p{env['MYSQL_ROOT_PASSWORD']}", env["MYSQL_DATABASE"], "-N", "-e", sql]
    return subprocess.check_output(cmd, text=True, stderr=subprocess.DEVNULL)


def signup_and_login(username, email, password="Passw0rd!1"):
    status, body = http_json("POST", "/member/signup",
                              {"username": username, "email": email, "password": password, "sex": "MALE"})
    assert status == 200, f"signup({email}) 실패: {status} {body}"
    status, body = http_json("POST", "/member/login", {"email": email, "password": password})
    assert status == 200, f"login({email}) 실패: {status} {body}"
    return body["accessToken"]


def member_id_for(email):
    return int(mysql_query(f"SELECT id FROM users WHERE email='{email}'").strip())


def create_group(token, name):
    status, body = http_json("POST", "/groups", {"name": name}, token=token)
    assert status == 201, f"그룹 생성 실패: {status} {body}"
    return body


def invite(token, group_id, invitee_id):
    status, body = http_json("POST", f"/groups/{group_id}/invitations", {"inviteeId": invitee_id}, token=token)
    assert status == 201, f"초대 실패: {status} {body}"
    return body


def accept(token, invitation_id):
    status, _ = http_json("POST", f"/invitations/{invitation_id}/accept", token=token)
    assert status == 200, f"초대 수락 실패: {status}"


def handshake_once(group_id, token, barrier, out_list, idx):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.settimeout(10)
    sock.connect((HOST, PORT))
    key = base64.b64encode(uuid.uuid4().bytes).decode()
    req = (
        f"GET /ws/groups/{group_id}?token={token} HTTP/1.1\r\n"
        f"Host: {HOST}:{PORT}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
    )
    barrier.wait()  # 전원이 같은 순간에 GET을 쏘려는 시도
    t0 = time.monotonic()
    try:
        sock.sendall(req.encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = sock.recv(4096)
            if not chunk:
                out_list.append((idx, None, "연결 종료"))
                return
            buf += chunk
        elapsed = (time.monotonic() - t0) * 1000
        status_line = buf.split(b"\r\n", 1)[0].decode()
        out_list.append((idx, elapsed, status_line))
    finally:
        sock.close()


def run_round(group_id, tokens):
    barrier = threading.Barrier(len(tokens))
    results = []
    threads = [threading.Thread(target=handshake_once, args=(group_id, tok, barrier, results, i))
               for i, tok in enumerate(tokens)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=15)
    return results


def median(values):
    values = sorted(values)
    n = len(values)
    return values[n // 2] if n else float("nan")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--concurrency", default="5,10,20")
    parser.add_argument("--reps", type=int, default=5)
    args = parser.parse_args()
    levels = [int(x) for x in args.concurrency.split(",")]
    max_m = max(levels)

    run_id = uuid.uuid4().hex[:8]
    print(f"[setup] run_id={run_id}, concurrency={levels}, reps={args.reps}")

    tokens, emails = [], []
    for i in range(max_m):
        email = f"lt-hs-{run_id}-{i}@test.local"
        tokens.append(signup_and_login(f"lt-hs-{run_id}-{i}", email))
        emails.append(email)

    owner_token = tokens[0]
    group_id = create_group(owner_token, f"handshake-group-{run_id}")["id"]
    for tok, email in zip(tokens[1:], emails[1:]):
        mid = member_id_for(email)
        inv = invite(owner_token, group_id, mid)
        accept(tok, inv["id"])
    print(f"[setup] group_id={group_id}, 멤버 {max_m}명 (전부 ACTIVE)")

    all_by_m = {}
    for m in levels:
        all_by_m[m] = []
        for rep in range(args.reps):
            results = run_round(group_id, tokens[:m])
            latencies = [lat for _, lat, _ in results if lat is not None]
            bad = [(i, status) for i, lat, status in results if lat is None or "101" not in status]
            p50 = median(latencies)
            pmax = max(latencies) if latencies else float("nan")
            all_by_m[m].append({"p50_ms": p50, "max_ms": pmax, "bad": bad})
            print(f"  M={m:2d} rep={rep+1}/{args.reps}: p50={p50:.1f}ms max={pmax:.1f}ms "
                  f"실패={len(bad)}{(' ' + str(bad)) if bad else ''}")

    print(f"\n{'='*60}\n집계 (판 하나 = 표본 하나)\n{'='*60}")
    for m in levels:
        p50s = [r["p50_ms"] for r in all_by_m[m]]
        maxs = [r["max_ms"] for r in all_by_m[m]]
        print(f"  M={m:2d}: p50 중앙값={median(p50s):.1f}ms (판별 {['%.1f' % v for v in p50s]}), "
              f"max들={['%.1f' % v for v in maxs]}")

    out_dir = REPO_ROOT / "loadtest" / "results" / f"group-ws-handshake-{time.strftime('%Y-%m-%d')}"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"run-{run_id}.json"
    out_path.write_text(json.dumps({"run_id": run_id, "args": vars(args), "results": all_by_m}, indent=2),
                         encoding="utf-8")
    print(f"\n[결과 저장] {out_path}")


if __name__ == "__main__":
    main()
