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
#
# ─────────────────────────────────────────────────────────────────────────
# ⚠️ 이 스크립트는 측정이 끝난 뒤 고쳐졌다 (이슈 #141 · #146)
#
# 커밋된 conn.tsv · results.tsv 는 **수정 전 판본**이 만든 것이다. 고친 것은 도구고
# 데이터가 아니다 — 결과는 다시 쓰지 않았다.
#
#   #141  호스트 키 검증을 끄고 붙었다 (아래 KNOWN 블록)
#   #146  실패를 삼켰다 — 파싱 실패가 `raise SystemExit`(종료 코드 0)로 끝나고, ghz·
#         reset801 종료 코드를 안 봤다. 판 하나가 조용히 빠져도 "=== 결과 ===" 를
#         출력하고 exit 0 이었다
#
# #146 에서 «중단이냐 계속이냐» 는 실패 종류로 갈랐다. #139(pool-cliff 쪽 sweep.sh)에서
# 중단을 고른 이유는 «컨테이너가 못 뜬 뒤의 판은 다른 시스템을 잰다» — 즉 **오염**이었다.
# 그 기준을 그대로 적용하면 여기서는 답이 갈린다:
#
#   reset801 실패        → 다음 판을 오염시킨다. pose_data 행 수는 이 실험이 고정해야
#                          하는 조건인데 그게 틀어지고 복구 경로가 없다  → **중단**
#   ghz·리포트·파싱 실패  → 이 판 데이터만 잃는다. 다른 conn 값의 비교는 그대로 유효
#                          → **FAIL 행 남기고 계속**
#
# 3판짜리 스윕이고 판당 90초라, 1판을 잃고 2판을 살리는 편이 전체 재실행보다 낫다.
# ⚠️ 다만 «계속» 이 «성공» 을 뜻하면 안 된다 — 그게 #146 의 실제 결함이었다. 실패한 판이
# 하나라도 있으면 끝에 요약을 찍고 **exit 1** 이다. 이 실험의 결론이 «1→16 에서 무변화»
# 라는 «비교» 이므로, 근거가 3점에서 몇 점으로 줄었는지가 보여야 한다.
# ─────────────────────────────────────────────────────────────────────────
set -uo pipefail
APP_PRIV="172.31.13.51"; DB_PRIV="172.31.6.180"
OUT="${OUT:-/tmp/connres}"; mkdir -p "$OUT"
LOG="$OUT/conn.tsv"

# ── 호스트 키 (이슈 #141) ────────────────────────────────────────────────
# 이전엔 `StrictHostKeyChecking=no` + `UserKnownHostsFile=/dev/null` 이었다. 둘이 같이
# 있으면 검증이 사실상 없다 — 학습한 키를 버리니 매 접속이 «처음 보는 호스트» 가 되고
# 그걸 무조건 수락한다. 이슈는 pool-cliff 쪽 sweep.sh 만 지목했지만 같은 패턴이 여기에도
# 있었고, 이 스크립트도 «재현용» 으로 커밋됐으므로 같이 고친다.
#
# 여기서 SSH 가 하는 일은 DB 인스턴스에서 `DELETE FROM pose_data` 를 돌리는 것 하나다.
# 판 사이 상태 초기화라 이게 엉뚱한 호스트로 가면 측정 자체가 무의미해진다.
#
# 시작할 때 한 번 학습하고 그 뒤로는 엄격 검증한다. ⚠️ 첫 학습은 여전히 TOFU 다
# (sweep.sh 헤더에 콘솔 출력 지문 대조 방법을 적어뒀다).
KNOWN="$OUT/known_hosts"
touch "$KNOWN"; chmod 600 "$KNOWN" 2>/dev/null
SSH_OPTS=(-o "UserKnownHostsFile=$KNOWN" -o StrictHostKeyChecking=yes
          -o ConnectTimeout=10 -o LogLevel=ERROR)

