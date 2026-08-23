-- pose_data.created_at 을 «세션 시작 시각» 하나로 맞춘다 (#392)
--
-- 배경: created_at 은 V6(#188) 부터 «적재 시각» 이 아니라 **세션 시작 시각**이다. 재전송이
-- 값을 바꾸면 멱등 키가 통째로 무력해지기 때문이고, 애플리케이션이 명시적으로 그 값을 넣는다
-- (PoseDataService.INSERT_POSE_SQL / sessionAnchor).
--
-- 그런데 V6 이전에 적재된 행은 컬럼 DEFAULT(CURRENT_TIMESTAMP)를 받아 **적재 시각**이 들어 있다.
-- 그 행들이 남아 있는 한 읽기 쿼리에 `created_at = 세션 시작 시각` 술어를 걸 수 없다 — 걸면
-- 그 행들이 조회에서 통째로 빠진다. 이 마이그레이션이 그 전제를 만든다.
--
-- 어긋난 폭은 «초» 가 아니다. 2026-08-23 로컬 실측에서 최대 격차가 **6,271,907초(≈72.6일)** 였고
-- 어긋난 1,199행 중 **1,190행이 월 파티션 경계를 넘었다**. 그래서 «범위 술어로 덮는다» 는 길은
-- 성립하지 않는다(덮으려면 구간이 4개 파티션이 되어 프루닝 이득이 사라진다).
--
-- ⚠️ 되돌릴 수 없다. 아래 1)이 지우는 행은 복원 경로가 백업뿐이다.

-- 1) 앵커가 어긋난 세션 목록.
--
-- 세션 단위로 잡는 이유: 보정이 끝나면 한 세션의 모든 행이 created_at 값 하나를 공유하므로,
-- uk_pose_event 의 유일성이 사실상 (session_id, rep_number, timestamp_sec) 로 좁혀진다.
-- 즉 «어긋난 행끼리» 가 아니라 **그 세션의 모든 행 사이**에서 겹침을 봐야 한다.
CREATE TEMPORARY TABLE tmp_anchor_sessions (
    session_id BIGINT NOT NULL PRIMARY KEY
) ENGINE = InnoDB;

INSERT INTO tmp_anchor_sessions (session_id)
SELECT DISTINCT p.session_id
  FROM pose_data p
  JOIN exercise_sessions s ON s.id = p.session_id
 WHERE p.created_at <> s.start_time;

-- 2) 보정하면 유니크 키가 겹칠 행을 접는다 — 그룹당 최소 id 1행만 남는다.
--
-- 임시 테이블을 거치는 이유는 V6 1)과 같다: MySQL 은 DELETE 의 서브쿼리에서 대상 테이블을
-- 직접 읽지 못한다(ERROR 1093).
--
-- 🔴 여기서 사라지는 행은 **멱등 키가 있었으면 애초에 안 생겼을 중복**이다. 실측(2026-08-23):
--    세션 801 이 1,190행인데 서로 다른 (rep_number, timestamp_sec) 는 **5개**뿐이다 — 부하 rig 이
--    같은 메시지를 반복 전송한 결과이고, V6 주석이 이미 «실사용 데이터가 아니다» 로 판정했다.
--    당시 그 행들이 공존할 수 있었던 것은 created_at 이 행마다 달랐기 때문이다.
CREATE TEMPORARY TABLE tmp_anchor_keep (
    session_id    BIGINT        NOT NULL,
    rep_number    INT           NOT NULL,
    timestamp_sec DECIMAL(10,3) NOT NULL,
    keep_id       BIGINT        NOT NULL,
    PRIMARY KEY (session_id, rep_number, timestamp_sec)
) ENGINE = InnoDB;

INSERT INTO tmp_anchor_keep (session_id, rep_number, timestamp_sec, keep_id)
SELECT p.session_id, p.rep_number, p.timestamp_sec, MIN(p.id)
  FROM pose_data p
  JOIN tmp_anchor_sessions t ON t.session_id = p.session_id
 GROUP BY p.session_id, p.rep_number, p.timestamp_sec
HAVING COUNT(*) > 1;

DELETE p
  FROM pose_data p
  JOIN tmp_anchor_keep k
    ON k.session_id    = p.session_id
   AND k.rep_number    = p.rep_number
   AND k.timestamp_sec = p.timestamp_sec
 WHERE p.id > k.keep_id;

DROP TEMPORARY TABLE tmp_anchor_keep;

-- 3) 앵커 보정.
--
-- created_at 은 PK(id, created_at) 의 구성요소이자 **파티션 표현식**이라, 이 UPDATE 는 행을
-- 파티션 사이로 옮긴다. 옮겨 갈 파티션은 V1 이 이미 월별로 만들어 뒀고, 세션 시작 시각이
-- 마지막 월별 파티션보다 미래면 pfuture 가 받는다.
--
-- PK 충돌은 없다 — id 자체가 유일하므로 (id, created_at) 도 유일하다.
-- 유니크 키 충돌은 2)가 미리 없앴다.
UPDATE pose_data p
  JOIN exercise_sessions s ON s.id = p.session_id
   SET p.created_at = s.start_time
 WHERE p.created_at <> s.start_time;

DROP TEMPORARY TABLE tmp_anchor_sessions;

-- 🔴 세션이 없는 고아 행(#87)은 손대지 않는다 — 맞출 앵커가 없다. 2026-08-23 로컬 실측에서는
--    0건이었다. 그런 행이 생기면 아래 «등호 술어» 조회에서 빠지는데, 그건 이 마이그레이션이
--    만든 문제가 아니라 고아 행 자체가 이미 아무도 안 훑는 행이라는 뜻이다.
