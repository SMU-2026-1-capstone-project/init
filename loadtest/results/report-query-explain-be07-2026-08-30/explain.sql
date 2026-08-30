-- BE-07(패턴 분석) 세션5 EXPLAIN — 로컬 (2026-08-30)
-- 설계: ../../../docs/decisions/pattern-analysis-implementation.md §3 (세션5)
-- 대상: shadowfit DB, member_id=1 (dev-seed 계정, 1,680세션, 2026-05-28~2026-08-29,
--       COMPLETED 1,226 / FAILED 454 — mysql/dev-seed.sql 이 심은 로드테스트용 합성 계정)
-- 실행: docker exec -i shadowfit-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit' < explain.sql
--
-- 확인 대상: findStartTimesByMemberAndRange · findIntensitySamplesByMemberAndRange 둘 다
-- status 등치 조건이 없다 — idx_session_member_status_start(member_id, status, start_time)가
-- status 등치 없이 start_time을 seek 못 해 회원 전체 이력을 읽지 않는지가 쟁점.

-- 0) 대조용 — 실제 창 안에 몇 행이 있는지(참값). "오늘" = 2026-08-30(일) 기준
--    서비스 코드의 윈도우 계산(PatternAnalysisService)을 손으로 넣은 값.
SELECT COUNT(*) AS actual_periodicity_rows FROM exercise_sessions
WHERE member_id=1 AND start_time BETWEEN '2026-08-02 12:00:00' AND '2026-08-30 12:00:00';

SELECT COUNT(*) AS actual_intensity_rows FROM exercise_sessions
WHERE member_id=1 AND avg_sync_rate IS NOT NULL AND start_time BETWEEN '2026-08-03 00:00:00' AND '2026-08-30 12:00:00';

SELECT COUNT(DISTINCT CAST(start_time AS date)) AS actual_consistency_days FROM exercise_sessions
WHERE member_id=1 AND status='COMPLETED' AND start_time BETWEEN '2026-08-03 00:00:00' AND '2026-08-30 12:00:00';

-- 1) periodicity — findStartTimesByMemberAndRange (status 조건 없음)
EXPLAIN ANALYZE SELECT start_time FROM exercise_sessions
WHERE member_id=1 AND start_time BETWEEN '2026-08-02 12:00:00' AND '2026-08-30 12:00:00';

-- 2) intensity-trend — findIntensitySamplesByMemberAndRange (status 조건 없음, avg_sync_rate IS NOT NULL)
EXPLAIN ANALYZE SELECT start_time, end_time, avg_sync_rate FROM exercise_sessions
WHERE member_id=1 AND avg_sync_rate IS NOT NULL AND start_time BETWEEN '2026-08-03 00:00:00' AND '2026-08-30 12:00:00';

-- 2-b) 정적 EXPLAIN(ANALYZE 아님) FORMAT=JSON — 옵티마이저의 사전 추정치(rows_examined_per_scan)를
--      실제 핸들러 카운터(아래 4번)와 대조하기 위한 것. 추정 1,680(=회원 전체) vs 실측 680.
EXPLAIN FORMAT=JSON SELECT start_time, end_time, avg_sync_rate FROM exercise_sessions
WHERE member_id=1 AND avg_sync_rate IS NOT NULL AND start_time BETWEEN '2026-08-03 00:00:00' AND '2026-08-30 12:00:00';

-- 3) consistency — findDistinctActiveDates(memberId, List.of(COMPLETED), ...) 단일 status 등치
EXPLAIN ANALYZE SELECT DISTINCT CAST(start_time AS date) FROM exercise_sessions
WHERE member_id=1 AND status IN ('COMPLETED') AND start_time BETWEEN '2026-08-03 00:00:00' AND '2026-08-30 12:00:00';

-- 4) Handler_read_next 실측 — EXPLAIN(ANALYZE)의 "rows"보다 신뢰할 수 있는 스토리지 엔진
--    레벨 실제 읽기 횟수. FLUSH STATUS로 세션 카운터를 리셋한 뒤 쿼리 하나씩 단독 실행.
--    (아래는 재현 절차 기록용 — 실제 실행은 세션을 분리해서 한다. §결과는 README.md 참고)
FLUSH STATUS;
SELECT start_time FROM exercise_sessions
WHERE member_id=1 AND start_time BETWEEN '2026-08-02 12:00:00' AND '2026-08-30 12:00:00';
SHOW SESSION STATUS LIKE 'Handler_read%';

FLUSH STATUS;
SELECT start_time, end_time, avg_sync_rate FROM exercise_sessions
WHERE member_id=1 AND avg_sync_rate IS NOT NULL AND start_time BETWEEN '2026-08-03 00:00:00' AND '2026-08-30 12:00:00';
SHOW SESSION STATUS LIKE 'Handler_read%';

-- 5) 재현성 대조 — 매칭 구간을 "최근 tail"이 아니라 대량 배치(1,000행) 앞머리로 옮겨도
--    스캔 규모가 참값(1,000)에 그대로 붙는지 확인. (이 회원의 데이터는 2026-05 COMPLETED
--    1,000행 + 2026-08 COMPLETED 226행/FAILED 454행 두 배치뿐이고 6~7월은 공백이다 —
--    로드테스트 합성 시딩의 결과, project_synthetic_data_distribution_limit 참고)
FLUSH STATUS;
SELECT start_time FROM exercise_sessions
WHERE member_id=1 AND start_time BETWEEN '2026-05-28 00:00:00' AND '2026-06-04 23:59:59';
SHOW SESSION STATUS LIKE 'Handler_read_next';

FLUSH STATUS;
SELECT start_time, end_time, avg_sync_rate FROM exercise_sessions
WHERE member_id=1 AND avg_sync_rate IS NOT NULL AND start_time BETWEEN '2026-05-28 00:00:00' AND '2026-06-04 23:59:59';
SHOW SESSION STATUS LIKE 'Handler_read_next';
