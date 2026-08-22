#!/bin/bash
# 동거 용량 rig — 승격 게이트 (P6)
#
# 🔴 **본 측정 전에 여기서 멈춘다.** 이 프로젝트가 반복해 밟은 사고는 «안 도는 rig 을 무인으로
#    돌려 몇 시간을 버린 것»(08-12)과 «조용히 다른 것을 잰 것»(#201·#202)이다. 게이트는 둘 다 막는다.
#
# 사용:
#   BASE=http://localhost:8080 AI=http://localhost:8000 TOKEN=... bash probe.sh
#
# 종료 코드 0 = 전 게이트 통과 → EC2 로 올려도 된다.

set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)

BASE=${BASE:-http://localhost:8080}
AI=${AI:-http://localhost:8000}
TOKEN=${TOKEN:-}
AI_CONTAINER=${AI_CONTAINER:-shadowfit-ai}
# 🔴 G3 는 **대상 박스의** 컨테이너를 봐야 한다. 게이트를 부하기에서 돌리면 로컬 docker 에는
#    그 컨테이너가 없어서 «못 찾겠다» 로 떨어지는데, 그건 캡이 없는 것이 아니라 **보는 곳이
#    틀린 것**이다. 환경 결함이 게이트 실패로 위장하는 자리라 통로를 열어둔다.
#      예) DOCKER="ssh root@10.0.0.5 docker" bash probe.sh
DOCKER=${DOCKER:-docker}
ORIG=${ORIG:-$HERE/../../measure_ai_concurrency.py}
FRAMES=${FRAMES:-$HERE/frames.json}

FAIL=0
g() { echo; echo "──── $* ────"; }
ok() { echo "  ✅ $*"; }
no() { echo "  🔴 $*"; FAIL=$((FAIL+1)); }

echo "════════ 동거 용량 rig — 승격 게이트 ════════"

# ── G0. 합성 인체 사본이 원본과 같은가 ───────────────────────────────────
# 두 rig 이 서로 다른 인체를 재면서 같은 이름을 쓰는 것을 막는다. 사본을 둔 이유는
# synthetic_body.py 의 docstring 에 있다(원본은 import 만 해도 측정이 돈다).
g "G0. 합성 인체 사본 ↔ 원본 대조"
if [ ! -f "$ORIG" ]; then
  no "원본을 못 찾겠다: $ORIG"
else
  python - "$ORIG" "$HERE/synthetic_body.py" <<'PY'
import sys, re
def funcs(path):
    s = open(path, encoding='utf-8').read()
    # figure() 시작부터 squat_cycle() 본문 끝까지
    m = re.search(r'def figure\(.*?return \[figure\(squat=.*?\]\n', s, re.S)
    return m.group(0) if m else None
a, b = funcs(sys.argv[1]), funcs(sys.argv[2])
if a is None or b is None:
    print("  🔴 함수 구간을 못 떼어냈다 — 원본 구조가 바뀌었다. 사본 대조가 무의미하니 확인할 것")
    sys.exit(2)
if a != b:
    print("  🔴 **사본이 원본과 다르다.** 두 측정이 다른 인체를 재게 된다 — 동기화할 것")
    sys.exit(1)
print("  ✅ 사본이 원본과 글자 단위로 같다")
PY
  [ $? -eq 0 ] || FAIL=$((FAIL+1))
fi

# ── G1. 프레임 자산 ──────────────────────────────────────────────────────
g "G1. 프레임 자산 — 검출이 되는가"
if [ ! -f "$FRAMES" ]; then
  no "$FRAMES 가 없다 — gen_frames.py 로 먼저 만든다"
else
  python - "$FRAMES" <<'PY'
import json, sys
b = json.load(open(sys.argv[1]))
m = b.get("meta", {})
n, dok = m.get("n"), m.get("detect_ok")
if not b.get("frames"):
    print("  🔴 프레임이 비어 있다"); sys.exit(1)
if dok != n:
    print(f"  🔴 검출 {dok}/{n} — 전 프레임 검출이 아니면 부하가 «탐지 실패» 를 잰다"); sys.exit(1)
print(f"  ✅ {n}프레임 · 검출 {dok}/{n} · 무릎 {m.get('knee_deg_min')}~{m.get('knee_deg_max')}°")
print(f"     ⚠️ rep 문턱(155°)은 안 넘는다 — 그래서 Spring 부하는 ghz 로 따로 건다(README §2)")
PY
  [ $? -eq 0 ] || FAIL=$((FAIL+1))
fi

# ── G2. 세션 개설 경로 ───────────────────────────────────────────────────
# 🔴 이게 이 rig 의 급소다. 세션이 안 열리면 프레임이 전부 «분석기 없음» 으로 거절되고,
#    표에는 요청 수·지연이 정상으로 찍힌다. 숫자가 나오는데 다른 것을 잰 전형이다.
g "G2. 세션 개설 → 프레임 수락"
if [ -z "$TOKEN" ]; then
  no "TOKEN(AI_PUBLIC_TOKEN)이 비었다 — AI HTTP 는 401 이다"
else
  python - "$BASE" "$AI" "$TOKEN" "$FRAMES" <<'PY'
import json, os, sys, time, urllib.request, urllib.error
base, ai, token, framespath = sys.argv[1:5]
def http(url, method="GET", body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    h = {"Content-Type": "application/json"}; h.update(headers or {})
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return 0, f"{type(e).__name__}: {e}"

em = f"probe{int(time.time())}@shadowfit.local"; pw = "P@ssw0rd!"
http(base + "/member/signup", "POST", {"username": em.split('@')[0], "email": em,
     "password": pw, "sex": "MALE", "role": "USER"})
st, body = http(base + "/member/login", "POST", {"email": em, "password": pw})
if st != 200:
    print(f"  🔴 로그인 {st}: {body[:150]}"); sys.exit(1)
tok = json.loads(body)["accessToken"]; auth = {"Authorization": "Bearer " + tok}
st, body = http(base + f"/member/onboarding/{em}", "PATCH", {
    "selectedPersona": "ADVANCED", "workoutLevel": "STARTER", "height": 180.0,
    "weight": 75.5, "preferredUrl": "https://www.youtube.com/watch?v=q6hBSSis_60"}, auth)
if st != 200:
    print(f"  🔴 온보딩 {st}: {body[:150]}  ← preferredUrl 없으면 세션이 400 이다"); sys.exit(1)
st, body = http(base + "/exercises/sessions", "POST", {"exerciseId": 1}, auth)
try:
    sid = json.loads(body)["sessionId"]
except Exception:
    print(f"  🔴 세션 생성 {st}: {body[:200]}"); sys.exit(1)
print(f"  ✅ 세션 생성 — sessionId={sid}")

frames = json.load(open(framespath))["frames"]

# 🔴 세션 생성 응답은 즉시 오지만 `StartAnalysis` 는 afterCommit + @Async 로 **그 뒤에** 나간다
#    (ExerciseAnalysisService:210-217). 그래서 생성 직후의 첫 프레임은 정상 시스템에서도
#    «분석기가 없습니다» 로 거절된다 — 2026-08-16 EC2 실측: 0s 거절 / 2s 성공.
#    붙을 때까지 기다리되, **안 붙는 것과 늦게 붙는 것은 구분해서** 적는다.
ATTACH_SEC = float(os.environ.get("ATTACH_SEC", "20"))
t0 = time.monotonic()
waited = None
while True:
    st, body = http(ai + "/api/v1/pose", "POST",
                    {"image": frames[0], "exercise_type": "squat", "session_id": sid,
                     "timestamp_sec": 0.0}, {"Authorization": "Bearer " + token})
    if st != 200:
        print(f"  🔴 프레임 {st}: {body[:150]}"); sys.exit(1)
    j = json.loads(body)
    if j.get("success"):
        waited = time.monotonic() - t0
        break
    msg = j.get("message", "")
    if "분석기가 없습니다" not in msg:
        print(f"  🔴 프레임이 거절됐다: {msg[:120]}"); sys.exit(1)
    if time.monotonic() - t0 >= ATTACH_SEC:
        print(f"  🔴 {ATTACH_SEC:.0f}s 안에 분석기가 안 붙었다: {msg[:120]}")
        print("     → Spring→AI gRPC 또는 검출기 풀을 본다 (경합이 아니라 «안 붙는» 것이다)")
        sys.exit(1)
    time.sleep(0.5)   # AI 유입 간격 상한(300ms)보다 넉넉히
print(f"  ✅ 프레임 수락 + 검출 성공 (분석기 부착까지 {waited:.1f}s 대기)")
http(base + f"/sessions/{sid}/end", "PATCH", None, auth)
PY
  [ $? -eq 0 ] || FAIL=$((FAIL+1))
fi

# ── G3. 캡이 실제로 걸려 있는가 (팔 C·D 전제) ────────────────────────────
g "G3. 컨테이너 자원 한도 — 팔이 실제로 갈리는가"
lim=$($DOCKER inspect -f '{{.HostConfig.Memory}}' "$AI_CONTAINER" 2>/dev/null | tr -d '\r')
cpu=$($DOCKER inspect -f '{{.HostConfig.NanoCpus}}' "$AI_CONTAINER" 2>/dev/null | tr -d '\r')
if [ -z "$lim" ]; then
  no "$AI_CONTAINER 를 못 찾겠다 (DOCKER='$DOCKER' — 보는 곳이 맞는지부터 볼 것)"
else
  if [ "$lim" = "0" ]; then
    no "메모리 한도가 없다 — 첫 세션에서 RuntimeError 다 (#214). 팔 B 도 메모리 캡은 걸어야 한다"
  else
    ok "메모리 한도 $((lim/1024/1024))MB"
  fi
  # CPU 는 팔에 따라 있기도 없기도 하다 — «사실만» 적는다. 판정하지 않는다.
  if [ "$cpu" = "0" ]; then
    echo "     CPU 한도: 없음  (팔 A·B 조건)"
  else
    echo "     CPU 한도: $(echo "$cpu" | awk '{printf "%.2f", $1/1000000000}') cores  (팔 C·D 조건)"
  fi
fi

# ── 판정 ─────────────────────────────────────────────────────────────────
echo
if [ "$FAIL" -eq 0 ]; then
  echo "✅ 전 게이트 통과 — EC2 로 올려도 된다"
  exit 0
fi
echo "🔴 게이트 ${FAIL}개 실패 — **올리지 않는다.** 로컬에서 닫고 다시 돌린다"
exit 1
