# 신뢰성 있는 비동기 통보 — 세션 종료 통보 유실(E1) 보강

작성일: 2026-06-12 / **전면 재작성: 2026-07-29**(실코드 재대조 — §1 피해 기술 정정, 서킷 스킵 경로 신설, outbox의 한계 명시)
상태: **분석/추천 (결정 전)** — 대안 비교·근거 제공. 착수·방식 선택은 사용자 confirm 후 박제 ([[feedback_user_decides_not_claude]], [[feedback_decision_doc]])
대상: 백엔드(Spring) 신입 포폴 — 헤드라인(세션 분산 정합성) **직접** 강화
관련: [`portfolio-narrative.md`](../portfolio/portfolio-narrative.md)(§1 헤드라인·§3 보강), [`failure-modes.md`](../portfolio/failure-modes.md)(E1·E2·C4·T3), [`observability-correlation-id.md`](./observability-correlation-id.md), [`db-portfolio-roadmap.md`](./db-portfolio-roadmap.md)

---

## 0. 한 줄 목적

> **"사용자는 운동을 끝냈는데, 그 사실을 FastAPI에 알리는 통보가 유실되어 분석 결과가 영영 회수되지 않는 것"을 막는다.**

유실 0(송신 at-least-once) + 멱등 수신(이미 있음) = **effectively exactly-once**.

---

## 1. 문제 — dual-write (실코드 기준, 2026-07-29 재대조)

`endSession` 한 번의 논리 작업이 **두 곳에 쓴다**:

| | 쓰기 대상 | 수단 | 원자성 |
|---|---|---|---|
| write 1 | MySQL (`exercise_sessions.end_time`) | `@Transactional` commit | ✅ |
| write 2 | FastAPI (분석 중단 통보) | gRPC `StopAnalysis` | ❌ |

현재 흐름 (`SessionService.endSession:192-218`):

```
endSession  @Transactional
  session.setEndTime(...); saveAndFlush()      // write 1 — :206-207
  registerSynchronization { afterCommit() {    // :210 — 커밋 확정 후. 순서는 잘 잡음
      analysisService.stopAnalysis(sessionId)  // write 2 (gRPC) — :214
  }}
```

### 1-1. ⚠️ 정정 — endSession은 상태를 COMPLETED로 바꾸지 않는다

이 문서의 이전 판은 갭을 *"MySQL은 COMPLETED인데 FastAPI는 orphan IN_PROGRESS"* 로 적었다. **실코드와 다르다.** `endSession`은 `end_time`만 찍고 `status`는 **`IN_PROGRESS` 그대로 둔다**(:206-207). `COMPLETED`로의 전이는 오직 AI의 `CompleteAnalysis` 콜백이 한다(`SessionService.applyComplete:139`, `ExerciseAnalysisService.applyCompleteFromApp:319`).

그리고 AI 쪽을 보면 **`CompleteAnalysis`를 촉발하는 유일한 트리거가 `StopAnalysis`다**(`ai-server/app/grpc/exercise_servicer.py:102-129` — Stop 수신 → 누적 rep으로 통계 산출 → 별도 스레드로 Spring 콜백). AI에는 자체 타임아웃도, 세션 상태 TTL도 없다(`session_state.py` — `remove()`는 Stop 경로에서만 불린다).

**따라서 통보 1건이 유실되면 실제로 벌어지는 일은 "불일치"가 아니라 연쇄 손실이다:**

```
통보 유실
  → AI 는 세션이 끝난 줄 모름 → CompleteAnalysis 영영 안 옴
  → DB 세션은 IN_PROGRESS 로 방치 (end_time 만 찍힌 채)
  → 예상시간+30분 뒤 SessionTimeoutScheduler 가 FAILED 로 걷어감 (:54, markAsFailedIfStillInProgress:254)
  → 사용자가 실제로 한 운동이 "실패"로 기록되고 reps/sync 결과는 소실
  → AI 프로세스엔 SessionState 가 영구 잔류 (메모리 누수 = E2)
```

즉 E1의 진짜 피해는 **"두 DB의 값이 다르다"가 아니라 "사용자 운동 결과의 영구 유실 + FAILED 오분류"** 다. 이게 `failure-modes.md`의 **C4와 같은 사건**인 이유이기도 하다.

