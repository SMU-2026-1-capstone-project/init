#!/bin/bash
# 從 — 「멱등 유니크 키가 쓰기에 얼마를 물리나」 (#272)
#
# ─────────────────────────────────────────────────────────────────────────
# 무엇이 열려 있나
#
# 2026-08-17 에 `uk_pose_event (session_id, rep_number, timestamp_sec, created_at)` 가
# 들어왔다(#188 멱등). 마이그레이션이 **스스로 적어둔** 미측정 항목이 이것이다:
#
#   "⚠️ 비용을 알고 건다: 유니크 secondary index 는 InnoDB change buffer 를 쓸 수 없다.
#    삽입마다 인덱스 페이지를 읽어 유일성을 확인해야 하므로 쓰기 경로에 랜덤 읽기가 붙는다.
#    이 스키마·이 박스에서 그 크기는 아직 측정된 적이 없다."
#      — V6__add_pose_data_idempotency_key.sql:62~65
#
# 이게 왜 지금 급한가: 정본 baseline **649.4 RPS** 는 이 키가 **없던** 스키마에서 나왔다.
# 그래서 P5 곡선을 그려도 「4차보다 높다/낮다」를 말할 수 없다. 이 판이 그 단절의 **크기**를
# 준다 — 「못 잇는다」가 「이만큼 차이 나서 못 잇는다」가 된다.
# ─────────────────────────────────────────────────────────────────────────
#
# 팔은 둘뿐이다. 조작 변수는 **인덱스의 존재** 하나다:
#   A(with)    — 지금 스키마 그대로
#   B(without) — `DROP INDEX uk_pose_event`
#
# 🔴 두 팔이 **같은 행을 넣는다.** 페이로드가 템플릿이라 요청마다 키가 달라
#    (#271 수정본) 중복이 없다 — 즉 B 에서도 «중복이 안 걸려서 빨라진» 것이 아니라
#    **인덱스 유지 비용만** 빠진다. 그게 이 대조의 전부여야 한다.
#
# 🔴 **본 스윕 뒤에 돌린다.** 스키마를 만지기 때문에 순서가 뒤집히면 본 스윕이
#    «키가 있는 조건» 이라고 믿으며 없는 상태를 잰다.
#
# 판 배치 — ABBA/BAAB 로 **위치를 완전히 균형**시킨다(각 팔의 평균 위치 4.5):
#   위치: 1 2 3 4 5 6 7 8
#         A B B A B A A B
# 팔당 1판이면 «팔» 과 «판 순서» 가 안 갈린다는 것은 이 저장소가 이미 두 번 데인 곳이다.
#
# 설계 근거: docs/decisions/session-spread-sweep.md · #272
# 사용: sessions_sweep.sh 와 같은 환경변수 (OUT 은 같은 디렉터리를 준다)

set -uo pipefail
cd "$(dirname "$0")"

SESS_LO=901
LEVEL=${LEVEL:-20}                   # 리허설에서 plateau 가 붙은 자리 — 천장 근처에서 본다
SESS_HI=$(( SESS_LO + LEVEL - 1 ))
C=${C:-100}
N_REQ=${N_REQ:-30000}
REPS=${REPS:-25}
DOWNSAMPLE_WINDOW=${DOWNSAMPLE_WINDOW:-5}
ROWS_PER_REQ=$(( (REPS + DOWNSAMPLE_WINDOW - 1) / DOWNSAMPLE_WINDOW ))
GEN=../../ghz/gen_batch_multi.py
PY=${PY:-python}

ORDER=(with without without with without with with without)

if [ "${PLAN_ONLY:-0}" = "1" ]; then
  echo "=== 從 유니크 키 대가 — 판 배치 (버림판 1 + 본판 ${#ORDER[@]}) ==="
  echo "  discard  팔 with"
  i=0; for e in "${ORDER[@]}"; do i=$(( i + 1 )); printf "  [%s] %s\n" "$i" "$e"; done
  echo
  echo "  무대: 레벨 ${LEVEL}세션 · c=$C · -n $N_REQ · 저장 행/판 $(( N_REQ * ROWS_PER_REQ ))"
  exit 0
fi

OUT="${OUT:?OUT 미설정}"
LOG="$OUT/uk.tsv"
MECH_LOG="$OUT/uk_mech.tsv"

LOCK="$OUT/.uk.lock"
mkdir -p "$OUT"
mkdir "$LOCK" 2>/dev/null || { echo "🔴 이 OUT 에서 이미 돌고 있다: $LOCK" >&2; exit 1; }
trap 'rmdir "$LOCK" 2>/dev/null' EXIT

