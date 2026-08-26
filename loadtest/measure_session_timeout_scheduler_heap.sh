#!/bin/bash
# #207 — 타임아웃 스케줄러가 IN_PROGRESS 세션 수에 따라 힙을 얼마나 먹나. 로컬 단독 인스턴스판.
#
# 원판: loadtest/results/session-spread-2026-08-13/timeout_scheduler_memory.sh (AWS, 원격 SSH).
# 이 스크립트는 그 방법론을 로컬 격리 컨테이너 쌍(별도 docker compose 프로젝트)에 맞춘 것이다 —
# 공유 중인 dev 스택(shadowfit-mysql/backend)을 건드리지 않기 위해 포트·컨테이너명을 분리한다.
#
# ── R9-207(2026-08-18)이 «지표가 질문에 못 미친다»로 끝난 이유와 이 판이 고친 것 ─────────────
#   원판은 jvm_gc_memory_allocated_bytes_total(GC가 떠야만 갱신되는 누적 할당 카운터)을 썼는데,
#   62초 창 안에 자연 GC가 안 뜬 팔은 전부 0으로 찍혀 "안 먹었다"와 "못 봤다"가 구분 안 됐다.
#   이 판은 jvm_memory_used_bytes{area="heap"}를 창 동안 촘촘히 샘플링해 그 구간의 최댓값
#   (스케줄러가 N개를 전부 힙에 올린 직후, GC가 걷어가기 전의 피크)과 최솟값(GC 직후 floor)을
#   같이 낸다 — 최댓값 쪽이 "적재량"에 훨씬 깨끗하게 반응한다(§ 아래 실측 참고).
#
# ── 🔴 이 스크립트 자체에 있던 실측 버그(2026-08-27 발견) ────────────────────────────────────
#   Prometheus 텍스트 포맷의 일부 라인은 라벨 값에 공백이 들어간다(예: id="G1 Eden Space").
#   awk 기본 IFS(공백)로 자르면 그 공백이 필드를 쪼개 $2가 값이 아니라 라벨의 한 단어("Eden")가
#   된다. 첫 실행은 이 버그 때문에 모든 팔이 "힙 0MB"로 나왔다 — $NF(항상 마지막 필드)로 고쳤다.
#   jvm_gc_pause_seconds_count도 같은 함정이 있다(action="end of minor GC" 등) — 같이 고쳤다.
#
# 사용: bash measure_session_timeout_scheduler_heap.sh
#   전제: docker-compose.yml 이미지가 로컬에 빌드돼 있어야 한다(init-shadowfit-backend:latest).
#   별도 프로젝트(session207)로 mysql·backend만 격리 기동한다 — 공유 dev 스택과 안 겹친다.
set -uo pipefail
cd "$(dirname "$0")"

PROJECT=session207-heap
MYSQL_PORT_LOCAL=${MYSQL_PORT_LOCAL:-3307}
BACKEND_MGMT_PORT=${BACKEND_MGMT_PORT:-9091}
LEVELS=(0 10000 0 10000 0 10000)   # 팔린드롬 — 시간 추세와 N 을 분리한다
TICKS=${TICKS:-1}
FAKE_LO=3000000
SAMPLE_INTERVAL=3
TICK_WAIT=65

PW=${MYSQL_ROOT_PASSWORD:-1234}
DB=shadowfit
OUT="$(dirname "$0")/results/session-timeout-heap-local-$(date +%Y-%m-%d 2>/dev/null || echo local)"
mkdir -p "$OUT"
LOG="$OUT/result.tsv"

MYSQL_C="${PROJECT}-mysql"
BACKEND_C="${PROJECT}-backend"

mysql_q() { docker exec -i "$MYSQL_C" mysql -uroot -p"$PW" -N "$DB" 2>/dev/null; }

prom() {  # $1 = metric name (grep 패턴). 값은 항상 마지막 필드($NF) — 라벨 공백 함정 참고.
  curl -s "localhost:$BACKEND_MGMT_PORT/actuator/prometheus" 2>/dev/null \
    | grep "^$1" | awk '{s+=$NF} END{printf "%.0f\n", s}'
}

fake_count() { echo "SELECT COUNT(*) FROM exercise_sessions WHERE id >= $FAKE_LO AND status='IN_PROGRESS';" | mysql_q | tr -d '[:space:]'; }