### 1-2. ⚠️ 신설 — 유실 경로는 `onError` 말고 하나 더 있다 (서킷 스킵)

`stopAnalysis` (`ExerciseAnalysisService:248-278`):

```java
CircuitBreaker cb = aiCircuitBreaker();
if (!cb.tryAcquirePermission()) {                       // :257
    log.warn("AI 서버 서킷브레이커 OPEN — 중단 요청 스킵");  // :258  ← 아예 안 보냄
    return;                                             // :259
}
...
getAuthenticatedStub().stopAnalysis(request, CorrelationIds.preserving(new StreamObserver<>() {
    onError(Throwable t) {
        cb.onError(...);                                // :271 — 서킷브레이커에는 기록
        log.error("AI 서버 중단 실패: {}", t.getMessage()); // :272 — 여전히 로그만
    }
}));
```

유실 경로가 **둘**이다:

| 경로 | 위치 | 현재 처리 | 심각도 |
|---|---|---|---|
| ① gRPC 실패/데드라인 | `onError:270-273` | 로그 + 서킷 기록만 | 복구 없음 |
| ② **서킷 OPEN → 송신 자체 스킵** | `:257-260` | `log.warn` 후 `return` | **복구 없음. 더 나쁨** |

②가 왜 더 나쁜가: 서킷이 열려 있는 순간이란 **AI가 실제로 죽어 있어 통보가 가장 많이 쌓이는 구간**이다. 그때 통보를 통째로 버린다. 게다가 `startAnalysis` 쪽은 같은 서킷 스킵에서 `markAsFailedIfStillInProgress`로 **사용자 피드백을 앞당기는 보상 처리**를 하는데(`:206-215`), `stopAnalysis`엔 그 보상조차 없다 — 조용히 사라지고 30분 뒤 타임아웃만 남는다.

> 관측성 작업(2026-07-28)으로 이 로그들에 **cid가 붙게 됐지만**, 그건 "실패를 더 잘 보이게" 한 것이지 **복구한 게 아니다.** 갭은 그대로다.

### 1-3. 이미 가진 절반

수신측 멱등성 — `SessionService.applyComplete:135`, `ExerciseAnalysisService.applyCompleteFromApp:313` 둘 다 `if (status == COMPLETED) return`(first-write-wins). **중복 통보는 안전하다. 그래서 송신만 보강하면 완결된다.**

> `afterCommit`을 쓴 건 **정답**이다(커밋 전 송신 시 "통보는 갔는데 DB 롤백"이 더 나쁨). 순서는 맞췄고, **두 번째 write의 실패를 메꾸지 못하는 것**만 남았다.

---

## 2. 대안 비교 ★

| # | 방식 | 핵심 아이디어 | dual-write 해소 | 크래시 안전 | 비용 | 평가 |
|---|---|---|---|---|---|---|
| A | **트랜잭셔널 Outbox + 폴링 발행기** | 보낼 통보를 같은 트랜잭션에 행으로 INSERT, 별도 잡이 전달 보장 | ✅ | ✅ | 중 | **⭐ 추천** |
| B | 동기 재시도 (`@Retryable`/afterCommit 내 재시도) | gRPC 실패 시 그 자리서 N회 재시도 | ❌ | ❌ | 저 | 순진. 크래시 시 유실 |
| C | 상태기반 리컨실리에이션(sweep 잡) | 메시지를 안 적고 **불일치 상태를 주기적으로 찾아 고침** | ✅(우회) | ✅ | 중 | 유력 2순위 |
| D | 영속 메시지 브로커(Kafka/SQS) | gRPC 대신 큐에 발행 | ❌(여전히 dual-write) | — | 고 | 단독으론 문제 그대로 |
| E | 2PC / XA 분산 트랜잭션 | DB+gRPC를 한 원자 단위로 묶음 | ✅(이론) | — | 고 | 교과서적 기각 |
| F | 현행 유지 (타임아웃 스케줄러 세이프티넷) | 아무것도 안 함 | ✗ | — | 0 | 정직한 baseline |

