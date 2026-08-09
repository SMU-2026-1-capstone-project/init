#!/bin/bash
# ③ 커밋 횟수 스윕 — «커밋 횟수를 줄이면 fsync 천장이 올라가는가»
#
# 3차가 커밋의 **비용**(fsync 를 안 돌리게 함)을 낮춰 3.47배를 봤다. 이 스윕은 비용이 아니라
# **횟수**를 줄인다. 내구성 설정은 기본값(flush=1/sync_binlog=1) 그대로다 — 천장이 fsync 인
# 상태여야 이 레버가 보인다.
#
# 조작: 요청당 rep 수 N. 총 기록 행 수는 세 판이 같다.
#
#   N=1   --reps 25   요청당 5행    -n 15000    총 75,000행
#   N=5   --reps 125  요청당 25행   -n 3000     총 75,000행
#   N=10  --reps 250  요청당 50행   -n 1500     총 75,000행
#
# 🔴 --reps 는 반드시 다운샘플 윈도우(5)의 배수여야 한다. 배수가 아니면 배치 경계가 «어느
#    프레임이 남는가» 를 바꿔서 조작이 «커밋 횟수» 하나가 아니게 된다. 이 불변성은 테스트로
#    고정돼 있다 — PoseDataServiceTest.downsample_isBatchInvariant_whenFramesPerRepIsMultipleOfWindow
#
# 판정은 셋을 같이 본다 (설계 §3-2). 하나만 보면 아래 교란을 못 가른다:
#   1. rows/sec       레버의 크기
#   2. fsync/초        고정이면 «커밋이 천장» 확정
#   3. p99(요청 단위)  N 배 커진 요청의 지연 — rows/s 가 올라도 개별 요청은 느려질 수 있다
#
# ⚠️ 교란: 요청 수가 1/N 이 되면 요청당 existsById SELECT 와 UPDATE exercise_sessions 도
#    같이 1/N 이 된다. **처리량만으로는 fsync 에 귀속할 수 없다** — 그래서 커밋·fsync 카운터를
#    판마다 직접 찍는다(_rig.sh 의 counters()).
#
# 설계: docs/decisions/commit-count-and-mysql-metrics.md §3-2
# 사용:
#   export PEM=... DB_PUB=... APP_PUB=... LOADER_PUB=... OBS_PUB=... DB_PRIV=... APP_PRIV=...
#   export OUT=/path/to/out
#   ./commit_sweep.sh

set -uo pipefail
cd "$(dirname "$0")"

OUT="${OUT:?OUT 미설정}"
LOG="$OUT/commit.tsv"
SESS_LO=901; SESS_HI=1000   # 다세션 페이로드 범위 (단일 핫세션 아티팩트 제거)
C=100; POOL=20

source ./_rig.sh

learn_all_hosts
init_log

echo "=== ③ 커밋 횟수 스윕 (c=$C, pool=$POOL, 내구성 기본값) ==="

# 내구성을 명시적으로 기본값에 고정한다. ④ 를 먼저 돌렸다면 완화 상태가 남아 있을 수
# 있고, 그러면 이 스윕은 «fsync 가 천장이 아닌 상태» 를 재게 된다 — 레버가 안 보인다.
set_durability 1 1

restart_backend $POOL || die "백엔드 기동 실패 — 스윕을 시작할 수 없다"

# tag  data-file            요청당행  -n
PLANS=(
  "n1  /tmp/batch_n1.json   5   15000"
  "n5  /tmp/batch_n5.json   25  3000"
  "n10 /tmp/batch_n10.json  50  1500"
)

for plan in "${PLANS[@]}"; do
  read -r tag data rows n <<< "$plan"
  echo "--- $tag (요청당 ${rows}행, -n $n) ---"
  ROWS_PER_REQ=$rows
  run_ghz "$tag" "$data" "$C" "$n" || true
done

finish ${#PLANS[@]}