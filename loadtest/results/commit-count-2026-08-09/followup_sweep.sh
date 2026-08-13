#!/bin/bash
# 후속 확인 2건 — ③·④ 를 돌리고 나서 **두 결과가 서로 안 맞아서** 추가한다.
#
# ─────────────────────────────────────────────────────────────────────────
# 무엇이 안 맞았나
#
# 1. 같은 조건이 재현되지 않았다
#      ③ n1          (c=100, pool=20, N=1, 기본 내구성) → 413.1 RPS
#      ④ default-p20 (완전히 같은 조건)                  → 649.4 RPS   ← 1.57배
#    ③ 은 라운드의 첫 스윕이고 ④ 는 그 뒤다. 버퍼풀·JIT 워밍업이 판 순서를 따라
#    누적됐다면, **③ 내부 비교(n1→n5→n10)도 같은 방향으로 오염된다** — n5·n10 의
#    이득 중 얼마가 커밋 감소이고 얼마가 워밍업인지 구분이 안 된다.
#
# 2. 3차의 «천장 = 커밋 fsync» 가 재현되지 않았다
#      3차: 내구성 완화로 231.6 → 803.1 (3.47배)
#      이번: default-p20 649.4 → relaxed-p20 668.6 (1.03배)
#    fsync/s 는 624 vs 30 으로 20배 차이인데 처리량이 3% 차이다. **이 조건에서
#    fsync 는 천장이 아니다.**
#
# 가설: 3차는 **단일 핫세션**(`batch.json`, session_id=801)을 썼고 이번은 다세션
#       (901~1000)이다. 모든 INSERT 가 한 인덱스 리프에 몰리면 래치·커밋이 직렬화돼
#       그룹 커밋이 안 먹고, 그때는 커밋마다 fsync 가 실제로 돈다. 이번 라운드의
#       `fsync/커밋` 은 0.16 — 커밋 6개당 fsync 1회로 이미 상각돼 있었다.
#
#       📌 `gen_batch_multi.py` 헤더가 2026-06-12 에 이미 이렇게 적어뒀다:
#          *"단일 session_id=801 에 모든 INSERT 가 몰리면 인덱스 리프 페이지 래치·redo
#            커밋이 직렬화돼 **가짜 천장**이 생긴다"*
#          3차는 그 파일을 안 쓰고 단일 세션 batch.json 을 썼다.
# ─────────────────────────────────────────────────────────────────────────
#
# A. 단일 핫세션 대조 — 페이로드만 바꿔 3차 조건을 재현한다 (2판: 기본/완화)
# B. ③ 역순 재실행 — n10 → n5 → n1. 순서를 뒤집어도 같은 방향이면 워밍업이 아니다
#
# 사용: commit_sweep.sh 와 같은 환경변수

set -uo pipefail
cd "$(dirname "$0")"

OUT="${OUT:?OUT 미설정}"
LOG="$OUT/followup.tsv"
C=100; POOL=20
SESS_LO=901; SESS_HI=1000

source ./_rig.sh

learn_all_hosts
init_log

PLANS=()

echo "=== A. 단일 핫세션 대조 (3차 조건 재현 시도) ==="
# 단일 세션이므로 판 사이 초기화 범위도 그 세션 하나다.
SESS_LO=801; SESS_HI=801
restart_backend $POOL || die "백엔드 기동 실패"
ROWS_PER_REQ=5

set_durability 1 1
echo "--- single-hot / 기본 내구성 ---"
PLANS+=("single-default")
run_ghz "single-default" "/tmp/batch_single.json" "$C" 15000 || true

set_durability 2 0
echo "--- single-hot / 완화 ---"
PLANS+=("single-relaxed")
run_ghz "single-relaxed" "/tmp/batch_single.json" "$C" 15000 || true

# 완화 상태를 남기지 않는다 — B 는 기본 내구성에서 재야 ③과 비교된다.
set_durability 1 1

echo
echo "=== B. ③ 역순 재실행 (순서 효과 배제) ==="
SESS_LO=901; SESS_HI=1000
REV=(
  "rev-n10 /tmp/batch_n10.json 50 1500"
  "rev-n5  /tmp/batch_n5.json  25 3000"
  "rev-n1  /tmp/batch_n1.json  5  15000"
)
for plan in "${REV[@]}"; do
  read -r tag data rows n <<< "$plan"
  echo "--- $tag ---"
  ROWS_PER_REQ=$rows
  PLANS+=("$tag")
  run_ghz "$tag" "$data" "$C" "$n" || true
done

finish ${#PLANS[@]}