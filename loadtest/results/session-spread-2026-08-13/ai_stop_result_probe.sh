#!/bin/bash
# 從 — 「AI 가 상태를 잃은 세션을 종료하면 무슨 일이 벌어지나」 (SLO 판정선 #9)
#
# ─────────────────────────────────────────────────────────────────────────
# 무엇이 열려 있나
#
# `slo-baseline.md` §3 의 9번 `shadowfit_ai_stop_result_total` 이 🟡 —
# *"실패 허용률의 근거 없음"*. 그런데 그 앞에 더 기본적인 것이 비어 있다:
# **이 지표가 실제로 얼마나 찍히는지 관측된 적이 없다.**
#
# 왜 중요한가는 코드가 직접 적어뒀다(SessionMetrics.aiStopResult):
#
#   "AI는 세션 상태를 못 찾아도 gRPC 에러가 아니라 success=false 인 정상 응답을 준다
#    (AI 프로세스 재시작 시 in-memory 상태가 사라지므로). 그 경우 CompleteAnalysis 가
#    영영 오지 않아 결과가 유실되는데, **전송 층만 보면 «성공» 으로 보여 사건 자체가
#    관측되지 않는다.**"
#
# 그리고 AI 세션 상태가 **프로세스 메모리**에 산다는 것은 이미 확인된 사실이다
# (ai-receive-path-scaling.md §1 구조도 — 세션 레지스트리 🔴 프로세스 메모리).
# ─────────────────────────────────────────────────────────────────────────
#
# 🔴 **무엇을 재는지 정확히**: 「AI 가 모르는 세션을 Spring 이 종료시킬 때」다.
#    AI 입장에서 **«한 번도 없던 세션»** 과 **«재시작으로 잃은 세션»** 은 같다 — 둘 다
#    in-memory 레지스트리에 없다. 그래서 전자를 만들어 후자의 대리로 쓴다.
#    ⚠️ 이 대리 관계가 이 판의 유일한 가정이고, 결과에 그대로 적는다.
#
# 팔 = 한꺼번에 타임아웃되는 세션 수: **10 · 100 · 1000**
#    개수를 흔드는 이유는 비율이 아니라 **부하**다 — 세션이 쌓이면 타임아웃 스윕 한 번이
#    AI 를 N번 때린다(#207 과 같은 자리에서 만나는 문제다).
#
# 🔴 **부하가 없을 때만.** 스케줄러 틱을 관측하는 판이라 요청 경로가 섞이면 안 된다.
#
# 사용: sessions_sweep.sh 와 같은 환경변수

set -uo pipefail
cd "$(dirname "$0")"

LEVELS=(10 100 1000)
FAKE_LO=${FAKE_LO:-4000000}      # #207 rig(3,000,000)와도 안 겹친다
OUT="${OUT:?OUT 미설정}"
LOG="$OUT/ai_stop.tsv"

if [ "${PLAN_ONLY:-0}" = "1" ]; then
  echo "=== 從 AI stop 결과 — 팔 배치 ==="
  i=0; for l in "${LEVELS[@]}"; do i=$(( i + 1 )); printf "  [%s] 한꺼번에 타임아웃될 세션 %s개\n" "$i" "$l"; done
  echo "  팔당 스케줄러 틱 1회(≈1분) · 예상 소요 $(( ${#LEVELS[@]} * 2 + 2 ))분"
  exit 0
fi

source ./../commit-count-2026-08-09/_rig.sh

LOCK="$OUT/.aistop.lock"
mkdir -p "$OUT"
mkdir "$LOCK" 2>/dev/null || { echo "🔴 이미 돌고 있다: $LOCK" >&2; exit 1; }

learn_all_hosts
echo "=== 사전 확인 ==="
assert_mysql_reachable

rsh "$LOADER_PUB" "pgrep -x ghz >/dev/null 2>&1" \
  && die "부하기에서 ghz 가 돌고 있다 — 스케줄러 틱을 보는 판이라 요청 경로가 섞이면 안 된다"
echo "  부하 없음 확인"

# AI 가 살아 있어야 «success=false 를 준다» 를 확인할 수 있다. 죽어 있으면 gRPC 에러가 나고
# 그건 이 판이 묻는 것과 다른 사건이다.
rsh "$APP_PUB" "sudo docker ps --format '{{.Names}} {{.Status}}' | grep shadowfit-ai" | sed 's/^/  AI: /'

SEED=$(mysql_q "SELECT id FROM exercise_sessions WHERE id < $FAKE_LO ORDER BY id LIMIT 1;" | tr -d '[:space:]')
[ -n "$SEED" ] || die "복제할 씨앗 세션이 없다"

