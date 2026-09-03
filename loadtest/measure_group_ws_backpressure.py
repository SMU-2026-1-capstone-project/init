"""그룹 WebSocket 브로드캐스트 백프레셔 — 느린 소비자가 같은 그룹 나머지 멤버의
전달을 지연시키는가.

배경: docs/decisions/group-websocket-capacity-deep-dive.md §1-2.

    GroupSocketRegistry.broadcast()는 그룹 세션을 순차 for-loop로 돌며 세션마다
        synchronized(session) { session.sendMessage(message) }
    를 동기 호출한다. 실패(IOException)했을 때만 그 세션을 정리할 뿐, "느리지만
    아직 안 끊긴" 세션에 대한 정책이 없다.

    GroupEventService.publish()는 @Transactional이고, 브로드캐스트는
    TransactionSynchronizationManager.afterCommit 콜백에서 "커밋한 그 스레드 위에서"
    동기 호출된다 — 즉 broadcast() 호출이 끝나야 publish()가 리턴하고, publish()를
    부른 GroupSocketHandler.handleTextMessage()도 그제서야 리턴한다. Tomcat은 한
    WS 세션의 onMessage 콜백을 순차 실행하므로, handleTextMessage가 블록되면 그
    발행자 세션의 "다음" 수신 메시지 처리도 같이 밀린다.

    JSR-356/Tomcat의 동기 sendText는 블로킹 호출이다 — 피어가 소켓을 안 읽으면
    OS 수신 버퍼가 차고 TCP 윈도우가 닫히면서 서버 쪽 write가 블록될 수 있다.

이 스크립트가 확인하는 것: 그룹 안에 "연결은 됐지만 전혀 읽지 않는" 소비자가 하나
있을 때, 같은 그룹의 다른(정상) 소비자들이 받는 이벤트의 도착 지연이 커지는가 —
그리고 커진다면 얼마나(전부 막히는가, 일부만 늦는가).

방법: 느린 소비자 쪽 소켓은 handshake 이후 recv를 아예 안 부른다. 발행자가 보내는
이벤트 payload를 ~2KB로 패딩해, 적은 개수만 보내도 OS 수신 버퍼가 금방 차게 만든다.
baseline(느린 소비자 없음) vs with-slow-consumer 두 판을 같은 K개 이벤트로 돌려
정상 소비자들의 도착 지연을 비교한다.

환경: project_loadtest_env_constraint — 이 로컬 환경(물리 코어 2개, MySQL·백엔드
동거)에서는 절대 수치(ms)가 목적이 아니다. 보는 것은 "느린 소비자가 있을 때/없을
때"의 상대적 차이(델타)뿐이다.

사전조건: docker-compose 로 shadowfit-backend(:8080)·shadowfit-mysql 이 떠 있을 것.

실행:
    python measure_group_ws_backpressure.py [--events 20] [--rcvbuf 2048]
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


# ── HTTP (REST 셋업) ──────────────────────────────────────────────────────

def http_call(method, path, body=None, token=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE_URL + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            raw = resp.read()
            return resp.status, raw
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def http_json(method, path, body=None, token=None):
    status, raw = http_call(method, path, body, token)
    parsed = None
    if raw:
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError:
            parsed = raw.decode("utf-8", errors="replace")
    return status, parsed


def signup_and_login(username, email, password="Passw0rd!1"):
    status, body = http_json("POST", "/member/signup",
                              {"username": username, "email": email, "password": password, "sex": "MALE"})
    assert status == 200, f"signup({email}) 실패: {status} {body}"
    status, body = http_json("POST", "/member/login", {"email": email, "password": password})
    assert status == 200, f"login({email}) 실패: {status} {body}"
    return body["accessToken"]


def member_id_for(email):
    env = {}
    for line in (REPO_ROOT / ".env").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.strip().startswith("#"):
            k, _, v = line.partition("=")
            env[k.strip()] = v.strip()
    root_password = env["MYSQL_ROOT_PASSWORD"]
    database = env["MYSQL_DATABASE"]
    cmd = ["docker", "exec", "-i", "shadowfit-mysql", "mysql",
           "-uroot", f"-p{root_password}", database, "-N", "-e",
           f"SELECT id FROM users WHERE email='{email}'"]
    out = subprocess.check_output(cmd, text=True, stderr=subprocess.DEVNULL).strip()
    return int(out)


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


# ── raw WebSocket 클라이언트 (stdlib만 사용, 의도적으로 최소 구현) ─────────

def ws_connect(path, token, rcvbuf=None, connect_timeout=5):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    if rcvbuf is not None:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, rcvbuf)
    else:
        # Nagle이 켜져 있으면 루프백에서도 지연 ACK와 겹쳐 수십~수백ms가 매 요청마다 붙어,
        # 우리가 재려는 "서버 쪽 브로드캐스트 블로킹"과 구분이 안 된다 — 아예 꺼서 배제한다.
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.settimeout(connect_timeout)
    sock.connect((HOST, PORT))

    key = base64.b64encode(uuid.uuid4().bytes).decode()
    req = (
        f"GET {path}?token={token} HTTP/1.1\r\n"
        f"Host: {HOST}:{PORT}\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        "\r\n"
    )
    sock.sendall(req.encode())

    # 헤더 끝(\r\n\r\n)까지만 읽는다 — 그 이후 바이트가 이미 왔다면 별도 버퍼에 보관.
    buf = b""
    while b"\r\n\r\n" not in buf:
        chunk = sock.recv(4096)
        if not chunk:
            raise ConnectionError("handshake 중 연결 종료")
        buf += chunk
    header, _, leftover = buf.partition(b"\r\n\r\n")
    status_line = header.split(b"\r\n", 1)[0].decode()
    if " 101 " not in status_line:
        raise ConnectionError(f"handshake 실패: {status_line}")
    return sock, leftover


def encode_text_frame(text: str) -> bytes:
    payload = text.encode("utf-8")
    mask_key = os.urandom(4)
    masked = bytes(b ^ mask_key[i % 4] for i, b in enumerate(payload))

    first = 0x80 | 0x1  # FIN + text opcode
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
    """서버→클라이언트 프레임 하나를 읽는다(unmasked 전제). 소켓 타임아웃 시 TimeoutError 전파."""
    header = recv_exact(sock, 2, leftover_buf)
    if header is None:
        return None, None
    opcode = header[0] & 0x0F
    length = header[1] & 0x7F
    if length == 126:
        ext = recv_exact(sock, 2, leftover_buf)
        length = struct.unpack(">H", ext)[0]
    elif length == 127:
        ext = recv_exact(sock, 8, leftover_buf)
        length = struct.unpack(">Q", ext)[0]
    payload = recv_exact(sock, length, leftover_buf) if length else b""
    return opcode, payload


# ── 실험 본체 ─────────────────────────────────────────────────────────────

def fast_reader_loop(name, sock, results, stop_event, read_timeout=0.5):
    leftover_buf = [b""]
    sock.settimeout(read_timeout)
    while not stop_event.is_set():
        try:
            opcode, payload = read_frame(sock, leftover_buf)
        except socket.timeout:
            continue
        except OSError:
            break
        if opcode is None:
            break
        if opcode == 0x9:  # ping → pong
            try:
                sock.sendall(bytes([0x8A, 0x00]))
            except OSError:
                break
            continue
        if opcode == 0x8:  # close
            break
        if opcode != 0x1:
            continue
        recv_at = time.monotonic()
        try:
            envelope = json.loads(payload.decode("utf-8"))
            inner = json.loads(envelope["payload"])
        except (json.JSONDecodeError, KeyError):
            continue
        if inner.get("marker") != "loadtest":
            continue
        results.append({
            "reader": name,
            "seq": inner["seq"],
            "sent_at": inner["sent_at"],
            "recv_at": recv_at,
            "latency_ms": (recv_at - inner["sent_at"]) * 1000,
        })


def send_events(owner_sock, count, pad_bytes):
    pad = "x" * pad_bytes
    for i in range(count):
        payload = {"marker": "loadtest", "seq": i, "sent_at": time.monotonic(), "pad": pad}
        frame_text = json.dumps({"type": "LOAD_TEST", "payload": payload})
        owner_sock.sendall(encode_text_frame(frame_text))


def run_phase(label, group_id, owner_token, fast_tokens, slow_token, event_count, pad_bytes, rcvbuf, wait_s):
    print(f"\n--- {label} ---")
    stop_event = threading.Event()
    results = []
    threads = []
    fast_sockets = []

    owner_sock, _ = ws_connect(f"/ws/groups/{group_id}", owner_token)

    for i, tok in enumerate(fast_tokens):
        sock, _ = ws_connect(f"/ws/groups/{group_id}", tok)
        fast_sockets.append(sock)
        t = threading.Thread(target=fast_reader_loop, args=(f"fast{i+1}", sock, results, stop_event), daemon=True)
        t.start()
        threads.append(t)

    slow_sock = None
    if slow_token is not None:
        slow_sock, _ = ws_connect(f"/ws/groups/{group_id}", slow_token, rcvbuf=rcvbuf)
        print(f"    느린 소비자 연결됨 (SO_RCVBUF={rcvbuf}), 이후 recv() 호출 안 함")

    time.sleep(0.3)  # 등록(afterConnectionEstablished)이 반영될 시간

    t0 = time.monotonic()
    send_events(owner_sock, event_count, pad_bytes)
    send_elapsed = time.monotonic() - t0
    print(f"    발행자 send() {event_count}건 완료 — 클라이언트 체감 {send_elapsed*1000:.1f}ms"
          f" (서버 처리와 무관, TCP 전송 계층일 뿐임에 유의)")

    time.sleep(wait_s)
    stop_event.set()
    for t in threads:
        t.join(timeout=1)

    for s in fast_sockets:
        s.close()
    owner_sock.close()
    if slow_sock is not None:
        # 실제 배포에서 "느린 클라이언트가 사라짐"에 해당 — 서버 쪽에 블록돼 있던
        # sendMessage가 이 close(RST/FIN)로 풀리는지도 관찰 대상이다.
        slow_sock.close()

    by_reader = {}
    for r in results:
        by_reader.setdefault(r["reader"], []).append(r)
    summary = {}
    for name, rows in by_reader.items():
        rows.sort(key=lambda r: r["seq"])
        received = len(rows)
        missing = event_count - received
        latencies = [r["latency_ms"] for r in rows]
        p50 = sorted(latencies)[len(latencies) // 2] if latencies else float("nan")
        pmax = max(latencies) if latencies else float("nan")
        last_seq = rows[-1]["seq"] if rows else -1
        summary[name] = {"received": received, "missing": missing, "p50_ms": p50, "max_ms": pmax}
        print(f"    {name}: {received}/{event_count}건 수신 (누락 {missing}, 마지막 수신 seq={last_seq}), "
              f"latency p50={p50:.1f}ms max={pmax:.1f}ms")

    return {"label": label, "event_count": event_count, "results": results, "summary": summary}


def median(values):
    values = sorted(values)
    n = len(values)
    return values[n // 2] if n else float("nan")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--events", type=int, default=20)
    parser.add_argument("--pad-bytes", type=int, default=2000)
    parser.add_argument("--rcvbuf", type=int, default=1024)
    parser.add_argument("--wait-s", type=float, default=15.0,
                         help="발행 후 정상 소비자 수신을 기다리는 시간(초) — 블로킹 시나리오 대비 상한")
    parser.add_argument("--reps", type=int, default=5,
                         help="판 반복 횟수 — 이 환경은 판마다 편차가 커서(2코어 공유 박스) "
                              "1판으로는 팔과 판 순서를 못 가른다([[feedback_measure_design_needs_repeats]])")
    args = parser.parse_args()

    run_id = uuid.uuid4().hex[:8]
    print(f"[setup] run_id={run_id}, reps={args.reps}")

    owner_token = signup_and_login(f"lt-owner-{run_id}", f"lt-owner-{run_id}@test.local")
    fast_tokens = [
        signup_and_login(f"lt-fast1-{run_id}", f"lt-fast1-{run_id}@test.local"),
        signup_and_login(f"lt-fast2-{run_id}", f"lt-fast2-{run_id}@test.local"),
    ]
    slow_token = signup_and_login(f"lt-slow-{run_id}", f"lt-slow-{run_id}@test.local")
    slow_email = f"lt-slow-{run_id}@test.local"
    fast_emails = [f"lt-fast1-{run_id}@test.local", f"lt-fast2-{run_id}@test.local"]

    all_reps = []
    for rep in range(args.reps):
        # 순서를 교대(ABBA류)해서 "시간이 지날수록 박스가 뜨거워진다/식는다" 같은 드리프트가
        # 한쪽 팔에만 쌓이지 않게 한다 — 판 순서와 팔 효과를 분리하는 최소한의 장치.
        order = ["baseline", "slow"] if rep % 2 == 0 else ["slow", "baseline"]
        print(f"\n=== rep {rep+1}/{args.reps} (순서: {order}) ===")

        group = create_group(owner_token, f"loadtest-group-{run_id}-{rep}")
        group_id = group["id"]
        for label, email, tok in [("fast1", fast_emails[0], fast_tokens[0]),
                                   ("fast2", fast_emails[1], fast_tokens[1]),
                                   ("slow", slow_email, slow_token)]:
            member_id = member_id_for(email)
            inv = invite(owner_token, group_id, member_id)
            accept(tok, inv["id"])

        rep_result = {}
        for phase in order:
            if phase == "baseline":
                rep_result["baseline"] = run_phase(
                    "A: baseline (느린 소비자 없음)", group_id, owner_token, fast_tokens, None,
                    args.events, args.pad_bytes, args.rcvbuf, wait_s=args.wait_s)
            else:
                rep_result["slow"] = run_phase(
                    "B: 느린 소비자 있음", group_id, owner_token, fast_tokens, slow_token,
                    args.events, args.pad_bytes, args.rcvbuf, wait_s=args.wait_s)
        all_reps.append(rep_result)

    # 판별 요약(p50 of p50s) — pooled event 통계가 아니라 "판 하나 = 표본 하나"로 집계한다.
    print(f"\n{'='*60}\n{args.reps}판 집계 (판 하나 = 표본 하나, pooled 아님)\n{'='*60}")
    for phase, phase_label in [("baseline", "A: baseline"), ("slow", "B: 느린 소비자 있음")]:
        for reader in ["fast1", "fast2"]:
            p50s = [rep[phase]["summary"].get(reader, {}).get("p50_ms", float("nan")) for rep in all_reps]
            maxs = [rep[phase]["summary"].get(reader, {}).get("max_ms", float("nan")) for rep in all_reps]
            missing = [rep[phase]["summary"].get(reader, {}).get("missing", args.events) for rep in all_reps]
            print(f"  {phase_label} / {reader}: p50들={['%.0f' % v for v in p50s]}ms "
                  f"→ 판간 중앙값={median(p50s):.0f}ms, max들={['%.0f' % v for v in maxs]}ms, "
                  f"누락={missing}")

    out_dir = REPO_ROOT / "loadtest" / "results" / f"group-ws-backpressure-{time.strftime('%Y-%m-%d')}"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"run-{run_id}.json"
    out_path.write_text(json.dumps({"run_id": run_id, "args": vars(args), "reps": all_reps}, indent=2),
                         encoding="utf-8")
    print(f"\n[결과 저장] {out_path}")


if __name__ == "__main__":
    main()
