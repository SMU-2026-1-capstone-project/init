#!/usr/bin/env bash
# HTTP 쓰기 경로 p99 — slo-baseline §4-2 의 「목표는 있고 현황은 없다」를 채우는 rig
#
# ──────────────────────────────────────────────
# 무엇이 열려 있었나
#
# 2026-08-23 읽기축 판이 §4-2 의 **읽기 절반**을 채웠다. 남은 절반이 쓰기다 —
# 「세션 쓰기(POST /exercises/sessions · PATCH /sessions/{id}/end) p99 ≤ 300ms」에
# **대응하는 실측이 아직 0** 이다. 그리고 읽기 판은 스스로 두 가지를 못 했다고 적었다:
#
#   ① 「16 VU 에 근거가 없다 — 가정 부하에서 유도한 값이 아니라 임의로 고른 상한이다」
#   ② 「판정선 대면은 EC2 從 라운드 몫이다」 (2코어 동거 박스 + 부하기까지 동거)
#
# 이 rig 은 그 둘을 다르게 한다.
#   ① 부하를 **가정 P1 에서 유도**한다 — 동접 67.5 / 900초 = **세션 시작률 0.075/초**.
#      팔은 그 **배수**다(`MULTS`). 그래서 산출이 「N ms」가 아니라 **「가정 피크의 몇 배까지
#      300ms 를 지키나」** 가 된다. 이 형태여야 박스가 달라도 진술이 살아남는다.
#   ② 부하기를 대상 박스 **밖**에 둔다(`BASE` 로 원격을 가리킨다 — AWS p6-loader/p6-target).
#
# ## 팔을 배수로 잡는 이유 — ×1 로는 p99 가 안 나온다
#
#   ×1 = 0.075/초 → 120초에 **9 iteration**. p99 는 표본 100개도 못 모은다.
#   그래서 ×1 을 «재는» 대신, 표본이 모이는 배수들을 재고 **곡선이 300ms 를 어디서 넘는지**를 낸다.
#   이건 회피가 아니라 이 질문에 맞는 형태다 — 우리가 알고 싶은 건 여유(headroom)이기 때문이다.
#
# ## 🔴 이 rig 으로 하면 안 되는 것
#   - 부하기와 대상이 **같은 박스**면 절대값을 인용하면 안 된다([[project_loadtest_env_constraint]]).
#     `BASE` 가 localhost 면 아래 [1] 이 그 사실을 결과에 박는다.
#   - 세션 길이를 모사하지 않는다(시작하자마자 끝낸다). 「15분 세션이 사는 동안」은 gRPC 적재 축 몫이다.
#
# ## 🔴 «종료» 는 동기가 아니다 — 이 rig 의 도착률 상한이 여기서 나온다
#
#   `PATCH /sessions/{id}/end` 는 `end_time` 만 쓴다(`Session.markEnded`). 세션이 `IN_PROGRESS`
#   를 벗어나는 것은 **AI 콜백이 온 뒤**다. 그런데 `createSession` 은 회원당 `IN_PROGRESS` 1개를
#   막으므로, **계정은 «종료 요청» 이 아니라 «완결» 이 되어야 다시 쓸 수 있다.**
#   → 이 rig 이 낼 수 있는 도착률 ≈ **계정 수 ÷ 완결 왕복**(아웃박스 폴 1초 + AI + 콜백).
#   409 가 무더기로 나면 서버가 느리다는 뜻이 아니라 **부하를 못 걸었다**는 뜻이다.
#   (2026-08-24 1차 AWS 판의 이 자리가 #528 을 끄집어냈다 — 콜백이 아예 안 와 32계정이 전부 갇혔다.)
#
# ## 🔴 이 판은 대상 박스에 «부수 부하» 도 같이 만든다 — 빼고 읽으면 안 된다
#
#   시작 1건마다 커밋 후 **AI 로 gRPC 가 비동기로 나가고**, 종료 1건마다 **outbox 행이 쌓여**
#   드레이너가 그걸 또 보낸다. 이건 실사용에서도 같이 일어나는 일이라 «오염» 이 아니라
#   «경로 그대로» 다. 다만 높은 배수(×180 ≈ 13.5 시작/초)에서는 AI 가 포화해 **서킷이 열릴 수 있고**,
#   그러면 그 팔은 «서킷 열린 상태의 p99» 다. 결과를 쓸 때 대상 백엔드 로그(러너가 걷는다)에서
#   서킷·outbox 적체를 확인하고 **그 사실을 같이 적을 것.**
# ──────────────────────────────────────────────
set -u