learn_host() {  # $1 = 호스트, $2 = 이름표
  local h=$1 tag=$2 keys
  if ssh-keygen -F "$h" -f "$KNOWN" >/dev/null 2>&1; then
    echo "  $tag ($h): 이미 학습된 키를 쓴다"; return 0
  fi
  keys=$(ssh-keyscan -T 10 -H "$h" 2>/dev/null)
  # 빈 값을 넘기면 known_hosts 가 비어 «엄격 검증» 이 껍데기가 된다.
  if [ -z "$keys" ] || ! ssh-keygen -lf - <<< "$keys" >/dev/null 2>&1; then
    echo "🔴 중단 — $tag ($h) 의 호스트 키를 못 읽었다 (인스턴스 미기동 / 22 차단)" >&2
    exit 1
  fi
  printf '%s\n' "$keys" >> "$KNOWN"
  echo "  $tag ($h) 지문:"; ssh-keygen -lf - <<< "$keys" | sed 's/^/    /'
}
[ -f "$LOG" ] || printf "conn\tc\tpool\trps\tp50_ms\tp95_ms\tp99_ms\tfail\ttotal\n" > "$LOG"

GHZ=~/go/bin/ghz
C=100; POOL=20; DUR=60s; WARM_C=3; WARM=30s
FAILED=()   # 유효 데이터를 못 낸 판. 끝에서 요약하고 exit 1 의 근거가 된다.

die() {  # 다음 판을 오염시키는 실패에만 쓴다
  echo "" >&2
  echo "🔴 스윕 중단 — $*" >&2
  echo "   pose_data 행 수는 이 실험이 고정해야 하는 조건이다. 초기화가 실패한 뒤의 판은" >&2
  echo "   «커넥션을 늘려도 안 오른다» 가 아니라 «테이블이 커졌다» 를 잰다 — 구분이 안 된다." >&2
  echo "   $LOG 의 기존 행은 유효하지만, 이 지점 이후는 측정되지 않았다." >&2
  exit 1
}

# 실패를 숫자로 바꾸지 않는다. 0 은 «측정했더니 0», FAIL 은 «측정하지 못했다» 다.
fail_row() { printf "%s\t%s\t%s\tFAIL\t-\t-\t-\t-\t-\n" "$1" "$C" "$POOL" >> "$LOG"; }

reset801() {  # $1 = 어느 시점인지 (메시지용)
  ssh "${SSH_OPTS[@]}" \
    ec2-user@$DB_PRIV "sudo docker exec sf-mysql mysql -ushadowfit -pshadowfit shadowfit \
    -e 'DELETE FROM pose_data WHERE session_id=801;'" >/dev/null 2>&1 \
    || die "pose_data 초기화 실패 ($1)"
}

