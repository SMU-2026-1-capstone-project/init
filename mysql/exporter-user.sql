-- mysqld_exporter 전용 계정 (2026-08-09 신설)
--
-- 왜 shadowfit 앱 계정을 재사용하지 않나:
--   익스포터에 필요한 권한(PROCESS · REPLICATION CLIENT · performance_schema SELECT)은
--   서버 전역 상태를 읽는 권한이라 앱 계정이 가지면 안 된다. 반대로 앱 계정의 DML 권한은
--   익스포터가 가질 이유가 없다. 두 계정의 필요 권한이 교집합 없이 갈린다.
--
-- 🔴 이 파일은 **템플릿**이다. 직접 실행하지 말 것 — __EXPORTER_PASSWORD__ 가 그대로 들어간다.
--
-- 적용 (로컬):
--   set -a; . ./.env; set +a
--   ./mysql/apply-exporter-user.sh
--
-- 래퍼가 __EXPORTER_PASSWORD__ 를 .env 의 MYSQL_EXPORTER_PASSWORD 로 치환한다. 전에는 이
-- 파일이 비밀번호를 하드코딩하고 docker-compose.yml 이 같은 값을 따로 정해서, 환경변수를
-- 실제로 넣는 순간 둘이 어긋났다 (#167). .sql 은 셸 확장이 안 걸려 파일 안에서는 못 고친다.
--
-- ⚠️ Flyway 마이그레이션이 아니다. 스키마가 아니라 인프라 계정이라 db/migration 에 두지
--    않는다 — 마이그레이션에 넣으면 «앱이 부팅하며 자기 관측 계정을 만든다» 가 되고,
--    운영 DB(RDS 등)에서는 권한이 없어 부팅이 깨진다.

CREATE USER IF NOT EXISTS 'exporter'@'%'
  IDENTIFIED BY '__EXPORTER_PASSWORD__'
  -- 익스포터가 커넥션을 흘리면 앱이 쓸 커넥션을 잠식한다. 상한을 걸어 그 경우에도
  -- 피시험 대상이 아니라 익스포터 쪽이 먼저 실패하게 만든다.
  WITH MAX_USER_CONNECTIONS 3;

-- CREATE USER IF NOT EXISTS 는 계정이 이미 있으면 통째로 no-op 이다 — 비밀번호만 바꿔 다시
-- 돌려도 기존 계정은 옛 값을 유지한다. 그러면 «스크립트는 고쳤는데 여전히 안 붙는다» 가 되고,
-- 원인이 계정 쪽인지 익스포터 쪽인지 구분이 안 된다. ALTER 를 같이 둬서 재실행이 항상
-- 현재 MYSQL_EXPORTER_PASSWORD 로 수렴하게 만든다 (#167 ①).
ALTER USER 'exporter'@'%'
  IDENTIFIED BY '__EXPORTER_PASSWORD__'
  WITH MAX_USER_CONNECTIONS 3;

-- PROCESS            : SHOW GLOBAL STATUS / PROCESSLIST — 지표 본체
-- REPLICATION CLIENT : SHOW BINARY LOG STATUS — binlog 관련 지표
-- SELECT on p_s      : performance_schema 기반 수집기
GRANT PROCESS, REPLICATION CLIENT ON *.* TO 'exporter'@'%';
GRANT SELECT ON performance_schema.* TO 'exporter'@'%';

FLUSH PRIVILEGES;