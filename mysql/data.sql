-- 1. 기존 테이블이 있다면 삭제 (초기화 보장)
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS reports;
DROP TABLE IF EXISTS exercise_sessions;
DROP TABLE IF EXISTS exercises;
DROP TABLE IF EXISTS users;
SET FOREIGN_KEY_CHECKS = 1;


-- 2. 전체 테이블 생성 (AUTO_INCREMENT를 PRIMARY KEY 옆에 바로 붙여야 합니다)
CREATE TABLE users (
                       id BIGINT AUTO_INCREMENT PRIMARY KEY, -- 자동 증가 추가
                       email VARCHAR(255) UNIQUE NOT NULL,
                       password VARCHAR(255) NOT NULL,
                       username VARCHAR(255),
                       role VARCHAR(20) DEFAULT 'ROLE_USER',
                       selected_persona VARCHAR(50),
                       onboarding_completed BOOLEAN DEFAULT FALSE,
                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE exercises (
                           id BIGINT AUTO_INCREMENT PRIMARY KEY, -- 자동 증가 추가
                           name VARCHAR(255) NOT NULL,
                           category VARCHAR(50),
                           created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE exercise_sessions (
                                   id BIGINT AUTO_INCREMENT PRIMARY KEY, -- 자동 증가 추가
                                   member_id BIGINT,
                                   exercise_id BIGINT,
                                   start_time TIMESTAMP,
                                   end_time TIMESTAMP,
                                   avg_sync_rate DOUBLE,
                                   total_reps INT,
                                   calories_burned INT,
                                   status VARCHAR(20),
                                   created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                   FOREIGN KEY (member_id) REFERENCES users(id),
                                   FOREIGN KEY (exercise_id) REFERENCES exercises(id)
);

CREATE TABLE reports (
                         id BIGINT AUTO_INCREMENT PRIMARY KEY, -- 자동 증가 추가
                         session_id BIGINT,
                         member_id BIGINT,
                         report_type VARCHAR(20),
                         summary TEXT,
                         improvement_tips TEXT,
                         created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                         FOREIGN KEY (session_id) REFERENCES exercise_sessions(id),
                         FOREIGN KEY (member_id) REFERENCES users(id)
);

-- 3. 데이터 삽입 시작
SET FOREIGN_KEY_CHECKS = 0;

-- 1. 유저 데이터 (ID 1번 확실히 생성)
INSERT INTO users (email, password, username, role, onboarding_completed)
VALUES ('test@test.com', '$2a$10$.mpvpjYHKGukSTvbCukWNusFWU/lHUBCmHjp3Un2mz6qjrOg9z/LC', '효재', 'USER', TRUE);

-- 2. 운동 종목 데이터 (REPLACE 사용으로 에러 방지)
REPLACE INTO exercises (id, name, category, created_at) VALUES (1, '스쿼트', 'LOWER', NOW());
REPLACE INTO exercises (id, name, category, created_at) VALUES (2, '런지', 'LOWER', NOW());
REPLACE INTO exercises (id, name, category, created_at) VALUES (3, '플랭크', 'CORE', NOW());

-- 3. 운동 세션 데이터 (4월 데이터)
REPLACE INTO exercise_sessions (id, member_id, exercise_id, start_time, end_time, avg_sync_rate, total_reps, calories_burned, status, created_at) VALUES
(601, 1, 1, '2026-04-01 09:00:00', '2026-04-01 09:30:00', 75.5, 30, 150, 'COMPLETED', NOW()),
(602, 1, 2, '2026-04-03 18:00:00', '2026-04-03 18:40:00', 82.0, 40, 210, 'COMPLETED', NOW()),
(603, 1, 1, '2026-04-05 10:00:00', '2026-04-05 10:20:00', 88.5, 20, 100, 'COMPLETED', NOW()),
(617, 1, 1, '2026-04-25 09:00:00', '2026-04-25 09:20:00', 92.5, 20, 100, 'COMPLETED', NOW()),
(618, 1, 2, '2026-04-25 14:00:00', '2026-04-25 14:40:00', 88.0, 40, 190, 'COMPLETED', NOW()),
(619, 1, 3, '2026-04-25 20:00:00', '2026-04-25 20:30:00', 95.0, 30, 140, 'COMPLETED', NOW());

-- 4. 리포트 데이터
REPLACE INTO reports (id, session_id, member_id, report_type, summary, improvement_tips, created_at) VALUES
(701, 601, 1, 'SESSION', '601번 리포트', '안정적입니다.', NOW()),
(702, 602, 1, 'SESSION', '602번 리포트', '안정적입니다.', NOW()),
(703, 603, 1, 'SESSION', '603번 리포트', '안정적입니다.', NOW()),
(717, 617, 1, 'SESSION', '617번 리포트', '안정적입니다.', NOW()),
(718, 618, 1, 'SESSION', '618번 리포트', '안정적입니다.', NOW()),
(719, 619, 1, 'SESSION', '719번 리포트', '안정적입니다.', NOW());

SET FOREIGN_KEY_CHECKS = 1;