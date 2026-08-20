#!/usr/bin/env bash
# #276 후속 — 데드락의 «자리» 를 기제까지 내린다
#
# 이슈: https://github.com/Shadowfit/init/issues/276
# 08-20 라운드가 팔 넷으로 조건을 셋으로 좁혔지만(키 존재 · 서로 다른 키 · 같은 파티션),
#   「무엇이 무엇을 기다리는가」는 안 봤다 — SHOW ENGINE INNODB STATUS 를 안 읽었다.
#   여기서 읽는다. 확인할 것은 딱 하나:
#     대기 중인 잠금의 `index` 가 `uk_pose_event` 인가 `PRIMARY` 인가.
#
# 판정선 (미리 박아둔다 — 결과를 보고 정하지 않기 위해):
#   · 양쪽 트랜잭션의 WAITING 이 전부 `index uk_pose_event` → 자리는 유니크 키. no_uk 팔과 일치
#   · 하나라도 `index PRIMARY` 의 supremum 대기 → 08-20 §2-ㄹ 의 서술을 다시 써야 한다
#
# 🔴 격리: same_partition 팔과 같은 페이로드를 `pose_data_r276` 에만 넣는다.
# ⚠️ 한계: 로컬 2물리코어. 이 판은 «비율» 을 재는 판이 아니라 «잠금 한 쌍» 을 읽는 판이다.
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a

WORKERS=${WORKERS:-8}
ITER=${ITER:-40}
ROWS=${ROWS:-25}
OUT=${OUT:-loadtest/results/r276-deadlock-2026-08-20}
SC=$(mktemp -d)
mkdir -p "$OUT"

DB(){ docker exec -i shadowfit-mysql mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit "$@" 2>/dev/null; }

echo "## [0] 상태 단언 — 키가 있어야 데드락이 난다(팔 no_uk 가 0% 였다)"
have=$(DB -e "SELECT COUNT(*) FROM information_schema.statistics
        WHERE table_schema='shadowfit' AND table_name='pose_data_r276' AND index_name='uk_pose_event';" | tr -d '[:space:]')
echo "  uk_pose_event 컬럼 수 = ${have:-없음}"
[ "$have" = "4" ] || { echo "🔴 4 가 아니다 — measure_r276_deadlock.sh 를 먼저 돌려 테이블을 만들 것"; exit 1; }
DB -e "TRUNCATE TABLE pose_data_r276;"

# 모든 데드락을 에러 로그로도 남긴다 (SHOW ENGINE 은 «마지막 하나» 만 준다)
prev_print=$(DB -e "SELECT @@innodb_print_all_deadlocks;" | tr -d '[:space:]')
DB -e "SET GLOBAL innodb_print_all_deadlocks=ON;"
echo "  innodb_print_all_deadlocks: $prev_print → ON (끝나면 되돌린다)"
restore(){ DB -e "SET GLOBAL innodb_print_all_deadlocks=${prev_print:-0};"; }
trap restore EXIT

echo
echo "## [1] same_partition 팔 1판 — 워커 $WORKERS · 문 $ITER · 행 $ROWS"
vals=""
for ((r=0;r<ROWS;r++)); do
  [ -n "$vals" ] && vals+=","
  vals+="(SID,0,$((r/2)).$(((r%2)*5))00,'{\"k\":$r}',45.0,0.0,'','2026-05-28 10:00:00')"
done
for ((w=0;w<WORKERS;w++)); do
  stmt="INSERT INTO pose_data_r276 (session_id,rep_number,timestamp_sec,joint_coordinates,sync_rate,smoothed_knee_angle,feedback_message,created_at) VALUES ${vals//SID/$((900+w))} ON DUPLICATE KEY UPDATE session_id = session_id;"
  : > "$SC/w.$w.sql"
  for ((i=0;i<ITER;i++)); do echo "$stmt" >> "$SC/w.$w.sql"; done
done
since=$(DB -e "SELECT NOW(6);")
for ((w=0;w<WORKERS;w++)); do
  ( docker exec -i shadowfit-mysql mysql --force -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit \
      < "$SC/w.$w.sql" > /dev/null 2> "$SC/err.$w" ) &
done
wait
dl=0; for ((w=0;w<WORKERS;w++)); do c=$(grep -c 'Deadlock found' "$SC/err.$w" 2>/dev/null); dl=$((dl+${c:-0})); done
echo "  데드락 $dl / $((WORKERS*ITER)) 시도"
[ "$dl" -gt 0 ] || { echo "🔴 데드락이 한 건도 안 났다 — 읽을 것이 없다. 중단"; exit 1; }

echo
echo "## [2] LATEST DETECTED DEADLOCK"
docker exec -i shadowfit-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW ENGINE INNODB STATUS\\G" 2>/dev/null \
  | sed -n '/LATEST DETECTED DEADLOCK/,/^TRANSACTIONS/p' | head -80 | tee "$SC/latest.txt"

echo
echo "## [3] 대기 잠금의 index — 이 판이 답하는 것"
grep -nE 'WAITING FOR THIS LOCK|HOLDS THE LOCK|index .* of table|lock_mode|insert intention|supremum' "$SC/latest.txt" \
  | sed 's/^/  /'

{
  echo "# #276 후속 — 데드락 한 쌍의 잠금 (로컬, 2026-08-20)"
  echo
  echo "\`same_partition\` 팔 1판(워커 $WORKERS · 문 $ITER · 행 $ROWS)에서 데드락 **$dl 건** 발생."
  echo "아래는 \`SHOW ENGINE INNODB STATUS\` 의 LATEST DETECTED DEADLOCK 원문이다."
  echo
  echo '```'
  cat "$SC/latest.txt"
  echo '```'
} > "$OUT/innodb-status.md"
echo
echo "→ $OUT/innodb-status.md"
