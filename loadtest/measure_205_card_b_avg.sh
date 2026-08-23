#!/usr/bin/env bash
# #205 카드 B — 「같은 컬럼을 평균 냈는데 두 시스템이 다른 값을 낸다」
#
# 이슈: https://github.com/Shadowfit/init/issues/205 (카드 B)
# 근거는 PoseDataRepository.findRepAverageSyncRates 의 주석에 이미 있다:
#   한 rep 의 모든 프레임은 같은 sync_rate 를 공유하는데, 다운샘플(R≈5) 때문에
#   rep 마다 살아남은 «행 수» 가 다르다. 그냥 AVG(sync_rate) 를 내면 프레임이 많이 남은
#   rep 이 더 무거워지는 «프레임 가중 평균» 이 되어, AI 가 계산하던 «rep 가중 평균» 과 값이 달라진다.
#
# 이 rig 이 답하는 것 — 성능이 아니라 **정확성** 이다:
#   ㄱ. 두 평균이 실제로 갈리는가, 얼마나
#   ㄴ. 🔴 **기제가 예측하는 자리에서만 갈리는가** — rep 당 프레임 수가 «균일한» 세션에서는
#       두 값이 **반드시 같아야 한다**. 거기서도 갈리면 설명이 틀린 것이다.
#       (이 대조가 이 판의 핵심이다. 「다르더라」만 보이면 우연과 구분이 안 된다)
#   ㄷ. rep 순서별 곡선 쿼리의 «비용» — rep_number 가 인덱스 밖이라 얼마나 비싼가
#
# ⚠️ 한계:
#   · 합성 데이터다. 「곡선의 모양」(몇 번째 rep 부터 무너지나)은 **의미 없다** —
#     시드가 만든 모양일 뿐이다. 여기서 말할 수 있는 것은 «갈린다/안 갈린다» 와 «비용» 이다
#   · 불균일 세션은 #204 rig 이 카드 B 를 위해 일부러 넣은 200개다(rep 당 3~30 프레임).
#     즉 **실사용 다운샘플이 만든 분포가 아니다** — 구조가 그렇게 되면 갈린다는 것까지만 보인다
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a

OUT=${OUT:-loadtest/results/card-b-avg-2026-08-20}
REPEATS=${REPEATS:-4}   # 곡선 쿼리 비용: 첫 판 버림 → 유효 3판
mkdir -p "$OUT"
SC=$(mktemp -d)

DB(){ docker exec -i shadowfit-mysql mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit "$@" 2>/dev/null; }
DBT(){ docker exec -i shadowfit-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit "$@" 2>/dev/null; }

echo "## [0] 전제 — rep 당 프레임 수가 세션 안에서 다른가"
DBT -e "
SELECT CASE WHEN mn=mx THEN 'uniform' ELSE 'nonuniform' END AS shape,
       COUNT(*) AS sessions, MIN(mn) AS min_frames_per_rep, MAX(mx) AS max_frames_per_rep
FROM ( SELECT session_id, MIN(c) mn, MAX(c) mx FROM
        ( SELECT session_id, rep_number, COUNT(*) c FROM pose_data WHERE rep_number>0
           GROUP BY session_id, rep_number ) t GROUP BY session_id ) u
GROUP BY shape;" | tee "$SC/shape.txt"

echo
echo "## [1] 두 평균의 차이 — 모양별로"
# frame_avg : 프레임 가중 = 그냥 AVG(sync_rate)
# rep_avg   : rep 가중   = rep 으로 먼저 접고 그 평균
DBT -e "
WITH per_rep AS (
  SELECT session_id, rep_number, COUNT(*) AS frames, AVG(sync_rate) AS rep_sync
    FROM pose_data WHERE rep_number > 0
   GROUP BY session_id, rep_number),
per_sess AS (
  SELECT session_id,
         MIN(frames) AS mn, MAX(frames) AS mx,
         SUM(rep_sync*frames)/SUM(frames) AS frame_avg,   -- 프레임 가중
         AVG(rep_sync)                    AS rep_avg      -- rep 가중
    FROM per_rep GROUP BY session_id)
SELECT CASE WHEN mn=mx THEN 'uniform' ELSE 'nonuniform' END AS shape,
       COUNT(*) AS sessions,
       SUM(ABS(frame_avg-rep_avg) > 0.000001) AS sessions_differing,
       ROUND(MAX(ABS(frame_avg-rep_avg)),6)   AS max_abs_diff,
       ROUND(AVG(ABS(frame_avg-rep_avg)),6)   AS avg_abs_diff
  FROM per_sess GROUP BY shape;" | tee "$SC/diff.txt"

