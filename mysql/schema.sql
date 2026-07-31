-- 인코딩 강제 (한글 깨짐 방지). 클라이언트 charset이 latin1 이어도 utf8mb4 로 협상.
SET NAMES utf8mb4;

-- 1. 데이터베이스 생성 및 선택
CREATE DATABASE IF NOT EXISTS shadowfit;
USE shadowfit;

-- 2. 사용자 테이블 (Member)
CREATE TABLE IF NOT EXISTS users (
                                     id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                     email VARCHAR(100) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    username VARCHAR(50) UNIQUE NOT NULL,
    sex ENUM('MALE', 'FEMALE', 'NONE') DEFAULT 'NONE',
    role VARCHAR(20) DEFAULT 'USER', -- UserRole enum(USER/ADMIN)의 실제 EnumType.STRING 값과 일치 (2026-07-15 정정, 기존 'ROLE_USER'는 한 번도 안 쓰이던 값)
    profile_image_url VARCHAR(500),
    height DECIMAL(5,1),
    weight DECIMAL(5,1),
    workout_level VARCHAR(20),
    selected_persona ENUM('BEGINNER', 'ADVANCED', 'DIET', 'REHAB') NOT NULL DEFAULT 'BEGINNER',
    preferred_url VARCHAR(500),
    onboarding_completed BOOLEAN DEFAULT FALSE,
    tts_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    tts_speed DECIMAL(3,1) NOT NULL DEFAULT 1.0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, -- DATETIME 대신 TIMESTAMP 권장 (타임존 대응)
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP -- 자동 갱신 설정
    );

-- 2-1. 리프레시 토큰 (RefreshToken.java — 기존에 ddl-auto=update로 암묵 생성되던 테이블, 2026-07-15 명시화)
CREATE TABLE IF NOT EXISTS refresh_token (
                                     member_id BIGINT PRIMARY KEY,
                                     token VARCHAR(512) NOT NULL,
                                     FOREIGN KEY (member_id) REFERENCES users(id) ON DELETE CASCADE
    );

-- 3. 운동 종목 마스터
CREATE TABLE IF NOT EXISTS exercises (
                                         id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                         name VARCHAR(100) NOT NULL,
    category ENUM('LOWER', 'BACK', 'UPPER', 'CORE', 'FULL') NOT NULL,
    description TEXT,
    preferred_url VARCHAR(500),
    target_joints JSON,
    sync_threshold_beginner DECIMAL(5,2) DEFAULT 60.00,
    sync_threshold_advanced DECIMAL(5,2) DEFAULT 85.00,
    sync_threshold_diet DECIMAL(5,2) DEFAULT 70.00,
    sync_threshold_rehab DECIMAL(5,2) DEFAULT 50.00,
    expected_duration_minutes INT DEFAULT 15,
    -- AI 서버가 이 종목 분석을 실제로 지원하는지. 기본 FALSE — 종목 행이 먼저 생기고 분석기가
    -- 나중에 붙는 순서라, 기본을 TRUE로 두면 준비 전에 세션이 열린다(현재 TRUE는 스쿼트뿐).
    analysis_supported BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP -- DEFAULT 추가
    );

-- 4. 운동별 기준 자세 데이터
CREATE TABLE IF NOT EXISTS exercise_references (
                                                   id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                                   exercise_id BIGINT NOT NULL,
                                                   timestamp_sec DECIMAL(10,3) NOT NULL,
    joint_coordinates JSON NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, -- DEFAULT 추가
    FOREIGN KEY (exercise_id) REFERENCES exercises(id) ON DELETE CASCADE,
    INDEX idx_exercise_ref_id (exercise_id)
    );

