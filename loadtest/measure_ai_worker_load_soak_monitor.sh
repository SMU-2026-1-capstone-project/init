#!/usr/bin/env bash
# AI 워커 부하-중 장애 빈도 — 대상 박스 모니터.
# soak(2026-08-26~27, project_ai_worker_soak_running)의 5분 간격 RestartCount 폴러를 확장 —
# OOMKilled·docker stats(mem/cpu%)·IN_PROGRESS 세션 수를 같이 걷는다.
# 설계: docs/decisions/ai-worker-load-soak-experiment.md §2
#
# 대상 박스(shadowfit-ai · shadowfit-mysql 컨테이너가 떠 있는 곳)에서 root로 nohup 실행.
set -uo pipefail

INTERVAL_SEC=${INTERVAL_SEC:-300}          # soak 와 같은 5분 간격
DURATION_SEC=${DURATION_SEC:-10800}        # 부하기 쪽과 맞춘다(3시간 기본)
TAIL_SEC=${TAIL_SEC:-600}                  # 부하 종료 후에도 10분 더 관찰(꼬리 회복 확인)
PW=${PW:-1234}
DB_NAME=${DB_NAME:-shadowfit}
OUT=${OUT:-/root/ai_worker_load_soak_monitor.log}

DEADLINE=$(( $(date +%s) + DURATION_SEC + TAIL_SEC ))

BASE_RESTART=$(docker inspect --format='{{.RestartCount}}' shadowfit-ai 2>/dev/null || echo "-1")
echo "epoch,restart_count,status,health,oom_killed,mem_pct,cpu_pct,in_progress_sessions" > "$OUT"
echo "START $(date -u +%FT%TZ) base_restart_count=$BASE_RESTART interval=${INTERVAL_SEC}s duration=${DURATION_SEC}s tail=${TAIL_SEC}s" >> "$OUT"

while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  ts=$(date +%s)
  rc=$(docker inspect --format='{{.RestartCount}}' shadowfit-ai 2>/dev/null || echo "-1")
  status=$(docker inspect --format='{{.State.Status}}' shadowfit-ai 2>/dev/null || echo "gone")
  health=$(docker inspect --format='{{.State.Health.Status}}' shadowfit-ai 2>/dev/null || echo "n/a")
  oom=$(docker inspect --format='{{.State.OOMKilled}}' shadowfit-ai 2>/dev/null || echo "n/a")
  stats=$(docker stats --no-stream --format '{{.MemPerc}}|{{.CPUPerc}}' shadowfit-ai 2>/dev/null || echo "n/a|n/a")
  mem_pct=${stats%%|*}; cpu_pct=${stats##*|}
  inprog=$(docker exec -e MYSQL_PWD="$PW" shadowfit-mysql mysql -uroot -N -e \
    "SELECT COUNT(*) FROM $DB_NAME.exercise_sessions WHERE status='IN_PROGRESS';" 2>/dev/null || echo "-1")

  echo "$ts,$rc,$status,$health,$oom,$mem_pct,$cpu_pct,$inprog" >> "$OUT"

  if [ "$rc" != "$BASE_RESTART" ]; then
    echo "EVENT $(date -u +%FT%TZ) restart_count changed: $BASE_RESTART -> $rc" >> "$OUT"
    BASE_RESTART=$rc
  fi

  sleep "$INTERVAL_SEC"
done

FINAL_RC=$(docker inspect --format='{{.RestartCount}}' shadowfit-ai 2>/dev/null || echo "-1")
FINAL_OOM=$(docker inspect --format='{{.State.OOMKilled}}' shadowfit-ai 2>/dev/null || echo "n/a")
echo "END $(date -u +%FT%TZ) final_restart_count=$FINAL_RC final_oom_killed=$FINAL_OOM" >> "$OUT"
