-- ============================================================================
-- dev 픽스처 — 개발·수동검증 전용 (이슈 #115 로 data.sql 에서 분리)
-- ============================================================================
--
-- 🔴 이 파일은 Flyway 가 실행하지 않는다. 그럴 의도로 뺀 것이다.
--
-- 여기 있는 것은 전부 **가짜 데이터**다 — 실제 사용자가 만든 게 아니라 화면을 보려고
-- 지어낸 것이다. Flyway 에 넣으면 배포하는 모든 환경에 test@test.com 계정이 실제로
-- 깔리므로(비밀번호 해시까지 깃에 박힌 채) 마이그레이션 밖에 둔다.
--
-- 운영에도 필요한 마스터 데이터(exercises, 피드백 템플릿)는 여기 없다 →
--   backend/src/main/resources/db/migration/V2__seed_master_data.sql
--
-- ── 쓰는 법 ────────────────────────────────────────────────────────────────
--   docker exec -i shadowfit-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit < mysql/dev-seed.sql
--
--   ⚠️ 맨 아래 TRUNCATE 가 회원·세션·리포트를 통째로 비운다. **dev 에서만 쓸 것.**
--   ⚠️ 스키마와 마스터 시드(V1·V2)가 먼저 적용돼 있어야 한다. 앱을 한 번 띄우면
--      Flyway 가 알아서 만든다.
--
--   `.claude/skills/verify` 가 test@test.com 과 세션 601~619 를 IDOR 점검 대상으로
--   쓴다 — 그래서 버리지 않고 남겼다.
-- ============================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 0. 기존 픽스처 정리 (테이블 구조는 그대로 두고 행만 비움)
-- ---------------------------------------------------------------------------
-- exercises / exercise_feedback_templates 는 건드리지 않는다 — 마스터 데이터이고
-- Flyway(V2)가 소유한다. 여기서 지우면 Flyway 는 이미 적용됨으로 기록돼 있어
-- 다시 채워주지 않는다.
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE body_records;
TRUNCATE TABLE reports;
TRUNCATE TABLE daily_logs;
TRUNCATE TABLE pose_data;
TRUNCATE TABLE session_feedback_logs;
TRUNCATE TABLE exercise_sessions;
TRUNCATE TABLE exercise_references;
TRUNCATE TABLE refresh_token;
TRUNCATE TABLE trainer_assignments;
TRUNCATE TABLE users;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------------------------------------------------------------------
-- 1. 테스트 계정 (ID 1번 확실히 생성)
-- ---------------------------------------------------------------------------
INSERT INTO users (email, password, username, role, onboarding_completed, preferred_url)
VALUES ('test@test.com', '$2a$10$.mpvpjYHKGukSTvbCukWNusFWU/lHUBCmHjp3Un2mz6qjrOg9z/LC', '효재', 'USER', TRUE,
        'https://www.youtube.com/watch?v=q6hBSSis_60');

-- 트레이너 계정 (ID 2번) — 같은 비밀번호 해시 재사용(dev 전용 픽스처라 편의상).
-- trainer-live-monitoring.md §8 세션1의 "테스트용 배정 시드"가 이것과 아래 배정 INSERT.
INSERT INTO users (email, password, username, role, onboarding_completed)
VALUES ('trainer@test.com', '$2a$10$.mpvpjYHKGukSTvbCukWNusFWU/lHUBCmHjp3Un2mz6qjrOg9z/LC', '트레이너', 'TRAINER', TRUE);

-- 트레이너(2번)가 테스트 사용자(1번)를 담당하도록 배정.
INSERT INTO trainer_assignments (trainer_id, user_id) VALUES (2, 1);

-- ---------------------------------------------------------------------------
-- 2. 운동 세션 (4월 데이터)
-- ---------------------------------------------------------------------------
REPLACE INTO exercise_sessions (id, member_id, exercise_id, start_time, end_time, avg_sync_rate, total_reps, calories_burned, status, created_at) VALUES
(601, 1, 1, '2026-04-01 09:00:00', '2026-04-01 09:30:00', 75.5, 30, 150, 'COMPLETED', NOW()),
(602, 1, 2, '2026-04-03 18:00:00', '2026-04-03 18:40:00', 82.0, 40, 210, 'COMPLETED', NOW()),
(603, 1, 1, '2026-04-05 10:00:00', '2026-04-05 10:20:00', 88.5, 20, 100, 'COMPLETED', NOW()),
(617, 1, 1, '2026-04-25 09:00:00', '2026-04-25 09:20:00', 92.5, 20, 100, 'COMPLETED', NOW()),
(618, 1, 2, '2026-04-25 14:00:00', '2026-04-25 14:40:00', 88.0, 40, 190, 'COMPLETED', NOW()),
(619, 1, 3, '2026-04-25 20:00:00', '2026-04-25 20:30:00', 95.0, 30, 140, 'COMPLETED', NOW());

-- ---------------------------------------------------------------------------
-- 3. TTS 피드백 시연용 더미 세션 (session_id = 801)
-- ---------------------------------------------------------------------------
-- 3 세트 × 평균 10 rep = 30 rep, 결함 20건 (KNEE_OUT 8 / BACK_BENT 5 / HIP_HIGH 4 / KNEE_IN 3)
-- AI 측 분류·송신 로직 완료 전 L1 백엔드 단독 시연용. 실제 ReportFeedbackBatch gRPC 호출로 들어올 데이터와 동일 분포.
REPLACE INTO exercise_sessions
  (id, member_id, exercise_id, reference_source, start_time, end_time,
   total_reps, avg_sync_rate, max_sync_rate, min_sync_rate, calories_burned,
   difficulty_level, status, created_at, version)
VALUES
  (801, 1, 1, 'https://www.youtube.com/watch?v=q6hBSSis_60',
   '2026-05-28 10:00:00', '2026-05-28 10:03:30',
   30, 65.50, 92.00, 42.10, 145.00,
   2, 'COMPLETED', NOW(), 0);

-- ---------------------------------------------------------------------------
-- 4. 리포트
-- ---------------------------------------------------------------------------
REPLACE INTO reports (id, session_id, member_id, report_type, summary, improvement_tips, created_at) VALUES
(701, 601, 1, 'SESSION', '601번 리포트', '안정적입니다.', NOW()),
(702, 602, 1, 'SESSION', '602번 리포트', '안정적입니다.', NOW()),
(703, 603, 1, 'SESSION', '603번 리포트', '안정적입니다.', NOW()),
(717, 617, 1, 'SESSION', '617번 리포트', '안정적입니다.', NOW()),
(718, 618, 1, 'SESSION', '618번 리포트', '안정적입니다.', NOW()),
(719, 619, 1, 'SESSION', '719번 리포트', '안정적입니다.', NOW()),
(801, 801, 1, 'SESSION', '801번 리포트 (TTS 피드백 시연용)',
 '무릎 정렬(KNEE_OUT) 위주 결함 — 발끝 방향 의식 + 햄스트링 가동성 점검 권장.', NOW());

-- ---------------------------------------------------------------------------
-- 5. 결함 이벤트 20건 (세트 경계 시뮬레이션 — BT-SET 의 3 batch 결과 누적)
-- ---------------------------------------------------------------------------
-- rep_number 는 멱등키의 두 번째 컬럼이다 (#193 ②, V5 마이그레이션). 세트 경계와 무관하게
-- 세션 안에서 이어지는 번호이며(AI 는 재부착 시 pose_data 의 MAX(rep_number) 부터 이어 센다),
-- 세트 1/2/3 을 각각 1~/11~/21~ 대역에 둬 «휴식 후 이어서 센다» 를 흉내 낸다.
--
-- 🔴 아래 rep 번호는 지어낸 값이다. 이 경로는 아직 실행된 적이 없어(#193) «진짜 rep 번호» 가
--    존재하지 않는다. 같은 (session_id, feedback_type) 안에서 번호가 겹치지 않게만 배치했다.
INSERT INTO session_feedback_logs
  (session_id, rep_number, feedback_type, sync_rate_at_trigger, occurred_at, created_at) VALUES
-- 세트 1 (10:00:00 ~ 10:00:30, 7건)
(801,  1, 'KNEE_OUT',  55.20, '2026-05-28 10:00:03', NOW()),
(801,  2, 'BACK_BENT', 48.70, '2026-05-28 10:00:06', NOW()),
(801,  3, 'KNEE_OUT',  52.10, '2026-05-28 10:00:09', NOW()),
(801,  4, 'HIP_HIGH',  50.30, '2026-05-28 10:00:13', NOW()),
(801,  5, 'KNEE_IN',   47.50, '2026-05-28 10:00:17', NOW()),
(801,  6, 'KNEE_OUT',  58.40, '2026-05-28 10:00:21', NOW()),
(801,  7, 'BACK_BENT', 51.00, '2026-05-28 10:00:25', NOW()),
-- 세트 2 (10:01:30 ~ 10:02:00, 7건) — 휴식 후
(801, 11, 'KNEE_OUT',  60.10, '2026-05-28 10:01:33', NOW()),
(801, 12, 'HIP_HIGH',  54.20, '2026-05-28 10:01:36', NOW()),
(801, 13, 'BACK_BENT', 49.50, '2026-05-28 10:01:40', NOW()),
(801, 14, 'KNEE_OUT',  62.70, '2026-05-28 10:01:43', NOW()),
(801, 15, 'KNEE_IN',   45.80, '2026-05-28 10:01:47', NOW()),
(801, 16, 'HIP_HIGH',  56.00, '2026-05-28 10:01:51', NOW()),
(801, 17, 'KNEE_OUT',  59.30, '2026-05-28 10:01:55', NOW()),
-- 세트 3 (10:03:00 ~ 10:03:30, 6건) — 휴식 후, is_final=true 시뮬레이션
(801, 21, 'BACK_BENT', 53.40, '2026-05-28 10:03:03', NOW()),
(801, 22, 'KNEE_OUT',  64.20, '2026-05-28 10:03:07', NOW()),
(801, 23, 'HIP_HIGH',  51.80, '2026-05-28 10:03:11', NOW()),
(801, 24, 'KNEE_OUT',  67.50, '2026-05-28 10:03:15', NOW()),
(801, 25, 'KNEE_IN',   48.60, '2026-05-28 10:03:19', NOW()),
(801, 26, 'BACK_BENT', 55.70, '2026-05-28 10:03:23', NOW());
