#!/bin/bash
# 從 — 「`rewriteBatchedStatements` 가 지금 경로에 얼마를 주나」 (#221)
#
# ─────────────────────────────────────────────────────────────────────────
# 무엇이 열려 있나
#
# `application.yml:17` 이 JDBC URL 에 `rewriteBatchedStatements=true` 를 **박아두고**
# 주석에 *"JDBC 드라이버가 batch INSERT 를 multi-row SQL 한 방으로 재작성 (부하테스트 §7.5 개선)"*
# 이라고 적었다. 그런데 **그 기여분을 잰 적이 없다.**
#
# #221 이 지적한 대로, 「효과 없음」이라고 적힌 옛 판정은 **JPA saveAll 경로** 얘기였다.
# 지금 쓰기 경로는 `PoseDataService.savePoseDataBatch` 의 `JdbcTemplate.batchUpdate` 이고,
# 이 플래그가 켜져 있으면 드라이버가 **배치 하나를 multi-values INSERT 한 문장**으로 보낸다.
# 즉 이 라운드가 내는 모든 숫자에 이 플래그의 몫이 섞여 있는데 **그 크기를 모른다.**
# ─────────────────────────────────────────────────────────────────────────
#
# 팔은 둘. 조작 변수는 **URL 파라미터 하나**다:
#   A(rewrite)   — `rewriteBatchedStatements=true`  (지금 기본값)
#   B(norewrite) — `rewriteBatchedStatements=false`
#
# 🔴 **«설정했다» 와 «설정됐다» 는 다르다.** 이 저장소는 그걸로 두 번 데였다(풀 크기 #253, 캡).
#    그런데 이번엔 확인 경로가 막혀 있다 — `/actuator/env` 는 401 이고 관리 포트는
#    prometheus 스크레이프만 연다. 그래서 **결과로 단언한다**:
#
#      요청 하나를 쏘고 `Com_insert` 델타를 센다.
#        · rewrite=true  → 배치가 **한 문장**이 되므로 델타 = **1**
#        · rewrite=false → 행마다 한 문장이므로 델타 = **ROWS_PER_REQ (=5)**
#
#    이건 임계값이 아니라 **구조적으로 갈리는 값**이다. 둘 중 어느 쪽도 아니면 멈춘다.
#    (그리고 이 카운터는 그대로 기제 채널이 된다 — 「왜 빨라졌나」의 답이 여기 있다.)
#
# 🔴 팔 전환은 **컨테이너 재기동**이다. compose 오버라이드 파일을 놓고 백엔드만 다시 띄운다.
#    끝나면 오버라이드를 지우고 기본값으로 되돌린다 — 남겨두면 다음 사람이 다른 설정을 잰다.
#
# 판 배치 — ABBA/BAAB 위치 균형 8판(각 팔 평균 위치 4.5) + 버림판 1.
#
# 설계 근거: #221 · docs/decisions/session-spread-sweep.md
# 사용: sessions_sweep.sh 와 같은 환경변수 (OUT 은 같은 디렉터리를 준다)

set -uo pipefail
cd "$(dirname "$0")"

SESS_LO=901
LEVEL=${LEVEL:-20}
SESS_HI=$(( SESS_LO + LEVEL - 1 ))
C=${C:-100}
N_REQ=${N_REQ:-30000}
REPS=${REPS:-25}
DOWNSAMPLE_WINDOW=${DOWNSAMPLE_WINDOW:-5}
ROWS_PER_REQ=$(( (REPS + DOWNSAMPLE_WINDOW - 1) / DOWNSAMPLE_WINDOW ))
GEN=../../ghz/gen_batch_multi.py
PY=${PY:-python}
WORKDIR=${WORKDIR:-/root/init}

ORDER=(rewrite norewrite norewrite rewrite norewrite rewrite rewrite norewrite)

if [ "${PLAN_ONLY:-0}" = "1" ]; then
  echo "=== 從 rewriteBatchedStatements — 판 배치 (버림판 1 + 본판 ${#ORDER[@]}) ==="
  echo "  discard  팔 rewrite"
  i=0; for e in "${ORDER[@]}"; do i=$(( i + 1 )); printf "  [%s] %s\n" "$i" "$e"; done
  echo
  echo "  무대: 레벨 ${LEVEL}세션 · c=$C · -n $N_REQ · 저장 행/판 $(( N_REQ * ROWS_PER_REQ ))"
  echo "  단언: Com_insert 델타가 rewrite=1 / norewrite=$ROWS_PER_REQ 인지 판마다 확인"
  exit 0
fi

OUT="${OUT:?OUT 미설정}"
LOG="$OUT/rewrite.tsv"
MECH_LOG="$OUT/rewrite_mech.tsv"

LOCK="$OUT/.rewrite.lock"
mkdir -p "$OUT"
mkdir "$LOCK" 2>/dev/null || { echo "🔴 이 OUT 에서 이미 돌고 있다: $LOCK" >&2; exit 1; }

source ./../commit-count-2026-08-09/_rig.sh

JDBC_BASE="jdbc:mysql://shadowfit-mysql:3306/shadowfit?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8&rewriteBatchedStatements="

