-- 인코딩 강제 (한글 깨짐 방지). 클라이언트 charset이 latin1 이어도 utf8mb4 로 협상.
SET NAMES utf8mb4;

-- 이 파일은 순수 시드 데이터 전용 (초기 시드). 테이블 구조는 schema.sql이 전담 —
-- 여기서 CREATE TABLE을 만들지 않는다. docker-entrypoint-initdb.d는 파일을 알파벳
-- 순으로 실행하므로 docker-compose.yml에서 01-schema.sql / 02-data.sql로 순서를 강제해
-- schema.sql이 먼저 실행된 뒤 이 파일이 그 위에 시드를 얹는 구조.
-- (예전엔 이 파일이 자체 CREATE TABLE을 갖고 있어 schema.sql과 구조가 갈라졌었음 —
--  exercise_sessions.version 컬럼·pose_data 파티셔닝·reports의 JSON 컬럼 등이 누락된
--  구버전 스키마가 만들어지는 문제였음. 2026-07-22 정리.)

-- 1. 기존 시드 데이터 정리 (테이블 구조는 그대로 두고 행만 비움)
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE body_records;
TRUNCATE TABLE reports;
TRUNCATE TABLE daily_logs;
TRUNCATE TABLE pose_data;
TRUNCATE TABLE session_feedback_logs;
TRUNCATE TABLE exercise_feedback_templates;
TRUNCATE TABLE exercise_sessions;
TRUNCATE TABLE exercise_references;
TRUNCATE TABLE exercises;
TRUNCATE TABLE refresh_token;
TRUNCATE TABLE users;

-- 2. 데이터 삽입 시작

-- 1. 유저 데이터 (ID 1번 확실히 생성)
INSERT INTO users (email, password, username, role, onboarding_completed, preferred_url)
VALUES ('test@test.com', '$2a$10$.mpvpjYHKGukSTvbCukWNusFWU/lHUBCmHjp3Un2mz6qjrOg9z/LC', '효재', 'USER', TRUE,
        'https://www.youtube.com/watch?v=q6hBSSis_60');

-- 2. 운동 종목 데이터 (REPLACE 사용으로 에러 방지)
-- analysis_supported: AI 분석기가 실제로 붙어 있는 종목만 TRUE. 현재는 스쿼트뿐이며
-- 런지·플랭크는 행만 있고 분석 불가라 FALSE(기본값) — 세션 생성이 W007로 차단된다.
REPLACE INTO exercises (id, name, category, preferred_url, analysis_supported, created_at)
VALUES (1, '스쿼트', 'LOWER', 'https://www.youtube.com/watch?v=q6hBSSis_60', TRUE, NOW());

REPLACE INTO exercises (id, name, category, preferred_url, created_at)
VALUES (2, '런지', 'LOWER', 'https://www.youtube.com/watch?v=U4s4mEQ5ovM', NOW());

REPLACE INTO exercises (id, name, category, preferred_url, created_at)
VALUES (3, '플랭크', 'CORE', 'https://www.youtube.com/watch?v=ASdvN_XEl_c', NOW());

-- 3. 운동 세션 데이터 (4월 데이터)
REPLACE INTO exercise_sessions (id, member_id, exercise_id, start_time, end_time, avg_sync_rate, total_reps, calories_burned, status, created_at) VALUES
(601, 1, 1, '2026-04-01 09:00:00', '2026-04-01 09:30:00', 75.5, 30, 150, 'COMPLETED', NOW()),
(602, 1, 2, '2026-04-03 18:00:00', '2026-04-03 18:40:00', 82.0, 40, 210, 'COMPLETED', NOW()),
(603, 1, 1, '2026-04-05 10:00:00', '2026-04-05 10:20:00', 88.5, 20, 100, 'COMPLETED', NOW()),
(617, 1, 1, '2026-04-25 09:00:00', '2026-04-25 09:20:00', 92.5, 20, 100, 'COMPLETED', NOW()),
(618, 1, 2, '2026-04-25 14:00:00', '2026-04-25 14:40:00', 88.0, 40, 190, 'COMPLETED', NOW()),
(619, 1, 3, '2026-04-25 20:00:00', '2026-04-25 20:30:00', 95.0, 30, 140, 'COMPLETED', NOW());

