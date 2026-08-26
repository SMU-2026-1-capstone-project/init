#!/bin/bash
# AI 워커 프로세스 하나를 강제로 죽였을 때 실제로 무슨 일이 일어나는지 재는 프로브.
#
# 계기: docs/decisions/ai-channel-pool-hardening.md §6 미결 질문 —
# "AI 프로세스 장애가 지금까지 몇 번·얼마나 발생했는지 측정된 적이 없다.
#  재배치 로직을 만들기 전에 먼저 영향도를 잴지" 에 답하기 위한 실측.
#
# 🔴 착수 전에 코드로 확인한 것 — ai-server/entrypoint.sh:
#   워커 하나라도 죽으면 `wait -n; exit $?` 로 컨테이너 전체(워커 3개 다)가 같이 내려간다.
#   "일부만 살아있는 상태를 정상으로 보이게 두지 않기 위해서"라는 의도적 설계다.
#   즉 원래 검토했던 "다른 살아있는 채널로 재배치"는 성립하지 않는다 — 재배치할 살아있는
#   대상 자체가 없다. 그래서 이 스크립트가 재는 것은 재배치 비용이 아니라:
#     ① 컨테이너 전체가 죽었다 healthy 로 복귀하는 데 걸리는 시간(다운타임)
#     ② 그 창 동안 Spring(서킷브레이커·gRPC deadline)과 nginx(프론트 경로)가 무엇을 보는가
#
# 사전 조건: `docker compose up -d`로 스택(mysql, shadowfit-backend, shadowfit-ai, ai-nginx)이
# 이미 떠 있어야 한다. 이 스크립트는 스택을 새로 띄우지 않는다 — 판마다 재기동 비용을 안 지려고.
set -e

COMPOSE_PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$COMPOSE_PROJECT_DIR"

TARGET_PORT="${1:-8001}"  # 죽일 워커의 HTTP 포트. 기본 8001(워커 인덱스 1).
POLL_INTERVAL=0.5
POLL_MAX=240  # 0.5초 × 240 = 최대 120초까지 관찰

RESULT_DIR="loadtest/results/ai-worker-kill-probe-$(date +%Y-%m-%d)"
mkdir -p "$RESULT_DIR"
LOG="$RESULT_DIR/probe.log"
TIMELINE="$RESULT_DIR/timeline.csv"

log() { echo "[$(date '+%H:%M:%S.%3N')] $*" | tee -a "$LOG"; }

log "사전 확인: shadowfit-ai 컨테이너 상태"
if ! docker inspect --format='{{.State.Status}}' shadowfit-ai >/dev/null 2>&1; then
  log "🔴 shadowfit-ai 컨테이너가 없다 — 'docker compose up -d' 로 스택을 먼저 띄울 것"
  exit 1
fi
docker compose ps shadowfit-ai shadowfit-backend ai-nginx 2>&1 | tee -a "$LOG"

# procps(ps/pkill)가 이미지에 없을 수 있어(python:3.12-slim 기본 이미지엔 없다) /proc을
# 직접 스캔해 대상 워커 PID를 찾는다 — 새 패키지 설치 없이 컨테이너 안에서 바로 됨.
log "워커(포트 $TARGET_PORT) PID 탐색·강제 종료(SIGKILL)"
docker compose exec -T shadowfit-ai python3 -c "
import os, signal
target = '$TARGET_PORT'
killed = False
for pid in os.listdir('/proc'):
    if not pid.isdigit():
        continue
    try:
        with open(f'/proc/{pid}/cmdline', 'rb') as f:
            cmdline = f.read().decode(errors='ignore')
    except (FileNotFoundError, ProcessLookupError):
        continue
    if 'uvicorn' in cmdline and target in cmdline:
        os.kill(int(pid), signal.SIGKILL)
        print(f'killed pid={pid} cmdline={cmdline!r}')
        killed = True
if not killed:
    print(f'포트 {target} 을 쓰는 uvicorn 프로세스를 못 찾음')
    raise SystemExit(1)
" 2>&1 | tee -a "$LOG"

KILL_TS=$(date +%s.%N)
log "kill 시각: $KILL_TS"

echo "epoch,container_status,container_health,nginx_health_http" > "$TIMELINE"

log "폴링 시작 (${POLL_INTERVAL}s 간격, 최대 $(awk "BEGIN{print $POLL_MAX * $POLL_INTERVAL}")s)"
RECOVERED=0
for i in $(seq 1 "$POLL_MAX"); do
  STATUS=$(docker inspect --format='{{.State.Status}}' shadowfit-ai 2>/dev/null || echo "gone")
  HEALTH=$(docker inspect --format='{{.State.Health.Status}}' shadowfit-ai 2>/dev/null || echo "n/a")
  NGINX_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 1 http://localhost:8000/health 2>/dev/null || echo "000")
  NOW=$(date +%s.%N)
  echo "$NOW,$STATUS,$HEALTH,$NGINX_CODE" >> "$TIMELINE"
  if [ "$STATUS" = "running" ] && [ "$HEALTH" = "healthy" ]; then
    log "healthy 재확인 (poll #$i)"
    RECOVERED=1
    RECOVER_TS="$NOW"
    break
  fi
  sleep "$POLL_INTERVAL"
done

if [ "$RECOVERED" = "1" ]; then
  DOWNTIME=$(awk "BEGIN{printf \"%.1f\", $RECOVER_TS - $KILL_TS}")
  log "다운타임(kill → healthy 재확인): ${DOWNTIME}초"
else
  log "🔴 최대 관찰 시간 안에 healthy 로 안 돌아왔다 — POLL_MAX를 늘려서 재관찰할 것"
fi

log "Spring 서킷브레이커 현재 상태(kill 직후~복구 후 사이 관찰용)"
curl -s http://localhost:9090/actuator/circuitbreakers 2>&1 | tee -a "$LOG" || log "actuator 접근 실패(9090 미개방 등)"

log "완료. 원시 타임라인: $TIMELINE"
log "요약: 이 실행은 워커 1개(포트 $TARGET_PORT)만 죽였는데도 컨테이너 전체가 재기동됐는지,"
log "      그렇다면 그 다운타임이 얼마인지를 timeline.csv 의 container_status/health 전이로 확인할 것"
