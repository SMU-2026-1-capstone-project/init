#!/bin/bash
# --connections 스윕 — 2026-08-08 오전 실험의 «~205 RPS 천장» 이 부하기 아티팩트인지 검증한다.
#
# 오전 rig 는 ghz 기본값(--connections 1)으로 14판 전부 돌았다. c 를 10→100 으로 흔든 것이
# 실제로는 TCP 커넥션 1개 위의 스트림 수를 흔든 것이었고, 전 구간 RPS 가 ~205 에 붙어 있었다.
# 여기서는 c·pool 을 고정하고 --connections 만 흔든다. RPS 가 올라가면 천장은 부하기 쪽이다.
#
# 오전과 다른 점(의도적):
#   -z 60s   판당 60초. 오전은 -n 2000(≈9.5초)이라 스크레이프 15초보다 짧아 지표를 판별로
#            귀속할 수 없었다(오전 정정의 원인 중 하나). 60초면 5초 간격으로 12샘플.
#   50만 행   오전은 375만. 내부 비교(1 vs 4 vs 16)라 절대값 일치가 불필요.
#            ⚠️ 그래서 절대 RPS 를 오전 판과 직접 비교하면 안 된다.
set -u
S="ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o LogLevel=ERROR"
APP_PRIV="172.31.13.51"; DB_PRIV="172.31.6.180"
OUT="${OUT:-/tmp/connres}"; mkdir -p "$OUT"
LOG="$OUT/conn.tsv"
[ -f "$LOG" ] || printf "conn\tc\tpool\trps\tp50_ms\tp95_ms\tp99_ms\tfail\ttotal\n" > "$LOG"

GHZ=~/go/bin/ghz
C=100; POOL=20; DUR=60s; WARM_C=3; WARM=30s

reset801() {
  ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR \
    ec2-user@$DB_PRIV "sudo docker exec sf-mysql mysql -ushadowfit -pshadowfit shadowfit \
    -e 'DELETE FROM pose_data WHERE session_id=801;'" >/dev/null 2>&1
}

run() {  # $1 = connections
  local conn=$1 f="conn${conn}-c${C}-pool${POOL}.json"
  echo "--- connections=$conn 워밍업 ${WARM} ---"
  $GHZ --insecure --call ExerciseService.SavePoseDataBatch --metadata-file /tmp/meta.json \
       --data-file /tmp/batch.json --connections "$conn" -c $WARM_C -z $WARM \
       "$APP_PRIV:6565" >/dev/null 2>&1
  reset801
  echo "--- connections=$conn 본판 ${DUR} (c=$C, pool=$POOL) ---"
  $GHZ --insecure --call ExerciseService.SavePoseDataBatch --metadata-file /tmp/meta.json \
       --data-file /tmp/batch.json --connections "$conn" -c $C -z $DUR \
       -O json -o "$OUT/$f" "$APP_PRIV:6565" >/dev/null 2>&1
  reset801
  python3 - "$OUT/$f" "$conn" "$C" "$POOL" "$LOG" <<'PY'
import json,sys
f,conn,c,pool,log = sys.argv[1:6]
try: j=json.load(open(f,encoding='utf-8'))
except Exception as e: print(f"  !! 파싱 실패: {e}"); raise SystemExit
d={x['percentage']: round(x['latency']/1e6) for x in (j.get('latencyDistribution') or [])}
sc=j.get('statusCodeDistribution') or {}
tot=j.get('count',0); fail=tot-sc.get('OK',0); rps=round(j.get('rps',0),1)
open(log,'a').write(f"{conn}\t{c}\t{pool}\t{rps}\t{d.get(50,-1)}\t{d.get(95,-1)}\t{d.get(99,-1)}\t{fail}\t{tot}\n")
print(f"  conn={conn}  RPS={rps}  p50={d.get(50)}ms  p99={d.get(99)}ms  fail={fail}/{tot}")
PY
}

echo "=== --connections 스윕 시작 (c=$C, pool=$POOL 고정) ==="
for conn in 1 4 16; do run $conn; done
echo
echo "=== 결과 ==="
cat "$LOG"
