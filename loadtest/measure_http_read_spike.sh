#!/usr/bin/env bash
# HTTP 읽기 경로 스파이크 테스트 — #587 · docs/decisions/read-path-spike-test.md
#
# 從 R14(constant-arrival-rate, 2026-08-28)는 "꾸준한 트래픽에 버티는가"만 쟀다. 이 스크립트는
# 같은 rig(k6/read_p99_ec2.js)의 SPIKE_MULT 모드로 급증·급락·회복을 한 판 안에서 잰다.
#
# 반복: 버림 1 + 유효 REPEATS 판 — 단일 판으로는 "이 결과가 우연"인지 못 가른다
# (feedback_measure_design_needs_repeats). 팔이 하나뿐이라 순서 교락은 없다.
set -u

BASE=${BASE:-http://localhost:8080}
K6=${K6_BIN:-k6}
HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT=${K6_SCRIPT:-$HERE/k6/read_p99_ec2.js}

K6_SIDS=${K6_SIDS:?K6_SIDS 가 필요하다 — seed/seed_k6_read_account.sh 의 출력을 넘길 것}
K6_EMAIL=${K6_EMAIL:?K6_EMAIL 이 필요하다 — seed/seed_k6_read_account.sh 의 출력을 넘길 것}
K6_PASSWORD=${K6_PASSWORD:-K6read!2026}

# 사용자 confirm (2026-08-29): 베이스라인 ×60 · 스파이크 ×3600(R14 상한 ×360의 10배) ·
# 스파이크 20초 · 회복 관찰 90초 · 급증/급락 램프 2초.
BASE_MULT=${BASE_MULT:-60}
SPIKE_MULT=${SPIKE_MULT:-3600}
BASELINE_DUR=${BASELINE_DUR:-30s}
RAMP_DUR=${RAMP_DUR:-2s}
SPIKE_DUR=${SPIKE_DUR:-20s}
RECOVERY_DUR=${RECOVERY_DUR:-90s}
VUS=${VUS:-300}
REPEATS=${REPEATS:-4}   # 버림 1 + 유효 3
OUT=${OUT:-$HERE/results/http-read-spike-ec2-$(date +%F)}

PY=${PY:-python3}
command -v "$PY" >/dev/null 2>&1 || { echo "🔴 $PY 가 없다 — k6 요약 JSON 을 못 읽는다"; exit 1; }

mkdir -p "$OUT/logs" || exit 1

echo "# HTTP 읽기 경로 스파이크 테스트 (#587)"
echo
echo "## [1] 전제"
echo "  대상        : $BASE"
case "$BASE" in
  *localhost*|*127.0.0.1*)
    echo "  🔴 부하기와 대상이 **같은 박스**다 — 절대값을 판정선에 대면 안 된다." ;;
  *) echo "  ✅ 원격 대상 — 부하기 CPU 가 대상과 섞이지 않는다" ;;
esac
echo "  k6          : $("$K6" version 2>&1 | head -1)"
echo "  커밋        : $(git -C "$HERE/.." rev-parse --short HEAD 2>/dev/null || echo NA)"
echo "  단계        : 베이스라인×$BASE_MULT($BASELINE_DUR) → 급증($RAMP_DUR) → 스파이크×$SPIKE_MULT($SPIKE_DUR) → 급락($RAMP_DUR) → 회복×$BASE_MULT($RECOVERY_DUR)"
echo "  반복        : ${REPEATS}판(0 버림)"
echo "  세션        : $(echo "$K6_SIDS" | tr ',' '\n' | grep -c .)개 (K6_SIDS)"

PROBE=$(curl -s -o /dev/null -w '%{http_code}|%{time_total}' --max-time 20 "$BASE/sessions/active" 2>/dev/null)
echo "  응답 확인   : $BASE/sessions/active → ${PROBE%%|*} (${PROBE##*|}s, 인증 없이 부른 것이라 401 이 정상)"
case "${PROBE%%|*}" in
  401|403) ;;
  000) echo "  🔴 대상에 못 닿았다 — BASE·보안그룹·포트를 볼 것. 이대로 돌리면 전 판이 빈다"; exit 1 ;;
  *)   echo "  🟡 401/403 이 아니다 — 보안 설정이 다르거나 다른 앱이다." ;;
esac

extract_row(){ # $1=json파일 $2=엔드포인트접두(t_report_session 등) $3=phase
  "$PY" - "$1" "$2" "$3" <<'PYEOF'
import json, sys
with open(sys.argv[1]) as f:
    d = json.load(f)
m = d.get("metrics", {})
name = f"{sys.argv[2]}__{sys.argv[3]}"
row = m.get(name, {})
print(row.get("med", "NA"), row.get("p(99)", "NA"), row.get("max", "NA"))
PYEOF
}

extract_scalar(){ # $1=json파일 $2=지표명 $3=stat
  "$PY" - "$1" "$2" "$3" <<'PYEOF'
import json, sys
with open(sys.argv[1]) as f:
    d = json.load(f)
m = d.get("metrics", {})
print(m.get(sys.argv[2], {}).get(sys.argv[3], 0))
PYEOF
}