### A. 트랜잭셔널 Outbox (추천)
- write 2를 "지금 gRPC"에서 → **"같은 트랜잭션 안 `outbox_events` INSERT"** 로 치환. 둘 다 MySQL이라 원자적 커밋.
- 별도 `@Scheduled` 발행기가 `PENDING`을 폴링 → gRPC 송신 → 성공 시 `SENT`, 실패면 남겨 다음 턴 재시도. **크래시해도 PENDING 행이 DB에 남아 재시작 후 이어 전달**(at-least-once).
- **§1-2의 두 경로를 한 번에 덮는다** — 서킷 OPEN이면 `return` 대신 그냥 다음 폴링에 재시도. 서킷과 outbox가 서로 보완(서킷=빠른 실패, outbox=지연 후 전달).
- 단점: 테이블 1 + 발행기 1 추가. 폴링 간격만큼 통보 지연.

### B. 동기 재시도 — 왜 부족한가
- `onError`에서 N회 재시도는 일시 장애엔 듣지만 **재시도 중 인스턴스가 죽으면 "보낼 일"이 메모리에서 증발**. 서킷 OPEN 구간(§1-2 ②)은 재시도해봐야 계속 거부라 애초에 못 푼다.
- outbox의 본질은 재시도가 아니라 **보낼 의무의 영속화**.

### C. 상태기반 리컨실리에이션 — 유력한 2순위
- 발상 전환: 보낼 메시지를 적지 말고 **"end_time은 찍혔는데 아직 IN_PROGRESS인 세션"을 주기적으로 쿼리해 다시 통보**. §1-1대로라면 이 조건은 **기존 컬럼만으로 판별 가능** — 이전 판이 "플래그 컬럼이 필요하다"고 본 것보다 조건이 유리하다.
- 이미 `SessionTimeoutScheduler`가 *같은 계열*이라 자산 재활용.
- 단점: 통보 재시도 횟수·백오프를 세션 테이블에 얹게 되고(사실상 outbox-lite), 도메인 쿼리에 정합성 로직이 섞이며, 통보 타입이 늘면(향후 다른 이벤트) 확장이 안 된다.
- **본질 차이**: outbox=**로그 기반**(보낼 것을 명시 기록, 순서·타입 확장 쉬움) vs 리컨실리에이션=**상태 기반**(현재 상태 차이를 역산, 테이블 안 늘지만 도메인에 결합).

### D. 메시지 브로커 — 오해 주의
- "Kafka/SQS 쓰면 되잖아"는 **dual-write를 안 푼다**. "DB commit + 브로커 enqueue"가 여전히 두 시스템. **브로커는 보통 outbox가 *공급*하는 하류**(outbox→Debezium→Kafka).
- **층위가 다름(경쟁 아님)**: outbox=*패턴*, Kafka=*인프라*. 본 프로젝트는 통보 대상이 **FastAPI 단일·팬아웃 소비자 없음** → 브로커 자리를 gRPC 직접 호출이 대신(브로커 생략이 right-sizing). 면접 답: **"dual-write는 브로커가 아니라 outbox가 푼다."**

### E. 2PC/XA — 왜 안 하나
- FastAPI/gRPC가 XA 참여자가 아니고, blocking·코디네이터 SPOF·운영 복잡도로 현업에서 기피. "왜 2PC 대신 outbox인가"는 좋은 면접 소재(채택은 안 함).

### F. 현행 유지 — 정직 baseline
- 타임아웃 스케줄러가 orphan을 FAILED로 **수렴은 시킨다**. 하지만 §1-1대로 그건 "올바른 결과 회수"가 아니라 **"포기 처리"** — 실제 한 운동이 FAILED로 묻힌다. E1을 *진짜로* 푸는 게 아니다.

---

## 3. 추천 — 순수 A(Outbox) + 내장 FAILED 처리. C는 확장 옵션

| | |
|---|---|
| **1차** | **A 트랜잭셔널 Outbox + 폴링 발행기만.** 헤드라인 직결·교과서·exactly-once 완성. **메인 경로 단일화.** |
| **내장** | 발행기가 `retry_count > N`이면 **`status=FAILED` + 로그/지표** 한 줄. 별도 잡 없이 독(poison) 메시지·영구 실패 처리. |
| 확장 옵션 | C(별도 리컨실리에이션 잡)는 **"여기서 더 가면"으로 문서에만**(§3-1). 구현 보류. |
| 안 함 | D(브로커)·E(2PC) — 도메인 규모 대비 과설계, 개념 언급만. |

