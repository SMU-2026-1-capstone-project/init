-- 2026-07-31 — pose_data.rep_number 추가 (이슈 #59 2단계, 세션 재부착)
--
-- 왜 이 컬럼이 필요한가:
--   AI 프로세스가 재시작하면 rep 카운트(in-memory)가 증발한다. 재부착 시 이어서 세려면
--   "지금까지 몇 rep 했나"를 Spring 이 알아야 하는데, 그 근거가 될 rep 경계가 DB 에 없었다.
--   한 rep 의 프레임이 같은 sync_rate 를 공유한다는 점을 이용해 "연속구간 = 1 rep" 으로 셀 수는
--   있으나, 인접 두 rep 의 sync_rate 가 우연히 같으면(DECIMAL(5,2)) 병합돼 undercount 된다.
--   → docs/decisions/session-resume-and-ai-state.md §3-3
--
-- 왜 이 파일이 필요한가:
--   schema.sql 은 CREATE TABLE IF NOT EXISTS 라 이미 만들어진 DB 에는 신규 컬럼이 반영되지 않는다.
--   Flyway/Liquibase 가 없어 신규 설치는 schema.sql 이, 기존 인스턴스는 이 디렉터리가 담당한다.
--
-- 적용 대상: 이 커밋 이전에 생성된 shadowfit DB (로컬 docker volume, EC2 등)
-- 멱등성: MySQL 은 ADD COLUMN IF NOT EXISTS 를 지원하지 않으므로 재실행 시 1060 (Duplicate column)
--         이 나면 이미 적용된 것이다.
--
-- ⚠️ 소요 시간: pose_data 는 파티션 테이블이고 부하 테스트로 행이 많이 쌓여 있을 수 있다.
--    ALTER TABLE 이 전 파티션을 다시 쓰므로 행 수에 비례해 오래 걸린다. 운영 중 적용은 피할 것.

USE shadowfit;

-- DEFAULT 0 인 이유: 이 컬럼이 생기기 전에 저장된 행은 rep 번호를 알 수 없다. NULL 대신 0 을 써서
-- "미상"을 명시적인 값 하나로 고정한다 — MAX(rep_number) 가 NULL 을 만나 분기하지 않게 하려는 것이다.
-- 그래서 재부착 시 과거 세션의 rep 카운트는 0 으로 복원된다(이 컬럼 도입 이전 데이터에 한함).
ALTER TABLE pose_data
    ADD COLUMN rep_number INT NOT NULL DEFAULT 0
    AFTER session_id;

-- 인덱스를 따로 만들지 않는 이유:
--   조회 패턴은 SELECT MAX(rep_number) WHERE session_id = ? 하나뿐이고, 기존
--   idx_session_timestamp (session_id, timestamp_sec) 로 session_id 범위가 이미 좁혀진다.
--   세션 하나의 pose_data 는 다운샘플(R=5) 후 수백 행 규모라 그 안의 스캔 비용은 무시할 만하다.
--   쓰기 경로(배치 INSERT)가 이 프로젝트의 병목이었으므로 인덱스를 늘리지 않는 쪽을 택했다
--   — 실제로 중복 인덱스가 역효과였던 선례가 있다(outbox, 커밋 d3a0551).
