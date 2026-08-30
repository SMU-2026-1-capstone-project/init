-- 운동 목표 테이블 (BE-06, goal-domain-design.md).
--
-- current_value·period_start·period_end·status 컬럼이 없다 — rolling window(최근 7일) +
-- 조회 시점 직접 계산으로 확정(§4 (c), §5, 2026-08-30 사용자 confirm). 그래서 이 테이블이
-- 아는 건 "누가 무엇을 얼마나 목표하는가"뿐이고, "지금 얼마나 했는지"는 GoalService가
-- exercise_sessions를 그때그때 읽어서 계산한다.
--
-- ⚠️ 번호(V11)는 이 브랜치가 갈라진 시점(origin/main, V10까지)을 기준으로 잡았다. 다른
-- 병렬 브랜치(그룹/트레이너 기능 등)도 각자 origin/main 기준으로 V11을 썼을 수 있다 —
-- 머지 순서에 따라 나중에 머지되는 쪽이 번호를 재조정해야 한다(이 문서 §6에서 이미 예견한
-- 상황).

CREATE TABLE goals (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id BIGINT NOT NULL,
    goal_type ENUM('WEEKLY_SESSIONS', 'WEEKLY_MINUTES') NOT NULL,
    target_value INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- 회원당 goalType 하나(GoalRepository.existsByMemberIdAndGoalType과 짝) — 동시에 같은
    -- 종류의 목표 2개를 만드는 레이스는 애플리케이션 체크만으로는 못 막으므로, 유니크
    -- 제약이 최종 방어선이다.
    UNIQUE KEY uk_goals_member_type (member_id, goal_type),
    CONSTRAINT fk_goals_member FOREIGN KEY (member_id) REFERENCES users(id) ON DELETE CASCADE
);
