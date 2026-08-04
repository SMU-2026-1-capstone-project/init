-- 2026-08-03 — 관리자 목록용 인덱스 2종 (PR #104, admin-page-scope.md §4 ㄱ안)
--
-- 왜 필요한가:
--   기존 세션 인덱스가 전부 member_id 선두라, member_id 조건 없이 상태·기간으로 훑는 관리자
--   목록에는 하나도 타지 않는다. users 는 보조 인덱스가 아예 없어 정렬이 통째로 filesort 였다.
--   지금까지 모든 조회가 "내 데이터"였으므로 그건 올바른 설계였고, 같은 테이블에 읽기 주체가
--   둘이 되면서 갈린 것이다.
--   → docs/decisions/admin-page-scope.md §4-1 (실측 근거)
--
-- 왜 이 파일이 필요한가:
--   schema.sql 은 CREATE TABLE IF NOT EXISTS 라 이미 만들어진 DB 에는 신규 인덱스가 반영되지
--   않는다. Flyway/Liquibase 가 없어 신규 설치는 schema.sql 이, 기존 인스턴스는 이 디렉터리가
--   담당한다.
--
-- 적용 대상: PR #104 이전에 생성된 shadowfit DB
-- 멱등성: MySQL 은 ADD INDEX IF NOT EXISTS 를 지원하지 않으므로 재실행 시 1061 (Duplicate key
--         name) 이 나면 이미 적용된 것이다.
--
-- ⚠️ 소요 시간: exercise_sessions 는 부하 테스트로 행이 쌓여 있을 수 있고, 인덱스 생성은
--    전체를 정렬해 B+tree 를 만드는 작업이라 행 수에 비례한다. 운영 중 적용은 피할 것.
--
-- ⚠️ 쓰기 비용: 이 인덱스는 공짜가 아니다. 세션 INSERT 는 이 프로젝트의 핵심 쓰기 축이고,
--    실측에서 느려지는 방향이 일관되게 확인됐다(배수는 로컬 환경 한계로 미확정 —
--    admin-page-scope.md §4-1). 되돌리려면 아래 롤백 구문을 쓴다.

USE shadowfit;

-- (status, start_time) 순서인 이유: status 는 등치, start_time 은 범위이자 정렬이다.
-- 등치를 선두에 둬야 매칭 행이 한 덩어리로 뭉치고 그 안에서 start_time 이 정렬돼, 범위 탐색과
-- ORDER BY 가 둘 다 인덱스 순서를 그대로 쓴다(EXPLAIN 에서 filesort 소멸 + Backward index scan).
-- 뒤집으면 날짜마다 status 가 섞여 범위에 걸린 것을 전부 읽고 걸러야 한다.
--
-- exercise_id 를 넣지 않은 이유: analysis_supported 가 스쿼트에만 TRUE 라(data.sql) 세션이
-- 사실상 한 종목에 몰려 선택도가 없고, 넣으면 종목 미지정 시 중간에 구멍이 나서 start_time 이
-- 범위 탐색에 못 쓰인다 — 거의 안 거르면서 기본 진입 쿼리를 망친다.
ALTER TABLE exercise_sessions
    ADD INDEX idx_session_status_starttime (status, start_time);

-- users 최초의 보조 인덱스. 관리자 회원 목록의 기본 정렬이 가입일 최신순이고 기간이 유일한
-- 범위 조건이라 created_at 단일로 잡는다. 다른 필터(페르소나·레벨·온보딩)는 enum/boolean 이라
-- 선택도가 낮아 선두로 세울 값이 없고, 검색어는 부분일치라 인덱스를 못 탄다.
ALTER TABLE users
    ADD INDEX idx_users_created_at (created_at);

-- 롤백:
--   ALTER TABLE exercise_sessions DROP INDEX idx_session_status_starttime;
--   ALTER TABLE users DROP INDEX idx_users_created_at;
