#!/bin/bash
# 스윕 v2 — 부하기를 obs 인스턴스로 분리해 재실행.
#
# v1 은 ghz 가 백엔드와 같은 2 vCPU 를 공유해 처리량이 ~200 RPS 에 눌렸고,
# 그 천장이 풀 크기 차이를 통째로 가렸다(pool=20 도 pool=5 도 똑같이 ~200).
# 여기서는 app(백엔드) / obs(부하기) 를 분리해 차이가 보이게 한다.
#
# ─────────────────────────────────────────────────────────────────────────
# ⚠️ 이 스크립트는 측정이 끝난 뒤 고쳐졌다 (이슈 #139 · #141)
#
# 고친 것은 **도구**고 데이터가 아니다. 두 건 다 «결과가 틀렸다» 가 아니라 «이 절차를
# 그대로 복사해 쓰면 안 된다» 쪽이다. #141(호스트 키 검증)은 아래 KNOWN 블록에 있다.
#
# 커밋된 sweep2.tsv 는 **수정 전 판본**이 만든 것이다. 수정 전에는 원격 실행이
# 실패해도 스윕이 계속됐고, 그 실패가 «RPS 0» 이 되어 cliff 판정 변수로 들어갔다:
#
#   plateau(pool=20) 판이 실패 → PLATEAU=0 → THRESH=0.0 → RLO>=0 은 항상 참
#                              → «cliff 없음» 이라고 출력된다
#   하한(pool=5) 판이 실패     → RLO=0 < THRESH → 이분탐색 진입, probe 마다 R=0
#                              → «풀이 작아서 느리다» 로 읽혀 없는 cliff 를 만든다
#
# 즉 인프라 문제(SSH 끊김·컨테이너 미기동·디스크)가 «풀 사이징 결론» 으로 위장됐다.
# 이번 실험의 결론이 하필 «절벽이 없다» 였기 때문에 특히 위험한 모양이다.
#
# 다만 **이번 데이터셋은 이 결함을 밟지 않은 것으로 보인다** — 파싱 실패는 TSV 행
# 누락을 남기는데, sweep2.tsv 에 설계상 판 수와 같은 10행이 다 있고 전 구간
# «cliff 없음» 이라 이분탐색 probe 가 0판이었다. 결론을 다시 쓰지는 않았다.
#
# 지금 판본은 실패를 숫자로 바꾸지 않는다. 실패하면 TSV 에 FAIL 행을 남기고
# **거기서 멈춘다.** 컨테이너가 못 뜬 뒤의 판들은 다른 시스템을 재는 것이므로,
# 이어서 도는 것보다 구멍이 보이는 채로 끝나는 편이 낫다.
# ─────────────────────────────────────────────────────────────────────────
set -uo pipefail
PEM="C:/Users/khjae/AppData/Local/Temp/claude/E--init/b2a0d6c9-7b8c-48b4-90ba-223a20532e31/scratchpad/shadowfit-cliff.pem"
# ⚠️ 퍼블릭 IP 2개(APP/OBS)는 커밋할 때 치환했다. 인스턴스는 측정 직후 전부 삭제했고,
#    반납된 주소는 지금 다른 계정 것일 수 있다. 재현 시 본인 인스턴스 주소를 넣으면 된다.
#    사설 IP(172.31.*)는 ghz·Prometheus 원본에도 그대로 박혀 있어 원본 값을 둔다.
APP="<app-public-ip>"; OBS="<obs-public-ip>"; DBP=172.31.1.47; APPP=172.31.8.60
OUT="C:/Users/khjae/AppData/Local/Temp/claude/E--init/b2a0d6c9-7b8c-48b4-90ba-223a20532e31/scratchpad/results2"
mkdir -p "$OUT"
LOG="$OUT/sweep2.tsv"
[ -f "$LOG" ] || printf "c\tpool\trps\tp50_ms\tp95_ms\tp99_ms\tfail\ttotal\n" > "$LOG"
LO=5; HI=20; N=2000; WARMUP_C=3; WARMUP_SEC=30

