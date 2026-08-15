#!/bin/bash
# 동거 용량 본 측정 스윕 (P6) — 설계: docs/decisions/ai-coresidency-capacity.md
#
# 🔴 **`probe.sh` 가 통과해 있어야 한다.** 게이트 없이 이것만 돌리면 «세션이 안 열린 채
#    전 프레임이 거절됐는데 표는 정상» 인 판이 나온다.
#
# 팔 (설계 §3):
#   A  AI 단독                          — 기준선. rig 이 바뀌었으므로 156 을 이 rig 으로 다시 세운다
#   B  AI + Spring + MySQL, CPU 캡 없음  — 현행 prod 형태
#   C  위 + CPU 캡                      — 캡이 무엇을 지키는지 (#212)
#   D  위 + 관측 스택                    — 선택. ARMS 에서 빼면 판이 25% 준다
#
# 🔴 **팔 B 도 메모리 캡은 건다.** «캡 없음» 을 곧이곧대로 만들면 첫 세션에서 RuntimeError 라
#    팔이 아예 안 돈다(#214). 이 팔이 흔드는 것은 **CPU 캡** 하나다.
#
# 사용 (부하기 박스에서):
#   HOST=10.0.0.5 TOKEN=... bash coresidency_sweep.sh
#
# 무인 실행 전 축소 리허설:  LEVELS="5 10" DUR=20 REPEATS=1 bash coresidency_sweep.sh

set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-$HERE}

HOST=${HOST:?측정 대상 호스트 IP 가 필요하다}
BASE=${BASE:-http://$HOST:8080}
AI=${AI:-http://$HOST:8000}
TOKEN=${TOKEN:?AI_PUBLIC_TOKEN}
SSH=${SSH:-ssh root@$HOST}          # 팔 전환은 대상 박스에서 compose 를 다시 세운다
REPO_DIR=${REPO_DIR:-/root/init}

ARMS=${ARMS:-"A B C"}               # D 를 넣으려면 "A B C D"
LEVELS=${LEVELS:-"20 40 80 160"}    # 동시 세션 ramp
DUR=${DUR:-90}                      # 판당 측정 구간(초). 앞뒤 5초는 부하기가 버린다
REPEATS=${REPEATS:-3}               # 본판. 버림판 1은 팔마다 별도로 돈다
STATS_SEC=${STATS_SEC:-5}           # docker stats 폴링 간격 — 관측이 대상을 흔드는 비용

LOG="$OUT/coresidency.tsv"
[ -f "$LOG" ] || printf "arm\tround\tsessions\treq\trps\tdetect_pct\tp50\tp95\tp99\tnolease\tnopose\tsetup_fail\n" > "$LOG"

step() { echo; echo "════ $* ════"; }
note() { echo "     $*"; }

# ── 팔 전환 ──────────────────────────────────────────────────────────────
# compose 파일을 바꿔 끼우는 것이 아니라 **override 를 얹는다** — 원본 compose 를 실험이
# 고치면 다음 라운드가 «무엇을 쟀는지» 를 잃는다.
apply_arm() {
  local arm=$1
  note "팔 $arm 구성"
  case $arm in
    A) $SSH "cd $REPO_DIR && docker compose stop shadowfit-backend mysql 2>/dev/null; \
             docker compose --profile obs stop prometheus grafana mysqld-exporter 2>/dev/null; \
             docker compose up -d shadowfit-ai" ;;
    B) $SSH "cd $REPO_DIR && rm -f docker-compose.cap.yml; \
             docker compose --profile obs stop prometheus grafana mysqld-exporter 2>/dev/null; \
             docker compose up -d mysql shadowfit-backend shadowfit-ai" ;;
    C) $SSH "cd $REPO_DIR && cat > docker-compose.cap.yml <<'YML'
services:
  shadowfit-ai:   { cpus: \"\${AI_CPUS:?팔 C 는 AI_CPUS 가 필요하다}\" }
  mysql:          { cpus: \"\${MYSQL_CPUS:?}\" }
  shadowfit-backend: { cpus: \"\${BACKEND_CPUS:?}\" }
YML
             docker compose --profile obs stop prometheus grafana mysqld-exporter 2>/dev/null; \
             docker compose -f docker-compose.yml -f docker-compose.cap.yml up -d mysql shadowfit-backend shadowfit-ai" ;;
    D) $SSH "cd $REPO_DIR && docker compose -f docker-compose.yml -f docker-compose.cap.yml --profile obs up -d" ;;
    *) echo "🔴 모르는 팔: $arm" >&2; return 1 ;;
  esac
}

