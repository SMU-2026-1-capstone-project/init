#!/usr/bin/env bash
# PreToolUse 가드 — Bash 명령이 `aws ec2 terminate-instances`/`stop-instances`일 때,
# 명령에 등장하는 instance-id(i-로 시작하는 실제 토큰)가 전부 Project=shadowfit-measure
# 태그를 갖고 있는지 확인한다. 하나라도 아니면(또는 조회 자체가 실패하면) 차단한다 —
# 다른 동시 세션의 인스턴스를 잘못 건드리는 사고(2026-08-30 near-miss)를 구조적으로
# 막기 위함. instance-id가 아예 없는 입력(예: 이 훅을 설명하는 텍스트)은 통과시킨다 —
# 그런 텍스트를 차단하다가 2026-08-30에 이 훅 자체를 커밋하는 명령을 오탐으로 막았다.
#
# jq에 의존하지 않는다(이 환경엔 없다) — stdin 전체를 grep으로 훑는다. Bash 도구의
# tool_input에는 command 키 하나뿐이라, 원본 JSON을 그대로 훑어도 안전하다.
#
# 입력: PreToolUse 이벤트 JSON(stdin). 출력: 없음(허용) 또는 deny JSON(차단).
set -uo pipefail

input=$(cat)

# aws ec2 terminate-instances / stop-instances 가 아니면 이 가드의 관심사가 아니다.
if ! printf '%s' "$input" | grep -qE 'aws +ec2 +(terminate|stop)-instances'; then
  exit 0
fi

ids=$(printf '%s' "$input" | grep -oE 'i-[0-9a-f]{8,17}' | sort -u)

# instance-id가 하나도 없으면 실제 호출이 아니라 텍스트 언급일 가능성이 높다(예:
# 이 훅 자체를 설명하는 커밋 메시지 — 2026-08-30 실측으로 확인된 오탐).
# instance-id 없는 진짜 terminate 호출은 AWS CLI가 그 자체로 usage error를 내
# 아무것도 안 지우므로, 여기서 막을 실익이 없다 — 조용히 통과시킨다.
if [ -z "$ids" ]; then
  exit 0
fi

bad=""
for id in $ids; do
  tag=$(aws ec2 describe-instances --instance-ids "$id" \
    --query "Reservations[0].Instances[0].Tags[?Key=='Project'].Value | [0]" \
    --output text 2>/dev/null || true)
  if [ "$tag" != "shadowfit-measure" ]; then
    bad="$bad $id(tag=${tag:-없음/조회실패})"
  fi
done

if [ -n "$bad" ]; then
  printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"EC2 terminate/stop 가드: Project=shadowfit-measure 태그가 아닌 인스턴스가 있다 —%s. 이 세션 소유가 아닐 수 있다."}}\n' "$bad"
fi
exit 0
