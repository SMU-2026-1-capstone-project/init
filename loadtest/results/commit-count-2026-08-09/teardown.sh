#!/bin/bash
# 실험 인프라 삭제 — 그리고 «지워졌는지» 를 확인한다.
#
# ─────────────────────────────────────────────────────────────────────────
# 왜 스크립트인가
#
# `pool-cliff-vs-concurrency.md` §8 이 미결로 남긴 항목이다:
#   *"종료 후 삭제 체크리스트를 어디에 둘지 — 인스턴스·EBS·키페어·SG. EBS 누락이 가장 흔한
#     과금 원인. 이번엔 손으로 확인하고 끝냈다 — 다음 실험에서 또 손으로 하게 된다"*
# 그 «또» 가 이번이다. 손으로 세 번 했으면 스크립트로 만들 때다.
#
# 🔴 **인스턴스를 지우는 것으로 끝나지 않는다.** EBS 볼륨은 인스턴스를 종료해도 남을 수 있고
#    (DeleteOnTermination=false 인 경우), 볼륨은 **인스턴스가 꺼져 있어도 계속 과금된다.**
#    이 rig 는 DeleteOnTermination=true 로 만들었지만, 그 전제가 맞는지는 **확인해야 안다.**
#
# ⚠️ 이 스크립트는 **태그로 대상을 좁힌다**(`Round=commit-2026-08-09`). 태그 없는 자원은
#    안 건드린다 — 계정에 다른 것이 있어도 안전하다. 반대로 **태그를 안 단 자원은 이
#    스크립트가 못 찾는다**는 뜻이기도 하다. 그래서 마지막에 «계정 전체» 를 한 번 훑는다.
# ─────────────────────────────────────────────────────────────────────────
#
# 사용: bash teardown.sh [--yes]
#   --yes 없이 돌리면 «무엇을 지울지» 만 보여주고 끝난다 (dry-run).

set -uo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash 가 /dev/xvda 같은 인자를 Windows 경로로 바꾸는 것을 막는다

ROUND_TAG="commit-2026-08-09"
KEY_NAME="shadowfit-commit-2026-08-09"
SG_NAME="sf-commit-2026-08-09"
APPLY=0
[ "${1:-}" = "--yes" ] && APPLY=1

echo "=== 대상 (태그 Round=$ROUND_TAG) ==="
IDS=$(aws ec2 describe-instances --filters "Name=tag:Round,Values=$ROUND_TAG" \
      --query "Reservations[].Instances[?State.Name!='terminated'].InstanceId" --output text)
echo "인스턴스: ${IDS:-(없음)}"

# 인스턴스에 물린 볼륨을 **종료 전에** 기록해 둔다. 종료 뒤에는 연결이 끊겨 못 찾는다.
VOLS=""
if [ -n "$IDS" ]; then
  VOLS=$(aws ec2 describe-volumes --filters "Name=attachment.instance-id,Values=$(echo $IDS | tr ' ' ',')" \
         --query "Volumes[].VolumeId" --output text)
fi
echo "볼륨:     ${VOLS:-(없음)}"
echo "키페어:   $KEY_NAME"
echo "보안그룹: $SG_NAME"

if [ $APPLY -eq 0 ]; then
  echo
  echo "🟡 dry-run 이다. 실제로 지우려면: bash teardown.sh --yes"
  exit 0
fi

echo
echo "=== 1. 인스턴스 종료 ==="
# 🔴 «종료 요청을 보냈다» 와 «종료됐다» 는 다르다. 초판은 요청만 보내고 넘어갔는데,
#    2026-08-09 실행에서 **요청이 먹지 않았고**(원인 미상 — 출력을 못 남겨 규명 못 했다)
#    스크립트는 그대로 §3·§4 로 진행해 «SG 삭제 실패 → 재시도 10회» 로만 나타났다.
#    인스턴스 4대가 살아 있는 채로 «키페어 ✅ 삭제» 를 찍었다. **키페어를 먼저 지웠으면
#    살아 있는 박스에 다시 못 들어갈 뻔했다.** 이제 여기서 확인하고, 안 되면 멈춘다.
if [ -n "$IDS" ]; then
  aws ec2 terminate-instances --instance-ids $IDS \
    --query "TerminatingInstances[].[InstanceId,CurrentState.Name]" --output text \
    || echo "  ⚠️ terminate-instances 가 non-zero 로 끝났다 — 아래에서 실제 상태를 확인한다"
  echo "  종료 대기..."
  aws ec2 wait instance-terminated --instance-ids $IDS 2>/dev/null
  STILL=$(aws ec2 describe-instances --instance-ids $IDS \
          --query "Reservations[].Instances[?State.Name!='terminated'].InstanceId" --output text)
  if [ -n "$STILL" ]; then
    echo "🔴 중단 — 아직 살아 있는 인스턴스가 있다: $STILL" >&2
    echo "   과금이 계속된다. 키페어·SG 는 **지우지 않는다** — 지우면 접속 경로가 사라진다." >&2
    echo "   수동: aws ec2 terminate-instances --instance-ids $STILL" >&2
    exit 1
  fi
  echo "  ✅ 전부 terminated"
