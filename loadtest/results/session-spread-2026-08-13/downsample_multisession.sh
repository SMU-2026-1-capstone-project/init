#!/bin/bash
# P2 — 「다운샘플 1.7배」를 다세션에서 다시 잰다
#
# ─────────────────────────────────────────────────────────────────────────
# 무엇이 열려 있나
#
# `one-pager.md` 의 정본 수치 **「다운샘플 R=25→5 가 RPS 1.7배 · p99 4.9배」** 는
# 2026-08-08 분리배포 라운드에서 나왔는데, **조건이 단일 핫세션**이었다
# (`batch.json`, session 801 하나에 전부).
#
# 그 조건이 왜 위험한가: **바로 그 조건이 fsync 3.47배를 1.03배로 무너뜨렸다.** 단일
# 세션은 인덱스 리프 페이지 래치와 redo 커밋이 직렬화돼 가짜 천장을 만든다 — 그 천장 아래서는
# 어떤 레버도 실제보다 크거나 작게 보인다. 그래서 「다세션에서 재측정한 적 없다」가
# 문서에 그대로 붙어 있다(AWS-RIDE-ALONG §1 P2).
#
# 이 판은 그 배수를 **다세션 페이로드**로 다시 잰다.
# ─────────────────────────────────────────────────────────────────────────
#
# 팔은 둘. 조작 변수는 **다운샘플 창** 하나다:
#   A(w5) — `pose-data.downsample-window=5`  (현재 채택값. 요청 25프레임 → 저장 5행)
#   B(w1) — `pose-data.downsample-window=1`  (옛 코드 상당. 25프레임 → 저장 25행)
#
# 🔴 **팔마다 저장 행수가 다르다. 그게 조작 변수다.**
#    그래서 이 판은 RPS 만 보면 안 된다 — `rows/s`(저장 처리량)와 `RPS`(요청 처리량)가
#    서로 다른 방향을 가리킬 수 있고, §5-1(4) 의 «수확체감» 이 바로 그 이야기였다.
#    행 그물도 팔별 기대값(요청 × 5 또는 × 25)으로 잡는다.
#
# 🔴 **재빌드가 선행이다.** 창이 상수였을 때는 팔을 바꾸려면 이미지를 다시 만들어야 했고
#    (그게 P2 를 막고 있었다), 2026-08-18 에 설정으로 뺐다. 이 스크립트는 **설정만** 바꾼다.
#
# 판 배치 — ABBA/BAAB 위치 균형 8판 + 버림판 1.
#
# 사용: sessions_sweep.sh 와 같은 환경변수

set -uo pipefail
cd "$(dirname "$0")"

SESS_LO=901
LEVEL=${LEVEL:-20}
SESS_HI=$(( SESS_LO + LEVEL - 1 ))
C=${C:-100}
N_REQ=${N_REQ:-10000}     # 팔 B 는 요청당 25행이라 판이 5배 무겁다 — 그만큼 줄인다
REPS=${REPS:-25}
GEN=../../ghz/gen_batch_multi.py
PY=${PY:-python}
WORKDIR=${WORKDIR:-/root/init}

ORDER=(w5 w1 w1 w5 w1 w5 w5 w1)

rows_per_req() { case "$1" in w5) echo 5 ;; w1) echo "$REPS" ;; esac; }

if [ "${PLAN_ONLY:-0}" = "1" ]; then
  echo "=== P2 다운샘플 다세션 — 판 배치 (버림판 1 + 본판 ${#ORDER[@]}) ==="
  echo "  discard  팔 w5"
  i=0; for e in "${ORDER[@]}"; do i=$(( i + 1 )); printf "  [%s] %s (요청당 %s행)\n" "$i" "$e" "$(rows_per_req "$e")"; done
  echo
  echo "  무대: 레벨 ${LEVEL}세션 · c=$C · -n $N_REQ"
  echo "  저장 행/판: w5=$(( N_REQ * 5 )) · w1=$(( N_REQ * REPS ))  ← 이 차이가 조작 변수다"
  exit 0
fi

OUT="${OUT:?OUT 미설정}"
LOG="$OUT/downsample.tsv"
MECH_LOG="$OUT/downsample_mech.tsv"

LOCK="$OUT/.downsample.lock"
mkdir -p "$OUT"
mkdir "$LOCK" 2>/dev/null || { echo "🔴 이 OUT 에서 이미 돌고 있다: $LOCK" >&2; exit 1; }

source ./../commit-count-2026-08-09/_rig.sh

switch_arm() {  # $1 = w5|w1
  local w=5
  [ "$1" = "w1" ] && w=1
  echo "  백엔드 재기동 — downsample-window=$w"
  rsh "$APP_PUB" "sudo bash -c 'cat > $WORKDIR/docker-compose.override.yml <<YML
services:
  shadowfit-backend:
    environment:
      POSE_DATA_DOWNSAMPLE_WINDOW: \"$w\"
YML
cd $WORKDIR && docker compose up -d --force-recreate shadowfit-backend >/dev/null 2>&1'" \
    || die "백엔드 재기동 실패 (팔 $1)"
  local up=0 i
  for i in $(seq 1 60); do
    rsh "$APP_PUB" "curl -sf localhost:9090/actuator/health >/dev/null 2>&1" && { up=1; break; }
    sleep 2
  done
  [ "$up" = "1" ] || die "백엔드가 120초 안에 health 를 안 준다 (팔 $1)"
}

