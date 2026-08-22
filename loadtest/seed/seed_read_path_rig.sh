#!/usr/bin/env bash
# 읽기 경로 rig 보강 — #204 EXPLAIN 스윕과 #205 카드 A·B·C 의 «전제» 를 채운다
#
# seed_report_rig.sh 가 만든 rig 에는 구멍이 넷 있었다. 전부 **실측으로 확인한 것**이다:
#
#   1. 겹치는 세션 쌍 = 0     → #205 카드 C(동시 세션 수)가 전부 0/1 로 나온다
#      (525분 간격 × 15분 지속이라 겹칠 수가 없다)
#   2. rep 당 프레임 = 30 고정 → #205 카드 B 의 「프레임 가중 ≠ rep 가중」이 **두 값이 같게** 나온다
#      (그 차이는 다운샘플로 rep 마다 살아남은 행 수가 달라야 생긴다)
#   3. 구별 회원 3명          → countDistinctActiveMembersBetween 의 DISTINCT 가 무의미
#   4. reports 10행·daily_logs 3행 → #204 §1 🟢 두 건이 const 로 접혀 계획이 안 보인다
#
# 그리고 다섯째로, pose_data 를 **버퍼풀(2GB) 위로** 올린다(재고표 §1 신규 4번).
# 지금 규모는 약 1.7GB 로 경계에 걸려 있어 «커버링이 아니라서 생기는 랜덤 접근» 이
# 전부 메모리 안에서 끝난다 — 디스크를 안 치면 그 대가가 안 보인다.
#
# 🔴 정직 단서:
#   · 도착 분포는 «06~24시 균등» 이다. 실제 저녁 피크가 아니다 — 겹침의 **존재**를 만드는 것이
#     목적이고, 피크 형태에 기대는 결론은 여기서 내면 안 된다
#   · payload 는 seed_report_rig.sh 의 템플릿 한 장을 계속 복제한다(분포 균일, 기존 한계 그대로)
#   · 로컬 2코어 동거라 절대 시간은 안 믿는다. 계획 모양·접근 행수·기제만 본다
#
# 사용: bash loadtest/seed/seed_read_path_rig.sh [U|O|V|R|X|all]
set -uo pipefail
cd "$(dirname "$0")/../.."
set -a; . ./.env; set +a

STAGE=${1:-all}
DB(){ docker exec -i shadowfit-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit "$@" 2>/dev/null; }

# n = 0..99999 생성기 (recursive CTE 한도 회피 — seed_sessions.sql 과 같은 수법)
D10="(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)"
DIGITS="(SELECT d0.n + d1.n*10 + d2.n*100 + d3.n*1000 + d4.n*10000 AS n
  FROM $D10 d0 CROSS JOIN $D10 d1 CROSS JOIN $D10 d2 CROSS JOIN $D10 d3 CROSS JOIN $D10 d4)"

# ── U. 회원 1,000명 ──────────────────────────────────────────────────────────
stage_U(){
  echo "## [U] 회원 1,000명"
  DB -e "
INSERT IGNORE INTO users (email, password, username, sex, role, selected_persona,
                          height, weight, onboarding_completed, created_at)
SELECT CONCAT('rig', n, '@seed.local'),
       'SEED_RIG_NOT_A_REAL_HASH',
       CONCAT('rig_user_', n),
       ELT(1 + (n % 3), 'MALE', 'FEMALE', 'NONE'),
       'USER',
       ELT(1 + (n % 4), 'BEGINNER', 'ADVANCED', 'DIET', 'REHAB'),
       160 + (n % 35), 50 + (n % 45), 1,
       TIMESTAMP('2025-12-01 00:00:00') + INTERVAL n MINUTE
  FROM $DIGITS digits WHERE n < 1000;"
  DB -e "SELECT COUNT(*) users_total FROM users;"
}