run() {  # $1 = connections
  local conn=$1 f="conn${conn}-c${C}-pool${POOL}.json" rc

  echo "--- connections=$conn 워밍업 ${WARM} ---"
  if ! $GHZ --insecure --call ExerciseService.SavePoseDataBatch --metadata-file /tmp/meta.json \
            --data-file /tmp/batch.json --connections "$conn" -c $WARM_C -z $WARM \
            "$APP_PRIV:6565" >/dev/null 2>&1; then
    # 상태를 바꾸지 않은 실패다 — 이 판만 버리고 다음 conn 으로 간다.
    echo "  ✗ 워밍업 ghz 실패 — conn=$conn 판을 버린다 (백엔드 미기동 / 포트 차단?)" >&2
    fail_row "$conn"; FAILED+=("$conn:워밍업"); return 1
  fi
  reset801 "워밍업 직후, conn=$conn"

  echo "--- connections=$conn 본판 ${DUR} (c=$C, pool=$POOL) ---"
  # ghz 는 RPC 가 일부 실패해도 리포트를 쓰고 0 으로 끝난다(이 실험은 -z 종료 아티팩트로
  # 판당 fail≈100 이 정상이다 — README §7). 그래서 non-zero 는 경고에 그치고, 판정은
  # 리포트 내용으로 한다. 리포트가 없으면 그건 판정할 것이 없다는 뜻이다.
  $GHZ --insecure --call ExerciseService.SavePoseDataBatch --metadata-file /tmp/meta.json \
       --data-file /tmp/batch.json --connections "$conn" -c $C -z $DUR \
       -O json -o "$OUT/$f" "$APP_PRIV:6565" >/dev/null 2>&1 \
    || echo "  ⚠️ ghz 가 non-zero 로 끝났다 — 리포트 내용으로 판정한다" >&2
  reset801 "본판 직후, conn=$conn"

  if [ ! -s "$OUT/$f" ]; then
    echo "  ✗ ghz 리포트가 없거나 비었다 ($OUT/$f) — conn=$conn 판을 버린다" >&2
    fail_row "$conn"; FAILED+=("$conn:리포트없음"); return 1
  fi

  python3 - "$OUT/$f" "$conn" "$C" "$POOL" "$LOG" <<'PY'
import json, sys

f, conn, c, pool, log = sys.argv[1:6]
try:
    j = json.load(open(f, encoding='utf-8'))
except Exception as e:
    # 이전엔 여기서 메시지만 찍고 `raise SystemExit` — 종료 코드 0 이라 호출부가
    # 성공으로 읽었고, TSV 행만 조용히 사라졌다(이슈 #146).
    print(f"  ✗ ghz JSON 파싱 실패 ({f}): {e}", file=sys.stderr)
    raise SystemExit(2)

d = {x['percentage']: round(x['latency'] / 1e6) for x in (j.get('latencyDistribution') or [])}
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
    print(f"  ✗ 성공 응답이 0 이다 ({f}, {tot}건 전부 실패) — RPS {rps} 는 처리량이 아니라 "
          f"«거절 속도» 다", file=sys.stderr)
    raise SystemExit(4)
if rps <= 0:
    print(f"  ✗ RPS 가 0 이다 ({f})", file=sys.stderr)
    raise SystemExit(5)

# 위 셋은 «사건의 유무»(0 인가 아닌가)이지 «얼마까지 참는가» 가 아니다. 후자는 넣지
# 않는다 — 실패율에 임의 임계를 걸어 «크다/작다» 를 판정할 근거가 없다. 실패 건수와
# 비율은 아래 출력과 TSV 에 사실로 남으므로, 판단은 그걸 보는 사람이 한다.
open(log, 'a').write(
    f"{conn}\t{c}\t{pool}\t{rps}\t{d.get(50,-1)}\t{d.get(95,-1)}\t{d.get(99,-1)}\t{fail}\t{tot}\n")
print(f"  conn={conn}  RPS={rps}  p50={d.get(50)}ms  p99={d.get(99)}ms  "
      f"fail={fail}/{tot} ({fail/tot:.2%})")
PY
  rc=$?
  if [ $rc -ne 0 ]; then
    fail_row "$conn"; FAILED+=("$conn:파싱/내용"); return 1
  fi
  return 0
}

echo "=== 호스트 키 학습 (이슈 #141) ==="
learn_host "$DB_PRIV" "db"
echo "  → 이후 접속은 StrictHostKeyChecking=yes 로 검증한다."
echo "     (IP 를 재사용해 옛 키가 남아 거부되면: rm $KNOWN)"
echo

CONNS=(1 4 16)
echo "=== --connections 스윕 시작 (c=$C, pool=$POOL 고정) ==="
[ -x "$GHZ" ] || die "ghz 가 없다: $GHZ"
for conn in "${CONNS[@]}"; do run "$conn" || true; done
echo
echo "=== 결과 ==="
cat "$LOG"

# 🔴 #146 의 실제 결함은 «판이 빠져도 여기까지 와서 exit 0» 이었다. 이 실험의 결론은
#    «1→16 에서 무변화» 라는 «비교» 이므로, 근거가 몇 점으로 줄었는지가 보여야 한다.
if [ ${#FAILED[@]} -gt 0 ]; then
  echo
  echo "🔴 ${#CONNS[@]}판 중 ${#FAILED[@]}판이 유효 데이터를 내지 못했다: ${FAILED[*]}" >&2
  echo "   위 표에서 해당 판은 FAIL 행이다. 남은 $(( ${#CONNS[@]} - ${#FAILED[@]} ))판만으로" >&2
  echo "   «커넥션 수를 늘려도 안 오른다» 를 판정하지 말 것 — 비교의 근거가 그만큼 줄었다." >&2
  exit 1
fi
echo
echo "✅ ${#CONNS[@]}판 전부 유효."
