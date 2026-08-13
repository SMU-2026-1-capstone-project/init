#!/bin/bash
# AWS 정리 — 「실측이 끝나면 지운다」를 절차로 만든다.
#
# ─────────────────────────────────────────────────────────────────────────
# 왜 이 스크립트가 있나
#
# 이 프로젝트의 EC2 는 **항상 «임시 생성 → 측정 → 삭제»** 인데, 실제로는 두 번 새어나갔다:
#
#   · 2026-08-13: 라운드가 끝나고 인스턴스는 «정지» 됐는데 **볼륨은 그대로**였다.
#     정지된 인스턴스는 컴퓨트 요금이 0 이라 눈에 안 띄는데, **EBS 는 계속 과금된다.**
#     gp3 250GB 가 붙은 채로 남아 있었다 — 월 20 USD 남짓이 조용히 나가는 상태.
#   · `AUTO_SHUTDOWN=1` 은 **stop 이지 terminate 가 아니다.** 그건 의도된 설계다
#     (`/root/run_all.log` 가 사라지면 사후 진단이 통째로 막힌다 — #203 이 그 로그로 잡혔다).
#     그래서 «지우는 일» 은 사람이 따로 해야 하고, **그 사람이 잊는다.**
#
# 그래서 정리를 **명령 하나**로 만든다. 잊는 것이 문제였지 방법이 어려운 게 아니었다.
# ─────────────────────────────────────────────────────────────────────────
#
# 🔴 안전장치 셋:
#   ① 기본 동작은 **조회**다. 아무것도 지우지 않는다
#   ② `Project=shadowfit-measure` 태그가 없는 자원은 **거부**한다
#      (같은 계정에 다른 프로젝트 인스턴스가 산다 — 예: `Project=DOCKin`)
#   ③ `sweep` 은 **stopped 인스턴스만** 지운다. 도는 라운드를 죽이지 않는다
#
# 사용:
#   bash scripts/aws_teardown.sh list                 # 현황 + 남은 비용
#   bash scripts/aws_teardown.sh terminate i-0abc...  # 지정한 것만
#   bash scripts/aws_teardown.sh sweep --yes          # stopped 전부 + 미연결 볼륨

set -uo pipefail

TAG_KEY=${TAG_KEY:-Project}
TAG_VAL=${TAG_VAL:-shadowfit-measure}
CMD=${1:-list}

die() { echo "🔴 $*" >&2; exit 1; }

instances() {  # $1 = 상태 필터(선택)
  local filt=(--filters "Name=tag:$TAG_KEY,Values=$TAG_VAL"
              "Name=instance-state-name,Values=${1:-pending,running,stopping,stopped}")
  aws ec2 describe-instances "${filt[@]}" \
    --query 'Reservations[].Instances[].[InstanceId,State.Name,InstanceType,LaunchTime,Tags[?Key==`Name`]|[0].Value,Tags[?Key==`Purpose`]|[0].Value]' \
    --output text
}

case "$CMD" in

