-- HTTP 읽기 4엔드포인트(weekly-summary/calendar/daily) 뒤 쿼리 EXPLAIN — 로컬 (2026-08-24)
-- 설계: ../../../docs/decisions/http-read-p99-cause-attribution.md §2-2
-- 대상: shadowfit DB, member_id=1024 (2026-08-23 http-read-p99 로컬판이 시딩한 k6load 계정, 1,000세션)
-- 실행: docker exec shadowfit-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit -e "<쿼리>"'

-- 1) daily — SessionRepository.findByMemberIdAndStartTimeBetween (1일 범위)
EXPLAIN ANALYZE SELECT s.* FROM exercise_sessions s
WHERE s.member_id = 1024 AND s.start_time BETWEEN '2026-08-20 00:00:00' AND '2026-08-20 23:59:59';

-- 2) calendar 본체 — 같은 리포지토리 메서드, 월 범위
EXPLAIN ANALYZE SELECT s.* FROM exercise_sessions s
WHERE s.member_id = 1024 AND s.start_time BETWEEN '2026-08-01 00:00:00' AND '2026-08-31 23:59:59';

-- 3) calendar 숨은 2차 쿼리 — SessionRepository.findDistinctActiveDates
--    (SessionService.calculateConsecutiveDays 가 오늘 기준 100일 창으로 호출, SessionService.java:685-699)
EXPLAIN ANALYZE SELECT DISTINCT CAST(s.start_time AS date) FROM exercise_sessions s
WHERE s.member_id = 1024 AND s.start_time BETWEEN '2026-05-16 00:00:00' AND '2026-08-24 23:59:59';

-- 3-b) 대조 — idx_session_starttime_member(start_time, member_id) 를 강제했을 때
EXPLAIN ANALYZE SELECT DISTINCT CAST(s.start_time AS date) FROM exercise_sessions s FORCE INDEX (idx_session_starttime_member)
WHERE s.member_id = 1024 AND s.start_time BETWEEN '2026-05-16 00:00:00' AND '2026-08-24 23:59:59';

-- 4) weekly-summary — SessionRepository.findWeeklySessionsWithExercise (exercise JOIN FETCH)
EXPLAIN ANALYZE SELECT s.* FROM exercise_sessions s JOIN exercises e ON s.exercise_id = e.id
WHERE s.member_id = 1024 AND s.start_time BETWEEN '2026-08-17 00:00:00' AND '2026-08-23 23:59:59';

-- 5) #541 고침 검증 — status IN (전체값) 을 붙이면 idx_session_member_status_start 가
--    상태별 range scan 4개로 쪼개져 start_time 을 seek 한다. 결과 집합은 그대로(101개
--    distinct 날짜)이고 examined rows 만 1,040 → 277 로 준다. 결과: fix_verify.txt
EXPLAIN ANALYZE SELECT DISTINCT CAST(s.start_time AS date) FROM exercise_sessions s
WHERE s.member_id = 1024 AND s.status IN ('IN_PROGRESS','COMPLETED','CANCELLED','FAILED')
  AND s.start_time BETWEEN '2026-05-16 00:00:00' AND '2026-08-24 23:59:59';
