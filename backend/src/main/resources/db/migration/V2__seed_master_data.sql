-- ============================================================================
-- V2 — 마스터 데이터 시드 (이슈 #115)
-- ============================================================================
--
-- 예전 `mysql/data.sql` 에서 **운영에도 있어야 하는 것만** 갈라낸 것이다.
-- 나머지(테스트 계정·가짜 세션·리포트)는 dev 픽스처라 `mysql/dev-seed.sql` 로 뺐다.
--
-- 가르는 기준: "운영 서버에 이게 없으면 서비스가 안 도는가."
--   exercises 가 없으면      → 운동 시작 자체가 불가        → 여기 (마스터)
--   test@test.com 이 없으면  → 아무 문제 없음. 오히려 있으면 안 됨 → dev-seed.sql
--
-- ⚠️ 원본에 있던 TRUNCATE 11건을 뺐다. 그건 dev 재실행을 위한 리셋이었는데,
--    Flyway 마이그레이션은 "언젠가 한 번" 도는 것이라 데이터가 있는 DB 에 걸리면
--    그대로 파괴적이다. 리셋이 필요하면 dev-seed.sql 을 쓴다.
--
-- ⚠️ 이 파일을 고치지 말 것 (checksum 감시). 종목·멘트를 바꾸려면 새 버전을 추가한다.
-- ============================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 1. 운동 종목
-- ---------------------------------------------------------------------------
-- analysis_supported: AI 분석기가 실제로 붙어 있는 종목만 TRUE. 현재는 스쿼트뿐이며
-- 런지·플랭크는 행만 있고 분석 불가라 FALSE(기본값) — 세션 생성이 W007로 차단된다.
REPLACE INTO exercises (id, name, category, preferred_url, analysis_supported, created_at)
VALUES (1, '스쿼트', 'LOWER', 'https://www.youtube.com/watch?v=q6hBSSis_60', TRUE, NOW());

REPLACE INTO exercises (id, name, category, preferred_url, created_at)
VALUES (2, '런지', 'LOWER', 'https://www.youtube.com/watch?v=U4s4mEQ5ovM', NOW());

REPLACE INTO exercises (id, name, category, preferred_url, created_at)
VALUES (3, '플랭크', 'CORE', 'https://www.youtube.com/watch?v=ASdvN_XEl_c', NOW());

-- ---------------------------------------------------------------------------
-- 2. 피드백 템플릿 (운동별 자세 피드백 멘트)
-- ---------------------------------------------------------------------------
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
