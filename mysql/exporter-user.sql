-- mysqld_exporter 전용 계정 (2026-08-09 신설)
--
-- 왜 shadowfit 앱 계정을 재사용하지 않나:
--   익스포터에 필요한 권한(PROCESS · REPLICATION CLIENT · performance_schema SELECT)은
--   서버 전역 상태를 읽는 권한이라 앱 계정이 가지면 안 된다. 반대로 앱 계정의 DML 권한은
--   익스포터가 가질 이유가 없다. 두 계정의 필요 권한이 교집합 없이 갈린다.
--
-- 적용 (로컬):
--   docker exec -i shadowfit-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" < mysql/exporter-user.sql
--
-- ⚠️ Flyway 마이그레이션이 아니다. 스키마가 아니라 인프라 계정이라 db/migration 에 두지
--    않는다 — 마이그레이션에 넣으면 «앱이 부팅하며 자기 관측 계정을 만든다» 가 되고,
--    운영 DB(RDS 등)에서는 권한이 없어 부팅이 깨진다.

CREATE USER IF NOT EXISTS 'exporter'@'%'
  IDENTIFIED BY 'exporter'
  -- 익스포터가 커넥션을 흘리면 앱이 쓸 커넥션을 잠식한다. 상한을 걸어 그 경우에도
  -- 피시험 대상이 아니라 익스포터 쪽이 먼저 실패하게 만든다.
  WITH MAX_USER_CONNECTIONS 3;

-- PROCESS            : SHOW GLOBAL STATUS / PROCESSLIST — 지표 본체
-- REPLICATION CLIENT : SHOW BINARY LOG STATUS — binlog 관련 지표
-- SELECT on p_s      : performance_schema 기반 수집기
GRANT PROCESS, REPLICATION CLIENT ON *.* TO 'exporter'@'%';
GRANT SELECT ON performance_schema.* TO 'exporter'@'%';

FLUSH PRIVILEGES;