# ── O. 겹치는 세션 50,000개 / 50일 (DAU 1,000 가정) ─────────────────────────
# 하루 1,000 세션 · 06~24시 도착 · 지속 15~45분 → 같은 시각에 여러 세션이 살아 있다.
# 카드 C 의 «행 수를 늘리며 대조» 는 이 한 벌에서 **날짜 범위를 잘라** 만든다(1일/5일/20일/50일).
stage_O(){
  echo "## [O] 겹치는 세션 50,000개 (2026-09-01 부터 50일)"
  DB -e "
INSERT INTO exercise_sessions
    (member_id, exercise_id, reference_source, start_time, end_time,
     total_reps, avg_sync_rate, max_sync_rate, min_sync_rate, status, version, created_at)
SELECT 17 + (seq.n % 1000), 1 + (seq.n % 3), 'seedC',
       st, st + INTERVAL (15 + (seq.n % 31)) MINUTE,
       10 + (seq.n % 40), 55 + (seq.n % 40), 95.00, 45.00,
       ELT(1 + (seq.n % 10), 'COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED',
                             'COMPLETED','COMPLETED','COMPLETED','CANCELLED','FAILED'),
       0, st
  FROM (
    SELECT n,
           TIMESTAMP('2026-09-01 00:00:00')
             + INTERVAL FLOOR(n / 1000) DAY
             + INTERVAL (360 + ((n * 7919) % 1080)) MINUTE AS st
      FROM $DIGITS digits WHERE n < 50000
  ) seq;"
  DB -e "
SELECT COUNT(*) sessions_C, MIN(start_time) mn, MAX(start_time) mx
  FROM exercise_sessions WHERE reference_source='seedC';
SELECT COUNT(*) overlapping_pairs_day1 FROM exercise_sessions a JOIN exercise_sessions b
   ON a.id<b.id AND a.start_time < b.end_time AND b.start_time < a.end_time
 WHERE a.reference_source='seedC' AND b.reference_source='seedC'
   AND a.start_time < '2026-09-02' AND b.start_time < '2026-09-02';"
}

# ── V. 가변 rep 크기 세션 (카드 B 전제) ──────────────────────────────────────
# rep r 의 살아남은 프레임 수 = 3 + (r*13 % 28)  → 3~30 으로 흩어진다.
# 그래야 AVG(sync_rate) 하나가 «프레임 가중» 이 되고 GROUP BY rep 판이 «rep 가중» 이 되어 갈린다.
stage_V(){
  echo "## [V] 가변 rep 세션 200개"
  DB -e "
INSERT INTO exercise_sessions
    (member_id, exercise_id, reference_source, start_time, end_time,
     total_reps, avg_sync_rate, max_sync_rate, min_sync_rate, status, version, created_at)
SELECT 17 + (n % 1000), 1, 'seedB',
       ts, ts + INTERVAL 15 MINUTE, 25, 75.00, 95.00, 50.00, 'COMPLETED', 0, ts
  FROM (SELECT n, TIMESTAMP('2026-06-01 07:00:00') + INTERVAL (n * 137) MINUTE AS ts
          FROM $DIGITS digits WHERE n < 200) seq;"
  DB -e "
DROP TABLE IF EXISTS _pose_template_var;
CREATE TABLE _pose_template_var AS
  SELECT * FROM _pose_template
   WHERE MOD(ROUND(timestamp_sec*10), 30) < 3 + MOD((FLOOR(ROUND(timestamp_sec*10)/30)+1)*13, 28);
SELECT COUNT(*) template_rows, COUNT(DISTINCT rep_number) reps FROM _pose_template_var;
SELECT rep_number, COUNT(*) frames FROM _pose_template_var GROUP BY rep_number ORDER BY rep_number LIMIT 8;"
  DB -e "
INSERT INTO pose_data (session_id, timestamp_sec, joint_coordinates, sync_rate,
                       rep_number, smoothed_knee_angle, feedback_message, created_at)
SELECT s.id, t.timestamp_sec, t.joint_coordinates, t.sync_rate,
       t.rep_number, t.smoothed_knee_angle, t.feedback_message, s.start_time
  FROM _pose_template_var t
  CROSS JOIN (SELECT id, start_time FROM exercise_sessions WHERE reference_source='seedB') s;"
  echo "  → 카드 B 주장 확인: 두 평균이 실제로 갈리는가"
  DB -e "
SELECT s.id session_id,
       (SELECT AVG(sync_rate) FROM pose_data p WHERE p.session_id=s.id) frame_weighted,
       (SELECT AVG(a) FROM (SELECT AVG(sync_rate) a FROM pose_data p2
                             WHERE p2.session_id=s.id AND rep_number>0 GROUP BY rep_number) x) rep_weighted
  FROM exercise_sessions s WHERE s.reference_source='seedB' ORDER BY s.id LIMIT 3;"
}

