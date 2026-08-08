#!/bin/bash
# 스윕 v2 — 부하기를 obs 인스턴스로 분리해 재실행.
#
# v1 은 ghz 가 백엔드와 같은 2 vCPU 를 공유해 처리량이 ~200 RPS 에 눌렸고,
# 그 천장이 풀 크기 차이를 통째로 가렸다(pool=20 도 pool=5 도 똑같이 ~200).
# 여기서는 app(백엔드) / obs(부하기) 를 분리해 차이가 보이게 한다.
set -u
PEM="C:/Users/khjae/AppData/Local/Temp/claude/E--init/b2a0d6c9-7b8c-48b4-90ba-223a20532e31/scratchpad/shadowfit-cliff.pem"
S="ssh -i $PEM -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10"
# ⚠️ 퍼블릭 IP 2개(APP/OBS)는 커밋할 때 치환했다. 인스턴스는 측정 직후 전부 삭제했고,
#    반납된 주소는 지금 다른 계정 것일 수 있다. 재현 시 본인 인스턴스 주소를 넣으면 된다.
#    사설 IP(172.31.*)는 ghz·Prometheus 원본에도 그대로 박혀 있어 원본 값을 둔다.
APP="<app-public-ip>"; OBS="<obs-public-ip>"; DBP=172.31.1.47; APPP=172.31.8.60
OUT="C:/Users/khjae/AppData/Local/Temp/claude/E--init/b2a0d6c9-7b8c-48b4-90ba-223a20532e31/scratchpad/results2"
mkdir -p "$OUT"
LOG="$OUT/sweep2.tsv"
[ -f "$LOG" ] || printf "c\tpool\trps\tp50_ms\tp95_ms\tp99_ms\tfail\ttotal\n" > "$LOG"
LO=5; HI=20; N=2000; WARMUP_C=3; WARMUP_SEC=30

restart() {  # $1 = pool
  timeout 180 $S ec2-user@$APP "source ~/env.sh; sudo docker rm -f sf-backend >/dev/null 2>&1
  sudo docker run -d --name sf-backend --memory 2g -p 8080:8080 -p 6565:6565 -p 9090:9090 \
   -e SPRING_PROFILES_ACTIVE=prod -e TZ=Asia/Seoul -e DB_HOST=$DBP \
   -e DB_USERNAME=\"\$DB_USERNAME\" -e DB_PASSWORD=\"\$DB_PASSWORD\" \
   -e INTERNAL_API_TOKEN=\"\$INTERNAL_API_TOKEN\" -e JWT_SECRET=\"\$JWT_SECRET\" \
   -e AI_SERVER_HOST=127.0.0.1 -e AI_SERVER_GRPC_PORT=8585 -e AI_SERVER_HTTP_PORT=8000 \
   -e SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=$1 sf-backend:obs >/dev/null
  for i in \$(seq 1 60); do curl -sf localhost:9090/actuator/health >/dev/null 2>&1 && break; sleep 2; done
  mysql -h $DBP -ushadowfit -pshadowfit shadowfit -N -e 'DELETE FROM pose_data WHERE session_id=801;' 2>/dev/null" >/dev/null 2>&1
}

run() {  # $1=c $2=pool  -> "rps fail"
  local c=$1
  local pool=$2
  local f="c${c}-pool${pool}.json"
  restart $pool
  timeout 400 $S ec2-user@$OBS "
    ghz --insecure --call ExerciseService.SavePoseDataBatch --metadata-file /tmp/meta.json \
        --data-file /tmp/batch.json -c $WARMUP_C -z ${WARMUP_SEC}s $APPP:6565 >/dev/null 2>&1
    mysql -h $DBP -ushadowfit -pshadowfit shadowfit -N -e 'DELETE FROM pose_data WHERE session_id=801;' 2>/dev/null || true
    ghz --insecure --call ExerciseService.SavePoseDataBatch --metadata-file /tmp/meta.json \
        --data-file /tmp/batch.json -c $c -n $N -O json -o /tmp/$f $APPP:6565 >/dev/null 2>&1" >/dev/null 2>&1
  scp -i "$PEM" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -q \
      ec2-user@$OBS:/tmp/$f "$OUT/$f" 2>/dev/null
  python - "$OUT/$f" "$c" "$pool" "$LOG" <<'PY'
import json,sys
f,c,pool,log=sys.argv[1:5]
try: j=json.load(open(f,encoding='utf-8'))
except Exception: print("0 9999"); raise SystemExit
d={x['percentage']:round(x['latency']/1e6) for x in j.get('latencyDistribution') or []}
sc=j.get('statusCodeDistribution') or {}; tot=j.get('count',0); fail=tot-sc.get('OK',0)
rps=round(j.get('rps',0),1)
open(log,'a').write(f"{c}\t{pool}\t{rps}\t{d.get(50,-1)}\t{d.get(95,-1)}\t{d.get(99,-1)}\t{fail}\t{tot}\n")
print(f"{rps} {fail}")
PY
}

ge() { python -c "import sys; sys.exit(0 if float('$1') >= float('$2') else 1)"; }

echo "=== 스윕 v2 (부하기 분리) 시작 $(date +%T) ==="
for c in 10 20 30 50 100; do
  echo "--- c=$c ---"
  read PLATEAU _ <<< "$(run $c $HI)"
  THRESH=$(python -c "print(round($PLATEAU*0.9,1))")
  echo "  plateau(pool=$HI)=$PLATEAU  판정선=$THRESH"
  read RLO FLO <<< "$(run $c $LO)"
  echo "  하한(pool=$LO)=$RLO fail=$FLO"
  if ge "$RLO" "$THRESH"; then echo "  => c=$c: [$LO,$HI] 에 cliff 없음"; continue; fi
  lo=$LO; hi=$HI
  while [ $((hi-lo)) -gt 1 ]; do
    mid=$(((lo+hi)/2))
    read R F <<< "$(run $c $mid)"
    echo "  probe pool=$mid RPS=$R fail=$F"
    if ge "$R" "$THRESH"; then hi=$mid; else lo=$mid; fi
  done
  echo "  => c=$c: cliff 는 pool $lo~$hi 사이 (pool>=$hi 부터 plateau 90% 이상)"
done
echo "=== 스윕 v2 완료 $(date +%T) ==="
cat "$LOG"
