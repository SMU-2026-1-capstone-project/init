-- 2026-07-31 — exercises.analysis_supported 추가 (#59 관련 종목 가드, PR #72)
--
-- 왜 이 파일이 필요한가:
--   schema.sql 은 CREATE TABLE IF NOT EXISTS 라, 이미 만들어진 DB에는 신규 컬럼이 반영되지 않는다.
--   이 프로젝트엔 Flyway/Liquibase 가 없어 신규 설치는 schema.sql 이, 기존 인스턴스는 이 디렉터리의
--   스크립트가 담당한다. 신규 설치에는 실행할 필요가 없다(schema.sql 에 이미 포함).
--
-- 적용 대상: 이 커밋 이전에 생성된 shadowfit DB (로컬 docker volume, EC2 등)
-- 멱등성: MySQL 은 ADD COLUMN IF NOT EXISTS 를 지원하지 않으므로 재실행 시 1060 (Duplicate column)
--         이 나면 이미 적용된 것이다.

USE shadowfit;

ALTER TABLE exercises
    ADD COLUMN analysis_supported BOOLEAN NOT NULL DEFAULT FALSE
    AFTER expected_duration_minutes;

-- ai-server 에 분석기가 붙어 있는 종목만 TRUE. 현재는 스쿼트(id=1)뿐이며 런지·플랭크는
-- 행만 있고 분석 불가라 FALSE 로 남긴다(세션 생성이 W007 로 차단됨).
UPDATE exercises SET analysis_supported = TRUE WHERE id = 1;

-- 주의: 이 값을 나중에 SQL 로 직접 바꿀 경우, exercises 캐시(Caffeine, expireAfterWrite=1h)에
-- 낡은 값이 남아 최대 1시간 동안 반영되지 않는다. 즉시 반영이 필요하면 애플리케이션을 재시작하거나
-- 캐시를 비울 것. 향후 이 플래그를 바꾸는 관리자 API 를 만든다면 AdminExerciseService.updateThresholds
-- 처럼 @CacheEvict(cacheNames = "exercises", key = "#exerciseId") 를 반드시 함께 걸어야 한다.
