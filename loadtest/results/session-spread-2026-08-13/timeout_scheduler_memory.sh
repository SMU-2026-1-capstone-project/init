#!/bin/bash
# 從 — 「타임아웃 스케줄러가 세션 수에 따라 얼마를 먹나」 (#207)
#
# ─────────────────────────────────────────────────────────────────────────
# 무엇이 열려 있나
#
#   SessionTimeoutScheduler.java:55  @Scheduled(fixedDelay = 1m)
#   SessionTimeoutScheduler.java:72  sessionRepository.findByStatus(Status.IN_PROGRESS)
#
# **IN_PROGRESS 세션을 전부 엔티티로 힙에 올린다.** 매 분. 페이지도 스트림도 아니다.
# 즉 «세션이 안 끝날수록 무거워지는» 방향인데, 그 기울기를 **한 번도 안 쟀다**(#207).
#
# 이게 왜 용량 문제인가: 이 앱은 회원당 활성 세션이 1개라 IN_PROGRESS 수 = 동시에 운동
# 중인 사람 수다(P5 와 같은 축). 그리고 **끝나지 않은 세션은 타임아웃 전까지 남는다** —
# 앱을 껐다 켜거나 클라이언트가 사라진 몫이 여기 쌓인다.
# ─────────────────────────────────────────────────────────────────────────
#
# 재는 것: **틱 하나가 할당하는 바이트.** 힙 사용량(used)은 GC 타이밍에 흔들려서 «얼마나
# 먹었나» 를 못 보여준다. `jvm_gc_memory_allocated_bytes_total` 은 **누적 할당 카운터**라
# 틱 사이 델타가 그 틱이 만든 쓰레기의 크기다.
#
# 🔴 **부하가 없을 때만 성립한다.** 부하 중이면 요청 경로 할당이 스케줄러 몫을 압도한다.
#    그래서 이 판은 라운드 **맨 마지막**, 다른 스윕이 전부 끝난 뒤에 돈다.
#
# 팔 = IN_PROGRESS 세션 수: **0(대조) · 1,000 · 10,000 · 50,000**
#
# 🔴 순서는 **팰린드롬**으로 돈다: 0 1k 10k 50k 50k 10k 1k 0
#    단조 증가로만 돌리면 «세션 수» 와 «시간 추세»(JIT 예열·힙 성장)가 같은 축에 겹쳐
#    부호조차 못 정한다 — 이 저장소가 두 번 데인 자리다. 앞뒤 대칭이면 추세가 상쇄된다.
#
# 🔴 **타임아웃이 팔을 무너뜨린다.** 스케줄러는 오래된 세션을 FAILED 로 걷어가고, 그때
#    AI 로 gRPC 통보까지 나간다(notifyAi=true, 이슈 #98). 5만 개가 한꺼번에 걷히면 그건
#    측정이 아니라 사고다. 그래서 **팔마다 타임스탬프를 현재로 갱신**하고, 팔이 끝난 뒤
#    개수가 그대로인지 **단언**한다.
#
# 사용: sessions_sweep.sh 와 같은 환경변수 (OUT 은 같은 디렉터리)

set -uo pipefail
cd "$(dirname "$0")"

LEVELS=(0 1000 10000 50000 50000 10000 1000 0)
TICKS=${TICKS:-2}                 # 팔당 관측할 스케줄러 틱 수(1틱 = 1분)
FAKE_LO=${FAKE_LO:-3000000}       # 이 실험 전용 id 대역. 부하 대역(901~950)과 안 겹친다

OUT="${OUT:?OUT 미설정}"
LOG="$OUT/timeout_mem.tsv"

if [ "${PLAN_ONLY:-0}" = "1" ]; then
  echo "=== 從 타임아웃 스케줄러 메모리 — 팔 배치 (팰린드롬) ==="
  i=0; for l in "${LEVELS[@]}"; do i=$(( i + 1 )); printf "  [%s] IN_PROGRESS %s개\n" "$i" "$l"; done
  echo "  팔당 $TICKS 틱 관측 · 예상 소요 $(( ${#LEVELS[@]} * TICKS + 3 ))분"
  exit 0
fi

source ./../commit-count-2026-08-09/_rig.sh

LOCK="$OUT/.timeout.lock"
mkdir -p "$OUT"
mkdir "$LOCK" 2>/dev/null || { echo "🔴 이미 돌고 있다: $LOCK" >&2; exit 1; }

learn_all_hosts
echo "=== 사전 확인 ==="
assert_mysql_reachable

# 씨앗 행 하나가 있어야 복제로 만든다(FK: member_id · exercise_id)
SEED=$(mysql_q "SELECT id FROM exercise_sessions ORDER BY id LIMIT 1;" | tr -d '[:space:]')
[ -n "$SEED" ] || die "exercise_sessions 가 비어 있다 — 복제할 씨앗이 없다"
echo "  씨앗 세션: $SEED"

