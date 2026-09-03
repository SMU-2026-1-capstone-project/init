"""그룹 이벤트 seq 채번 락 경합 — 같은 그룹에 동시 발행이 몰리면 어디서, 왜 꺾이는가.

배경: docs/decisions/group-websocket-capacity-deep-dive.md §1-3.

    GroupEventService.publish()는 Group 행을 비관적 쓰기 잠금(findByIdForUpdate)으로
    잡고 next_seq를 증가시킨 뒤 커밋한다. 브로드캐스트 자체는 커밋 후(락 밖)라 락
    보유 시간은 짧을 것으로 "설계"돼 있다 — 이 스크립트가 그 설계가 실제로 맞는지,
    그리고 락 경합이 처리량 천장의 원인인지를 확인한다.

방법 (이 프로젝트가 ghz batch_multi vs batch.json에서 이미 쓴 것과 같은 격리 패턴 —
"전 요청이 세션 하나로 몰리면 그 경합의 천장이지 시스템의 천장이 아니다"):
    - hot:    멤버 M명이 전부 "같은 그룹" 하나에 있다 → 전원이 동시에 발행하면
              같은 Group 행(next_seq)을 두고 비관적 락을 다툰다.
    - spread: 같은 M명이 "서로 다른 그룹"(1인 1그룹) M개에 있다 → 발행량은 같지만
              그룹 행이 갈려 있어 락 경합이 없다. hot과의 차이가 "락 경합" 성분이다.
    두 조건 다 M명이 threading.Barrier로 동시에 send()하도록 맞춘다. 각 연결은
    자기 소켓으로 자기가 보낸 이벤트가 되돌아오는 것(자기 자신도 그룹 멤버라 브로드캐스트
    대상)까지의 왕복 시간을 잰다 — 락 대기 + INSERT + 커밋 + 브로드캐스트를 전부 포함한다.

    직접 인과 확인: InnoDB의 누적 카운터(information_schema.INNODB_METRICS의
    lock_row_lock_waits·lock_row_lock_time)를 hot/spread 각 구간 전후로 diff해서,
    타이밍상의 델타가 "정말 행 잠금 대기"인지 다른 원인(커넥션 풀·CPU 경합)인지를
    타이밍 추론이 아니라 엔진 자신의 계측으로 확인한다.

환경: project_loadtest_env_constraint — 물리 코어 2개 공유 박스. 절대 수치가 아니라
hot vs spread의 상대적 차이, 그리고 InnoDB 카운터라는 환경에 안 묶이는 직접 증거를 본다.
§1-2에서 1판으로 판단했다가 잡음에 뒤집힌 교훈으로, 조건마다 반복(--reps)한다
([[feedback_measure_design_needs_repeats]]).

실행:
    python measure_group_ws_seq_lock_contention.py [--concurrency 2,4,8] [--reps 4]
"""

import argparse
import base64
import json
import os
import socket
import struct
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


# ── HTTP (REST 셋업) — measure_group_ws_backpressure.py와 동일 패턴 ────────

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


# ── raw WebSocket 클라이언트 (stdlib만 사용) ───────────────────────────────

def ws_connect(path, token, connect_timeout=5):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.settimeout(connect_timeout)
    sock.connect((HOST, PORT))
    key = base64.b64encode(uuid.uuid4().bytes).decode()
    req = (
        f"GET {path}?token={token} HTTP/1.1\r\n"
        f"Host: {HOST}:{PORT}\r\n"
        "Upgrade: websocket\r\nConnection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
    )
    sock.sendall(req.encode())
    buf = b""
    while b"\r\n\r\n" not in buf:
        chunk = sock.recv(4096)
        if not chunk:
            raise ConnectionError("handshake 중 연결 종료")
        buf += chunk
    header, _, leftover = buf.partition(b"\r\n\r\n")
    if " 101 " not in header.split(b"\r\n", 1)[0].decode():
        raise ConnectionError(f"handshake 실패: {header[:80]!r}")
    return sock, leftover


def encode_text_frame(text: str) -> bytes:
    payload = text.encode("utf-8")
    mask_key = os.urandom(4)
    masked = bytes(b ^ mask_key[i % 4] for i, b in enumerate(payload))
    first = 0x80 | 0x1
    length = len(payload)
    if length <= 125:
        header = bytes([first, 0x80 | length])
    elif length <= 0xFFFF:
        header = bytes([first, 0x80 | 126]) + struct.pack(">H", length)
    else:
        header = bytes([first, 0x80 | 127]) + struct.pack(">Q", length)
    return header + mask_key + masked