source ./../commit-count-2026-08-09/_rig.sh

# ── 팔 전환 ──────────────────────────────────────────────────────────────
#
# 🔴 «걸었다» 와 «걸렸다» 는 다르다. 캡 실험이 같은 이유로 단언을 넣었다(#253).
#    인덱스가 안 빠진 채로 도는 판이 표에 들어가면 «차이 없음» 으로 읽힌다.
assert_index() {  # $1 = present|absent
  local n
  n=$(mysql_q "SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema='shadowfit' AND table_name='pose_data'
                 AND index_name='uk_pose_event';")
  case "$1" in
    present) [ "${n:-0}" -gt 0 ] || die "uk_pose_event 가 없어야 할 자리가 아닌데 없다 (열 $n)" ;;
    absent)  [ "${n:-0}" = "0" ] || die "uk_pose_event 를 뗐는데 아직 있다 (열 $n)" ;;
  esac
  echo "  인덱스: $1 확인 (열 $n)"
}

set_arm() {  # $1 = with|without
  case "$1" in
    with)
      mysql_q "ALTER TABLE pose_data ADD UNIQUE KEY uk_pose_event
               (session_id, rep_number, timestamp_sec, created_at);" >/dev/null 2>&1
      assert_index present ;;
    without)
      mysql_q "ALTER TABLE pose_data DROP INDEX uk_pose_event;" >/dev/null 2>&1
      assert_index absent ;;
  esac
}

# ── 기제 채널 ────────────────────────────────────────────────────────────
#
# 주장은 «change buffer 를 못 써서 랜덤 읽기가 붙는다» 다. 처리량 차이만 보면 그 주장이
# 아니라 **어떤 차이든** 지지하는 것처럼 보인다. 그래서 읽기를 직접 센다:
#   · `Innodb_buffer_pool_reads`         — 버퍼풀에서 못 찾아 **디스크로 간** 횟수
#   · `Innodb_buffer_pool_read_requests` — 전체 읽기 요청
#   · `Innodb_ibuf_merges` / `_merged_inserts` — change buffer 가 실제로 일한 양
# 팔 B 에서 ibuf 가 늘고 팔 A 에서 reads 가 늘면 기제가 그대로 보인다.
mech_counters() {
  mysql_q "SELECT
      MAX(CASE WHEN VARIABLE_NAME='INNODB_BUFFER_POOL_READS' THEN VARIABLE_VALUE END),
      MAX(CASE WHEN VARIABLE_NAME='INNODB_BUFFER_POOL_READ_REQUESTS' THEN VARIABLE_VALUE END),
      MAX(CASE WHEN VARIABLE_NAME='INNODB_IBUF_MERGES' THEN VARIABLE_VALUE END),
      MAX(CASE WHEN VARIABLE_NAME='INNODB_IBUF_MERGED_INSERTS' THEN VARIABLE_VALUE END)
    FROM performance_schema.global_status
    WHERE VARIABLE_NAME IN ('INNODB_BUFFER_POOL_READS','INNODB_BUFFER_POOL_READ_REQUESTS',
                            'INNODB_IBUF_MERGES','INNODB_IBUF_MERGED_INSERTS');" | tr '\t' ' '
}

CUR_ARM=""; M0=""
round_begin_hook() { M0=$(mech_counters); }
round_end_hook() {   # $1=태그 $2=t0 $3=t1
  local tag=$1 t0=$2 t1=$3
  local m1 r0 rr0 im0 ii0 r1 rr1 im1 ii1 rows want
  m1=$(mech_counters)

  # 본 스윕과 같은 그물 — 보낸 요청이 실제로 행을 만들었는가 (#271)
  rows=$(mysql_q "SELECT COUNT(*) FROM pose_data WHERE session_id BETWEEN $SESS_LO AND $SESS_HI;")
  want=$(( N_REQ * ROWS_PER_REQ ))
  [ "${rows:-0}" = "0" ] && die "판 $tag 이 행을 하나도 안 만들었다 (#271)"
  [ "${rows:-0}" = "$want" ] || echo "  ⚠️ 행수가 기대와 다르다 — $rows / $want" >&2

  read -r r0 rr0 im0 ii0 <<< "$M0"
  read -r r1 rr1 im1 ii1 <<< "$m1"
  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "$tag" "$CUR_ARM" "$(( ${r1:-0} - ${r0:-0} ))" "$(( ${rr1:-0} - ${rr0:-0} ))" \
    "$(( ${im1:-0} - ${im0:-0} ))" "$(( ${ii1:-0} - ${ii0:-0} ))" \
    "$(( t1 - t0 ))" "${rows:--}" >> "$MECH_LOG"
  return 0
}