counter() {  # $1 = metric 이름(정규식). 없으면 0 — micrometer 는 첫 증가 때 등록한다
  rsh "$APP_PUB" "curl -s localhost:9090/actuator/prometheus 2>/dev/null | grep -E '$1' | awk '{s+=\$2} END{print s+0}'" \
    2>/dev/null | tr -d '[:space:]'
}

[ -f "$LOG" ] || printf "arm\tsessions\tstop_ok\tstop_missing\tfailed_transitions\tsecs\n" > "$LOG"

cleanup() {
  echo "=== 정리 ==="
  mysql_q "DELETE FROM exercise_sessions WHERE id >= $FAKE_LO;" >/dev/null
  rmdir "$LOCK" 2>/dev/null
}
trap cleanup EXIT

i=0
for n in "${LEVELS[@]}"; do
  i=$(( i + 1 ))
  echo
  echo "──────── [$i/${#LEVELS[@]}] 세션 $n 개를 한꺼번에 타임아웃시킨다 ────────"
  mysql_q "DELETE FROM exercise_sessions WHERE id >= $FAKE_LO;" >/dev/null

  # 🔴 start_time 을 3시간 전으로 둔다. 타임아웃 식이 start_time + (예상 운동시간 + 버퍼 30분)
  #    이라 확실히 걷히는 자리다. last_active_at 도 같이 밀어 idle 조건도 만족시킨다.
  mysql_q "INSERT INTO exercise_sessions
             (id, member_id, exercise_id, start_time, last_active_at, status, version, created_at)
           SELECT $FAKE_LO + t.n, s.member_id, s.exercise_id,
                  NOW() - INTERVAL 3 HOUR, NOW() - INTERVAL 3 HOUR, 'IN_PROGRESS', 0, NOW()
             FROM (WITH RECURSIVE seq(n) AS (SELECT 0 UNION ALL SELECT n+1 FROM seq WHERE n+1 < $n)
                   SELECT n FROM seq) t
             JOIN exercise_sessions s ON s.id = $SEED;" >/dev/null

  have=$(mysql_q "SELECT COUNT(*) FROM exercise_sessions WHERE id >= $FAKE_LO AND status='IN_PROGRESS';" | tr -d '[:space:]')
  [ "${have:-0}" = "$n" ] || die "팔이 안 섰다 — $n 개를 원했는데 $have 개다"
  echo "  IN_PROGRESS $have 개 (전부 3시간 전 시작 = 타임아웃 대상)"

  ok0=$(counter '^shadowfit_ai_stop_result_total.*outcome="ok"')
  ms0=$(counter '^shadowfit_ai_stop_result_total.*outcome="session-missing"')
  tr0=$(counter '^shadowfit_session_transitions_total.*source="timeout-scheduler"')
  s0=$(date +%s)

  echo "  스케줄러 틱 대기(최대 150초)..."
  left=$n
  for _ in $(seq 1 15); do
    sleep 10
    left=$(mysql_q "SELECT COUNT(*) FROM exercise_sessions WHERE id >= $FAKE_LO AND status='IN_PROGRESS';" | tr -d '[:space:]')
    [ "${left:-1}" = "0" ] && break
  done
  s1=$(date +%s)

  ok1=$(counter '^shadowfit_ai_stop_result_total.*outcome="ok"')
  ms1=$(counter '^shadowfit_ai_stop_result_total.*outcome="session-missing"')
  tr1=$(counter '^shadowfit_session_transitions_total.*source="timeout-scheduler"')

  d_ok=$(( ${ok1%.*} - ${ok0%.*} )); d_ms=$(( ${ms1%.*} - ${ms0%.*} )); d_tr=$(( ${tr1%.*} - ${tr0%.*} ))
  printf "%s\t%s\t%s\t%s\t%s\t%s\n" "$i" "$n" "$d_ok" "$d_ms" "$d_tr" "$(( s1 - s0 ))" >> "$LOG"
  echo "  결과: stop ok=$d_ok · session-missing=$d_ms · FAILED 전이=$d_tr · $(( s1 - s0 ))초"
  [ "${left:-1}" = "0" ] || echo "  ⚠️ 아직 $left 개가 IN_PROGRESS 다 — 틱 하나로 다 못 걷었다(그 자체가 사실이다)" >&2
done

echo
echo "=== 결과 ($LOG) ==="
cat "$LOG"
echo
echo "🔴 읽는 법: 이 판은 «AI 가 모르는 세션» 을 종료시킨 것이고, AI 입장에서 그것은"
echo "   «재시작으로 잃은 세션» 과 같다. 「실사용 실패율」이 아니라 «그 조건에서의 결과» 다."
