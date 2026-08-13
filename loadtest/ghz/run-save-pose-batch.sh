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
# ghz 경로 — ps1 판(_ghz-path.ps1)과 같은 규칙: 저장소 .bin 우선, 없으면 PATH (#194).
# 전에는 PATH 만 봐서, .bin 에 바이너리를 두고도 «미설치» 라는 말을 들었다.
# 이 스크립트는 Git Bash(.exe)와 Linux(확장자 없음) 양쪽에서 돈다 — 둘 다 본다.
GHZ_BIN=""
for cand in "$HERE/../.bin/ghz.exe" "$HERE/../.bin/ghz"; do
  [ -x "$cand" ] && { GHZ_BIN="$cand"; break; }
done
if [ -n "$GHZ_BIN" ]; then
  GHZ="$GHZ_BIN"; GHZ_ORIGIN="저장소 .bin"
elif command -v ghz >/dev/null 2>&1; then
  GHZ="$(command -v ghz)"; GHZ_ORIGIN="PATH"
else
  # «미설치» 라고 하지 않는다 — 설치는 됐는데 이 스크립트가 보는 자리에 없는 경우가 더 흔하고,
  # 그때 «재설치하세요» 는 시간을 태운다. 찾아본 자리를 그대로 적는다.
  echo "ghz 를 못 찾았습니다. 찾아본 자리:"
  echo "  1) $HERE/../.bin/ghz[.exe]   (없음)"
  echo "  2) PATH 의 ghz          (없음)"
  echo "둘 중 하나를 채우세요 (loadtest/README.md §ghz 설치). .bin/ 은 gitignore 대상이라 clone 만으로는 안 생깁니다."
  exit 1
fi
# 어느 바이너리로 쟀는지 남긴다 — 결과만 보고 사후에 «그때 뭘로 쟀나» 를 물으면 답이 없다.
echo "[ghz] $GHZ_ORIGIN — $GHZ ($("$GHZ" --version 2>&1 | head -1))"
[ -f "$DATA_FILE" ] || { echo "$DATA_FILE 없음 (gen_batch_multi.py 로 생성)"; exit 1; }

# 프리플라이트 — 페이로드가 쓰는 세션이 없으면 ghz 는 판을 완주하고 «OK 0» 결과를 남긴다.
# 숫자가 나오므로 실패로 안 보인다. 시작 전에 막는다.
#
# 세션 집합은 페이로드에서 직접 읽는다(ps1 판의 _payload-sessions.ps1 과 같은 규약) — 상수로
# 두면 gen_batch_multi.py --sessions 를 다른 범위로 재생성했을 때 조용히 어긋난다.
# || true 가 필요하다: set -euo pipefail 아래에서 grep 이 아무것도 못 찾으면 exit 1 이고,
# 그러면 이 치환에서 스크립트가 **바로 끝나** 아래 형식 오류 메시지가 출력되지 않는다.
# 「sessionId 를 못 찾았다」를 알려주려고 쓴 줄이 정작 도달 불가였다 (PR #172 리뷰).
# 바로 아래 HAVE=$(...) 에 같은 이유로 || true 를 붙여놓고 이 줄에서 놓쳤다.
SESSION_IDS=$(grep -o '"sessionId"[[:space:]]*:[[:space:]]*[0-9]\+' "$DATA_FILE" | grep -o '[0-9]\+$' | sort -un || true)
[ -n "$SESSION_IDS" ] || { echo "$DATA_FILE 에서 sessionId 를 못 찾았다 — 페이로드 형식 확인 필요"; exit 1; }
EXPECTED=$(printf '%s\n' "$SESSION_IDS" | wc -l | tr -d ' ')
SQL_IN=$(printf '%s\n' "$SESSION_IDS" | paste -sd, -)

# `set -e` 아래에서 HAVE=$(cmd) 는 cmd 실패 시 그대로 스크립트를 끝낸다 — docker 미기동·컨테이너
# 부재가 «세션이 없다» 가 아니라 «조용한 종료» 로 나온다. || true 로 받아 아래에서 함께 판정한다.
HAVE=$(docker exec shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit -N \
  -e "SELECT COUNT(*) FROM exercise_sessions WHERE id IN ($SQL_IN);" 2>/dev/null | tail -1) || true

# «세션이 없다» 와 «물어보지도 못했다» 는 다른 사건이다. 구분하지 않으면 docker 데몬이 내려간
# 상황에서 «시드를 적용하세요» 라고 안내하게 되고, 시드를 아무리 넣어도 안 고쳐진다.
# (2026-08-12 에 실제로 그렇게 나왔다 — Docker Desktop 이 죽어 있었다.)
case "$HAVE" in
  ''|*[!0-9]*)
    echo "[preflight] 세션 수를 못 물어봤습니다 — MySQL 컨테이너에 질의가 실패했습니다."
    echo "            docker 데몬과 shadowfit-mysql 컨테이너 상태를 먼저 확인하세요:"
    echo "            docker ps --filter name=shadowfit-mysql"
    echo "            (이건 «시드가 없다» 가 아닙니다. 시드를 넣어도 안 고쳐집니다.)"
    exit 1
    ;;
esac

if [ "$HAVE" != "$EXPECTED" ]; then
  echo "$DATA_FILE 이 쓰는 세션 $EXPECTED 개 중 $HAVE 개만 존재. 시드 먼저:"
  echo "  docker exec -i shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit < ../seed/seed-multi-sessions.sql"
  exit 1
fi
echo "[preflight] 세션 ${HAVE}/${EXPECTED} ✅"

mkdir -p results

# 메타데이터는 파일로 전달 (--metadata-file) — 인라인 인용 이슈 회피, ps1 판과 일관. results/(gitignore).
META_FILE="results/metadata.json"
printf '{"authorization":"Bearer %s"}' "$INTERNAL_API_TOKEN" > "$META_FILE"
CALL="ExerciseService.SavePoseDataBatch"
COMMON=(--insecure --call "$CALL" --metadata-file "$META_FILE" --data-file "$DATA_FILE")

case "$MODE" in
  smoke)
    echo "[smoke] 경로·인증 검증 — 5 call, c=1"
    "$GHZ" "${COMMON[@]}" -n 5 -c 1 "$TARGET"
    ;;
  baseline)
    echo "[baseline] 순차 — 200 call, c=1 (동시성 1 이라 페이로드 분산과 무관)"
    "$GHZ" "${COMMON[@]}" -n 200 -c 1 -O html -o results/baseline.html "$TARGET"
    echo "리포트: results/baseline.html"
    ;;
  ramp)
    echo "[ramp] 동시성 step 5->100 (10s/step) — throughput 천장 + p99"
    "$GHZ" "${COMMON[@]}" \
      --concurrency-schedule=step \
      --concurrency-start=5 --concurrency-step=5 --concurrency-end=100 \
      --concurrency-step-duration=10s \
      -z 210s \
      -O html -o results/ramp.html "$TARGET"
    echo "리포트: results/ramp.html — throughput 평탄 지점 = 천장, 그 p99 를 SLO 와 비교 (doc §11)"
    ;;
  *) echo "알 수 없는 mode: $MODE (smoke|baseline|ramp)"; exit 1 ;;
esac
