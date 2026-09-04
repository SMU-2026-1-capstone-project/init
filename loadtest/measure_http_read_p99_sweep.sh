#!/usr/bin/env bash
# HTTP 읽기 p99 스윕 — 從 R14 (판정선 대면), k6/read_p99_ec2.js 를 배수 × 블록으로 돌린다.
#
# write 축(measure_http_write_p99.sh)과 형태는 같다 — 가정 P1 배수 · constant-arrival-rate ·
# 라틴 방격 · 게이트(dropped_iterations·bad_status 0) · 유효 블록 중앙값 집계.
#
# 갈리는 점: read_p99_ec2.js 는 setup() 안에서 **매 실행마다 자기 계정을 새로 만든다**
#   (write 축처럼 미리 계정을 준비해두는 단계가 없다 — 회원당 세션 제약이 없어 재사용 필요가 없다).
#   대신 K6_SIDS(리포트가 붙은 세션 id 목록)를 미리 받아야 한다 — 이 스크립트를 부르기 전에
#   seed/seed_k6_read_account.sh 로 만들어서 넘길 것. 이 스크립트 자체는 시딩을 안 한다
#   (누가 부르든 같은 시드를 매번 다시 만들 필요가 없어서다).
set -u

BASE=${BASE:-http://localhost:8080}
K6=${K6_BIN:-k6}
HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT=${K6_SCRIPT:-$HERE/k6/read_p99_ec2.js}

K6_SIDS=${K6_SIDS:?K6_SIDS 가 필요하다 — seed/seed_k6_read_account.sh 의 출력을 넘길 것}
K6_EMAIL=${K6_EMAIL:?K6_EMAIL 이 필요하다 — seed/seed_k6_read_account.sh 의 출력을 넘길 것}
K6_PASSWORD=${K6_PASSWORD:-K6read!2026}
VUS=${VUS:-50}

MULTS=${MULTS:-"60 180 360"}
DUR=${DUR:-120s}
BLOCKS=${BLOCKS:-4}
OUT=${OUT:-$HERE/results/http-read-p99-ec2-$(date +%F)}

PY=${PY:-python3}
command -v "$PY" >/dev/null 2>&1 || { echo "🔴 $PY 가 없다 — k6 요약 JSON 을 못 읽는다"; exit 1; }

mkdir -p "$OUT/logs" || exit 1
RAW="$OUT/raw.txt"

echo "# HTTP 읽기 p99 — 가정 피크 배수 스윕 (EC2 판정선 대면)"
echo
echo "## [1] 전제"
echo "  대상        : $BASE"
case "$BASE" in
  *localhost*|*127.0.0.1*)
    echo "  🔴 부하기와 대상이 **같은 박스**다 — 절대 p99 를 판정선(1s)에 대면 안 된다." ;;
  *) echo "  ✅ 원격 대상 — 부하기 CPU 가 대상과 섞이지 않는다" ;;
esac
echo "  k6          : $("$K6" version 2>&1 | head -1)"
echo "  커밋        : $(git -C "$HERE/.." rev-parse --short HEAD 2>/dev/null || echo NA)"
echo "  앵커        : 가정 피크 = 0.075 요청/초 (read_p99_ec2.js 내장)"
echo "  팔          : 배수 «$MULTS» · 판당 $DUR · ${BLOCKS}블록(0 버림) · 라틴 방격"
echo "  세션        : $(echo "$K6_SIDS" | tr ',' '\n' | grep -c .)개 (K6_SIDS)"

# 🔴 actuator 로 안 본다 — write 축과 같은 이유(9090 이 원격에선 안 열려 있는 게 정상).
PROBE=$(curl -s -o /dev/null -w '%{http_code}|%{time_total}' --max-time 20 "$BASE/sessions/active" 2>/dev/null)
echo "  응답 확인   : $BASE/sessions/active → ${PROBE%%|*} (${PROBE##*|}s, 인증 없이 부른 것이라 401 이 정상)"
case "${PROBE%%|*}" in
  401|403) ;;
  000) echo "  🔴 대상에 못 닿았다 — BASE·보안그룹·포트를 볼 것. 이대로 돌리면 전 판이 빈다"; exit 1 ;;
  *)   echo "  🟡 401/403 이 아니다 — 보안 설정이 다르거나 다른 앱이다. 블록 0 을 보고 판단할 것" ;;
esac

