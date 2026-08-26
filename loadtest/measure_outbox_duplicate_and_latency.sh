#!/usr/bin/env bash
# outbox 신뢰성 실측 — docs/decisions/outbox-reliable-messaging.md §6-5 "아직 안 한 것" 을 채운다.
#
# §6(2026-07-29)이 이미 로컬 Docker 스택(mysql+backend+ai)으로 실제 HTTP·gRPC 세션을 굴려
# 네트워크 단절(docker pause)·AI 재시작(docker restart)·서킷 OPEN 셋을 쟀다. 이 rig 은 같은
# 방법론으로 §6-5 의 나머지 둘을 잰다:
#
#   ① 중복 흡수 — 세션 하나를 정상 종료시켜 outbox 가 SENT·세션이 COMPLETED 로 수렴한 뒤,
#      같은 행을 SQL 로 강제로 PENDING 되돌려 발행기가 다시 집게 만든다(크래시 후 재전송을
#      흉내낸다 — §4-3-1 이 말하는 at-least-once 조건). 두 번째 송신이 실제로 AI 에 닿았을 때
#      세션·리포트 상태가 안전한지 관찰한다.
#   ② 지연 분포 — 정상 종료를 N 회 반복해 "PATCH .../end 요청 → outbox SENT" 소요를 잰다.
#
# 🔴 로컬(물리 2코어) 박스라 ②의 절대 지연값은 신뢰하지 않는다([[project_loadtest_env_constraint]]).
#    이 스크립트는 그대로 AWS 대상 박스에 BASE 만 바꿔 재사용하는 것을 전제로 짰다.
set -uo pipefail

BASE=${BASE:-http://localhost:8080}
MYSQL_CONTAINER=${MYSQL_CONTAINER:-shadowfit-mysql}
MYSQL_DB=${MYSQL_DB:-shadowfit}
MYSQL_USER=${MYSQL_USER:-root}
MYSQL_PW=${MYSQL_ROOT_PASSWORD:-}
EXERCISE_ID=${EXERCISE_ID:-1}
PASSWORD=${TEST_PASSWORD:-'Outbox!2026load'}
PREFIX=${ACCOUNT_PREFIX:-obx}
N_LATENCY=${N_LATENCY:-15}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-$HERE/results/outbox-duplicate-latency-$(date +%F)}
mkdir -p "$OUT" || exit 1
RAW="$OUT/raw.txt"
: > "$RAW"

log() { echo "$*" | tee -a "$RAW"; }

sql() {
  docker exec -i "$MYSQL_CONTAINER" mysql -u"$MYSQL_USER" ${MYSQL_PW:+-p"$MYSQL_PW"} -N -B "$MYSQL_DB" 2>/dev/null
}

log "# outbox 중복 흡수 · 지연 분포 — $(date -u +%FT%TZ)"
log "대상: $BASE"
case "$BASE" in
  *localhost*|*127.0.0.1*) log "🔴 로컬 대상 — 지연 절대값은 참고치일 뿐, AWS 재측정 전제" ;;
  *) log "✅ 원격 대상" ;;
esac
log "커밋: $(git -C "$HERE/.." rev-parse --short HEAD 2>/dev/null || echo NA)"
log

# ── 준비: 계정 하나 ──────────────────────────────────────────────────────
EMAIL="${PREFIX}1@loadtest.local"
signup_body=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/member/signup" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${PREFIX}1\",\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"sex\":\"MALE\",\"role\":\"USER\"}")
log "signup: $signup_body (409/400 이면 이미 존재 — 무시하고 로그인)"

login_resp=$(curl -s -X POST "$BASE/member/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
TOKEN=$(echo "$login_resp" | python -c 'import json,sys; print(json.load(sys.stdin).get("accessToken",""))' 2>/dev/null)
if [ -z "$TOKEN" ]; then
  log "🔴 로그인 실패 — 응답: $login_resp"
  exit 1
fi
log "로그인 성공, 토큰 획득"

curl -s -o /dev/null -X PATCH "$BASE/member/onboarding/$EMAIL" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"preferredUrl":"https://www.youtube.com/watch?v=outboxrig"}'

