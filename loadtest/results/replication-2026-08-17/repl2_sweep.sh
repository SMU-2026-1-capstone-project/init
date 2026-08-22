#!/usr/bin/env bash
# 복제 2대 무대 — 본 측정 (Q1 지연 · Q2 반동기의 대가)
#
# 설계: docs/decisions/replication-lag-and-semisync.md §2·§4·§6
# 선행: repl2_probe.sh 의 게이트 G1~G3 통과. 안 통과했으면 여기 수치는 무대의 결함이다.
#
# ── 판 설계 (§4) ─────────────────────────────────────────────────────────
#   팔 A  비동기 복제 (기본)          기준선
#   팔 B  반동기 (AFTER_SYNC, 1대 대기) Q2 의 대가를 무는 쪽
#   버림판  팔당 1판 — 첫 판은 버퍼풀·페이지 캐시 상태를 가장 크게 탄다
#   본판    팔당 3판, **위치 합을 맞춘 배열**(A B B A A B) — 조건과 판 순서를 가른다
#   대조    단일 핫세션 1판씩 (H3) — 반동기 대가가 페이로드 조건에 의존하는지
#
# 🔴 판 사이에 리플리카를 다시 세우지 않는다(설계 §4 와 다른 지점, 의도적).
#    §4 는 「매 판 백업본에서 다시 세운다」였는데, 2대 구성에서 그 절차는 판마다
#    사본 뜨기·전송·붓기·기동을 반복한다 — 무인 라운드에서 제일 잘 깨지는 경로를
#    10번 밟는 셈이다. 대신 판 시작 조건을 **좌표 일치(따라잡음)** 라는 이진 사실로 걸고,
#    판마다 **무대 행수를 같이 기록**해 「판이 거듭될수록 테이블이 커졌다」를 표에서
#    볼 수 있게 한다. 완전한 대체는 아니다 — 이 차이를 결과 문서에 승계할 것.
#    (PER_ROUND_REINIT=1 로 두면 §4 그대로 판마다 다시 세운다. 시간이 크게 는다.)
#
# 🔴 팔 B 가 실패해도 팔 A 판을 버리지 않는다. 실패는 숫자가 아니라 FAIL 로 남긴다 —
#    「재봤더니 0」과 「재지 못했다」는 다른 사실이다.

set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/repl2_rig.sh"

REPS=${REPS:-3}
ARM_ORDER=${ARM_ORDER:-"A B B A A B"}
HOT_ARMS=${HOT_ARMS:-"A B"}
DISCARD_ARMS=${DISCARD_ARMS:-"A B"}
PER_ROUND_REINIT=${PER_ROUND_REINIT:-0}

LOG="$OUT/repl2.tsv"
RAW="$OUT/_raw"
mkdir -p "$RAW"
FAILED=()

init_log() {
  [ -f "$LOG" ] || printf 'round\tarm\tpayload\tkind\tdur_s\ttx_n\ttps\tc_p50_us\tc_p95_us\tc_p99_us\tc_max_us\tlag_n\tlag_p50_us\tlag_p95_us\tlag_max_us\tsbs_p50\tsbs_max\tsemi_status\tyes_tx_d\tno_tx_d\tcatchup_s\trows_before\n' > "$LOG"
}
fail_row() {  # $1=round $2=arm $3=payload $4=kind $5=사유
  printf '%s\t%s\t%s\t%s\tFAIL\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\n' "$1" "$2" "$3" "$4" >> "$LOG"
  FAILED+=("$1($2/$3): $5")
  log "🔴 판 $1 실패 — $5"
}

