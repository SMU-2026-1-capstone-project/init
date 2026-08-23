#!/usr/bin/env bash
# #204 EXPLAIN 스윕 — 리포트 읽기 경로 쿼리의 «무게» 를 예상에서 사실로 바꾼다
#
# 이슈: https://github.com/Shadowfit/init/issues/204
#   §1 이 쿼리를 🟢🟡🔴 로 분류했지만 §4 가 «EXPLAIN 미실행» 이라고 못박고 있다.
#   특히 §2 는 findFramesBySessionId 가 셋을 동시에 놓친다고 «예상» 한다:
#     ㄱ. 파티션 프루닝 미적용 (WHERE 에 created_at 이 없다 → 14 파티션 전부)
#     ㄴ. 커버링 아님 (sync_rate·smoothed_knee_angle·rep_number 가 인덱스 밖 → 행 본체 랜덤 접근)
#     ㄷ. 정렬이 인덱스와 어긋남 → filesort
#
# 팔(arm) 셋을 같은 데이터에 대고 비교한다:
#   A  현재 로컬 스키마         idx_session_timestamp (session_id, timestamp_sec)
#   B  + uk_pose_event         V6 멱등 키 (session_id, rep_number, timestamp_sec, created_at)
#      🔴 이슈는 V6 **이전에** 쓰였다. 이 키는 §2-ㄷ 이 요구하는 정렬 순서를 그대로 갖고 있다
#      ⚠️ V6 은 **origin/main 에 없다** — #188 작업 브랜치에만 있는 미머지 상태다.
#         즉 B 는 «현재 main» 이 아니라 «#188 이 머지되면 될 모습» 이다
#   C  + 커버링 인덱스 후보     (session_id, rep_number, timestamp_sec, sync_rate, smoothed_knee_angle)
#      §2 «후보 수정» 이 ㄴ·ㄷ 을 같이 닫는다고 적은 그 인덱스다
#
# 🔴 이 rig 으로 답이 나오는 것 / 안 나오는 것 (project_synthetic_data_distribution_limit):
#   ✅ 계획 모양(프루닝·인덱스 선택·filesort), 접근 행수(Handler_*), 정렬 행수
#   ❌ 옵티마이저 카디널리티 추정의 «정확도» — 세션 1,000개가 같은 템플릿 복제라 분포가 균일하다
#   ❌ 절대 시간 — 로컬 2코어 동거(project_loadtest_env_constraint). EXPLAIN ANALYZE 시간은
#      **팔 간 상대비교로만** 읽을 것
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a

ARM=${1:-A}
TAG=${TAG:-seed204}
REPEATS=${REPEATS:-5}
OUT=${OUT:-loadtest/results/report-query-explain-2026-08-19}
mkdir -p "$OUT"

DB(){ docker exec -i shadowfit-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit "$@" 2>/dev/null; }

# ── 팔 준비 ──────────────────────────────────────────────────────────────────
UK="ALTER TABLE pose_data ADD UNIQUE KEY uk_pose_event (session_id, rep_number, timestamp_sec, created_at)"
COV="ALTER TABLE pose_data ADD KEY idx_report_cover (session_id, rep_number, timestamp_sec, sync_rate, smoothed_knee_angle)"

has_index(){ [ "$(DB -N -e "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='shadowfit' AND table_name='pose_data' AND index_name='$1'")" -gt 0 ]; }

# ── 원상 복구 ────────────────────────────────────────────────────────────────
#
# 🔴 **이 rig 이 스키마를 만지고 안 돌려놨다** (#429). 팔 B·C 가 인덱스를 만드는데 종료 시
#    복구가 없어서, 마지막에 돈 팔의 스키마가 그대로 남았다. 그 결과 나중에 Flyway 가
#    V6(`ADD UNIQUE KEY uk_pose_event`)을 적용할 때 **`Duplicate key name` 으로 실패**하고,
#    실패한 마이그레이션이 이력에 success=0 으로 남아 **백엔드가 아예 안 떴다.**
#    스키마는 맞는데 이력만 틀린 상태라 원인까지 가는 데 시간이 든다.
#
#    같은 계열 rig 인 R8(`uk_index_ridealong.sh`)은 처음부터 trap 으로 되돌리고 **복구를
#    확인**까지 한다. 그 관례를 여기에도 맞춘다.
#
# ⚠️ **되돌리는 것은 인덱스뿐이다.** 아래 `dedup` 이 지우는 행은 복구 경로가 없다
#    (V6 가 main 에 들어온 뒤로는 중복이 존재할 수 없어 사실상 0행이지만, 옛 스냅샷에
#    돌리면 지워진다). 그건 이 trap 이 답할 수 있는 문제가 아니다.
BASE_UK=absent
BASE_COV=absent
has_index uk_pose_event    && BASE_UK=present
has_index idx_report_cover && BASE_COV=present

