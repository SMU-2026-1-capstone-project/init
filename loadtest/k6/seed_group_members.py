"""group_ws_handshake_concurrency.js 용 사전 시딩 — 계정 M개 + 그룹 1개 + 전원 ACTIVE 멤버.

왜 별도 스크립트인가: k6(group_ws_handshake_concurrency.js)는 순수 HTTP 클라이언트라
email -> memberId 조회 수단이 없다(이 앱은 /member/me 가 없고, admin 조회 API는 role
승격 경로 자체가 막혀 있어 signup으로 admin 토큰을 못 만든다 — MemberRequestDto 주석,
이슈 #138). 기존 measure_group_handshake_concurrency.py와 동일하게 docker exec mysql로
memberId를 직접 읽어 그룹 멤버십을 구성한 뒤, k6가 바로 쓸 수 있는 JSON(그룹ID + 토큰
배열)만 남긴다 — 부하 생성 자체는 k6가 전담한다.

레이트리밋 주의: AuthRateLimitFilter가 /member/signup·/member/login 각각 IP당 60건/60초.
M > 55 정도부터 --pacing-ms(기본 900ms)로 계정 생성을 늦춘다.

실행:
    python seed_group_members.py --count 100 --out seed-100.json
    BASE=http://<대상>:8080 python seed_group_members.py --count 300 --pacing-ms 950 --out seed-300.json
"""

import argparse
import json
import os
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

HOST = os.environ.get("HOST", "localhost")
PORT = int(os.environ.get("PORT", "8080"))
BASE_URL = os.environ.get("BASE", f"http://{HOST}:{PORT}")
REPO_ROOT = Path(__file__).resolve().parent.parent.parent


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
    if status == 429:
        raise RuntimeError(f"signup 429({email}) — 레이트리밋. --pacing-ms를 늘릴 것")
    assert status == 200, f"signup({email}) 실패: {status} {body}"
    status, body = http_json("POST", "/member/login", {"email": email, "password": password})
    if status == 429:
        raise RuntimeError(f"login 429({email}) — 레이트리밋. --pacing-ms를 늘릴 것")
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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=40)
    parser.add_argument("--pacing-ms", type=int, default=900,
                         help="계정 하나당 이 간격(ms)만큼 쉰다 — signup+login 각 1건씩이라 "
                              "60초/60건 한도 아래로 유지하려면 넉넉히(900ms 기본 = 66.7건/60초 여유)")
    parser.add_argument("--out", default=None)
    args = parser.parse_args()

    run_id = uuid.uuid4().hex[:8]
    print(f"[seed] run_id={run_id} count={args.count} pacing_ms={args.pacing_ms} base={BASE_URL}")

    tokens, emails = [], []
    t_start = time.monotonic()
    for i in range(args.count):
        email = f"lt-hsk6-{run_id}-{i}@test.local"
        tokens.append(signup_and_login(f"lt-hsk6-{run_id}-{i}", email))
        emails.append(email)
        if i < args.count - 1:
            time.sleep(args.pacing_ms / 1000)
        if (i + 1) % 20 == 0 or i == args.count - 1:
            elapsed = time.monotonic() - t_start
            print(f"  계정 {i + 1}/{args.count} 준비됨 ({elapsed:.0f}s 경과)")

    owner_token = tokens[0]
    group_id = create_group(owner_token, f"k6-hs-{run_id}")["id"]
    for tok, email in zip(tokens[1:], emails[1:]):
        mid = member_id_for(email)
        inv = invite(owner_token, group_id, mid)
        accept(tok, inv["id"])
    print(f"[seed] group_id={group_id}, 멤버 {args.count}명 (전부 ACTIVE)")

    out_path = Path(args.out) if args.out else (
        REPO_ROOT / "loadtest" / "k6" / f"seed-group-{run_id}.json")
    out_path.write_text(json.dumps({"run_id": run_id, "groupId": group_id, "tokens": tokens}, indent=2),
                         encoding="utf-8")
    print(f"[seed] 저장: {out_path}")
    print(f"       k6 실행: SEED_FILE={out_path} CONCURRENCY={args.count} k6 run group_ws_handshake_concurrency.js")


if __name__ == "__main__":
    main()
