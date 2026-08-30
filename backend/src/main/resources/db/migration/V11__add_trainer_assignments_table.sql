-- 트레이너-사용자 배정 테이블 (trainer-live-monitoring.md §8 세션1).
--
-- role 컬럼 자체(V1__baseline.sql:30, VARCHAR(20))는 이미 있어 TRAINER 값 추가에
-- 마이그레이션이 필요 없다 — UserRole enum(자바)에 TRAINER 를 추가하는 것으로 끝난다.
-- 이 파일은 그 role 을 실제로 "누구를 담당하는가"에 연결하는 배정 관계만 새로 만든다.

CREATE TABLE trainer_assignments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trainer_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 사용자당 담당 트레이너는 한 명 (trainer-live-monitoring.md §1, 관계 형태 1:1).
    -- 트레이너 한 명이 여러 사용자를 담당하는 것은 허용되므로 trainer_id 에는 유니크를 안 건다.
    UNIQUE KEY uk_trainer_assignments_user (user_id),
    CONSTRAINT fk_trainer_assignments_trainer FOREIGN KEY (trainer_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_trainer_assignments_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