list)
  echo "════════ $TAG_KEY=$TAG_VAL 자원 ════════"
  echo
  echo "── 인스턴스"
  instances | while read -r id state type launch name purpose; do
    printf "  %-21s %-9s %-13s %s\n" "$id" "$state" "$type" "${name:-?} / ${purpose:-?}"
    [ "$state" = "stopped" ] && echo "        ⚠️ 정지 상태 — 컴퓨트는 0 이지만 **볼륨은 계속 과금된다**"
  done
  echo
  echo "── 볼륨"
  aws ec2 describe-volumes --filters "Name=tag:$TAG_KEY,Values=$TAG_VAL" \
    --query 'Volumes[].[VolumeId,Size,VolumeType,State,Attachments[0].InstanceId]' --output text \
  | while read -r vid size vtype vstate att; do
      printf "  %-23s %4sGB %-5s %-10s %s\n" "$vid" "$size" "$vtype" "$vstate" "${att:-미연결}"
    done
  echo
  # 태그가 없으면 여기 안 잡힌다 — 그 사실 자체를 알려준다. 조용히 빠지는 게 제일 나쁘다.
  UNTAGGED=$(aws ec2 describe-volumes --filters "Name=status,Values=available" \
    --query "length(Volumes[?!not_null(Tags)])" --output text 2>/dev/null)
  [ "${UNTAGGED:-0}" != "0" ] && \
    echo "  ⚠️ 태그 없는 미연결 볼륨이 ${UNTAGGED}개 있다 — 이 스크립트는 **안 건드린다**. 콘솔에서 확인할 것"
  echo
  echo "── 스냅샷 · EIP"
  aws ec2 describe-snapshots --owner-ids self --query 'Snapshots[].[SnapshotId,VolumeSize,StartTime]' --output text | head -10
  aws ec2 describe-addresses --query 'Addresses[].[PublicIp,InstanceId]' --output text | head -10
  echo
  echo "정리하려면: bash $0 sweep --yes   (stopped 만) 또는 terminate <id>"
  ;;

terminate)
  ID=${2:-}
  [ -n "$ID" ] || die "인스턴스 ID 가 필요하다 — 예: $0 terminate i-0abc123"
  # 🔴 남의 프로젝트를 지우지 않는다. 같은 계정에 다른 태그의 인스턴스가 실제로 산다.
  GOT=$(aws ec2 describe-instances --instance-ids "$ID" \
        --query "Reservations[].Instances[].Tags[?Key=='$TAG_KEY'].Value" --output text 2>/dev/null)
  [ "$GOT" = "$TAG_VAL" ] || die "$ID 의 $TAG_KEY 태그가 '$GOT' 다 (기대: $TAG_VAL). 거부한다"
  echo "종료: $ID"
  aws ec2 terminate-instances --instance-ids "$ID" \
    --query 'TerminatingInstances[].[InstanceId,CurrentState.Name]' --output text || die "종료 실패"
  echo "볼륨 삭제 확인 중 (DeleteOnTermination=true 면 같이 사라진다)…"
  aws ec2 wait instance-terminated --instance-ids "$ID" 2>/dev/null
  aws ec2 describe-volumes --filters "Name=attachment.instance-id,Values=$ID" \
    --query 'Volumes[].VolumeId' --output text
  echo "✅ 종료 완료 — 위에 볼륨 ID 가 안 찍혔으면 같이 삭제된 것이다"
  ;;

sweep)
  [ "${2:-}" = "--yes" ] || die "실제로 지우려면 --yes 가 필요하다. 먼저 '$0 list' 로 볼 것"
  # ③ 도는 것은 안 건드린다 — 측정 중인 라운드를 죽이는 것이 이 스크립트의 최악 실패다.
  STOPPED=$(instances stopped | awk '{print $1}')
  if [ -z "$STOPPED" ]; then
    echo "정지된 인스턴스 없음"
  else
    echo "종료할 인스턴스: $STOPPED"
    aws ec2 terminate-instances --instance-ids $STOPPED \
      --query 'TerminatingInstances[].[InstanceId,CurrentState.Name]' --output text
  fi
  echo
  echo "미연결 볼륨(태그 일치) 삭제:"
  aws ec2 describe-volumes --filters "Name=tag:$TAG_KEY,Values=$TAG_VAL" "Name=status,Values=available" \
    --query 'Volumes[].VolumeId' --output text | tr '\t' '\n' | while read -r v; do
      [ -n "$v" ] || continue
      echo "  삭제 $v"; aws ec2 delete-volume --volume-id "$v" || echo "  ⚠️ $v 삭제 실패"
    done
  echo
  echo "✅ sweep 완료. 남은 것은 '$0 list' 로 다시 확인할 것"
  ;;

*)
  die "알 수 없는 명령 '$CMD' — list | terminate <id> | sweep --yes"
  ;;
esac