### 3-1. 왜 A+C 결합을 *1차에서* 안 하나 (정직)

- **책임 경계 흐려짐**: 발행기(A)와 리컨실리에이션(C)이 같은 세션을 동시에 통보 → 중복 폭증 위험.
- **진실의 출처 이중화**: A는 `outbox_events.status`, C는 도메인 상태를 봄. 둘이 어긋나면 정합성 풀려다 새 정합성 문제.
- **표면적·테스트 2배**: 신입 한 카드치고 과설계로 보일 위험(right-sizing 위반).

**C가 실제 값을 하는 구간은 "독 메시지/영구 실패" 하나뿐**인데, 그건 위 *내장 FAILED 처리* 한 줄로 더 싸게 해결된다.

| 장애 상황 | A만으로 충분? |
|---|---|
| FastAPI 일시 장애(초~분) | ✅ 발행기 재시도로 해결 |
| **서킷 OPEN 구간**(§1-2 ②) | ✅ 스킵 대신 PENDING 유지 → 서킷 닫히면 전달 |
| FastAPI 장기 장애 | ✅ PENDING 누적 → 복구 시 전송 |
| 독 메시지(영영 실패) | ⚠️ *내장 FAILED+지표*로 처리. 별도 C 불필요 |
| **AI 프로세스 재시작**(§3-2) | ❌ **outbox로도 못 고침** |

### 3-2. ⚠️ outbox가 못 고치는 것 — 반드시 같이 말할 한계

AI의 세션 상태는 **프로세스 in-memory**다(`session_state.py`, 무-TTL 무-영속). 그래서:

```
endSession → outbox INSERT ✅   (신호는 안전하게 보존됨)
       ⋮   AI 프로세스 재시작 (배포·OOM·크래시)
발행기 재전송 → StopAnalysis 도착
       → get_registry().remove(sid) 가 None      (exercise_servicer.py:107)
       → StopResponse(success=false, "진행 중인 세션을 찾을 수 없습니다")
       → CompleteAnalysis 는 영영 안 옴 → 결과는 그대로 유실
```

**outbox는 "신호의 전달"을 보장할 뿐 "AI 분석 상태의 내구성"을 주지 않는다.** 이건 outbox의 결함이 아니라 **경계**이고, 면접에서 먼저 말하면 오히려 강점이다(보강하려면 AI 측 상태 영속화 = 별도 카드). 또한 실무적으로 **`success=false`를 발행기가 어떻게 볼지**가 설계 결정이 된다(§7).

---

## 4. 설계 (A 채택 가정 — 미확정)

### 4-1. 테이블

프로젝트 스키마 컨벤션 준수: snake_case, **업무 시각은 `DATETIME`·`created_at`만 `TIMESTAMP DEFAULT CURRENT_TIMESTAMP`**(`schema.sql`의 `exercise_sessions:69-79` 패턴), BIGINT PK.

```sql
-- 11. 아웃박스 (트랜잭셔널 메시징 — 통보 유실 방지)
CREATE TABLE IF NOT EXISTS outbox_events (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    aggregate_type VARCHAR(50)  NOT NULL,          -- 'SESSION'
    aggregate_id   BIGINT       NOT NULL,          -- session_id (FK 안 검 — §4-1-1)
    event_type     VARCHAR(50)  NOT NULL,          -- 'STOP_ANALYSIS'
    payload        JSON         NOT NULL,          -- { "sessionId": 42 }
    correlation_id VARCHAR(64)  NULL,              -- §4-4 (1) — 시간·프로세스 경계를 건너는 cid
    status         ENUM('PENDING','SENT','FAILED') NOT NULL DEFAULT 'PENDING',
    retry_count    INT          NOT NULL DEFAULT 0,
    next_retry_at  DATETIME     NULL,              -- 백오프용(선택)
    sent_at        DATETIME     NULL,
    created_at     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_outbox_dispatch (status, next_retry_at)  -- 발행기 폴링 쿼리용
);
```

