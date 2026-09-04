#!/usr/bin/env bash
# gRPC SavePoseDataBatch — 무부하 단일요청(c=1) 지연 실측
#
# 배경: #676. slo-baseline.md §4-2 가 gRPC 정책적 실패 게이트를 보류한 이유가 이것이다 —
# 지금까지 이 RPC 의 유일한 실측(§4-0)은 c=100 동시 부하 조건(p50 429ms · p99 488~1,047ms)
# 뿐이고, "무부하 단일요청" 이 얼마인지는 이 저장소에 값이 없다. 그 값을 여기서 채운다.
#
# 🔴 이 값으로 «곧바로 SLO 임계» 를 만들지 않는다 — 한 판의 목적은 «c=100 이 낸 429~1,047ms가
#    동시성(락 경합·풀 대기) 때문인지, 아니면 이 RPC 자체가 원래 느린지」를 가르는 것이다.
#    임계를 어디에 둘지는 이 값을 보고 별도로 결정한다([[feedback_no_arbitrary_threshold_values]]).
#
# 설계:
#   - ghz `-c 1` (닫힌 루프, 동시성 1) — 요청 하나가 끝나야 다음이 나간다. 이게 "무부하"의 정의.
#     동시에 다른 부하도 없다(이 rig 혼자 이 대상을 쓴다는 전제 — README 참고).
#   - 페이로드는 기존 c=100 스윕과 같은 batch_multi.json — 프로덕션 요청 모양과 같다.
#   - 버림 1판 + 유효 N판(기본 4, feedback_measure_design_needs_repeats 관례) — 한 판만으로는
#     "이 결과가 우연"인지 못 가른다. 판마다 -n 200(기본)씩 시퀀셜 왕복시간을 뽑는다.
#
# 필요한 환경변수:
#   TARGET_HOST   대상(p6-target) 사설/공인 IP — ghz 가 이 주소의 6565 를 친다
#   GHZ_TOKEN     대상 .env 의 INTERNAL_API_TOKEN
#   GHZ_DATA      부하기의 batch_multi.json 경로 (기본 /root/batch_multi.json)
#   GHZ_BIN       ghz 바이너리 경로 (기본 /usr/local/bin/ghz)
#   OUT           결과 디렉터리
#   N             판당 요청 수 (기본 200)
#   ROUNDS        버림 포함 총 판 수 (기본 5 = 버림 1 + 유효 4)

set -uo pipefail

: "${TARGET_HOST:?TARGET_HOST 미설정}" "${GHZ_TOKEN:?GHZ_TOKEN 미설정}"
OUT="${OUT:?OUT 미설정}"
GHZ_DATA=${GHZ_DATA:-/root/batch_multi.json}
GHZ_BIN=${GHZ_BIN:-/usr/local/bin/ghz}
N=${N:-200}
ROUNDS=${ROUNDS:-5}

mkdir -p "$OUT/logs" || exit 1
RAW="$OUT/raw.tsv"

note() { echo "  $*"; }
die()  { echo "🔴 중단 — $*" >&2; exit 1; }

command -v "$GHZ_BIN" >/dev/null 2>&1 || [ -x "$GHZ_BIN" ] || die "ghz 를 못 찾았다: $GHZ_BIN"
[ -f "$GHZ_DATA" ] || die "페이로드가 없다: $GHZ_DATA"

echo "# gRPC SavePoseDataBatch 무부하(c=1) 단일요청 지연 — #676"
echo "  대상 : $TARGET_HOST:6565 (c=1, n=$N, ${ROUNDS}판 — 0번 버림)"
echo "  대조 : §4-0 c=100 동시성 baseline p50 429ms / p99 488~1,047ms"
echo

printf "round\tcount\tok\tfail\tp50_ms\tp95_ms\tp99_ms\tmax_ms\n" > "$RAW"

