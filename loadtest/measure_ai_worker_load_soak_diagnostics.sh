#!/usr/bin/env bash
# AI 워커 부하-중 장애 재현 라운드 — 사고 순간의 직접 증거를 걷는 고빈도 폴러.
#
# 계기: 2026-08-28 첫 라운드(measure_ai_worker_load_soak_monitor.sh, 5분 간격)가 사고를
# 못 봤다 — 대상 전체가 무응답이 돼서 다음 5분 샘플 자체가 안 왔고, 사후 로그 뒤지기로만
# 간접 정황(MySQL 로그가 InnoDB "장기 세마포어 대기" 헤더에서 끊김)을 얻었다
# (docs/decisions/ai-worker-load-soak-experiment.md, 결과 README §3).
#
# 이 스크립트는 그 갭을 메운다 — SHOW ENGINE INNODB STATUS 와 백엔드 JVM 스레드 덤프를
# 5~10초 간격으로 걷어서, 사고가 다시 나면 "그 순간 무엇을 기다리고 있었는지"를 직접 남긴다.
# 5분 간격 모니터(RestartCount·OOMKilled·docker stats)는 그대로 별도로 계속 돈다 —
# 이 스크립트는 그것을 대체하지 않고 추가하는 것이다.
set -uo pipefail

INTERVAL_SEC=${INTERVAL_SEC:-7}
DURATION_SEC=${DURATION_SEC:-2700}   # 부하기 쪽과 맞춘다(45분 기본)
TAIL_SEC=${TAIL_SEC:-300}
PW=${PW:-1234}
OUT_DIR=${OUT_DIR:-/root}
INNODB_LOG="$OUT_DIR/innodb_status_poll.log"
THREADDUMP_LOG="$OUT_DIR/backend_threaddumps.log"

DEADLINE=$(( $(date +%s) + DURATION_SEC + TAIL_SEC ))

echo "START $(date -u +%FT%TZ) interval=${INTERVAL_SEC}s duration=${DURATION_SEC}s tail=${TAIL_SEC}s" | tee -a "$INNODB_LOG"

# 백엔드 이미지가 eclipse-temurin:21-jre 라 jstack 이 없다(JDK 전용 도구) — 대신
# kill -3(SIGQUIT)로 JVM 자체 스레드 덤프를 표준출력에 찍게 하고, docker logs -f 로
# 그 출력을 계속 받아 적는다. 이 tail 은 폴링 루프와 별개로 한 번만 시작한다.
: > "$THREADDUMP_LOG"
docker logs -f --since 0s shadowfit-backend >> "$THREADDUMP_LOG" 2>&1 &
LOGS_TAIL_PID=$!
echo "  backend docker logs tail 시작 (pid $LOGS_TAIL_PID) -> $THREADDUMP_LOG" | tee -a "$INNODB_LOG"

while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  ts=$(date -u +%FT%T.%3NZ)
  {
    echo "=== $ts ==="
    docker exec -e MYSQL_PWD="$PW" shadowfit-mysql mysql -uroot -e "SHOW ENGINE INNODB STATUS\G" 2>&1
  } >> "$INNODB_LOG" || echo "=== $ts === (INNODB STATUS 조회 실패 — MySQL 무응답일 수 있음)" >> "$INNODB_LOG"

  # 이 marker 로 THREADDUMP_LOG 안에서 언제 덤프를 요청했는지 찾는다.
  echo "--- kill -3 요청 $ts ---" >> "$THREADDUMP_LOG"
  docker exec shadowfit-backend kill -3 1 2>>"$THREADDUMP_LOG" || echo "  kill -3 실패($ts) — 백엔드 컨테이너 무응답일 수 있음" >> "$INNODB_LOG"

  sleep "$INTERVAL_SEC"
done

kill "$LOGS_TAIL_PID" 2>/dev/null || true
echo "END $(date -u +%FT%TZ)" | tee -a "$INNODB_LOG"
