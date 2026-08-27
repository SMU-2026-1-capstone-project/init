#!/usr/bin/env bash
# Q2 잔여 — 從 R12 회수(../q2-partition-quiet-box-2026-08-24/README.md)가 남긴 「팔당 30~40
# 블록 더 필요(p=0.092, n=13)」를 채우는 드라이버. round3 과 같은 노브(WARM_DROP=1·SETTLE_SEC=10)로
# q2_partition_rig.sh 를 블록마다 두 번(pclean·pholed) 부르고, 블록 내 순서를 라틴 방격으로 교대한다.
#
# 한 블록 = 두 번의 호출(각각 pq_t 를 처음부터 다시 세운다 — rig 자체의 설계, §0 참고).
# 「순서」는 그 두 호출 중 어느 쪽을 먼저 부르느냐일 뿐 — 각 호출은 독립적으로 무대를 새로 세우므로
# SQL 상으로는 아무 영향이 없고, 오직 「박스 상태(버퍼풀 온도 등)가 먼저/나중에 영향을 주는가」를
# 나중에 가르기 위한 라벨이다(README §4 순서 교락 분석과 같은 목적).
#
# 사용: N_BLOCKS=35 bash run_q2_blocks.sh
set -uo pipefail
cd "$(dirname "$0")"
RIG=./q2_partition_rig.sh
[ -x "$RIG" ] || { echo "🔴 $RIG 가 없거나 실행권한이 없다"; exit 1; }

N_BLOCKS=${N_BLOCKS:-35}
OUT=${OUT:-./round4-raw.tsv}
export WARM_DROP=${WARM_DROP:-1}
export SETTLE_SEC=${SETTLE_SEC:-10}

echo -e "block\torder\tpos\tarm\ttarget\tms" > "$OUT"

run_one(){ # $1=block $2=order-label $3=pos(1|2) $4=arm(C|H) $5=target-partition
  local out
  out=$(TARGET="$5" "$RIG" 2>&1)
  local rc=$?
  if [ $rc -ne 0 ]; then
    echo "🔴 block $1 pos $3 ($4/$5) 실패 rc=$rc — 이 값은 기록하지 않는다" >&2
    echo "$out" | tail -5 >&2
    return 1
  fi
  local ms
  ms=$(echo "$out" | grep -oE '[0-9]+\.[0-9]+ ms   \(이 판의' | grep -oE '^[0-9]+\.[0-9]+')
  if [ -z "$ms" ]; then
    echo "🔴 block $1 pos $3 ($4/$5) — ms 를 rig 출력에서 못 읽었다. rig 출력 형식이 바뀌었을 수 있다" >&2
    echo "$out" | tail -10 >&2
    return 1
  fi
  echo -e "$1\t$2\t$3\t$4\t$5\t$ms" >> "$OUT"
  echo "  block $1 [$2] pos$3 $4($5) = ${ms}ms"
}

t0=$(date +%s)
for b in $(seq 1 "$N_BLOCKS"); do
  if [ $(( b % 2 )) -eq 1 ]; then
    order="C→H"
    run_one "$b" "$order" 1 C pclean
    run_one "$b" "$order" 2 H pholed
  else
    order="H→C"
    run_one "$b" "$order" 1 H pholed
    run_one "$b" "$order" 2 C pclean
  fi
done
t1=$(date +%s)

echo
echo "완료 — ${N_BLOCKS}블록, $(( (t1-t0)/60 ))분 $(( (t1-t0)%60 ))초. 원시 데이터: $OUT"
echo "판정(부호검정 등)은 $OUT 을 round3 분석과 같은 방식으로 손으로 낼 것 — 이 스크립트는 원시값만 낸다."

valid=$(( $(wc -l < "$OUT") - 1 ))
expect=$(( N_BLOCKS * 2 ))
echo "유효 판 ${valid}/${expect}"
# 🔴 절반 넘게 실패했으면 «측정했다» 로 부르면 안 된다 — run_all.sh 의 단계 표에 FAIL 로 남긴다.
[ "$valid" -ge $(( expect / 2 )) ]
