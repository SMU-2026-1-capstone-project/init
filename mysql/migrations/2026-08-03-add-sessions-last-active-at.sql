-- 2026-08-03 — exercise_sessions.last_active_at 추가 (PR #102, 세션 생존 판정 ㄷ안)
--
-- 왜 이 컬럼이 필요한가:
--   타임아웃 식에 활동 항이 없어 세 가지가 한꺼번에 틀렸다 — 그만둔 세션을 45분간 붙들고,
--   45분 넘게 운동 중인 세션을 프레임이 들어오는 중에 걷어가고, 그렇게 찍힌 end_time 이 주간
--   통계에 운동 시간으로 합산됐다. 고정된 시간창은 필연적으로 양방향으로 틀린다.
--   → docs/decisions/session-liveness-vs-elapsed-time.md
--
-- 왜 이 파일이 필요한가:
--   schema.sql 은 CREATE TABLE IF NOT EXISTS 라 이미 만들어진 DB 에는 신규 컬럼이 반영되지 않는다.
--   Flyway/Liquibase 가 없어 신규 설치는 schema.sql 이, 기존 인스턴스는 이 디렉터리가 담당한다.
--
--   ⚠️ PR #102 는 schema.sql 만 고치고 이 파일을 빠뜨린 채 머지됐다. 그 상태로 기존 DB 에
--      배포하면 ddl-auto: none 이라 Hibernate 가 컬럼을 만들지 않고,
--      PoseDataService 의 UPDATE ... SET last_active_at 이 Unknown column 으로 실패한다.
--      이 파일이 그 소급 보완이다.
--
-- 적용 대상: PR #102 이전에 생성된 shadowfit DB (로컬 docker volume, EC2 등)
-- 멱등성: MySQL 은 ADD COLUMN IF NOT EXISTS 를 지원하지 않으므로 재실행 시 1060 (Duplicate column)
--         이 나면 이미 적용된 것이다.
--
-- 소요 시간: exercise_sessions 는 세션 단위라 행 수가 pose_data 보다 훨씬 작다. 짧게 끝난다.

USE shadowfit;

-- NULL 을 허용하는 이유: NULL 이 "아직 rep 이 하나도 없음"을 뜻하는 의미 있는 값이다.
-- 그 경우 타임아웃 판정이 기존 식(start_time + 예상시간 + 버퍼)으로 폴백하므로, 이 컬럼이
-- 비어 있는 기존 행들은 종전과 완전히 같게 동작한다. 첫 rep 이 들어오는 순간 자연스럽게
-- 유휴 기준으로 넘어간다 — 그래서 배포 순서에 제약이 없다.
ALTER TABLE exercise_sessions
    ADD COLUMN last_active_at DATETIME NULL
    AFTER end_time;

-- 인덱스를 만들지 않는 이유:
--   조회 패턴이 "이 세션의 last_active_at" 단건뿐이다 — 타임아웃 스케줄러는
--   findByStatus(IN_PROGRESS) 로 후보를 먼저 좁힌 뒤 애플리케이션에서 판정하고,
--   재부착도 세션 id 로 단건 조회한다. 이 컬럼으로 범위 검색을 하는 쿼리가 없다.
--   쓰기는 rep 배치마다 일어나므로(JdbcTemplate 직접 UPDATE) 인덱스를 달면 그 경로에
--   유지비용만 붙는다. 스케줄러가 훑는 대상이 커지면 그때 재검토.