run_round() {  # $1=라운드번호
  local r=$1 tag="r${r}"
  printf '{"authorization":"Bearer %s"}' "$GHZ_TOKEN" > "$OUT/_meta.json"
  "$GHZ_BIN" --insecure --call ExerciseService.SavePoseDataBatch \
    --metadata-file "$OUT/_meta.json" --data-file "$GHZ_DATA" \
    -c 1 -n "$N" -O json -o "$OUT/logs/$tag.json" "$TARGET_HOST:6565" > "$OUT/logs/$tag.log" 2>&1
  local rc=$?
  if [ $rc -ne 0 ] || [ ! -s "$OUT/logs/$tag.json" ]; then
    echo "  🔴 [$tag] ghz 실행 실패 (rc=$rc) — $OUT/logs/$tag.log 를 볼 것" >&2
    printf "%s\tFAIL\t-\t-\t-\t-\t-\t-\n" "$r" >> "$RAW"
    return 1
  fi
  python3 - "$OUT/logs/$tag.json" "$r" "$RAW" <<'PY'
import json, sys
f, r, log = sys.argv[1:4]
j = json.load(open(f, encoding='utf-8'))
sc = j.get("statusCodeDistribution") or {}
count = j.get("count", 0); ok = sc.get("OK", 0); fail = count - ok

def pct(sorted_ns, p):
    if not sorted_ns:
        return ""
    idx = min(len(sorted_ns) - 1, int(len(sorted_ns) * p / 100))
    return round(sorted_ns[idx] / 1e6, 3)

details = j.get("details") or []
ok_lat = sorted(d["latency"] for d in details if d.get("status") == "OK")
p50, p95, p99 = pct(ok_lat, 50), pct(ok_lat, 95), pct(ok_lat, 99)
mx = round(max(ok_lat) / 1e6, 3) if ok_lat else ""
with open(log, "a", encoding='utf-8') as fh:
    fh.write(f"{r}\t{count}\t{ok}\t{fail}\t{p50}\t{p95}\t{p99}\t{mx}\n")
PY
}

for ((r=0; r<ROUNDS; r++)); do
  note "──── 판 $r$([ "$r" = 0 ] && echo ' (버림)') ────"
  run_round "$r"
  tail -1 "$RAW" | awk -F'\t' '{printf "    count=%s ok=%s fail=%s p50=%sms p95=%sms p99=%sms max=%sms\n",$2,$3,$4,$5,$6,$7,$8}'
done

echo
echo "## 게이트 — 유효판(1~$((ROUNDS-1))) 전부 fail=0 이어야 인용 가능"
GATE_OK=1
awk -F'\t' -v rounds="$ROUNDS" 'NR>1 && $1+0>0 { if ($4!="0" && $4!="-") { print "  🔴 판 "$1" fail="$4; bad=1 } } END{ if(!bad) print "  ✅ 전 유효판 fail=0" }' "$RAW"

echo
echo "## 집계 — 유효판(0 제외)의 중앙값"
{
echo "# gRPC SavePoseDataBatch 무부하(c=1) 단일요청 지연 — #676"
echo
echo "대상 \`$TARGET_HOST\` · c=1 · n=$N/판 · ${ROUNDS}판(0 버림)"
echo
echo "| 지표 | 값 |"
echo "|---|--:|"
awk -F'\t' '
  NR>1 && $1+0>0 && $2!="FAIL" { n++; p50[n]=$5+0; p95[n]=$6+0; p99[n]=$7+0; mx[n]=$8+0 }
  function med(arr,   i,j,t,c) {
    c=n; for(i=1;i<=c;i++) for(j=i+1;j<=c;j++) if(arr[j]<arr[i]) {t=arr[i];arr[i]=arr[j];arr[j]=t}
    return (c%2) ? arr[int((c+1)/2)] : (arr[c/2]+arr[c/2+1])/2
  }
  END{
    if(n==0){print "| (유효판 없음) | — |"; exit}
    printf "| p50 중앙값 | %.2fms |\n", med(p50)
    printf "| p95 중앙값 | %.2fms |\n", med(p95)
    printf "| p99 중앙값 | %.2fms |\n", med(p99)
    printf "| max 중앙값 | %.2fms |\n", med(mx)
    printf "| 유효판 수 | %d |\n", n
  }' "$RAW"
echo
echo "> §4-0 c=100 동시성 baseline: p50 429ms / p95 447ms / p99 488~1,047ms — 위 값과 나란히 놓고 읽을 것"
echo "> 원자료: \`raw.tsv\` · ghz 로그: \`logs/\`"
} | tee "$OUT/table.md"

echo
echo "결과 → $OUT"
