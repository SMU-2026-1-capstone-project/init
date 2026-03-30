# 데이터베이스 설계 가이드

## ERD 개요
회의록에서 정의된 DB 활용 3단계 로드맵에 따른 설계입니다.
- **1단계**: 현재 상태 기록
- **2단계**: 과거 히스토리 관리 및 추이 분석
- **3단계**: 미래 예측 (누적 데이터 기반)

## 테이블 설계

### users (사용자)
```sql
CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(50) NOT NULL,
    persona ENUM('BEGINNER', 'ADVANCED', 'DIET', 'REHAB') NOT NULL DEFAULT 'BEGINNER',
    height DECIMAL(5,1),              -- 키 (cm)
    weight DECIMAL(5,1),              -- 몸무게 (kg)
    profile_image_url VARCHAR(500),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

### exercises (운동 종목 마스터)
```sql
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
```

### exercise_sessions (운동 세션)
```sql
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
```

### pose_data (자세 데이터 - 1초당 평균값 저장)
```sql
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
```

### daily_logs (달력 일지)
```sql
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
```

### reports (운동 보고서)
```sql
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
```

### body_records (신체 변화 기록 - 3단계용)
```sql
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
```

## joint_coordinates JSON 구조 예시
MediaPipe의 33개 관절 포인트에 대한 1초 평균 좌표:
```json
{
  "landmarks": [
    {"id": 0, "name": "nose", "x": 0.51, "y": 0.32, "z": -0.12, "visibility": 0.99},
    {"id": 11, "name": "left_shoulder", "x": 0.62, "y": 0.45, "z": -0.08, "visibility": 0.95},
    {"id": 12, "name": "right_shoulder", "x": 0.40, "y": 0.44, "z": -0.09, "visibility": 0.96},
    {"id": 23, "name": "left_hip", "x": 0.58, "y": 0.72, "z": 0.01, "visibility": 0.92},
    {"id": 24, "name": "right_hip", "x": 0.44, "y": 0.71, "z": 0.02, "visibility": 0.91},
    {"id": 25, "name": "left_knee", "x": 0.57, "y": 0.88, "z": 0.05, "visibility": 0.88},
    {"id": 26, "name": "right_knee", "x": 0.45, "y": 0.87, "z": 0.06, "visibility": 0.87}
  ]
}
```

## 데이터 저장 전략
- **실시간 분석 데이터**: 모든 프레임이 아닌 **1초당 평균값**만 저장 (회의록 결정사항)
- **좌표 데이터**: JSON 타입으로 유연하게 저장
- **인덱스**: 세션별 시계열 조회를 위해 `(session_id, timestamp_sec)` 복합 인덱스 적용
