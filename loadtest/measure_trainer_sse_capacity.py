"""트레이너 SSE 캐파시티 실측 — heartbeat 틱이 N(동시 연결 수)에 얼마나 늘어나는가
(trainer-live-monitoring.md §8 세션7~8).

── 무엇을 재는가, 그리고 왜 이것만 재는가 ──────────────────────────────────────
  TrainerConnectionRegistry.broadcast(userId, ...) 는 그 userId 의 연결(보통 1~2개)만
  건드린다 — 전체 연결 수 N 과 무관하게 빠르다. 반면 heartbeat() 는 **모든 사용자의
  모든 연결을 스케줄러 스레드 하나에서 순차로** 돈다(TrainerConnectionRegistry.java).
  그래서 N 이 커질 때 실제로 늘어나는 유일한 경로는 heartbeat 뿐이고, 이 rig 은 그것만 잰다.

  ⚠️ project_loadtest_env_constraint.md 대로 이 박스(i3-6100 2코어)에서 나오는 절대
  밀리초는 프로덕션 캐파시티가 아니다. 믿을 것은 **N 에 따른 기울기(선형이냐)와 어디서
  무너지기 시작하는가**뿐이다.

── 어떻게 재는가(서버 코드 수정 없이) ──────────────────────────────────────────
  heartbeat() 한 틱 안에서 N 개 연결에 순차로 보내므로, 같은 틱 안에서도 "먼저 보낸
  연결"과 "마지막에 보낸 연결"의 클라이언트 수신 시각이 벌어진다. 그 벌어짐
  (max - min, 이 rig 에서 "틱 스프레드")이 곧 그 틱의 heartbeat() 실행 시간의 근사치다
  — 로컬 네트워크 지연은 무시할 만큼 작다는 전제. 서버에 계측 코드를 추가하지 않고
  클라이언트 관찰만으로 재는 이유는, 실제 프로덕션에도 없는 계측을 넣어 그 결과에
  근거해 판단하면 "잰 것"과 "배포되는 것"이 달라지기 때문이다.

── N 범위 ───────────────────────────────────────────────────────────────────
  이 기능은 아직 미출시라 실제 트레이너 채택률 근거가 없다(2026-08-30 사용자 확인) —
  DAU 기반 역산 대신 로그스케일 스윕(10/30/100/300/500)으로 기울기와 무너지는 지점을 찾는다.

실행 전제: backend 를 별도 인스턴스로 격리 실행(port 8081) — README 참고.
"""

import asyncio
import json
import statistics
import subprocess
import sys
import time
from dataclasses import dataclass, field

import httpx
import jwt

BASE_URL = "http://localhost:8081"
MYSQL_CONTAINER = "shadowfit-mysql"
MYSQL_ROOT_PW = "1234"
TEST_DB = "shadowfit_ssetest"
JWT_SECRET = "your-very-long-secret-key-for-shadowfit-2026-project"  # docker inspect shadowfit-backend 실측값과 동일
HEARTBEAT_INTERVAL_SEC = 30  # application.yml coaching.trainer-stream.heartbeat-interval-seconds 기본값과 일치시킨다
N_LEVELS = [10, 30, 100, 300, 500]
TICKS_PER_LEVEL = 3  # 레벨당 관찰할 heartbeat 틱 수(워밍업 1 + 관측 2)


def mysql(sql: str) -> str:
    result = subprocess.run(
        ["docker", "exec", "-i", MYSQL_CONTAINER, "mysql", "-uroot", f"-p{MYSQL_ROOT_PW}", TEST_DB],
        input=sql, capture_output=True, text=True,
    )
    if result.returncode != 0:
        raise RuntimeError(f"mysql 실패: {result.stderr}")
    return result.stdout


def seed(n: int) -> list[tuple[int, int]]:
    """(userId, trainerId) 쌍 n개를 만든다. 이미 있으면 건너뛴다(재실행 안전)."""
    existing = mysql("SELECT COUNT(*) FROM users WHERE email LIKE 'sse-user-%@test.local';").strip().splitlines()
    have = int(existing[1]) if len(existing) > 1 else 0
    if have >= n:
        pass
    else:
        rows = []
        for i in range(have + 1, n + 1):
            rows.append(f"('sse-user-{i:04d}@test.local','x','sse_u{i:04d}','USER')")
            rows.append(f"('sse-trainer-{i:04d}@test.local','x','sse_t{i:04d}','TRAINER')")
        # 한 번에 다 넣는다 — N=500 이면 1000행, MySQL 기본 max_allowed_packet 에 안 걸림
        mysql("INSERT INTO users (email,password,username,role) VALUES " + ",".join(rows) + ";")

    out = mysql(
        "SELECT u.id, t.id FROM users u JOIN users t "
        "ON t.email = REPLACE(u.email, 'sse-user-', 'sse-trainer-') "
        "WHERE u.email LIKE 'sse-user-%@test.local' ORDER BY u.id LIMIT " + str(n) + ";"
    )
    pairs = [tuple(map(int, line.split())) for line in out.strip().splitlines()[1:]]

    assign_rows = ",".join(f"({t},{u})" for u, t in pairs)
    mysql(
        "INSERT IGNORE INTO trainer_assignments (trainer_id,user_id) VALUES " + assign_rows + ";"
    )
    return pairs