# ── R. reports · daily_logs ─────────────────────────────────────────────────
stage_R(){
  echo "## [R] reports (세션당 1건) · daily_logs (회원×날짜)"
  DB -e "
INSERT IGNORE INTO reports (member_id, session_id, report_type, summary, detailed_analysis,
                            improvement_tips, created_at)
SELECT s.member_id, s.id, 'SESSION',
       CONCAT('세션 ', s.id, ' 요약'),
       JSON_OBJECT('avgSyncRate', s.avg_sync_rate, 'totalReps', s.total_reps,
                   'worstRep', 1 + (s.id % 25), 'kneeAngleMin', 80 + (s.id % 20)),
       '무릎이 안쪽으로 모이지 않게 유지하세요',
       s.end_time
  FROM exercise_sessions s
 WHERE s.reference_source IN ('seed204','seed204b','seedB','seedC');"
  DB -e "
INSERT IGNORE INTO daily_logs (member_id, log_date, total_exercise_time, total_calories, mood, created_at)
SELECT s.member_id, DATE(s.start_time),
       SUM(TIMESTAMPDIFF(SECOND, s.start_time, s.end_time)),
       ROUND(SUM(TIMESTAMPDIFF(MINUTE, s.start_time, s.end_time)) * 7.2, 2),
       ELT(1 + (s.member_id % 5), 'GREAT','GOOD','NORMAL','BAD','TERRIBLE'),
       MIN(s.start_time)
  FROM exercise_sessions s
 WHERE s.reference_source IN ('seed204','seed204b','seedB','seedC')
 GROUP BY s.member_id, DATE(s.start_time);"
  DB -e "SELECT (SELECT COUNT(*) FROM reports) reports, (SELECT COUNT(*) FROM daily_logs) daily_logs;"
}

# ── X. pose_data 를 버퍼풀(2GB) 위로 ────────────────────────────────────────
stage_X(){
  echo "## [X] pose_data 2배 — 세션 1,000개 추가 (버퍼풀 초과 규모)"
  DB -e "
INSERT INTO exercise_sessions
    (member_id, exercise_id, reference_source, start_time, end_time,
     total_reps, avg_sync_rate, max_sync_rate, min_sync_rate, status, version, created_at)
SELECT 17 + (n % 1000), 1 + (n % 3), 'seed204b',
       ts, ts + INTERVAL 15 MINUTE, 25, 75.00, 95.00, 50.00, 'COMPLETED', 0, ts
  FROM (SELECT n, TIMESTAMP('2026-01-01 18:00:00') + INTERVAL (n * 525) MINUTE AS ts
          FROM $DIGITS digits WHERE n < 1000) seq;"
  for i in $(seq 0 9); do
    DB -e "
INSERT INTO pose_data (session_id, timestamp_sec, joint_coordinates, sync_rate,
                       rep_number, smoothed_knee_angle, feedback_message, created_at)
SELECT s.id, t.timestamp_sec, t.joint_coordinates, t.sync_rate,
       t.rep_number, t.smoothed_knee_angle, t.feedback_message, s.start_time
  FROM _pose_template t
  CROSS JOIN (SELECT id, start_time FROM exercise_sessions
               WHERE reference_source='seed204b' AND id % 10 = $i) s;"
    echo "  청크 $i/9  ($(DB -N -e 'SELECT COUNT(*) FROM pose_data') 행)"
  done
}

case "$STAGE" in
  U) stage_U ;;
  O) stage_O ;;
  V) stage_V ;;
  R) stage_R ;;
  X) stage_X ;;
  all) stage_U; stage_O; stage_V; stage_X; stage_R ;;
esac

echo
echo "## 확인"
DB -e "ANALYZE TABLE pose_data, exercise_sessions, users, reports, daily_logs;" >/dev/null
DB -e "
SELECT reference_source, COUNT(*) sessions FROM exercise_sessions GROUP BY reference_source;
SELECT COUNT(*) pose_rows, COUNT(DISTINCT session_id) pose_sessions FROM pose_data;
SELECT ROUND((data_length+index_length)/1024/1024) mb_total, ROUND(data_length/1024/1024) mb_data,
       ROUND(index_length/1024/1024) mb_index
  FROM information_schema.tables WHERE table_schema='shadowfit' AND table_name='pose_data';"
