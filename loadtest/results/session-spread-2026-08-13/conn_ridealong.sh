#!/bin/bash
# 從 — 「커넥션 수가 정말 무의미한가」 (네 축 심화 §3-2)
#
# ─────────────────────────────────────────────────────────────────────────
# 무엇이 열려 있나
#
# 3차(2026-08-08)가 `--connections` 를 1 → 4 → 16 으로 흔들었더니 **230.2 → 208.0 → 210.9**
# 였다. 16배로 늘려도 안 움직이거나 약간 낮다. 그 값은 지금 «부하기가 천장의 범인이 아니다»
# 라는 **반증 근거로만** 쓰이고 있다.
#
# 그런데 답 안 한 것이 남아 있다:
#
#   ㉠ HTTP/2 멀티플렉싱이 실제로 작동해서 커넥션 수가 **무의미한** 것인가
#   ㉡ 다른 병목(커밋 fsync)에 **가려져서** 무의미해 보이는 것인가
#
# 3차의 스윕은 **fsync 가 천장이던 조건**에서 돌았다. 천장을 안 건드리는 레버는 무엇을
# 흔들어도 안 움직인다 — 그래서 ㉠과 ㉡이 같은 표를 낸다.
#
# 가르는 법: **내구성을 완화한 조건에서 같은 스윕을 다시 돌린다.** 가려져 있던 것이라면
# 거기서 커넥션 효과가 드러나야 한다.
#
# 반증 조건: **완화 조건에서도 커넥션 수가 처리량을 안 바꾸면 「가려져 있었다」가 틀린 것**
# 이고, 그때 비로소 ㉠(멀티플렉싱이 실제로 일한다)이 근거를 얻는다.
# ─────────────────────────────────────────────────────────────────────────
#
# ⚠️ **내구성 완화는 측정 조건이지 채택이 아니다.** 3.47배는 데이터 안전을 판 대가이고
#    이미 «미채택» 으로 닫혀 있다. 이 스크립트는 그 결정을 다시 열지 않는다 — 끝나면
#    기본값으로 되돌리고, 되돌아갔는지 확인까지 한다.
#
# 🔴 **세션 분산도 스윕(sessions_sweep.sh) 뒤에 돌린다.** 이쪽이 내구성을 흔들기 때문에,
#    순서가 뒤집히면 본 스윕이 «기본 내구성» 이라고 믿으며 완화 상태를 잰다.
#
# 🔴 3차의 230→211 은 **단일 핫세션 페이로드**에서 나온 값이다. 이 라운드는 100세션으로
#    돈다 — **그 선을 잇는 것이 아니라**, 이 라운드 안에서 «기본 ↔ 완화» 를 짝지어 답한다.
#    3차 숫자와 나란히 놓지 말 것.
#
# 🔴 2026-08-17 (#271): 페이로드가 ghz 템플릿이 됐다 — 본 스윕과 같은 파일을 쓰므로 같이 바뀐다.
#    `--connections` 는 이 스윕의 조작 변수이고, `GHZ_EXTRA` 가 워밍업에도 걸린다는 규약은 그대로다.
#
# 설계: docs/decisions/session-spread-sweep.md §4-4 · docs/decisions/loadtest-payload-uniqueness.md
# 사용: sessions_sweep.sh 와 같은 환경변수 (OUT 은 같은 디렉터리를 준다)

set -uo pipefail
cd "$(dirname "$0")"

SESS_LO=901
LEVEL=${LEVEL:-50}                   # 본 스윕의 최고 레벨과 같은 무대에서 본다(2026-08-17 격자 변경)
SESS_HI=$(( SESS_LO + LEVEL - 1 ))   # reset_rows 범위
C=${C:-100}
N_REQ=${N_REQ:-30000}
REPS=${REPS:-25}
# 본 스윕과 같은 유도식 — 상수로 두면 `REPS` 를 덮었을 때 `rows_s` 가 조용히 틀린다(#273 ②)
DOWNSAMPLE_WINDOW=${DOWNSAMPLE_WINDOW:-5}     # 출처: PoseDataService.java:58
ROWS_PER_REQ=$(( (REPS + DOWNSAMPLE_WINDOW - 1) / DOWNSAMPLE_WINDOW ))
CONNS=(1 4 16)
GEN=../../ghz/gen_batch_multi.py
PY=${PY:-python}

