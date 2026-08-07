# 데이터베이스 설계 가이드

> 이 문서는 설계 의도와 핵심 스키마를 정리합니다. **운영 중인 실제 스키마는 `mysql/schema.sql` 이 단일 진실 원천**이며, 본 문서와 다를 수 있는 부분은 마지막 "코드 동기 메모" 절에서 명시합니다.

## ERD 개요
회의록에서 정의된 DB 활용 3단계 로드맵에 따른 설계입니다.
- **1단계**: 현재 상태 기록
- **2단계**: 과거 히스토리 관리 및 추이 분석
- **3단계**: 미래 예측 (누적 데이터 기반)

## 테이블 설계

### users (사용자) — 실제 스키마 기준
```sql
CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(100) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    username VARCHAR(50) UNIQUE NOT NULL,         -- 닉네임 역할
    sex ENUM('MALE', 'FEMALE', 'NONE') DEFAULT 'NONE',
    role VARCHAR(20) DEFAULT 'ROLE_USER',         -- Spring Security 권한
    profile_image_url VARCHAR(500),
    height DECIMAL(5,1),
    weight DECIMAL(5,1),
    workout_level VARCHAR(20),
    selected_persona ENUM('BEGINNER', 'ADVANCED', 'DIET', 'REHAB') NOT NULL DEFAULT 'BEGINNER',
    preferred_url VARCHAR(500),                   -- 사용자 기본 기준 영상
    onboarding_completed BOOLEAN DEFAULT FALSE,
    tts_enabled BOOLEAN NOT NULL DEFAULT TRUE,    -- TTS 사용 여부 (2026-05 추가)
    tts_speed DECIMAL(3,1) NOT NULL DEFAULT 1.0,  -- TTS 속도 0.5~2.0 (2026-05 추가)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

> 컬럼명 차이: 회의록 초안의 `nickname` 은 코드상 `username`. 컬럼 추가 이력은 [`architecture/ai-backend-monthly-log.md`](./architecture/ai-backend-monthly-log.md) 의 04-25/05-09 절 참조.

### exercises (운동 종목 마스터)
```sql
CREATE TABLE exercises (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,                          -- 스쿼트, 데드리프트, 턱걸이
    category ENUM('LOWER', 'BACK', 'UPPER', 'CORE', 'FULL') NOT NULL,
    description TEXT,
    preferred_url VARCHAR(500),                          -- 기본 레퍼런스 영상 (코드상 컬럼명)
    target_joints JSON,                                  -- 분석 대상 관절 목록
    sync_threshold_beginner DECIMAL(5,2) DEFAULT 60.00,  -- 헬린이 기준
    sync_threshold_advanced DECIMAL(5,2) DEFAULT 85.00,  -- 헬창 기준
    expected_duration_minutes INT DEFAULT 15,            -- 세션 타임아웃 산정용 (2026-05 추가, 커밋 136f0e6)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### exercise_references (운동별 기준 좌표) — 2026-04 추가
```sql
CREATE TABLE exercise_references (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exercise_id BIGINT NOT NULL,
    timestamp_sec DECIMAL(10,3) NOT NULL,
    joint_coordinates JSON NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (exercise_id) REFERENCES exercises(id) ON DELETE CASCADE,
    INDEX idx_exercise_ref_id (exercise_id)
);
```
관리자가 유튜브 URL을 등록하면 AI 서버에서 추출한 기준 포즈 시퀀스가 이 테이블에 저장된다. 운동 세션 시작 시 Spring 이 여기서 조회해 gRPC `StartAnalysis` 의 `reference_poses` 필드로 AI에 전달. (커밋 4eb153b)

### exercise_sessions (운동 세션)
```sql
CREATE TABLE exercise_sessions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id BIGINT NOT NULL,                       -- 코드상 컬럼명 (users.id FK)
    exercise_id BIGINT NOT NULL,
    reference_source VARCHAR(500),                   -- 사용된 기준 영상 (로컬 경로 or YouTube URL)
    start_time DATETIME NOT NULL,
    end_time DATETIME,
    total_reps INT DEFAULT 0,
    avg_sync_rate DECIMAL(5,2),
    max_sync_rate DECIMAL(5,2),
    min_sync_rate DECIMAL(5,2),
    calories_burned DECIMAL(7,2),
    difficulty_level INT DEFAULT 1,
    status ENUM('IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'FAILED') DEFAULT 'IN_PROGRESS',
    version BIGINT NOT NULL DEFAULT 0,               -- JPA @Version (낙관적 락, 2026-05 추가)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (member_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (exercise_id) REFERENCES exercises(id)
);
```

**동시성**: `version` 컬럼은 AI 완료 콜백과 `SessionTimeoutScheduler` 가 동시에 같은 세션을 갱신할 때 충돌을 감지하기 위한 낙관적 락. `SessionService.completeSession` 에서 `OptimisticLockingFailureException` 발생 시 3회 재시도. (커밋 136f0e6, [`15-session-timeout-guide.md`](./15-session-timeout-guide.md) 참조)
**상태 추가**: 기존 3개 상태 외에 `FAILED` 추가 — 스케줄러가 타임아웃된 세션을 떨어뜨릴 때 사용.

### pose_data (자세 데이터 - 1초당 평균값 저장)
```sql
CREATE TABLE pose_data (
    id BIGINT AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    rep_number INT NOT NULL DEFAULT 0,    -- 이 프레임이 속한 rep (1-based, 0=미상). 재부착 시 MAX로 복원
    timestamp_sec DECIMAL(10,3) NOT NULL, -- 소수점 타임스탬프 (코드상 DECIMAL)
    joint_coordinates JSON NOT NULL,      -- 관절 좌표 평균값 (33개 포인트)
    sync_rate DECIMAL(5,2) NOT NULL,      -- 해당 rep의 싱크로율 (rep 단위 채점 후 프레임마다 복제 → rep 안에서 상수)
    smoothed_knee_angle DECIMAL(5,2) NOT NULL DEFAULT 0.00, -- 좌우 무릎각 평균의 3프레임 평활(0=미상). 작을수록 깊다
    feedback_message VARCHAR(500),        -- 실시간 피드백 메시지 (한국어, [project-korean-only])
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, created_at),         -- 파티션 키가 모든 유니크키에 포함돼야 하는 제약
    INDEX idx_session_timestamp (session_id, timestamp_sec)
) PARTITION BY RANGE (UNIX_TIMESTAMP(created_at)) (...);
```

> **이 표의 이력** — 실제 정의는 `mysql/schema.sql` 이 기준이다.
> - **FK 없음**: 파티셔닝을 위해 제거했다(2026-07-20). 참조무결성은 `PoseDataService` 의 세션 존재 검증이 담당한다 → [`decisions/pose-data-partition-fk-tradeoff.md`](./decisions/pose-data-partition-fk-tradeoff.md)
> - **`rep_number` 추가**(2026-07-31): 재부착 시 rep 카운트 복원 근거 → [`decisions/session-resume-and-ai-state.md`](./decisions/session-resume-and-ai-state.md) §3-3
> - **`smoothed_knee_angle` 추가 · `is_correct` 삭제**(2026-08-01): `sync_rate` 는 rep 안에서 상수라 프레임을 구분하지 못해 대표 프레임 선택 기준이 필요했고, `is_correct` 는 읽는 곳이 없으면서 임계값 40 을 쓰기 시점에 굳혀 AI 의 persona 임계값과 모순됐다 → [`decisions/worst-section-rep-resolution.md`](./decisions/worst-section-rep-resolution.md) §4-ㄹ

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
    member_id BIGINT NOT NULL,
    record_date DATE NOT NULL,
    weight DECIMAL(5,1),
    body_fat_percentage DECIMAL(4,1),
    muscle_mass DECIMAL(5,1),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (member_id) REFERENCES users(id),
    INDEX idx_member_date (member_id, record_date)
);
```

### exercise_feedback_templates (운동별 TTS 피드백 멘트) — 2026-05 추가
```sql
CREATE TABLE exercise_feedback_templates (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exercise_id BIGINT NOT NULL,
    feedback_type VARCHAR(30) NOT NULL,    -- KNEE_OVER, BACK_BEND, GOOD_FORM, REP_COUNT 등
    message VARCHAR(200) NOT NULL,         -- 한국어 멘트 (예: "무릎이 발끝을 넘었습니다")
    priority INT NOT NULL DEFAULT 100,     -- 낮을수록 우선
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (exercise_id) REFERENCES exercises(id) ON DELETE CASCADE,
    UNIQUE KEY uk_exercise_feedback (exercise_id, feedback_type)
);
```
세션 시작 시 클라이언트가 `GET /exercises/{exerciseId}/feedback-templates` 로 받아 device TTS 로 재생. 다국어 분리 컬럼 없음 ([`project-korean-only`](../../C:/Users/khjae/.claude/projects/E--init/memory/project_korean_only.md)).

### session_feedback_logs (세션별 TTS 발화 이벤트 로그) — 2026-05 추가
```sql
CREATE TABLE session_feedback_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    feedback_type VARCHAR(30) NOT NULL,
    sync_rate_at_trigger DECIMAL(5,2),     -- 발화 시점 싱크로율 스냅샷
    occurred_at DATETIME NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (session_id) REFERENCES exercise_sessions(id) ON DELETE CASCADE,
    INDEX idx_session_feedback (session_id, occurred_at)
);
```
운동 중 device TTS 가 실제 발화한 시점을 AI 가 gRPC `ExerciseService.ReportFeedbackBatch` (인증: `Authorization: Bearer`) 로 송신. BT-SET (분기 2.A.BT) 모델 — 세트 경계마다 mini-batch + 세션 종료 시 final batch. 매 rep 실시간 호출 금지. 멱등성: `(session_id, occurred_at, feedback_type)` uniqueKey + `INSERT IGNORE` 로 retry 안전.

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
  - ⚠️ **타입 불일치(2026-07-15 발견, 미해결)**: `mysql/schema.sql`은 `joint_coordinates`를 `JSON` 타입으로 선언하지만, JPA 엔티티 `PoseData.java`는 `@Lob @Column(columnDefinition = "TEXT")`로 매핑돼 있음. 동작상 즉시 문제가 되진 않으나(Hibernate가 문자열로 다룸) 스키마와 엔티티 선언이 서로 다름 — 엔티티를 JSON 타입으로 맞출지, 스키마를 TEXT로 통일할지는 결정 필요.
- **인덱스**: 세션별 시계열 조회를 위해 `(session_id, timestamp_sec)` 복합 인덱스 적용
- **인코딩**: 전체 charset `utf8mb4` 강제 (한국어 피드백 메시지 깨짐 방지, 커밋 0fe056e)

---

## 인덱스 현황 (2026-08-07)

### `exercise_sessions` — 보조 인덱스 4 + FK 1

| 인덱스 | 컬럼 | 겨냥한 쿼리 | 삽입 성격 |
|---|---|---|---|
| `idx_session_member_starttime` | `(member_id, start_time)` | 주간 리포트·캘린더 | 🔴 무작위 |
| `idx_session_member_status` | `(member_id, status)` | 활성 세션 확인, 관리자 검색 경로 | 🔴 무작위 |
| `idx_session_member_exercise_status_start` | `(member_id, exercise_id, status, start_time)` | 직전 동일 운동 비교 | 🔴 무작위 |
| `idx_session_status_starttime` | `(status, start_time)` | 관리자 세션 목록 기본 진입 | 🔶 상태 블록 내 순차 |
| *(FK)* `exercise_id` | `(exercise_id)` | FK 제약 | — |

**"삽입 성격" 열의 뜻** — 세션은 시간순으로 도착하는데(`SessionService:121` 이 `startTime(now())`),
인덱스가 **무엇으로 정렬돼 있느냐**에 따라 그게 append 로 보이기도 하고 무작위로 보이기도 한다.
`member_id` 선두는 "누가 언제 운동할지 모른다"라 무작위다. **보조 인덱스 4개 중 3개가 그렇다.**

> 🔶 **[#110](https://github.com/Shadowfit/init/issues/110) — 그 셋이 겹치는지 미검증.**
> 2026-08-07 실측에서 양방향 증거가 하나씩 나왔다: 쓰기 부담의 지배 요인이 그 셋이라는 것(무작위
> 3종 16.6s vs 1종 12.3s)과, `idx_session_member_status` 는 관리자 검색 경로에서 **실제로 쓰인다**는 것.
> 질문은 "지울까"가 아니라 **"어떻게 합칠까"** 로 남아 있다.
>
> 🔶 **6번째 후보 `(start_time, member_id)`** — 대시보드 집계 e 를 355ms → 13.6ms(26배)로 줄이고,
> `start_time` 이 `now()` 라 **append** 이므로 쓰기 대가가 실측 **1.007배**다. 다만 #110 이 선결이라 미결.
> 근거: [`decisions/admin-page-scope.md`](./decisions/admin-page-scope.md) §4-5-1.

### `users`

| 인덱스 | 컬럼 | 겨냥한 쿼리 |
|---|---|---|
| `idx_users_created_at` | `(created_at)` | 관리자 회원 목록 — 가입일 범위 + 최신순 정렬 |

**이 인덱스가 실제로 한 일**은 예측과 절반만 맞았다 — 필터 조합 6가지 **전부에서 `filesort` 를
없앴지만**(정렬용), 스캔을 줄인 것은 가입일이 걸린 경우뿐이다(탐색용).
[`decisions/admin-page-scope.md`](./decisions/admin-page-scope.md) §4-3 ①.

---

## 코드 동기 메모 (회의록 초안 ↔ 실제 schema.sql 차이)

| 회의록 초안 | 실제 코드 | 사유 |
|------------|---------|------|
| `users.nickname` | `users.username` | 코드 작성 시 명명 |
| `users.persona` | `users.selected_persona` | Spring 엔티티 필드명과 정렬 |
| `exercises.reference_video_url` | `exercises.preferred_url` | 코드 작성 시 명명 |
| `exercise_sessions.user_id` | `exercise_sessions.member_id` | Member 엔티티 명칭 |
| `body_records.user_id` | `body_records.member_id` | 위와 동일 |
| `pose_data.timestamp_sec INT` | `DECIMAL(10,3)` | 소수점 타임스탬프 대응 |
| `status` 3개 enum | 4개 (`FAILED` 추가) | 스케줄러 타임아웃 처리 |
| (없음) | `users.tts_enabled`, `tts_speed` | 2026-05 TTS 설정 |
| (없음) | `exercises.expected_duration_minutes` | 2026-05 타임아웃 산정 |
| (없음) | `exercise_sessions.version` | 2026-05 낙관적 락 |
| (없음) | `exercise_references` 테이블 | 2026-04 기준 좌표 저장 |
| (없음) | `exercise_feedback_templates`, `session_feedback_logs` | 2026-05 TTS 피드백 |

스키마 변경 이력은 [`architecture/ai-backend-monthly-log.md`](./architecture/ai-backend-monthly-log.md) 와 함께 참조.
