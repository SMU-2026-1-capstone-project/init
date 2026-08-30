-- BE-07 세션9 응답시간 실측용 대량 계정 시딩.
-- bulk-timing@test.com (사전에 /member/signup 으로 생성) 앞으로 COMPLETED 세션 2,000건을
-- 최근 180일에 분산 적재 — session5 EXPLAIN 판(member_id=1, 1,680세션)과 비슷한 자릿수로
-- 맞춰, 그 EXPLAIN 결과가 실제 응답시간에도 반영되는지 참고할 규모.
INSERT INTO exercise_sessions
    (member_id, exercise_id, reference_source, start_time, end_time,
     total_reps, avg_sync_rate, max_sync_rate, min_sync_rate, status, version, last_active_at)
SELECT
    (SELECT id FROM users WHERE email='bulk-timing@test.com') AS member_id,
    1 AS exercise_id,
    'response-time-seed' AS reference_source,
    ts AS start_time,
    ts + INTERVAL 20 MINUTE AS end_time,
    25, 70.00, 85.00, 55.00,
    'COMPLETED', 0, ts + INTERVAL 20 MINUTE
FROM (
    SELECT NOW() - INTERVAL (n * 130) MINUTE AS ts
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
    WHERE n < 2000
) seq;
