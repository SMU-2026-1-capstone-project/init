#!/usr/bin/env bash
# #598 — extractReferenceData·completeAnalysis·reportFeedbackBatch 의 in-flight 패턴을
# savePoseDataBatch 와 같은 방식(ghz ramp + in-flight 게이지 폴링)으로 잰다.
#
# savePoseDataBatch 하나만 2026-08-28에 애드혹으로 쟀던 것(원시 데이터 gitignore, 재현 불가)을
# 이 스크립트로 재현 가능하게 만든다 — 세 핸들러 전부, 그리고 savePoseDataBatch 자체도 다시
# 돌려서 같은 조건에서 넷을 나란히 비교할 수 있게 한다.
#
# 전제: 격리 스택(shadowfit-iso, docker-compose.iso.yml)이 떠 있고, exercises 1~3 ·
#   exercise_sessions 1~200(exercise_id=1 로 통일 — #598 조사 중 발견: 런지/플랭크는
#   feedback_type 지원 목록이 좁아 ReportFeedbackBatch 가 INVALID_ARGUMENT 로 배치 전체를
#   거절한다, exercise_feedback_templates 참고)이 시딩돼 있을 것.
#
# 🔴 로컬(물리 2코어) 박스, 게다가 이번엔 공유 스택(크래시 루프 중인 shadowfit-backend)과
#    여러 워크트리 컨테이너가 동거 중이라 이 라운드가 그 어느 때보다 자원 경합이 심하다.
#    절대 in-flight 숫자·지연은 무의미 — plateau 가 붙는지 안 붙는지만 본다
#    ([[project_loadtest_env_constraint]]).
set -uo pipefail
cd "$(dirname "$0")/.."

TARGET=${TARGET:-localhost:16565}
ACTUATOR=${ACTUATOR:-http://localhost:19090/actuator/prometheus}
GHZ=${GHZ_BIN:-./loadtest/.bin/ghz.exe}
DATA_DIR=${DATA_DIR:-/tmp/r598}
LEVELS=${LEVELS:-"5 15 30 50 80"}
DURATION=${DURATION:-8s}
OUT=${OUT:-loadtest/results/r598-remaining-handlers-$(date +%F)}
POLL_INTERVAL=${POLL_INTERVAL:-0.25}

mkdir -p "$OUT" "$DATA_DIR"

TOKEN=$(grep '^INTERNAL_API_TOKEN=' .env | cut -d= -f2-)
[ -n "$TOKEN" ] || { echo "🔴 INTERNAL_API_TOKEN 없음 (.env)"; exit 1; }
printf '{"authorization":"Bearer %s"}' "$TOKEN" > "$DATA_DIR/meta.json"

[ -x "$GHZ" ] || { echo "🔴 ghz 없음: $GHZ"; exit 1; }

python loadtest/ghz/gen_r598_calls.py --out-dir "$DATA_DIR" --n 400 || exit 1

gauge() { # $1 = method name
  curl -s "$ACTUATOR" 2>/dev/null | grep "^shadowfit_grpc_server_inflight{method=\"$1\"}" | awk '{print $2}'
}

run_method() { # $1=method $2=datafile $3=metric_method_label
  local method="$1" file="$2" label="$3"
  echo "## $method" | tee -a "$OUT/summary.md"
  echo "level,peak_inflight,ok,errors" > "$OUT/${method}.csv"
  for c in $LEVELS; do
    local pollfile="$DATA_DIR/${method}_${c}.poll.tsv"
    : > "$pollfile"
    ( while true; do
        v=$(gauge "$label")
        [ -n "$v" ] && echo "$(date +%s.%N) $v" >> "$pollfile"
        sleep "$POLL_INTERVAL"
      done ) &
    local poller=$!

    local ghzout="$DATA_DIR/${method}_${c}.ghz.json"
    "$GHZ" --insecure --call "ExerciseService.$method" \
      --metadata-file "$DATA_DIR/meta.json" --data-file "$DATA_DIR/$file" \
      -z "$DURATION" -c "$c" -O json -o "$ghzout" "$TARGET" >/dev/null 2>"$DATA_DIR/${method}_${c}.err"

    kill "$poller" 2>/dev/null; wait "$poller" 2>/dev/null

    local peak ok errs
    peak=$(awk '{print $2}' "$pollfile" | sort -n | tail -1)
    ok=$(python -c "import json;d=json.load(open('$ghzout'));print(d.get('statusCodeDistribution',{}).get('OK',0))" 2>/dev/null || echo NA)
    errs=$(python -c "
import json
d=json.load(open('$ghzout'))
dist=d.get('statusCodeDistribution',{})
print(sum(v for k,v in dist.items() if k!='OK'))
" 2>/dev/null || echo NA)
    echo "c=$c peak_inflight=$peak ok=$ok errors=$errs" | tee -a "$OUT/summary.md"
    echo "$c,$peak,$ok,$errs" >> "$OUT/${method}.csv"
    cp "$pollfile" "$OUT/${method}_c${c}_poll.tsv"
  done
  echo | tee -a "$OUT/summary.md"
}

echo "# r598 in-flight ramp — $(date -u +%FT%TZ)" > "$OUT/summary.md"
echo "target=$TARGET levels=[$LEVELS] duration=$DURATION" >> "$OUT/summary.md"
echo >> "$OUT/summary.md"

run_method "ExtractReferenceData" "extract_reference.json" "ExtractReferenceData"
run_method "CompleteAnalysis" "complete_analysis.json" "CompleteAnalysis"
run_method "ReportFeedbackBatch" "feedback_batch.json" "ReportFeedbackBatch"

echo "완료 — $OUT/summary.md 및 *.csv/*_poll.tsv 참고"
