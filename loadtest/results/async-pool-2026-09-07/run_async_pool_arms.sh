#!/usr/bin/env bash
# async-pool-backpressure-experiment.md 재측정 (2026-09-07) — 팔 A(AI 정상) vs 팔 B(AI 정지), 3판씩 순서 반전.
#
# 원본(2026-08-28, measure/async-pool-backpressure-r2 브랜치, 병합 안 됨)에서 두 가지를 고쳤다:
#   1. fire_burst의 로깅 경합 버그 — 여러 백그라운드 서브셸이 같은 파일에 동시에 >>로 append해서
#      응답이 유실됐다(판당 15개 중 1~3개만 로그에 잡힘, DB 전수 확인으로 서버는 다 받은 게 확인됨,
#      즉 rig만의 결함). 요청마다 별도 파일에 쓰고 나중에 합치는 방식으로 고쳤다.
#   2. 큐 길이 폴러 추가 — #667(2026-09-04, ca1ae4f2)가 applicationTaskExecutor를 non-lazy 빈으로
#      고쳐서 Micrometer가 이제 이 executor를 잡는다(확인된 지표명, 2026-09-07 curl 실측):
#        executor_queued_tasks{name="applicationTaskExecutor"}   — 큐 길이(H1의 핵심)
#        executor_active_threads{name="applicationTaskExecutor"} — 활성 워커 수
#        executor_pool_core_threads / executor_pool_max_threads  — 참고용(설정값 대조)
set -uo pipefail

BASE=http://localhost:8080
MGMT=http://localhost:9090
TOKEN_FILE=./admin_token.txt
OUT=$(cd "$(dirname "$0")" && pwd)
N_SESSIONS=${N_SESSIONS:-15}
POLL_INTERVAL=${POLL_INTERVAL:-0.5}
POLL_MAX=${POLL_MAX:-40}   # 0.5s * 40 = 20초 상한

TOKEN=$(cat "$TOKEN_FILE")
mapfile -t POOL_TOKENS < "$OUT/pool_tokens.txt"
echo "  풀 계정 ${#POOL_TOKENS[@]}개 로드"

reset_pool_sessions() {
  docker exec shadowfit-mysql mysql -uroot -p1234 shadowfit -e \
    "UPDATE exercise_sessions SET status='CANCELLED' WHERE member_id IN (SELECT id FROM users WHERE email LIKE 'asyncpool%') AND status='IN_PROGRESS';" 2>/dev/null
}

reset_check() {
  local cb
  cb=$(curl -s "$MGMT/actuator/circuitbreakers" -H "Authorization: Bearer $TOKEN")
  echo "  [리셋확인] circuitbreakers: $cb"
  echo "$cb" | grep -q '"state":"OPEN"' && { echo "  🔴 서킷이 OPEN인 채로 시작 — half-open 대기 필요"; return 1; }
  return 0
}

queue_snapshot() {
  # 큐 샘플러 — /actuator/prometheus를 직접 폴링(스크레이프 간격이 아니라)해야 톱니가 보인다.
  curl -s "$MGMT/actuator/prometheus" 2>/dev/null | awk '
    /^executor_queued_tasks\{name="applicationTaskExecutor"\}/ {q=$2}
    /^executor_active_threads\{name="applicationTaskExecutor"\}/ {a=$2}
    /^executor_pool_size_threads\{name="applicationTaskExecutor"\}/ {p=$2}
    END {printf "q=%s active=%s poolsize=%s", q, a, p}
  '
}

# 로깅 경합 버그 수정 — 요청마다 별도 파일에 쓰고 나중에 합친다(동시 append 경쟁 제거).
fire_burst() {
  local label=$1
  local n=$2
  local ids_file="$OUT/${label}_session_ids.txt"
  local raw_dir="$OUT/${label}_raw"
  mkdir -p "$raw_dir"
  rm -f "$raw_dir"/*
  local npool=${#POOL_TOKENS[@]}
  for i in $(seq 0 $((n-1))); do
    local tok="${POOL_TOKENS[$((i % npool))]}"
    (curl -s -X POST "$BASE/exercises/sessions" \
      -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' \
      -d '{"exerciseId":1}' > "$raw_dir/$i.json") &
  done
  wait
  cat "$raw_dir"/*.json > "$ids_file.raw"
  grep -oE '"sessionId":[0-9]+' "$ids_file.raw" | grep -oE '[0-9]+' > "$ids_file"
  local err409
  err409=$(grep -c '"status":409' "$ids_file.raw")
  [ "$err409" -gt 0 ] && echo "  ⚠️ [$label] 409(이미 진행중) $err409건 — 풀 재사용 리셋이 덜 됐을 수 있다"
  rm -f "$ids_file.raw"
  rm -rf "$raw_dir"
  echo "  [$label] 세션 $(wc -l < "$ids_file") 개 생성 (요청 $n, 파일별 분리로 유실 없음)"
}

poll_status() {
  local label=$1
  local ids_file="$OUT/${label}_session_ids.txt"
  local ids
  ids=$(paste -sd, "$ids_file")
  [ -z "$ids" ] && { echo "  🔴 [$label] 세션 id 없음"; return; }

  local log="$OUT/${label}_poll.log"
  : > "$log"
  local t0
  t0=$(date +%s.%N 2>/dev/null || date +%s)
  for tick in $(seq 1 "$POLL_MAX"); do
    local now cbstate row q
    now=$(date +%s.%N 2>/dev/null || date +%s)
    cbstate=$(curl -s "$MGMT/actuator/circuitbreakers" -H "Authorization: Bearer $TOKEN" | grep -oE '"state":"[A-Z_]+"' | sort | uniq -c | tr '\n' ' ')
    q=$(queue_snapshot)
    row=$(docker exec shadowfit-mysql mysql -uroot -p1234 shadowfit -N -e \
      "SELECT status, COUNT(*) FROM exercise_sessions WHERE id IN ($ids) GROUP BY status;" 2>/dev/null | tr '\n' ';')
    echo "t=$(awk -v a="$now" -v b="$t0" 'BEGIN{printf "%.1f", a-b}') cb[$cbstate] $q status[$row]" >> "$log"
    echo "$row" | grep -q "FAILED" && [ "$tick" -gt 4 ] && break
    sleep "$POLL_INTERVAL"
  done
  echo "  [$label] 폴링 로그 → $log"
}

echo "=== 준비 — 빈 상태(부하 없음) 큐 지표 확인 ==="
echo "  $(queue_snapshot)"
echo

ARMS=${ARMS:-"A B B A A B"}   # 판1: A B · 판2: B A · 판3: A B
rep=0
for arm in $ARMS; do
  rep=$((rep+1))
  echo "=== 판 $rep — 팔 $arm ==="
  reset_pool_sessions
  reset_check || { echo "  대기 12초 후 재확인"; sleep 12; reset_check || echo "  🔴 여전히 OPEN, 계속 진행(기록에 남긴다)"; }

  if [ "$arm" = "B" ]; then
    echo "  docker pause shadowfit-ai"
    docker pause shadowfit-ai >/dev/null
  fi

  fire_burst "rep${rep}_${arm}" "$N_SESSIONS"
  poll_status "rep${rep}_${arm}"

  if [ "$arm" = "B" ]; then
    echo "  docker unpause shadowfit-ai"
    docker unpause shadowfit-ai >/dev/null
    sleep 3
  fi
  echo
done

echo "=== 완료 ==="
