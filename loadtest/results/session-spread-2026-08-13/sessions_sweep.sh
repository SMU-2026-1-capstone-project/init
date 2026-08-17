#!/bin/bash
# 세션 분산도 스윕 — «부하가 몇 개 세션에 흩어져 있는가» 하나만 흔든다.
#
# ─────────────────────────────────────────────────────────────────────────
# 왜 이 스윕이 있나
#
# 4차(2026-08-09)가 **페이로드만** 단일 핫세션 ↔ 100세션으로 바꿔 처리량 2.9배를 냈다
# (220.4 ↔ 649.4 RPS). 그 뒤 정본 baseline 은 **649.4 로 갱신됐다.**
#
# 그런데 그 «100» 은 **측정으로 고른 값이 아니다.** `gen_batch_multi.py` 헤더가 고른 값이고,
# 1 과 100 사이는 통째로 비어 있다. 그리고 이 앱은 **회원당 활성 세션을 1개로 강제**하므로
# (`createSession` 이 409) **동시 세션 수 = 동시에 운동 중인 사람 수**다.
#
# 즉 baseline 이 서 있는 조건이 «가정한 부하» 보다 분산된 쪽일 수 있다. 그러면 정본 수치가
# 낙관 방향으로 틀린다 — 3.47배 → 1.03배와 **같은 계열의 위험**이다.
#
# 설계: docs/decisions/session-spread-sweep.md
# ─────────────────────────────────────────────────────────────────────────
#
# 이 스윕이 고정하는 것 (조작 변수는 «세션 수» 하나여야 한다):
#   - 요청 모양      : `--reps 25` 고정 → 다운샘플 R=5 → 저장 5행/요청. 전 레벨 동일
#   - 생성기         : 전 레벨 `gen_batch_multi.py` 하나. 🔴 4차의 «단일 220.4 RPS» 는
#                      **다른 생성기**(`gen_batch.py`)로 잰 값이라 이 표의 레벨 1 과
#                      **같은 판이 아니다.** 레벨 간 비교는 이 표 안에서만 한다
#   - 총 행수        : 판마다 N_REQ × 5 행으로 같다. 흩어지는 «자리» 만 다르다
#   - 내구성         : 기본값(flush=1 / sync_binlog=1). 완화판은 이 스윕이 묻는 질문이 아니다
#   - 풀 크기        : 재기동하지 않는다. pool 은 이 실험의 조작 변수가 아니라 **고정 조건**이라
#                      값을 확인만 하고 다르면 멈춘다
#
# 🔴 2026-08-17 (#271): 페이로드가 **배열이 아니라 ghz 템플릿**이 됐다. 세션 라우팅은
#    `mod .RequestNumber <레벨>` 이 하고(예전 배열 순환과 같은 일), `repNumber` 가 요청마다
#    달라져 멱등 키를 움직인다. 안 그러면 세션당 첫 요청만 행을 만들고 나머지는 no-op 인데
#    `fail=0` 에 RPS 도 정상으로 찍혀 **표를 봐서는 안 보인다.**
#    부수 효과: 레벨 100 페이로드가 5.2MB → 54KB. 대신 부하기에 요청마다 템플릿·파싱이
#    붙는다 — 리허설에서 부하기 CPU 와 달성 rate 를 볼 것(설계 문서 §3).
#
# 사용:
#   PLAN_ONLY=1 bash sessions_sweep.sh          # 판 배치만 출력하고 끝낸다(EC2 불필요)
#   PEM=... DB_PUB=... APP_PUB=... LOADER_PUB=... OBS_PUB=... DB_PRIV=... APP_PRIV=... \
#   OUT=$PWD/run bash sessions_sweep.sh

set -uo pipefail
cd "$(dirname "$0")"