BASE=${BASE:-http://localhost:8080}
K6=${K6_BIN:-k6}
HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT=${K6_SCRIPT:-$HERE/k6/write_p99.js}

# 가정 P1 (load-test-strategy.md §4.2 · 2026-08-23 사용자 confirm) 에서 유도한 앵커.
#   동접 67.5세션 = DAU 1,000 × 1.5세션/일 × p 0.18 × (15/60)
#   세션 시작률   = 67.5 / (15분 × 60) = 0.075/초        ← 이 rig 의 1 iteration 이 이것
PEAK_RATE=${PEAK_RATE:-0.075}

# 🔴 배수를 이렇게 고른 이유는 **표본 수**다. 판당 표본 = 도착률 × DUR 이고, p99 는
#    표본 100개면 «두 번째로 나쁜 값» 이라 사실상 max 다.
#      ×20  → 1.5/초 × 120초 =   180  ← 너무 얇다. 그래서 기본에서 뺐다
#      ×60  → 4.5/초 × 120초 =   540
#      ×180 → 13.5/초 × 120초 = 1,620
#      ×360 → 27/초  × 120초 = 3,240
#    낮은 쪽이 필요하면 배수가 아니라 **DUR 을 늘려서** 받는다(×20 을 1,200 표본으로 보려면 800초).
MULTS=${MULTS:-"60 180 360"}         # 가정 피크 배수 = 4.5 · 13.5 · 27 세션시작/초
DUR=${DUR:-120s}
BLOCKS=${BLOCKS:-4}                  # 블록 0 은 버린다(예열·JIT·캐시)
# 🔴 계정 수는 «부하» 가 아니라 **레이트리밋**이 정한다.
#    `/member/signup`·`/member/login` 은 IP당 60초에 60건이 상한이다(AuthRateLimitFilter ·
#    application.yml `ip-per-window: 60`). 판마다 계정 수만큼 로그인하므로,
#    **계정 수 < 60** 이어야 한 판의 로그인 묶음이 창 하나에 안 걸린다(판 간격이 120초라 묶음끼리는 안 겹친다).
#    부하 쪽 요구는 훨씬 작다 — ×360(27/초)에 지연 500ms 여도 동시 VU 는 14 면 된다.
# 🔴 두 상한 사이에서 고른다.
#    ① 위(레이트리밋): 판당 로그인이 계정 수만큼이라 **계정 수 < 60**
#    ② 아래(완결 왕복): 세션 종료는 «요청» 이고, 계정이 다시 자유로워지는 것은 AI 콜백이
#       와서 status 가 IN_PROGRESS 를 벗어난 뒤다(`markEnded` 는 end_time 만 쓴다).
#       그 왕복이 «아웃박스 폴 1초 + AI 처리 + 콜백» 이라 대략 1~1.5초다.
#       → **도달 가능 도착률 ≈ 계정 수 ÷ 왕복.** 48계정이면 ~32~48/초라 ×360(27/초)이 들어간다.
ACCOUNTS=${ACCOUNTS:-48}
PASSWORD=${K6_PASSWORD:-'K6load!2026'}
PREFERRED_URL=${PREFERRED_URL:-https://www.youtube.com/watch?v=k6loadrig}
# 준비 단계의 간격. 계정마다 보호 경로를 2건(가입·로그인) 쓰므로 2.5초면 분당 48건이다.
PREP_SLEEP=${PREP_SLEEP:-2.5}
EXERCISE_ID=${EXERCISE_ID:-1}
PREFIX=${ACCOUNT_PREFIX:-k6w}
OUT=${OUT:-$HERE/results/http-write-p99-$(date +%F)}

mkdir -p "$OUT/logs" || exit 1
RAW="$OUT/raw.txt"

echo "# HTTP 쓰기 p99 — 가정 피크 배수 스윕"
echo
echo "## [1] 전제"
echo "  대상        : $BASE"
case "$BASE" in
  *localhost*|*127.0.0.1*)
    echo "  🔴 부하기와 대상이 **같은 박스**다 — 절대 p99 를 300ms 판정선에 대면 안 된다."
    echo "     (이 판의 존재 이유가 «판정선 대면» 이므로, 이 상태면 예행 이상으로 쓰지 말 것)" ;;
  *) echo "  ✅ 원격 대상 — 부하기 CPU 가 대상과 섞이지 않는다" ;;
esac
echo "  k6          : $("$K6" version 2>&1 | head -1)"
echo "  커밋        : $(git -C "$HERE/.." rev-parse --short HEAD 2>/dev/null || echo NA)"
echo "  앵커        : 가정 피크 = $PEAK_RATE 세션시작/초 (동접 67.5 ÷ 900초)"
echo "  팔          : 배수 «$MULTS» · 판당 $DUR · ${BLOCKS}블록(0 버림) · 라틴 방격"
echo "  계정        : $ACCOUNTS 개 (프리픽스 $PREFIX) — VU 당 1개, 회원당 활성세션 1개 제약 때문"

