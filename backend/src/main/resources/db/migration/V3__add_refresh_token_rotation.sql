-- refresh token 회전 (이슈 #135 · #136, decisions/token-lifecycle.md §4-1)
--
-- 회전 자체는 컬럼이 필요 없다 — token 을 덮어쓰면 끝이고, 폐기 판정도 «서명은 유효한데
-- row.token 과 다르다» 하나로 성립한다(서명 위조가 불가능하므로 그런 토큰은 정의상
-- 우리가 발급했던 구본이다). 아래 두 컬럼은 **탐지가 아니라 유예(grace)** 를 위한 것이다.
--
-- 왜 유예가 필요한가:
--   재발급 응답이 유실되면(모바일 네트워크) 클라는 새 토큰을 못 받은 채 구본으로 재시도한다.
--   유예가 없으면 서버가 그걸 «탈취» 로 읽어 **공격이 없는데 강제 로그아웃**시킨다.
--
--   token_version  직전 세대인지 판정한다. ver == row.token_version - 1 이면 «바로 앞 것».
--                  previous_token 컬럼을 두는 방법도 있었으나, 그건 평문 자격증명을 하나 더
--                  저장하게 된다(§1-1-ㄱ). 세대 번호는 토큰 원문이 아니다.
--   rotated_at     그 유예를 언제까지 인정할지. 창을 안 두면 «직전 토큰» 이 영원히 유효해져
--                  탈취자가 구본으로 현재 토큰을 받아갈 수 있다 — 탐지를 넣은 의미가 사라진다.
--
-- ⚠️ 유예 길이는 코드 상수이고 근거는 frontend/services/api.ts:35 의 axios timeout(10초)이다.
--    «클라가 포기했지만 서버는 이미 처리했을 수 있는» 구간이 그만큼이다. 그 이상은 근거가 없다.
--    ⚠️ 현재 프론트에는 재시도 로직이 **아예 없다.** 재발급 흐름을 붙일 때 재시도 간격이
--       정해지면 이 값을 다시 유도해야 한다.
--
-- DEFAULT 0 / NULL 인 이유 — 배포가 기존 세션을 끊지 않게 한다:
--   V3 이전에 발급된 refresh JWT 에는 ver claim 이 없고 코드가 그걸 0 으로 읽는다.
--   기존 행도 0 이므로 정상 경로(token 일치)로 통과한다. rotated_at 이 NULL 이면 유예는
--   «없음» 으로 판정되는데, 회전한 적이 없으니 맞는 값이다.
ALTER TABLE refresh_token
    ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN rotated_at    DATETIME NULL;