# 앞 판이 남긴 활성 세션 정리
active=$(curl -s "$BASE/sessions/active" -H "Authorization: Bearer $TOKEN")
old_sid=$(echo "$active" | python -c 'import json,sys
try:
  d=json.load(sys.stdin); print(d.get("sessionId") or "")
except Exception:
  print("")' 2>/dev/null)
if [ -n "$old_sid" ]; then
  curl -s -o /dev/null -X PATCH "$BASE/sessions/$old_sid/end" -H "Authorization: Bearer $TOKEN"
  log "앞 판의 활성 세션 $old_sid 정리"
fi

# start 직후 바로 end 하면 AI 쪽 세션 등록(StartAnalysis 비동기)과 경합해 1차 시도가
# 스스로 실패한다(로컬 실측: 0초 지연 시 outbox가 FAILED·report 0으로 끝났다가, 강제
# 재전송에서야 성공 — AI 등록이 그제서야 따라잡힌 것으로 보임). 이건 duplicate-absorption
# 질문과 별개의 레이스이므로, 정상 케이스를 만들기 위해 최소 대기를 둔다.
START_SETTLE_S=${START_SETTLE_S:-4}

start_session() {
  local resp status sid
  resp=$(curl -s -w '\n%{http_code}' -X POST "$BASE/exercises/sessions" \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
    -d "{\"exerciseId\":$EXERCISE_ID}")
  status=$(echo "$resp" | tail -1)
  body=$(echo "$resp" | sed '$d')
  if [ "$status" != "202" ] && [ "$status" != "200" ]; then
    log "🔴 세션 시작 실패: $status $body"
    return 1
  fi
  sid=$(echo "$body" | python -c 'import json,sys; print(json.load(sys.stdin).get("sessionId",""))' 2>/dev/null)
  sleep "$START_SETTLE_S"
  echo "$sid"
}

end_session() {
  local sid=$1
  curl -s -o /dev/null -w '%{http_code}' -X PATCH "$BASE/sessions/$sid/end" \
    -H "Authorization: Bearer $TOKEN"
}

wait_outbox_sent() {
  # aggregate_id(=session_id) 기준으로 최신 행이 SENT 될 때까지 0.2초 간격으로 폴링,
  # 소요를 초 단위 소수점 2자리로 반환한다(1초 폴링은 outbox 폴 주기와 같은 자릿수라
  # 값을 못 가른다 — 반드시 더 촘촘히 봐야 한다).
  local sid=$1 timeout_s=${2:-30}
  local t0 t1 st
  t0=$(python -c 'import time; print(time.time())')
  while true; do
    t1=$(python -c 'import time; print(time.time())')
    if python -c "exit(0 if ($t1 - $t0) < $timeout_s else 1)"; then :; else echo "-2"; return 1; fi
    st=$(echo "SELECT status FROM outbox_events WHERE aggregate_id=$sid ORDER BY id DESC LIMIT 1;" | sql)
    if [ "$st" = "SENT" ]; then
      python -c "print(f'{$t1 - $t0:.2f}')"
      return 0
    fi
    if [ "$st" = "FAILED" ]; then
      echo "-1"
      return 1
    fi
    sleep 0.2
  done
}

log
log "## [1] 중복 흡수"
log
sid=$(start_session)
if [ -z "$sid" ]; then log "🔴 세션 시작 실패, 중단"; exit 1; fi
log "세션 시작: sessionId=$sid"
end_status=$(end_session "$sid")
log "종료 요청: HTTP $end_status"
elapsed=$(wait_outbox_sent "$sid" 30)
log "1차 발행 SENT 까지: ${elapsed}s"

row_before=$(echo "SELECT id,status,retry_count FROM outbox_events WHERE aggregate_id=$sid ORDER BY id DESC LIMIT 1;" | sql)
outbox_id=$(echo "$row_before" | awk '{print $1}')
session_status_before=$(echo "SELECT status FROM exercise_sessions WHERE id=$sid;" | sql)
report_count_before=$(echo "SELECT COUNT(*) FROM reports WHERE session_id=$sid;" | sql)
log "1차 발행 후 — outbox: [$row_before] · session.status=$session_status_before · reports=$report_count_before"

log
log "→ 같은 행(outbox_id=$outbox_id)을 PENDING 으로 강제 되돌려 재전송을 흉내낸다"
echo "UPDATE outbox_events SET status='PENDING', locked_by=NULL, lock_expires_at=NULL, next_retry_at=NULL, sent_at=NULL WHERE id=$outbox_id;" | sql

elapsed2=$(wait_outbox_sent "$sid" 30)
log "2차(강제 재전송) 도달까지: ${elapsed2}s"

row_after=$(echo "SELECT id,status,retry_count FROM outbox_events WHERE aggregate_id=$sid ORDER BY id DESC LIMIT 1;" | sql)
session_status_after=$(echo "SELECT status FROM exercise_sessions WHERE id=$sid;" | sql)
report_count_after=$(echo "SELECT COUNT(*) FROM reports WHERE session_id=$sid;" | sql)
log "2차 발행 후 — outbox: [$row_after] · session.status=$session_status_after · reports=$report_count_after"

log
if [ "$report_count_before" = "$report_count_after" ] && [ "$session_status_after" = "COMPLETED" -o "$session_status_after" = "$session_status_before" ]; then
  log "✅ 판정: 리포트 중복 생성 없음(reports $report_count_before → $report_count_after), session.status 유지($session_status_after)"
else
  log "🔴 판정: 상태 변화 감지 — reports $report_count_before → $report_count_after, session.status $session_status_before → $session_status_after (자세히 볼 것)"
fi

log
log "백엔드 로그에서 2차 발행 관련 라인(마지막 60줄 중 관련 것):"
docker logs shadowfit-backend --tail 200 2>&1 | grep -iE "outbox|stopanalysis|session.*$sid|NOT_FOUND" | tail -20 | tee -a "$RAW"

log
log "## [2] 지연 분포 (N=$N_LATENCY, 참고치 — 로컬이면 절대값 신뢰 X)"
log
declare -a LAT
for i in $(seq 1 "$N_LATENCY"); do
  active=$(curl -s "$BASE/sessions/active" -H "Authorization: Bearer $TOKEN")
  osid=$(echo "$active" | python -c 'import json,sys
try:
  d=json.load(sys.stdin); print(d.get("sessionId") or "")
except Exception:
  print("")' 2>/dev/null)
  if [ -n "$osid" ]; then curl -s -o /dev/null -X PATCH "$BASE/sessions/$osid/end" -H "Authorization: Bearer $TOKEN"; sleep 1; fi

  sid=$(start_session)
  if [ -z "$sid" ]; then log "  [$i] 세션 시작 실패, 건너뜀"; continue; fi
  t0=$(date +%s.%N)
  end_session "$sid" > /dev/null
  e=$(wait_outbox_sent "$sid" 20)
  t1=$(date +%s.%N)
  wall=$(python -c "print(f'{$t1-$t0:.2f}')" 2>/dev/null)
  log "  [$i] sessionId=$sid outbox_wait=${e}s wall=${wall}s"
  LAT+=("$e")
done

log
log "표본: ${LAT[*]}"
python - "${LAT[@]}" <<'PYEOF' | tee -a "$RAW"
import sys
vals = sorted(float(x) for x in sys.argv[1:] if float(x) >= 0)
if not vals:
    print("유효 표본 없음")
    sys.exit(0)
n = len(vals)
def pct(p):
    idx = min(n - 1, int(round(p * (n - 1))))
    return vals[idx]
print(f"n={n} min={vals[0]:.2f}s median={pct(0.5):.2f}s p95={pct(0.95):.2f}s p99={pct(0.99):.2f}s max={vals[-1]:.2f}s")
print("🔴 N이 작아 p99는 사실상 max에 가깝다 — 참고치로만 쓸 것")
PYEOF

log
log "완료. 원자료: $RAW"