# ── 고정 조건 ────────────────────────────────────────────────────────────
SESS_LO=901
LEVELS=(1 2 5 20 100)
# 🔴 `reset_rows` 가 지우는 범위다. **모든 레벨의 상위집합**이어야 한다 — 좁으면 앞 판이
#    남긴 행이 다음 판의 테이블 크기가 되고, 그러면 «판 순서» 가 조작 변수에 섞인다.
SESS_HI=$(( SESS_LO + ${LEVELS[-1]} - 1 ))

C=${C:-100}
N_REQ=${N_REQ:-30000}
REPS=${REPS:-25}          # 요청당 프레임 수
# 🔴 유도한다. 예전엔 `ROWS_PER_REQ=5` 상수였는데 `REPS` 는 환경변수로 덮을 수 있어서,
#    `REPS` 를 바꾸면 `rows_s` 열이 **조용히 틀렸다**(#273 ②). 둘은 같이 움직여야 한다.
#    창 크기 5 의 출처는 `PoseDataService.java:58` 의 `DOWNSAMPLE_WINDOW` 다 — 저기가 바뀌면
#    여기도 바뀌어야 하고, 그 사실을 상수로 숨기지 않는다.
DOWNSAMPLE_WINDOW=${DOWNSAMPLE_WINDOW:-5}
ROWS_PER_REQ=$(( (REPS + DOWNSAMPLE_WINDOW - 1) / DOWNSAMPLE_WINDOW ))   # ceil
GEN=../../ghz/gen_batch_multi.py
PY=${PY:-python}