# 판 배치 — 커넥션 3수준 × 내구성 2수준, **위치가 균형**이 되게 섞는다.
#   위치: 1     2      3     4      5     6
#         d-c1  r-c16  d-c4  r-c4   d-c16 r-c1
# 커넥션별 평균 위치가 (1+6)/2 = (3+4)/2 = (2+5)/2 로 같다. 블록으로 몰아 돌리면
# «뒤에 놓인 팔이 유리/불리» 가 커넥션 효과로 위장한다(4차가 그걸로 부호를 잘못 냈다).
ORDER=("default:1" "relaxed:16" "default:4" "relaxed:4" "default:16" "relaxed:1")

if [ "${PLAN_ONLY:-0}" = "1" ]; then
  echo "=== 從 커넥션 스윕 판 배치 (버림판 1 + 본판 ${#ORDER[@]}) ==="
  echo "  discard  기본 내구성 · --connections 4"
  i=0
  for e in "${ORDER[@]}"; do
    i=$(( i + 1 )); printf "  [%s] %s\t--connections %s\n" "$i" "${e%%:*}" "${e##*:}"
  done
  echo
  echo "  무대: 레벨 ${LEVEL}세션 · c=$C · -n $N_REQ"
  exit 0
fi

OUT="${OUT:?OUT 미설정}"
LOG="$OUT/conn.tsv"

source ./../commit-count-2026-08-09/_rig.sh

learn_all_hosts
init_log

echo "=== 사전 확인 ==="
assert_mysql_reachable
assert_sessions_exist   # 공통부에 있다 — 단독 실행돼도 그물이 선다 (#273 ③)
echo

# 🔴 «보낸 요청이 실제로 행을 만들었는가» — 본 스윕과 같은 그물(#271).
#    이쪽은 관측 채널이 없어 훅을 안 쓰던 자리인데, 이 확인만은 본 스윕과 같아야 한다.
#    멈추는 것은 0 하나다(임계값이 아니라 «측정 불성립» 의 정의).
round_end_hook() {  # $1=태그 $2=t0 $3=t1 — reset_rows 보다 먼저 돈다
  local rows want
  rows=$(mysql_q "SELECT COUNT(*) FROM pose_data WHERE session_id BETWEEN $SESS_LO AND $SESS_HI;")
  want=$(( N_REQ * ROWS_PER_REQ ))
  [ "${rows:-0}" = "0" ] && die "판 $1 이 행을 하나도 안 만들었다 — 요청은 갔는데 저장이 안 됐다 (#271)"
  [ "${rows:-0}" = "$want" ] || echo "  ⚠️ 행수가 기대와 다르다 — $rows / $want" >&2
  return 0
}

# 🔴 어떻게 끝나든 완화 상태를 남기지 않는다. 다음 실험이 그걸 모른 채 재면 3.47배만큼
#    틀린 결론이 나온다. 4차 pool 스윕이 같은 이유로 같은 trap 을 갖고 있다.
restore_default_durability() {
  echo "=== 내구성을 기본값으로 되돌린다 ==="
  set_durability 1 1
}
trap restore_default_durability EXIT

# 페이로드 — 본 스윕이 이미 올려뒀으면 그대로 쓴다.
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

echo "──────── 버림판 (기본 내구성 · --connections 4) ────────"
set_durability 1 1
GHZ_DISCARD=1                                                   # 실패해도 집계 밖 (#273 ①)
GHZ_EXTRA="--connections 4" run_ghz "discard_conn" "$DATA" "$C" "$N_REQ" || true
GHZ_DISCARD=0
sed -i "/^discard_conn\t/d" "$LOG" 2>/dev/null
echo "  (버림판은 표에서 제외했다)"
echo

i=0
for e in "${ORDER[@]}"; do
  dur=${e%%:*}; conn=${e##*:}
  i=$(( i + 1 ))
  tag="${dur}-conn$conn"
  echo "──────── [$i/${#ORDER[@]}] $tag ────────"
  case $dur in
    default) set_durability 1 1 ;;
    relaxed) set_durability 2 0 ;;
  esac
  PLANS+=("$tag")
  GHZ_EXTRA="--connections $conn" run_ghz "$tag" "$DATA" "$C" "$N_REQ" || true
done

finish ${#PLANS[@]}