require_stage_ready() {
  require_stage
  detect_semisync_names
  local io sq
  io=$(rstat Replica_IO_Running); sq=$(rstat Replica_SQL_Running)
  [ "$io" = "Yes" ] && [ "$sq" = "Yes" ] \
    || die "복제가 안 돌고 있다 (IO=$io SQL=$sq) — repl2_probe.sh 를 먼저 돌릴 것"
  [ "$(SDBQ "SELECT COUNT(*) FROM information_schema.routines
             WHERE routine_schema='replprobe' AND routine_name='load_run';")" = "1" ] \
    || die "부하 프로시저가 없다 — repl2_probe.sh 를 먼저 돌릴 것"
  # 하트비트가 살아 있는지 — 죽어 있으면 지연이 전부 «안 움직이는 값» 으로 찍힌다.
  local a b
  a=$(SDBQ "SELECT beat_us FROM replprobe.heartbeat WHERE id=1;")
  sleep 2
  b=$(SDBQ "SELECT beat_us FROM replprobe.heartbeat WHERE id=1;")
  [ "${b:-0}" -gt "${a:-0}" ] || { log "하트비트가 멈춰 있다 — 다시 건다"; start_heartbeat; }
}

set_arm() {  # $1 = A|B → rc=0 이면 팔이 성립
  case "$1" in
    A) semisync_off
       [ "$(sstatus "${SRC_PREFIX}_status")" != "ON" ] || return 1
       return 0 ;;
    B) semisync_on || return 1
       semisync_yes_tx_grows || return 1
       return 0 ;;
    *) return 1 ;;
  esac
}

round_run() {  # $1=round $2=arm $3=multi|hot $4=main|discard
  local r=$1 arm=$2 payload=$3 kind=$4
  local dir="$RAW/${r}_${arm}_${payload}_${kind}"
  mkdir -p "$dir"
  head_ "판 $r — 팔 $arm · 페이로드 $payload · $kind"

  if [ "$PER_ROUND_REINIT" = "1" ]; then
    log "판마다 재구성(§4) — 리플리카를 다시 세운다"
    rebuild_replica || { fail_row "$r" "$arm" "$payload" "$kind" "리플리카 재구성 실패"; return 1; }
  fi

  set_arm "$arm" || { fail_row "$r" "$arm" "$payload" "$kind" "팔 $arm 구성 실패(반동기 상태)"; return 1; }

  # 🔴 초기 따라잡기 구간은 측정에 안 넣는다(§4). 판은 «좌표가 같아진 뒤» 시작한다.
  local catchup; catchup=$(wait_caught_up)
  [ "${catchup:-x}" = "-1" ] && { fail_row "$r" "$arm" "$payload" "$kind" "따라잡기 상한 초과"; return 1; }

  local rows_before yes0 no0 yes1 no1 st
  rows_before=$(SDBQ "SELECT COUNT(*) FROM $DB_NAME.pose_data_scale;")
  yes0=$(sstatus "${SRC_PREFIX}_yes_tx"); no0=$(sstatus "${SRC_PREFIX}_no_tx")

  start_sampler "$dir/lag.tsv"
  run_writers "$payload" "$DUR" "$dir"
  stop_sampler

  yes1=$(sstatus "${SRC_PREFIX}_yes_tx"); no1=$(sstatus "${SRC_PREFIX}_no_tx")
  st=$(sstatus "${SRC_PREFIX}_status")

  local cs ls tx_n tps
  cs=$(commit_stats "$dir" "$DUR")
  ls=$(lag_stats "$dir/lag.tsv")
  tx_n=$(echo "$cs" | awk '{print $1}')
  tps=$(echo "$cs" | awk '{print $2}')
  if [ "${tx_n:-0}" -lt 1 ]; then
    fail_row "$r" "$arm" "$payload" "$kind" "커밋 표본 0건 — writer 가 안 돌았다"
    return 1
  fi

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$r" "$arm" "$payload" "$kind" "$DUR" \
    "$tx_n" "$tps" \
    "$(echo "$cs" | awk '{print $3}')" "$(echo "$cs" | awk '{print $4}')" \
    "$(echo "$cs" | awk '{print $5}')" "$(echo "$cs" | awk '{print $6}')" \
    "$(echo "$ls" | awk '{print $1}')" "$(echo "$ls" | awk '{print $2}')" \
    "$(echo "$ls" | awk '{print $3}')" "$(echo "$ls" | awk '{print $4}')" \
    "$(echo "$ls" | awk '{print $5}')" "$(echo "$ls" | awk '{print $6}')" \
    "${st:-NA}" "$(( ${yes1:-0} - ${yes0:-0} ))" "$(( ${no1:-0} - ${no0:-0} ))" \
    "$catchup" "$rows_before" >> "$LOG"

  log "판 $r 완료 — tx=$tx_n tps=$tps · lag_net p50=$(echo "$ls" | awk '{print $2}')us · semi=$st"
  return 0
}