-- 5. 운동 세션
CREATE TABLE IF NOT EXISTS exercise_sessions (
                                                 id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                                 member_id BIGINT NOT NULL,
                                                 exercise_id BIGINT NOT NULL,
                                                 reference_source VARCHAR(500),
    start_time DATETIME NOT NULL,
    end_time DATETIME,
    total_reps INT DEFAULT 0,
    avg_sync_rate DECIMAL(5,2),
    max_sync_rate DECIMAL(5,2),
    min_sync_rate DECIMAL(5,2),
    calories_burned DECIMAL(7,2),
    difficulty_level INT DEFAULT 1,
    status ENUM('IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'FAILED') DEFAULT 'IN_PROGRESS',
    version BIGINT NOT NULL DEFAULT 0, -- 낙관적 락 (JPA @Version)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, -- DEFAULT 추가
    FOREIGN KEY (member_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (exercise_id) REFERENCES exercises(id),
    -- 캘린더/주간활동 조회(member_id + start_time 범위)가 FK 단일 인덱스로는
    -- member_id로 찾은 뒤 range를 filesort/filter 하는 게 EXPLAIN으로 확인돼 추가
    -- (report-read-path.md §4 인덱스 갭 ④, production-signal-checklist.md §2-2 관련 조사)
    INDEX idx_session_member_starttime (member_id, start_time),
    -- 직전 동일 운동 조회(findFirstByMemberIdAndExerciseIdAndStatusOrderByStartTimeDesc, 이전 기록
    -- 비교용)가 위 인덱스만으론 member_id로 찾은 뒤 exercise_id·status를 filter(Using where,
    -- filtered 5.19%)하는 게 EXPLAIN으로 확인돼 추가 (2026-07-15, filtered 100%로 개선)
    INDEX idx_session_member_exercise_status_start (member_id, exercise_id, status, start_time),
    -- 회원당 활성 세션 체크(existsByMemberIdAndStatus, createSession 매 호출마다 실행)가 위
    -- 인덱스로는 exercise_id가 중간에 껴서 status까지 seek 못 하고 member_id로 찾은 뒤 status를
    -- filter(filtered 10%, rows 1675)하는 게 EXPLAIN으로 확인돼 추가 — (member_id, status)만으로
    -- 바로 seek해 rows 1, filtered 100%로 개선 (2026-07-16).
    INDEX idx_session_member_status (member_id, status)
    );

-- 6. 자세 데이터
-- pose_data: 날짜 파티셔닝 적용 (TTL 만료 시 DROP PARTITION이 DELETE보다 ~625배 빠름,
-- 실측: loadtest/measure_partition.sh, docs/portfolio/realmysql-experiments.md).
-- MySQL/InnoDB는 FK 걸린 테이블의 파티셔닝을 지원 안 해서(ERROR 1506) 아래 두 가지를 함께 변경:
--   1) FK(session_id → exercise_sessions, ON DELETE CASCADE) 제거
--      → 참조무결성은 애플리케이션이 대체: INSERT 시 세션 존재 검증(PoseDataService.savePoseDataBatch),
--        회원 탈퇴 시 이벤트 트리거 비동기 정리(MemberService.deleteAccount, PoseDataCleanupService)
--      → docs/decisions/pose-data-partition-fk-tradeoff.md 참조
--   2) PK를 id 단일키 → (id, created_at) 복합키로 변경 (파티션 키가 모든 유니크키에 포함돼야 하는 제약)
CREATE TABLE IF NOT EXISTS pose_data (
                                         id BIGINT AUTO_INCREMENT,
                                         session_id BIGINT NOT NULL,
    -- 이 프레임이 속한 rep 번호 (1-based, 0=미상). 재부착 시 MAX(rep_number) 로 rep 카운트를 복원한다
    -- (이슈 #59 2단계, docs/decisions/session-resume-and-ai-state.md §3-3).
    rep_number INT NOT NULL DEFAULT 0,
                                         timestamp_sec DECIMAL(10,3) NOT NULL, -- [수정] 소수점 타임스탬프 대응을 위해 DECIMAL로 변경
    joint_coordinates JSON NOT NULL,
    sync_rate DECIMAL(5,2) NOT NULL,
    is_correct BOOLEAN DEFAULT TRUE,
    feedback_message VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, created_at),
    INDEX idx_session_timestamp (session_id, timestamp_sec)
    )
    PARTITION BY RANGE (UNIX_TIMESTAMP(created_at)) (
      PARTITION p2026_01 VALUES LESS THAN (UNIX_TIMESTAMP('2026-02-01 00:00:00')),
      PARTITION p2026_02 VALUES LESS THAN (UNIX_TIMESTAMP('2026-03-01 00:00:00')),
      PARTITION p2026_03 VALUES LESS THAN (UNIX_TIMESTAMP('2026-04-01 00:00:00')),
      PARTITION p2026_04 VALUES LESS THAN (UNIX_TIMESTAMP('2026-05-01 00:00:00')),
      PARTITION p2026_05 VALUES LESS THAN (UNIX_TIMESTAMP('2026-06-01 00:00:00')),
      PARTITION p2026_06 VALUES LESS THAN (UNIX_TIMESTAMP('2026-07-01 00:00:00')),
      PARTITION p2026_07 VALUES LESS THAN (UNIX_TIMESTAMP('2026-08-01 00:00:00')),
      PARTITION p2026_08 VALUES LESS THAN (UNIX_TIMESTAMP('2026-09-01 00:00:00')),
      PARTITION p2026_09 VALUES LESS THAN (UNIX_TIMESTAMP('2026-10-01 00:00:00')),
      PARTITION p2026_10 VALUES LESS THAN (UNIX_TIMESTAMP('2026-11-01 00:00:00')),
      PARTITION p2026_11 VALUES LESS THAN (UNIX_TIMESTAMP('2026-12-01 00:00:00')),
      PARTITION p2026_12 VALUES LESS THAN (UNIX_TIMESTAMP('2027-01-01 00:00:00')),
      PARTITION p2027_01 VALUES LESS THAN (UNIX_TIMESTAMP('2027-02-01 00:00:00')),
      -- 위 범위를 넘는 미래 데이터는 임시로 이 파티션에 적재됨 — 운영 시 주기적으로
      -- ALTER TABLE ... REORGANIZE PARTITION pfuture INTO (...) 로 월별 파티션을 추가해야 함
      PARTITION pfuture VALUES LESS THAN MAXVALUE
    );

