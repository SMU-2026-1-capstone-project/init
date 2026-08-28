#!/usr/bin/env bash
# 從 R12 선결 — k6 읽기 rig 이 쓸 «계정 + 데이터» 를 한 번에 만든다.
#
# 왜 필요한가:
#   시더(seed_report_rig.sh)가 member_id 를 1·5·12 로 하드코딩해서, 로그인할 수 있는 계정에는
#   읽을 데이터가 없고 데이터가 있는 계정에는 비밀번호를 모른다. 2026-08-23 로컬 판은 이 셋을
#   **손으로** 밟았다 — 계정 생성 · 그 member_id 로 시드 · 세션 id 추출.
#   從 은 «인프라가 살아 있을 때 추가 비용 거의 0» 이어야 하는데, 손으로 밟으면 그게 아니다.
#
# 🔴 남의 계정 비밀번호를 갈아끼우지 않는다. 내가 만든 계정에 데이터를 넣는다.
#
# 🔴 이 스크립트는 **시더를 복사하지 않는다** (#303 — rig 사본이 드리프트를 낳는다).
#    seed_report_rig.sh 에 MEMBER_ID·WITH_REPORTS 를 넘겨 호출한다. 행 모양의 정본은 그쪽이다.
#
# 쓰는 법:
#   loadtest/seed/seed_k6_read_account.sh            # 기본 1,000 세션
#   SESSIONS=200 SIDS_LIMIT=20 loadtest/seed/seed_k6_read_account.sh
#
# 마지막에 k6 실행줄을 그대로 찍는다. 복사해서 붙이면 된다.
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a; . ./.env; set +a

BASE=${BASE:-http://localhost:8080}
K6_EMAIL=${K6_EMAIL:-k6read@shadowfit.local}
K6_PASSWORD=${K6_PASSWORD:-K6read!2026}
K6_USERNAME=${K6_USERNAME:-k6read}
TAG=${TAG:-k6load}
SESSIONS=${SESSIONS:-1000}
SIDS_LIMIT=${SIDS_LIMIT:-50}

DB(){ docker exec -i shadowfit-mysql mysql -N -B -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit "$@"; }

echo "## [1] 계정 준비 — $K6_EMAIL"
# 이미 있으면 signup 이 실패한다. 그건 정상이므로 삼키고 조회로 넘어간다.
#
# 🔴 role 을 안 보낸다. MemberRequestDto 에 그 필드가 **없는 것 자체가 방어**다 (#138) —
#    permitAll 엔드포인트라 필드가 있으면 «권한을 요청자가 정한다» 가 된다. 실제로 뚫렸던 자리다.
#    지금은 ignoreUnknown 이라 보내도 버려지지만, 스크립트가 그 모양을 퍼뜨리지 않게 뺀다.
http_code=$(curl -s -o /tmp/_k6signup.txt -w '%{http_code}' \
  -X POST "$BASE/member/signup" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$K6_USERNAME\",\"email\":\"$K6_EMAIL\",\"password\":\"$K6_PASSWORD\",\"sex\":\"MALE\"}" || true)
echo "  signup HTTP $http_code — $(head -c 200 /tmp/_k6signup.txt)"

MEMBER_ID=$(DB -e "SELECT id FROM users WHERE email='$K6_EMAIL' LIMIT 1;" | tr -d '[:space:]')
if [ -z "$MEMBER_ID" ]; then
  echo "🔴 계정을 못 찾았다. signup 응답을 볼 것 (위 HTTP $http_code)." >&2
  exit 1
fi
echo "  member_id = $MEMBER_ID"

# 🔴 로그인이 되는지 여기서 확인한다. 시드를 다 해놓고 k6 가 401 로 죽으면
#    «데이터가 없나» 와 «로그인이 안 되나» 가 안 갈린다.
login_code=$(curl -s -o /tmp/_k6login.txt -w '%{http_code}' \
  -X POST "$BASE/member/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$K6_EMAIL\",\"password\":\"$K6_PASSWORD\"}" || true)
if [ "$login_code" != "200" ]; then
  echo "🔴 로그인 실패 (HTTP $login_code) — $(head -c 200 /tmp/_k6login.txt)" >&2
  echo "   계정이 이미 있고 비밀번호가 다르면 K6_PASSWORD 를 맞춰 넘길 것." >&2
  exit 1
fi
echo "  로그인 확인 OK"

echo
echo "## [2] 세션·프레임·리포트 시드 (정본은 seed_report_rig.sh)"
MEMBER_ID="$MEMBER_ID" WITH_REPORTS=1 TAG="$TAG" SESSIONS="$SESSIONS" \
  loadtest/seed/seed_report_rig.sh

echo
echo "## [3] K6_SIDS — 리포트가 붙은 세션만"
SIDS=$(DB -e "
  SELECT s.id FROM exercise_sessions s
    JOIN reports r ON r.session_id = s.id
   WHERE s.member_id = $MEMBER_ID AND s.reference_source = '$TAG'
   ORDER BY s.id LIMIT $SIDS_LIMIT;" | tr '\n' ',' | sed 's/,$//')

if [ -z "$SIDS" ]; then
  echo "🔴 리포트가 붙은 세션이 0개다 — [2] 의 [3-b] 출력을 볼 것." >&2
  exit 1
fi
echo "  세션 $(echo "$SIDS" | tr ',' '\n' | grep -c .) 개"

cat <<EOF

## 준비 끝. 아래를 그대로 돌리면 된다:

K6_EMAIL='$K6_EMAIL' K6_PASSWORD='$K6_PASSWORD' K6_SIDS='$SIDS' \
  k6 run --summary-trend-stats "avg,p(50),p(95),p(99),max" \
  loadtest/results/http-read-p99-2026-08-23/read_p99.js

⚠️ 로컬에서 돌리면 절대값은 판정선에 못 댄다 (2코어 동거 + 부하기 동거).
   판정선 대면은 從 R12 — loadtest/AWS-RIDE-ALONG.md
EOF
