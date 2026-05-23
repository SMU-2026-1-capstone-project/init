## 운동 세션 네트워크 타임아웃 처리 가이드

### 📋 개요

운동 중 네트워크가 끊겼을 때, FastAPI 서버에서 분석 결과를 받지 못하는 상황을 자동으로 처리합니다.
Spring Scheduler를 통해 주기적으로 타임아웃된 세션을 확인하고 **FAILED** 상태로 변경합니다.

---

### 🏗️ 시스템 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│ 클라이언트 (React Native)                                     │
│ • 운동 시작 → Spring에 세션 생성 요청                         │
│ • 네트워크 끊김                                               │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ Spring Boot API Server                                      │
│ 1. 세션 생성 (Status: IN_PROGRESS)                          │
│ 2. gRPC로 FastAPI에 분석 요청                               │
│ 3. SessionTimeoutScheduler가 주기적으로 체크                │
└─────────────────────────────────────────────────────────────┘
                          ↓ (네트워크 장애)
┌─────────────────────────────────────────────────────────────┐
│ FastAPI AI Server                                           │
│ 분석 진행 중... (응답 불가)                                  │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ Spring 스케줄러 (매 1분마다)                                  │
│ • IN_PROGRESS 상태 세션 항상 모니터링                        │
│ • 타임아웃 시간 = 세션 시작 + 예상시간 + 30분               │
│ • 조건 만족 시 Status → FAILED로 변경                      │
└─────────────────────────────────────────────────────────────┘
```

---

### ⏰ 타임아웃 계산

```
타임아웃 시간 = 세션 시작 시간 + 예상 운동 시간 + 버퍼 시간

예시):
• 스쿼트 시작: 14:00:00
• 스쿼트 예상 시간: 15분
• 버퍼 시간: 30분
• 타임아웃 시간: 14:00:00 + 15분 + 30분 = 14:45:00
• 14:45:00 이후에도 IN_PROGRESS면 → FAILED로 변경
```

---

### 📝 변경된 사항

#### 1. **Status Enum 추가**
```java
// com.shadowfit.model.exercise.Status.java
public enum Status {
    IN_PROGRESS,
    COMPLETED,
    CANCELED,
    FAILED  // ← 신규 추가 (네트워크 장애 시)
}
```

#### 2. **Exercise 모델 확장**
```java
// 각 운동 종목당 예상 소요 시간 (분)
private Integer expectedDurationMinutes = 15; // 기본값

// 예시:
// • 스쿼트: 15분
// • 플랭크: 10분
// • 에어로빅: 30분
```

#### 3. **SessionTimeoutScheduler 추가**
```java
@Scheduled(fixedDelayString = "1m", initialDelayString = "30s")
public void checkAndTimeoutSessions()
```
- 매 1분마다 실행
- IN_PROGRESS 상태의 세션 확인
- 타임아웃된 세션을 FAILED로 변경

#### 4. **설정값 (application.yml)**
```yaml
exercise:
  session:
    timeout:
      default-buffer-minutes: 30      # 타임아웃 버퍼 (분)
      check-interval-minutes: 1       # 스케줄러 체크 간격 (분)
```

---

### 🔄 동작 플로우

```
1. 사용자가 운동 시작
   ↓
2. Spring이 세션 생성 (Status: IN_PROGRESS, startTime: now)
   ↓
3. gRPC로 FastAPI 분석 요청
   ↓
4. 일반적인 경우: FastAPI에서 분석 완료 → gRPC 응답 → Status: COMPLETED
   ↓
5. 네트워크 장애 경우: 응답 안 됨
   ↓
6. SessionTimeoutScheduler 실행 (매 1분마다)
   └ IN_PROGRESS 세션 조회
   └ 현재시간 > (시작시간 + 예상시간 + 30분) 확인
   └ ✓ 조건 만족 → Status: FAILED로 변경
         └ 로그: "세션 타임아웃 감지" 기록
         └ endTime: 현재시간으로 설정
```

---

### 📊 데이터베이스 변경

#### Exercise 테이블
```sql
ALTER TABLE exercises ADD COLUMN expected_duration_minutes INT DEFAULT 15;