# ── 호스트 키 (이슈 #141) ────────────────────────────────────────────────
# 이전엔 `StrictHostKeyChecking=no` + `UserKnownHostsFile=/dev/null` 이었다. 둘이 같이
# 있으면 검증이 사실상 없다 — 학습한 키를 버리니 매 접속이 «처음 보는 호스트» 가 되고,
# 그걸 무조건 수락한다. 이 SSH 가 나르는 것은 원격 명령(컨테이너 재기동·DB DELETE)과
# env.sh 를 통한 자격증명 참조(DB_PASSWORD·INTERNAL_API_TOKEN·JWT_SECRET)다.
#
# 그렇다고 그냥 지우면 안 됐다. EC2 를 반복 재생성하는 실험이라 매번 호스트 키가 바뀌고,
# `StrictHostKeyChecking=ask` 는 대화형 프롬프트에서 멈춰 자동 스윕을 깬다. 그래서
# **스윕 시작 전에 한 번 학습(ssh-keyscan)하고, 그 뒤로는 엄격 검증**한다.
#
# 이 실험에 실제로 필요한 성질이기도 하다 — «인스턴스를 재생성했는지 아닌지» 가 측정
# 조건이므로, 세션 도중 키가 바뀌면 조용히 붙는 게 아니라 멈춰야 맞다.
#
# ⚠️ 첫 학습 순간은 여전히 TOFU 다. 그 창까지 없애려면 인스턴스 콘솔 출력의 지문과
#    대조해야 한다 — 부팅 로그를 기다려야 해서 자동화하지 않았다. 필요하면 수동으로:
#      aws ec2 get-console-output --instance-id <id> --output text | grep -A2 'SSH HOST KEY'
#    아래 학습 단계가 출력하는 지문과 눈으로 맞추면 된다.
KNOWN="$OUT/known_hosts"
touch "$KNOWN"; chmod 600 "$KNOWN" 2>/dev/null
S="ssh -i $PEM -o UserKnownHostsFile=$KNOWN -o StrictHostKeyChecking=yes -o ConnectTimeout=10"
SCP_OPTS=(-o "UserKnownHostsFile=$KNOWN" -o StrictHostKeyChecking=yes)

die() {
  echo "" >&2
  echo "🔴 스윕 중단 — $*" >&2
  echo "   부분 결과는 $LOG 에 남아 있다. **실패 지점 이후는 측정되지 않았다** —" >&2
  echo "   남은 행만 보고 cliff 를 판정하지 말 것." >&2
  exit 1
}

# 실패한 판을 TSV 에 «숫자가 아닌 것» 으로 남긴다. 이게 이 스크립트의 핵심 규칙이다:
# 실패는 0 이 아니다. 0 은 «측정했더니 0» 이라는 뜻이고, 실패는 «측정하지 못했다» 다.
fail_row() { printf "%s\t%s\tFAIL\t-\t-\t-\t-\t-\n" "$1" "$2" >> "$LOG"; }

learn_host() {  # $1 = 호스트, $2 = 이름표
  local h=$1 tag=$2 keys
  if ssh-keygen -F "$h" -f "$KNOWN" >/dev/null 2>&1; then
    echo "  $tag ($h): 이미 학습된 키를 쓴다"
    return 0
  fi
  keys=$(ssh-keyscan -T 10 -H "$h" 2>/dev/null)
  # 🔴 여기서 빈 값을 그냥 넘기면 known_hosts 가 비어 «엄격 검증» 이 껍데기가 된다.
  #    #139 와 같은 계열의 실수라 명시적으로 막는다.
  [ -n "$keys" ] && ssh-keygen -lf - <<< "$keys" >/dev/null 2>&1 \
    || die "$tag ($h) 의 호스트 키를 못 읽었다 — 인스턴스가 안 떴거나 22 가 막혔다"
  printf '%s\n' "$keys" >> "$KNOWN"
  echo "  $tag ($h) 지문:"
  ssh-keygen -lf - <<< "$keys" | sed 's/^/    /'
  return 0
}

