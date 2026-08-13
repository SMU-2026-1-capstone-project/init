-- 다중 세션 판(batch_multi.json)용 시드 — 세션 901~1900.
--
-- 왜 필요한가: batch_multi.json 은 요청마다 다른 sessionId(901~1900)를 쓴다. 그래야 요청들이
-- 서로 다른 행을 잠가 실운영에 가까운 조건이 된다. 이 세션들이 DB 에 없으면 savePoseDataBatch 가
-- 존재 검증에서 전부 거절하므로 측정 자체가 성립하지 않는다.
--
-- ⚠️ 이 행들은 측정 후에도 dev DB 에 남겨뒀다(재현용). 구분자는 reference_source='loadtest-multi'.
--    부수효과: 관리자 대시보드 통계(AdminStatsService)가 이 1000건을 같이 센다 — 전부 COMPLETED 라
--    로컬에서 세션 수·완료율이 부풀어 보인다. 지우려면 아래 DELETE.
--
-- 적용:
--   docker exec -i shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit < seed-multi-sessions.sql

INSERT INTO exercise_sessions
  (id, member_id, exercise_id, reference_source, start_time, end_time, last_active_at,
   total_reps, avg_sync_rate, max_sync_rate, min_sync_rate, calories_burned,
   difficulty_level, status, version, created_at)
SELECT 900 + n, 1, 1, 'loadtest-multi', '2026-05-28 10:00:00', '2026-05-28 10:03:30', NOW(),
       30, 65.50, 92.00, 42.10, 145.00, 2, 'COMPLETED', 0, NOW()
FROM (SELECT a.N + b.N*10 + c.N*100 + 1 AS n FROM
        (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
        (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
        (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c
      ) nums
WHERE n <= 1000
ON DUPLICATE KEY UPDATE id = id;

-- 확인
SELECT COUNT(*) AS seeded FROM exercise_sessions WHERE id BETWEEN 901 AND 1900;

-- 되돌리기
-- DELETE FROM pose_data WHERE session_id BETWEEN 901 AND 1900;
-- DELETE FROM exercise_sessions WHERE reference_source = 'loadtest-multi';