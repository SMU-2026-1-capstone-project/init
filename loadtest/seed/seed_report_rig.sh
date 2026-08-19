#!/usr/bin/env bash
# 리포트 읽기 경로 rig — #204 EXPLAIN 스윕용 시딩
#
# 기존 rig A(README §rig A)와 무엇이 다른가:
#   rig A 는 템플릿의 rep_number·smoothed_knee_angle 이 **전부 0** 이다(원본 601 재현).
#   그 상태로는 #204 가 묻는 것을 못 잰다 —
#     · findFramesBySessionId 의 ORDER BY rep_number 가 «전부 같은 값» 정렬이 되고
#     · findRepAverageSyncRates 는 rep_number > 0 필터에 걸려 0행을 낸다
#   그래서 이 스크립트는 템플릿에 **rep 경계를 실제로 넣는다**.
#
# 🔴 정직 단서 — 합성 분포의 한계(project_synthetic_data_distribution_limit):
#   세션 1,000개가 **같은 템플릿의 복제**다. rep 당 프레임 수도 30 으로 고정이고
#   sync_rate 도 rep 번호의 함수다. 즉 **값 분포가 균일**하다.
#   → 계획 모양(파티션 프루닝·인덱스 선택·filesort 유무)과 **접근 행수**는 이 rig 으로 답이 나오지만,
#     옵티마이저 카디널리티 추정·선택도에 의존하는 결론은 **여기서 내면 안 된다**.
#
# 규모: 세션 1,000개 × 750행 = 750,000행. created_at = 세션 start_time 이라
#   2026-01 ~ 2026-12 열두 파티션에 ~균등 분산된다(파티션 프루닝 실험의 전제).
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a; . ./.env; set +a

SESSIONS=${SESSIONS:-1000}
ROWS=${ROWS:-750}
REP_FRAMES=${REP_FRAMES:-30}   # rep 당 프레임 수 → 750/30 = rep 25개/세션
TAG=${TAG:-seed204}

DB(){ docker exec -i shadowfit-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit "$@"; }

echo "## [1] 세션 $SESSIONS 개 — 2026 전 기간 균등 분산 (tag=$TAG)"
# 525분 간격 × 1,000 = 525,000분 ≈ 364.6일 → 2026 한 해를 덮는다
DB -e "
INSERT INTO exercise_sessions
    (member_id, exercise_id, reference_source, start_time, end_time,
     total_reps, avg_sync_rate, max_sync_rate, min_sync_rate, status, version, created_at)
SELECT ELT(1 + (n % 3), 1, 5, 12), 1 + (n % 3), '$TAG',
       ts, ts + INTERVAL 15 MINUTE,
       $((ROWS / REP_FRAMES)), 75.00, 95.00, 50.00, 'COMPLETED', 0, ts
FROM (
    SELECT n, TIMESTAMP('2026-01-01 06:00:00') + INTERVAL (n * 525) MINUTE AS ts
    FROM (
        SELECT d0.n + d1.n*10 + d2.n*100 + d3.n*1000 AS n
        FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
              UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d0
        CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
              UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d1
        CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
              UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d2
        CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
              UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d3
    ) digits
    WHERE n < $SESSIONS
) seq;"
DB -e "SELECT COUNT(*) sessions, MIN(start_time) mn, MAX(start_time) mx
       FROM exercise_sessions WHERE reference_source='$TAG';"

echo
echo "## [2] 템플릿 $ROWS 행 생성 (33 랜드마크 JSON) + rep 경계 주입"
python loadtest/seed/gen_pose_template.py --rows "$ROWS" --out /tmp/_template204.sql
DB < /tmp/_template204.sql

# gen_pose_template.py 는 rep_number·smoothed_knee_angle 을 0 으로 낸다(원본 601 재현).
# 여기서 실제 값으로 덮는다:
#   rep_number          = 프레임 인덱스 / REP_FRAMES + 1   (1..25)
#   smoothed_knee_angle = rep 안에서 170°(선 자세) → 80°(최저점) → 170° 코사인
#   sync_rate           = rep 단위 상수 (한 rep 의 모든 프레임이 같은 값 — 실제 계약)
DB -e "
UPDATE _pose_template SET
  rep_number          = FLOOR(ROUND(timestamp_sec*10) / $REP_FRAMES) + 1,
  smoothed_knee_angle = ROUND(125 + 45*COS(2*PI()*(MOD(ROUND(timestamp_sec*10), $REP_FRAMES)/$REP_FRAMES)), 2),
  sync_rate           = 55 + MOD((FLOOR(ROUND(timestamp_sec*10) / $REP_FRAMES) + 1) * 7, 40);
SELECT rep_number, COUNT(*) frames, MIN(smoothed_knee_angle) knee_min, MAX(smoothed_knee_angle) knee_max,
       COUNT(DISTINCT sync_rate) sync_distinct
  FROM _pose_template GROUP BY rep_number ORDER BY rep_number LIMIT 3;"

echo
echo "## [3] cross join 적재 (청크 10) — created_at = 세션 start_time"
for i in $(seq 0 9); do
  DB -e "
    INSERT INTO pose_data (session_id, timestamp_sec, joint_coordinates, sync_rate,
                           rep_number, smoothed_knee_angle, feedback_message, created_at)
    SELECT s.id, t.timestamp_sec, t.joint_coordinates, t.sync_rate,
           t.rep_number, t.smoothed_knee_angle, t.feedback_message, s.start_time
    FROM _pose_template t
    CROSS JOIN (SELECT id, start_time FROM exercise_sessions
                 WHERE reference_source='$TAG' AND id % 10 = $i) s;"
  echo "  청크 $i/9"
done

echo
echo "## [4] 통계 갱신 · 확인"
DB -e "ANALYZE TABLE pose_data;"
DB -e "
SELECT COUNT(*) rows_total, COUNT(DISTINCT session_id) sessions FROM pose_data;
SELECT partition_name, table_rows FROM information_schema.partitions
 WHERE table_schema='shadowfit' AND table_name='pose_data' AND table_rows > 0
 ORDER BY partition_ordinal_position;"