#### 4-1-1. FK를 안 거는 이유와 그 대가

`aggregate_id`에 `exercise_sessions(id)` FK를 걸면 outbox가 **특정 애그리거트에 종속**되고(다른 이벤트 타입 확장 불가), `deleteSession`(`SessionService:231`) 시 CASCADE로 통보 이력이 함께 사라진다. 그래서 outbox는 관례적으로 FK를 안 건다.

**대가**: 세션이 삭제됐는데 PENDING 행이 남아 **없는 세션에 stop을 쏘는** 케이스가 생긴다. 발행기가 이걸 어떻게 볼지 정해야 한다(§7) — AI가 `success=false`를 주므로 터지진 않지만, 무의미한 재시도로 남는다.

#### 4-1-2. 테이블 성장·정리

`SENT` 행이 무한 누적된다. 세션당 1행 규모라 급하진 않지만 정리 정책은 필요하다. 주의: **소량 반복 DELETE의 파편화 누적은 이 프로젝트에서 아직 미검증**이라([[project_pending_delete_fragmentation_experiment]]) "DELETE로 충분하다"고 단정하지 말 것. 선택지는 (a) 주기 DELETE, (b) `created_at` 기준 파티션 + DROP PARTITION(`pose_data`에서 이미 검증된 패턴), (c) SENT 즉시 삭제(이력 포기).

### 4-2. 코드 변경점 (최소 침습)

| 위치 | 현재 | 변경 |
|---|---|---|
| `SessionService.endSession:206-217` | 트랜잭션 내 `saveAndFlush` + afterCommit `stopAnalysis(id)` | **트랜잭션 본문에서** `outboxRepository.save(STOP_ANALYSIS, id, cid)`. afterCommit 직접호출 제거(또는 best-effort 즉시발송 유지 — §7) |
| 신규 `OutboxPublisher` | — | `@Scheduled(fixedDelayString=...)` → PENDING 조회 → `stopAnalysis` 송신 → SENT / retry++ |
| `ExerciseAnalysisService.stopAnalysis:248-278` | 서킷 스킵 `return`(:259), onError 로그만(:272) | **결과를 발행기에 돌려줘야** 함 — 스킵·에러를 boolean/예외로 표면화해 발행기가 PENDING 유지를 판단 |

> 멱등 수신(`applyComplete:135` / `applyCompleteFromApp:313`)은 **그대로** — at-least-once 중복을 흡수. 손 안 댐.

> ⚠️ `stopAnalysis`는 현재 **비동기 스텁 + 콜백**이라 호출자가 성공/실패를 못 받는다. 발행기가 결과로 상태를 갱신하려면 콜백 → `CompletableFuture`(또는 blocking stub) 전환이 필요하다. **이게 이 카드의 실제 코드 작업 중 가장 큰 덩어리**이므로 착수 전에 인지할 것.

### 4-3. 확장성 경로 (반드시 같이 박제 — [[feedback_industry_level_standard]])

| 단계 | 방식 | 깨지는 지점 → 대응 |
|---|---|---|
| 1차 | 단일 인스턴스 폴링 | OK |
| 수평 확장 | 발행기 다중화 | **여러 발행기가 같은 PENDING 동시 집음 → 중복 송신.** `SELECT … FOR UPDATE SKIP LOCKED` 또는 ShedLock |
| 고트래픽 | 폴링 빈조회·지연 | **CDC(Debezium)로 outbox binlog 스트리밍** → 폴링 제거. 운영 복잡도↑, 현 규모선 보류 |

> 정직 체크: 이 프로젝트엔 **ShedLock 의존성이 없고**, 이미 `@Scheduled`가 3개(`SessionTimeoutScheduler:52`, `PoseDataPartitionScheduler:55`, `JwtBlacklist:13`) 돈다. 즉 **다중 인스턴스 중복 실행(T3)은 outbox가 새로 만드는 문제가 아니라 이미 있는 공통 갭**이다. 발행기만 SKIP LOCKED로 막고 나머지를 방치하면 앞뒤가 안 맞는다 — T3는 한 묶음으로 다룰 것.

### 4-4. 관측성(2026-07-28 완료)과의 접점 — 착수 시 같이 설계할 것

