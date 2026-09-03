-- 다중사용자 실시간 동기화(그룹/파트너, WebSocket) 데이터 모델
-- (docs/decisions/multiuser-realtime-sync.md, 2026-08-30 사용자 confirm으로 채택 확정)

-- 테이블명이 GROUP 이 아니라 workout_groups 인 이유: GROUP 은 SQL 예약어.
CREATE TABLE workout_groups (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    created_by BIGINT NOT NULL,
    -- 그룹별 이벤트 시퀀스 채번 카운터. group_events.seq 를 원자적으로 발급하는 데 쓰인다
    -- (단일 인스턴스 전제 — GroupEventService 가 이 행을 잠그고 증가시킨다).
    next_seq BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_workout_groups_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE group_members (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 같은 그룹에 같은 사용자가 중복 가입하는 것을 DB 레벨에서 막는다.
    UNIQUE KEY uk_group_members_group_member (group_id, member_id),
    CONSTRAINT fk_group_members_group FOREIGN KEY (group_id) REFERENCES workout_groups(id) ON DELETE CASCADE,
    CONSTRAINT fk_group_members_member FOREIGN KEY (member_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE group_invitations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    inviter_id BIGINT NOT NULL,
    invitee_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    responded_at DATETIME NULL,
    CONSTRAINT fk_group_invitations_group FOREIGN KEY (group_id) REFERENCES workout_groups(id) ON DELETE CASCADE,
    CONSTRAINT fk_group_invitations_inviter FOREIGN KEY (inviter_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_group_invitations_invitee FOREIGN KEY (invitee_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE group_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    seq BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    -- 시스템이 발행하는 이벤트(예: MEMBER_JOINED)는 특정 발신자가 없어 NULL 을 허용한다.
    sender_id BIGINT NULL,
    payload TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 재연결 백필·중복 판정의 유일한 근거 — 그룹 안에서 유일하고 순서를 보장해야 한다.
    UNIQUE KEY uk_group_events_group_seq (group_id, seq),
    CONSTRAINT fk_group_events_group FOREIGN KEY (group_id) REFERENCES workout_groups(id) ON DELETE CASCADE,
    CONSTRAINT fk_group_events_sender FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE SET NULL
);