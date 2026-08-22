-- 세션 소유권 검증용 비밀값 (#187 안 (d)).
--
-- 배경: /pose 는 공유 토큰 하나로만 인증하고 session_id 는 클라가 보낸 정수를 그대로 믿는다.
-- 그 id 가 AUTO_INCREMENT 순차값이라 추측되므로, 토큰을 가진 누구나 남의 세션에 프레임을
-- 꽂을 수 있었다. 세션마다 다른 이 비밀값이 「그 세션을 만든 클라」를 가른다.
--   docs/decisions/ai-session-ownership-verification.md  §3-(d)
--
-- 길이: 128비트를 URL-safe Base64(패딩 없음)로 담아 22자. 컬럼은 64자로 둔다 — 자릿수나
-- 인코딩을 바꿀 때 다시 마이그레이션하지 않으려는 여유이고, 이 테이블의 행 폭에서 유의미한
-- 비중이 아니다.
--
-- ⚠️ NULL 을 허용한다. 이 배포 시점에 **이미 진행 중인 세션**은 nonce 가 없다. NOT NULL 로
--    두면 그 행들에 값을 지어내야 하는데, 지어낸 값은 클라도 AI 도 모르므로 «있는데 아무도
--    못 맞히는» 상태가 된다 — 검증을 켜는 순간(2단계) 그 세션들이 전부 끊긴다.
--    1단계가 compat(빈 값이면 통과)인 이유가 이것이고, NULL 이 그 사실을 스키마에 적는다.
ALTER TABLE exercise_sessions
    ADD COLUMN session_nonce VARCHAR(64) NULL COMMENT '세션 소유권 검증용 비밀값 (#187 d). NULL = 이 기능 배포 전에 시작된 세션';