restore_schema(){
  local rc=$?
  echo
  echo "=== 스키마 원복 (시작 시점: uk_pose_event=$BASE_UK · idx_report_cover=$BASE_COV) ==="

  if [ "$BASE_UK" = absent ]; then
    has_index uk_pose_event    && DB -e "ALTER TABLE pose_data DROP KEY uk_pose_event"
  else
    has_index uk_pose_event    || { dedup; DB -e "$UK"; }
  fi

  if [ "$BASE_COV" = absent ]; then
    has_index idx_report_cover && DB -e "ALTER TABLE pose_data DROP KEY idx_report_cover"
  else
    has_index idx_report_cover || DB -e "$COV"
  fi

  # 「되돌렸다」와 「되돌아갔다」는 다르다 — R8 이 같은 이유로 확인을 넣었다.
  local now_uk=absent now_cov=absent
  has_index uk_pose_event    && now_uk=present
  has_index idx_report_cover && now_cov=present
  if [ "$now_uk" = "$BASE_UK" ] && [ "$now_cov" = "$BASE_COV" ]; then
    echo "  복구 확인 (uk_pose_event=$now_uk · idx_report_cover=$now_cov)"
  else
    echo "  🔴 복구 실패 — 손으로 확인할 것 (지금: uk_pose_event=$now_uk · idx_report_cover=$now_cov)" >&2
  fi
  return $rc
}
trap restore_schema EXIT

# V6 마이그레이션의 1) 단계와 같다 — 기존 위반 행(부하 rig 이 같은 메시지를 여러 번 보낸 것,
# 전부 세션 801)을 지워야 ADD UNIQUE KEY 가 성립한다. 그룹당 최소 id 1행은 반드시 남는다.
dedup(){
  DB -e "
CREATE TEMPORARY TABLE tmp_pose_dup (
    session_id BIGINT NOT NULL, rep_number INT NOT NULL,
    timestamp_sec DECIMAL(10,3) NOT NULL, created_at TIMESTAMP NOT NULL,
    keep_id BIGINT NOT NULL,
    PRIMARY KEY (session_id, rep_number, timestamp_sec, created_at)) ENGINE=InnoDB;
INSERT INTO tmp_pose_dup SELECT session_id, rep_number, timestamp_sec, created_at, MIN(id)
  FROM pose_data GROUP BY session_id, rep_number, timestamp_sec, created_at HAVING COUNT(*) > 1;
SELECT CONCAT('  중복 그룹 ', COUNT(*)) FROM tmp_pose_dup;
DELETE p FROM pose_data p JOIN tmp_pose_dup d
    ON d.session_id=p.session_id AND d.rep_number=p.rep_number
   AND d.timestamp_sec=p.timestamp_sec AND d.created_at=p.created_at
 WHERE p.id > d.keep_id;
DROP TEMPORARY TABLE tmp_pose_dup;"
}

setup_arm(){
  case "$1" in
    A) has_index idx_report_cover && DB -e "ALTER TABLE pose_data DROP KEY idx_report_cover"
       has_index uk_pose_event    && DB -e "ALTER TABLE pose_data DROP KEY uk_pose_event" ;;
    B) has_index idx_report_cover && DB -e "ALTER TABLE pose_data DROP KEY idx_report_cover"
       has_index uk_pose_event    || { dedup; echo "  (uk_pose_event 생성 중…)"; time DB -e "$UK"; } ;;
    C) has_index uk_pose_event    || { dedup; DB -e "$UK"; }
       has_index idx_report_cover || { echo "  (idx_report_cover 생성 중…)"; time DB -e "$COV"; } ;;
  esac
  DB -e "ANALYZE TABLE pose_data;" >/dev/null
}

