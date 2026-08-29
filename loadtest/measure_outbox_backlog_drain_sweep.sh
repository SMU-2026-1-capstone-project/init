#!/usr/bin/env bash
# outbox 완결 처리량 — 3차: 레벨 사이 재고를 0으로 비우고 시작하는 스윕(#573).
#
# 1차(outbox-throughput-2026-08-26)와 2차(outbox-throughput-fine-2026-08-26)의 결론:
# "천장은 RATE 2~4 사이"는 틀렸다 — RATE=2.0에서도 재고(sent_at IS NULL)가 계속 쌓였고,
# 레벨 사이에 재고를 안 비워서 "RATE 자체의 순수 효과"와 "이미 쌓인 재고 효과"가 섞여
# 있었다(2차 §3·§7). 이 3차는 그 결함을 고친다 — 레벨을 시작하기 전에 재고가 0이 될 때까지
# 기다린다(DRAIN_TIMEOUT 안에 안 비면 FAIL 로 남기고 그 상태로 진행 — 조용히 건너뛰지 않는다,
# run_all.sh 와 같은 원칙).
#
# 🔴 정직 고지: 1·2차가 실제로 부하를 어떻게 걸었는지(어떤 도구·동시성)는 저장소에 안 남아
#    있다 — 파일명이 `k6_*`였지만 이 박스엔 k6 바이너리가 없다(2026-08-27 확인). 즉 1·2차는
#    저장소 밖에서 즉석으로 짠 무언가였고 재현 불가능하다. 이 스크립트는 2차 README의 서술
#    ("세션 시작→종료 요청을 RATE 로 걸었다")만 보고 **새로 재구성**한 것이다 — 계정 1개로
#    시작 직후 바로 종료를 반복해서 쏘는 open-loop 부하(완료를 기다리지 않고 다음 요청을
#    간격대로 계속 쏜다). 1·2차와 동시성 모델이 다를 수 있고, 그러면 conflict% 절대값은
#    1·2차와 직접 비교하면 안 된다 — 이 라운드 안에서의 RATE 간 상대 비교와 "재고가 0으로
#    돌아오는가"만 신뢰할 것.
set -uo pipefail

BASE=${BASE:-http://localhost:8080}
MYSQL_CONTAINER=${MYSQL_CONTAINER:-shadowfit-mysql}
MYSQL_DB=${MYSQL_DB:-shadowfit}
MYSQL_USER=${MYSQL_USER:-root}
MYSQL_PW=${MYSQL_ROOT_PASSWORD:-}
EXERCISE_ID=${EXERCISE_ID:-1}
PASSWORD=${TEST_PASSWORD:-'Outbox!2026load'}
PREFIX=${ACCOUNT_PREFIX:-obxd3}
RATES=${RATES:-"0.5 1.0 1.5"}
LEVEL_DUR=${LEVEL_DUR:-90}       # 초 — 2차와 같은 창
DRAIN_TIMEOUT=${DRAIN_TIMEOUT:-600}  # 초 — 재고 0 대기 상한(10분), 넘으면 FAIL 로 남기고 진행
STATS_INTERVAL=${STATS_INTERVAL:-10} # 초 — docker stats 샘플 간격(2차와 동일)
MAX_INFLIGHT=${MAX_INFLIGHT:-50}     # 동시 백그라운드 curl 상한(폭주 방지)

HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-$HERE/results/outbox-throughput-drain-gated-$(date +%F)}
mkdir -p "$OUT" || exit 1
SUMMARY="$OUT/sweep.tsv"
BACKLOG="$OUT/backlog.tsv"
echo -e "level\trate\ttotal_requests\tconflict_count\tconflict_pct\tdrain_status" > "$SUMMARY"
echo -e "level\trate\tpending_count\toldest_wait_ms\tdrain_waited_s" > "$BACKLOG"

log() { echo "$*"; }

sql() {
  docker exec -i "$MYSQL_CONTAINER" mysql -u"$MYSQL_USER" ${MYSQL_PW:+-p"$MYSQL_PW"} -N -B "$MYSQL_DB" 2>/dev/null
}

log "# outbox 3차 — 레벨 드레인 게이트 스윕 — $(date -u +%FT%TZ)"
log "대상: $BASE, RATES=$RATES, LEVEL_DUR=${LEVEL_DUR}s, DRAIN_TIMEOUT=${DRAIN_TIMEOUT}s"
log "커밋: $(git -C "$HERE/.." rev-parse --short HEAD 2>/dev/null || echo NA)"
log

# ── 계정 준비 ────────────────────────────────────────────────────────────
EMAIL="${PREFIX}@loadtest.local"
curl -s -o /dev/null -X POST "$BASE/member/signup" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${PREFIX}\",\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"sex\":\"MALE\",\"role\":\"USER\"}"

login_resp=$(curl -s -X POST "$BASE/member/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
TOKEN=$(echo "$login_resp" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("accessToken",""))' 2>/dev/null)
if [ -z "$TOKEN" ]; then
  log "🔴 로그인 실패 — 응답: $login_resp"
  exit 1
fi
log "로그인 성공"

curl -s -o /dev/null -X PATCH "$BASE/member/onboarding/$EMAIL" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"preferredUrl":"https://www.youtube.com/watch?v=outboxrig3"}'

