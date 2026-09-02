-- 운동 목표 테이블 (BE-06, goal-domain-design.md).
--
-- current_value·period_start·period_end·status 컬럼이 없다 — rolling window(최근 7일) +
-- 조회 시점 직접 계산으로 확정(§4 (c), §5, 2026-08-30 사용자 confirm). 그래서 이 테이블이
-- 아는 건 "누가 무엇을 얼마나 목표하는가"뿐이고, "지금 얼마나 했는지"는 GoalService가
-- exercise_sessions를 그때그때 읽어서 계산한다.
--
-- ⚠️ 이 파일이 예견했던 그 상황이 실제로 났다 — 트레이너 모니터링(#622, 51fdc9a8)이
-- 2026-08-30 16:05에 먼저 V11을 머지했고, 이 파일(#624, dabf4b2b)은 같은 날 19:26에
-- origin/main 재확인 없이 V11로 머지돼 충돌했다(#650). 먼저 머지된 쪽
-- (V11__add_trainer_assignments_table.sql)을 그대로 두고, 이 파일을 V13으로 재조정했다
-- (2026-09-02) — V12가 아니라 V13인 이유: `Goal.java`의 기존 주석이 이미
-- "V13__add_goals_table.sql"을 짝으로 지목해뒀다(V12는 그룹 기능용으로 비워둔 것으로 보인다).

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