# ── 표본 세션 — 서로 다른 달에서 뽑는다(파티션이 갈리도록) ──────────────────
mapfile -t SIDS < <(DB -N -e "
  SELECT id FROM exercise_sessions
   WHERE reference_source='$TAG' AND id % 97 = 0
   ORDER BY start_time LIMIT $REPEATS")
SID=${SIDS[0]}
SID_START=$(DB -N -e "SELECT start_time FROM exercise_sessions WHERE id=$SID")

STATUS_VARS="'Handler_read_key','Handler_read_next','Handler_read_rnd_next','Handler_read_last','Sort_rows','Sort_scan','Sort_range','Created_tmp_tables','Created_tmp_disk_tables'"

# 쿼리 하나에 대해: EXPLAIN(파티션 포함) · TREE · 핸들러 카운터 · 시간 REPEATS 판
probe(){
  local name="$1" sql="$2"
  echo
  echo "### $name"
  echo '```sql'
  echo "$sql" | sed 's/^ *//'
  echo '```'
  echo
  echo "**EXPLAIN**"
  echo '```'
  DB -e "EXPLAIN $sql\G" | sed -n '/partitions\|key\|rows\|filtered\|Extra\|type:\|table:/p'
  echo '```'
  echo "**EXPLAIN FORMAT=TREE**"
  echo '```'
  DB -e "EXPLAIN FORMAT=TREE $sql\G" | sed -n '2,$p'
  echo '```'
  echo "**핸들러 카운터** (FLUSH STATUS 직후 1회 실행)"
  echo '```'
  DB -e "FLUSH STATUS; $sql; SELECT '===MARK==='; SHOW SESSION STATUS WHERE Variable_name IN ($STATUS_VARS);" \
    | awk '/===MARK===/{m=1;next} m' | awk 'NF && $2+0>0'
  echo '```'
  echo "**EXPLAIN ANALYZE 실제시간** (세션 $REPEATS 개 · 첫 판은 워밍업으로 버린다)"
  echo '```'
  local i=0
  for s in "${SIDS[@]}"; do
    i=$((i+1))
    local q=${sql//$SID/$s}
    local t
    t=$(DB -e "EXPLAIN ANALYZE $q\G" | grep -oE 'actual time=[0-9.]+\.\.[0-9.]+ rows=[0-9]+ loops=[0-9]+' | head -1)
    if [ "$i" = 1 ]; then echo "  s=$s  $t   ← 워밍업(버림)"; else echo "  s=$s  $t"; fi
  done
  echo '```'
}

echo "=== ARM $ARM 준비 ==="
setup_arm "$ARM"
DB -e "SHOW INDEX FROM pose_data" | awk 'NR==1||$3!="PRIMARY"{print $3, $4, $5}' | sort -u

{
echo "# ARM $ARM — #204 EXPLAIN 스윕"
echo
echo "- 실행: $(git rev-parse --short HEAD) · 표본 세션 ${SIDS[*]}"
echo "- pose_data 인덱스:"
echo '```'
DB -e "SHOW INDEX FROM pose_data" | awk '{print $3"\t"$4"\t"$5}'
echo '```'
echo '```'
DB -e "SELECT COUNT(*) rows_total, COUNT(DISTINCT session_id) sessions FROM pose_data;
       SELECT partition_name, table_rows FROM information_schema.partitions
        WHERE table_schema='shadowfit' AND table_name='pose_data' AND table_rows>0
        ORDER BY partition_ordinal_position;"
echo '```'

probe "R1 🔴 findFramesBySessionId — 리포트 핵심 경로 (§2)" \
"SELECT timestamp_sec, sync_rate, rep_number, smoothed_knee_angle FROM pose_data WHERE session_id = $SID ORDER BY rep_number ASC, timestamp_sec ASC"

probe "R1p 후보수정 ㄱ — created_at 범위를 같이 넘긴 판" \
"SELECT timestamp_sec, sync_rate, rep_number, smoothed_knee_angle FROM pose_data WHERE session_id = $SID AND created_at >= '$SID_START' AND created_at < '$SID_START' + INTERVAL 1 DAY ORDER BY rep_number ASC, timestamp_sec ASC"

probe "R2 🟡 findMaxRepNumberBySessionId" \
"SELECT COALESCE(MAX(rep_number),0) FROM pose_data WHERE session_id = $SID"

probe "R3 🟢 findMaxTimestampSecBySessionId" \
"SELECT COALESCE(MAX(timestamp_sec),0.0) FROM pose_data WHERE session_id = $SID"

probe "R4 🟡 findRepAverageSyncRates" \
"SELECT AVG(sync_rate) FROM pose_data WHERE session_id = $SID AND rep_number > 0 GROUP BY rep_number ORDER BY rep_number"

probe "R5 🟢 countSince — 프루닝이 걸린다고 적힌 대조군" \
"SELECT COUNT(*) FROM pose_data WHERE session_id IN ($SID) AND created_at > '2026-12-01 00:00:00'"

echo
echo "### R6 🔴 deleteBySessionIdIn — EXPLAIN 만 (실행 안 함)"
echo '```'
DB -e "EXPLAIN DELETE FROM pose_data WHERE session_id IN ($SID)\G"
echo '```'
} > "$OUT/arm-$ARM.md"

echo "→ $OUT/arm-$ARM.md"