set_arm() {
  local want=$1 have
  echo "DELETE FROM exercise_sessions WHERE id >= $FAKE_LO;" | mysql_q
  if [ "$want" -gt 0 ]; then
    SEED=$(echo "SELECT id FROM exercise_sessions ORDER BY id LIMIT 1;" | mysql_q | tr -d '[:space:]')
    [ -n "$SEED" ] || { echo "  🔴 씨앗 세션이 없다 — 최소 1개 필요" >&2; return 1; }
    echo "SET GLOBAL cte_max_recursion_depth=200000;" | mysql_q
    echo "INSERT INTO exercise_sessions
            (id, member_id, exercise_id, start_time, last_active_at, status, version, created_at)
          SELECT $FAKE_LO + n, s.member_id, s.exercise_id, NOW(), NOW(), 'IN_PROGRESS', 0, NOW()
            FROM (WITH RECURSIVE seq(n) AS (SELECT 0 UNION ALL SELECT n+1 FROM seq WHERE n+1 < $want)
                  SELECT n FROM seq) t
            JOIN exercise_sessions s ON s.id = $SEED;" | mysql_q
  fi
  have=$(fake_count)
  [ "${have:-0}" = "$want" ] || { echo "  🔴 팔이 안 섰다 — 원함 $want, 실제 $have" >&2; return 1; }
  echo "  팔 확인: IN_PROGRESS $have 개"
}

refresh_arm() {
  echo "UPDATE exercise_sessions SET start_time=NOW(), last_active_at=NOW()
        WHERE id >= $FAKE_LO AND status='IN_PROGRESS';" | mysql_q
}

echo "=== 사전 확인 ==="
v=$(prom "jvm_gc_memory_allocated_bytes_total")
[ -n "$v" ] && [ "$v" != "0" ] || { echo "🔴 JVM 지표를 못 읽었다 (받은 값 '$v') — $BACKEND_C 가 안 떴거나 관리 포트가 막혔다"; exit 1; }
echo "  actuator 확인 (할당 누적 $v)"

printf "arm_idx\tin_progress\ttick\theap_min_mb\theap_max_mb\tgc_pause_delta\tsecs\n" > "$LOG"

i=0
for want in "${LEVELS[@]}"; do
  i=$(( i + 1 ))
  echo
  echo "──────── [$i/${#LEVELS[@]}] IN_PROGRESS $want 개 ────────"
  set_arm "$want" || { echo "  스킵"; continue; }

  t=0
  while [ $t -lt "$TICKS" ]; do
    t=$(( t + 1 ))
    refresh_arm
    g0=$(prom "jvm_gc_pause_seconds_count")
    s0=$(date +%s)
    heap_min=""; heap_max=0; elapsed=0
    while [ $elapsed -lt $TICK_WAIT ]; do
      h=$(prom "jvm_memory_used_bytes{area=\"heap\"")
      if [ -n "$h" ] && [ "$h" -gt 0 ] 2>/dev/null; then
        if [ -z "$heap_min" ] || [ "$h" -lt "$heap_min" ]; then heap_min=$h; fi
        if [ "$h" -gt "$heap_max" ]; then heap_max=$h; fi
      fi
      sleep $SAMPLE_INTERVAL
      elapsed=$(( elapsed + SAMPLE_INTERVAL ))
    done
    g1=$(prom "jvm_gc_pause_seconds_count")
    s1=$(date +%s)
    heap_min_mb=$(( ${heap_min:-0} / 1024 / 1024 ))
    heap_max_mb=$(( ${heap_max:-0} / 1024 / 1024 ))
    gc_delta=$(( g1 - g0 ))
    printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\n" "$i" "$want" "$t" "$heap_min_mb" "$heap_max_mb" "$gc_delta" "$(( s1 - s0 ))" >> "$LOG"
    echo "  틱 $t: 힙 최저 ${heap_min_mb}MB · 최고 ${heap_max_mb}MB · GC ${gc_delta}회 · ${TICK_WAIT}초"
  done

  have=$(fake_count)
  [ "${have:-0}" = "$want" ] || echo "  ⚠️ 팔 도중 IN_PROGRESS 가 $want → $have 로 줄었다" >&2
done

echo "DELETE FROM exercise_sessions WHERE id >= $FAKE_LO;" | mysql_q
echo
echo "=== 결과 ($LOG) ==="
cat "$LOG"
