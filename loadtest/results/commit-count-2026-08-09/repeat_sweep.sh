#!/bin/bash
# ③ 재측정 — 반복 · 순서 회전 설계
#
# ─────────────────────────────────────────────────────────────────────────
# 왜 다시 재나: 초판(commit_sweep.sh)이 «판 순서» 와 «N» 을 분리하지 못했다
#
#   정순  n1 2,065 → n5 2,435 → n10 2,720   rows/s   («N 클수록 높다», +32%)
#   역순  n10 2,705 → n5 3,332 → n1 3,672   rows/s   («N 작을수록 높다», +36%)
#
# **방향이 정반대다.** 두 스윕 모두 «나중 판일수록 높다» 로 설명되므로, 초판이 관측한
# +32% 는 커밋 횟수의 효과가 아니라 **판 순서의 효과**였다. 유력한 원인은 버퍼풀 —
# `pose_data` 가 1,298MB 인데 버퍼풀은 768MB 라 전체가 안 들어가고, 초판은 시딩 직후
# 첫 스윕이라 가장 차가웠다.
#
# 초판 설계의 결함은 **반복이 없었다는 것**이다. 팔마다 1판씩이면 팔 효과와 순서 효과가
# 같은 축에 겹쳐서 원리적으로 분리가 안 된다. 그걸 실행 전에 못 봤다.
#
# 이번 설계:
#   ① **버림판 1회** — 차가운 구간을 측정에서 빼낸다. 결과에 안 넣는다
#   ② **3라운드 × 3팔, 라운드마다 순서 회전**
#        R1  n1  n5  n10
#        R2  n5  n10 n1
#        R3  n10 n1  n5
#      각 팔이 판 위치 1·2·3 에 정확히 한 번씩 온다 → 순서 효과가 팔에 균등 배분된다
#      (라틴 방격. 팔 효과와 순서 효과를 직교시키는 최소 설계다)
#   ③ 백엔드를 **판마다 재기동하지 않는다** — JIT 재워밍이 새 변동을 만든다. 조작 변수는
#      페이로드뿐이므로 재기동할 이유도 없다
#
# ⚠️ 이 설계로도 **드리프트가 단조라면** 완전히는 안 걷힌다. 라운드 번호를 태그에 남기니
#    (r1/r2/r3) 사후에 라운드별 평균으로 방향을 확인할 수 있다.
#
# 사용: commit_sweep.sh 와 같은 환경변수
# ─────────────────────────────────────────────────────────────────────────

set -uo pipefail
cd "$(dirname "$0")"

OUT="${OUT:?OUT 미설정}"
LOG="$OUT/repeat.tsv"
SESS_LO=901; SESS_HI=1000
C=100; POOL=20

source ./_rig.sh

learn_all_hosts
init_log

declare -A DATA=( [n1]=/tmp/batch_n1.json [n5]=/tmp/batch_n5.json [n10]=/tmp/batch_n10.json )
declare -A ROWS=( [n1]=5  [n5]=25  [n10]=50 )
declare -A NREQ=( [n1]=15000 [n5]=3000 [n10]=1500 )

echo "=== ③ 재측정 (c=$C, pool=$POOL, 내구성 기본값) ==="
set_durability 1 1
restart_backend $POOL || die "백엔드 기동 실패"

# ① 버림판 — 결과에 안 넣는다. LOG 를 잠깐 /dev/null 로 돌려 표를 오염시키지 않는다.
echo "--- 버림판 (차가운 구간 제거, 표에 안 들어감) ---"
_real_log="$LOG"; LOG=/dev/null
ROWS_PER_REQ=${ROWS[n1]}
run_ghz "discard" "${DATA[n1]}" "$C" "${NREQ[n1]}" || echo "  (버림판 실패 — 무시하고 진행)"
LOG="$_real_log"
rm -f "$OUT/discard.json"

# ② 라틴 방격 3라운드
PLANS=()
run_round() {  # $1=라운드번호, $2.. = 팔 순서
  local r=$1; shift
  for arm in "$@"; do
    local tag="r$r-$arm"
    echo "--- $tag ---"
    ROWS_PER_REQ=${ROWS[$arm]}
    PLANS+=("$tag")
    run_ghz "$tag" "${DATA[$arm]}" "$C" "${NREQ[$arm]}" || true
  done
}
run_round 1 n1 n5 n10
run_round 2 n5 n10 n1
run_round 3 n10 n1 n5

finish ${#PLANS[@]}

echo
echo "=== 팔별 요약 (라운드 평균) ==="
python - "$LOG" <<'PY'
import sys, collections
rows = collections.defaultdict(list)
with open(sys.argv[1], encoding='utf-8') as f:
    next(f)
    for line in f:
        p = line.rstrip('\n').split('\t')
        if len(p) < 12 or p[1] == 'FAIL':
            continue
        arm = p[0].split('-', 1)[1]
        rows[arm].append((float(p[2]), int(p[5]), int(p[8]), int(p[9])))
print(f"{'팔':>4}  {'판수':>3}  {'rows/s 평균':>11}  {'최소~최대':>15}  {'p99 평균':>9}  {'fsync/커밋':>10}")
for arm in ('n1', 'n5', 'n10'):
    v = rows.get(arm) or []
    if not v:
        print(f"{arm:>4}  측정 없음"); continue
    rs = [x[0] for x in v]; p99 = [x[1] for x in v]
    ratio = sum(x[3] for x in v) / max(sum(x[2] for x in v), 1)
    print(f"{arm:>4}  {len(v):>3}  {sum(rs)/len(rs):>11.0f}  "
          f"{min(rs):>6.0f}~{max(rs):<8.0f}  {sum(p99)/len(p99):>9.0f}  {ratio:>10.2f}")
print("\n⚠️ 판 사이 변동이 팔 사이 차이보다 크면 «레버 크기» 를 말할 수 없다 — 최소~최대를 먼저 볼 것.")
PY
