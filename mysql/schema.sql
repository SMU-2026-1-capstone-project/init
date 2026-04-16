-- 1. 데이터베이스 생성 및 선택
CREATE DATABASE IF NOT EXISTS shadowfit;
USE shadowfit;

-- 2. 사용자 테이블 (회원가입 기능 보완)
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL,          -- 사용자 고유 ID (예: 이메일 또는 UUID)
    email VARCHAR(100) UNIQUE NOT NULL,       -- 이메일
    password VARCHAR(255) NOT NULL,           -- 해싱된 비밀번호
    nickname VARCHAR(50) NOT NULL,
    sex VARCHAR(10) DEFAULT 'NONE', -- 성별
    selected_persona ENUM('BEGINNER', 'ADVANCED', 'DIET', 'REHAB') NOT NULL DEFAULT 'BEGINNER', -- 피드백 페르소나
    role VARCHAR(20) DEFAULT 'ROLE_USER',     -- 스프링 시큐리티 연동용 권한
-- [온보딩 데이터] --
    profile_image_url VARCHAR(500),
    height VARCHAR(10),                       -- 키 (예: "175")
    weight VARCHAR(10),                       -- 몸무게 (예: "70")
    workout_level VARCHAR(20),                -- 운동 수준 (STARTER, BEGINNER 등)
    selected_persona VARCHAR(20) DEFAULT 'BEGINNER', -- 피드백 페르소나
    onboarding_completed BOOLEAN DEFAULT FALSE, -- 온보딩 완료 여부

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
    );

### exercises (운동 종목 마스터)

CREATE TABLE exercises (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,           -- 스쿼트, 데드리프트, 턱걸이
    category ENUM('LOWER', 'BACK', 'UPPER', 'CORE', 'FULL') NOT NULL,
    description TEXT,
    reference_video_url VARCHAR(500),     -- 기본 레퍼런스 영상
    target_joints JSON,                   -- 분석 대상 관절 목록
    sync_threshold_beginner DECIMAL(5,2) DEFAULT 60.00,   -- 헬린이 기준
    sync_threshold_advanced DECIMAL(5,2) DEFAULT 85.00,   -- 헬창 기준
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

### exercise_references (운동별 기준 자세 데이터)

CREATE TABLE IF NOT EXISTS exercise_references (
                                                   id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                                   exercise_id BIGINT NOT NULL,          -- exercises 테이블 참조
                                                   timestamp_sec DECIMAL(10,3) NOT NULL, -- 영상 내 시점
    joint_coordinates JSON NOT NULL,      -- MediaPipe 좌표 (JSON)
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (exercise_id) REFERENCES exercises(id),
    INDEX idx_exercise_ref_id (exercise_id) -- 조회 성능 향상
    );

### exercise_sessions (운동 세션)

CREATE TABLE exercise_sessions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    exercise_id BIGINT NOT NULL,
    reference_source VARCHAR(500),        -- 사용된 기준 영상 (로컬 경로 or YouTube URL)
    start_time DATETIME NOT NULL,
    end_time DATETIME,
    total_reps INT DEFAULT 0,             -- 총 반복 횟수
    avg_sync_rate DECIMAL(5,2),           -- 평균 싱크로율
    max_sync_rate DECIMAL(5,2),           -- 최고 싱크로율
    min_sync_rate DECIMAL(5,2),           -- 최저 싱크로율
    calories_burned DECIMAL(7,2),         -- 소모 칼로리
    difficulty_level INT DEFAULT 1,       -- 적응형 난이도 레벨
    status ENUM('IN_PROGRESS', 'COMPLETED', 'CANCELLED') DEFAULT 'IN_PROGRESS',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (exercise_id) REFERENCES exercises(id)
);


### pose_data (자세 데이터 - 1초당 평균값 저장)

CREATE TABLE pose_data (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    timestamp_sec INT NOT NULL,           -- 운동 시작 후 경과 초
    joint_coordinates JSON NOT NULL,      -- 관절 좌표 평균값 (33개 포인트)
    sync_rate DECIMAL(5,2) NOT NULL,      -- 해당 초의 싱크로율
    is_correct BOOLEAN DEFAULT TRUE,      -- 올바른 자세 여부
    feedback_message VARCHAR(500),        -- 실시간 피드백 메시지
    FOREIGN KEY (session_id) REFERENCES exercise_sessions(id),
    INDEX idx_session_timestamp (session_id, timestamp_sec)
);


### daily_logs (달력 일지)

CREATE TABLE daily_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    log_date DATE NOT NULL,
    memo TEXT,                            -- 사용자 메모
    total_exercise_time INT DEFAULT 0,    -- 당일 총 운동 시간 (분)
    total_calories DECIMAL(7,2) DEFAULT 0,
    mood ENUM('GREAT', 'GOOD', 'NORMAL', 'BAD', 'TERRIBLE'),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id),
    UNIQUE KEY uk_user_date (user_id, log_date)
);


### reports (운동 보고서)

CREATE TABLE reports (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    report_type ENUM('SESSION', 'WEEKLY', 'MONTHLY') DEFAULT 'SESSION',
    summary TEXT,                          -- GPT 생성 피드백 요약
    detailed_analysis JSON,               -- 상세 분석 데이터
    improvement_tips TEXT,                 -- 개선 포인트
    comparison_with_previous JSON,        -- 이전 기록 대비 변화량
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (session_id) REFERENCES exercise_sessions(id)
);


### body_records (신체 변화 기록 - 3단계용)

CREATE TABLE body_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    record_date DATE NOT NULL,
    weight DECIMAL(5,1),
    body_fat_percentage DECIMAL(4,1),
    muscle_mass DECIMAL(5,1),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_user_date (user_id, record_date)
);