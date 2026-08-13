# 데이터베이스 설계 가이드

> 이 문서는 설계 의도와 핵심 스키마를 정리합니다. **운영 중인 실제 스키마의 단일 진실 원천은 [`backend/src/main/resources/db/migration/`](../backend/src/main/resources/db/migration/)** — 초기 형태는 `V1__baseline.sql`, 이후 변경은 `V2`·`V3`… 순서로 누적됩니다. 어떤 DB 가 어디까지 적용됐는지는 그 DB 의 `flyway_schema_history` 가 답합니다(§「스키마는 이제 Flyway 가 적용한다」). 본 문서와 다를 수 있는 부분은 마지막 "코드 동기 메모" 절에서 명시합니다.
>
> 🔴 **2026-08-12 정정** — 이 줄은 `mysql/schema.sql` 을 원천으로 가리키고 있었는데, **그 파일은 2026-08-01 `f7e52d4`(#115 Flyway 도입)에서 삭제됐다.** 아래 §「스키마는 이제 Flyway 가 적용한다」의 표가 «이전 → 지금» 으로 전환을 적어두고도, **이 머리말과 `pose_data` 이력 주석·같은 절의 스크래치 DB 줄·「데이터 저장 전략」의 타입 불일치 주석** 넷은 옛 파일을 계속 가리키고 있었다 — [[project_doc_drift_pattern]] 이 말한 «기능 표는 맞고 나머지가 빠진다» 의 실례다.

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

> **이 표의 이력** — 실제 정의는 [`V1__baseline.sql`](../backend/src/main/resources/db/migration/V1__baseline.sql) 이 기준이다(아래 세 변경은 baseline 에 이미 반영돼 있다 — Flyway 도입 시점이 셋보다 뒤였다).
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
세션 시작 시 클라이언트가 `GET /exercises/{exerciseId}/feedback-templates` 로 받아 device TTS 로 재생. 다국어 분리 컬럼 없음 ([[project_korean_only]]).

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

> ⚠️ **이 테이블은 비어 있다** (2026-08-08 확인). 송신하는 쪽(AI)이 `ReportFeedbackBatch` 를 **한 번도 부르지 않는다** — 수신부·테이블·시드만 있다([`tasks/30-ai-remaining-work.md`](./tasks/30-ai-remaining-work.md) §1).

### outbox_events (세션 종료 통보 전달 보장) — 🆕 2026-07-29 추가

```sql
CREATE TABLE outbox_events (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    aggregate_type  VARCHAR(50)  NOT NULL,                    -- 'SESSION'
    aggregate_id    BIGINT       NOT NULL,                    -- session_id (FK 없음 — 아래)
    event_type      VARCHAR(50)  NOT NULL,                    -- 'STOP_ANALYSIS'
    payload         JSON         NOT NULL,                    -- { "sessionId": 42 }
    correlation_id  VARCHAR(64)  NULL,                        -- 원 요청과 이어붙이기 위해 «행에» 저장
    status          ENUM('PENDING','PROCESSING','SENT','FAILED') NOT NULL DEFAULT 'PENDING',
    retry_count     INT          NOT NULL DEFAULT 0,
    next_retry_at   DATETIME     NULL,                        -- 지수 백오프(1s→2s→4s…, 상한 5분)
    locked_by       VARCHAR(64)  NULL,                        -- 선점한 발행기 식별(인스턴스 ID)
    lock_expires_at DATETIME     NULL,                        -- 지난 PROCESSING 은 회수 대상
    sent_at         DATETIME     NULL,
    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
```

**무엇을 위한 테이블인가** — 세션 종료 통보(Spring → AI `StopAnalysis`)가 **dual-write** 였다. DB 커밋과 gRPC 송신이 별개라, 커밋은 됐는데 통보가 실패하면 **Spring 은 `COMPLETED` 인데 AI 에는 orphan 세션**이 남았다. 이제 통보 «의도» 를 세션 상태와 **같은 트랜잭션**에 적재하고, `OutboxPublisher` 가 폴링해 보낸다 → **at-least-once**. 수신 측 멱등과 합쳐 effectively exactly-once. 설계: [`decisions/outbox-reliable-messaging.md`](./decisions/outbox-reliable-messaging.md).

**이 스키마의 결정 3가지가 다른 테이블과 다르다:**

| 결정 | 왜 |
|---|---|
| 🔴 **`aggregate_id` 에 FK 를 걸지 않았다** | 걸면 outbox 가 특정 애그리거트에 종속돼 다른 이벤트 타입으로 확장할 수 없고, **세션 삭제 시 CASCADE 로 통보 이력까지 사라진다.** 참조무결성은 발행기가 대체한다 — 대상이 없으면 AI 가 `success=false` 를 주고 그때 터미널 `FAILED` 로 종결된다 |
| **`correlation_id` 를 컬럼으로 둔다** | 발행기는 `@Scheduled` 스레드라 MDC 가 비어 있고, outbox 는 스레드가 아니라 **시간·프로세스 경계**를 넘는다. 런타임 캡처로는 원리상 이을 수 없어 **행에 저장**해야 원 요청과 이어진다. MDC 와 달리 이 값은 **인스턴스 재시작을 견딘다** |
| **`locked_by` + `lock_expires_at`** | 발행기가 둘 이상일 때 같은 행을 두 번 집는 것 방지(조건부 갱신=CAS). 리뷰에서 지적받아 추가(`eebf852`) |

**운영 파라미터**: 폴링 간격 `outbox.publisher.poll-interval-ms`(기본 1000), 재시도 상한 `outbox.publisher.max-retry`(기본 10). 지표 3종(`shadowfit_outbox_pending`·`dispatch`·`lag`)이 Prometheus 로 나간다 — **적체는 "지금 몇 건"이 아니라 기울기가 답**이라 시계열로 본다([`../monitoring/README.md`](../monitoring/README.md)).

> 🔴 **2026-08-08 정정 — 이 문서에 이 테이블이 6주간 없었다.** 스키마 문서가 «기능 테이블» 은 다 담았는데 **«전달 보장» 테이블이 빠졌다.** 같은 결의 누락이 [`02-folder-structure.md`](./02-folder-structure.md) 에도 있었다(`model/outbox/`·`global/observability/` 없음) — **기능이 아닌 것은 문서 갱신 트리거가 안 울린다**는 패턴이다.

## 스키마는 이제 Flyway 가 적용한다 (2026-08-01, #115)

이 문서의 `CREATE TABLE` 들은 **읽기용 설명**이다. 실제 적용 경로는 다음과 같이 바뀌었다:

| | 이전 | 지금 |
|---|---|---|
| 적용 | docker initdb 가 `mysql/schema.sql` 을 **최초 1회** | **백엔드 부팅 시** `backend/src/main/resources/db/migration/` 의 미적용분 |
| 이력 | 없음 — "이 DB 가 어디까지 갔는지" 알 수 없었다 | `flyway_schema_history` 테이블 |
| 확인 | — | `curl http://localhost:9090/actuator/flyway` (🔴 8080 아님) |

- 스키마를 바꿀 때는 **새 파일**을 추가한다(`V3__…sql`). **이미 적용된 파일은 고치지 않는다** — checksum 이 달라지면 다음 부팅이 실패한다
- ~~`mysql/schema.sql` 은 남아 있다 — 부하테스트 스크래치 DB 를 통째로 세울 때 쓴다~~
  → 🔴 **틀렸다(2026-08-12 정정).** 그 파일은 이 전환(`f7e52d4`)에서 **삭제됐다.**
  **부하테스트 스크래치 DB 도 이미 `V1__baseline.sql` 을 쓴다** — [`loadtest/measure_admin_filter_explain.sh:91`](../loadtest/measure_admin_filter_explain.sh)
  이 baseline 원본에 DB 지정만 얹어 통째로 적용한다. 같은 파일 `:22-23` 이 그 이유를 적어뒀다:
  *"실 스키마를 그대로 쓴다 … 컬럼 타입·길이·인덱스가 실테이블과 어긋날 여지가 없다."*
  **즉 결손이 아니라 대체가 이미 끝나 있었고, 이 줄만 안 따라왔다.**
- ⚠️ `mysql/dev-seed.sql`(테스트 계정·더미 세션 801)은 **의도적으로 마이그레이션에서 제외**했다. 배포 환경에 가면 안 되는 데이터라서 — 부하테스트 전에 손으로 넣는다
- ⚠️ Flyway 가 답하는 것은 *"내 파일이 돌았나"* 까지다. 누가 손으로 `ALTER TABLE` 을 치면 **아무것도 안 남는다** — 드리프트 탐지는 별건이고 미도입이다

상세: [`decisions/schema-migration-tracking.md`](./decisions/schema-migration-tracking.md)

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
  - ⚠️ **타입 불일치(2026-07-15 발견, 미해결 — 2026-08-12 재확인)**: [`V1__baseline.sql:81,194`](../backend/src/main/resources/db/migration/V1__baseline.sql) 은 `joint_coordinates` 를 `JSON NOT NULL` 로 선언하지만, JPA 엔티티 [`PoseData.java:34-35`](../backend/src/main/java/com/shadowfit/model/exercise/PoseData.java) 는 `@Lob @Column(columnDefinition = "TEXT")` 로 매핑돼 있음. 동작상 즉시 문제가 되진 않으나(Hibernate 가 문자열로 다룸) 스키마와 엔티티 선언이 서로 다름 — 엔티티를 JSON 타입으로 맞출지, 스키마를 TEXT 로 통일할지는 결정 필요. **드리프트는 실 DB 가 아니라 테스트에서 샌다**: H2 테스트는 엔티티 기반 DDL 이라 `TEXT` 로 만들어지므로, 같은 결의 사고가 이미 한 번 있었다(`@OnDelete` 누락 2건, [`tasks/27-implementation-gaps.md`](./tasks/27-implementation-gaps.md) §1).
    - 📌 인용처만 갱신했다(`mysql/schema.sql` → `V1__baseline.sql`). **불일치 자체는 그대로 살아 있고, 결정도 그대로 미결이다.**
- **인덱스**: 세션별 시계열 조회를 위해 `(session_id, timestamp_sec)` 복합 인덱스 적용
- **인코딩**: 전체 charset `utf8mb4` 강제 (한국어 피드백 메시지 깨짐 방지, 커밋 0fe056e)

---

## 인덱스 현황 (2026-08-07)

### `exercise_sessions` — 보조 인덱스 4 + FK 1

| 인덱스 | 컬럼 | 겨냥한 쿼리 | 삽입 성격 |
|---|---|---|---|
| `idx_session_member_status_start` | `(member_id, status, start_time)` | 활성 세션 확인, 탈퇴 가드, 주간 리포트·캘린더, 관리자 검색 경로 | 🔴 무작위 |
| `idx_session_member_exercise_status_start` | `(member_id, exercise_id, status, start_time)` | 직전 동일 운동 비교 | 🔴 무작위 |
| `idx_session_status_starttime` | `(status, start_time)` | 관리자 세션 목록 기본 진입 | 🔶 상태 블록 내 순차 |
| `idx_session_starttime_member` | `(start_time, member_id)` | 관리자 대시보드 활성 회원 집계 | 🟢 append |
| *(FK)* `exercise_id` | `(exercise_id)` | FK 제약 | — |

**"삽입 성격" 열의 뜻** — 세션은 시간순으로 도착하는데(`SessionService:121` 이 `startTime(now())`),
인덱스가 **무엇으로 정렬돼 있느냐**에 따라 그게 append 로 보이기도 하고 무작위로 보이기도 한다.
`member_id` 선두는 "누가 언제 운동할지 모른다"라 무작위다. **보조 인덱스 4개 중 2개가 그렇다.**

> ✅ **[#110](https://github.com/Shadowfit/init/issues/110) 해소 (2026-08-07)** — `member_id` 선두 3종이
> 겹치는지가 질문이었고, 답은 **"떼면 안 되고 합쳐야 한다"** 였다.
> `(member_id, start_time)` 와 `(member_id, status)` 를 **`(member_id, status, start_time)`** 하나로 통합했다.
>
> 겹침 해소가 목적이었는데 **새 이득이 나왔다.** `(member_id, status)` 가 `GET /sessions/active` 에서
> "일하는 척"만 하고 있었다 — 등치 둘 + `ORDER BY start_time LIMIT 1` 인데 정렬을 못 받쳐 옵티마이저가
> 다른 인덱스로 도망가 **회원의 전 세션을 읽고 정렬했다.** 통합 후엔 팬아웃(회원당 세션 수)과 무관하게
> **상수 1행**이다: 팬아웃 2000 에서 2001행 → 1행.
> 대가는 주간 리포트가 `status` 를 건너뛰어야 해 읽는 행이 14 → 20 으로 느는 것(절대 0.03ms).
> 근거: [`decisions/session-index-composition.md`](./decisions/session-index-composition.md).
>
> ✅ **그 위에 `(start_time, member_id)` 를 얹었다** — 대시보드 집계 e 를 355ms → 13.6ms(26배)로 줄이고,
> `start_time` 이 `now()` 라 **append** 이므로 쓰기 대가가 작다 — 실측 **1.007배**.
>
> ⚠️ **이 수치를 최종 구성의 쓰기 대가로 읽으면 안 된다.** 1.007배는 *"기존 5개 위에 이 인덱스를
> 하나 더 얹으면"* 을 잰 값이다(통합 전 기준, `admin-page-scope.md` §4-5-1). 지금 구성은
> **2개를 빼고 2개를 넣은 4개**라 기준선이 다르다. 통합 전·후 구성으로 재측정한 값은 **없다.**
> 참고로 `session-index-composition.md` §4-4 는 통합 자체가 인덱스 개수를 줄여 `base` 보다
> 싸다는 **방향**까지만 신뢰하고 배수는 근거로 쓰지 않기로 했다.
> #110 이 선결이었던 이유는 예산이다: 통합으로 4 → 3 이 된 뒤에 얹어 **총 4개로 이전과 같다.**
> 근거: [`decisions/admin-page-scope.md`](./decisions/admin-page-scope.md) §4-5-1.
>
> 🔶 **남은 불안 요소** — 주간 리포트가 `status` 를 건너뛰는 skip scan 은 **팬아웃에 따라 선택이 갈린다**
> (팬아웃 500 에선 고르고, 50 에선 안 골라 2 → 51행). 절대 시간은 0.04ms 라 감수했지만 불안정성은 남는다
> (`session-index-composition.md` §4-2).

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
