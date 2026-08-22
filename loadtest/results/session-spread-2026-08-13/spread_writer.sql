-- 백그라운드 writer (세션 분산도 스윕용) — 「핫세션 부하가 **무관한 사용자**에게 번지는가」
--
-- ─────────────────────────────────────────────────────────────────────────
-- 왜 이게 필요한가 — ghz 만으로는 못 보는 것
--
-- ghz 가 재는 RPS·p99 는 **부하를 넣는 그 세션들의 것**이다. 레벨 1(모든 요청이 세션 901
-- 하나로)에서 처리량이 떨어진다 해도, 그것만으로는 두 가지를 구분할 수 없다:
--
--   ㉠ 그 한 세션만 느리다 (다른 사용자는 멀쩡)
--   ㉡ 서버 전체가 느리다   (무관한 사용자도 같이 느리다)
--
-- SLO 관점에서 이 둘은 완전히 다른 사건이다. ㉠이면 「한 사용자의 폭주가 격리된다」이고,
-- ㉡이면 「한 사용자가 서비스를 끈다」다. 4차의 `Innodb_row_lock_waits` 8,056 은 **핫세션
-- 안의 직렬화**를 보여줬지, 그 대기가 **밖으로 번지는지**는 재지 않았다.
--
-- 그래서 부하와 무관한 세션 하나에 초당 5회 쓰면서 그 지연을 따로 기록한다.
-- 이것이 이 스윕의 «번짐 반경(blast radius)» 관측 채널이다.
-- ─────────────────────────────────────────────────────────────────────────
--
-- 원본: `../online-ddl-2026-08-09/writer.sql`. 그 파일의 설계 근거(셸 루프가 아니라
-- 스토어드 프로시저인 이유 · 제어 테이블로 협조 종료하는 이유)가 그대로 승계된다.
-- 바뀐 것은 셋뿐이다:
--   ① 대상 테이블  `pose_data_scale` → **`pose_data`** (부하가 실제로 쓰는 그 테이블)
--   ② session_id   9000001 → **파라미터**. `pose_data` 는 FK 가 걸려 있어 **존재하는
--      세션이어야 한다** — 원본의 9000001 을 그대로 쓰면 전 건이 FK 오류로 실패하고,
--      이 장치는 «에러를 삼키고 계속» 도는 성질이라 **표가 조용히 비어 보인다**
--   ③ 로그·제어 테이블 이름 분리 — DDL rig 와 같은 박스에서 섞이지 않게

DROP TABLE IF EXISTS spread_writer_log;
CREATE TABLE spread_writer_log (
  seq        INT UNSIGNED NOT NULL AUTO_INCREMENT,
  arm        VARCHAR(32)  NOT NULL,   -- 판 태그(s20_r3 등). 판별로 갈라 보는 열쇠
  started_at DATETIME(3)  NOT NULL,
  elapsed_ms INT          NOT NULL,   -- 이 한 건이 걸린 시간. 번지면 여기가 커진다
  errno      INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (seq),
  KEY idx_arm (arm)
) ENGINE=InnoDB;

DROP TABLE IF EXISTS spread_writer_ctl;
CREATE TABLE spread_writer_ctl (
  id      TINYINT UNSIGNED NOT NULL PRIMARY KEY,
  conn_id BIGINT UNSIGNED  NOT NULL,
  stop    TINYINT UNSIGNED NOT NULL DEFAULT 0
) ENGINE=InnoDB;

DROP PROCEDURE IF EXISTS spread_writer;

DELIMITER $$
CREATE PROCEDURE spread_writer(IN p_arm VARCHAR(32), IN p_session BIGINT,
                               IN p_seconds INT, IN p_gap_ms INT)
BEGIN
  DECLARE v_deadline DATETIME(3);
  DECLARE v_t0       DATETIME(3);
  DECLARE v_errno    INT DEFAULT 0;
  DECLARE v_stop     TINYINT DEFAULT 0;
  -- 🔴 멱등 키를 움직이는 열 (#271). 이게 없으면 이 채널은 **자기 자신과 충돌한다**:
  --    `uk_pose_event` 는 (session_id, rep_number, timestamp_sec, created_at) 인데
  --    `created_at` 이 **초 단위**(`timestamp`, precision 0)다. writer 는 200ms 간격이라
  --    초당 5건이 같은 키가 되고, 넷은 1062 로 죽는다.
  --    2026-08-17 리허설 실측: **1062가 864건 · 정상 250건(77.6% 실패).**
  --    그런데 이 프로시저는 에러를 삼키고 계속 돌고, 실패한 삽입은 **즉시 돌아오므로**
  --    `p50=0ms` 가 찍힌다 — 즉 표가 「번지지 않는다」로 **거짓 안심**을 준다.
  DECLARE v_seq      INT DEFAULT 0;

  -- 실패해도 루프를 멈추지 않는다 — 이 장치의 목적이 «실패를 기록하는 것» 이다.
  DECLARE CONTINUE HANDLER FOR SQLEXCEPTION
  BEGIN
    GET DIAGNOSTICS CONDITION 1 v_errno = MYSQL_ERRNO;
  END;

  REPLACE INTO spread_writer_ctl (id, conn_id, stop) VALUES (1, CONNECTION_ID(), 0);
  SET v_deadline = SYSDATE(3) + INTERVAL p_seconds SECOND;

  WHILE SYSDATE(3) < v_deadline AND v_stop = 0 DO
    SET v_errno = 0;
    SET v_seq = v_seq + 1;
    SET v_t0 = SYSDATE(3);

    -- 🔴 페이로드는 `'{}'` 다. 이 행은 **부하가 아니라 관측**이라 무대 크기에 영향을
    --    주면 안 된다(백업 rig 의 writer 와 같은 규약).
    -- 🔴 컬럼은 `pose_data_scale`(실험 전용 테이블)이 아니라 **`pose_data` 실제 스키마**다.
    --    `is_correct` 는 2026-08-01 에 삭제됐고(`V1__baseline.sql:201`), 대신
    --    `rep_number`·`smoothed_knee_angle` 이 NOT NULL 로 있다. 원본 writer 를 그대로
    --    복사했으면 전 건이 실패하는데, 이 프로시저는 **에러를 삼키고 계속 도는** 성질이라
    --    「writer 는 돌았고 지연은 0건」 처럼 보인다.
    INSERT INTO pose_data
      (session_id, rep_number, timestamp_sec, joint_coordinates, sync_rate,
       smoothed_knee_angle, feedback_message, created_at)
    VALUES
      -- `rep_number` 에 판 안에서 증가하는 일련번호를 넣는다. 이 행들은 어차피 관측용이라
      -- rep 의미가 없고(payload 는 '{}'), 유일성만 있으면 된다. 판 사이에 이 세션의 행을
      -- 통째로 지우므로 판을 건너 겹치지도 않는다.
      (p_session, v_seq, 0.0, '{}', 75.0, 0.00, 'writer', SYSDATE(3));

    INSERT INTO spread_writer_log (arm, started_at, elapsed_ms, errno)
    VALUES (p_arm, v_t0, TIMESTAMPDIFF(MICROSECOND, v_t0, SYSDATE(3)) / 1000, v_errno);

    SELECT IFNULL(MAX(stop), 1) INTO v_stop FROM spread_writer_ctl WHERE id = 1;

    DO SLEEP(p_gap_ms / 1000);
  END WHILE;

  DELETE FROM spread_writer_ctl WHERE id = 1 AND conn_id = CONNECTION_ID();
END$$
DELIMITER ;
