"""비정상 종료(WS Close 프레임 없이 TCP만 끊김) 감지 지연 — 정확히 몇 초 걸리는가.

배경: docs/decisions/group-websocket-capacity-deep-dive.md §9-3(AWS 다중 그룹 스파이크
실측)에서 이미 관찰된 사실 — WS Close 프레임 없이 raw `sock.close()`로 끊으면, 서버가
그 연결을 정리하는 데 "3초보다 훨씬 오래(3초와 60~120초 사이 어딘가)" 걸린다. 그때는
범용 jvm_threads_live를 3초 간격으로만 보고, 나머지는 "1~2분 뒤 수동 확인"이라 정확한
감지 시각을 못 좁혔다.

이 스크립트는 docs/decisions/group-websocket-heartbeat.md §6의 실험 설계를 그대로
실행한다 — heartbeat(ping/pong) 도입 여부·주기(§3, 잠정 30초/2회 유예)를 정하기 전에
"지금(TCP만 있을 때) 정확히 얼마나 걸리는가"라는 비교 기준선을 만드는 것이 목적이다.

정밀도를 올린 지점:
    - 범용 jvm_threads 대신 GroupSocketRegistry가 새로 노출한 전용 게이지
      (shadowfit.group.ws.active.sessions, Prometheus 이름 shadowfit_group_ws_active_sessions)를
      쓴다 — "이 레지스트리가 실제로 몇 개를 들고 있는가"를 직접 읽는다.
    - 0.5초 간격으로 최대 180초까지 폴링하며, 값이 베이스라인(0)으로 떨어지는 첫 시점을 기록.
    - 판마다 새 그룹·새 세션으로 반복(--reps, 기본 5) — [[feedback_measure_design_needs_repeats]].

환경: 처리량이 아니라 "OS/JVM이 끊김을 인지하는 타이밍"이라 버스트 용량이 필요 없다.
로컬(2코어 공유, [[project_loadtest_env_constraint]])에서 먼저 시도한다.

실행:
    python measure_group_ws_disconnect_detection_lag.py [--sessions 3] [--reps 5] [--ceiling 180]
        [--port 8080] [--mgmt-port 9090]

--port/--mgmt-port: docs/decisions/group-websocket-heartbeat.md §6 실측 1회는 공유 dev
shadowfit-mysql/-backend 볼륨과 충돌해(다른 세션이 동시에 마이그레이션 파일을 건드리고
있었다) 격리 스택(mysql-test/backend-test, 앱 8081/관리 9092)에서 돌렸다 — 기본값은
평소 dev 스택(8080/9090) 그대로 두고 필요할 때만 오버라이드한다.
"""

import argparse
import base64
import json
import re
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
PROMETHEUS_URL = "http://127.0.0.1:9090/actuator/prometheus"
REPO_ROOT = Path(__file__).resolve().parent.parent

GAUGE_RE = re.compile(r'^shadowfit_group_ws_active_sessions\s+([\d.]+)', re.MULTILINE)