def recv_exact(sock, n, leftover_buf):
    while len(leftover_buf[0]) < n:
        chunk = sock.recv(65536)
        if not chunk:
            return None
        leftover_buf[0] += chunk
    data, leftover_buf[0] = leftover_buf[0][:n], leftover_buf[0][n:]
    return data


def read_frame(sock, leftover_buf):
    header = recv_exact(sock, 2, leftover_buf)
    if header is None:
        return None, None
    opcode = header[0] & 0x0F
    length = header[1] & 0x7F
    if length == 126:
        length = struct.unpack(">H", recv_exact(sock, 2, leftover_buf))[0]
    elif length == 127:
        length = struct.unpack(">Q", recv_exact(sock, 8, leftover_buf))[0]
    payload = recv_exact(sock, length, leftover_buf) if length else b""
    return opcode, payload


# ── InnoDB 락 카운터 — 타이밍 추론이 아니라 엔진 계측으로 직접 확인 ─────────

LOCK_METRIC_NAMES = ("lock_row_lock_waits", "lock_row_lock_time")


def ensure_lock_metrics_enabled():
    out = mysql_query(
        "SELECT name, status FROM information_schema.INNODB_METRICS "
        f"WHERE name IN ({','.join(repr(n) for n in LOCK_METRIC_NAMES)})")
    disabled = [line.split("\t")[0] for line in out.strip().splitlines() if line.split("\t")[1] != "enabled"]
    for name in disabled:
        mysql_query(f"SET GLOBAL innodb_monitor_enable = '{name}';")
    if disabled:
        print(f"[setup] InnoDB 락 카운터 활성화: {disabled}")


def read_lock_metrics():
    out = mysql_query(
        "SELECT name, count FROM information_schema.INNODB_METRICS "
        f"WHERE name IN ({','.join(repr(n) for n in LOCK_METRIC_NAMES)})")
    values = {}
    for line in out.strip().splitlines():
        name, count = line.split("\t")
        values[name] = int(count)
    return values


# ── 실험 본체 ─────────────────────────────────────────────────────────────

def publish_and_await_self(sock, marker_id, barrier, out_list):
    leftover = [b""]
    sock.settimeout(15)
    barrier.wait()  # 참가자 전원이 "동시에" 보내려는 시도 — hot 조건에서 락 경합을 유도
    sent_at = time.monotonic()
    frame = json.dumps({"type": "LOCK_TEST", "payload": {"marker": "locktest", "id": marker_id, "sent_at": sent_at}})
    try:
        sock.sendall(encode_text_frame(frame))
    except OSError:
        out_list.append((marker_id, None))
        return

    while True:
        try:
            opcode, payload = read_frame(sock, leftover)
        except socket.timeout:
            out_list.append((marker_id, None))  # 타임아웃 — 왕복을 못 받음(누락으로 집계)
            return
        if opcode is None:
            out_list.append((marker_id, None))
            return
        if opcode == 0x9:
            try:
                sock.sendall(bytes([0x8A, 0x00]))
            except OSError:
                pass
            continue
        if opcode != 0x1:
            continue
        try:
            envelope = json.loads(payload.decode("utf-8"))
            inner = json.loads(envelope["payload"])
        except (json.JSONDecodeError, KeyError):
            continue
        if inner.get("marker") == "locktest" and inner.get("id") == marker_id:
            out_list.append((marker_id, time.monotonic() - inner["sent_at"]))
            return


def run_round(label, group_ids_for_member, tokens):
    """group_ids_for_member[i] = i번째 멤버가 이번 판에서 붙을 그룹 id.
    hot: 전부 같은 값. spread: 전부 다른 값(자기 1인 그룹)."""
    sockets = [ws_connect(f"/ws/groups/{gid}", tok)[0] for gid, tok in zip(group_ids_for_member, tokens)]
    time.sleep(0.2)  # afterConnectionEstablished 반영 대기

    barrier = threading.Barrier(len(sockets))
    results = []
    threads = [threading.Thread(target=publish_and_await_self, args=(s, i, barrier, results))
               for i, s in enumerate(sockets)]

    metrics_before = read_lock_metrics()
    t0 = time.monotonic()
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=20)
    wall_ms = (time.monotonic() - t0) * 1000
    metrics_after = read_lock_metrics()

    for s in sockets:
        s.close()

    latencies = [lat for _, lat in results if lat is not None]
    missing = len(sockets) - len(latencies)
    lock_delta = {k: metrics_after[k] - metrics_before[k] for k in LOCK_METRIC_NAMES}
    return {
        "label": label, "n": len(sockets), "wall_ms": wall_ms, "missing": missing,
        "latencies_ms": [round(l * 1000, 1) for l in latencies],
        "lock_wait_count_delta": lock_delta["lock_row_lock_waits"],
        "lock_wait_time_ms_delta": lock_delta["lock_row_lock_time"],
    }