com_insert() { mysql_q "SELECT VARIABLE_VALUE FROM performance_schema.global_status
                        WHERE VARIABLE_NAME='COM_INSERT';" | tr -d '[:space:]'; }

# ── 팔 전환 ──────────────────────────────────────────────────────────────
switch_arm() {  # $1 = rewrite|norewrite
  local flag=true
  [ "$1" = "norewrite" ] && flag=false
  echo "  백엔드 재기동 — rewriteBatchedStatements=$flag"
  rsh "$APP_PUB" "sudo bash -c 'cat > $WORKDIR/docker-compose.override.yml <<YML
services:
  shadowfit-backend:
    environment:
      SPRING_DATASOURCE_URL: \"${JDBC_BASE}${flag}\"
YML
cd $WORKDIR && docker compose up -d --force-recreate shadowfit-backend >/dev/null 2>&1'" \
    || die "백엔드 재기동 실패 (팔 $1)"

  local up=0 i
  for i in $(seq 1 60); do
    if rsh "$APP_PUB" "curl -sf localhost:9090/actuator/health >/dev/null 2>&1"; then up=1; break; fi
    sleep 2
  done
  [ "$up" = "1" ] || die "백엔드가 120초 안에 health 를 안 준다 (팔 $1)"
}

# 🔴 결과로 단언한다. 요청 하나를 쏘고 Com_insert 가 몇 늘었는지 본다.
#    구조적으로 1(rewrite) 또는 ROWS_PER_REQ(norewrite) 여야 한다 — 임계값이 아니다.
assert_arm() {  # $1 = rewrite|norewrite
  local want=1 c0 c1 d
  [ "$1" = "norewrite" ] && want=$ROWS_PER_REQ
  c0=$(com_insert)
  rsh "$LOADER_PUB" "$GHZ --insecure --call ExerciseService.SavePoseDataBatch \
      --metadata-file /tmp/meta.json --data-file $DATA -c 1 -n 1 $APP_PRIV:6565 >/dev/null 2>&1" \
    || die "단언용 프로브 요청이 실패했다 (팔 $1)"
  c1=$(com_insert)
  d=$(( ${c1:-0} - ${c0:-0} ))
  [ "$d" = "$want" ] \
    || die "팔이 안 물렸다 — $1 이면 Com_insert 델타가 $want 여야 하는데 $d 다.
   (rewrite=true 는 배치가 한 문장, false 는 행마다 한 문장이라 구조적으로 갈린다)"
  echo "  팔 확인: $1 · Com_insert 델타 $d"
  mysql_q "DELETE FROM pose_data WHERE session_id BETWEEN $SESS_LO AND $SESS_HI;" >/dev/null
}

CUR_ARM=""; CI0=""
round_begin_hook() { CI0=$(com_insert); }
round_end_hook() {   # $1=태그 $2=t0 $3=t1
  local tag=$1 t0=$2 t1=$3 ci1 rows want
  ci1=$(com_insert)
  rows=$(mysql_q "SELECT COUNT(*) FROM pose_data WHERE session_id BETWEEN $SESS_LO AND $SESS_HI;")
  want=$(( N_REQ * ROWS_PER_REQ ))
  [ "${rows:-0}" = "0" ] && die "판 $tag 이 행을 하나도 안 만들었다 (#271)"
  [ "${rows:-0}" = "$want" ] || echo "  ⚠️ 행수가 기대와 다르다 — $rows / $want" >&2
  printf "%s\t%s\t%s\t%s\t%s\n" \
    "$tag" "$CUR_ARM" "$(( ${ci1:-0} - ${CI0:-0} ))" "$(( t1 - t0 ))" "${rows:--}" >> "$MECH_LOG"
  return 0
}

[ -f "$MECH_LOG" ] || printf "tag\tarm\tcom_insert\tsecs\trows\n" > "$MECH_LOG"

learn_all_hosts
init_log

echo "=== 사전 확인 ==="
assert_mysql_reachable
assert_sessions_exist
echo

restore_default() {
  echo "=== 백엔드를 기본 설정으로 되돌린다 ==="
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

echo "──────── 버림판 (팔 rewrite) ────────"
CUR_ARM=rewrite; switch_arm rewrite; assert_arm rewrite
GHZ_DISCARD=1
run_ghz "discard_rw" "$DATA" "$C" "$N_REQ" || true
GHZ_DISCARD=0
sed -i "/^discard_rw\t/d" "$LOG" 2>/dev/null
echo "  (버림판은 표에서 제외했다)"
echo

i=0
for arm in "${ORDER[@]}"; do
  i=$(( i + 1 ))
  CUR_ARM=$arm
  tag="rw_${arm}_p$i"
  echo "──────── [$i/${#ORDER[@]}] $tag ────────"
  switch_arm "$arm"
  assert_arm "$arm"
  PLANS+=("$tag")
  run_ghz "$tag" "$DATA" "$C" "$N_REQ" || true
done

echo
echo "=== 기제 ($MECH_LOG) — Com_insert 가 팔을 그대로 보여준다 ==="
cat "$MECH_LOG"

finish ${#PLANS[@]}