# 🔴 actuator 로 안 본다 — 관리 포트가 8080 이 아니라 9090 이고(application.yml),
#    원격에선 안 열려 있는 게 정상이다. 「앱이 대답하는가」는 인증이 걸린 엔드포인트가
#    401 을 주는지로 본다. 000 이면 못 닿은 것이다.
PROBE=$(curl -s -o /dev/null -w '%{http_code}|%{time_total}' --max-time 20 "$BASE/sessions/active" 2>/dev/null)
echo "  응답 확인   : $BASE/sessions/active → ${PROBE%%|*} (${PROBE##*|}s, 인증 없이 부른 것이라 401 이 정상)"
case "${PROBE%%|*}" in
  401|403) ;;
  000) echo "  🔴 대상에 못 닿았다 — BASE·보안그룹·포트를 볼 것. 이대로 돌리면 전 판이 빈다"; exit 1 ;;
  *)   echo "  🟡 401/403 이 아니다 — 보안 설정이 다르거나 다른 앱이다. 블록 0 을 보고 판단할 것" ;;
esac

echo
echo "## [1-b] 계정 준비 — $ACCOUNTS 개 (레이트리밋 때문에 rig 이 미리 만든다)"
echo "  가입·로그인은 IP당 60초 60건 상한이라 ${PREP_SLEEP}초 간격으로 만든다 (분당 48건)"
prep_ok=0
for i in $(seq 1 "$ACCOUNTS"); do
  email="${PREFIX}${i}@loadtest.local"
  curl -s -o /dev/null -m 30 -X POST "$BASE/member/signup" -H 'Content-Type: application/json'     -d "{\"username\":\"${PREFIX}${i}\",\"email\":\"$email\",\"password\":\"$PASSWORD\",\"sex\":\"MALE\",\"role\":\"USER\"}"
  tok=$(curl -s -m 30 -X POST "$BASE/member/login" -H 'Content-Type: application/json'         -d "{\"email\":\"$email\",\"password\":\"$PASSWORD\"}"         | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
  if [ -z "$tok" ]; then
    echo "  🔴 $email 로그인 실패 — 429(레이트리밋)면 PREP_SLEEP 을 올릴 것"
    exit 1
  fi
  code=$(curl -s -o /dev/null -w '%{http_code}' -m 30 -X PATCH "$BASE/member/onboarding/$email"          -H 'Content-Type: application/json' -H "Authorization: Bearer $tok"          -d "{\"preferredUrl\":\"$PREFERRED_URL\"}")
  [ "$code" = "200" ] || { echo "  🔴 $email 온보딩 실패 → $code (preferredUrl 이 비면 세션 시작이 400 이다)"; exit 1; }
  prep_ok=$((prep_ok+1))
  sleep "$PREP_SLEEP"
done
echo "  ✅ 계정 $prep_ok 개 준비됨 (가입·온보딩 완료)"
# 🔴 준비의 마지막 요청과 첫 판의 로그인 묶음이 **같은 창**에 들면 또 429 다. 창 하나를 비운다.
echo "  창 비우기 60초 대기"
sleep 60

# ── 한 판 ───────────────────────────────────────────────────────────────────
run_one() {  # $1=배수  $2=블록
  local mult=$1 blk=$2 rate log sum
  rate=$(awk -v p="$PEAK_RATE" -v m="$mult" 'BEGIN{printf "%.4f", p*m}')
  log="$OUT/logs/x${mult}-b${blk}.log"
  sum="$OUT/logs/x${mult}-b${blk}.row"
  BASE="$BASE" RATE="$rate" DUR="$DUR" ACCOUNTS="$ACCOUNTS" EXERCISE_ID="$EXERCISE_ID" \
  ACCOUNT_PREFIX="$PREFIX" ARM_LABEL="x$mult" BLOCK="$blk" SUMMARY_OUT="$sum"   SIGNUP=0 K6_PASSWORD="$PASSWORD" \
    "$K6" run --quiet "$SCRIPT" > "$log" 2>&1
  if [ ! -s "$sum" ]; then
    echo "x$mult $blk $rate NA NA NA NA NA NA NA NA 0 NA NA NA"
    return
  fi
  cat "$sum"
}

echo
echo "## [2] 스윕"
echo "arm block rate s_p50 s_p95 s_p99 s_max e_p50 e_p95 e_p99 e_max iters failed dropped conflict" > "$RAW"
mv_arr=($MULTS); mn=${#mv_arr[@]}
for ((b=0;b<BLOCKS;b++)); do
  echo "  --- 블록 $b$([ "$b" = 0 ] && echo ' (버림)')"
  for ((k=0;k<mn;k++)); do
    line=$(run_one "${mv_arr[$(((k+b)%mn))]}" "$b")
    echo "$line" >> "$RAW"
    echo "    $line"
  done
  if [ "$b" = 0 ]; then
    bad=$(awk 'NR>1 && $2==0 && ($6=="NA" || $12+0==0) {c++} END{print c+0}' "$RAW")
    if [ "$bad" -gt 0 ]; then
      echo
      echo "  🔴 버림 블록에서 $bad 팔이 표본을 못 만들었다 — 스윕을 계속해 봐야 빈 표가 나온다."
      echo "     흔한 원인: EXERCISE_ID=$EXERCISE_ID 가 대상에 없다 · 온보딩 실패 · BASE 오타."
      echo "     첫 판 로그: $OUT/logs/"
      exit 1
    fi
  fi
done

# ── 게이트 ──────────────────────────────────────────────────────────────────
echo
echo "## [3] 🔴 게이트 — 이 셋 중 하나라도 깨지면 그 팔의 지연은 인용 금지"
GATE_OK=1
for m in $MULTS; do
  d=$(awk -v a="x$m" 'NR>1 && $1==a && $2>0 {s+=$14} END{print s+0}' "$RAW")
  c=$(awk -v a="x$m" 'NR>1 && $1==a && $2>0 {s+=$15} END{print s+0}' "$RAW")
  f=$(awk -v a="x$m" 'NR>1 && $1==a && $2>0 {s+=$13} END{print s+0}' "$RAW")
  msg=""
  [ "$d" -gt 0 ] && { msg="$msg dropped=$d(도착률 미달성 → 「가정 피크 ×$m」 진술이 깨진다)"; GATE_OK=0; }
  [ "$c" -gt 0 ] && { msg="$msg conflict409=$c(계정이 안 풀렸다 — 완결 왕복 vs 도착률/계정수 를 볼 것)"; GATE_OK=0; }
  [ "$f" -gt 0 ] && { msg="$msg 실패요청=$f(시작/종료 중 2xx 가 아닌 것)"; GATE_OK=0; }
  if [ -z "$msg" ]; then echo "  ✅ ×$m — 실패 0 · dropped 0 · 409 0"; else echo "  🔴 ×$m —$msg"; fi
done
[ "$GATE_OK" = 1 ] || echo "  🔴 깨진 팔은 «부하를 못 걸었다» 는 뜻이지 «느리다» 는 뜻이 아니다. ACCOUNTS 를 올려 다시 볼 것."

# ── 집계 ────────────────────────────────────────────────────────────────────
echo
echo "## [4] 집계 — 유효 블록의 중앙값"
{
echo "# HTTP 쓰기 p99 @ 가정 피크 배수 — 생성 표 (판정은 [README.md](./README.md) 에)"
echo
echo "대상 \`$BASE\` · 판당 $DUR · ${BLOCKS}블록(0 버림) · 라틴 방격 · 계정 $ACCOUNTS"
echo
echo "| 배수 | 도착률(세션시작/초) | 시작 p50 | 시작 p99 | 종료 p50 | 종료 p99 | 표본 |"
echo "|---|--:|--:|--:|--:|--:|--:|"
for m in $MULTS; do
  awk -v a="x$m" -v mult="$m" '
    # 🔴 1-based. n 을 0 으로 안 두고 sp[n] 을 쓰면 첫 값이 «빈 문자열» 키로 들어가
    #    (awk 는 미초기화 변수를 "" 로 본다) 중앙값이 조용히 한 칸 어긋난다.
    NR>1 && $1==a && $2>0 {
      n++; rate=$3; sp50[n]=$4+0; sp99[n]=$6+0; ep50[n]=$8+0; ep99[n]=$10+0; it+=$12
    }
    function med(arr,   i,j,t,c) {
      c=n; for(i=1;i<=c;i++) for(j=i+1;j<=c;j++) if(arr[j]<arr[i]) {t=arr[i];arr[i]=arr[j];arr[j]=t}
      return (c%2) ? arr[int((c+1)/2)] : (arr[c/2]+arr[c/2+1])/2
    }
    END{
      if(n==0){printf "| ×%s | — | — | — | — | — | 0 |\n", mult; exit}
      printf "| ×%s | %.2f | %.1f | **%.1f** | %.1f | **%.1f** | %d |\n",
             mult, rate, med(sp50), med(sp99), med(ep50), med(ep99), it
    }' "$RAW"
done
echo
echo "> 원자료: [\`raw.txt\`](./raw.txt) · k6 로그: \`logs/\`"
} | tee "$OUT/table.md"

echo
echo "결과 → $OUT"
