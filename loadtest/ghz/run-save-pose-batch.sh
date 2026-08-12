#!/usr/bin/env bash
# ② 백엔드 격리 부하 테스트 (load-test-strategy.md §3.2) — SavePoseDataBatch gRPC.
# bash 변형 (Windows 외 환경 / Git Bash). PowerShell 판은 run-save-pose-batch.ps1.
#
# 사전조건: gRPC :6565 reflection ON, $INTERNAL_API_TOKEN, ghz 설치, 그리고 세션 901~1900:
#   docker exec -i shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit < ../seed/seed-multi-sessions.sql
# 사용:
#   export INTERNAL_API_TOKEN="<server-token>"
#   ./run-save-pose-batch.sh smoke      # 경로·인증 검증
#   ./run-save-pose-batch.sh baseline   # 순차 1건
#   ./run-save-pose-batch.sh ramp       # 동시성 step ramp — throughput 천장
#
# 기본 페이로드는 다세션이다 (#166) — 단일 session 801 은 인덱스 리프 경합의 천장을 시스템의
# 천장으로 보이게 만든다. 그 조건을 일부러 재현하려면 DATA_FILE=batch.json.
set -euo pipefail

MODE="${1:-smoke}"
TARGET="${TARGET:-localhost:6565}"
DATA_FILE="${DATA_FILE:-batch_multi.json}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

: "${INTERNAL_API_TOKEN:?INTERNAL_API_TOKEN 미설정 — export 후 재실행}"
command -v ghz >/dev/null || { echo "ghz 미설치 (README §설치)"; exit 1; }
[ -f "$DATA_FILE" ] || { echo "$DATA_FILE 없음 (gen_batch_multi.py 로 생성)"; exit 1; }

# 프리플라이트 — 페이로드가 쓰는 세션이 없으면 ghz 는 판을 완주하고 «OK 0» 결과를 남긴다.
# 숫자가 나오므로 실패로 안 보인다. 시작 전에 막는다.
SESSION_LO=901; SESSION_HI=1900
[ "$DATA_FILE" = "batch.json" ] && { SESSION_LO=801; SESSION_HI=801; }
EXPECTED=$((SESSION_HI - SESSION_LO + 1))
HAVE=$(docker exec shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit -N \
  -e "SELECT COUNT(*) FROM exercise_sessions WHERE id BETWEEN $SESSION_LO AND $SESSION_HI;" 2>/dev/null | tail -1)
if [ "${HAVE:-0}" != "$EXPECTED" ]; then
  echo "$DATA_FILE 이 쓰는 세션 $SESSION_LO~$SESSION_HI 중 ${HAVE:-0}/$EXPECTED 개만 존재. 시드 먼저:"
  echo "  docker exec -i shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit < ../seed/seed-multi-sessions.sql"
  exit 1
fi

mkdir -p results

# 메타데이터는 파일로 전달 (--metadata-file) — 인라인 인용 이슈 회피, ps1 판과 일관. results/(gitignore).
META_FILE="results/metadata.json"
printf '{"authorization":"Bearer %s"}' "$INTERNAL_API_TOKEN" > "$META_FILE"
CALL="ExerciseService.SavePoseDataBatch"
COMMON=(--insecure --call "$CALL" --metadata-file "$META_FILE" --data-file "$DATA_FILE")

case "$MODE" in
  smoke)
    echo "[smoke] 경로·인증 검증 — 5 call, c=1"
    ghz "${COMMON[@]}" -n 5 -c 1 "$TARGET"
    ;;
  baseline)
    echo "[baseline] 단일 세션 순차 — 200 call, c=1"
    ghz "${COMMON[@]}" -n 200 -c 1 -O html -o results/baseline.html "$TARGET"
    echo "리포트: results/baseline.html"
    ;;
  ramp)
    echo "[ramp] 동시성 step 5->100 (10s/step) — throughput 천장 + p99"
    ghz "${COMMON[@]}" \
      --concurrency-schedule=step \
      --concurrency-start=5 --concurrency-step=5 --concurrency-end=100 \
      --concurrency-step-duration=10s \
      -z 210s \
      -O html -o results/ramp.html "$TARGET"
    echo "리포트: results/ramp.html — throughput 평탄 지점 = 천장, 그 p99 를 SLO 와 비교 (doc §11)"
    ;;
  *) echo "알 수 없는 mode: $MODE (smoke|baseline|ramp)"; exit 1 ;;
esac
