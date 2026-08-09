-- 백그라운드 writer — DDL 이 도는 동안 「쓰기가 되는가」를 재는 장치
-- 설계: docs/decisions/online-ddl-vs-blocking-alter.md §3-2
--
-- ─────────────────────────────────────────────────────────────────────────
-- 왜 셸 루프가 아니라 스토어드 프로시저인가
--
-- 이 장치가 재는 값은 ms 단위다. `docker exec ... mysql -e "INSERT ..."` 를 루프로
-- 돌리면 매 회 컨테이너 exec + 클라이언트 접속 + TCP 왕복이 붙는데, 그 바닥이
-- 수십 ms 다. projection 실험에서 SSH+docker exec 왕복 ~50ms 가 sub-100ms 차이를
-- 통째로 삼켜 「2배」로 잘못 나온 전례가 있다(realmysql §4-②(b)).
--
-- 여기선 그 오염이 더 치명적이다. 팔 B 의 산출물이 «컷오버 순간의 짧은 정지» 인데,
-- 그게 클라이언트 왕복 잡음과 같은 자릿수면 **정지를 봤는지 잡음을 봤는지 구분이 안 된다.**
-- 루프를 DB 안에 두면 그 바닥이 사라진다.
--
-- (부수 효과: 「Stored Procedure 작성」이 실사용 근거로 남는다. 다만 그건 결과지
--  이유가 아니다 — 이유는 위의 측정 오염이다.)
-- ─────────────────────────────────────────────────────────────────────────

DROP TABLE IF EXISTS ddl_writer_log;
CREATE TABLE ddl_writer_log (
  seq        INT UNSIGNED NOT NULL AUTO_INCREMENT,
  arm        VARCHAR(16)  NOT NULL,
  started_at DATETIME(3)  NOT NULL,   -- 시도 «시작» 시각. 정지 구간은 이 값들의 간격으로도 보인다
  elapsed_ms INT          NOT NULL,   -- 이 한 건이 걸린 시간. 막히면 여기가 통째로 커진다
  errno      INT          NOT NULL DEFAULT 0,  -- 0=성공. 컷오버 순간의 에러를 잡는 자리
  PRIMARY KEY (seq)
) ENGINE=InnoDB;

DROP PROCEDURE IF EXISTS ddl_writer;

DELIMITER $$
CREATE PROCEDURE ddl_writer(IN p_arm VARCHAR(16), IN p_seconds INT, IN p_gap_ms INT)
BEGIN
  DECLARE v_deadline DATETIME(3);
  DECLARE v_t0       DATETIME(3);
  DECLARE v_errno    INT DEFAULT 0;

  -- 🔴 실패해도 루프를 멈추지 않는다. 이 장치의 목적이 «실패를 기록하는 것» 이라,
  --    에러에서 죽으면 정확히 재고 싶었던 순간에 관측이 끊긴다.
  DECLARE CONTINUE HANDLER FOR SQLEXCEPTION
  BEGIN
    GET DIAGNOSTICS CONDITION 1 v_errno = MYSQL_ERRNO;
  END;

  -- ⚠️ NOW() 가 아니라 SYSDATE() 다. NOW() 는 «문장이 시작된 시각» 으로 고정될 수 있어
  --    프로시저 안에서 시간이 안 흐르는 것처럼 보일 수 있다. SYSDATE() 는 실행 순간을 준다.
  SET v_deadline = SYSDATE(3) + INTERVAL p_seconds SECOND;

  WHILE SYSDATE(3) < v_deadline DO
    SET v_errno = 0;
    SET v_t0 = SYSDATE(3);

    -- session_id 9000001 = writer 전용 대역. 시드 세션(1~13,334)과 안 겹치게 띄워둔다.
    INSERT INTO pose_data_scale
      (session_id, timestamp_sec, joint_coordinates, sync_rate, is_correct, feedback_message, created_at)
    VALUES
      (9000001, 0.0, '{}', 75.0, 1, 'writer', SYSDATE(3));

    INSERT INTO ddl_writer_log (arm, started_at, elapsed_ms, errno)
    VALUES (p_arm, v_t0, TIMESTAMPDIFF(MICROSECOND, v_t0, SYSDATE(3)) / 1000, v_errno);

    DO SLEEP(p_gap_ms / 1000);
  END WHILE;
END$$
DELIMITER ;