restart() {  # $1 = pool
  local pool=$1 rc
  # 원격 쪽에서도 각 단계가 실패하면 고유 코드로 죽는다. 특히 헬스체크 —
  # 예전엔 60회 전부 실패해도 for 루프가 그냥 끝나서, 안 뜬 백엔드에 부하를 줬다.
  timeout 180 $S ec2-user@$APP "
  set -u
  source ~/env.sh || exit 10
  sudo docker rm -f sf-backend >/dev/null 2>&1
  sudo docker run -d --name sf-backend --memory 2g -p 8080:8080 -p 6565:6565 -p 9090:9090 \
   -e SPRING_PROFILES_ACTIVE=prod -e TZ=Asia/Seoul -e DB_HOST=$DBP \
   -e DB_USERNAME=\"\$DB_USERNAME\" -e DB_PASSWORD=\"\$DB_PASSWORD\" \
   -e INTERNAL_API_TOKEN=\"\$INTERNAL_API_TOKEN\" -e JWT_SECRET=\"\$JWT_SECRET\" \
   -e AI_SERVER_HOST=127.0.0.1 -e AI_SERVER_GRPC_PORT=8585 -e AI_SERVER_HTTP_PORT=8000 \
   -e SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=$pool sf-backend:obs >/dev/null || exit 11
  up=0
  for i in \$(seq 1 60); do
    curl -sf localhost:9090/actuator/health >/dev/null 2>&1 && { up=1; break; }
    sleep 2
  done
  [ \$up -eq 1 ] || { echo '백엔드가 120초 안에 health 를 주지 않았다' >&2; exit 12; }
  mysql -h $DBP -ushadowfit -pshadowfit shadowfit -N -e 'DELETE FROM pose_data WHERE session_id=801;' \
    || { echo 'pose_data 정리 실패 — 앞 판 데이터가 남은 채로 잰다' >&2; exit 13; }
  " >/dev/null
  rc=$?
  case $rc in
    0)   return 0 ;;
    10)  echo "  ✗ env.sh 를 읽지 못했다" >&2 ;;
    11)  echo "  ✗ 컨테이너 기동 실패 (pool=$pool)" >&2 ;;
    12)  echo "  ✗ 헬스체크 실패 — 백엔드가 안 떴다 (pool=$pool)" >&2 ;;
    13)  echo "  ✗ pose_data 정리 실패 (pool=$pool)" >&2 ;;
    124) echo "  ✗ 재기동이 180초 timeout (pool=$pool)" >&2 ;;
    255) echo "  ✗ SSH 연결 실패 → $APP" >&2 ;;
    *)   echo "  ✗ 재기동 실패 rc=$rc (pool=$pool)" >&2 ;;
  esac
  return 1
}

run() {  # $1=c $2=pool  -> stdout "rps fail" / 실패 시 non-zero
  local c=$1
  local pool=$2
  local f="c${c}-pool${pool}.json"
  local rc

  restart $pool || { fail_row "$c" "$pool"; return 1; }

  # ghz 는 RPC 가 일부 실패해도 리포트를 쓰고 0 으로 끝난다. 여기서 non-zero 는
  # «부하를 못 걸었다» 쪽이므로, 리포트가 남았더라도 경고하고 내용 검증에 맡긴다.
  timeout 400 $S ec2-user@$OBS "
    set -u
    ghz --insecure --call ExerciseService.SavePoseDataBatch --metadata-file /tmp/meta.json \
        --data-file /tmp/batch.json -c $WARMUP_C -z ${WARMUP_SEC}s $APPP:6565 >/dev/null 2>&1 \
      || { echo '워밍업 ghz 실패' >&2; exit 21; }
    mysql -h $DBP -ushadowfit -pshadowfit shadowfit -N -e 'DELETE FROM pose_data WHERE session_id=801;' 2>/dev/null || true
    ghz --insecure --call ExerciseService.SavePoseDataBatch --metadata-file /tmp/meta.json \
        --data-file /tmp/batch.json -c $c -n $N -O json -o /tmp/$f $APPP:6565 >/dev/null 2>&1 \
      || echo '본 ghz 가 non-zero 로 끝났다 — 리포트 내용으로 판정한다' >&2
    [ -s /tmp/$f ] || { echo 'ghz 리포트가 비었거나 없다' >&2; exit 23; }
  " >/dev/null
  rc=$?
  if [ $rc -ne 0 ]; then
    case $rc in
      21)  echo "  ✗ 워밍업 ghz 실패 (c=$c pool=$pool)" >&2 ;;
      23)  echo "  ✗ ghz 리포트 없음/빈 파일 (c=$c pool=$pool)" >&2 ;;
      124) echo "  ✗ 부하 실행이 400초 timeout (c=$c pool=$pool)" >&2 ;;
      255) echo "  ✗ SSH 연결 실패 → $OBS" >&2 ;;
      *)   echo "  ✗ 부하 실행 실패 rc=$rc (c=$c pool=$pool)" >&2 ;;
    esac
    fail_row "$c" "$pool"
    return 1
  fi

  if ! scp -i "$PEM" "${SCP_OPTS[@]}" -q ec2-user@$OBS:/tmp/$f "$OUT/$f"; then
    echo "  ✗ 결과 회수(scp) 실패 (c=$c pool=$pool) — 원격 /tmp/$f 는 남아 있다" >&2
    fail_row "$c" "$pool"
    return 1
  fi

  python - "$OUT/$f" "$c" "$pool" "$LOG" <<'PY'
import json, sys

f, c, pool, log = sys.argv[1:5]
try:
    j = json.load(open(f, encoding='utf-8'))
except Exception as e:
    # 예전엔 여기서 "0 9999" 를 출력하고 exit 0 으로 끝났다(`raise SystemExit` 는
    # 종료 코드 0 이다). 그 0 이 호출부의 판정 변수로 들어가 결론을 뒤집었다.
    print(f"  ✗ ghz JSON 파싱 실패 ({f}): {e}", file=sys.stderr)
    raise SystemExit(2)