def median(values):
    values = sorted(values)
    n = len(values)
    return values[n // 2] if n else float("nan")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--concurrency", default="2,4,8", help="쉼표로 구분한 동시 발행자 수(M) 목록")
    parser.add_argument("--reps", type=int, default=4)
    args = parser.parse_args()
    levels = [int(x) for x in args.concurrency.split(",")]
    max_m = max(levels)

    ensure_lock_metrics_enabled()

    run_id = uuid.uuid4().hex[:8]
    print(f"[setup] run_id={run_id}, concurrency={levels}, reps={args.reps}")

    # 최대 동시성만큼 계정을 미리 만든다 — 작은 M은 앞쪽 부분집합만 쓴다.
    tokens = []
    emails = []
    for i in range(max_m):
        email = f"lt-lock-{run_id}-{i}@test.local"
        tokens.append(signup_and_login(f"lt-lock-{run_id}-{i}", email))
        emails.append(email)
    member_ids = [member_id_for(e) for e in emails]

    owner_token, owner_email = tokens[0], emails[0]

    # hot: 전원이 들어갈 그룹 하나. spread: 각자의 1인 그룹.
    hot_group = create_group(owner_token, f"lock-hot-{run_id}")["id"]
    for tok, mid in zip(tokens[1:], member_ids[1:]):
        inv = invite(owner_token, hot_group, mid)
        accept(tok, inv["id"])
    print(f"[setup] hot_group={hot_group} (멤버 {max_m}명)")

    spread_groups = []
    for i, tok in enumerate(tokens):
        gid = create_group(tok, f"lock-spread-{run_id}-{i}")["id"]
        spread_groups.append(gid)
    print(f"[setup] spread_groups={spread_groups}")

    all_results = {"hot": {}, "spread": {}}
    for m in levels:
        all_results["hot"][m] = []
        all_results["spread"][m] = []
        for rep in range(args.reps):
            order = ["hot", "spread"] if rep % 2 == 0 else ["spread", "hot"]
            for phase in order:
                if phase == "hot":
                    r = run_round(f"hot(M={m})", [hot_group] * m, tokens[:m])
                else:
                    r = run_round(f"spread(M={m})", spread_groups[:m], tokens[:m])
                all_results[phase][m].append(r)
                p50 = median(r["latencies_ms"])
                print(f"  M={m} rep={rep+1}/{args.reps} {phase:7s}: "
                      f"p50={p50:.0f}ms wall={r['wall_ms']:.0f}ms 누락={r['missing']} "
                      f"lock_waits+{r['lock_wait_count_delta']} lock_wait_time+{r['lock_wait_time_ms_delta']}ms")

    print(f"\n{'='*70}\n집계 (판 하나 = 표본 하나)\n{'='*70}")
    for m in levels:
        for phase in ("hot", "spread"):
            rounds = all_results[phase][m]
            p50s = [median(r["latencies_ms"]) for r in rounds]
            lock_waits = [r["lock_wait_count_delta"] for r in rounds]
            lock_times = [r["lock_wait_time_ms_delta"] for r in rounds]
            print(f"  M={m:2d} {phase:7s}: p50 중앙값={median(p50s):.0f}ms "
                  f"(판별 {['%.0f' % v for v in p50s]}), "
                  f"lock_row_lock_waits 합계={sum(lock_waits)}, lock_row_lock_time 합계={sum(lock_times)}ms")

    out_dir = REPO_ROOT / "loadtest" / "results" / f"group-ws-seq-lock-{time.strftime('%Y-%m-%d')}"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"run-{run_id}.json"
    out_path.write_text(json.dumps({"run_id": run_id, "args": vars(args), "results": all_results}, indent=2),
                         encoding="utf-8")
    print(f"\n[결과 저장] {out_path}")


if __name__ == "__main__":
    main()
