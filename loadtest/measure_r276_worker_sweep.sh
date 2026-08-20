#!/usr/bin/env bash
# #276 후속 — 시도당 데드락 확률 p 를 «동시성의 함수» 로
#
# 이슈: https://github.com/Shadowfit/init/issues/276
# 08-20 라운드는 p = 0.3750 을 줬지만 그것은 **워커 8 에서의 값 하나**다.
#   #188 재시도 구현이 「몇 번」을 정하려면 p 가 동시성에 어떻게 움직이는지가 필요하다.
#
# 팔 = 워커 수 2 · 4 · 8 · 16. 그 외는 same_partition 팔과 동일
#   (세션 8개가 아니라 «워커 수만큼», created_at 은 전부 같은 날 = 같은 파티션).
#
# ⚠️ 워커당 문 수(ITER)는 40 으로 **고정**한다. 총 시도수는 워커에 비례해 늘지만
#   지표가 «시도당» 비율이라 정규화된다. 그리고 이렇게 해야 워커 8 값이
#   08-20 라운드의 0.3750 과 **직접 비교**된다(그 판도 ITER=40 이었다).
#
# 🔴 판 순서: 라틴 방격으로 돌린다. 블록마다 레벨 순서를 한 칸씩 회전시켜
#   «레벨» 과 «판 순서(캐시·드리프트)» 를 분리한다. 첫 블록은 통째로 버린다.
#
# ⚠️ 한계: 로컬 2물리코어 동거. 절대 비율은 이 박스 값이고, 팔 간 상대비교만 신뢰할 것.
#   워커 16 은 물리 코어보다 훨씬 많다 — «동시성» 이 아니라 «대기열» 을 재는 구간일 수 있다.
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a

# shellcheck disable=SC2206 — LEVELS="2 3 4" 처럼 넘겨 구간을 좁힐 수 있게 한다
LEVELS=(${LEVELS:-2 4 8 16})
ITER=${ITER:-40}
ROWS=${ROWS:-25}
BLOCKS=${BLOCKS:-4}        # 첫 블록은 버린다 → 레벨당 유효 3판
OUT=${OUT:-loadtest/results/r276-worker-sweep-2026-08-20}
SC=$(mktemp -d)
mkdir -p "$OUT"

DB(){ docker exec -i shadowfit-mysql mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit "$@" 2>/dev/null; }

echo "## [0] 상태 단언 — 키가 있어야 난다 (no_uk 팔이 0% 였다)"
have=$(DB -e "SELECT COUNT(*) FROM information_schema.statistics
        WHERE table_schema='shadowfit' AND table_name='pose_data_r276' AND index_name='uk_pose_event';" | tr -d '[:space:]')
echo "  uk_pose_event 컬럼 수 = ${have:-없음} (4 여야 정상)"
[ "$have" = "4" ] || { echo "🔴 4 가 아니다 — measure_r276_deadlock.sh 를 먼저 돌릴 것"; exit 1; }

run_level(){ # $1=workers  $2=block → "workers block 데드락 시도 그외에러 행수"
  local w_n="$1" blk="$2" w vals stmt pids=()
  DB -e "TRUNCATE TABLE pose_data_r276;"
  rm -f "$SC"/err.* "$SC"/w.*
  for ((w=0;w<w_n;w++)); do
    vals=""
    for ((r=0;r<ROWS;r++)); do
      [ -n "$vals" ] && vals+=","
      vals+="($((900+w)),0,$((r/2)).$(((r%2)*5))00,'{\"k\":$r}',45.0,0.0,'','2026-05-28 10:00:00')"
    done
    stmt="INSERT INTO pose_data_r276 (session_id,rep_number,timestamp_sec,joint_coordinates,sync_rate,smoothed_knee_angle,feedback_message,created_at) VALUES $vals ON DUPLICATE KEY UPDATE session_id = session_id;"
    : > "$SC/w.$w.sql"
    for ((i=0;i<ITER;i++)); do echo "$stmt" >> "$SC/w.$w.sql"; done
  done
  for ((w=0;w<w_n;w++)); do
    ( docker exec -i shadowfit-mysql mysql --force -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit \
        < "$SC/w.$w.sql" > /dev/null 2> "$SC/err.$w" ) &
    pids+=($!)
  done
  for p in "${pids[@]}"; do wait "$p"; done
  local dl=0 other=0 c
  for ((w=0;w<w_n;w++)); do
    c=$(grep -c 'Deadlock found' "$SC/err.$w" 2>/dev/null); dl=$((dl + ${c:-0}))
    c=$(grep -c 'ERROR' "$SC/err.$w" 2>/dev/null);          other=$((other + ${c:-0}))
  done
  local rows; rows=$(DB -e "SELECT COUNT(*) FROM pose_data_r276;")
  echo "$w_n $blk $dl $((w_n*ITER)) $((other-dl)) $rows"
}

echo
echo "## [1] 레벨 ${LEVELS[*]} × ${BLOCKS}블록 (첫 블록 버림) — 라틴 방격 · 워커당 문 $ITER · 행 $ROWS"
echo "workers block deadlocks attempts other_err rows" > "$SC/raw.txt"
n=${#LEVELS[@]}
for ((b=0;b<BLOCKS;b++)); do
  order=""
  for ((k=0;k<n;k++)); do order+="${LEVELS[$(( (b+k) % n ))]} "; done
  echo "  — 블록 $b 순서: $order$([ "$b" = 0 ] && echo '  ← 버림')"
  for lv in $order; do
    line=$(run_level "$lv" "$b")
    echo "$line" >> "$SC/raw.txt"
    echo "    w=$line"
  done
done

echo
echo "## [2] 집계"
{
echo "# #276 후속 — 워커 수 스윕 (로컬, 2026-08-20) · 생성 표"
echo
echo "격리 테이블 \`pose_data_r276\` · 워커당 INSERT 문 **$ITER** · 문당 행 **$ROWS** · **${BLOCKS}블록**(첫 블록 버림) · 라틴 방격."
echo
echo "| 워커 | 블록 | 데드락 | 시도 | 비율 | 그 외 에러 | 행수 |"
echo "|---|---|---|---|---|---|---|"
awk 'NR>1 {printf "| %s | %s | %s | %s | %.1f%% | %s | %s |%s\n", $1,$2,$3,$4,($3/$4)*100,$5,$6, ($2==0?" ← 버림":"")}' "$SC/raw.txt"
echo
echo "**레벨별 중앙값(첫 블록 제외)**"
echo
echo "| 워커 | 데드락 비율 중앙값 | 시도당 확률 p |"
echo "|---|---|---|"
for lv in "${LEVELS[@]}"; do
  awk -v a="$lv" 'NR>1 && $1==a && $2>0 {print ($3/$4)}' "$SC/raw.txt" | sort -n | awk -v a="$lv" '
    {v[NR]=$1} END{
      if (NR==0) { printf "| %s | — (유효 판 0) | — |\n", a; exit }
      m=(NR%2)? v[(NR+1)/2] : (v[NR/2]+v[NR/2+1])/2; printf "| %s | %.1f%% | %.4f |\n", a, m*100, m }'
done
} | tee "$OUT/summary.md"

DB -e "TRUNCATE TABLE pose_data_r276;"
cp "$SC/raw.txt" "$OUT/raw.tsv"
echo
echo "→ $OUT/summary.md (판정은 손으로 쓴 $OUT/README.md 에)"