-- 운동별 예상 시간 설정
UPDATE exercises SET expected_duration_minutes = 15 WHERE name = '스쿼트';
UPDATE exercises SET expected_duration_minutes = 10 WHERE name = '플랭크';
UPDATE exercises SET expected_duration_minutes = 20 WHERE name = '런지';
```

#### Exercise_Sessions 테이블
```sql
-- Status ENUM에 FAILED 추가
ALTER TABLE exercise_sessions 
  MODIFY COLUMN status ENUM('IN_PROGRESS', 'COMPLETED', 'CANCELED', 'FAILED') DEFAULT 'IN_PROGRESS';
```

---

### 🛠️ 설정 커스터마이제이션

#### 버퍼 시간 조정 (application.yml)
```yaml
exercise:
  session:
    timeout:
      default-buffer-minutes: 45  # 45분으로 변경 (예: 느린 네트워크 고려)
```

#### 운동별 예상 시간 설정
```sql
-- 데이터베이스에서 직접 설정
UPDATE exercises SET expected_duration_minutes = 25 WHERE id = 1;  -- 스쿼트 25분
UPDATE exercises SET expected_duration_minutes = 12 WHERE id = 2;  -- 런지 12분
UPDATE exercises SET expected_duration_minutes = 30 WHERE id = 3;  -- 에어로빅 30분
```

#### 체크 간격 조정 (더 자주 확인)
```yaml
exercise:
  session:
    timeout:
      check-interval-minutes: 0.5  # 30초마다 확인
```

---

### 🔔 향후 확장 기능 (선택사항)

#### 1. 사용자 알림
```java
// SessionTimeoutScheduler에서 호출
notificationService.notifySessionTimeout(session);
// → 푸시 알림: "운동이 네트워크 오류로 저장되지 않았습니다"
```

#### 2. 자동 재시도
```java
// 타임아웃된 세션을 재분석 대기 상태로 표시
session.setStatus(Status.PENDING_RETRY);
```

#### 3. 모니터링 & 알림
```java
// 타임아웃이 자주 발생하면 관리자에게 알림
if (timeoutCount > 10) {
    adminService.notifyHighTimeoutRate(timeoutCount);
}
```

---

### ✅ 장점

✓ **자동화된 실패 처리**: 수동 개입 없이 자동으로 처리
✓ **데이터 손실 방지**: 세션 레코드는 DB에 보존 (FAILED 상태)
✓ **명확한 상태**: 사용자가 "실패"를 인식하고 재시도 가능
✓ **설정 가능**: 버퍼 시간을 필요에 따라 조정 가능
✓ **N+1 최적화**: FETCH JOIN으로 쿼리 최적화

---

### ⚠️ 주의사항

1. **운동별 예상 시간 정확성**
   - 초기 데이터 입력 시 현실적인 값 설정 필수
   - 운동 난이도에 따라 변할 수 있음

2. **버퍼 시간 설정**
   - 너무 짧으면: 실제 느린 네트워크도 타임아웃
   - 너무 길면: 장시간 응답 대기

3. **스케줄러 부터드**
   - 체크 간격이 짧으면 DB 부하 증가
   - 일반적으로 1분 간격이 권장됨

---

### 🧪 테스트 시나리오

#### 시나리오 1: 정상 케이스
```
1. 14:00 - 세션 시작 (예상 15분)
2. 14:02 - FastAPI 응답 수신
3. 14:02 - Status: COMPLETED ✓
```

#### 시나리오 2: 네트워크 장애
```
1. 14:00 - 세션 시작 (예상 15분)
2. 네트워크 끊김 (FastAPI 응답 없음)
3. 14:45 - 스케줄러 실행
4. 14:45 - 타임아웃 감지 (14:00 + 15분 + 30분 = 14:45)
5. 14:45 - Status: FAILED로 변경 ✓
```

#### 시나리오 3: 느린 네트워크 (경계 케이스)
```
1. 14:00 - 세션 시작 (예상 15분)
2. 14:30 - FastAPI 응답 시작 (느린 에지)
3. 14:44 - FastAPI 응답 완료 (타임아웃 1분 전)
4. Status: COMPLETED ✓ (간신히 세이프)
```

---

### 📞 문제 해결

#### Q: FAILED 세션이 너무 많이 생김
```
A: 다음을 확인하세요:
1. FastAPI 서버 상태 확인
2. 네트워크 연결 상태
3. 버퍼 시간 증가 고려 (30분 → 45분)
4. 타임아웃 로그에서 네트워크 지연 패턴 분석
```

#### Q: 예상보다 빨리 타임아웃됨
```
A: expected_duration_minutes 확인:
   UPDATE exercises SET expected_duration_minutes = 25 WHERE name = '스쿼트';