echo
echo "## [1-b] 가장 크게 갈린 세션 다섯"
DBT -e "
WITH per_rep AS (
  SELECT session_id, rep_number, COUNT(*) AS frames, AVG(sync_rate) AS rep_sync
    FROM pose_data WHERE rep_number > 0 GROUP BY session_id, rep_number),
per_sess AS (
  SELECT session_id, MIN(frames) mn, MAX(frames) mx,
         SUM(rep_sync*frames)/SUM(frames) AS frame_avg, AVG(rep_sync) AS rep_avg
    FROM per_rep GROUP BY session_id)
SELECT session_id, mn AS min_frames, mx AS max_frames,
       ROUND(frame_avg,6) AS frame_weighted, ROUND(rep_avg,6) AS rep_weighted,
       ROUND(frame_avg-rep_avg,6) AS diff
  FROM per_sess ORDER BY ABS(frame_avg-rep_avg) DESC LIMIT 5;" | tee "$SC/top.txt"

echo
echo "## [2] rep 순서별 곡선 쿼리의 비용 — 커버링 인덱스 있음/없음"
# 🔴 2026-08-20 발견: idx_report_cover 가 로컬 DB 에 남아 있었다. 이 인덱스는 **마이그레이션에
#   없다** — #204 라운드 arm C 가 만든 뒤 안 지워진 것이다. 즉 그냥 재면 «배포 스키마» 가 아니라
#   «arm C 상태» 를 재게 된다. 이슈 #205 카드 B 의 두 번째 체크박스가 "카드 A 의 커버링 인덱스와
#   열쇠가 같다" 이므로, 두 상태를 **둘 다** 잰다.
COVER_DDL="ADD INDEX idx_report_cover (session_id, rep_number, timestamp_sec, sync_rate, smoothed_knee_angle)"
have_cover(){ DB -e "SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema='shadowfit' AND table_name='pose_data' AND index_name='idx_report_cover';" | tr -d '[:space:]'; }
set_cover(){ # $1=1|0
  local want="$1" have; have=$(have_cover)
  if [ "$want" = "1" ] && [ "$have" = "0" ]; then
    echo "  + idx_report_cover 생성 중 (150만 행 — 1~2분)"; DB -e "ALTER TABLE pose_data $COVER_DDL;"
  elif [ "$want" = "0" ] && [ "$have" != "0" ]; then
    echo "  - idx_report_cover 제거"; DB -e "ALTER TABLE pose_data DROP INDEX idx_report_cover;"
  fi
  have=$(have_cover)
  if { [ "$want" = "1" ] && [ "$have" != "5" ]; } || { [ "$want" = "0" ] && [ "$have" != "0" ]; }; then
    echo "🔴 인덱스 상태 불일치: want=$want 실제컬럼수=$have — 중단" >&2; exit 1
  fi
  echo "  [단언] idx_report_cover 컬럼 수 = $have (want=$want)"
}

# ── 원상 복구 ────────────────────────────────────────────────────────────────
#
# 🔴 **이 rig 은 Flyway 가 소유한 `pose_data` 를 만진다** (#429). 위 `set_cover` 가 만든
#    인덱스를 종료 시 되돌리지 않으면, 마지막으로 세운 상태가 그대로 남는다. 같은 종류의
#    잔재가 실제로 사고를 냈다 — `measure_report_query_explain.sh` 가 남긴 `uk_pose_event`
#    때문에 나중에 Flyway V6 가 `Duplicate key name` 으로 실패했고, 실패한 마이그레이션이
#    이력에 success=0 으로 남아 **백엔드가 아예 안 떴다.**
#
#    여기는 더 새기 쉽다 — `set_cover` 의 단언이 불일치에서 `exit 1` 을 하므로, **중간에
#    죽는 경로가 스크립트 안에 이미 있다.** 그 경로로 죽으면 인덱스가 확실히 남는다.
#
#    R8(`uk_index_ridealong.sh`)의 관례를 따른다 — trap 으로 되돌리고 **복구를 확인**한다.
BASE_COVER=absent
[ "$(have_cover)" = "0" ] || BASE_COVER=present

restore_cover(){
  local rc=$?
  echo
  echo "=== 스키마 원복 (시작 시점: idx_report_cover=$BASE_COVER) ==="
  if [ "$BASE_COVER" = absent ]; then
    [ "$(have_cover)" = "0" ] || DB -e "ALTER TABLE pose_data DROP INDEX idx_report_cover;"
  else
    [ "$(have_cover)" = "0" ] && DB -e "ALTER TABLE pose_data $COVER_DDL;"
  fi

  # 「되돌렸다」와 「되돌아갔다」는 다르다.
  local now=absent
  [ "$(have_cover)" = "0" ] || now=present
  if [ "$now" = "$BASE_COVER" ]; then
    echo "  복구 확인 (idx_report_cover=$now)"
  else
    echo "  🔴 복구 실패 — 손으로 확인할 것 (지금: idx_report_cover=$now)" >&2
  fi
  return $rc
}
trap restore_cover EXIT