# 🔴 부하가 도는 중이면 이 판은 성립하지 않는다. ghz 가 살아 있으면 멈춘다.
if rsh "$LOADER_PUB" "pgrep -x ghz >/dev/null 2>&1"; then
  die "부하기에서 ghz 가 돌고 있다 — 이 판은 «부하 없는 창» 에서만 성립한다(요청 경로 할당이 스케줄러 몫을 압도한다)"
fi
echo "  부하 없음 확인"

prom() {  # $1 = metric 이름 prefix → 값 합
  rsh "$APP_PUB" "curl -s localhost:9090/actuator/prometheus 2>/dev/null | grep '^$1' | awk '{s+=\$2} END{print s+0}'" \
    | tr -d '[:space:]'
}

fake_count() { mysql_q "SELECT COUNT(*) FROM exercise_sessions WHERE id >= $FAKE_LO AND status='IN_PROGRESS';" | tr -d '[:space:]'; }

set_arm() {  # $1 = 원하는 IN_PROGRESS 개수
  local want=$1 have
  mysql_q "DELETE FROM exercise_sessions WHERE id >= $FAKE_LO;" >/dev/null
  if [ "$want" -gt 0 ]; then
    # 재귀 CTE 로 want 개를 한 번에 만든다. 값은 전부 씨앗 복제 + id/시각만 다르다.
    mysql_q "INSERT INTO exercise_sessions
               (id, member_id, exercise_id, start_time, last_active_at, status, version, created_at)
             SELECT $FAKE_LO + n, s.member_id, s.exercise_id, NOW(), NOW(), 'IN_PROGRESS', 0, NOW()
               FROM (WITH RECURSIVE seq(n) AS (SELECT 0 UNION ALL SELECT n+1 FROM seq WHERE n+1 < $want)
                     SELECT n FROM seq) t
               JOIN exercise_sessions s ON s.id = $SEED;" >/dev/null
  fi
  have=$(fake_count)
  # 「만들었다」와 「만들어졌다」는 다르다. 개수가 안 맞으면 팔이 틀린 것이다.
  [ "${have:-0}" = "$want" ] || die "팔이 안 섰다 — IN_PROGRESS 를 $want 개로 원했는데 $have 개다"
  echo "  팔 확인: IN_PROGRESS $have 개"
}

refresh_arm() {  # 타임아웃으로 걷혀 나가지 않게 시각을 현재로 민다
  mysql_q "UPDATE exercise_sessions SET start_time=NOW(), last_active_at=NOW()
           WHERE id >= $FAKE_LO AND status='IN_PROGRESS';" >/dev/null
}

[ -f "$LOG" ] || printf "arm_idx\tin_progress\ttick\talloc_bytes\theap_used\tgc_pause_count\tsecs\n" > "$LOG"

cleanup() {
  echo "=== 정리 — 가짜 세션 제거 ==="
  mysql_q "DELETE FROM exercise_sessions WHERE id >= $FAKE_LO;" >/dev/null
  local left; left=$(mysql_q "SELECT COUNT(*) FROM exercise_sessions WHERE id >= $FAKE_LO;" | tr -d '[:space:]')
  echo "  남은 가짜 세션: ${left:-?}"
  rmdir "$LOCK" 2>/dev/null
}
trap cleanup EXIT

i=0
for want in "${LEVELS[@]}"; do
  i=$(( i + 1 ))
  echo
  echo "──────── [$i/${#LEVELS[@]}] IN_PROGRESS $want 개 ────────"
  set_arm "$want"

  t=0
  while [ $t -lt "$TICKS" ]; do
    t=$(( t + 1 ))
    refresh_arm
    a0=$(prom "jvm_gc_memory_allocated_bytes_total"); h0=$(prom "jvm_memory_used_bytes{area=\"heap\"")
    g0=$(prom "jvm_gc_pause_seconds_count"); s0=$(date +%s)
    sleep 62                                    # 스케줄러 주기 1분 + 여유
    a1=$(prom "jvm_gc_memory_allocated_bytes_total"); h1=$(prom "jvm_memory_used_bytes{area=\"heap\"")
    g1=$(prom "jvm_gc_pause_seconds_count"); s1=$(date +%s)
    printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
      "$i" "$want" "$t" "$(( ${a1%.*} - ${a0%.*} ))" "${h1%.*}" \
      "$(( ${g1%.*} - ${g0%.*} ))" "$(( s1 - s0 ))" >> "$LOG"
    echo "  틱 $t: 할당 $(( ( ${a1%.*} - ${a0%.*} ) / 1024 / 1024 ))MB · 힙 $(( ${h1%.*} / 1024 / 1024 ))MB · GC $(( ${g1%.*} - ${g0%.*} ))회"
  done

  # 🔴 팔이 끝날 때까지 개수가 유지됐는지. 줄었으면 타임아웃이 걷어간 것이고 그 팔은 못 쓴다.
  have=$(fake_count)
  [ "${have:-0}" = "$want" ] \
    || echo "  ⚠️ 팔 도중 IN_PROGRESS 가 $want → $have 로 줄었다 — 타임아웃이 걷어갔다. 이 팔은 의심할 것" >&2
done

echo
echo "=== 결과 ($LOG) ==="
cat "$LOG"