# ── 판 사이 초기화 ───────────────────────────────────────────────────────
# 🔴 세션 상태는 AI **프로세스 메모리**에 있다(`session_state.py:243`). 재기동 없이 다음 판을
#    돌리면 앞 판의 세션이 검출기 풀 자리를 물고 있어 «nolease» 가 앞 판 탓으로 난다.
reset_between() {
  $SSH "cd $REPO_DIR && docker compose restart shadowfit-ai >/dev/null 2>&1"
  sleep 15
}

# ── 지표 샘플러 ──────────────────────────────────────────────────────────
start_stats() {  # $1 = 태그
  $SSH "docker stats --no-stream --format '{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}' > /tmp/s_$1.tsv" >/dev/null 2>&1
  ( while :; do
      $SSH "docker stats --no-stream --format '{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}'" 2>/dev/null \
        | sed "s/^/$(date +%s)\t/" >> "$OUT/stats_$1.tsv"
      sleep "$STATS_SEC"
    done ) & echo $! > "/tmp/stats_$1.pid"
}
stop_stats() { [ -f "/tmp/stats_$1.pid" ] && kill "$(cat "/tmp/stats_$1.pid")" 2>/dev/null; rm -f "/tmp/stats_$1.pid"; }

# ── 한 판 ────────────────────────────────────────────────────────────────
run_one() {  # $1=팔 $2=라운드 $3=세션수
  local arm=$1 round=$2 n=$3 tag="${arm}_${round}_${n}"
  echo; echo "──────── $tag ────────"
  start_stats "$tag"
  python "$HERE/load_ai.py" --base "$BASE" --ai "$AI" --token "$TOKEN" \
      --frames "$HERE/frames.json" --sessions "$n" --dur "$DUR" \
      --out "$OUT/req_$tag.tsv" --label "$tag"
  local rc=$?
  stop_stats "$tag"
  if [ $rc -ne 0 ] || [ ! -f "$OUT/req_${tag}_summary.tsv" ]; then
    printf "%s\t%s\t%s\tFAIL\t-\t-\t-\t-\t-\t-\t-\t-\n" "$arm" "$round" "$n" >> "$LOG"
    return 1
  fi
  tail -1 "$OUT/req_${tag}_summary.tsv" \
    | awk -v a="$arm" -v r="$round" -v n="$n" -F'\t' \
      '{printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n", a,r,n,$5,$6,$7,$8,$9,$10,$11,$12,$13}' >> "$LOG"
}

# ── 실행 ─────────────────────────────────────────────────────────────────
[ -f "$HERE/frames.json" ] || { echo "🔴 frames.json 이 없다 — gen_frames.py 로 먼저 만든다"; exit 1; }

for arm in $ARMS; do
  step "팔 $arm"
  apply_arm "$arm" || exit 1
  sleep 20
  # 버림판 — 첫 판은 컨테이너 워밍업·JIT·버퍼풀을 가장 크게 탄다. **표에 안 넣는다.**
  note "버림판 (표에 안 들어간다)"
  run_one "$arm" "discard" "$(echo "$LEVELS" | awk '{print $1}')" >/dev/null 2>&1
  reset_between
  for r in $(seq 1 "$REPEATS"); do
    for n in $LEVELS; do
      run_one "$arm" "r$r" "$n" || true
      reset_between
    done
  done
done

echo; echo "════ 요약 ════"; cat "$LOG"
echo
echo "🔴 판정은 사람이 한다. 특히 볼 것:"
echo "   · nolease > 0  → 풀 자리 없음(용량). detect_pct 하락과 **다른 축**이다"
echo "   · nopose  > 0  → 검출이 깨짐(품질). #164 가 고친 지표가 다시 무너진 것일 수 있다"
echo "   · setup_fail>0 → 그 판의 «동시 세션 수» 는 목표값이 아니다. 값을 그대로 쓰지 말 것"