def mint_token(email: str) -> str:
    now = int(time.time())
    payload = {"sub": email, "userId": email, "role": "TRAINER", "iat": now, "exp": now + 3600}
    return jwt.encode(payload, JWT_SECRET, algorithm="HS256")


@dataclass
class ConnResult:
    user_id: int
    frames: list[tuple[float, str]] = field(default_factory=list)  # (도착시각, 프레임 종류)
    error: str | None = None


async def hold_connection(client: httpx.AsyncClient, user_id: int, token: str,
                           result: ConnResult, stop_at: float):
    url = f"{BASE_URL}/coaching/trainer/{user_id}/stream"
    try:
        async with client.stream("GET", url, headers={"Authorization": f"Bearer {token}"}) as resp:
            if resp.status_code != 200:
                result.error = f"status={resp.status_code}"
                return
            buf = ""
            async for chunk in resp.aiter_text():
                buf += chunk
                while "\n\n" in buf:
                    frame, buf = buf.split("\n\n", 1)
                    now = time.monotonic()
                    if frame.startswith(":"):
                        result.frames.append((now, "heartbeat"))
                    elif "event:connected" in frame:
                        result.frames.append((now, "connected"))
                    else:
                        result.frames.append((now, "other"))
                if time.monotonic() >= stop_at:
                    return
    except (httpx.ReadError, httpx.RemoteProtocolError, asyncio.CancelledError) as e:
        result.error = f"{type(e).__name__}: {e}"
    except Exception as e:  # noqa: BLE001 — 이 rig 은 원인 분류가 아니라 "몇 개나 죽었나"만 본다
        result.error = f"{type(e).__name__}: {e}"


async def run_level(pairs: list[tuple[int, int]], n: int) -> dict:
    subset = pairs[:n]
    duration = HEARTBEAT_INTERVAL_SEC * TICKS_PER_LEVEL + 5
    stop_at = time.monotonic() + duration

    limits = httpx.Limits(max_connections=n + 20, max_keepalive_connections=n + 20)
    timeout = httpx.Timeout(connect=10.0, read=duration + 10, write=10.0, pool=None)

    results = [ConnResult(user_id=u) for u, _t in subset]

    t0 = time.monotonic()
    async with httpx.AsyncClient(limits=limits, timeout=timeout, http2=False) as client:
        tasks = [
            asyncio.create_task(hold_connection(client, u, mint_token(f"sse-trainer-{i+1:04d}@test.local"), results[i], stop_at))
            for i, (u, _t) in enumerate(subset)
        ]
        connect_done = time.monotonic()
        await asyncio.gather(*tasks, return_exceptions=True)

    errors = [r for r in results if r.error]
    print(f"  [N={n}] 연결 오픈 {connect_done - t0:.2f}s(디스패치 큐잉일뿐, 실제 연결완료와는 별개) · "
          f"에러 {len(errors)}/{n}", file=sys.stderr)

    # 틱 스프레드: heartbeat 프레임들을 HEARTBEAT_INTERVAL_SEC 근사 간격으로 묶어
    # 각 틱에서 (그 틱 안 최초 수신 - 최후 수신)을 구한다.
    all_hb = sorted(
        (t for r in results for (t, kind) in r.frames if kind == "heartbeat")
    )
    ticks: list[list[float]] = []
    for t in all_hb:
        if ticks and t - ticks[-1][-1] < HEARTBEAT_INTERVAL_SEC * 0.5:
            ticks[-1].append(t)
        else:
            ticks.append([t])
    spreads = [max(tk) - min(tk) for tk in ticks if len(tk) >= max(2, n // 2)]

    return {
        "n": n,
        "connect_dispatch_sec": connect_done - t0,
        "errors": len(errors),
        "error_samples": [e.error for e in errors[:5]],
        "tick_count": len(ticks),
        "tick_sizes": [len(tk) for tk in ticks],
        "tick_spreads_sec": spreads,
        "tick_spread_p_max": max(spreads) if spreads else None,
        "tick_spread_mean": statistics.mean(spreads) if spreads else None,
    }


async def main():
    levels = N_LEVELS
    if len(sys.argv) > 1:
        levels = [int(x) for x in sys.argv[1].split(",")]
    max_n = max(levels)
    print(f"시딩: {max_n}쌍 (user,trainer) — 기존 것 재사용, 부족분만 추가", file=sys.stderr)
    pairs = seed(max_n)
    print(f"시딩 완료: {len(pairs)}쌍", file=sys.stderr)

    report = []
    for n in levels:
        if n > len(pairs):
            print(f"  [N={n}] 스킵 — 시드 부족({len(pairs)}쌍)", file=sys.stderr)
            continue
        print(f"[N={n}] 시작 — {HEARTBEAT_INTERVAL_SEC * TICKS_PER_LEVEL + 5}초 관찰", file=sys.stderr)
        level_result = await run_level(pairs, n)
        report.append(level_result)
        print(json.dumps(level_result, ensure_ascii=False, indent=2))

    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