EPS="t_report_session t_weekly_summary t_calendar t_daily"
PHASES="baseline rampup spike rampdown recovery"

RAW="$OUT/raw.tsv"
# 🔴 실패(성공 아닌 응답) 레이턴시는 f_ 접두로 따로 둔다 — 성공 p50/p99 에 안 섞는다.
echo -e "rep\tendpoint\tphase\tp50\tp99\tmax\tf_p50\tf_p99\tf_max" > "$RAW"

echo
echo "## [2] 실행"
for ((r=0; r<REPEATS; r++)); do
  jsonf="$OUT/logs/rep${r}.json"
  log="$OUT/logs/rep${r}.log"
  echo "  --- 판 $r$([ "$r" = 0 ] && echo ' (버림)')"
  SPIKE_MULT="$SPIKE_MULT" BASE_MULT="$BASE_MULT" BASELINE_DUR="$BASELINE_DUR" \
  RAMP_DUR="$RAMP_DUR" SPIKE_DUR="$SPIKE_DUR" RECOVERY_DUR="$RECOVERY_DUR" \
  VUS="$VUS" K6_SIDS="$K6_SIDS" K6_EMAIL="$K6_EMAIL" K6_PASSWORD="$K6_PASSWORD" \
    "$K6" run --quiet -e BASE="$BASE" --summary-trend-stats "avg,med,p(95),p(99),max" \
    --summary-export="$jsonf" "$SCRIPT" > "$log" 2>&1

  if [ ! -s "$jsonf" ]; then
    echo "    🔴 판 $r 요약 JSON 이 없다 — $log 를 볼 것"
    continue
  fi

  bad=$(extract_scalar "$jsonf" bad_status count)
  dropped=$(extract_scalar "$jsonf" dropped_iterations count)
  echo "    bad_status=$bad dropped_iterations=$dropped"

  for ep in $EPS; do
    for ph in $PHASES; do
      read -r p50 p99 mx <<< "$(extract_row "$jsonf" "$ep" "$ph")"
      read -r fp50 fp99 fmx <<< "$(extract_row "$jsonf" "${ep}_fail" "$ph")"
      echo -e "${r}\t${ep}\t${ph}\t${p50}\t${p99}\t${mx}\t${fp50}\t${fp99}\t${fmx}" >> "$RAW"
    done
  done
done

echo
echo "## [3] 🔴 게이트"
GATE_OK=1
for ((r=1; r<REPEATS; r++)); do
  jsonf="$OUT/logs/rep${r}.json"
  [ -s "$jsonf" ] || { echo "  🔴 판 $r 무효(JSON 없음)"; GATE_OK=0; continue; }
  bad=$(extract_scalar "$jsonf" bad_status count)
  dropped=$(extract_scalar "$jsonf" dropped_iterations count)
  if [ "${bad%.*}" != "0" ] || [ "${dropped%.*}" != "0" ]; then
    echo "  🔴 판 $r — bad_status=$bad dropped_iterations=$dropped (이 판은 «느리다» 가 아니라 부하를 못 걸었거나 응답이 비정상)"
    GATE_OK=0
  else
    echo "  ✅ 판 $r — bad_status 0 · dropped 0"
  fi
done
[ "$GATE_OK" = 1 ] || echo "  🔴 깨진 판은 인용 금지."

echo
echo "## [4] 집계 — 유효 판(0 제외)의 중앙값"
{
echo "# HTTP 읽기 스파이크 — 생성 표 (판정은 README.md 에)"
echo
echo "대상 \`$BASE\` · 베이스라인×$BASE_MULT·스파이크×$SPIKE_MULT($SPIKE_DUR)·회복 관찰 ${RECOVERY_DUR} · ${REPEATS}판(0 버림)"
echo
echo "| 엔드포인트 | 단계 | p50 (med) | p99 | 유효 판수 |"
echo "|---|---|--:|--:|--:|"
for ep in $EPS; do
  for ph in $PHASES; do
    awk -F'\t' -v ep="$ep" -v ph="$ph" '
      NR>1 && $1>0 && $2==ep && $3==ph && $4!="NA" {
        n++; p50[n]=$4+0; p99[n]=$5+0
      }
      function med(arr,   i,j,t,c) {
        c=n; for(i=1;i<=c;i++) for(j=i+1;j<=c;j++) if(arr[j]<arr[i]) {t=arr[i];arr[i]=arr[j];arr[j]=t}
        return (c%2) ? arr[int((c+1)/2)] : (arr[c/2]+arr[c/2+1])/2
      }
      END{
        if(n==0){printf "| %s | %s | — | — | 0 |\n", ep, ph; exit}
        printf "| %s | %s | %.1f | %.1f | %d |\n", ep, ph, med(p50), med(p99), n
      }' "$RAW"
  done
done
echo
echo "> 원자료: \`raw.tsv\` · k6 로그: \`logs/\`"
} | tee "$OUT/table.md"

echo
echo "결과 → $OUT"
