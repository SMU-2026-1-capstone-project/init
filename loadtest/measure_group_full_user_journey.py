"""그룹 풀 유저저니 — 가입~그룹참여~운동세션 시작~그룹이벤트 발행을 REST+WS가 겹치는 채로 잰다.

배경: docs/decisions/group-full-user-journey-scenario.md.
지금까지의 실측(§1-1~§1-5·§9·§11)은 REST(가입/로그인/그룹 API)와 WS(핸드셰이크/발행)를
따로 쟀다. §1-1이 "WS가 Tomcat 스레드풀을 HTTP 커넥터와 공유한다"를 코드 사실로 확정했고
§9-4는 "WS 버스트만으로는 busy_threads가 M=8~11까지만 올랐다"(풀 200에 한참 못 미침)까지
갔다 — 이 스크립트는 같은 순간에 REST(세션 시작)까지 얹었을 때 그 지점이 §9-4보다 가까이
있는지를 본다.

확정된 설계(문서 §3-1~3-3, 2026-09-01 사용자 confirm):
    - AI /pose 루프는 제외 — POST /exercises/sessions 응답까지만 잰다.
    - 1회성 라운드 — 정상상태 반복 부하가 아니라 매 판이 독립된 스파이크.
    - G=100×M=3(300연결) 고정, 5판 반복.

🔴 구현 중 발견한 스코프 조정 두 가지 ([[feedback_decision_doc]] 정신 — 조용히 바꾸지 않고 여기 명시):

  1. AuthRateLimitProperties(이슈 #394, application.yml security.rate-limit)가 IP 하나당
     /member/signup·/member/login 각각 60건/60초로 막는다(기본 활성화). 로컬에서 300개
     계정을 신호 하나로 동시에 만드는 것 자체가 §9-1과 같은 벽에 부딪힌다 — 계정 생성은
     "측정 대상"이 아니라 "측정을 위한 조건"이므로, 이 스크립트는 가입·로그인·온보딩을
     페이싱(기본 50건/60초, 한도 아래 안전마진)해서 순차로 준비하고, 그룹 결성(초대·수락,
     rate limit 대상 아님)도 이어서 순차 준비한다. **동시성 창은 WS 접속 → 세션 시작(REST)
     → 이벤트 발행(WS)** 구간에만 건다 — 이 구간만으로도 "REST가 WS 옆에 동시에 있다"는
     §0의 질문에는 답한다(세션 시작 자체가 Tomcat 스레드를 잡는 REST 호출이다).

  2. SessionService.createSession은 회원 하나당 IN_PROGRESS 세션이 있으면 409
     (SESSION_ALREADY_IN_PROGRESS)를 던진다(SessionService.java:102). endSession을
     불러도 status는 AI의 CompleteAnalysis 콜백(applyComplete)이 와야 바뀌는데, 이 실험은
     AI 루프를 스코프 밖으로 뒀으므로(위 확정 ①) 그 콜백이 영영 안 온다 — 즉 계정을
     재사용하면 2판째부터 전원이 409를 받는다. 그래서 **판(rep)마다 새 계정 300명을
     새로 만든다** — 재사용이 아니라 "1회성 라운드"를 5번 반복한다는 확정 설계(§3-2)와도
     맞다. 대가는 총 소요 시간(계정 1,500명 × 페이싱)이 커진다는 것 — §9-1이 겪은 실측
     비용의 연장선이다.

실행:
    python measure_group_full_user_journey.py [--groups 100] [--members-per-group 3] \\
        [--reps 5] [--exercise-id 1] [--signup-pace-per-min 50]
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

HOST = os.environ.get("BACKEND_HOST", "localhost")
PORT = int(os.environ.get("BACKEND_PORT", "8080"))
BASE_URL = f"http://{HOST}:{PORT}"
PROMETHEUS_URL = os.environ.get("PROM_URL", "http://127.0.0.1:9090/actuator/prometheus")
MYSQL_CONTAINER = os.environ.get("MYSQL_CONTAINER", "shadowfit-mysql")
REPO_ROOT = Path(__file__).resolve().parent.parent

# 온보딩 미완료 회원은 세션 시작이 400(INVALID_INPUT_VALUE)으로 막힌다
# (ExerciseAnalysisService.startAnalysis, member.getPreferredUrl() null 체크).
# 어떤 값이든 "설정돼 있음"만 중요하므로 기존 시드 데이터(V4__seed_squat_reference.sql,
# 이슈 [[project_squat_first]])와 같은 스쿼트 기준 영상을 그대로 쓴다.
DEFAULT_PREFERRED_URL = "https://www.youtube.com/watch?v=q6hBSSis_60"


# ── REST 헬퍼 (기존 measure_group_ws_*.py와 동일 패턴) ──────────────────────

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
    cmd = ["docker", "exec", "-i", MYSQL_CONTAINER, "mysql",
           "-uroot", f"-p{env['MYSQL_ROOT_PASSWORD']}", env["MYSQL_DATABASE"], "-N", "-e", sql]
    return subprocess.check_output(cmd, text=True, stderr=subprocess.DEVNULL)


def signup_and_login(username, email, password="Passw0rd!1"):
    status, body = http_json("POST", "/member/signup",
                              {"username": username, "email": email, "password": password, "sex": "MALE"})
    assert status == 200, f"signup({email}) 실패: {status} {body}"
    status, body = http_json("POST", "/member/login", {"email": email, "password": password})
    assert status == 200, f"login({email}) 실패: {status} {body}"
    return body["accessToken"]


def onboard(token, email, preferred_url=DEFAULT_PREFERRED_URL):
    status, body = http_json("PATCH", f"/member/onboarding/{email}",
                              {"preferredUrl": preferred_url}, token=token)
    assert status == 200, f"온보딩({email}) 실패: {status} {body}"


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


def start_session(token, exercise_id):
    status, body = http_json("POST", "/exercises/sessions", {"exerciseId": exercise_id}, token=token)
    if status != 202:
        raise RuntimeError(f"세션 시작 실패: {status} {body}")
    return body


# ── raw WebSocket 클라이언트 (stdlib만 — measure_group_ws_seq_lock_contention.py와 동일) ──

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


# ── 지표 폴러 (measure_group_ws_multi_group_spike.py와 동일) ────────────────

import re

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


# ── 여정의 동시성 구간: WS 접속 → 세션 시작(REST) → 이벤트 발행(WS, 자기 echo로 왕복 확인) ──

def journey_burst(token, group_id, exercise_id, barrier, out, idx):
    result = {"ws_connect_ms": None, "session_start_ms": None, "publish_ms": None, "error": None}
    sock = None
    try:
        barrier.wait()

        t0 = time.monotonic()
        sock, leftover_bytes = ws_connect(f"/ws/groups/{group_id}", token)
        result["ws_connect_ms"] = (time.monotonic() - t0) * 1000
        leftover = [leftover_bytes]

        t1 = time.monotonic()
        start_session(token, exercise_id)
        result["session_start_ms"] = (time.monotonic() - t1) * 1000

        sock.settimeout(15)
        marker_id = idx
        sent_at = time.monotonic()
        frame = json.dumps({"type": "JOURNEY_EVENT", "payload": {"marker": "journey", "id": marker_id, "sent_at": sent_at}})
        sock.sendall(encode_text_frame(frame))

        while True:
            opcode, payload = read_frame(sock, leftover)
            if opcode is None:
                result["error"] = "발행 후 소켓이 닫힘(echo 못 받음)"
                break
            if opcode == 0x9:  # ping → pong
                try:
                    sock.sendall(bytes([0x8A, 0x00]))
                except OSError:
                    pass
                continue
            if opcode == 0x8:  # close
                result["error"] = "발행 후 서버가 close를 보냄(echo 못 받음)"
                break
            if opcode != 0x1:
                continue
            try:
                envelope = json.loads(payload.decode("utf-8"))
                inner = json.loads(envelope["payload"])
            except (json.JSONDecodeError, KeyError):
                continue
            if inner.get("marker") == "journey" and inner.get("id") == marker_id:
                result["publish_ms"] = (time.monotonic() - inner["sent_at"]) * 1000
                break
    except socket.timeout:
        result["error"] = result["error"] or "타임아웃"
    except (OSError, ConnectionError, RuntimeError, AssertionError) as e:
        result["error"] = result["error"] or str(e)
    finally:
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass
    out[idx] = result


def summarize(values):
    if not values:
        return {"p50": None, "p90": None, "max": None, "n": 0}
    values = sorted(values)
    n = len(values)
    return {"p50": round(values[n // 2], 1), "p90": round(values[int(n * 0.9)], 1),
            "max": round(values[-1], 1), "n": n}


# ── 판(rep) 하나: 계정 준비(페이싱) → 그룹 결성 → 동시성 구간 → 집계 ────────

def run_round(rep_idx, run_id, g, m, exercise_id, pace_interval_s):
    total = g * m
    print(f"\n[rep {rep_idx}] 계정 {total}명 준비 시작 (페이싱 {pace_interval_s:.2f}s/건, "
          f"예상 {total * pace_interval_s / 60:.1f}분)")

    accounts = []
    for i in range(total):
        email = f"lt-uj-{run_id}-r{rep_idx}-{i}@test.local"
        t0 = time.monotonic()
        token = signup_and_login(f"lt-uj-{run_id}-r{rep_idx}-{i}", email)
        onboard(token, email)
        accounts.append((email, token))
        elapsed = time.monotonic() - t0
        if elapsed < pace_interval_s:
            time.sleep(pace_interval_s - elapsed)
        if (i + 1) % 50 == 0:
            print(f"  ...{i + 1}/{total} 계정 준비")
    print(f"[rep {rep_idx}] 계정 {total}명 준비 완료")

    group_ids = []
    member_tokens = []  # (group_id, token) 평평한 리스트, journey_burst 입력
    for gi in range(g):
        owner_email, owner_token = accounts[gi * m]
        group_id = create_group(owner_token, f"journey-{run_id}-r{rep_idx}-{gi}")["id"]
        group_ids.append(group_id)
        member_tokens.append((group_id, owner_token))
        for mi in range(1, m):
            email, tok = accounts[gi * m + mi]
            member_id = member_id_for(email)
            inv = invite(owner_token, group_id, member_id)
            accept(tok, inv["id"])
            member_tokens.append((group_id, tok))
    print(f"[rep {rep_idx}] 그룹 {g}개, 멤버십 {len(member_tokens)}건 준비 완료")

    baseline = read_metrics()
    poller = Poller(interval_s=0.05)
    poller.start()
    time.sleep(0.2)

    barrier = threading.Barrier(total)
    results = [None] * total
    threads = [threading.Thread(target=journey_burst, args=(tok, gid, exercise_id, barrier, results, i))
               for i, (gid, tok) in enumerate(member_tokens)]

    t0 = time.monotonic()
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=40)
    wall_ms = (time.monotonic() - t0) * 1000

    time.sleep(0.5)
    peak = {k: poller.peak(k) for k in METRIC_RES}
    poller.stop()
    poller.join(timeout=1)

    ws_lat = [r["ws_connect_ms"] for r in results if r and r["ws_connect_ms"] is not None]
    sess_lat = [r["session_start_ms"] for r in results if r and r["session_start_ms"] is not None]
    pub_lat = [r["publish_ms"] for r in results if r and r["publish_ms"] is not None]
    errors = [r["error"] for r in results if r and r["error"]]

    rep_result = {
        "rep": rep_idx, "total": total, "wall_ms": round(wall_ms),
        "baseline": baseline, "peak": peak,
        "succeeded": total - len(errors), "failed": len(errors),
        "errors_sample": errors[:5],
        "ws_connect_ms": summarize(ws_lat),
        "session_start_ms": summarize(sess_lat),
        "publish_ms": summarize(pub_lat),
    }
    print(f"[rep {rep_idx}] wall={wall_ms:.0f}ms 성공={rep_result['succeeded']}/{total} "
          f"ws접속 p50={rep_result['ws_connect_ms']['p50']}ms "
          f"세션시작 p50={rep_result['session_start_ms']['p50']}ms "
          f"발행왕복 p50={rep_result['publish_ms']['p50']}ms "
          f"peak(jvm={peak['jvm_threads']:.0f} tomcat_busy={peak['tomcat_busy']:.0f}(max 200) "
          f"hikari_pending={peak['hikari_pending']:.0f})")
    if errors:
        print(f"  실패 샘플: {errors[:5]}")
    return rep_result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--groups", type=int, default=100)
    parser.add_argument("--members-per-group", type=int, default=3)
    parser.add_argument("--reps", type=int, default=5)
    parser.add_argument("--exercise-id", type=int, default=1, help="V2__seed_master_data.sql의 스쿼트(id=1)")
    parser.add_argument("--signup-pace-per-min", type=int, default=50,
                         help="AuthRateLimitProperties(#394, 60/60s) 아래로 유지하는 안전마진 페이싱")
    args = parser.parse_args()
    g, m, reps = args.groups, args.members_per_group, args.reps
    pace_interval_s = 60.0 / args.signup_pace_per_min

    run_id = uuid.uuid4().hex[:8]
    total_accounts = g * m * reps
    print(f"[setup] run_id={run_id} groups={g} members/group={m} reps={reps} "
          f"총 참여자(전체 판 합)={total_accounts}")

    all_reps = [run_round(rep + 1, run_id, g, m, args.exercise_id, pace_interval_s) for rep in range(reps)]

    print(f"\n{'=' * 70}\n집계 (판 하나 = 표본 하나)\n{'=' * 70}")
    for key, label in (("ws_connect_ms", "WS접속"), ("session_start_ms", "세션시작"), ("publish_ms", "발행왕복")):
        p50s = [r[key]["p50"] for r in all_reps if r[key]["p50"] is not None]
        print(f"  {label}: 판별 p50={p50s}")
    peaks = {k: [r["peak"][k] for r in all_reps] for k in METRIC_RES}
    for k, vals in peaks.items():
        print(f"  {k} 판별 최댓값={vals}")
    print(f"  실패 합계={sum(r['failed'] for r in all_reps)}/{sum(r['total'] for r in all_reps)}")

    out_dir = REPO_ROOT / "loadtest" / "results" / f"group-full-user-journey-{time.strftime('%Y-%m-%d')}"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"run-{run_id}.json"
    out_path.write_text(json.dumps({"run_id": run_id, "args": vars(args), "reps": all_reps}, indent=2, default=str),
                         encoding="utf-8")
    print(f"\n[결과 저장] {out_path}")


if __name__ == "__main__":
    main()
