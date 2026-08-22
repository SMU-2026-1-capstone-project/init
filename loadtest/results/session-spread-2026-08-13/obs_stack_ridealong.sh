#!/bin/bash
# 從 — 「관측 스택을 같이 올리면 얼마를 더 먹나」 (Q5)
#
# ─────────────────────────────────────────────────────────────────────────
# 무엇이 열려 있나
#
# 2026-08-08 에 관측 스택(prometheus·grafana·mysqld-exporter)을 **별도 박스로 분리했다.**
# 분리한 이유가 정확히 «같이 두면 측정 대상을 갉는다» 인데, **그 비용을 숫자로 적은 적이 없다.**
#
# 이 항목(Q5)은 두 라운드 연속으로 밀렸다:
#   ai-coresidency-capacity.md:422  "팔 D(관측 스택)를 넣을지 — 제외(08-16). 판이 25% 는다"
#   ai-coresidency-capacity.md:667  "팔 D | 제외 | Q5(관측 스택 비용)는 다음으로"
# 「다음 라운드로」가 두 번 적혔고 그 다음 라운드가 오늘이다.
# ─────────────────────────────────────────────────────────────────────────
#
# 🔴 **원 설계의 Q5 와 같은 질문이 아니다. 이름을 정확히 적는다.**
#    팔 D 는 **AI 부하** 기준이었다(동거 용량 라운드). 이 판의 부하는 **쓰기 경로**다.
#    그래서 이 판이 답하는 것은:
#
#      «관측 스택이 **쓰기 처리량**을 얼마나 깎나»
#
#    이고, 「AI 용량을 얼마나 깎나」는 **여전히 미측정**이다. 결과를 인용할 때 이 구분을
#    잃으면 안 된다 — 두 부하는 병목이 다르다(쓰기는 fsync, AI 는 추론).
#    ⚠️ 그래도 값이 있는 이유: 이 판의 조건이 곧 **P5 자신의 조건**이다. P5 는 관측 스택
#       없이 돌았는데, 실배포는 어떤 형태로든 관측이 붙는다.
#
# 팔은 둘. 조작 변수는 **관측 스택의 유무** 하나다:
#   A(off) — 지금 상태 (mysql · backend · ai 만)
#   B(on)  — `--profile obs` 로 prometheus · grafana · mysqld-exporter 추가
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
N_REQ=${N_REQ:-30000}
REPS=${REPS:-25}
DOWNSAMPLE_WINDOW=${DOWNSAMPLE_WINDOW:-5}
ROWS_PER_REQ=$(( (REPS + DOWNSAMPLE_WINDOW - 1) / DOWNSAMPLE_WINDOW ))
GEN=../../ghz/gen_batch_multi.py
PY=${PY:-python}
WORKDIR=${WORKDIR:-/root/init}

ORDER=(off on on off on off off on)

if [ "${PLAN_ONLY:-0}" = "1" ]; then
  echo "=== 從 관측 스택 동거 비용 (Q5, 쓰기 경로판) — 판 배치 (버림판 1 + 본판 ${#ORDER[@]}) ==="
  echo "  discard  팔 off"
  i=0; for e in "${ORDER[@]}"; do i=$(( i + 1 )); printf "  [%s] obs %s\n" "$i" "$e"; done
  echo
  echo "  무대: 레벨 ${LEVEL}세션 · c=$C · -n $N_REQ"
  exit 0
fi

OUT="${OUT:?OUT 미설정}"
LOG="$OUT/obs.tsv"
MECH_LOG="$OUT/obs_mech.tsv"

LOCK="$OUT/.obs.lock"
mkdir -p "$OUT"
mkdir "$LOCK" 2>/dev/null || { echo "🔴 이 OUT 에서 이미 돌고 있다: $LOCK" >&2; exit 1; }

source ./../commit-count-2026-08-09/_rig.sh

OBS_CTNS="shadowfit-prometheus shadowfit-grafana shadowfit-mysqld-exporter"

obs_running() {  # 떠 있는 관측 컨테이너 수
  rsh "$APP_PUB" "sudo docker ps --format '{{.Names}}' | grep -c -E 'shadowfit-(prometheus|grafana|mysqld-exporter)'" \
    2>/dev/null | tr -d '[:space:]'
}

switch_arm() {  # $1 = off|on
  case "$1" in
    on)
      rsh "$APP_PUB" "sudo bash -c 'cd $WORKDIR && docker compose --profile obs up -d prometheus grafana mysqld-exporter >/dev/null 2>&1'" \
        || die "관측 스택 기동 실패" ;;
    off)
      rsh "$APP_PUB" "sudo bash -c 'cd $WORKDIR && docker compose --profile obs stop prometheus grafana mysqld-exporter >/dev/null 2>&1; docker rm -f $OBS_CTNS >/dev/null 2>&1' || true" ;;
  esac
  sleep 5
}