main() {
  head_ "복제 2대 — 본 측정 (Q1·Q2)"
  require_stage_ready
  init_log

  {
    echo "# 이 라운드의 조건"
    echo "무대            pose_data_scale $(SDBQ "SELECT COUNT(*) FROM $DB_NAME.pose_data_scale;") 행 (SESSIONS=$SESSIONS)"
    echo "부하            커넥션 $CONNS · 트랜잭션당 ${ROWS_PER_TX}행 INSERT + 세션행 UPDATE · 판당 ${DUR}s"
    echo "                (경로는 rig 내장 SQL — ghz→Spring 앱 경로가 아니다. repl2_rig.sh 헤더)"
    echo "팔 순서         버림 $DISCARD_ARMS → 본판 $ARM_ORDER → 핫세션 $HOT_ARMS"
    echo "반동기          $SRC_PREFIX · timeout=${SEMISYNC_TIMEOUT_MS}ms · wait_point=$SEMISYNC_WAIT_POINT"
    echo "GTID            $(SDBQ 'SELECT @@gtid_mode;')"
    echo "병렬 적용       replica_parallel_workers=$(RDBQ 'SELECT @@replica_parallel_workers;') (팔 아님 — §9-1 ⑥)"
    echo "내구성          sync_binlog=$(SDBQ 'SELECT @@sync_binlog;') / flush=$(SDBQ 'SELECT @@innodb_flush_log_at_trx_commit;')"
    echo "AZ 구성         ${REPL_AZ_MODE:-(미기입 — 사람이 채울 것)}"
    echo "리플리카 초기화 ${REPLICA_INIT_USED:-(이 스윕에서는 안 세웠다 — probe 참조)}"
  } | tee "$OUT/conditions.txt"

  local r=0 arm
  for arm in $DISCARD_ARMS; do
    r=$(( r + 1 )); round_run "$r" "$arm" multi discard
  done
  for arm in $ARM_ORDER; do
    r=$(( r + 1 )); round_run "$r" "$arm" multi main
  done
  # H3 — 반동기의 대가가 페이로드 조건에 의존하는가. 4차가 fsync 3.47배를 1.03배로
  # 뒤집은 자리라 이번엔 처음부터 두 조건을 다 잰다.
  for arm in $HOT_ARMS; do
    r=$(( r + 1 )); round_run "$r" "$arm" hot main
  done

  semisync_off
  head_ "결과 ($LOG)"
  cat "$LOG"
  echo
  echo "# 읽는 법"
  echo "#  Q1 = lag_p50/p95/max (lag_net = 리플리카 - 소스 자기읽기. 계측 바닥이 빠진 값)"
  echo "#  Q2 = 팔 A ↔ 팔 B 의 tps 와 c_p99_us 차이. **같은 페이로드끼리만** 비교할 것"
  echo "#  H3 = payload=multi ↔ hot 에서 그 차이의 «배수» 가 같은가"
  echo "#  discard 행은 버림판이다 — 표에 넣지 말 것"

  if [ ${#FAILED[@]} -gt 0 ]; then
    echo >&2
    echo "🔴 ${#FAILED[@]}판이 유효 데이터를 못 냈다:" >&2
    printf '   - %s\n' "${FAILED[@]}" >&2
    echo "   남은 판만으로 결론을 쓰지 말 것 — 비교의 근거가 그만큼 줄었다." >&2
    exit 1
  fi
  echo
  echo "✅ 전 판 유효."
}

main "$@"
