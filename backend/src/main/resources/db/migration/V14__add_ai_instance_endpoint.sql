-- AI 인스턴스 수평확장 준비 — 세션 ↔ 인스턴스 매핑을 담을 자리 (docs/decisions/ai-sticky-routing.md §5-2 ㉯)
--
-- 🔴 이건 준비다. AI 는 여전히 1 인스턴스이고, 이 컬럼을 읽거나 쓰는 코드는 아직 없다.
-- §8 의 ㉠(착수 여부)·㉮(누가 프론트에 알려주나)·㉰(장애 재배치)는 여전히 미결정이다.
--
-- 왜 지금 컬럼만 먼저 두는가: 나중에 실제로 착수할 때 이 마이그레이션 자체는 더 이상
-- 필요 없게 만들려는 것이다. §5-2 가 이미 이 위치(세션 테이블 nullable 컬럼)를 추천했고,
-- 그 이유(해시는 롤링 배포마다 깨진다·Redis는 이 읽기 빈도엔 과하다)는 착수 시점이 언제든
-- 안 바뀐다 — 그래서 위치만 먼저 확정해도 손해가 없다.
--
-- NULL 허용: 지금 저장되는 모든 세션은 이 값이 없다(단일 인스턴스라 매핑이 필요 없다).
-- 착수 후에도 «그 세션이 시작될 때 인스턴스가 하나뿐이었다» 를 구분할 수 있어야 하므로,
-- V8(session_nonce)과 같은 이유로 NOT NULL 로 채우지 않는다.
ALTER TABLE exercise_sessions
    ADD COLUMN ai_instance_endpoint VARCHAR(128) NULL COMMENT 'AI 인스턴스 스티키 라우팅 준비 (docs/decisions/ai-sticky-routing.md §5-2). NULL = 단일 인스턴스 또는 미착수';