# 요약 JSON 한 장에서 필요한 값만 뽑는다 — 트렌드 넷(p50·p99) + 게이트 둘(bad_status·dropped_iterations) + iterations.
extract_row(){ # $1=json파일
  "$PY" - "$1" <<'PYEOF'
import json, sys
with open(sys.argv[1]) as f:
    d = json.load(f)
m = d.get("metrics", {})
def v(name, stat, default=0):
    return m.get(name, {}).get(stat, default)
row = [
    v("t_report_session", "med"), v("t_report_session", "p(99)"),
    # 🔴 실패(성공 아닌 응답) 레이턴시는 성공 열에 안 섞는다 — 게이트(bad_status>0)가 걸린
    #    판에서 「뭐가 느렸길래 실패했나」를 raw.txt 에서 따로 읽을 수 있어야 한다.
    v("t_report_session_fail", "med"), v("t_report_session_fail", "p(99)"), v("t_report_session_fail", "max"),
    v("t_weekly_summary", "med"), v("t_weekly_summary", "p(99)"),
    v("t_weekly_summary_fail", "med"), v("t_weekly_summary_fail", "p(99)"), v("t_weekly_summary_fail", "max"),
    v("t_calendar", "med"),       v("t_calendar", "p(99)"),
    v("t_calendar_fail", "med"), v("t_calendar_fail", "p(99)"), v("t_calendar_fail", "max"),
    v("t_daily", "med"),          v("t_daily", "p(99)"),
    v("t_daily_fail", "med"), v("t_daily_fail", "p(99)"), v("t_daily_fail", "max"),
    v("iterations", "count"),
    v("bad_status", "count"),
    v("dropped_iterations", "count"),
]
print(" ".join(str(x) for x in row))
PYEOF
}

run_one(){  # $1=배수  $2=블록
  local mult=$1 blk=$2 rate log jsonf
  rate=$(awk -v m="$mult" 'BEGIN{printf "%.4f", 0.075*m}')
  log="$OUT/logs/x${mult}-b${blk}.log"
  jsonf="$OUT/logs/x${mult}-b${blk}.json"
  MULT="$mult" DUR="$DUR" K6_SIDS="$K6_SIDS" K6_EMAIL="$K6_EMAIL" K6_PASSWORD="$K6_PASSWORD" VUS="$VUS" \
    "$K6" run --quiet -e BASE="$BASE" --summary-trend-stats "avg,med,p(95),p(99),max" \
    --summary-export="$jsonf" "$SCRIPT" > "$log" 2>&1
  if [ ! -s "$jsonf" ]; then
    echo "x$mult $blk $rate NA NA NA NA NA NA NA NA NA NA NA NA NA NA NA NA NA NA NA NA 0 NA NA"
    return
  fi
  echo "x$mult $blk $rate $(extract_row "$jsonf")"
}