# 🔴 «올렸다» 와 «올라갔다» 는 다르다. 개수로 단언한다 — 3개 또는 0개, 구조적으로 갈린다.
#    그리고 팔 on 에서는 **실제로 스크레이프가 도는지**까지 본다. 컨테이너가 떠 있어도
#    타깃을 못 긁으면 «관측 스택이 일하는 조건» 이 아니다 — 그러면 비용도 가짜다.
assert_arm() {  # $1 = off|on
  local n; n=$(obs_running)
  case "$1" in
    off) [ "${n:-0}" = "0" ] || die "팔 off 인데 관측 컨테이너가 $n 개 떠 있다" ;;
    on)
      [ "${n:-0}" = "3" ] || die "팔 on 인데 관측 컨테이너가 $n 개다 (3 이어야 한다)"
      local up
      up=$(rsh "$APP_PUB" "curl -s 'localhost:9091/api/v1/targets?state=active' 2>/dev/null | grep -o '\"health\":\"up\"' | wc -l" 2>/dev/null | tr -d '[:space:]')
      [ "${up:-0}" -gt 0 ] \
        || echo "  ⚠️ prometheus 가 up 타깃을 0개로 보고한다 — 컨테이너는 떴지만 실제로 안 긁고 있을 수 있다" >&2
      echo "  팔 확인: obs on · 컨테이너 $n · up 타깃 ${up:-?}" ;;
  esac
  [ "$1" = "off" ] && echo "  팔 확인: obs off · 컨테이너 $n"
  return 0
}

CUR_ARM=""
round_end_hook() {   # $1=태그 $2=t0 $3=t1
  local tag=$1 t0=$2 t1=$3 rows want obs_cpu
  rows=$(mysql_q "SELECT COUNT(*) FROM pose_data WHERE session_id BETWEEN $SESS_LO AND $SESS_HI;")
  want=$(( N_REQ * ROWS_PER_REQ ))
  [ "${rows:-0}" = "0" ] && die "판 $tag 이 행을 하나도 안 만들었다 (#271)"
  [ "${rows:-0}" = "$want" ] || echo "  ⚠️ 행수가 기대와 다르다 — $rows / $want" >&2

  # 관측 스택이 실제로 먹은 CPU. 「깎였다」의 반대편 절반이라 같이 남긴다.
  obs_cpu=$(rsh "$APP_PUB" "sudo docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}' 2>/dev/null \
      | grep -E 'prometheus|grafana|mysqld-exporter' | awk '{gsub(/%/,\"\",\$2); s+=\$2} END{print s+0}'" \
      2>/dev/null | tr -d '[:space:]')
  printf "%s\t%s\t%s\t%s\t%s\n" "$tag" "$CUR_ARM" "${obs_cpu:--}" "$(( t1 - t0 ))" "${rows:--}" >> "$MECH_LOG"
  return 0
}

[ -f "$MECH_LOG" ] || printf "tag\tarm\tobs_cpu_pct\tsecs\trows\n" > "$MECH_LOG"

learn_all_hosts
init_log
echo "=== 사전 확인 ==="
assert_mysql_reachable
assert_sessions_exist
echo

# 🔴 어떻게 끝나든 관측 스택을 내린다. 다음 판이 «없는 조건» 이라 믿으며 있는 상태를 재면 안 된다.
trap 'echo "=== 관측 스택 정리 ==="; switch_arm off; rmdir "$LOCK" 2>/dev/null' EXIT

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

echo "──────── 버림판 (팔 off) ────────"
CUR_ARM=off; switch_arm off; assert_arm off
GHZ_DISCARD=1
run_ghz "discard_obs" "$DATA" "$C" "$N_REQ" || true
GHZ_DISCARD=0
sed -i "/^discard_obs\t/d" "$LOG" 2>/dev/null
echo "  (버림판은 표에서 제외했다)"
echo

i=0
for arm in "${ORDER[@]}"; do
  i=$(( i + 1 ))
  CUR_ARM=$arm
  tag="obs_${arm}_p$i"
  echo "──────── [$i/${#ORDER[@]}] $tag ────────"
  switch_arm "$arm"
  assert_arm "$arm"
  PLANS+=("$tag")
  run_ghz "$tag" "$DATA" "$C" "$N_REQ" || true
done

echo
echo "=== 기제 ($MECH_LOG) — 관측 스택이 먹은 CPU ==="
cat "$MECH_LOG"
echo
echo "🔴 인용 주의: 이 판은 «관측 스택이 **쓰기 처리량**을 얼마나 깎나» 에만 답한다."
echo "   원 설계의 Q5(AI 부하 기준, 팔 D)는 **여전히 미측정**이다 — 두 부하는 병목이 다르다."

finish ${#PLANS[@]}