#### (1) correlation id를 outbox 행에 실어야 한다 ⚠️

```
[요청 스레드]  endSession(cid=a3f9c1d20b84)  →  outbox INSERT
       ⋮  (수 초~수 분 뒤, 인스턴스 재시작을 건널 수도 있음)
[발행기 스레드]  PENDING 조회 → stopAnalysis 송신     ← 여기 cid 가 없다
```

발행기는 `@Scheduled` 스레드라 MDC가 비어 있다. **이건 이미 한 번 밟은 함정이다** — Python `threading.Thread`가 ContextVar를 상속하지 않아 `CompleteAnalysis` 콜백이 원 요청과 끊겼던 것([`observability-correlation-id.md §3-3-1`](./observability-correlation-id.md)). outbox는 스레드 경계가 아니라 **시간·프로세스 경계**를 넘으므로 `CorrelationIds.wrap()` 같은 런타임 캡처로는 **원리상 불가능**하고, **행에 저장**해야 한다.

→ 두 층으로 붙인다(둘 다 기존 유틸 재사용, 새 인프라 0):

| 층 | 무엇 | 재사용할 것 |
|---|---|---|
| tick | 발행기 1회 실행 = 하나의 흐름 | `CorrelationIds.startTask("outbox-dispatch")` — `SessionTimeoutScheduler:60`이 확립한 패턴 |
| 행 | 메시지별로 **원 요청의 cid 복원** | `CorrelationIds.withCorrelationId(row.correlationId)` + `withSession(aggregateId)` |

> 이게 outbox의 숨은 이점이기도 하다: 스레드에 매달린 MDC와 달리 **DB에 적힌 cid는 재시작을 견딘다.**

#### (2) `SessionMetrics`에 발행기 지표를 얹는다

`SessionMetrics`(`global/observability/SessionMetrics.java`)가 이미 있어 새 인프라 없이 추가된다. 계수기가 없으면 "PENDING이 조용히 쌓이는 것"을 아무도 모른다 — outbox의 대표적 실패 양상이다.

