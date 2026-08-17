-- pose_data 적재 멱등 (#188)
--
-- 배경: AI→Spring 세 콜백 중 SavePoseDataBatch 만 **재전송도 수신측 멱등도 없다.**
-- 지금 중복이 안 나는 이유는 방어가 아니라 부재다 — 재전송이 없어서 없다. 그래서 재시도를
-- 붙이는 순간 중복이 새로 생기고, 둘은 따로 고칠 수 없다.
--   docs/decisions/pose-batch-idempotency-vs-partition.md   (무엇을 할지: ㄱ+ㄴ 채택)
--   docs/decisions/pose-batch-idempotency-implementation.md (어떻게 할지: 분기 A~F)
--
-- 왜 유니크 키에 created_at 이 끼는가: MySQL 은 파티션 테이블의 모든 유니크 키가 파티션
-- 표현식의 컬럼을 포함할 것을 요구한다. PK 가 (id) 아닌 (id, created_at) 인 것도 같은 이유다.
-- 즉 이건 «빠뜨린 방어» 가 아니라 **파티셔닝을 얻은 대가**다.
--
-- 그래서 created_at 이 «적재 시각» 이면 안 된다 — 재전송은 나중에 도착하므로 값이 달라지고
-- 유니크 키가 통째로 무력해진다. 애플리케이션이 **세션 시작 시각**을 명시적으로 쓴다
-- (PoseDataService.INSERT_POSE_SQL). 한 세션의 모든 행이 값 하나를 공유하므로 세션이
-- 파티션 경계에서 쪼개지지도 않는다.
--
-- ⚠️ 컬럼 DEFAULT CURRENT_TIMESTAMP 는 **일부러 남긴다.** 애플리케이션 경로는 항상 값을 주고,
--    JPA 로 엔티티를 직접 만드는 경로(테스트 픽스처 등)는 DEFAULT 가 받아야 NOT NULL 로 안 깨진다.

-- 1) 기존 위반 행 정리 — 없으면 아무것도 하지 않는다.
--
-- ADD UNIQUE KEY 는 기존 행에 위반이 있으면 실패한다. 그리고 위반이 있다(2026-08-12 실측:
-- 3,225 행 중 900 건, 전부 세션 801). 원인은 부하 rig 이 같은 메시지를 여러 번 보낸 것이고
-- 실사용 데이터가 아니다 — 실제 프레임은 timestamp_sec 이 서로 다르다.
--
-- 지우는 것은 **중복 그룹의 잉여 사본**뿐이고 그룹당 1행(최소 id)은 반드시 남는다. 즉 어떤
-- 세션도 프레임 집합을 잃지 않는다. 잉여 사본은 애초에 정보가 아니다.
--
-- 임시 테이블을 거치는 이유: MySQL 은 DELETE 의 서브쿼리에서 대상 테이블을 직접 읽지 못한다
-- (ERROR 1093).
CREATE TEMPORARY TABLE tmp_pose_dup (
    session_id    BIGINT        NOT NULL,
    rep_number    INT           NOT NULL,
    timestamp_sec DECIMAL(10,3) NOT NULL,
    -- NOT NULL 인 것은 가정이 아니다 — created_at 은 pose_data 의 PK 구성요소라 NULL 일 수 없다.
    created_at    TIMESTAMP     NOT NULL,
    keep_id       BIGINT        NOT NULL,
    PRIMARY KEY (session_id, rep_number, timestamp_sec, created_at)
) ENGINE = InnoDB;

INSERT INTO tmp_pose_dup (session_id, rep_number, timestamp_sec, created_at, keep_id)
SELECT session_id, rep_number, timestamp_sec, created_at, MIN(id)
  FROM pose_data
 GROUP BY session_id, rep_number, timestamp_sec, created_at
HAVING COUNT(*) > 1;

DELETE p
  FROM pose_data p
  JOIN tmp_pose_dup d
    ON d.session_id    = p.session_id
   AND d.rep_number    = p.rep_number
   AND d.timestamp_sec = p.timestamp_sec
   AND d.created_at    = p.created_at
 WHERE p.id > d.keep_id;

DROP TEMPORARY TABLE tmp_pose_dup;

-- 2) 멱등 키.
--
-- ⚠️ 비용을 알고 건다: **유니크 secondary index 는 InnoDB change buffer 를 쓸 수 없다.**
--    삽입마다 인덱스 페이지를 읽어 유일성을 확인해야 하므로 쓰기 경로에 랜덤 읽기가 붙는다.
--    이 스키마·이 박스에서 그 크기는 **아직 측정된 적이 없다**(구현 문서 §3).
--    인덱스 크기는 행당 약 33바이트(8+4+5+4 + PK 12) — 실측이 아니라 컬럼 폭에서 나온 산술이다.
ALTER TABLE pose_data
    ADD UNIQUE KEY uk_pose_event (session_id, rep_number, timestamp_sec, created_at);