-- 3-A. 피드백 템플릿 시드 데이터 (운동별 자세 피드백 멘트)
-- 스쿼트 (id=1) — 4 결함 × 4 페르소나 = 16 row (12-persona-difficulty.md 톤 가이드)
INSERT INTO exercise_feedback_templates (exercise_id, feedback_type, persona, message, priority) VALUES
-- KNEE_OUT (무릎이 발끝보다 나감)
(1, 'KNEE_OUT', 'BEGINNER', '무릎이 발끝을 넘었어요. 살짝 뒤로 빼면 완벽해요', 10),
(1, 'KNEE_OUT', 'ADVANCED', '무릎이 발끝 전방으로 벗어남. 발목 가동범위 조정 필요', 10),
(1, 'KNEE_OUT', 'DIET',     '무릎 정렬 교정하면 자세가 안정되어 효율이 올라가요', 10),
(1, 'KNEE_OUT', 'REHAB',    '무릎이 안전 범위를 벗어났습니다. 천천히 자세를 조정해주세요', 10),
-- KNEE_IN (무릎이 안쪽으로 모임)
(1, 'KNEE_IN',  'BEGINNER', '무릎이 안쪽으로 모였어요. 발끝 방향으로 살짝 벌려보세요', 20),
(1, 'KNEE_IN',  'ADVANCED', '무릎 내전 발생. 둔근 외전 활성화 필요', 20),
(1, 'KNEE_IN',  'DIET',     '무릎 방향만 잡으면 하체 전체에 자극이 들어가요', 20),
(1, 'KNEE_IN',  'REHAB',    '무릎이 안쪽으로 들어가고 있습니다. 무리하지 말고 교정해주세요', 20),
-- HIP_HIGH (엉덩이 과도하게 들림)
(1, 'HIP_HIGH', 'BEGINNER', '엉덩이를 더 내려보세요. 깊게 앉을수록 효과가 커요', 30),
(1, 'HIP_HIGH', 'ADVANCED', 'ROM 부족. 골반 후방 경사와 햄스트링 가동성 확인 필요', 30),
(1, 'HIP_HIGH', 'DIET',     '더 깊게 앉으면 칼로리 소모가 늘어나요', 30),
(1, 'HIP_HIGH', 'REHAB',    '안전한 범위 내에서 가능한 만큼만 내려가주세요', 30),
-- BACK_BENT (등 굽음)
(1, 'BACK_BENT','BEGINNER', '허리를 곧게 펴주세요. 가슴을 살짝 들면 도움돼요', 5),
(1, 'BACK_BENT','ADVANCED', '흉추 굴곡 발생. 코어 활성화와 견갑골 안정화 필요', 5),
(1, 'BACK_BENT','DIET',     '허리 자세 유지하면 부상 없이 운동을 지속할 수 있어요', 5),
(1, 'BACK_BENT','REHAB',    '허리가 굽으면 부상 위험이 있습니다. 즉시 자세 교정해주세요', 5);

-- 런지 (id=2) — 페르소나 row 없음, NULL fallback 으로 호환 유지
INSERT INTO exercise_feedback_templates (exercise_id, feedback_type, persona, message, priority) VALUES
(2, 'KNEE_OUT',  NULL, '앞 무릎이 발끝을 넘지 않게 해주세요', 10),
(2, 'BACK_BENT', NULL, '상체를 곧게 세워주세요', 5),
(2, 'HIP_HIGH',  NULL, '뒷무릎을 더 굽혀주세요', 20);