```

#### Q: 스케줄러가 실행 안 됨
```
A: 확인 사항:
1. @EnableScheduling이 메인 클래스에 있는가?
2. spring.task.scheduling.pool.size > 0인가?
3. 로그에서 "스케줄러 실행" 메시지 확인
```

---

---

### 🔒 동시성 처리 (낙관적 락)

#### 문제 상황

`SessionTimeoutScheduler`(Spring)와 `CompleteAnalysis` gRPC 콜백(AI)이 **같은 세션을 동시에 갱신**하려고 할 때 데이터 정합성 문제 발생.

```
[충돌 시점]
  Spring 스케줄러: IN_PROGRESS → FAILED
  AI 콜백       : IN_PROGRESS → COMPLETED + 운동 기록
                ↓
        어느 쪽이 마지막에 commit 하느냐에 따라
        실제 운동 데이터가 사라질 수 있음
```

#### 해결: `@Version` 기반 낙관적 락

Session 엔티티에 버전 컬럼을 추가하면 JPA가 UPDATE 시 `WHERE id=? AND version=?` 을 자동으로 붙임. 다른 트랜잭션이 먼저 커밋해 버전이 바뀌었으면 `ObjectOptimisticLockingFailureException` 발생.

```java
// Session.java
@Version
@Column(nullable = false)
private Long version = 0L;
```

```sql
-- schema.sql
ALTER TABLE exercise_sessions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
```

#### 시나리오별 처리

##### 1-1) 정상 케이스
AI 가 타임아웃 전에 결과 도착 → COMPLETED + 기록 저장 (v=0 → v=1).

##### 1-2) 경계 시점 동시 발생 (찰나의 충돌)

| 시각 | 스케줄러 | AI 콜백 | DB |
|---|---|---|---|
| T   | findById (v=0) | findById (v=0) | IN_PROGRESS, v=0 |
| T+ε | FAILED set | COMPLETED + 기록 set | IN_PROGRESS, v=0 |
| T+δ | saveAndFlush 성공 | — | **FAILED, v=1** |
| T+2δ | — | saveAndFlush → `OptimisticLockException` (v=0 기대했지만 v=1) | FAILED, v=1 |
| T+3δ | — | **재시도**: findById (v=1, FAILED) → COMPLETED + 기록 → saveAndFlush | **COMPLETED, v=2** |

→ 스케줄러가 먼저 commit 했어도 **사용자 운동 데이터는 유실되지 않음**.

##### 1-3) 늦은 재연결 (충돌 없음)

```
T+0   세션 시작                              IN_PROGRESS, v=0
T+45  스케줄러 → FAILED                      FAILED, v=1
T+60  AI 가 뒤늦게 결과 도착
        findById (v=1, FAILED) 로드
        COMPLETED + 기록 set
        saveAndFlush                         COMPLETED, v=2
```

이미 스케줄러가 commit 을 끝낸 상태라 충돌 없음. AI 가 가져온 시점의 v=1 을 그대로 갱신해 v=2.

#### 구현 핵심

| 위치 | 처리 |
|---|---|
| `SessionTimeoutScheduler` | 충돌 시 `ObjectOptimisticLockingFailureException` catch → 양보 (AI 결과 우선) |
| `SessionService.completeSession` | 충돌 시 최대 3회 재시도 → 재조회 후 COMPLETED + 기록 덮어쓰기 |
| `ExerciseAnalysisService.completeSession` | 동일 재시도 로직 (앱→Spring 경로) |
| 트랜잭션 분리 | 스케줄러는 세션별 독립 트랜잭션(`SessionService.markAsFailedIfStillInProgress`) — 한 세션 충돌이 다른 세션 처리를 막지 않음 |
| 자기 주입(`@Lazy`) | 같은 클래스 내부 메서드 호출 시에도 Spring 프록시를 거치도록 `@Transactional` 적용 보장 |

---

### 🔁 멱등성 처리 (AI ↔ Spring 응답 신호 유실)

#### 문제 상황

gRPC 양방향이라 어느 쪽 신호든 유실 가능:

```
2-1) AI → Spring 요청 신호 유실
     AI: 결과 메모리 보관 → Spring 전송 → 응답 미수신
        → (네트워크 복구 후) 같은 결과 재전송