else
  echo "  (없음)"
fi

echo "=== 2. 볼륨 확인 — DeleteOnTermination 이 실제로 동작했나 ==="
# 여기가 이 스크립트의 존재 이유다. «지웠겠지» 가 아니라 «지워졌나» 를 묻는다.
# ⚠️ 다만 이 절은 **§0 에서 인스턴스를 찾았을 때만** 의미가 있다. 이미 종료된 뒤에 다시
#    돌리면 VOLS 가 비어서 «전부 사라졌다» 를 찍는데, 그건 확인이 아니라 **빈 루프**다.
#    (초판이 실제로 그 거짓 초록불을 냈다.) 그래서 «확인함» 과 «확인할 게 없었음» 을 가른다.
LEFT=""
for v in $VOLS; do
  st=$(aws ec2 describe-volumes --volume-ids "$v" --query "Volumes[0].State" --output text 2>/dev/null)
  if [ -n "$st" ] && [ "$st" != "deleted" ]; then
    echo "  🔴 $v 가 남아 있다 (state=$st) — 삭제한다"
    aws ec2 delete-volume --volume-id "$v" && echo "     삭제 요청 완료"
    LEFT="$LEFT $v"
  fi
done
if [ -z "$VOLS" ]; then
  echo "  ⚪ 확인할 볼륨이 없었다 (인스턴스가 이미 종료된 뒤 실행) — §5 의 계정 전체 훑기가 판정한다"
elif [ -z "$LEFT" ]; then
  echo "  ✅ 물려 있던 볼륨 $(echo $VOLS | wc -w)개가 전부 사라졌다"
fi

echo "=== 3. 보안그룹 ==="
SG=$(aws ec2 describe-security-groups --filters "Name=group-name,Values=$SG_NAME" \
     --query "SecurityGroups[0].GroupId" --output text 2>/dev/null)
if [ -n "$SG" ] && [ "$SG" != "None" ]; then
  # ENI 정리가 늦으면 DependencyViolation 이 난다. 몇 번 재시도한다.
  for i in $(seq 1 10); do
    if aws ec2 delete-security-group --group-id "$SG" 2>/dev/null; then echo "  ✅ $SG 삭제"; break; fi
    echo "  대기 중 ($i/10) — ENI 정리가 안 끝났을 수 있다"; sleep 15
  done
else
  echo "  (없음)"
fi

echo "=== 4. 키페어 ==="
aws ec2 delete-key-pair --key-name "$KEY_NAME" 2>/dev/null && echo "  ✅ $KEY_NAME 삭제" || echo "  (없음)"

echo
echo "=== 5. 계정 전체 훑기 — 태그 안 붙은 잔여물까지 ==="
# 태그 기반 삭제의 사각지대다. 이 실험이 만든 게 아니어도, 남아 있으면 과금된다는 사실은 같다.
echo "-- 살아 있는 인스턴스 --"
aws ec2 describe-instances --query "Reservations[].Instances[?State.Name!='terminated'].[InstanceId,InstanceType,State.Name]" --output text
echo "-- 남은 볼륨 (인스턴스를 꺼도 과금된다) --"
aws ec2 describe-volumes --query "Volumes[].[VolumeId,Size,State]" --output text
echo "-- 남은 키페어 --"
aws ec2 describe-key-pairs --query "KeyPairs[].KeyName" --output text
echo "-- 남은 보안그룹 (default 제외) --"
aws ec2 describe-security-groups --query "SecurityGroups[?GroupName!='default'].[GroupId,GroupName]" --output text
echo
# §5 가 이 스크립트의 **최종 판정**이다. 태그·순서·요청 성공 여부와 무관하게, 계정에
# 남아 있는 것이 있으면 과금된다. 2026-08-09 실행에서 «인스턴스 4대 running» 을 잡은 것도
# 여기다 — 앞 절들은 전부 통과한 것처럼 보였다.
REMAIN=$( { aws ec2 describe-instances --query "Reservations[].Instances[?State.Name!='terminated'].InstanceId" --output text
            aws ec2 describe-volumes --query "Volumes[].VolumeId" --output text
            aws ec2 describe-key-pairs --query "KeyPairs[].KeyName" --output text
            aws ec2 describe-security-groups --query "SecurityGroups[?GroupName!='default'].GroupId" --output text; } | tr -d '[:space:]')
if [ -n "$REMAIN" ]; then
  echo "🔴 위에 남은 것이 있다 — 과금이 계속된다. 지우고 다시 확인할 것." >&2
  exit 1
fi
echo "✅ 계정에 남은 실험 자원이 없다."
