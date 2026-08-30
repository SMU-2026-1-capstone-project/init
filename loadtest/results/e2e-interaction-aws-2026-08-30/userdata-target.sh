#!/bin/bash
exec > /var/log/e2e-userdata.log 2>&1
set -x
SHA=b29a113f8a7efa1a398a13ae28216b16ca388377
cd /root || exit 1
curl -fsSL "https://raw.githubusercontent.com/Shadowfit/init/${SHA}/loadtest/aws/bootstrap.sh" -o bootstrap.sh
grep -q "p6-target" bootstrap.sh || { echo "FATAL: bootstrap.sh missing p6-target — bad fetch"; touch /root/BOOTSTRAP_FAILED; exit 1; }
REF="$SHA" ROLE=p6-target \
  AI_PUBLIC_TOKEN=cyyVMeCqTFdUQLlxfmVwLGJQQCPzRd9 \
  INTERNAL_API_TOKEN=F2K5gSBzIvassfyWJH8ZojarqFXsd3 \
  bash bootstrap.sh
if [ $? -eq 0 ]; then
  touch /root/BOOTSTRAP_DONE
else
  touch /root/BOOTSTRAP_FAILED
fi
# 안전판 — 사람(포크)이 조작 못 하게 되면 2.5시간 뒤 스스로 꺼진다. 정상 종료는 이 fork가
# 명시적으로 aws ec2 terminate-instances 를 호출한다(unattended run_all.sh 경로 아님).
shutdown -h +150 &