# 앞 판이 남긴 활성 세션 정리
active=$(curl -s "$BASE/sessions/active" -H "Authorization: Bearer $TOKEN")
old_sid=$(echo "$active" | python3 -c 'import json,sys
try:
  d=json.load(sys.stdin); print(d.get("sessionId") or "")
except Exception:
  print("")' 2>/dev/null)
if [ -n "$old_sid" ]; then
  curl -s -o /dev/null -X PATCH "$BASE/sessions/$old_sid/end" -H "Authorization: Bearer $TOKEN"
  log "앞 판의 활성 세션 $old_sid 정리"
fi

# ── 재고(backlog) 측정 ───────────────────────────────────────────────────
measure_backlog() {
  local pc owm
  pc=$(echo "SELECT COUNT(*) FROM outbox_events WHERE sent_at IS NULL;" | sql)
  owm=$(echo "SELECT COALESCE(TIMESTAMPDIFF(MICROSECOND, MIN(created_at), NOW())/1000, 0) FROM outbox_events WHERE sent_at IS NULL;" | sql)
  echo "${pc:-NA}	${owm:-NA}"
}

wait_drain() {
  local timeout=$1 waited=0 pc
  while true; do
    pc=$(echo "SELECT COUNT(*) FROM outbox_events WHERE sent_at IS NULL;" | sql)
    if [ "$pc" = "0" ]; then
      echo "OK	$waited"
      return 0
    fi
    if [ "$waited" -ge "$timeout" ]; then
      echo "TIMEOUT	$waited"
      return 1
    fi
    sleep 5
    waited=$((waited+5))
  done
}

# ── 부하 한 틱 ───────────────────────────────────────────────────────────
one_tick() {
  local raw=$1
  local resp status body sid
  resp=$(curl -s -w '\n%{http_code}' -X POST "$BASE/exercises/sessions" \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
    -d "{\"exerciseId\":$EXERCISE_ID}")
  status=$(echo "$resp" | tail -1)
  body=$(echo "$resp" | sed '$d')
  echo "$status" >> "$raw"
  if [ "$status" = "202" ] || [ "$status" = "200" ]; then
    sid=$(echo "$body" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("sessionId",""))' 2>/dev/null)
    if [ -n "$sid" ]; then
      curl -s -o /dev/null -X PATCH "$BASE/sessions/$sid/end" -H "Authorization: Bearer $TOKEN"
    fi
  fi
}

sample_stats() {
  local f=$1 dur=$2 interval=$3
  : > "$f"
  local end_ts=$(( $(date +%s) + dur ))
  while [ "$(date +%s)" -lt "$end_ts" ]; do
    echo "---$(date -u +%FT%TZ)---" >> "$f"
    docker stats --no-stream --format '{{.Name}}	{{.CPUPerc}}	{{.MemUsage}}' >> "$f" 2>/dev/null
    sleep "$interval"
  done
}

run_level() {
  local rate=$1
  local label="r$(echo "$rate" | tr -d '.')"
  local raw="$OUT/raw_${label}.log"
  local statsf="$OUT/stats_${label}.log"
  : > "$raw"

  log
  log "==== 레벨 $label — RATE=$rate DUR=${LEVEL_DUR}s ===="

  sample_stats "$statsf" "$LEVEL_DUR" "$STATS_INTERVAL" &
  local stats_pid=$!

  local interval
  interval=$(python3 -c "print(1.0/$rate)")
  local end_ts=$(( $(date +%s) + LEVEL_DUR ))
  while [ "$(date +%s)" -lt "$end_ts" ]; do
    while [ "$(jobs -rp | wc -l)" -ge "$MAX_INFLIGHT" ]; do sleep 0.1; done
    one_tick "$raw" &
    sleep "$interval"
  done
  sleep 5   # 유예 — 막차 요청들 마무리
  kill "$stats_pid" 2>/dev/null
  wait "$stats_pid" 2>/dev/null

  local total conflict pct
  total=$(wc -l < "$raw" | tr -d ' ')
  conflict=$(grep -c '^409$' "$raw" || true)
  if [ "$total" -gt 0 ]; then
    pct=$(python3 -c "print(round(100.0*$conflict/$total, 1))")
  else
    pct="NA"
  fi

  local bl pc owm
  bl=$(measure_backlog)
  pc=$(echo "$bl" | cut -f1)
  owm=$(echo "$bl" | cut -f2)

  log "  total=$total conflict=$conflict(${pct}%) pending=$pc oldest_wait_ms=$owm"

  log "  → 다음 레벨 전 재고 드레인 대기(타임아웃 ${DRAIN_TIMEOUT}s)"
  local drain_result drain_status drain_waited
  drain_result=$(wait_drain "$DRAIN_TIMEOUT")
  drain_status=$(echo "$drain_result" | cut -f1)
  drain_waited=$(echo "$drain_result" | cut -f2)
  log "  드레인: $drain_status (${drain_waited}s)"

  echo -e "${label}\t${rate}\t${total}\t${conflict}\t${pct}\t${drain_status}" >> "$SUMMARY"
  echo -e "${label}\t${rate}\t${pc}\t${owm}\t${drain_waited}" >> "$BACKLOG"
}

for r in $RATES; do
  run_level "$r"
done

log
log "완료. 원자료: $OUT"
log "🔴 인스턴스는 자동 종료하지 않는다 — 결과 확인 후 사람이 판단."
