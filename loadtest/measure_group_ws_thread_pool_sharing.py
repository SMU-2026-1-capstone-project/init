"""그룹 WebSocket 메시지 처리가 Tomcat HTTP 스레드풀을 공유하는가 — §1-1 직접 확인.

배경: docs/decisions/group-websocket-capacity-deep-dive.md §1-1.

    WebSocketConfig가 WS 전용 실행기를 구성한 적이 없어, Tomcat 기본 동작상 HTTP
    요청과 WS 메시지 처리(onMessage)가 같은 커넥터 스레드풀(http-nio-8080)을
    공유할 것으로 추정됐다. 이 추정을 실측으로 확인한다.

    application.yml에 server.tomcat.mbeanregistry.enabled: true를 켜서
    tomcat_threads_busy_threads(JMX 기반, 이전엔 등록조차 안 돼 있었다)를
    Prometheus 엔드포인트(:9090/actuator/prometheus, 무인증)로 볼 수 있게 만든 뒤(§1-5),
    이 스크립트가 그 지표를 폴링하면서 그룹 이벤트 락 경합 버스트(§1-3과 같은 hot
    시나리오 — 처리 시간이 수백ms로 늘어나 폴링 윈도우에 걸리기 쉽다)를 건다.

    반증 조건: WS 발행만 있고 다른 HTTP 트래픽이 없는 상태에서 busy_threads가
    0에서 M(동시 발행자 수) 근처까지 올라갔다 내려오면 — WS 메시지 처리가
    HTTP 커넥터 스레드를 그대로 쓰고 있다는 뜻(공유 확정). 안 올라가면 별도 실행기를
    쓰고 있다는 뜻이다.

실행:
    python measure_group_ws_thread_pool_sharing.py [--concurrency 8] [--reps 3]
"""

import argparse
import base64
import json
import re
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
PROMETHEUS_URL = "http://127.0.0.1:9090/actuator/prometheus"  # "localhost"는 Windows에서
# IPv6(::1) 시도 후 폴백하느라 호출마다 ~2s가 붙는다 — 폴링 주기를 통째로 망가뜨린다.
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


def ws_connect(path, token, connect_timeout=5):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.settimeout(connect_timeout)
    sock.connect((HOST, PORT))
    key = base64.b64encode(uuid.uuid4().bytes).decode()
    req = (
        f"GET {path}?token={token} HTTP/1.1\r\n"
        f"Host: {HOST}:{PORT}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
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
    return sock


def encode_text_frame(text: str) -> bytes:
    payload = text.encode("utf-8")
    mask_key = b"\x01\x02\x03\x04"
    masked = bytes(b ^ mask_key[i % 4] for i, b in enumerate(payload))
    length = len(payload)
    header = bytes([0x81, 0x80 | length]) if length <= 125 else \
        bytes([0x81, 0x80 | 126]) + struct.pack(">H", length)
    return header + mask_key + masked


BUSY_RE = re.compile(r'^tomcat_threads_busy_threads\{name="http-nio-8080"\}\s+([\d.]+)', re.MULTILINE)
CURRENT_RE = re.compile(r'^tomcat_threads_current_threads\{name="http-nio-8080"\}\s+([\d.]+)', re.MULTILINE)


def read_busy_threads():
    with urllib.request.urlopen(PROMETHEUS_URL, timeout=2) as resp:
        text = resp.read().decode("utf-8")
    busy = BUSY_RE.search(text)
    current = CURRENT_RE.search(text)
    return float(busy.group(1)) if busy else None, float(current.group(1)) if current else None


class Poller(threading.Thread):
    def __init__(self, interval_s=0.01):
        super().__init__(daemon=True)
        self.interval_s = interval_s
        self.samples = []
        # threading.Thread가 내부적으로 self._stop(메서드)를 이미 쓰고 있어
        # 이름이 겹치면 join()이 깨진다 — _stop_requested로 분리한다.
        self._stop_requested = threading.Event()

    def run(self):
        while not self._stop_requested.is_set():
            try:
                busy, current = read_busy_threads()
                self.samples.append((time.monotonic(), busy, current))
            except OSError:
                pass
            time.sleep(self.interval_s)

    def stop(self):
        self._stop_requested.set()


def publish_burst(group_id, tokens, barrier):
    def worker(tok):
        sock = ws_connect(f"/ws/groups/{group_id}", tok)
        barrier.wait()
        sock.sendall(encode_text_frame('{"type":"REP_COMPLETED","payload":{"rep":1}}'))
        time.sleep(2)  # 서버가 처리를 끝낼 시간을 준 뒤 닫는다(락 대기 포함, §1-3 기준 최대 ~1s)
        sock.close()

    threads = [threading.Thread(target=worker, args=(tok,)) for tok in tokens]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=10)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--concurrency", type=int, default=8)
    parser.add_argument("--reps", type=int, default=3)
    args = parser.parse_args()
    m = args.concurrency

    base_busy, base_current = read_busy_threads()
    print(f"[baseline] busy={base_busy} current={base_current} (max=200 고정, 앞서 확인)")

    run_id = uuid.uuid4().hex[:8]
    tokens, emails = [], []
    for i in range(m):
        email = f"lt-tp-{run_id}-{i}@test.local"
        tokens.append(signup_and_login(f"lt-tp-{run_id}-{i}", email))
        emails.append(email)

    owner_token = tokens[0]
    group_id = create_group(owner_token, f"threadpool-group-{run_id}")["id"]
    for tok, email in zip(tokens[1:], emails[1:]):
        mid = member_id_for(email)
        inv = invite(owner_token, group_id, mid)
        accept(tok, inv["id"])
    print(f"[setup] group_id={group_id}, 멤버 {m}명")

    for rep in range(args.reps):
        poller = Poller(interval_s=0.01)
        poller.start()
        time.sleep(0.2)

        barrier = threading.Barrier(m)
        publish_burst(group_id, tokens, barrier)

        time.sleep(0.3)
        poller.stop()
        poller.join(timeout=1)

        busys = [b for _, b, _ in poller.samples if b is not None]
        peak = max(busys) if busys else float("nan")
        print(f"  rep {rep+1}/{args.reps}: 샘플 {len(busys)}개, busy_threads 관측 최대값={peak:.0f} "
              f"(동시 발행자 M={m})")

    print("\n결론 판단 기준: 관측 최대값이 0~1 근처면 분리(별도 실행기), M 근처까지 올라가면 공유(HTTP 커넥터와 동일 풀).")


if __name__ == "__main__":
    main()