-- 7. 달력 일지
CREATE TABLE IF NOT EXISTS daily_logs (
                                          id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                          member_id BIGINT NOT NULL,
                                          log_date DATE NOT NULL,
                                          memo TEXT,
                                          total_exercise_time INT DEFAULT 0,
                                          total_calories DECIMAL(7,2) DEFAULT 0,
    mood ENUM('GREAT', 'GOOD', 'NORMAL', 'BAD', 'TERRIBLE'),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (member_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uk_member_date (member_id, log_date)
    );

-- 8. 운동 보고서
CREATE TABLE IF NOT EXISTS reports (
                                       id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                       member_id BIGINT NOT NULL,
                                       session_id BIGINT NOT NULL,
                                       report_type ENUM('SESSION', 'WEEKLY', 'MONTHLY') DEFAULT 'SESSION',
    summary TEXT,
    detailed_analysis JSON,
    improvement_tips TEXT,
    comparison_with_previous JSON,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, -- DEFAULT 추가
    -- Report 는 BaseTimeEntity 를 상속해 updated_at 을 갖는다(@LastModifiedDate). 이 컬럼이 없어
    -- INSERT 가 "Unknown column 'updated_at'" 으로 터졌고, precomputeReport 가 세션 완료와 같은
    -- 트랜잭션이라 세션 COMPLETED 까지 롤백되면서 모든 세션이 FAILED 로 수렴했다(이슈 #66, 실제 재현).
    -- JPA 감사(auditing)가 값을 직접 써넣으므로 DB DEFAULT/ON UPDATE 는 두지 않는다.
    updated_at DATETIME NULL,
    FOREIGN KEY (member_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (session_id) REFERENCES exercise_sessions(id) ON DELETE CASCADE,
    -- 세션당 리포트 1건 보장 (report 생성 멱등성, db-deep-dive.md §C) — 아래 주석은 스키마 작성
    -- 당시 기준이며, 현재는 SessionService.precomputeReport 가 세션 완료 시 리포트를 생성한다.
    -- (그 변경 때 updated_at 추가가 누락돼 위 버그가 생겼다)
    -- 재시도로 인한 중복 생성을 DB 제약으로 막기 위해 선반영
    UNIQUE KEY uk_report_session (session_id)
    );

-- 9-A. 운동별 피드백 메시지 템플릿 (TTS 멘트, 페르소나별 분기)
-- persona NULL row 는 페르소나 row 없을 때 fallback 으로 사용 (분기 4-A + BE-13)
CREATE TABLE IF NOT EXISTS exercise_feedback_templates (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exercise_id BIGINT NOT NULL,
    feedback_type VARCHAR(30) NOT NULL,
    persona VARCHAR(10) NULL,
    message VARCHAR(200) NOT NULL,
    priority INT NOT NULL DEFAULT 100,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (exercise_id) REFERENCES exercises(id) ON DELETE CASCADE,
    UNIQUE KEY uk_exercise_feedback_persona (exercise_id, feedback_type, persona)
);

-- 9-B. 세션 피드백 판정 이벤트 로그 (AI 가 BT-SET 으로 batch 송신, 멱등성 보장)
CREATE TABLE IF NOT EXISTS session_feedback_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    feedback_type VARCHAR(30) NOT NULL,
    sync_rate_at_trigger DECIMAL(5,2),
    occurred_at DATETIME NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (session_id) REFERENCES exercise_sessions(id) ON DELETE CASCADE,
    -- idx_session_feedback(session_id, occurred_at)는 2026-07-24 제거 — uk_session_event가 앞 2컬럼을
    -- 그대로 포함해 읽기 쪽엔 이득 0(EXPLAIN 확인: findBySessionIdOrderByOccurredAtAsc·GROUP BY
    -- feedback_type 집계 둘 다 옵티마이저가 idx_session_feedback 존재 시에도 uk_session_event만 선택),
    -- batch INSERT 유지비용만 이중 (production-signal-checklist.md:343, loadtest/measure_redundant_index.sh)
    UNIQUE KEY uk_session_event (session_id, occurred_at, feedback_type)
);

-- 10. 신체 변화 기록 (user_id -> member_id 변경)
-- ⚠️ 현재 Entity/Repository/Service 전부 없는 미구현 테이블(production-signal-checklist.md).
-- ON DELETE CASCADE는 2026-07-24 선반영 — MemberService.deleteAccount가 memberRepository.delete()
-- 하나로 회원 삭제를 처리하고 나머지 테이블 정리를 전부 FK CASCADE에 의존하는 구조라, CASCADE 없이
-- 이 테이블에 쓰기 기능이 생기면 refresh_token과 동일한 FK violation 500 버그가 재현됨(8efbca1에서
-- refresh_token 대상으로 실제 발견·수정한 바 있음).
CREATE TABLE body_records (
                              id BIGINT AUTO_INCREMENT PRIMARY KEY,
                              member_id BIGINT NOT NULL,          -- 수정 완료
                              record_date DATE NOT NULL,
                              weight DECIMAL(5,1),
                              body_fat_percentage DECIMAL(4,1),
                              muscle_mass DECIMAL(5,1),
                              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                              FOREIGN KEY (member_id) REFERENCES users(id) ON DELETE CASCADE,
                              INDEX idx_member_date (member_id, record_date)
);
-- 11. 아웃박스 (트랜잭셔널 메시징 — 세션 종료 통보 유실 방지)
-- 설계 근거: docs/decisions/outbox-reliable-messaging.md
--
-- endSession 은 "MySQL 커밋"과 "AI 에 gRPC StopAnalysis 송신" 두 곳에 쓰는 dual-write 라,
-- 두 번째 쓰기가 실패하면(gRPC 오류 / 서킷 OPEN 스킵) 복구 수단이 없었다(at-most-once).
-- 보낼 통보를 같은 트랜잭션 안에 이 테이블 행으로 INSERT 하고, 별도 발행기가 전달을
-- 책임진다(at-least-once). 수신측 멱등성(applyComplete first-write-wins)과 합쳐
-- 통보 전달은 effectively exactly-once 가 된다.
--   ※ 단 "통보 전달"에 한한다. AI 프로세스가 재시작해 세션 상태를 잃으면 통보는 정확히
--     전달되지만 분석 결과는 회수되지 않는다(문서 §3-2). outbox 의 결함이 아니라 경계다.
CREATE TABLE IF NOT EXISTS outbox_events (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- 애그리거트 식별. aggregate_id 에 exercise_sessions FK 를 걸지 않는다 — 걸면 outbox 가
    -- 특정 애그리거트에 종속돼 다른 이벤트 타입으로 확장할 수 없고, 세션 삭제 시 CASCADE 로
    -- 통보 이력까지 사라진다(문서 §4-1-1). 참조무결성은 발행기가 대체: 대상이 없으면 AI 가
    -- success=false 를 주고 그때 터미널 FAILED 로 종결된다.
    aggregate_type  VARCHAR(50)  NOT NULL,                    -- 'SESSION'
    aggregate_id    BIGINT       NOT NULL,                    -- session_id
    event_type      VARCHAR(50)  NOT NULL,                    -- 'STOP_ANALYSIS'
    payload         JSON         NOT NULL,                    -- { "sessionId": 42 }
    -- 발행기는 @Scheduled 스레드라 MDC 가 비어 있고, outbox 는 스레드가 아니라 시간·프로세스
    -- 경계를 넘으므로 런타임 캡처(CorrelationIds.wrap)로는 원리상 이을 수 없다. 행에 저장해야
    -- 원 요청의 흐름과 이어진다(문서 §4-4). MDC 와 달리 이 값은 인스턴스 재시작을 견딘다.
    correlation_id  VARCHAR(64)  NULL,
    -- PROCESSING: 발행기가 선점해 송신 중. SKIP LOCKED 만으로는 중복 송신이 안 막힌다 —
    -- 행 락은 트랜잭션 수명만큼인데 gRPC 송신은 그 트랜잭션 밖에서 일어나므로, 송신 도중
    -- 크래시하면 행은 PENDING 인 채 남아 다른 발행기가 또 집는다. 그래서 소유권을 "상태 +
    -- 만료 시각"으로 표현한다(문서 §4-3-1). SKIP LOCKED 는 작업 분배, 중복 방지는 이쪽 담당.
    status          ENUM('PENDING','PROCESSING','SENT','FAILED') NOT NULL DEFAULT 'PENDING',
    retry_count     INT          NOT NULL DEFAULT 0,
    next_retry_at   DATETIME     NULL,                        -- 지수 백오프(1s→2s→4s…, 상한 5분)
    locked_by       VARCHAR(64)  NULL,                        -- 선점한 발행기 식별(인스턴스 ID)
    lock_expires_at DATETIME     NULL,                        -- 이 시각이 지난 PROCESSING 은 회수 대상
    sent_at         DATETIME     NULL,
    -- 업무 시각은 DATETIME, created_at 만 TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    -- (exercise_sessions:69-79 패턴 준수)
    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    -- 발행기는 두 갈래를 각각 별도 쿼리로 집는다:
    --   ① 신규·재시도분: status='PENDING'    AND (next_retry_at IS NULL OR next_retry_at <= NOW())
    --   ② 유실 회수분:   status='PROCESSING' AND lock_expires_at <= NOW()
    --
    -- 인덱스는 ①용 하나만 둔다. 처음엔 ②용 (status, lock_expires_at) 도 같이 뒀는데, 실측해보니
    -- 둘 다 선두 컬럼이 status 라 옵티마이저가 구분하지 못하고 아무거나 골라 **양쪽 다 status
    -- 프리픽스만** 쓰고 나머지를 필터링했다(key_len 1, filtered 33~40%). 데이터 분포를 바꿔도
    -- 동일해 분포 탓이 아니라 구조 탓이었다. ②용을 지우자 ①이 정상화됐다(key_len 7, filtered 100%,
    -- range + ICP). 2026-07-29 MySQL 8.0.46 실측, EXPLAIN 근거.
    --
    -- ②가 인덱스를 못 타는 건 감수한다 — PROCESSING 행은 "지금 송신 중 + 크래시로 묶인 것"뿐이라
    -- 구조적으로 (배치크기 × 발행기수) 수준(수십 건)을 넘지 않아 좁힐 대상이 애초에 없다. 반면
    -- ①은 AI 장애 시 수천 건까지 쌓이는 쿼리라 인덱스가 실제로 필요하다. 필요한 쪽만 고친 셈.
    -- 만약 PROCESSING 이 크게 적체되는 상황이 관측되면 두 시각 컬럼을 visible_at 하나로 합쳐
    -- (status, visible_at) 단일 인덱스로 가는 안이 있다(SQS visibility timeout 모델, 실측 확인함).
    INDEX idx_outbox_dispatch (status, next_retry_at)
) COMMENT='트랜잭셔널 아웃박스 — 세션 종료 통보(STOP_ANALYSIS) 전달 보장';
-- 보존 정책(문서 §4-1-2): SENT 는 짧게(예: 7일) 후 삭제, 터미널 FAILED 는 길게(예: 90일)
-- 보존한다 — 지표는 건수만 알려주고 "어느 세션이 유실됐는지"는 이 행에만 남기 때문.
-- 소량 반복 DELETE 의 파편화 누적은 이 프로젝트에서 미검증이라, 관측되면 created_at 기준
-- 파티션 + DROP PARTITION(pose_data 에서 검증된 패턴)으로 전환한다.