2-2) Spring → AI 응답 신호 유실
     Spring: DB 저장 완료 → 응답 송신 중 끊김
     AI:     응답 미수신 → 같은 결과 재전송
```

두 케이스 모두 **Spring 입장에서는 "같은 sessionId로 CompleteAnalysis 가 두 번 들어오는 상황"**.

#### 멱등성 미적용 시 문제

| 항목 | 문제 |
|---|---|
| 첫 `endTime` 손실 | 두 번째 호출 시각으로 덮어써짐 |
| 불필요한 DB write | 같은 데이터 재저장 |
| `version` 무의미한 증가 | 낙관적 락 카운터만 올라감 |
| 자기 자신과 충돌 가능 | 동시 재전송 시 OptimisticLockException |

#### 해결: 진입점에서 status 체크

```java
@Transactional
public void applyComplete(SessionCompleteRequest request) {
    Session session = sessionRepository.findById(request.getSessionId())
            .orElseThrow(...);

    // 멱등성: 이미 COMPLETED 면 첫 완료 시각/기록 보존하고 즉시 종료
    if (session.getStatus() == Status.COMPLETED) {
        return;
    }
    session.setStatus(Status.COMPLETED);
    // ... 기록 저장
}
```

#### Status 별 동작

| 진입 시 status | 동작 | 설명 |
|---|---|---|
| `IN_PROGRESS` | 정상 완료 처리 | 가장 일반적 |
| `FAILED` | COMPLETED + 기록으로 덮어쓰기 | 시나리오 1-2/1-3 |
| `COMPLETED` | **즉시 return (no-op)** | **시나리오 2-1/2-2 (재전송)** |
| `CANCELED` | 현재는 덮어씀 (기존 동작 유지) | 사용자 명시적 취소 — 추후 정책 결정 필요 |

#### 효과
- 첫 완료 시각(`endTime`) 보존
- AI 는 안심하고 재전송 가능 (At-Least-Once 보장)
- 동일 데이터로 인한 OptimisticLock 충돌 방지
- 진입점이 두 곳(`SessionService`, `ExerciseAnalysisService`)이지만 동일 정책 적용

---

### 🧰 테스트 환경 정비 (H2 인메모리)

#### 문제 상황

기존 테스트는 `@SpringBootTest` 로 전체 컨텍스트를 띄우면서 production 용 `application.yml` 을 그대로 사용 → Docker 컨테이너명 `shadowfit-mysql` 을 가리키므로 **로컬에서 `gradlew test` 항상 실패**.

```
java.net.UnknownHostException: shadowfit-mysql
→ Unable to determine Dialect without JDBC metadata
```

#### 해결: 테스트 전용 application.yml + H2

| 변경 | 내용 |
|---|---|
| `build.gradle` | `testRuntimeOnly 'com.h2database:h2'` 추가 |
| `src/test/resources/application.yml` | 신규 생성 — H2 인메모리, JPA `ddl-auto: create-drop`, `sql.init.mode: never` |
| 기타 | JWT/internal 토큰 더미값, gRPC 더미 주소, security.whitelist 모두 포함 |

JPA가 `@Entity` 에서 자동 스키마 생성하므로 ENUM/JSON 등 MySQL 전용 syntax 호환 문제 없음.

**결과**: `gradlew test` / `gradlew build` 가 Docker 없이도 통과.

---

### 📚 관련 코드

- **Model**: `com.shadowfit.model.exercise.Session` (`@Version` 포함)
- **Enum**: `com.shadowfit.model.exercise.Status` (`FAILED` 포함)
- **Scheduler**: `com.shadowfit.service.Exercise.SessionTimeoutScheduler`
- **Service**: `com.shadowfit.service.Exercise.SessionService.completeSession` / `markAsFailedIfStillInProgress`
- **Service**: `com.shadowfit.service.Exercise.ExerciseAnalysisService.completeSession`
- **Repository**: `com.shadowfit.repository.exercise.SessionRepository`
- **Config**: `com.shadowfit.global.config.SchedulerConfig`
- **Test**: `test/java/com/shadowfit/service/Exercise/SessionTimeoutSchedulerTest.java`
- **Test resources**: `src/test/resources/application.yml`