d = {x['percentage']: round(x['latency'] / 1e6) for x in j.get('latencyDistribution') or []}
sc = j.get('statusCodeDistribution') or {}
tot = j.get('count', 0)
ok = sc.get('OK', 0)
fail = tot - ok
rps = round(j.get('rps', 0), 1)

# 측정으로 성립하지 않는 리포트는 숫자로 내보내지 않는다.
if tot <= 0:
    print(f"  ✗ 요청 수가 0 이다 ({f}) — 부하가 실제로 걸리지 않았다", file=sys.stderr)
    raise SystemExit(3)
if ok == 0:
    print(f"  ✗ 성공 응답이 0 이다 ({f}, {tot}건 전부 실패) — RPS {rps} 는 "
          f"처리량이 아니라 «거절 속도» 다", file=sys.stderr)
    raise SystemExit(4)
if rps <= 0:
    print(f"  ✗ RPS 가 0 이다 ({f})", file=sys.stderr)
    raise SystemExit(5)
if fail > tot * 0.05:
    print(f"  ⚠️ 실패율 {fail}/{tot} ({fail/tot:.1%}) — 이 판의 RPS 를 "
          f"plateau 로 쓰면 판정이 흔들린다", file=sys.stderr)

open(log, 'a').write(
    f"{c}\t{pool}\t{rps}\t{d.get(50,-1)}\t{d.get(95,-1)}\t{d.get(99,-1)}\t{fail}\t{tot}\n")
print(f"{rps} {fail}")
PY
  rc=$?
  [ $rc -eq 0 ] || { fail_row "$c" "$pool"; return 1; }
  return 0
}

# 숫자 비교 — 예전엔 값을 파이썬 소스에 문자열로 끼워 넣어서, 빈 값이나 비숫자가
# 오면 python 이 종료 코드 1 로 죽고 그게 «작다» 로 읽혔다(= 없는 cliff 를 만든다).
# 지금은 argv 로 넘기고 «비숫자» 를 «작다» 와 구분해 중단시킨다.
ge() {
  local rc
  python -c "
import sys
try:
    a, b = float(sys.argv[1]), float(sys.argv[2])
except ValueError:
    sys.exit(2)
sys.exit(0 if a >= b else 1)" "$1" "$2"
  rc=$?
  [ $rc -le 1 ] || die "판정값이 숫자가 아니다: '$1' vs '$2'"
  return $rc
}

echo "=== 호스트 키 학습 (이슈 #141) ==="
learn_host "$APP" "app"
learn_host "$OBS" "obs"
echo "  → 이후 접속은 StrictHostKeyChecking=yes 로 검증한다."
echo "     세션 도중 키가 바뀌면 접속이 거부되고 스윕이 멈춘다 — 그게 의도다."
echo "     (IP 를 재사용해 옛 키가 남아 거부되면: rm $KNOWN)"
echo

echo "=== 스윕 v2 (부하기 분리) 시작 $(date +%T) ==="
for c in 10 20 30 50 100; do
  echo "--- c=$c ---"

  R_HI=$(run $c $HI) || die "c=$c 의 plateau 판(pool=$HI)이 실패했다"
  read PLATEAU _ <<< "$R_HI"
  ge "$PLATEAU" 0.1 || die "plateau(pool=$HI)=$PLATEAU — 판정선을 만들 수 없다"
  THRESH=$(python -c "import sys; print(round(float(sys.argv[1]) * 0.9, 1))" "$PLATEAU") \
    || die "판정선 계산 실패 (PLATEAU=$PLATEAU)"
  echo "  plateau(pool=$HI)=$PLATEAU  판정선=$THRESH"

  R_LO=$(run $c $LO) || die "c=$c 의 하한 판(pool=$LO)이 실패했다"
  read RLO FLO <<< "$R_LO"
  echo "  하한(pool=$LO)=$RLO fail=$FLO"

  if ge "$RLO" "$THRESH"; then echo "  => c=$c: [$LO,$HI] 에 cliff 없음"; continue; fi
  lo=$LO; hi=$HI
  while [ $((hi-lo)) -gt 1 ]; do
    mid=$(((lo+hi)/2))
    R_MID=$(run $c $mid) || die "c=$c 의 probe 판(pool=$mid)이 실패했다"
    read R F <<< "$R_MID"
    echo "  probe pool=$mid RPS=$R fail=$F"
    if ge "$R" "$THRESH"; then hi=$mid; else lo=$mid; fi
  done
  echo "  => c=$c: cliff 는 pool $lo~$hi 사이 (pool>=$hi 부터 plateau 90% 이상)"
done
echo "=== 스윕 v2 완료 $(date +%T) ==="
cat "$LOG"
