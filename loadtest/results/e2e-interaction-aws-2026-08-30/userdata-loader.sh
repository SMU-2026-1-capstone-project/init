#!/bin/bash
exec > /var/log/e2e-userdata.log 2>&1
set -x
SHA=b29a113f8a7efa1a398a13ae28216b16ca388377
cd /root || exit 1
curl -fsSL "https://raw.githubusercontent.com/Shadowfit/init/${SHA}/loadtest/aws/bootstrap.sh" -o bootstrap.sh
grep -q "p6-loader" bootstrap.sh || { echo "FATAL: bootstrap.sh missing p6-loader — bad fetch"; touch /root/BOOTSTRAP_FAILED; exit 1; }
REF="$SHA" ROLE=p6-loader bash bootstrap.sh
if [ $? -eq 0 ]; then
  touch /root/BOOTSTRAP_DONE
else
  touch /root/BOOTSTRAP_FAILED
fi
shutdown -h +150 &