[ -f "$MECH_LOG" ] || printf \
  "tag\tarm\tbp_reads\tbp_read_reqs\tibuf_merges\tibuf_merged_inserts\tsecs\trows\n" > "$MECH_LOG"

learn_all_hosts
init_log

echo "=== 사전 확인 ==="
assert_mysql_reachable
assert_sessions_exist
assert_index present          # 시작 상태를 못박는다
echo

# 🔴 어떻게 끝나든 키를 되돌린다. 이 키는 프로덕션 계약(#188)이고, 실험이 스키마를
#    바꿔둔 채로 끝나면 다음 판·다음 사람이 **다른 시스템을 잰다.**
restore_index() {
  echo "=== 유니크 키를 되돌린다 ==="
  mysql_q "ALTER TABLE pose_data ADD UNIQUE KEY uk_pose_event
           (session_id, rep_number, timestamp_sec, created_at);" >/dev/null 2>&1
  local n
  n=$(mysql_q "SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema='shadowfit' AND table_name='pose_data'
                 AND index_name='uk_pose_event';")
  [ "${n:-0}" -gt 0 ] && echo "  복구 확인 (열 $n)" || echo "  🔴 복구 실패 — 손으로 확인할 것" >&2
}
trap 'restore_index; rmdir "$LOCK" 2>/dev/null' EXIT

DATA=/tmp/spread_$LEVEL.json
if ! rsh "$LOADER_PUB" "test -s $DATA"; then
  echo "=== 페이로드 생성 (레벨 $LEVEL) ==="
  mkdir -p "$OUT/_payload"
  "$PY" "$GEN" --sessions "$SESS_LO-$SESS_HI" --reps "$REPS" --out "$OUT/_payload/spread_$LEVEL.json" \
    || die "페이로드 생성 실패"
  scp "${SCP_OPTS[@]}" -q "$OUT/_payload/spread_$LEVEL.json" "ec2-user@$LOADER_PUB:$DATA" \
    || die "페이로드 전송 실패"
fi

PLANS=()

echo "──────── 버림판 (팔 with) ────────"
CUR_ARM=with; set_arm with
GHZ_DISCARD=1
run_ghz "discard_uk" "$DATA" "$C" "$N_REQ" || true
GHZ_DISCARD=0
sed -i "/^discard_uk\t/d" "$LOG" 2>/dev/null
echo "  (버림판은 표에서 제외했다)"
echo

i=0
for arm in "${ORDER[@]}"; do
  i=$(( i + 1 ))
  CUR_ARM=$arm
  tag="uk_${arm}_p$i"
  echo "──────── [$i/${#ORDER[@]}] $tag ────────"
  set_arm "$arm"
  PLANS+=("$tag")
  run_ghz "$tag" "$DATA" "$C" "$N_REQ" || true
done

echo
echo "=== 기제 ($MECH_LOG) ==="
cat "$MECH_LOG"

# 🔴 덤 — 인덱스가 실제로 얼마나 큰가 (#272 의 미검증: "행당 약 33바이트는 실측이 아니라
#    컬럼 폭에서 나온 산술이다"). 마지막 판이 with 팔이 아닐 수 있으므로 키를 되돌린 뒤 잰다.
#    비용 0 이고, 「쓰기가 느려진 만큼 무엇이 늘었나」의 다른 쪽 절반이다.
echo
echo "=== 인덱스 크기 실측 (#272 미검증 항목) ==="
mysql_q "ALTER TABLE pose_data ADD UNIQUE KEY uk_pose_event
         (session_id, rep_number, timestamp_sec, created_at);" >/dev/null 2>&1
mysql_q "SELECT index_name, stat_name, stat_value
         FROM mysql.innodb_index_stats
         WHERE database_name='shadowfit' AND table_name LIKE 'pose_data%'
           AND stat_name IN ('size','n_leaf_pages')
         ORDER BY index_name, stat_name;" | sed 's/^/  /'
echo "  (size·n_leaf_pages 는 **페이지 수**다. × 16KB 가 바이트다)"
mysql_q "SELECT 'pose_data 행수', COUNT(*) FROM pose_data;" | sed 's/^/  /'

finish ${#PLANS[@]}