# ── 판 배치 — 5×5 순환 라틴 방격 ─────────────────────────────────────────
#
# 🔴 **팔당 1판을 쓰지 않는다.** 4차 초판이 팔당 1판이라 «N 클수록 +32%» 를 냈는데, 순서만
#    뒤집으니 «작을수록 +36%» 였다. 팔과 판 순서가 같은 축에 겹치면 **원리적으로 분리가
#    안 된다** — 부호조차 못 정한다.
#
# 5개 레벨 × 5회 반복. 행마다 한 칸씩 회전시키면 각 레벨이 **위치 1~5 에 정확히 한 번씩**
# 온다. 그래서 «앞에 놓여서 빨랐다» 가 레벨 효과로 위장할 수 없다.
#
#   반복1: 1   2   5   20  100
#   반복2: 2   5   20  100 1
#   반복3: 5   20  100 1   2
#   반복4: 20  100 1   2   5
#   반복5: 100 1   2   5   20
plan_rounds() {
  local n=${#LEVELS[@]} i j
  PLAN=()
  for (( i=0; i<n; i++ )); do
    for (( j=0; j<n; j++ )); do
      PLAN+=("${LEVELS[$(( (i + j) % n ))]}:$(( i + 1 )):$(( j + 1 ))")   # 레벨:반복:위치
    done
  done
}

plan_rounds

# 버림판 — 버퍼풀·JIT 가 첫 판에 몰아주는 몫을 표 밖으로 뺀다. 최고 레벨로 도는 이유는
# **가장 넓은 인덱스 구간을 먼저 훑어두기 위해서**다(레벨 1 로 워밍업하면 900번대 뒷쪽이
# 차갑게 남아 첫 100세션 판이 손해를 본다).
DISCARD_LEVEL=${LEVELS[-1]}

print_plan() {
  echo "=== 판 배치 (버림판 1 + 본판 ${#PLAN[@]}) ==="
  printf "  discard\t레벨 %s\t(표에 안 들어간다)\n" "$DISCARD_LEVEL"
  local e lvl rep pos
  for e in "${PLAN[@]}"; do
    IFS=: read -r lvl rep pos <<< "$e"
    printf "  s%s_r%s\t레벨 %s\t반복 %s\t위치 %s\n" "$lvl" "$rep" "$lvl" "$rep" "$pos"
  done
  echo
  echo "  레벨별 판 수: $(for e in "${PLAN[@]}"; do echo "${e%%:*}"; done | sort -n | uniq -c \
        | awk '{printf "%s=%s판 ", $2, $1}')"
  echo "  위치별 분포 : 각 레벨이 위치 1~5 에 한 번씩 (라틴 방격)"
  echo
  echo "  요청 수/판  : $N_REQ (c=$C) · 저장 행/판: $(( N_REQ * ROWS_PER_REQ ))"
  echo "  세션 범위   : $SESS_LO~$SESS_HI (판 사이 이 범위를 지운다)"
}

if [ "${PLAN_ONLY:-0}" = "1" ]; then
  print_plan
  exit 0
fi

OUT="${OUT:?OUT 미설정}"
LOG="$OUT/sessions.tsv"
SPREAD_LOG="$OUT/spread.tsv"
WRITER_LOG="$OUT/writer.tsv"

source ./../commit-count-2026-08-09/_rig.sh

# ── 추가 관측 (27-implementation-gaps §4 의 «같이 고칠 것» 2건) ──────────
#
# ① **판별 시간 창을 남긴다.** 4차는 이걸 안 남겨 ghz 리포트에서 역산해야 했다.
# ② **dirty page 를 남긴다.** «판 순서 효과» 의 새 후보였는데 스크레이프에 없어서 검증을
#    못 했다.
# 그리고 이 실험의 **기제 지표**를 하나 더 건다:
# ③ `Innodb_row_lock_waits` — 4차 사후 분석에서 단일 세션 8,056 vs 다세션 0~3 으로
#    갈린 바로 그 값이다. 레벨이 오를 때 **이것이 어디서 죽는지**가 곡선의 설명이다.
spread_counters() {  # stdout: "row_lock_waits row_lock_time dirty_pages"
  mysql_q "SELECT
      MAX(CASE WHEN VARIABLE_NAME='INNODB_ROW_LOCK_WAITS' THEN VARIABLE_VALUE END),
      MAX(CASE WHEN VARIABLE_NAME='INNODB_ROW_LOCK_TIME' THEN VARIABLE_VALUE END),
      MAX(CASE WHEN VARIABLE_NAME='INNODB_BUFFER_POOL_PAGES_DIRTY' THEN VARIABLE_VALUE END)
    FROM performance_schema.global_status
    WHERE VARIABLE_NAME IN ('INNODB_ROW_LOCK_WAITS','INNODB_ROW_LOCK_TIME',
                            'INNODB_BUFFER_POOL_PAGES_DIRTY');" | tr '\t' ' '
}

CUR_LEVEL=""; CUR_REP=""; CUR_POS=""; S0=""
round_begin_hook() { S0=$(spread_counters); start_writer "$1"; }
round_end_hook() {   # $1=태그 $2=t0 $3=t1
  local tag=$1 t0=$2 t1=$3 s1 w0 w1 tm0 tm1 d0 d1 rows want
  s1=$(spread_counters)

  # 🔴 «보낸 요청이 실제로 행을 만들었는가». 이 그물이 없어서 #271 을 못 봤다 —
  #    멱등 키가 중복을 삼키면 `ON DUPLICATE KEY UPDATE` 가 **성공**이라 fail=0 에
  #    RPS 도 정상으로 찍히고, 표를 봐서는 아무것도 안 보인다.
  #    **`reset_rows` 보다 먼저 도는 자리**여야 한다(run_ghz 의 훅 호출 순서가 그렇다).
  #
  #    임계값을 두지 않는다. 「몇 % 이하면 문제」는 근거 없는 선이라 판정을 내리는 척만 한다.
  #    멈추는 것은 **0** 하나 — 그건 기준이 아니라 «측정이 성립하지 않았다» 의 정의다.
  #    그 밖의 어긋남은 숫자를 표에 남기고 사람이 읽는다(설계 §4-1 이 «총 행수는 판마다
  #    같다» 를 고정 조건으로 걸어뒀으므로, want 와 다르면 그 조건이 깨진 것이다).
  rows=$(mysql_q "SELECT COUNT(*) FROM pose_data WHERE session_id BETWEEN $SESS_LO AND $SESS_HI;")
  want=$(( N_REQ * ROWS_PER_REQ ))
  if [ "${rows:-0}" = "0" ]; then
    stop_writer "$tag"
    die "판 $tag 이 행을 하나도 안 만들었다 — 요청은 갔는데 저장이 안 됐다.
   멱등 키가 전부 삼켰거나(#271) 페이로드가 같은 값을 반복하고 있다.
   RPS·fail 은 정상으로 보이지만 이 판은 처리량이 아니라 «중복 감지» 를 잰 것이다"
  fi
  [ "${rows:-0}" = "$want" ] || echo "  ⚠️ 행수가 기대와 다르다 — $rows / $want (조작 변수 밖 조건이 흔들렸다)" >&2

  stop_writer "$tag"
  read -r w0 tm0 d0 <<< "$S0"
  read -r w1 tm1 d1 <<< "$s1"
  # 못 걷었으면 «0» 이 아니라 «-» 다. 이 rig 의 규약(FAIL ≠ 0)을 훅에도 그대로 적용한다.
  local waits="-" lock_ms="-"
  [ -n "${w0:-}" ] && [ -n "${w1:-}" ] && waits=$(( w1 - w0 ))
  [ -n "${tm0:-}" ] && [ -n "${tm1:-}" ] && lock_ms=$(( tm1 - tm0 ))
  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "$tag" "$CUR_LEVEL" "$CUR_REP" "$CUR_POS" \
    "$(date -u -d "@$t0" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo "-")" \
    "$(date -u -d "@$t1" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo "-")" \
    "$(( t1 - t0 ))" "$waits" "$lock_ms" "${d0:--}→${d1:--}" \
    "${rows:--}" "$want" >> "$SPREAD_LOG"
}

init_spread_log() {
  [ -f "$SPREAD_LOG" ] || printf \
    "tag\tlevel\trep\tpos\tt0_utc\tt1_utc\tsecs\trow_lock_waits\trow_lock_time_ms\tdirty_pages\trows_inserted\trows_expected\n" \
    > "$SPREAD_LOG"
  [ -f "$WRITER_LOG" ] || printf \
    "tag\tlevel\tattempts\terrors\tp50_ms\tp95_ms\tmax_ms\tmax_gap_ms\tended_early\n" > "$WRITER_LOG"
}

# ── 백그라운드 writer — «번짐 반경» 관측 채널 ────────────────────────────
#
# 부하와 **무관한 세션 하나**에 초당 5회 쓰면서 그 지연을 따로 기록한다. ghz 가 재는 것은
# 부하를 받는 세션들의 지연이라, 「한 사용자의 폭주가 다른 사용자에게 번지는가」는 그 표에
# 안 나온다. 상세 근거는 `spread_writer.sql` 헤더.
#
# ⚠️ 이 writer 자체도 쓰기다. 초당 5건 ≈ 부하의 1% 미만이고 **모든 판에 똑같이** 걸리므로
#    레벨 간 비교를 흔들지 않는다. 다만 «부하가 0인 판» 은 이 rig 에 없다는 뜻이기도 하다.
WRITER_SESSION=${WRITER_SESSION:-1001}   # 부하 대역(901~1000) 밖. 존재하는 세션이어야 한다(FK)
WRITER_GAP_MS=${WRITER_GAP_MS:-200}      # 초당 5회 — 정지 구간을 200ms 해상도로 본다
WRITER_MAX_SEC=${WRITER_MAX_SEC:-900}    # 판이 끝나면 stop 으로 멈춘다. 이건 백스톱이다

install_writer() {
  ssh "${SSH_OPTS[@]}" "ec2-user@$DB_PUB" \
    "sudo docker exec -i $MYSQL_CTN mysql -u$MYSQL_USER -p$MYSQL_PW shadowfit" < ./spread_writer.sql \
    || die "spread_writer.sql 적재 실패"
  mysql_q "SELECT COUNT(*) FROM information_schema.routines
           WHERE routine_schema='shadowfit' AND routine_name='spread_writer';" | grep -q '^1$' \
    || die "spread_writer 프로시저가 안 만들어졌다"
  local n
  n=$(mysql_q "SELECT COUNT(*) FROM exercise_sessions WHERE id=$WRITER_SESSION;")
  [ "$n" = "1" ] || die "writer 세션 $WRITER_SESSION 이 없다 — FK 로 전 건이 실패하고, 이 장치는 그걸 삼킨다"
  echo "  writer: 세션 $WRITER_SESSION · ${WRITER_GAP_MS}ms 간격 (설치 확인)"
}

start_writer() {  # $1 = 태그
  mysql_q "DELETE FROM spread_writer_log WHERE arm='$1'; DELETE FROM spread_writer_ctl;" >/dev/null
  rsh "$DB_PUB" "sudo docker exec -d $MYSQL_CTN mysql -u$MYSQL_USER -p$MYSQL_PW shadowfit \
    -e \"CALL spread_writer('$1', $WRITER_SESSION, $WRITER_MAX_SEC, $WRITER_GAP_MS);\"" >/dev/null 2>&1
  sleep 3   # 부하 전 평상시 구간을 몇 건 확보한다 — 「원래 몇 ms 인가」의 기준선
  local n
  n=$(mysql_q "SELECT COUNT(*) FROM spread_writer_log WHERE arm='$1';")
  # 🔴 writer 가 안 떴으면 **판을 죽이지 않고 그 판의 writer 칸만 비운다.** ghz 지표는
  #    멀쩡한데 부가 관측 때문에 판을 버리면 주객이 뒤집힌다(훅 규약).
  [ "${n:-0}" -gt 0 ] || echo "  ⚠️ writer 가 3초 안에 한 건도 못 썼다 — 이 판의 writer 칸은 비운다" >&2
}

stop_writer() {  # $1 = 태그
  # 🔴 **멈추라고 하기 전에** 아직 살아 있었는지 본다(#273 ④). `WRITER_MAX_SEC` 는 백스톱인데
  #    판이 그보다 길면 writer 가 판 도중에 스스로 끝난다 — 그러면 이 판의 «번짐 반경» 은
  #    판 후반을 못 본 값이다. 그런데 그건 **정지가 아니라 관측 종료**라 `max_gap_ms` 에
  #    안 잡히고, `attempts` 가 조금 작을 뿐이라 표에서 안 보인다.
  #    임계값이 아니라 **이진 사실**로 잡는다: 제어행이 이미 없거나 커넥션이 죽어 있으면 그렇다.
  local early="no" cid0 alive0
  cid0=$(mysql_q "SELECT conn_id FROM spread_writer_ctl WHERE id=1;" | tr -d '[:space:]')
  if [ -z "${cid0:-}" ]; then
    early="yes"   # 프로시저가 끝나며 제어행을 스스로 지웠다
  else
    alive0=$(mysql_q "SELECT COUNT(*) FROM performance_schema.processlist WHERE id=$cid0;")
    [ "${alive0:-0}" = "0" ] && early="yes"
  fi
  [ "$early" = "yes" ] && echo "  ⚠️ writer 가 판보다 먼저 멈췄다 (WRITER_MAX_SEC=$WRITER_MAX_SEC 초과 또는 죽음)" \
    "— 이 판의 번짐 반경은 판 전체를 못 봤다" >&2

  mysql_q "UPDATE spread_writer_ctl SET stop=1 WHERE id=1;" >/dev/null
  local cid alive i
  cid=$(mysql_q "SELECT conn_id FROM spread_writer_ctl WHERE id=1;" | tr -d '[:space:]')
  for i in 1 2 3 4 5; do
    alive=$(mysql_q "SELECT COUNT(*) FROM performance_schema.processlist WHERE id=${cid:-0};")
    [ "${alive:-0}" = "0" ] && break
    sleep 1
  done
  # 협조 종료가 안 되면 KILL 이 백스톱이다. 살아남은 writer 는 **다음 판에 계속 쓴다** —
  # DDL rig 에서 실제로 그 사고가 났고(writer.sql 헤더), 그때는 다음 판의 행수 검사가 깨졌다.
  if [ "${alive:-0}" != "0" ] && [ -n "${cid:-}" ]; then
    echo "  ⚠️ writer 가 협조 종료에 응답하지 않아 KILL 한다 (conn $cid)" >&2
    mysql_q "KILL $cid;" >/dev/null 2>&1
  fi

  # 이 판의 writer 지연을 요약해 남긴다. `max_gap_ms` 는 **시도 «시작» 시각의 최대 간격** —
  # 한 건이 오래 걸린 것과 아예 시도가 끊긴 것을 가르는 값이다.
  mysql_q "SELECT COUNT(*), SUM(errno<>0),
                  MAX(CASE WHEN rn=p50 THEN elapsed_ms END),
                  MAX(CASE WHEN rn=p95 THEN elapsed_ms END),
                  MAX(elapsed_ms), MAX(gap)
           FROM (SELECT elapsed_ms, errno,
                        ROW_NUMBER() OVER (ORDER BY elapsed_ms) rn,
                        CEIL(COUNT(*) OVER () * 0.50) p50,
                        CEIL(COUNT(*) OVER () * 0.95) p95,
                        TIMESTAMPDIFF(MICROSECOND,
                          LAG(started_at) OVER (ORDER BY seq), started_at)/1000 gap
                 FROM spread_writer_log WHERE arm='$1') t;" \
    | awk -v tag="$1" -v lvl="$CUR_LEVEL" -v early="$early" 'BEGIN{OFS="\t"}
        {print tag, lvl, $1, $2, $3, $4, $5, $6, early}' >> "$WRITER_LOG"

  # writer 가 넣은 행은 부하 대역 밖이라 `reset_rows` 가 안 지운다. 여기서 지운다 —
  # 안 지우면 판이 갈수록 테이블이 커져 «판 순서» 가 조작 변수에 섞인다.
  mysql_q "DELETE FROM pose_data WHERE session_id=$WRITER_SESSION;" >/dev/null
}

# ── 사전 확인 ────────────────────────────────────────────────────────────
#
# 전부 «틀려도 표는 정상으로 보이는» 것들이다. 무인이 아니라 사람이 지켜보는 스윕이어도
# 1시간 뒤에 알아채면 1시간을 버린다.
# `assert_sessions_exist` 는 공통부(`_rig.sh`)로 옮겼다 — 從 스크립트에도 같은 그물이
# 필요한데 여기 있으면 복사본이 생긴다(#273 ③).

assert_default_durability() {
  local got
  got=$(mysql_q "SELECT @@innodb_flush_log_at_trx_commit, @@sync_binlog;" | tr '\t' ' ')
  # 🔴 4차의 pool 스윕이 완화 상태를 남기고 끝났을 수 있다. 이 스윕은 내구성을 **안 만지므로**
  #    확인하지 않으면 «기본값이겠거니» 하고 3.47배만큼 틀린 판을 잰다.
  [ "$got" = "1 1" ] || die "내구성이 기본값이 아니다 — flush/sync_binlog = '$got' (원하는 값 '1 1')"
  echo "  내구성: flush=1 sync_binlog=1 확인"
}

assert_pool_fixed() {
  local got
  got=$(rsh "$APP_PUB" "curl -s localhost:9090/actuator/prometheus 2>/dev/null \
        | grep '^hikaricp_connections_max' | head -1 | awk '{print \$2}' | cut -d. -f1")
  [ -n "$got" ] || die "백엔드에서 풀 크기를 못 읽었다 — 안 떠 있거나 관리 포트가 막혔다"
  # 값을 강제하지 않는다. 이 실험은 pool 을 안 흔들므로 **무엇으로 고정됐는지 기록**하면 된다.
  echo "  풀 크기(고정 조건): $got"
  echo "pool=$got" >> "$OUT/_conditions.txt"
}

prepare_payloads() {
  echo "=== 페이로드 생성 — 레벨당 1개 (요청 모양은 전 레벨 동일) ==="
  mkdir -p "$OUT/_payload"
  local lvl hi f
  for lvl in "${LEVELS[@]}"; do
    hi=$(( SESS_LO + lvl - 1 ))
    f=$OUT/_payload/spread_$lvl.json
    "$PY" "$GEN" --sessions "$SESS_LO-$hi" --reps "$REPS" --out "$f" \
      || die "페이로드 생성 실패 (레벨 $lvl)"
    # 🔴 «만들었다» 와 «올라갔다» 는 다르다. 부하기에 없으면 ghz 가 그 판만 조용히 죽는다.
    scp "${SCP_OPTS[@]}" -q "$f" "ec2-user@$LOADER_PUB:/tmp/spread_$lvl.json" \
      || die "페이로드 전송 실패 (레벨 $lvl)"
    local remote
    remote=$(rsh "$LOADER_PUB" "wc -c < /tmp/spread_$lvl.json" 2>/dev/null | tr -d '\r')
    [ "$remote" = "$(wc -c < "$f" | tr -d ' ')" ] \
      || die "전송된 페이로드 크기가 다르다 (레벨 $lvl) — 원본 $(wc -c < "$f") vs 원격 $remote"
  done
  echo
}

# ── 실행 ─────────────────────────────────────────────────────────────────
learn_all_hosts
mkdir -p "$OUT"
init_log
init_spread_log
: > "$OUT/_conditions.txt"

echo "=== 사전 확인 ==="
assert_mysql_reachable
assert_sessions_exist
assert_default_durability
assert_pool_fixed
install_writer
echo

prepare_payloads
print_plan
echo

PLANS=()

echo "──────── 버림판 (레벨 $DISCARD_LEVEL) ────────"
CUR_LEVEL=$DISCARD_LEVEL; CUR_REP=0; CUR_POS=0
# 🔴 실패해도 **집계에 안 넣는다**(#273 ①). 예전엔 행만 지우고 FAILED 에는 남아서, 본판
#    25개가 전부 멀쩡해도 스윕이 exit 1 로 끝나고 요약이 표에 없는 태그를 지목했다.
GHZ_DISCARD=1
run_ghz "discard_s$DISCARD_LEVEL" "/tmp/spread_$DISCARD_LEVEL.json" "$C" "$N_REQ" || true
GHZ_DISCARD=0
# 🔴 버림판은 **표에서 지운다.** 남겨두면 다음 사람이 판 수를 세다가 레벨 하나만 6판이 되는
#    것을 못 보고 평균에 넣는다.
sed -i "/^discard_s$DISCARD_LEVEL\t/d" "$LOG" 2>/dev/null
echo "  (버림판은 표에서 제외했다)"
echo

i=0
for e in "${PLAN[@]}"; do
  IFS=: read -r CUR_LEVEL CUR_REP CUR_POS <<< "$e"
  i=$(( i + 1 ))
  tag="s${CUR_LEVEL}_r${CUR_REP}"
  echo "──────── [$i/${#PLAN[@]}] $tag — 레벨 ${CUR_LEVEL}세션 · 반복 $CUR_REP · 위치 $CUR_POS ────────"
  PLANS+=("$tag")
  run_ghz "$tag" "/tmp/spread_$CUR_LEVEL.json" "$C" "$N_REQ" || true
done

echo
echo "=== 추가 관측 ($SPREAD_LOG) ==="
cat "$SPREAD_LOG"
echo
echo "=== 무관한 세션의 쓰기 — 번짐 반경 ($WRITER_LOG) ==="
cat "$WRITER_LOG"

finish ${#PLANS[@]}