| 지표(안) | 왜 |
|---|---|
| `shadowfit.outbox.dispatch` (outcome: sent/retry/failed/**skipped-circuit-open**) | 발행 성공률. 서킷 스킵을 따로 태깅해야 §1-2 ②가 지표로 보인다 |
| `shadowfit.outbox.pending` (gauge) | **적체 감시.** 계속 증가 = 발행기가 죽었거나 독 메시지 |
| `shadowfit.outbox.lag` (timer, `created_at`→`sent_at`) | 폴링 간격이 실제 통보 지연에 얼마나 반영되는지(§6과 직결) |

#### (3) 검증 방식도 이미 깔려 있다

`SessionMetricsRecordingTest`(2026-07-28, `service/Exercise/`)가 **진짜 `SimpleMeterRegistry`로 카운트를 assert하는 패턴**을 확립해 뒀다. outbox 지표도 같은 틀로 검증하면 된다 — 새로 만들 게 없다.

---

## 5. 포폴 서사 (왜 이 카드인가)

- 면접 질문 "두 서비스 걸친 상태인데 통보 유실되면요?" → **"트랜잭셔널 outbox로 at-least-once 송신, 기존 멱등 수신과 합쳐 exactly-once. 2PC는 blocking·운영비용으로 기각, 브로커는 dual-write를 못 풀어 outbox가 그 앞단."**
- **한 단계 더**: "서킷브레이커가 열린 구간에서 통보를 아예 버리고 있었고, 서킷(빠른 실패)과 outbox(지연 후 전달)는 대체재가 아니라 보완재였다" — 남의 글에 잘 안 나오는, **자기 코드를 읽어야만 나오는 관찰**이라 차별점([[project_portfolio_benchmark]]).
- **정직 마감**: "outbox로도 AI 프로세스 재시작 시 결과 유실은 못 막는다(§3-2)" — 한계를 먼저 말하는 쪽이 신뢰를 얻는다.
- `failure-modes.md`의 **E1·E2·C4 공통 뿌리(at-most-once 송신)** 를 한 번에 제거.

---

## 6. 측정 (구현 시 — 말 아닌 증거)

- **유실 재현(경로 ①)**: FastAPI를 죽인 채 N세션 종료 → 현행은 통보 N건 소실 / outbox는 PENDING N건 적재 → 복구 후 **전건 SENT 수렴** 관찰.
- **유실 재현(경로 ②, 서킷)**: 연속 실패로 서킷을 OPEN시킨 뒤 종료 → 현행은 `log.warn` 한 줄 남기고 끝(`:258`), outbox는 다음 폴링에 전달. **①보다 이쪽이 데모로 더 선명하다.**
- **결과 회수율**: 유실 재현 후 **최종 세션 상태 분포**(COMPLETED vs FAILED) 비교 — §1-1대로면 현행은 FAILED로 수렴, outbox는 COMPLETED로 회수. E1의 실제 피해를 그대로 보여주는 지표.
- **지연**: 폴링 간격별 통보 p99(간격이 하한).
- **중복 흡수**: 의도적 2회 송신 → 멱등 수신으로 COMPLETED 1회만 반영.

---

## 7. 미결정 (사용자 confirm 필요)

- [ ] **방식 선택**: 순수 A(추천) vs C(리컨실리에이션 — §2-C대로 조건 판별이 기존 컬럼만으로 가능해 이전 판보다 유리해짐) vs A+C 결합
- [ ] afterCommit **즉시 best-effort 송신도 유지**할지(지연↓) vs 순수 outbox만(단순)
- [ ] `stopAnalysis` **비동기 콜백 → 동기/Future 전환 범위**(§4-2 ⚠️) — 이 카드 최대 작업량
- [ ] **(신설)** `StopResponse.success=false`(AI에 세션 상태 없음) 처리 정책: 터미널 `SENT`로 볼지, `FAILED`로 볼지, 재시도할지 — §3-2의 한계와 직결
- [ ] **(신설)** 삭제된 세션의 PENDING 행 처리(§4-1-1) / `SENT` 정리 정책(§4-1-2: DELETE vs 파티션 vs 즉시삭제)
- [ ] 재시도 정책: 고정 간격 vs 지수 백오프(`next_retry_at`), `retry_count > N`의 N값·알람 채널
- [ ] 다중 인스턴스 범위: 1차부터 SKIP LOCKED 넣을지 vs 문서화만 — **넣는다면 기존 스케줄러 3개(T3)와 한 묶음으로**(§4-3)
- [ ] §4-4 반영 여부: `correlation_id` 컬럼 + 발행기 지표 3종을 1차부터 넣을지
- [x] ~~보강 착수 순서에서 이걸 1번으로 둘지 (vs 관측성)~~ → **관측성이 먼저 갔다**(2026-07-28 완료, PR #54). 남은 비교 대상 회복탄력성은 Actuator·Resilience4j·gRPC deadline으로 실질 충족 — **보강 3축 중 실제로 빈 곳은 outbox뿐**이다.

---

## 결정 로그
- 2026-06-12: 문서 작성. 대안 6종(A~F) 비교, **A(Outbox) 추천·C 결합 옵션**. 방식·착수 **미결정**(§7).
- 2026-06-12: 추천 수정 — A+C 결합의 단점 검토 후 **순수 A + 내장 FAILED 처리**로 하향, C는 확장 옵션으로 강등(§3-1).
- 2026-07-29(1차): 줄 번호 갱신 + 관측성 접점(§4-4) 신설.
- 2026-07-29(재작성): 실코드 전면 재대조. **이전 판의 사실 오류 정정** — ① `endSession`은 `status`를 안 바꾼다(피해는 "불일치"가 아니라 **결과 유실 + FAILED 오분류**, §1-1), ② 유실 경로가 `onError` 말고 **서킷 OPEN 스킵**(`:257-260`)까지 둘이다(§1-2), ③ AI 상태가 in-memory 무-TTL이라 **outbox로도 못 고치는 한계**가 있다(§3-2). 추가: DDL 컨벤션(DATETIME) 정정, FK·정리 정책(§4-1-1·4-1-2), `stopAnalysis` 콜백→Future 전환이 최대 작업량(§4-2), T3는 기존 스케줄러 3개와 공통 갭(§4-3). 미결정 2건 신설. **새 결정 아님 — 방식·착수는 여전히 미결정.**
