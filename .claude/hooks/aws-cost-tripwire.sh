#!/usr/bin/env bash
# 관측 전용 AWS 비용 트립와이어 — 차단하지 않는다, 임계값도 없다.
#
# Project=shadowfit-measure 태그의 실행/대기 중 EC2 인스턴스를 조회해서 몇 대가,
# 각각 얼마나 오래 떠 있는지만 사용자에게 보여준다. "이상"의 기준(개수·시간 임계값)은
# 아직 이 세션에서 확인된 근거가 없어서(임의로 못 박지 말 것) 넣지 않았다 — 관측 데이터가
# 쌓이면 그때 기준을 정한다.
#
# 두 시점에서 호출된다:
#   - SessionStart: 이전 라운드가 실패해서(업로드 실패 시 박스를 안 끄는 패턴,
#     bootstrap.sh 참고) 방치된 인스턴스를 세션 시작하자마자 알아채기 위함.
#   - PreToolUse(Bash, "aws ec2 run-instances"): 새 인스턴스를 또 띄우기 직전에
#     이미 떠 있는 게 뭔지 인지하기 위함.
#
# jq에 의존하지 않는다(이 환경엔 없다) — aws cli 의 --query/--output text 만 쓴다.
# 인스턴스가 하나도 없으면 완전히 조용하다(매 세션 시작마다 잡음을 내지 않기 위함).
set -uo pipefail

# 발동 자체를 남기는 로그 — 인스턴스가 없을 때는 systemMessage 도 안 뜨기 때문에
# (잡음 방지), 이 로그가 없으면 "훅이 안 돈다"와 "돌지만 조용하다"를 구분할 수 없다.
# .claude/logs/ 는 .gitignore 예외 목록(hooks/·settings.json·skills/)에 없어서
# 항상 로컬 전용으로 남는다.
LOG_FILE=".claude/logs/aws-cost-tripwire.log"
mkdir -p "$(dirname "$LOG_FILE")" 2>/dev/null || true
log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$1" >> "$LOG_FILE" 2>/dev/null || true; }

input=$(cat 2>/dev/null || true)

# PreToolUse(Bash) 이벤트에는 tool_name 이 있다 — 그 경우 run-instances 명령일 때만
# 실제로 조회한다(모든 bash 명령마다 AWS 를 부르는 낭비를 막기 위함). SessionStart
# 처럼 tool_name 이 없는 이벤트는 무조건 조회한다.
if printf '%s' "$input" | grep -q '"tool_name"'; then
  if ! printf '%s' "$input" | grep -qE 'run-instances'; then
    log "skip (run-instances 아닌 Bash 호출)"
    exit 0
  fi
  TRIGGER="run-instances 직전"
else
  TRIGGER="SessionStart"
fi

ROWS=$(aws ec2 describe-instances \
  --filters "Name=tag:Project,Values=shadowfit-measure" "Name=instance-state-name,Values=running,pending" \
  --query "Reservations[].Instances[].[InstanceId,InstanceType,State.Name,LaunchTime]" \
  --output text 2>/dev/null)

if [ -z "$ROWS" ]; then
  log "조회함 (${TRIGGER}) — 실행 중 인스턴스 0대"
  exit 0
fi

NOW_EPOCH=$(date -u +%s)
COUNT=0
LINES=""
while IFS=$'\t' read -r id type state launch; do
  [ -z "$id" ] && continue
  COUNT=$((COUNT + 1))
  launch_epoch=$(date -u -d "$launch" +%s 2>/dev/null || echo "$NOW_EPOCH")
  elapsed_min=$(( (NOW_EPOCH - launch_epoch) / 60 ))
  if [ "$elapsed_min" -ge 60 ]; then
    elapsed_h=$((elapsed_min / 60))
    elapsed_rest=$((elapsed_min % 60))
    dur="${elapsed_h}시간 ${elapsed_rest}분"
  else
    dur="${elapsed_min}분"
  fi
  LINES="${LINES}"$'\n'"- ${id} (${type}, ${state}) — ${dur}째"
done <<< "$ROWS"

MSG="AWS 비용 관측: Project=shadowfit-measure 태그 인스턴스 ${COUNT}대 실행 중${LINES}"
ESCAPED=$(printf '%s' "$MSG" | python -c "import json,sys; print(json.dumps(sys.stdin.read()))")

log "조회함 (${TRIGGER}) — 실행 중 인스턴스 ${COUNT}대, systemMessage 출력함"
printf '{"systemMessage":%s}\n' "$ESCAPED"
exit 0