echo
echo "## [2] 스윕"
echo "arm block rate s_p50 s_p99 sf_p50 sf_p99 sf_max w_p50 w_p99 wf_p50 wf_p99 wf_max c_p50 c_p99 cf_p50 cf_p99 cf_max d_p50 d_p99 df_p50 df_p99 df_max iters bad_status dropped" > "$RAW"
mv_arr=($MULTS); mn=${#mv_arr[@]}
for ((b=0;b<BLOCKS;b++)); do
  echo "  --- 블록 $b$([ "$b" = 0 ] && echo ' (버림)')"
  for ((k=0;k<mn;k++)); do
    line=$(run_one "${mv_arr[$(((k+b)%mn))]}" "$b")
    echo "$line" >> "$RAW"
    echo "    $line"
  done
  if [ "$b" = 0 ]; then
    bad=$(awk 'NR>1 && $2==0 && ($4=="NA" || $24+0==0) {c++} END{print c+0}' "$RAW")
    if [ "$bad" -gt 0 ]; then
      echo
      echo "  🔴 버림 블록에서 $bad 팔이 표본을 못 만들었다 — 스윕을 계속해 봐야 빈 표가 나온다."
      echo "     흔한 원인: K6_SIDS 가 비었거나 존재하지 않는 세션 id · BASE 오타 · signup 레이트리밋."
      echo "     첫 판 로그: $OUT/logs/"
      exit 1
    fi
  fi
done

# ── 게이트 ──────────────────────────────────────────────────────────────────
# 정책적 실패(policy failure) — 200 으로 응답은 왔지만 SLO 를 넘긴 경우. 새 임계값을
# 만들지 않고 docs/decisions/slo-baseline.md §4-2 의 기존 목표(1s, latency-perception.md
# 의 Nielsen UX 앵커)를 그대로 쓴다 — 이 게이트를 켜는 것 자체가 §4-2 "목표→판정선 승격"
# 결정(2026-09-04 사용자 confirm)이다.
POLICY_MS=1000
median_col() {  # $1=arm(예: x60) $2=컬럼(1-based)
  awk -v a="$1" -v col="$2" '
    NR>1 && $1==a && $2>0 { n++; v[n]=$col+0 }
    function med(arr,   i,j,t,c) {
      c=n; for(i=1;i<=c;i++) for(j=i+1;j<=c;j++) if(arr[j]<arr[i]) {t=arr[i];arr[i]=arr[j];arr[j]=t}
      return (c%2) ? arr[int((c+1)/2)] : (arr[c/2]+arr[c/2+1])/2
    }
    END{ if(n==0){print "NA"; exit} printf "%.2f", med(v) }
  ' "$RAW"
}
echo
echo "## [3] 🔴 게이트 — 이 셋 중 하나라도 깨지면 그 팔의 지연은 인용 금지"
GATE_OK=1
for m in $MULTS; do
  bs=$(awk -v a="x$m" 'NR>1 && $1==a && $2>0 {s+=$25} END{print s+0}' "$RAW")
  dr=$(awk -v a="x$m" 'NR>1 && $1==a && $2>0 {s+=$26} END{print s+0}' "$RAW")
  msg=""
  [ "$bs" -gt 0 ] && { msg="$msg bad_status=$bs(401/500 등 정상 아닌 응답)"; GATE_OK=0; }
  [ "$dr" -gt 0 ] && { msg="$msg dropped=$dr(도착률 미달성 → 「가정 피크 ×$m」 진술이 깨진다)"; GATE_OK=0; }
  for ep_col in "session:5" "weekly:10" "calendar:15" "daily:20"; do
    epname=${ep_col%%:*}; col=${ep_col##*:}
    p99=$(median_col "x$m" "$col")
    if [ "$p99" != "NA" ] && awk -v v="$p99" -v t="$POLICY_MS" 'BEGIN{exit !(v>t)}'; then
      msg="$msg ${epname}p99=${p99}ms>${POLICY_MS}ms(정책적 실패 — §4-2 목표 초과)"; GATE_OK=0
    fi
  done
  if [ -z "$msg" ]; then echo "  ✅ ×$m — bad_status 0 · dropped 0 · p99 ≤ ${POLICY_MS}ms(4개 엔드포인트)"; else echo "  🔴 ×$m —$msg"; fi
done
[ "$GATE_OK" = 1 ] || echo "  🔴 깨진 팔 중 bad_status·dropped 는 «부하를 못 걸었다/데이터가 없다» 는 뜻이지 «느리다» 는 뜻이 아니다. 정책적 실패(p99 초과)만 «실제로 느렸다» 는 뜻이다."

# ── 집계 ────────────────────────────────────────────────────────────────────
echo
echo "## [4] 집계 — 유효 블록(0 제외)의 중앙값"
{
echo "# HTTP 읽기 p99 @ 가정 피크 배수 — 생성 표 (판정은 README.md 에)"
echo
echo "대상 \`$BASE\` · 판당 $DUR · ${BLOCKS}블록(0 버림) · 라틴 방격 · K6_SIDS $(echo "$K6_SIDS" | tr ',' '\n' | grep -c .)개"
echo
echo "| 배수 | 도착률(요청/초) | session p50/p99 | weekly p50/p99 | calendar p50/p99 | daily p50/p99 | 표본 |"
echo "|---|--:|--:|--:|--:|--:|--:|"
for m in $MULTS; do
  awk -v a="x$m" -v mult="$m" '
    NR>1 && $1==a && $2>0 {
      n++; rate=$3
      sp50[n]=$4+0; sp99[n]=$5+0; wp50[n]=$9+0; wp99[n]=$10+0
      cp50[n]=$14+0; cp99[n]=$15+0; dp50[n]=$19+0; dp99[n]=$20+0; it+=$24
    }
    function med(arr,   i,j,t,c) {
      c=n; for(i=1;i<=c;i++) for(j=i+1;j<=c;j++) if(arr[j]<arr[i]) {t=arr[i];arr[i]=arr[j];arr[j]=t}
      return (c%2) ? arr[int((c+1)/2)] : (arr[c/2]+arr[c/2+1])/2
    }
    END{
      if(n==0){printf "| ×%s | — | — | — | — | — | 0 |\n", mult; exit}
      printf "| ×%s | %.2f | %.0f/**%.0f** | %.0f/**%.0f** | %.0f/**%.0f** | %.0f/**%.0f** | %d |\n",
             mult, rate, med(sp50),med(sp99), med(wp50),med(wp99), med(cp50),med(cp99), med(dp50),med(dp99), it
    }' "$RAW"
done
echo
echo "> 원자료: \`raw.txt\` · k6 로그: \`logs/\`"
} | tee "$OUT/table.md"

echo
echo "결과 → $OUT"