# 🔴 «설정했다» 와 «설정됐다» 는 다르다. 요청 하나를 쏘고 **실제로 몇 행이 들어갔는지** 센다.
#    w5 면 5행, w1 이면 25행 — 구조적으로 갈리는 값이라 임계값이 아니다.
assert_arm() {  # $1 = w5|w1
  local want; want=$(rows_per_req "$1")
  mysql_q "DELETE FROM pose_data WHERE session_id BETWEEN $SESS_LO AND $SESS_HI;" >/dev/null
  rsh "$LOADER_PUB" "$GHZ --insecure --call ExerciseService.SavePoseDataBatch \
      --metadata-file /tmp/meta.json --data-file $DATA -c 1 -n 1 $APP_PRIV:6565 >/dev/null 2>&1" \
    || die "단언용 프로브 요청이 실패했다 (팔 $1)"
  local got
  got=$(mysql_q "SELECT COUNT(*) FROM pose_data WHERE session_id BETWEEN $SESS_LO AND $SESS_HI;" | tr -d '[:space:]')
  [ "${got:-0}" = "$want" ] \
    || die "팔이 안 물렸다 — $1 이면 요청 하나가 $want 행이어야 하는데 $got 행이다"
  echo "  팔 확인: $1 · 요청 1건 → $got 행"
  mysql_q "DELETE FROM pose_data WHERE session_id BETWEEN $SESS_LO AND $SESS_HI;" >/dev/null
}

CUR_ARM=""
round_end_hook() {   # $1=태그 $2=t0 $3=t1
  local tag=$1 t0=$2 t1=$3 rows want
  want=$(( N_REQ * $(rows_per_req "$CUR_ARM") ))
  rows=$(mysql_q "SELECT COUNT(*) FROM pose_data WHERE session_id BETWEEN $SESS_LO AND $SESS_HI;")
  [ "${rows:-0}" = "0" ] && die "판 $tag 이 행을 하나도 안 만들었다 (#271)"
  [ "${rows:-0}" = "$want" ] || echo "  ⚠️ 행수가 기대와 다르다 — $rows / $want" >&2
  printf "%s\t%s\t%s\t%s\t%s\n" "$tag" "$CUR_ARM" "${rows:--}" "$want" "$(( t1 - t0 ))" >> "$MECH_LOG"
  return 0
}

[ -f "$MECH_LOG" ] || printf "tag\tarm\trows_inserted\trows_expected\tsecs\n" > "$MECH_LOG"

learn_all_hosts
init_log
echo "=== 사전 확인 ==="
assert_mysql_reachable
assert_sessions_exist
echo

restore_default() {
  echo "=== 백엔드를 기본 설정으로 되돌린다 (window=5) ==="
  rsh "$APP_PUB" "sudo bash -c 'rm -f $WORKDIR/docker-compose.override.yml; \
    cd $WORKDIR && docker compose up -d --force-recreate shadowfit-backend >/dev/null 2>&1'" \
    && echo "  오버라이드 제거 · 재기동" || echo "  🔴 복구 실패 — 손으로 확인할 것" >&2
}
trap 'restore_default; rmdir "$LOCK" 2>/dev/null' EXIT

DATA=/tmp/spread_$LEVEL.json
rsh "$LOADER_PUB" "test -s $DATA" || {
  echo "=== 페이로드 생성 (레벨 $LEVEL) ==="
  mkdir -p "$OUT/_payload"
  "$PY" "$GEN" --sessions "$SESS_LO-$SESS_HI" --reps "$REPS" --out "$OUT/_payload/spread_$LEVEL.json" \
    || die "페이로드 생성 실패"
  scp "${SCP_OPTS[@]}" -q "$OUT/_payload/spread_$LEVEL.json" "ec2-user@$LOADER_PUB:$DATA" \
    || die "페이로드 전송 실패"
}

PLANS=()

echo "──────── 버림판 (팔 w5) ────────"
CUR_ARM=w5; switch_arm w5; assert_arm w5
ROWS_PER_REQ=5
GHZ_DISCARD=1
run_ghz "discard_ds" "$DATA" "$C" "$N_REQ" || true
GHZ_DISCARD=0
sed -i "/^discard_ds\t/d" "$LOG" 2>/dev/null
echo "  (버림판은 표에서 제외했다)"
echo

i=0
for arm in "${ORDER[@]}"; do
  i=$(( i + 1 ))
  CUR_ARM=$arm
  # 🔴 `rows/s` 계산이 이 값을 읽는다. 팔마다 바꿔주지 않으면 팔 B 의 저장 처리량이
  #    5배 낮게 찍힌다 — 그러면 이 실험의 결론이 통째로 뒤집힌다.
  ROWS_PER_REQ=$(rows_per_req "$arm")
  tag="ds_${arm}_p$i"
  echo "──────── [$i/${#ORDER[@]}] $tag (요청당 ${ROWS_PER_REQ}행) ────────"
  switch_arm "$arm"
  assert_arm "$arm"
  PLANS+=("$tag")
  run_ghz "$tag" "$DATA" "$C" "$N_REQ" || true
done

echo
echo "=== 행 그물 ($MECH_LOG) ==="
cat "$MECH_LOG"
echo
echo "🔴 읽는 법: RPS(요청 처리량)와 rows_s(저장 처리량)를 **따로** 본다."
echo "   정본 «1.7배» 는 RPS 쪽 배수다. rows_s 는 팔 B 가 5배 많은 행을 넣으므로 방향이 다를 수 있고,"
echo "   그 둘이 갈리는 자리가 §5-1(4) 의 «수확체감» 이 말하던 것이다."

finish ${#PLANS[@]}