def http_json(method, path, body=None, token=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE_URL + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        # 30초 — 이 박스는 dev 스택 전체 + 격리 테스트 스택을 동시에 돌려 자원 경합이 크다
        # (2코어 공유, [[project_loadtest_env_constraint]]). 10초는 N=150 순차 초대 중
        # 우연히 한 번 타임아웃으로 전체 스크립트가 죽는 걸 봤다 — 측정 대상(disconnect 감지)과
        # 무관한 실패라 여유를 둔다.
        with urllib.request.urlopen(req, timeout=30) as resp:
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


MYSQL_CONTAINER = "shadowfit-mysql"


def mysql_query(sql):
    env = _env()
    cmd = ["docker", "exec", "-i", MYSQL_CONTAINER, "mysql",
           "-uroot", f"-p{env['MYSQL_ROOT_PASSWORD']}", env["MYSQL_DATABASE"], "-N", "-e", sql]
    return subprocess.check_output(cmd, text=True, stderr=subprocess.DEVNULL)


def member_id_for(email):
    return int(mysql_query(f"SELECT id FROM users WHERE email='{email}'").strip())


def member_ids_for(emails):
    """§9-3처럼 계정 수가 많을 때(150~300) N번 개별 조회 대신 한 번에 배치 조회한다."""
    quoted = ",".join(repr(e) for e in emails)
    out = mysql_query(f"SELECT email, id FROM users WHERE email IN ({quoted})")
    by_email = {}
    for line in out.strip().splitlines():
        email, mid = line.split("\t")
        by_email[email] = int(mid)
    return [by_email[e] for e in emails]


def signup_and_login(username, email, password="Passw0rd!1"):
    status, body = http_json("POST", "/member/signup",
                              {"username": username, "email": email, "password": password, "sex": "MALE"})
    assert status == 200, f"signup({email}) 실패: {status} {body}"
    status, body = http_json("POST", "/member/login", {"email": email, "password": password})
    assert status == 200, f"login({email}) 실패: {status} {body}"
    return body["accessToken"]


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


def ws_handshake(path, token, timeout_s=20):
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


def read_gauge():
    with urllib.request.urlopen(PROMETHEUS_URL, timeout=3) as resp:
        text = resp.read().decode("utf-8")
    m = GAUGE_RE.search(text)
    if m is None:
        raise RuntimeError(
            "shadowfit_group_ws_active_sessions 지표가 안 보인다 — "
            "GroupSocketRegistry 게이지 등록/배포를 확인할 것")
    return float(m.group(1))


def wait_for_gauge(target, ceiling_s, poll_s=0.2):
    """게이지가 target에 도달할 때까지 기다린다(초기 연결 등록 확인용, 짧게)."""
    t0 = time.monotonic()
    while time.monotonic() - t0 < ceiling_s:
        if read_gauge() == target:
            return True
        time.sleep(poll_s)
    return False


def _connect_worker(path, token, barrier, sockets_out, errors_out, idx):
    try:
        barrier.wait()
        sockets_out[idx] = ws_handshake(path, token)
    except Exception as e:  # noqa: BLE001 - 실측 스크립트: 실패 자체가 관측 대상
        errors_out[idx] = str(e)


def run_trial(rep, tokens, member_ids, run_id, ceiling_s, poll_s):
    """§9-3과 같은 형태 — N명이 동시에 접속했다가, WS Close 프레임 없이 한꺼번에 끊긴다."""
    n = len(tokens)
    group_id = create_group(tokens[0], f"lt-lag-{run_id}-{rep}")["id"]

    # 초대·수락은 순차로 한다 — 전부 같은 그룹 하나에 동시에 몰리면 HikariCP 풀(15)이
    # 감당 못 해 500/타임아웃이 쏟아진다(1차 시도에서 실측 확인: 149스레드 동시 → 131/150
    # 핸드셰이크 403, 즉 초대·수락 자체가 실패해 애초에 활성 멤버가 아니었다).
    for tok, mid in zip(tokens[1:], member_ids[1:]):
        inv = invite(tokens[0], group_id, mid)
        accept(tok, inv["id"])

    baseline = read_gauge()

    # 동시 접속 — §9-3과 같은 barrier 패턴. N명이 한 시점에 몰려야 §1-1(Tomcat 커넥터
    # 스레드풀 공유)에서 확인된 자원 경합 조건이 재현된다.
    barrier = threading.Barrier(n)
    sockets_out = [None] * n
    errors_out = [None] * n
    conn_threads = [threading.Thread(target=_connect_worker,
                                      args=(f"/ws/groups/{group_id}", tok, barrier, sockets_out, errors_out, i))
                    for i, tok in enumerate(tokens)]
    for t in conn_threads:
        t.start()
    for t in conn_threads:
        t.join(timeout=30)

    sockets = [s for s in sockets_out if s is not None]
    failed = [e for e in errors_out if e is not None]
    if failed:
        print(f"    (rep={rep}: 핸드셰이크 실패 {len(failed)}/{n}, 샘플: {failed[:3]})")

    registered = wait_for_gauge(baseline + len(sockets), ceiling_s=15, poll_s=0.2)
    if not registered:
        raise RuntimeError(f"rep={rep}: 연결 후에도 게이지가 {baseline + len(sockets)}에 도달 안 함 "
                            f"(현재 {read_gauge()}) — 핸드셰이크/등록 실패 의심")

    # 비정상 종료 — WS Close 프레임 없이 raw TCP만 끊는다(§9-3과 동일 기법,
    # 실제 모바일 클라이언트의 "네트워크가 갑자기 끊김"과 같은 조건).
    t0 = time.monotonic()
    for s in sockets:
        s.close()

    elapsed = None
    while time.monotonic() - t0 < ceiling_s:
        if read_gauge() <= baseline:
            elapsed = time.monotonic() - t0
            break
        time.sleep(poll_s)

    return elapsed, len(sockets), len(failed)


def median(values):
    values = sorted(values)
    n = len(values)
    return values[n // 2] if n else float("nan")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sessions", type=int, default=3, help="판마다 동시에 끊을 세션 수")
    parser.add_argument("--reps", type=int, default=5)
    parser.add_argument("--ceiling", type=float, default=180.0, help="감지 대기 상한(초)")
    parser.add_argument("--poll", type=float, default=0.5, help="폴링 간격(초)")
    parser.add_argument("--port", type=int, default=8080, help="백엔드 앱 포트")
    parser.add_argument("--mgmt-port", type=int, default=9090, help="백엔드 관리(actuator) 포트")
    parser.add_argument("--mysql-container", default="shadowfit-mysql", help="member id 조회용 MySQL 컨테이너명")
    parser.add_argument("--pace", type=float, default=1.05,
                         help="계정 생성 간격(초) — §9-3의 AuthRateLimitFilter(IP당 60건/60초) 우회용")
    args = parser.parse_args()

    global PORT, BASE_URL, PROMETHEUS_URL, MYSQL_CONTAINER
    PORT = args.port
    BASE_URL = f"http://{HOST}:{PORT}"
    PROMETHEUS_URL = f"http://127.0.0.1:{args.mgmt_port}/actuator/prometheus"
    MYSQL_CONTAINER = args.mysql_container
    print(f"[target] {BASE_URL}  /  {PROMETHEUS_URL}  /  mysql={MYSQL_CONTAINER}")

    baseline0 = read_gauge()
    print(f"[baseline] shadowfit_group_ws_active_sessions={baseline0:.0f}")
    if baseline0 != 0:
        print("  🔴 시작 전 게이지가 0이 아니다 — 이전 실행의 잔여 세션이 남아 있을 수 있음. "
              "결과 해석 시 감안할 것.")

    run_id = uuid.uuid4().hex[:8]
    n = args.sessions
    print(f"[setup] run_id={run_id}, sessions/rep={n}, reps={args.reps}, "
          f"poll={args.poll}s, ceiling={args.ceiling}s, pace={args.pace}s")

    # 계정은 전체 실행에서 한 번만 만들고 판마다 재사용한다(§9-3: IP당 signup·login 각 60건/60초
    # 제한 — N이 크면 판마다 새로 만드는 건 페이싱 비용이 반복돼 낭비다). 판마다 그룹은 새로 만든다.
    print(f"[setup] 계정 {n}개 생성 중 (페이싱 {args.pace}s)...")
    tokens, emails = [], []
    t_setup = time.monotonic()
    for i in range(n):
        email = f"lt-lag-{run_id}-{i}@test.local"
        tokens.append(signup_and_login(f"lt-lag-{run_id}-{i}", email))
        emails.append(email)
        if i < n - 1:
            time.sleep(args.pace)
    member_ids = member_ids_for(emails)
    print(f"[setup] 계정 {n}개 준비 완료 ({time.monotonic() - t_setup:.0f}s)")

    elapsed_by_rep = []
    counts_by_rep = []
    for rep in range(args.reps):
        t0 = time.monotonic()
        elapsed, connected, failed = run_trial(rep, tokens, member_ids, run_id, args.ceiling, args.poll)
        wall = time.monotonic() - t0
        if elapsed is None:
            print(f"  rep={rep+1}/{args.reps}: 감지 안 됨(상한 {args.ceiling}s 초과) — ceiling을 늘려 재시도 필요 "
                  f"(연결 {connected}/{n}, 실패 {failed})")
        else:
            print(f"  rep={rep+1}/{args.reps}: 감지까지 {elapsed:.1f}s (판 전체 소요 {wall:.1f}s, "
                  f"연결 {connected}/{n}, 실패 {failed})")
        elapsed_by_rep.append(elapsed)
        counts_by_rep.append({"connected": connected, "failed": failed})

    detected = [e for e in elapsed_by_rep if e is not None]
    print(f"\n{'='*60}\n집계 ({len(detected)}/{args.reps}판 감지됨)\n{'='*60}")
    if detected:
        print(f"  중앙값={median(detected):.1f}s, 범위={min(detected):.1f}~{max(detected):.1f}s")
        print(f"  판별 값: {['%.1f' % v for v in detected]}")
    else:
        print("  전부 상한 내 감지 안 됨 — --ceiling을 늘려 재실행할 것")

    out_dir = REPO_ROOT / "loadtest" / "results" / f"group-ws-disconnect-lag-{time.strftime('%Y-%m-%d')}"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"run-{run_id}.json"
    out_path.write_text(json.dumps({
        "run_id": run_id, "args": vars(args),
        "elapsed_s_by_rep": elapsed_by_rep,
        "counts_by_rep": counts_by_rep,
    }, indent=2), encoding="utf-8")
    print(f"\n[결과 저장] {out_path}")


if __name__ == "__main__":
    main()
