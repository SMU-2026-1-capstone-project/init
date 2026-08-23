#!/usr/bin/env bash
# #276 ② — 배포된 재시도 상한(2)이 «동시성이 올라가도» 버티는가. **앱 경로**로 잰다.
#
# 이슈: https://github.com/Shadowfit/init/issues/276
#
# 왜 앱 경로인가 — 지금 상한 2 를 지탱하는 실측(r276-retry-2026-08-20)은 **저장 프로시저**
#   안에서 재시도한 값이고, 조건이 **워커 8 한 점**이다. 코드 주석이 그 한계를 스스로 적어뒀다
#   (ExerciseGrpcService.java: "실측은 저장 프로시저 안에서 재시도했고 여기는 앱이라
#   시도마다 왕복이 하나 더 붙는다"). 그리고 워커 스윕은 p 가 동시성의 함수임을 보였다
#   (w=2 에서 1.2%, w=16 에서 59.5%). 즉 «2회면 0%» 는 한 점의 값이다.
#
#   이 rig 은 그 한 점을 선으로 만든다. 재는 것은 **실제로 배포된 코드**다 —
#   ghz → Spring gRPC → PoseDataService, 그 안의 savePoseDataBatchWithDeadlockRetry.
#
# 무엇을 읽나:
#   ① ghz 의 상태 분포 — Internal 이 곧 «두 겹 중 안쪽(Spring 재시도 2회)이 다 소진된 요청» 이다
#   ② 지표 shadowfit_pose_batch_deadlock_retries{outcome} 의 판별 증분:
#        retried   = 데드락을 만나 다시 던진 횟수
#        recovered = 재시도 끝에 성공한 요청
#        exhausted = 상한을 다 쓰고 실패한 요청   ← 🔴 이 칸이 0 이 아니게 되는 동시성이 답이다
#   두 값은 서로를 검산한다. exhausted 와 Internal 이 어긋나면 둘 중 하나가 틀린 것이다.
#
# 조건 만들기 — 데드락은 «다세션 **중복**이 동시에» 일 때만 열린다
#   (2026-08-23 판정: loadtest/results/r276-newkeys-aws-2026-08-23). 그래서 페이로드를
#   `gen_batch_multi.py --duplicate-keys` 로 만든다(rep_number 고정 → 같은 세션 재전송).
#
# 🔴 판마다 대상 세션의 행을 지운다. 안 지우면 2판째부터는 «첫 요청도 중복» 인 다른 조건이 된다
#    — 실사용의 재전송은 원본이 방금 들어간 상태에서 오는 것이라 첫 삽입이 있어야 한다.
#
# ⚠️ 한계:
#   · 부하기가 대상과 **같은 박스**에 산다. 절대 RPS·지연은 못 쓴다 — 읽을 것은 실패 비율뿐이다
#   · 재시도 간격 0(코드 그대로). 백오프는 별도 팔이고 여기서 안 만든다
#   · 상한 2 는 **코드에 박힌 값**이라 이 rig 은 «상한을 바꿔가며» 재지 않는다.
#     이 판이 답하는 것은 «지금 값이 어디까지 버티나» 이지 «몇이 최적인가» 가 아니다
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a

