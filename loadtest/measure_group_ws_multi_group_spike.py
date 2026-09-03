"""여러 그룹이 동시에 스파이크 나는 경우 — 그룹 수 자체가 늘어날 때 무엇이 먼저 꺾이는가.

배경: docs/decisions/group-websocket-capacity-deep-dive.md §1-2 수정(#623) 이후,
GroupSocketRegistry는 연결마다 전용 단일 스레드 executor를 만든다(느린 소비자 격리 목적).
지금까지의 실측(§1-2·§1-3·§1-5)은 전부 "그룹 하나 안에서 멤버 M명이 동시에 몰리는" 형태였다
— "서로 다른 그룹 G개가 동시에 스파이크 나는" 형태(예: "다 같이 운동하는 시간대"에 여러 그룹이
동시에 활동 시작)는 아직 확인한 적이 없다.

이게 왜 다른 질문인가: 그룹 하나의 동시성은 seq 락(§1-3)이나 백프레셔(§1-2) 문제였지만,
그룹이 G개로 늘면 전용 스레드 executor가 G×M개 만들어진다 — 스레드 수 자체가 새 자원
소비 축이 된다. 이 프로젝트에 이미 등록된 것: Tomcat HTTP 커넥터 스레드풀(§1-1, max 200,
WS 핸드셰이크와 공유)·JVM 전체 스레드 수(jvm_threads_live_threads).

확인하는 것:
    1. G×M개 연결이 동시에 핸드셰이크할 때 실패(거부·타임아웃)가 나는가.
    2. JVM 스레드 수·Tomcat busy threads가 스파이크 동안 얼마나 오르는가 — §1-1에서 확인한
       "WS가 HTTP 풀을 공유한다"가 그룹 수가 늘어도 여전히 관찰되는가.
    3. 스파이크가 끝나고 전부 연결을 끊으면 스레드 수가 베이스라인으로 돌아오는가 — 세션당
       전용 executor(#623 수정)가 실제로 정리되는지, 스레드 누수가 없는지의 직접 확인
       (four-axes 스타일 실패 주입 — "설계한 대로 정리되나").

환경: project_loadtest_env_constraint. 절대 수치가 아니라 방향성(증가·회복 여부)을 본다.

실행:
    python measure_group_ws_multi_group_spike.py [--groups 30] [--members-per-group 4]
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
PROMETHEUS_URL = "http://127.0.0.1:9090/actuator/prometheus"
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


def ws_handshake(path, token, timeout_s=10):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.settimeout(timeout_s)
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
    header, _, _ = buf.partition(b"\r\n\r\n")
    status_line = header.split(b"\r\n", 1)[0].decode()
    if " 101 " not in status_line:
        raise ConnectionError(f"handshake 실패: {status_line}")
    return sock


METRIC_RES = {
    "jvm_threads": re.compile(r'^jvm_threads_live_threads\s+([\d.]+)', re.MULTILINE),
    "tomcat_busy": re.compile(r'^tomcat_threads_busy_threads\{name="http-nio-8080"\}\s+([\d.]+)', re.MULTILINE),
    "hikari_pending": re.compile(r'^hikaricp_connections_pending\{pool="HikariPool-1"\}\s+([\d.]+)', re.MULTILINE),
}


def read_metrics():
    with urllib.request.urlopen(PROMETHEUS_URL, timeout=3) as resp:
        text = resp.read().decode("utf-8")
    return {name: (float(m.group(1)) if (m := rx.search(text)) else None) for name, rx in METRIC_RES.items()}


class Poller(threading.Thread):
    def __init__(self, interval_s=0.05):
        super().__init__(daemon=True)
        self.interval_s = interval_s
        self.samples = []
        self._stop_requested = threading.Event()

    def run(self):
        while not self._stop_requested.is_set():
            try:
                m = read_metrics()
                self.samples.append((time.monotonic(), m))
            except OSError:
                pass
            time.sleep(self.interval_s)

    def stop(self):
        self._stop_requested.set()

    def peak(self, key):
        vals = [m[key] for _, m in self.samples if m.get(key) is not None]
        return max(vals) if vals else None


def connect_worker(group_id, token, barrier, sockets_out, errors_out, idx):
    try:
        barrier.wait()
        t0 = time.monotonic()
        sock = ws_handshake(f"/ws/groups/{group_id}", token)
        elapsed = (time.monotonic() - t0) * 1000
        sockets_out[idx] = (sock, elapsed)
    except Exception as e:  # noqa: BLE001 - 실측 스크립트: 실패 자체가 관측 대상
        errors_out[idx] = str(e)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--groups", type=int, default=30)
    parser.add_argument("--members-per-group", type=int, default=4)
    args = parser.parse_args()
    g, m = args.groups, args.members_per_group
    total = g * m

    baseline = read_metrics()
    print(f"[baseline] jvm_threads={baseline['jvm_threads']:.0f} "
          f"tomcat_busy={baseline['tomcat_busy']:.0f} hikari_pending={baseline['hikari_pending']:.0f}")

    run_id = uuid.uuid4().hex[:8]
    print(f"[setup] run_id={run_id}, groups={g}, members/group={m}, 총 연결={total}개")

    # 계정 생성은 스파이크 대상이 아니므로 병렬로 빠르게 끝낸다.
    accounts = [None] * total

    def make_account(i):
        email = f"lt-mg-{run_id}-{i}@test.local"
        token = signup_and_login(f"lt-mg-{run_id}-{i}", email)
        accounts[i] = (email, token)

    threads = [threading.Thread(target=make_account, args=(i,)) for i in range(total)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    print(f"[setup] 계정 {total}개 생성 완료")

    group_ids = []
    member_tokens = []  # group_id, token 쌍의 평평한 리스트
    for gi in range(g):
        owner_email, owner_token = accounts[gi * m]
        group_id = create_group(owner_token, f"spike-group-{run_id}-{gi}")["id"]
        group_ids.append(group_id)
        member_tokens.append((group_id, owner_token))
        for mi in range(1, m):
            email, tok = accounts[gi * m + mi]
            member_id = member_id_for(email)
            inv = invite(owner_token, group_id, member_id)
            accept(tok, inv["id"])
            member_tokens.append((group_id, tok))
    print(f"[setup] 그룹 {g}개, 총 멤버십 {len(member_tokens)}건 준비 완료")

    poller = Poller(interval_s=0.05)
    poller.start()
    time.sleep(0.2)

    barrier = threading.Barrier(total)
    sockets_out = [None] * total
    errors_out = [None] * total
    conn_threads = [threading.Thread(target=connect_worker,
                                      args=(gid, tok, barrier, sockets_out, errors_out, i))
                    for i, (gid, tok) in enumerate(member_tokens)]

    t0 = time.monotonic()
    for t in conn_threads:
        t.start()
    for t in conn_threads:
        t.join(timeout=30)
    spike_wall_ms = (time.monotonic() - t0) * 1000

    time.sleep(0.5)
    during_peak_jvm = poller.peak("jvm_threads")
    during_peak_tomcat = poller.peak("tomcat_busy")
    during_peak_hikari = poller.peak("hikari_pending")

    succeeded = [s for s in sockets_out if s is not None]
    failed = [e for e in errors_out if e is not None]
    latencies = [lat for _, lat in succeeded]
    latencies.sort()
    print(f"\n[스파이크] {total}개 동시 핸드셰이크, 체감 {spike_wall_ms:.0f}ms")
    print(f"  성공 {len(succeeded)}/{total}, 실패 {len(failed)}")
    if failed:
        print(f"  실패 샘플: {failed[:5]}")
    if latencies:
        print(f"  핸드셰이크 지연 p50={latencies[len(latencies)//2]:.0f}ms "
              f"p90={latencies[int(len(latencies)*0.9)]:.0f}ms max={latencies[-1]:.0f}ms")
    print(f"  스파이크 중 관측 최대값 — jvm_threads={during_peak_jvm:.0f} "
          f"(베이스라인 {baseline['jvm_threads']:.0f}, +{during_peak_jvm - baseline['jvm_threads']:.0f}), "
          f"tomcat_busy={during_peak_tomcat:.0f}(max 200), hikari_pending={during_peak_hikari:.0f}")

    # 정리 — 전부 닫고 스레드가 실제로 회수되는지 확인(설계대로 정리되나 확인, four-axes 스타일).
    for s in succeeded:
        s[0].close()
    time.sleep(2.0)  # executor.shutdown() 후 스레드 실제 종료까지 약간의 시간
    poller.stop()
    poller.join(timeout=1)

    after = read_metrics()
    print(f"\n[정리 후] jvm_threads={after['jvm_threads']:.0f} "
          f"(베이스라인 {baseline['jvm_threads']:.0f}, 차이 {after['jvm_threads'] - baseline['jvm_threads']:+.0f})")
    if after["jvm_threads"] - baseline["jvm_threads"] > total * 0.1:
        print("  🔴 베이스라인 대비 스레드가 상당히 안 돌아왔다 — 누수 의심, 추가 확인 필요")
    else:
        print("  스레드 수가 베이스라인 근처로 회복됨 — 세션별 executor가 정리되고 있다는 뜻")


if __name__ == "__main__":
    main()