-- 플랭크 (id=3) — 페르소나 row 없음, NULL fallback 으로 호환 유지
INSERT INTO exercise_feedback_templates (exercise_id, feedback_type, persona, message, priority) VALUES
(3, 'HIP_HIGH',  NULL, '엉덩이를 너무 들지 마세요', 10),
(3, 'HIP_LOW',   NULL, '엉덩이가 처지지 않게 들어주세요', 10),
(3, 'HEAD_DOWN', NULL, '고개를 너무 숙이지 마세요', 30),
(3, 'BACK_BENT', NULL, '몸을 일직선으로 유지해주세요', 5);

-- 4. 리포트 데이터
REPLACE INTO reports (id, session_id, member_id, report_type, summary, improvement_tips, created_at) VALUES
(701, 601, 1, 'SESSION', '601번 리포트', '안정적입니다.', NOW()),
(702, 602, 1, 'SESSION', '602번 리포트', '안정적입니다.', NOW()),
(703, 603, 1, 'SESSION', '603번 리포트', '안정적입니다.', NOW()),
(717, 617, 1, 'SESSION', '617번 리포트', '안정적입니다.', NOW()),
(718, 618, 1, 'SESSION', '618번 리포트', '안정적입니다.', NOW()),
(719, 619, 1, 'SESSION', '719번 리포트', '안정적입니다.', NOW()),
(801, 801, 1, 'SESSION', '801번 리포트 (TTS 피드백 시연용)',
 '무릎 정렬(KNEE_OUT) 위주 결함 — 발끝 방향 의식 + 햄스트링 가동성 점검 권장.', NOW());

-- 5. TTS 피드백 시연용 더미 세션 (session_id = 801)
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

-- 5-A. 결함 이벤트 20건 (세트 경계 시뮬레이션 — BT-SET 의 3 batch 결과 누적)
INSERT INTO session_feedback_logs
  (session_id, feedback_type, sync_rate_at_trigger, occurred_at, created_at) VALUES
-- 세트 1 (10:00:00 ~ 10:00:30, 7건)
(801, 'KNEE_OUT',  55.20, '2026-05-28 10:00:03', NOW()),
(801, 'BACK_BENT', 48.70, '2026-05-28 10:00:06', NOW()),
(801, 'KNEE_OUT',  52.10, '2026-05-28 10:00:09', NOW()),
(801, 'HIP_HIGH',  50.30, '2026-05-28 10:00:13', NOW()),
(801, 'KNEE_IN',   47.50, '2026-05-28 10:00:17', NOW()),
(801, 'KNEE_OUT',  58.40, '2026-05-28 10:00:21', NOW()),
(801, 'BACK_BENT', 51.00, '2026-05-28 10:00:25', NOW()),
-- 세트 2 (10:01:30 ~ 10:02:00, 7건) — 휴식 후
(801, 'KNEE_OUT',  60.10, '2026-05-28 10:01:33', NOW()),
(801, 'HIP_HIGH',  54.20, '2026-05-28 10:01:36', NOW()),
(801, 'BACK_BENT', 49.50, '2026-05-28 10:01:40', NOW()),
(801, 'KNEE_OUT',  62.70, '2026-05-28 10:01:43', NOW()),
(801, 'KNEE_IN',   45.80, '2026-05-28 10:01:47', NOW()),
(801, 'HIP_HIGH',  56.00, '2026-05-28 10:01:51', NOW()),
(801, 'KNEE_OUT',  59.30, '2026-05-28 10:01:55', NOW()),
-- 세트 3 (10:03:00 ~ 10:03:30, 6건) — 휴식 후, is_final=true 시뮬레이션
(801, 'BACK_BENT', 53.40, '2026-05-28 10:03:03', NOW()),
(801, 'KNEE_OUT',  64.20, '2026-05-28 10:03:07', NOW()),
(801, 'HIP_HIGH',  51.80, '2026-05-28 10:03:11', NOW()),
(801, 'KNEE_OUT',  67.50, '2026-05-28 10:03:15', NOW()),
(801, 'KNEE_IN',   48.60, '2026-05-28 10:03:19', NOW()),
(801, 'BACK_BENT', 55.70, '2026-05-28 10:03:23', NOW());

SET FOREIGN_KEY_CHECKS = 1;