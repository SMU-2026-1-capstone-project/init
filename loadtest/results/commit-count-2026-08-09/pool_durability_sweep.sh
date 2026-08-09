#!/bin/bash
# ④ 내구성 완화 상태에서의 pool 스윕 — «2차의 «절벽 없음» 은 어떤 조건 아래의 문장이었나»
#
# 2차(2026-08-08 오전)는 pool 5~20 전 구간에서 처리량이 plateau 대비 97~106% 로 붙어
# «절벽이 없다» 로 닫았다. 저녁에 천장의 정체가 **커밋 fsync** 로 밝혀지면서 그 문장의
# 뜻이 좁아졌다 — 정확히는 **«fsync 가 천장이던 구간에서는»** 이다.
#
# fsync 를 천장에서 빼면(내구성 완화) 풀이 다시 병목으로 드러나는가. 그게 이 스윕이다.
#
# ⚠️ 완화 설정은 **진단용이지 권고가 아니다.** 채택은 별도 결정이고 이 스크립트는 그 결정을
#    하지 않는다. 그래서 스윕이 끝나면 기본값으로 되돌린다.
#
# 판정: plateau(pool=20) 대비 90% + 실패율 병기 (3차에서 확정된 기준 그대로).
#   🔴 90% 는 임의값이다. 이 사실은 결과 문서에도 같이 적는다 — 판정선을 정할 근거가 있어서
#      고른 값이 아니라 «어딘가에 선을 그어야 해서» 고른 값이다.
#
# 대조군: 같은 pool 사다리를 **기본 내구성**에서도 2점(5·20) 찍는다. «절벽이 완화 상태에서만
#         나타난다» 를 말하려면 반대편 점이 있어야 한다. 대조군이 없으면 이 스윕은 «완화
#         상태에서 절벽이 있다» 까지만 말할 수 있고, 그건 2차와 비교가 안 된다.
#
# 설계: docs/decisions/commit-count-and-mysql-metrics.md §3-3
# 사용: commit_sweep.sh 와 같은 환경변수

set -uo pipefail
cd "$(dirname "$0")"

OUT="${OUT:?OUT 미설정}"
LOG="$OUT/pool.tsv"
SESS_LO=901; SESS_HI=1000
C=100
DATA=/tmp/batch_n1.json     # N=1 — 3차와 같은 조건(요청당 1 rep)
ROWS_PER_REQ=5
N_REQ=15000

source ./_rig.sh

learn_all_hosts
init_log

restore_default_durability() {
  echo "=== 내구성을 기본값으로 되돌린다 ==="
  set_durability 1 1
}
# 스윕이 어떻게 끝나든(중단·실패·성공) 완화 상태를 남기지 않는다. 다음 실험이 그것을
# 모른 채 «기본값이겠거니» 하고 재면 3.47배만큼 틀린 결론이 나온다.
trap restore_default_durability EXIT

PLANS=()

echo "=== ④-1 내구성 완화 (flush=2, sync_binlog=0) 에서 pool 스윕 ==="
set_durability 2 0
for pool in 20 10 5 2; do
  echo "--- relaxed pool=$pool ---"
  PLANS+=("relaxed-p$pool")
  if restart_backend "$pool"; then
    run_ghz "relaxed-p$pool" "$DATA" "$C" "$N_REQ" || true
  else
    fail_row "relaxed-p$pool"; FAILED+=("relaxed-p$pool:기동")
  fi
done

echo
echo "=== ④-2 대조군 — 기본 내구성 (flush=1, sync_binlog=1) 에서 2점 ==="
set_durability 1 1
for pool in 20 5; do
  echo "--- default pool=$pool ---"
  PLANS+=("default-p$pool")
  if restart_backend "$pool"; then
    run_ghz "default-p$pool" "$DATA" "$C" "$N_REQ" || true
  else
    fail_row "default-p$pool"; FAILED+=("default-p$pool:기동")
  fi
done

finish ${#PLANS[@]}