measure_curve(){ # $1=팔 라벨
for ((r=0;r<REPEATS;r++)); do
  out=$(docker exec -i shadowfit-mysql mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit 2>/dev/null <<'SQL'
FLUSH STATUS;
SET @t0 = NOW(6);
SELECT 'ROWS', COUNT(*) FROM (
  SELECT rep_number, AVG(sync_rate) AS q, COUNT(*) AS n
    FROM pose_data WHERE rep_number > 0
   GROUP BY rep_number ORDER BY rep_number) x;
SELECT 'MS', ROUND(TIMESTAMPDIFF(MICROSECOND, @t0, NOW(6))/1000, 1);
SELECT 'H', SUM(VARIABLE_VALUE) FROM performance_schema.session_status
 WHERE VARIABLE_NAME IN ('Handler_read_next','Handler_read_key','Handler_read_rnd_next','Handler_read_first');
SELECT 'T', SUM(VARIABLE_VALUE) FROM performance_schema.session_status
 WHERE VARIABLE_NAME IN ('Created_tmp_tables','Created_tmp_disk_tables');
SQL
)
  ms=$(echo "$out" | awk -F'\t' '$1=="MS"{print $2}')
  h=$(echo  "$out" | awk -F'\t' '$1=="H"{print $2}')
  t=$(echo  "$out" | awk -F'\t' '$1=="T"{print $2}')
  if [ -z "${ms:-}" ] || [ -z "${h:-}" ]; then
    echo "🔴 지표를 못 읽었다 — 중단" >&2; echo "$out" | sed 's/^/    /' >&2; exit 1
  fi
  echo "$1 $r $ms $h $t" >> "$SC/curve.txt"
  echo "  [$1] 판 $r → ${ms}ms · 핸들러 $h · tmp $t$([ "$r" = 0 ] && echo '   ← 버림')"
done
}
echo "arm round ms handlers tmp_tables" > "$SC/curve.txt"
set_cover 1; measure_curve with_cover
set_cover 0; measure_curve no_cover

echo
echo "## [2-b] 그 쿼리의 실행계획"
DBT -e "EXPLAIN FORMAT=TREE
SELECT rep_number, AVG(sync_rate), COUNT(*) FROM pose_data WHERE rep_number > 0
 GROUP BY rep_number ORDER BY rep_number\G" | tee "$SC/plan.txt"

{
echo "# #205 카드 B — 두 평균 · 곡선 비용 · 생성 표 (로컬, 2026-08-20)"
echo
echo "## 전제 — rep 당 프레임 수 모양"; echo; echo '```'; cat "$SC/shape.txt"; echo '```'
echo
echo "## 두 평균의 차이"; echo; echo '```'; cat "$SC/diff.txt"; echo '```'
echo
echo "## 가장 크게 갈린 세션"; echo; echo '```'; cat "$SC/top.txt"; echo '```'
echo
echo "## rep 순서별 곡선 쿼리 비용 (첫 판 버림)"; echo
echo "| 팔 | 판 | ms | 핸들러 | tmp |"; echo "|---|---|---|---|---|"
awk 'NR>1 {printf "| %s | %s | %s | %s | %s |%s\n", $1,$2,$3,$4,$5, ($2==0?" ← 버림":"")}' "$SC/curve.txt"
echo
echo "**팔별 중앙값(첫 판 제외)**"; echo
echo "| 팔 | ms 중앙값 | 핸들러 |"; echo "|---|---|---|"
for a in with_cover no_cover; do
  awk -v x="$a" 'NR>1 && $1==x && $2>0 {print $3, $4}' "$SC/curve.txt" | sort -n | awk -v x="$a" '
    {m[NR]=$1; h[NR]=$2} END{
      if (NR==0) { printf "| %s | — (유효 판 0) | — |\n", x; exit }
      a1=(NR%2)? m[(NR+1)/2] : (m[NR/2]+m[NR/2+1])/2;
      printf "| %s | %.1f | %.0f |\n", x, a1, h[1] }'
done
echo
echo "## 실행계획"; echo; echo '```'; cat "$SC/plan.txt"; echo '```'
} > "$OUT/summary.md"
cp "$SC/curve.txt" "$OUT/curve.tsv"
echo
echo "→ $OUT/summary.md (판정은 손으로 쓴 $OUT/README.md 에)"