LEVELS=${LEVELS:-"8 16 32"}       # ghz 동시성
# 🔴 상한 팔 (#276 ②, 2026-08-23 추가). 비어 있으면 «상한을 안 건드린다» — 배포 기본값 그대로 돌고
#    컨테이너를 다시 띄우지도 않는다(1차·2차 라운드와 같은 동작).
#    값을 주면 팔마다 백엔드를 그 상한으로 **다시 띄운다**(shadowfit.pose.deadlock.max-retries).
RETRY_ARMS=${RETRY_ARMS:-""}
# 🔴 백오프 팔 (#276 ③ 후속). 형식: «ms» 또는 «msj»(j = 지터). 예: "0 10 50 50j"
#    비어 있으면 간격을 안 건드린다(기본 0 = 즉시 재시도, 지금까지의 모든 라운드 조건).
#    RETRY_ARMS 와 **같이 쓰지 않는다** — 팔이 둘이면 이 판이 무엇을 재는지 흐려진다.
BACKOFF_ARMS=${BACKOFF_ARMS:-""}
REQS=${REQS:-500}                 # 판당 요청 수 (레벨 사이 공통 — 일의 양을 맞춘다)
BLOCKS=${BLOCKS:-4}               # 첫 블록은 버린다
SESSIONS=${SESSIONS:-901-1000}    # 대상 세션 (DB 에 있어야 한다)
REPS=${REPS:-25}                  # 배치당 프레임 수
TARGET=${TARGET:-localhost:6565}
ACTUATOR=${ACTUATOR:-http://localhost:9090/actuator/prometheus}
CONTAINER=${CONTAINER:-shadowfit-mysql}
DB_NAME=${DB_NAME:-shadowfit}
PW=${PW:-${MYSQL_ROOT_PASSWORD:-1234}}
GHZ=${GHZ_BIN:-$(command -v ghz || echo /usr/local/bin/ghz)}
OUT=${OUT:-loadtest/results/r276-app-retry-$(date +%Y-%m-%d)}
SC=$(mktemp -d)
mkdir -p "$OUT"

DB(){ docker exec -i -e MYSQL_PWD="$PW" "$CONTAINER" mysql -uroot -N "$DB_NAME" "$@" 2>/dev/null; }

die(){ echo "🔴 중단 — $*" >&2; exit 1; }

echo "## [0] 사전 확인 — «못 쟀다» 를 «0» 으로 찍지 않으려고 먼저 막는다"

[ -x "$GHZ" ] || die "ghz 가 없다: $GHZ"
echo "  ghz: $("$GHZ" --version 2>&1 | head -1)"

# 세션 시드. 없으면 전 요청이 SESSION_NOT_FOUND 로 죽는데 ghz 표는 «완주» 로 보인다.
LO=${SESSIONS%-*}; HI=${SESSIONS#*-}
SEEDED=$(DB -e "SELECT COUNT(*) FROM exercise_sessions WHERE id BETWEEN $LO AND $HI;" | tr -d '[:space:]')
WANT=$((HI - LO + 1))
[ "$SEEDED" = "$WANT" ] || die "세션 시드가 $WANT 개가 아니다 ($SEEDED) — bootstrap ROLE=p6-target 을 볼 것"
echo "  세션 $LO~$HI · $SEEDED 개"

# 🔴 상태가 무대의 일부다. IN_PROGRESS 가 아니면 PoseDataService 가 배치를 **조용히 버린다**
#    (#187 (b) — 던지지 않고 드롭한다). 그러면 INSERT 가 아예 없는데 ghz 는 전부 OK 로 찍히고,
#    표에는 «데드락 0» 이 남는다. «안 났다» 와 «잴 것이 없었다» 가 같은 얼굴이 되는 자리다.
#
#    그런데 seed-multi-sessions.sql 은 세션을 **COMPLETED** 로 넣는다(리포트 rig 이 그 상태를
#    쓴다). 그래서 여기서 무대를 세운다 — 조용히 고치지 않고 몇 행을 바꿨는지 찍는다.
INPROG=$(DB -e "SELECT COUNT(*) FROM exercise_sessions WHERE id BETWEEN $LO AND $HI AND status='IN_PROGRESS';" | tr -d '[:space:]')
if [ "$INPROG" != "$WANT" ]; then
  echo "  IN_PROGRESS 가 $INPROG/$WANT 다 — 무대를 세운다(status=IN_PROGRESS, last_active_at=NOW())"
  DB -e "UPDATE exercise_sessions SET status='IN_PROGRESS', end_time=NULL, last_active_at=NOW()
         WHERE id BETWEEN $LO AND $HI;" >/dev/null
  INPROG=$(DB -e "SELECT COUNT(*) FROM exercise_sessions WHERE id BETWEEN $LO AND $HI AND status='IN_PROGRESS';" | tr -d '[:space:]')
fi
[ "$INPROG" = "$WANT" ] || die "IN_PROGRESS 세션이 $WANT 개가 아니다 ($INPROG) — 배치가 조용히 버려진다(#187 (b))"
echo "  IN_PROGRESS $INPROG 개"

# 타임아웃 스케줄러가 판 도중에 세션을 걷어가면 «조용히 버려짐» 이 다시 열린다(같은 이유).
# 판마다 last_active_at 을 되돌리는 것으로 막는다 — 배치가 도착하면 서비스도 갱신하지만,
# 첫 요청 전에 이미 늙어 있으면 그 전에 잘린다.
touch_sessions(){ DB -e "UPDATE exercise_sessions SET last_active_at=NOW()
                         WHERE id BETWEEN $LO AND $HI;" >/dev/null; }

# 멱등 키가 없으면 중복이 그냥 다 들어가고 데드락 조건 자체가 성립하지 않는다(2026-08-20 no_uk 팔).
UK=$(DB -e "SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema='$DB_NAME' AND table_name='pose_data' AND index_name='uk_pose_event';" | tr -d '[:space:]')
[ "$UK" = "4" ] || die "uk_pose_event 가 4열이 아니다 ($UK) — 이 조건에서는 데드락이 안 난다"
echo "  uk_pose_event 4열"

metrics_raw(){ curl -sf --max-time 5 "$ACTUATOR" 2>/dev/null; }
counter(){ # $1=outcome → 누적값(없으면 0). 지표는 카운터라 «없다» 와 «0» 이 같은 모양이다
  printf '%s' "$1" >/dev/null
  metrics_raw | awk -v o="$1" '
    $0 ~ "^shadowfit_pose_batch_deadlock_retries_total\\{" && $0 ~ ("outcome=\"" o "\"") { v=$NF }
    END { printf "%d", (v=="" ? 0 : v) }'
}
metrics_raw >/dev/null || die "액추에이터에 못 붙는다: $ACTUATOR — Spring 이 떠 있는지, 9090 이 열려 있는지"
echo "  액추에이터 응답 ✅ (지표 누적 retried=$(counter retried) recovered=$(counter recovered) exhausted=$(counter exhausted))"

echo
echo "## [1] 페이로드 — 중복 조건 (#276 ② 는 이 조건에서만 성립한다)"
DATA=$SC/batch_dup.tmpl
python3 loadtest/ghz/gen_batch_multi.py --sessions "$SESSIONS" --reps "$REPS" \
  --out "$DATA" --duplicate-keys || die "페이로드 생성 실패"
printf '{"authorization":"Bearer %s"}' "${INTERNAL_API_TOKEN:?INTERNAL_API_TOKEN 이 .env 에 없다}" > "$SC/meta.json"

# 상한을 바꾼다 = 백엔드를 그 값으로 다시 띄운다. 🔴 그리고 **실제로 그 값이 붙었는지 단언한다** —
# 「바꿨다고 생각했는데 안 바뀐」 판이 이 저장소의 대표 실패 모양이다(rig 의 uk_pose_event 단언과 같은 이유).
set_retry_ceiling(){ # $1=상한
  local want="$1"
  echo "  [상한 $want] 백엔드 재기동"
  POSE_DEADLOCK_MAX_RETRIES="$want" docker compose up -d --no-deps --force-recreate shadowfit-backend >/dev/null 2>&1     || { echo "🔴 백엔드 재기동 실패"; return 1; }
  # 액추에이터가 답할 때까지 기다린다(«준비됐나» 는 ping 이 아니라 응답으로 본다 — #275 ②와 같은 결).
  local i
  for i in $(seq 1 60); do
    metrics_raw >/dev/null 2>&1 && break
    sleep 5
  done
  metrics_raw >/dev/null 2>&1 || { echo "🔴 재기동 후 액추에이터 무응답"; return 1; }
  # 환경변수가 실제로 컨테이너에 붙었는지 확인한다. 프로퍼티 자체를 되읽는 엔드포인트가
  # 없으므로(env 엔드포인트는 whitelist 밖) 여기까지가 이 rig 이 볼 수 있는 한계다.
  local got
  got=$(docker exec shadowfit-backend printenv SHADOWFIT_POSE_DEADLOCK_MAX_RETRIES 2>/dev/null | tr -d "[:space:]")
  [ "$got" = "$want" ] || { echo "🔴 컨테이너 환경변수가 $got 다 (기대 $want)"; return 1; }
  echo "  [상한 $want] SHADOWFIT_POSE_DEADLOCK_MAX_RETRIES=$got ✅"
  return 0
}

# 백오프 팔. «50j» 같은 형식을 ms + 지터로 쪼개고, 상한 함수와 같은 방식으로 단언한다.
set_backoff(){ # $1=팔 (예: 0 · 10 · 50j)
  local raw="$1" ms jit
  case "$raw" in
    *j) ms=${raw%j}; jit=true ;;
    *)  ms=$raw;     jit=false ;;
  esac
  echo "  [백오프 ${ms}ms$([ "$jit" = true ] && echo ' 지터')] 백엔드 재기동"
  POSE_DEADLOCK_BACKOFF_MS="$ms" POSE_DEADLOCK_BACKOFF_JITTER="$jit"     docker compose up -d --no-deps --force-recreate shadowfit-backend >/dev/null 2>&1     || { echo "🔴 백엔드 재기동 실패"; return 1; }
  local i
  for i in $(seq 1 60); do metrics_raw >/dev/null 2>&1 && break; sleep 5; done
  metrics_raw >/dev/null 2>&1 || { echo "🔴 재기동 후 액추에이터 무응답"; return 1; }
  local gm gj
  gm=$(docker exec shadowfit-backend printenv SHADOWFIT_POSE_DEADLOCK_BACKOFF_MS 2>/dev/null | tr -d "[:space:]")
  gj=$(docker exec shadowfit-backend printenv SHADOWFIT_POSE_DEADLOCK_BACKOFF_JITTER 2>/dev/null | tr -d "[:space:]")
  [ "$gm" = "$ms" ] && [ "$gj" = "$jit" ]     || { echo "🔴 컨테이너 환경변수가 ms=$gm jitter=$gj 다 (기대 $ms / $jit)"; return 1; }
  echo "  [백오프 $raw] BACKOFF_MS=$gm JITTER=$gj ✅"
  return 0
}

run_one(){ # $1=level $2=block → "level block ok internal other retried recovered exhausted rows"
  local c="$1" blk="$2"
  # 🔴 판마다 무대를 되돌린다. 안 지우면 2판째는 «첫 요청부터 중복» 인 다른 조건이 된다.
  DB -e "DELETE FROM pose_data WHERE session_id BETWEEN $LO AND $HI;" >/dev/null
  touch_sessions

  local r0 c0 e0 r1 c1 e1
  r0=$(counter retried); c0=$(counter recovered); e0=$(counter exhausted)

  "$GHZ" --insecure --call ExerciseService.SavePoseDataBatch \
    --metadata-file "$SC/meta.json" --data-file "$DATA" \
    -n "$REQS" -c "$c" -O json -o "$SC/ghz.$c.$blk.json" "$TARGET" >/dev/null 2>"$SC/ghz.err"

  r1=$(counter retried); c1=$(counter recovered); e1=$(counter exhausted)

  # ghz 의 상태 분포. python3 이 없으면 grep 으로 떨어진다 — 둘 다 없으면 «못 읽었다» 를 남긴다.
  local dist ok internal other
  dist=$(python3 - "$SC/ghz.$c.$blk.json" <<'PY' 2>/dev/null
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8")).get("statusCodeDistribution", {})
ok = d.get("OK", 0)
internal = d.get("Internal", 0)
print(ok, internal, sum(v for k, v in d.items() if k not in ("OK", "Internal")))
PY
)
  [ -n "$dist" ] || dist="- - -"
  read -r ok internal other <<<"$dist"

  local rows; rows=$(DB -e "SELECT COUNT(*) FROM pose_data WHERE session_id BETWEEN $LO AND $HI;" | tr -d '[:space:]')
  echo "${CEILING:--} $c $blk $ok $internal $other $((r1-r0)) $((c1-c0)) $((e1-e0)) ${rows:-0}"
}

echo
echo "## [2] 스윕 — ${RETRY_ARMS:+상한 «$RETRY_ARMS» × }레벨 «$LEVELS» × ${BLOCKS}블록(첫 블록 버림) · 판당 $REQS 요청 · 라틴 방격"
echo "ceiling level block ok internal other retried recovered exhausted rows" > "$SC/raw.txt"
lv=($LEVELS); n=${#lv[@]}
if [ -n "${RETRY_ARMS:-}" ] && [ -n "${BACKOFF_ARMS:-}" ]; then
  echo "🔴 RETRY_ARMS 와 BACKOFF_ARMS 를 같이 주면 이 판이 무엇을 재는지 흐려진다 — 하나만 줄 것"; exit 1
fi
# 팔 축은 하나다. 상한이면 set_retry_ceiling, 백오프면 set_backoff 가 무대를 세운다.
if [ -n "${BACKOFF_ARMS:-}" ]; then
  arms=($BACKOFF_ARMS); ARM_KIND=backoff
else
  arms=(${RETRY_ARMS:-}); ARM_KIND=ceiling
fi
an=${#arms[@]}
CEILING="-"

for ((b=0;b<BLOCKS;b++)); do
  echo "  --- 블록 $b$([ "$b" = 0 ] && echo ' (버림)')"
  if [ "$an" -gt 0 ]; then
    # 상한 팔이 있는 판 — 팔마다 백엔드를 다시 띄우고, 그 안에서 레벨을 돈다.
    # 라틴 방격은 **상한** 쪽에 건다(그게 이 라운드의 팔이다).
    for ((k=0;k<an;k++)); do
      CEILING=${arms[$(((k+b)%an))]}
      if [ "$ARM_KIND" = "backoff" ]; then
        set_backoff "$CEILING" || { echo "🔴 백오프 $CEILING 을 못 세웠다 — 이 판을 건너뛴다"; continue; }
      else
        set_retry_ceiling "$CEILING" || { echo "🔴 상한 $CEILING 을 못 세웠다 — 이 판을 건너뛴다"; continue; }
      fi
      for ((m=0;m<n;m++)); do
        line=$(run_one "${lv[$m]}" "$b")
        echo "$line" >> "$SC/raw.txt"
        echo "    $line"
      done
    done
  else
    for ((k=0;k<n;k++)); do
      # 라틴 방격 — 블록마다 레벨 순서를 한 칸 회전시켜 «레벨» 과 «판 순서» 를 분리한다.
      line=$(run_one "${lv[$(((k+b)%n))]}" "$b")
      echo "$line" >> "$SC/raw.txt"
      echo "    $line"
    done
  fi
done

echo
echo "## [3] 집계"
{
echo "# #276 ② 앱 경로 재시도 — 생성 표 (판정은 [README.md](./README.md) 에)"
echo
echo "ghz → Spring gRPC \`SavePoseDataBatch\` → \`savePoseDataBatchWithDeadlockRetry\`(간격 0)."
echo "페이로드는 **중복 조건**(\`--duplicate-keys\`, rep_number 고정) · 세션 $SESSIONS · 배치당 $REPS 프레임."
echo "판당 **$REQS 요청** · 레벨 \`$LEVELS\` · ${BLOCKS}블록(첫 블록 버림) · 판마다 대상 세션 행 삭제."
if [ -n "${RETRY_ARMS:-}" ]; then
echo
echo "🔴 **상한 팔**: \`$RETRY_ARMS\` — 팔마다 백엔드를 그 값으로 다시 띄웠고(\`shadowfit.pose.deadlock.max-retries\`),"
echo "붙었는지 컨테이너 환경변수로 단언했다. **라틴 방격은 상한 쪽에 걸었다.**"
fi
if [ -n "${BACKOFF_ARMS:-}" ]; then
echo
echo "🔴 **백오프 팔**: \`$BACKOFF_ARMS\` (\`j\` = 지터) — 팔마다 백엔드를 그 값으로 다시 띄웠고"
echo "(\`shadowfit.pose.deadlock.backoff-ms\` · \`.backoff-jitter\`), 붙었는지 환경변수로 단언했다."
echo "**라틴 방격은 백오프 쪽에 걸었다.** 상한은 배포 기본값(3) 고정."
fi
echo
echo "| 팔 | 동시성 | 블록 | OK | Internal | 그 외 | retried | recovered | **exhausted** | 저장된 행 |"
echo "|---|---|---|---|---|---|---|---|---|---|"
awk 'NR>1 {printf "| %s | %s | %s | %s | %s | %s | %s | %s | **%s** | %s |%s\n",
     $1,$2,$3,$4,$5,$6,$7,$8,$9,$10, ($3==0?" ← 버림":"")}' "$SC/raw.txt"
echo

# 팔이 있으면 상한별로, 없으면 레벨별로 접는다.
if [ -n "${RETRY_ARMS:-}" ]; then
  echo "**상한별 중앙값(첫 블록 제외)**"
  echo
  echo "| 상한 | Internal 중앙값 | 잔여 실패율 | exhausted 중앙값 | retried 중앙값 |"
  echo "|---|---|---|---|---|"
  KEYS="$RETRY_ARMS"; COL=1
elif [ -n "${BACKOFF_ARMS:-}" ]; then
  echo "**백오프별 중앙값(첫 블록 제외)**"
  echo
  echo "| 백오프 | Internal 중앙값 | 잔여 실패율 | exhausted 중앙값 | retried 중앙값 |"
  echo "|---|---|---|---|---|"
  KEYS="$BACKOFF_ARMS"; COL=1
else
  echo "**레벨별 중앙값(첫 블록 제외)**"
  echo
  echo "| 동시성 | Internal 중앙값 | 잔여 실패율 | exhausted 중앙값 | retried 중앙값 |"
  echo "|---|---|---|---|---|"
  KEYS="$LEVELS"; COL=2
fi
for key in $KEYS; do
  awk -v k="$key" -v col="$COL" 'NR>1 && $col==k && $3>0 {print $5, $9, $7}' "$SC/raw.txt"   | sort -n | awk -v k="$key" -v n="$REQS" '
      {i[NR]=$1; e[NR]=$2; r[NR]=$3}
      END{
        if (NR==0) { printf "| %s | — (유효 판 0) | — | — | — |\n", k; exit }
        m=(NR%2)? i[(NR+1)/2] : (i[NR/2]+i[NR/2+1])/2
        me=(NR%2)? e[(NR+1)/2] : (e[NR/2]+e[NR/2+1])/2
        mr=(NR%2)? r[(NR+1)/2] : (r[NR/2]+r[NR/2+1])/2
        printf "| %s | %.1f | %.2f%% | %.1f | %.1f |\n", k, m, (m/n)*100, me, mr }'
done
} | tee "$OUT/summary.md"

cp "$SC/raw.txt" "$OUT/raw.tsv"
cp "$SC"/ghz.*.json "$OUT/" 2>/dev/null
# 잠금 원문 — 판 직후에만 있다(다음 데드락이 덮어쓴다).
DB -e "SHOW ENGINE INNODB STATUS\G" > "$OUT/innodb-status.txt" 2>/dev/null

echo
echo "→ $OUT/summary.md (판정은 손으로 쓴 $OUT/README.md 에)"
