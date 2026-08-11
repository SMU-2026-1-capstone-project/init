# 신뢰성 있는 비동기 통보 — 세션 종료 통보 유실(E1) 보강

작성일: 2026-06-12 / **전면 재작성: 2026-07-29**(실코드 재대조 — §1 피해 기술 정정, 서킷 스킵 경로 신설, outbox의 한계 명시)
상태: **구현·측정 완료 (2026-07-29, PR #60·#63·#67)** — §7 미결정 8건 확정 → 구현 → §6 실측까지 마침. **"통보 유실 0"은 이제 주장이 아니라 실측이다 — 단 네트워크 단절·서킷 OPEN 시나리오에 한하며, AI 프로세스 재시작 시엔 통보가 전달돼도 분석 결과는 유실된다(§3-2·§6-2)** ([[feedback_user_decides_not_claude]], [[feedback_decision_doc]])
대상: 백엔드(Spring) 신입 포폴 — 헤드라인(세션 분산 정합성) **직접** 강화
관련: [`portfolio-narrative.md`](../portfolio/portfolio-narrative.md)(§1 헤드라인·§3 보강), [`failure-modes.md`](../portfolio/failure-modes.md)(E1·E2·C4·T3), [`observability-correlation-id.md`](./observability-correlation-id.md), [`db-portfolio-roadmap.md`](./db-portfolio-roadmap.md)

---

## 0. 한 줄 목적

> **"사용자는 운동을 끝냈는데, 그 사실을 FastAPI에 알리는 통보가 유실되어 분석 결과가 영영 회수되지 않는 것"을 막는다.**

유실 0(송신 at-least-once) + 멱등 수신(이미 있음) = **effectively exactly-once — 단, "통보의 전달"에 한해서다.**

> ⚠️ 범위를 좁혀 읽을 것. 이 보장은 **AI 가 그 세션 상태를 들고 살아있는 동안**에만 결과 회수까지 이어진다. AI 프로세스가 재시작하면 통보는 정확히 한 번 전달되지만 **분석 결과는 회수되지 않고 세션은 FAILED 로 남는다**(§3-2). 즉 outbox 가 주는 건 *메시지 전달의 exactly-once* 이지 *분석 결과의 exactly-once* 가 아니다. 네트워크 장애와 AI 재시작은 결과가 다른 사건이므로 §6 측정에서도 구분한다.

---

## 1. 문제 — dual-write (실코드 기준, 2026-07-29 재대조)

`endSession` 한 번의 논리 작업이 **두 곳에 쓴다**:

| | 쓰기 대상 | 수단 | 원자성 |
|---|---|---|---|
| write 1 | MySQL (`exercise_sessions.end_time`) | `@Transactional` commit | ✅ |
| write 2 | FastAPI (분석 중단 통보) | gRPC `StopAnalysis` | ❌ |

현재 흐름 (`SessionService.endSession:192-218`):

```text
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

```text
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

```text
endSession → outbox INSERT ✅   (신호는 안전하게 보존됨)
       ⋮   AI 프로세스 재시작 (배포·OOM·크래시)
발행기 재전송 → StopAnalysis 도착
       → get_registry().remove(sid) 가 None      (exercise_servicer.py:107)
       → StopResponse(success=false, "진행 중인 세션을 찾을 수 없습니다")
       → CompleteAnalysis 는 영영 안 옴 → 결과는 그대로 유실
```

**outbox는 "신호의 전달"을 보장할 뿐 "AI 분석 상태의 내구성"을 주지 않는다.** 이건 outbox의 결함이 아니라 **경계**이고, 면접에서 먼저 말하면 오히려 강점이다(보강하려면 AI 측 상태 영속화 = 별도 카드).

#### 3-2-1. ⚠️ 이건 미래 얘기가 아니다 — 지금 이미 무증상으로 새고 있다

`stopAnalysis`의 `onNext`(`ExerciseAnalysisService:265-267`)가 **`value.getSuccess()`를 보지 않는다**:

```java
public void onNext(StopResponse value) {
    cb.onSuccess(...);                         // :266 — success=false 인데 "성공"으로 기록
    log.info("AI 서버 응답: {}", value.getMessage());  // :267 — INFO 한 줄로 끝
}
```

~~AI가 재시작돼 세션을 잃은 상태에서 Stop이 도착하면 `success=false`가 오지만, Spring은 이걸 **정상 응답으로 처리하고 INFO 로그만 남긴다**. 결과는 영영 안 오는데 경보는 어디에도 안 뜬다.~~

> ✅ **2026-08-11 확인 — 이 서술은 낡았다. §7-확정 ④ 의 선행 작업이 이미 반영돼 있다.**
> [`ExerciseAnalysisService.java:478-500`](../../backend/src/main/java/com/shadowfit/service/Exercise/ExerciseAnalysisService.java) 은 지금
> `success=false` 를 **분기해서 다룬다** — `sessionMetrics.aiStopResult("session-missing")` 으로 **지표에 집계**하고
> `log.warn` 으로 남긴다(INFO 아님). 게다가 `possiblyRedelivered` 를 구분해 «회수분 재송신»(첫 송신이 이미
> 처리됐을 수 있다)과 «진짜 유실»을 갈라 놨다([#152](https://github.com/Shadowfit/init/issues/152)).
>
> 🔴 **문서 드리프트의 방향이 평소와 반대다.** 보통은 «문서가 됐다고 하는데 코드가 안 돼 있는» 쪽인데,
> 여기는 **코드가 고쳐졌는데 문서가 «아직 안 됐다» 고 말하고 있었다.** 그래서 이 서술을 근거로
> «무증상 유실이 남아 있다» 는 이슈를 올릴 뻔했다(2026-08-11). 완료 시 원 서술을 지우지 말고
> 위처럼 취소선 + 확인 근거로 남기는 편이 안전하다.

여전히 유효한 것: §3-2 의 한계 자체 — **outbox 는 «통보의 전달»을 보장할 뿐 «AI 분석 상태의 내구성»을 주지 않는다.**
관측은 생겼지만 결과가 회수되지 않는다는 사실은 그대로다.

---

## 4. 설계 (A 확정 — 2026-07-29)

### 4-1. 테이블

프로젝트 스키마 컨벤션 준수: snake_case, **업무 시각은 `DATETIME`·`created_at`만 `TIMESTAMP DEFAULT CURRENT_TIMESTAMP`**(`schema.sql`의 `exercise_sessions:69-79` 패턴), BIGINT PK.

> 📌 **구현 완료(2026-07-29, PR #63).** 아래는 **실제 반영된 DDL**이다. 초안에서 두 군데가 바뀌었고 둘 다 근거가 있다 — `PROCESSING`·`locked_by`·`lock_expires_at` 추가(§4-3-1), 인덱스 2개 → 1개(§4-1-3 실측).

```sql
-- 11. 아웃박스 (트랜잭셔널 메시징 — 통보 유실 방지)
CREATE TABLE IF NOT EXISTS outbox_events (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    aggregate_type  VARCHAR(50)  NOT NULL,          -- 'SESSION'
    aggregate_id    BIGINT       NOT NULL,          -- session_id (FK 안 검 — §4-1-1)
    event_type      VARCHAR(50)  NOT NULL,          -- 'STOP_ANALYSIS'
    payload         JSON         NOT NULL,          -- { "sessionId": 42 }
    correlation_id  VARCHAR(64)  NULL,              -- §4-4 (1) — 시간·프로세스 경계를 건너는 cid
    status          ENUM('PENDING','PROCESSING','SENT','FAILED') NOT NULL DEFAULT 'PENDING',
    retry_count     INT          NOT NULL DEFAULT 0,
    next_retry_at   DATETIME     NULL,              -- 지수 백오프
    locked_by       VARCHAR(64)  NULL,              -- 선점한 발행기 (§4-3-1)
    lock_expires_at DATETIME     NULL,              -- 이 시각이 지난 PROCESSING 은 회수 대상
    sent_at         DATETIME     NULL,
    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_outbox_dispatch (status, next_retry_at)  -- 인덱스는 하나만 — §4-1-3
) COMMENT='트랜잭셔널 아웃박스 — 세션 종료 통보(STOP_ANALYSIS) 전달 보장';
```

#### 4-1-3. ⚠️ 인덱스를 2개 두려다 실측으로 되돌린 기록

초안대로라면 폴링 두 갈래에 각각 인덱스를 둬야 했다 — `(status, next_retry_at)` 과 `(status, lock_expires_at)`. **실제로 올려보니 정반대로 동작했다.**

MySQL 8.0.46 에 DDL 을 적용하고 EXPLAIN 을 떴다:

| | 인덱스 2개 | `(status, lock_expires_at)` 제거 후 |
|---|---|---|
| ① 신규·재시도 (`PENDING`) | `key_len 1`, filtered 40%, `ref` | **`key_len 7`, filtered 100%, `range`+ICP** |
| ② 유실 회수 (`PROCESSING`) | `key_len 1`, filtered 33% | 동일(개선 없음) |

**원인**: 두 인덱스의 **선두 컬럼이 같아서**(`status`) 옵티마이저가 둘을 동등하게 보고 아무거나 고른다. 그러면 두 번째 컬럼을 못 써서 status 로만 훑고 나머지를 필터링한다. 데이터 분포를 바꿔(PENDING 30% → 0.8%) 다시 재도 **수치가 소수점까지 같아** 합성 분포 탓이 아니라 구조 탓임을 확인했다([[project_synthetic_data_distribution_limit]] 때문에 이 확인이 필요했다).

**②가 인덱스를 못 타는 건 감수한다.** `PROCESSING` 행은 "지금 송신 중 + 크래시로 묶인 것"뿐이라 구조적으로 `배치크기 × 발행기수`(수십 건)를 넘지 않아 좁힐 대상이 애초에 없다. 반면 ①은 **AI 장애 시 수천 건까지 쌓이는** 쿼리라 인덱스가 실제로 필요하다. 필요한 쪽만 고쳤고 쓰기 경로의 인덱스 유지 비용도 절반이 됐다.

> 이 프로젝트는 `session_feedback_logs` 에서도 EXPLAIN 근거로 중복 인덱스를 제거한 적이 있다(2026-07-24). 같은 기준을 적용했다.

**에스컬레이션 경로**: `PROCESSING` 적체가 관측되면 두 시각 컬럼을 `visible_at` 하나로 합쳐 `(status, visible_at)` 단일 인덱스로 가는 안이 있다(SQS visibility timeout 모델). 이 안도 EXPLAIN 으로 확인해 뒀다(`key_len 6`, filtered 100%). 다만 컬럼 하나가 두 의미를 겸하게 되고 상태 전이마다 올바르게 갱신해야 해서, 지금은 채택하지 않는다.

> 정직 체크: **이 문제는 리뷰로 걸러지지 않았다.** `.coderabbit.yaml` 의 `path_filters` 가 `mysql/**` 를 통째로 제외하고 있어 DDL 이 리뷰 대상이 아니었다. 직접 MySQL 에 올려 EXPLAIN 을 떠보고서야 발견했고, 설정은 PR #64 로 고쳤다.

#### 4-1-1. FK를 안 거는 이유와 그 대가

`aggregate_id`에 `exercise_sessions(id)` FK를 걸면 outbox가 **특정 애그리거트에 종속**되고(다른 이벤트 타입 확장 불가), `deleteSession`(`SessionService:231`) 시 CASCADE로 통보 이력이 함께 사라진다. 그래서 outbox는 관례적으로 FK를 안 건다.

**대가 — 그런데 생각보다 좁다**: 원래 걱정은 "세션이 삭제됐는데 PENDING 행이 남아 없는 세션에 stop을 쏘는" 케이스였다. 그러나 `deleteSession:230-233`이 **IN_PROGRESS 세션의 삭제를 막는다**(`SESSION_DELETE_NOT_ALLOWED`). PENDING 행이 살아있는 세션은 정의상 아직 IN_PROGRESS라 삭제 자체가 불가능하고, 타임아웃으로 FAILED가 되거나 COMPLETED로 수렴한 **뒤에야** 삭제 가능해진다. 즉 이 케이스는 "독 메시지가 30분 넘게 남아있는 동안 사용자가 그 세션을 지운" 좁은 창에서만 생긴다.

**결정(2026-07-29)**: 별도 처리를 넣지 않는다. 발생해도 AI가 `success=false`를 돌려주므로 아래 §4-2-1의 터미널 처리로 자동 흡수된다 — 전용 분기를 만들 값이 없다.

#### 4-1-2. 테이블 성장·정리

`SENT` 행이 무한 누적된다. 세션당 1행 규모라 급하진 않지만 정리 정책은 필요하다. 선택지는 (a) 주기 DELETE, (b) `created_at` 기준 파티션 + DROP PARTITION(`pose_data`에서 이미 검증된 패턴), (c) SENT 즉시 삭제(이력 포기).

**결정(2026-07-29): (a) 주기 DELETE로 시작.** 당시 **소량 반복 DELETE의 파편화 누적이 미검증**이었으므로 "DELETE로 충분하다"고 단정해 쓰지 않았고, 에스컬레이션 조건을 *"파편화가 관측되면 (b) 파티션으로 전환"* 으로 뒀다. (b)는 경로로만 박제하고 구현하지 않는다. (c)는 실패 조사 시 이력이 없어 기각.

> ✅ **실측 (2026-08-09) — 결정은 유지, 에스컬레이션 조건은 좁힌다.**
> [`loadtest/results/delete-fragmentation-2026-08-09/`](../../loadtest/results/delete-fragmentation-2026-08-09/README.md)
> ([[project_pending_delete_fragmentation_experiment]]). 이 테이블의 행 모양으로 200,000행 고정 · 25,000행/사이클로 쟀다.
>
> | 삭제 모양 | 해당 | 결과 |
> |---|---|---|
> | FIFO (오래된 것부터 통째로) | `SENT` 만 있을 때 | 누적 **없음** — 3회전 내내 1,348페이지, 재구축본보다 11% 조밀 |
> | 구멍 뚫기 (`FAILED` 가 섞여 살아남음) | **실제 정책의 모양** | **+24% 계단 한 번**(1,348→1,668) 후 평탄. 구멍 밀도를 **20배** 올려도 동일 |
>
> 🔴 **그래서 «파편화가 관측되면 전환» 은 이제 쓸 수 없는 조건이다.** 파편화는 **이미 관측됐다** —
> 실제 정책(FAILED 보존)이 구멍 뚫기라 +24% 계단이 실재한다. 그 문구를 그대로 두면 전환 트리거가
> 상시 참이 되어 조건 구실을 못 한다.
>
> **조건을 «누적이 관측되면» 으로 좁힌다.** 계단은 한 번 밟고 멈추고 최악값도 재구축본 대비 +11% 안에
> 묶여 있으므로 전환 사유가 아니다. **점유 페이지가 행 수 대비 단조 증가하는 것**이 관측될 때 (b)로 간다.
>
> ⚠️ **덜 닫힌 것**: 평탄 확인이 2회전(16사이클)의 두 점에 기대고 있다. FIFO 쪽은 3회전까지 봤지만
> 구멍 뚫기 쪽은 더 길게 돌리지 않았다. 그리고 이 지표는 **익스텐트 단위로 양자화**돼 분해능이
> 64페이지(≈4.7%)라, 그 폭 안의 변화는 이 측정으로 못 가른다.

**터미널 `FAILED` 행은 다르게 다룬다(2026-07-29 추가).** `retry_count > 10` 으로 포기했거나 `success=false` 로 종결된 행은 **"사람이 봐야 할 사건"** 이라, SENT 와 같은 주기로 지우면 조사할 근거가 사라진다. 정책:

| 상태 | 보존 | 근거 |
|---|---|---|
| `SENT` | 짧게(예: 7일) 후 삭제 | 정상 처리 이력, 조사 가치 낮음 |
| `FAILED` | **길게(예: 90일) 보존 후 삭제** | 결과 유실 사건의 유일한 기록. 지표(`outbox.dispatch{outcome=failed}`)는 건수만 알려주고 **어느 세션이었는지는 이 행에만 있다** |

FAILED 가 무한 누적되는 건 아니다 — 정상 운영에서 이 상태는 드물어야 하고, **꾸준히 쌓인다면 그 자체가 알람 신호**다(§4-4 (2)의 pending gauge 와 같은 성격).

### 4-2. 코드 변경점 (최소 침습)

| 위치 | 현재 | 변경 |
|---|---|---|
| `SessionService.endSession:206-217` | 트랜잭션 내 `saveAndFlush` + afterCommit `stopAnalysis(id)` | **트랜잭션 본문에서** `outboxRepository.save(STOP_ANALYSIS, id, cid)`. afterCommit 직접호출 제거(또는 best-effort 즉시발송 유지 — §7) |
| 신규 `OutboxPublisher` | — | `@Scheduled(fixedDelayString=...)` → PENDING 조회 → `stopAnalysis` 송신 → SENT / retry++ |
| `ExerciseAnalysisService.stopAnalysis:248-278` | 비동기 스텁 + 콜백, 서킷 스킵 `return`(:259), onError 로그만(:272) | **동기(blocking stub + deadline)로 교체** — 결과를 발행기에 반환값으로 돌려준다(§4-2-1) |

> 멱등 수신(`applyComplete:135` / `applyCompleteFromApp:313`)은 **그대로** — at-least-once 중복을 흡수. 손 안 댐.

#### 4-2-1. `stopAnalysis` 동기 전환 (확정) 및 결과 3분류

현재 stub은 async 하나뿐이라(`getAuthenticatedStub():78`, `ExerciseServiceStub`) 호출자가 성공/실패를 못 받는다. **확정: `CompletableFuture` 래핑이 아니라 blocking stub + deadline으로 간다.**

- 발행기는 `@Scheduled` 스레드라 **블로킹이 문제되지 않는다**. 오히려 순차 처리가 재시도·상태전이를 단순하게 만든다. Future 병렬 발행은 지금 볼륨에 불필요한 복잡도.
- afterCommit 직접호출이 사라지면(§7-확정 ③) **기존 async `stopAnalysis`를 남길 이유가 없다** → 두 버전 공존 없이 그냥 교체. `startAnalysis`의 async 경로는 그대로 둔다(그쪽은 fire-and-forget이 맞음).
- 참고: 이전 판은 이 전환을 "이 카드 최대 작업량"으로 적었으나 **과대평가였다**. `stopAnalysis`는 unary 호출이라 전환이 기계적이고, 실제 최대 덩어리는 **발행기의 재시도·상태전이 로직**이다.

발행기가 판단해야 할 결과는 셋이다:

| 결과 | 조건 | outbox 행 처리 |
|---|---|---|
| 성공 | 응답 `success=true` | `SENT` + `sent_at` |
| 재시도 | gRPC 에러/데드라인, **서킷 OPEN 스킵** | `PENDING` 유지 + `retry_count++` + `next_retry_at` 백오프 |
| **터미널 실패** | 응답 `success=false`(AI에 세션 없음) | **`FAILED`**(재시도 안 함) + 지표 `outcome=ai-session-missing` |

터미널 실패를 `SENT`로 찍지 않는 이유: **실제 결과 유실을 "전송 성공"으로 위장**하기 때문이다. 재시도하지 않는 이유: AI는 그 세션을 영영 모르므로(§3-2) 재시도가 원리상 무의미하다.

### 4-3. 확장성 경로 (반드시 같이 박제 — [[feedback_industry_level_standard]])

| 단계 | 방식 | 깨지는 지점 → 대응 |
|---|---|---|
| 1차 | 단일 인스턴스 폴링 | OK — **단, `FOR UPDATE SKIP LOCKED`는 1차부터 넣는다**(아래) |
| 수평 확장 | 발행기 다중화 | SKIP LOCKED 로 **선점은 나뉘지만 그것만으로는 부족하다** — 소유권 이양이 필요(§4-3-1) |
| 고트래픽 | 폴링 빈조회·지연 | **CDC(Debezium)로 outbox binlog 스트리밍** → 폴링 제거. 운영 복잡도↑, 현 규모선 보류 |

**결정(2026-07-29): SKIP LOCKED는 1차부터, T3(스케줄러 3개)는 별도 카드로 분리.**

이전 판은 "발행기만 SKIP LOCKED로 막고 나머지 스케줄러를 방치하면 앞뒤가 안 맞으니 한 묶음으로 다루라"고 적었으나, **두 문제는 성격이 다르다**:

- 발행기의 `SELECT … FOR UPDATE SKIP LOCKED`는 *중복 실행 가드*가 아니라 **행 단위 작업 분배**다. 인스턴스가 늘면 서로 다른 행을 집어 **오히려 처리량이 는다**. 폴링 쿼리 한 줄 비용이고 MySQL 8이 지원한다.
- 반면 `SessionTimeoutScheduler:52`·`PoseDataPartitionScheduler:55`·`JwtBlacklist:13`의 다중 인스턴스 중복 tick은 **ShedLock 의존성 추가 + 락 테이블**이 필요한 별개 결정이다(현재 의존성 없음).

즉 **T3는 outbox가 새로 만드는 문제가 아니라 이미 있는 공통 갭**이라는 진단은 유효하되, 해법이 달라 한 PR에 묶을 이유가 없다. T3는 별도 카드로 남긴다.

#### 4-3-1. ⚠️ 정정 — SKIP LOCKED 만으로 중복 송신이 막히지 않는다

위 표의 이전 판은 "SKIP LOCKED가 있어 중복 송신 없이 그대로 확장된다"고 적었다. **과장이다.** SKIP LOCKED가 보장하는 건 **"같은 행을 두 트랜잭션이 동시에 선점하지 않는다"** 까지고, 그 뒤가 비어 있다:

```text
발행기 A: SELECT ... FOR UPDATE SKIP LOCKED  → 행 42 선점
발행기 A: COMMIT (또는 트랜잭션 종료)         → 락 해제
발행기 A: gRPC 송신 시작 ... 그 도중 크래시    → 행 42 는 여전히 PENDING
발행기 B: 같은 행 42 를 집어 다시 송신          → 중복
```

락은 **트랜잭션 수명**만큼만 살아있는데, 외부 호출(gRPC)은 그 트랜잭션 밖에서 일어난다. 락을 송신 끝까지 잡고 있으면 DB 커넥션을 외부 I/O 시간만큼 점유하게 되어 그것대로 나쁘다.

**필요한 것: 상태로 표현되는 소유권 + 만료.**

| 단계 | 행 상태 | 비고 |
|---|---|---|
| 선점 | `PENDING` → **`PROCESSING`** (+`locked_by`, `lock_expires_at`) | SKIP LOCKED 로 고른 뒤 **원자적으로 상태 전이하고 커밋**. 여기서 락을 놓는다 |
| 송신 | `PROCESSING` | 트랜잭션 밖. 다른 발행기는 이 상태를 안 집는다 |
| 완료 | → `SENT` / `PENDING`(재시도) / `FAILED` | |
| **크래시** | `lock_expires_at` 경과 → 회수 대상 | 폴링 쿼리가 `PENDING` + **"만료된 `PROCESSING`"** 을 같이 집어 재시도 |

즉 SKIP LOCKED 는 **작업 분배** 도구이고, 중복 방지는 **PROCESSING + lease 만료**가 한다. 둘은 대체재가 아니라 각자 다른 일을 한다.

> ✅ **반영됨(PR #63)**: §4-1 DDL 에 `locked_by`·`lock_expires_at` 이 추가되고 `status` ENUM 에 `PROCESSING` 이 붙었다. 다만 "만료 조회용 인덱스도 함께 둔다"는 부분은 **실측 결과 역효과여서 채택하지 않았다** — §4-1-3 참고.
>
> **추가로 얻은 것 — 경량 펜싱(PR #63 리뷰 반영)**: 상태 전이 쿼리(`markSent`/`markForRetry`/`markFailed`)에 `AND locked_by = :me AND status = 'PROCESSING'` 조건을 걸었다. lease 가 만료돼 다른 발행기가 회수해 간 뒤 원래 발행기가 뒤늦게 깨어나 결과를 쓰면 **새 소유자의 진행을 덮어쓰기** 때문이다(특히 아직 송신 중인 행을 `PENDING` 으로 되돌리면 세 번째 발행기가 또 집어 중복이 번진다). 0 행이 반환되면 발행기는 지표도 올리지 않고 물러난다.
>
> 이건 **중복 송신을 막지 못한다**(그건 이미 나간 뒤다). 막는 것은 **상태 오염**이다. 아래 §3-2 에 적은 "fencing token 없음" 한계가 사라진 건 아니지만, 조건부 갱신(CAS)으로 상당 부분 좁혔다.
>
> 그리고 at-least-once 특성이 여기서 다시 확인된다: 크래시 회수는 **"보냈는지 확실하지 않은 행"** 을 다시 보내는 것이므로 중복이 원리상 남는다. 그래서 수신측 멱등성(§1-3)이 필수 짝이다.

### 4-4. 관측성(2026-07-28 완료)과의 접점 — 착수 시 같이 설계할 것

#### (1) correlation id를 outbox 행에 실어야 한다 ⚠️

```text
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

## 6. 측정 — ✅ 실측 완료 (2026-07-29)

> Docker 로 전체 스택(MySQL + Spring + FastAPI)을 띄우고 실제 HTTP·gRPC 로 세션을 돌려 측정했다.
> AI 를 죽이는 방식이 시나리오를 가른다 — `docker pause` 는 프로세스를 얼리되 **메모리 상태를 보존**하고
> (=네트워크 단절), `docker restart` 는 **상태를 잃는다**(=프로세스 재시작). §3-2 의 한계가 이 차이로 드러난다.

### 6-0. 결과 요약

| 시나리오 | AI 조작 | 아웃박스 행 | 세션 최종 | 판정 |
|---|---|---|---|---|
| **①-a 네트워크 단절** | `pause` (상태 보존) | 25초간 `PENDING` 유지 → 복구 후 **`SENT`** | **`COMPLETED`** | ✅ 유실 0 + 결과 회수 |
| **①-b AI 재시작** | `restart` (상태 소실) | **터미널 `FAILED`** (재시도 0회) | `FAILED` | ✅ 의도대로 — §3-2 한계의 실증 |
| **② 서킷 OPEN** | `pause` 후 서킷 개방 | `PENDING` 유지(**버리지 않음**) → 복구 후 `SENT` | `COMPLETED` | ✅ 이전 설계의 유실 경로 제거 확인 |

### 6-1. ①-a 네트워크 단절 — 유실 0 과 결과 회수

세션 종료 요청은 **HTTP 200, 59~135ms** 로 반환됐다 — AI 가 얼어붙어 있는데도 사용자는 기다리지 않는다(요청 경로에 외부 호출이 없다).

행이 즉시 생성됐고 **`correlation_id` 에 원 요청의 cid 가 실렸다**(`9f969c7c664a`). 이후 AI 가 멈춰 있는 동안:

```text
 8초  PROCESSING  retry_count=3
16초  PENDING     retry_count=4   다음 시도 14초 뒤
24초  PENDING     retry_count=5   다음 시도 17초 뒤
40초  PENDING     retry_count=6   다음 시도 37초 뒤
```

**백오프가 8s → 16s → 32s 로 정확히 2배씩** 늘었고 행은 한 번도 사라지지 않았다. AI 복구 후 **10초 안에 `SENT`**, 세션은 **`COMPLETED`** 로 수렴했다.

### 6-2. ①-b AI 재시작 — 결과가 달라야 정상이다

AI 를 재시작해 세션 상태를 잃게 한 뒤 종료했더니, 발행기는 **재시도 없이(`retry_count=0`) 즉시 터미널 `FAILED`** 로 종결했다. `success=false` 는 재시도가 원리상 무의미하므로 옳은 동작이다.

세션도 **6초 만에 `FAILED`** 가 됐다. 이전 설계라면 타임아웃 스케줄러(`시작시간+예상시간+30분`)를 기다려야 했다 — PR #60 의 즉시 실패 처리가 실측으로 확인된 셈이다.

**①-a 와 결과가 다른 것이 이 문서의 §3-2 한계 그 자체다.** 통보 전달은 **at-least-once**(크래시 회수 시 재전송이 남으므로 — §4-3-1)이고, 멱등 수신과 합쳐야 비로소 *처리*가 effectively-once 가 된다. 그리고 그 어느 쪽도 **분석 결과의 내구성을 주지는 않는다** — 전달과 결과 보존은 별개 축이다.

### 6-3. ② 서킷 OPEN — 이전 설계에서 통보를 버리던 구간

연속 실패로 서킷을 열었다(설정: `minimumNumberOfCalls=5`, `failureRateThreshold=50%`).

```text
 7초  CLOSED    failed=1   아웃박스 PROCESSING
14초  CLOSED    failed=2
21초  CLOSED    failed=3
28초  OPEN      failed=4   아웃박스 PENDING  ← 서킷 개방
```

서킷이 열린 뒤에도 행은 `PENDING` 으로 살아 있었고(`retry_count=6`에서 정지), 백엔드 로그엔 이렇게 남았다:

```text
WARN [fit-scheduler-3] [41318f1afbd1|805] ExerciseAnalysisService :
  AI 서버 서킷브레이커 OPEN — 중단 요청 보류 (세션 ID: 805)
```

**이전 설계에서는 바로 이 지점이 유실의 끝이었다** — `log.warn` 한 줄 남기고 `return` 했다. 지금은 "보류"이고, AI 복구 후 **10초 안에 `SENT` + `COMPLETED`** 로 이어졌다.

> 로그에 cid(`41318f1afbd1`)와 sessionId(`805`)가 함께 찍힌 것도 확인됐다 — 발행기가 행에 저장된 cid 를 복원해 원 요청과 이어붙인다(§4-4).

### 6-4. ⚠️ 측정 중 발견한 별개 버그

첫 측정에서 아웃박스는 `SENT` 인데 세션이 `COMPLETED` 로 가지 않았다. 원인은 아웃박스가 아니라 **`reports` 테이블에 `updated_at` 컬럼이 없어** 리포트 INSERT 가 실패한 것이었다. 리포트 생성이 세션 완료와 같은 트랜잭션이라 세션 COMPLETED 까지 롤백됐고, **모든 세션이 FAILED 로 수렴하고 있었다**(이슈 #66, PR #67 로 수정). 위 표의 결과는 그 수정 후 재측정한 값이다.

> 이게 **E2E 측정을 실제로 돌려본 값어치**다 — 단위 테스트와 코드 리뷰로는 안 잡혔다.

### 6-5. 아직 안 한 것

- **지연 p99**: 폴링 간격별 통보 지연 분포. 단건 측정만 했고 분포는 안 냈다
- **중복 흡수**: 의도적 2회 송신 → 멱등 수신으로 COMPLETED 1회만 반영. 코드상 보장되지만 실측은 안 함
- **부하 상태에서의 거동**: 위는 전부 단일 세션 기준이다. 다건 동시 적체·다중 발행기는 미측정

---

### 6-6. 원래 측정 계획 (참고)

- **유실 재현(경로 ①-a, 네트워크 단절)**: FastAPI **프로세스는 살려둔 채** 연결만 끊고 N세션 종료 → 현행은 통보 N건 소실 / outbox는 PENDING N건 적재 → 복구 후 **전건 SENT + COMPLETED 수렴** 관찰. AI 가 세션 상태를 그대로 들고 있으므로 결과까지 회수된다.
- **유실 재현(①-b, AI 프로세스 재시작)**: FastAPI를 **재시작**한 뒤 종료 → outbox는 SENT 가 아니라 **터미널 `FAILED`(`outcome=ai-session-missing`)로 수렴**하고 세션은 FAILED 로 남는다. **①-a 와 결과가 다른 게 정상이며, 이 차이가 §3-2 한계의 실측 증거다.** 둘을 섞어 "전건 SENT" 로 뭉뚱그리지 말 것.
- **유실 재현(경로 ②, 서킷)**: 연속 실패로 서킷을 OPEN시킨 뒤 종료 → 현행은 `log.warn` 한 줄 남기고 끝(`:258`), outbox는 다음 폴링에 전달. **①보다 이쪽이 데모로 더 선명하다.**
- **결과 회수율**: 유실 재현 후 **최종 세션 상태 분포**(COMPLETED vs FAILED) 비교 — §1-1대로면 현행은 FAILED로 수렴, outbox는 COMPLETED로 회수. E1의 실제 피해를 그대로 보여주는 지표.
- **지연**: 폴링 간격별 통보 p99(간격이 하한).
- **중복 흡수**: 의도적 2회 송신 → 멱등 수신으로 COMPLETED 1회만 반영.

---

## 7. 결정 완료 (2026-07-29 · 사용자 confirm) — 전건 확정

- [x] **① 방식 선택** → **순수 A(Outbox)**. C는 *기술적으로는* 이 단일 이벤트에 충분하다(§2-C: 판별 조건이 기존 컬럼만으로 성립, 재통보도 멱등). 그럼에도 A를 고른 이유는 (a) 이벤트 타입이 하나만 늘어도 C는 도메인 쿼리를 또 짜야 하고, (b) "트랜잭셔널 outbox"는 채점되는 패턴명이지만 "리컨실리에이션 잡"은 애드혹으로 읽힌다. **C가 기술적으로 밀리지 않는다는 사실은 문서에 남긴다** — "왜 더 싼 C를 안 골랐나"에 답할 수 있는 게 오히려 강점.
- [x] **② `stopAnalysis` 전환 범위** → **blocking stub + deadline으로 동기 교체**(§4-2-1). Future 병렬 발행은 현 볼륨에 불필요. async 버전은 남기지 않고 교체(afterCommit 경로가 사라지므로 공존할 이유 없음). **이전 판의 "최대 작업량" 평가는 과대평가로 정정** — unary라 전환이 기계적이고, 실제 최대 덩어리는 발행기 재시도·상태전이 로직이다.
- [x] **③ afterCommit 즉시 best-effort 송신** → **유지하지 않음(순수 outbox).** 유지하면 즉시송신과 발행기가 같은 행을 두고 경쟁해 결과 처리를 두 곳에 짜야 한다. 지연 우려는 `fixedDelay`를 **1초**로 두면 소멸한다 — 사용자가 이 통보를 기다리며 블록되지 않기 때문.
- [x] **④ `StopResponse.success=false` 처리** → **터미널 `FAILED` + 지표 `outcome=ai-session-missing`**(재시도 안 함, §4-2-1). `SENT`로 찍으면 실제 유실을 전송 성공으로 위장한다. **추가 확정: 이건 outbox 이전에 이미 있는 무증상 버그**(§3-2-1 — `onNext`가 `getSuccess()`를 안 읽음)라 **선행 소품 PR로 분리**해 먼저 고친다.
- [x] **⑤ 삭제 세션 PENDING / `SENT` 정리** → 삭제 세션은 **별도 처리 없음**(§4-1-1: `deleteSession`이 IN_PROGRESS 삭제를 막아 케이스가 좁고, ④가 흡수). `SENT`는 **주기 DELETE로 시작**하고 파티션은 에스컬레이션 경로로 박제(§4-1-2). ~~파편화 미검증을 이유로 "충분하다"고 단정하지 않음~~ → **2026-08-09 실측 완료**(§4-1-2 후속): 결정은 유지하되 에스컬레이션 조건이 «파편화가 관측되면» → **«누적이 관측되면»** 으로 좁혀졌다. 계단(+24%)은 이미 관측됐고 그건 전환 사유가 아니다.
- [x] **⑥ 재시도 정책** → **지수 백오프**(`next_retry_at`, 1s→2s→4s…, **상한 5분**), **`retry_count > 10` → `FAILED` + 지표**. 서킷브레이커가 빠른 실패를 담당하므로 발행기는 느긋한 백오프가 맞다. **별도 알람 채널은 만들지 않고 지표(⑧)로 대체** — 지금 알람 인프라가 없다.
- [x] **⑦ 다중 인스턴스 범위** → **`FOR UPDATE SKIP LOCKED`는 1차부터 포함, T3(스케줄러 3개)는 별도 카드로 분리**(§4-3). 발행기 SKIP LOCKED는 중복 가드가 아니라 행 단위 작업 분배(쿼리 한 줄, 확장 시 처리량 이득)이고, 스케줄러 중복 tick은 ShedLock 의존성이 필요한 별개 문제라 한 PR에 묶을 이유가 없다.
- [x] **⑧ §4-4 반영** → **`correlation_id` 컬럼 + 지표 3종 모두 1차부터.** 컬럼 하나 비용이고, "MDC는 스레드에 매달려 죽지만 **DB에 적힌 cid는 재시작을 견딘다**"가 관측성 작업(PR #54)과 이 카드를 잇는 서사다. 셋 중 **`outbox.pending` gauge가 최우선** — 없으면 적체를 아무도 모른다.
- [x] ~~보강 착수 순서에서 이걸 1번으로 둘지 (vs 관측성)~~ → **관측성이 먼저 갔다**(2026-07-28 완료, PR #54). 남은 비교 대상 회복탄력성은 Actuator·Resilience4j·gRPC deadline으로 실질 충족 — **보강 3축 중 실제로 빈 곳은 outbox뿐**이다.

### 7-1. 구현 순서 (확정 결과 요약)

| 순서 | 내용 | 상태 |
|---|---|---|
| 0 (선행 소품) | `onNext`에서 `getSuccess()` 판별 → `log.warn` + 지표 + 즉시 FAILED | ✅ **PR #60** (이슈 #58) |
| 1 | `outbox_events` 테이블 + 엔티티/리포지토리 | ✅ PR #63 |
| 2 | `endSession` afterCommit 제거 → 트랜잭션 내 outbox INSERT(cid 포함) | ✅ PR #63 |
| 3 | `stopAnalysis` blocking 전환 + 결과 3분류 반환 | ✅ PR #63 |
| 4 | `OutboxPublisher`(폴링 1s · SKIP LOCKED · 지수 백오프 · 터미널 FAILED) | ✅ PR #63 |
| 5 | 지표 3종 + `SessionMetricsRecordingTest` 패턴 검증 | ✅ PR #63 |
| 6 | §6 측정(유실 재현 ①②, 결과 회수율) | ✅ **완료** — 결과는 §6 |

**0번에서 정정된 것**: "서킷 오분류 수정"으로 적어뒀으나 **`cb.onSuccess()` 는 원래 맞는 동작**이었다. 서킷이 판단하는 건 "AI 서비스가 살아있나"이지 "이 세션이 있었나"가 아니고, 세션을 잃은 AI 도 새 분석은 정상 처리하므로 여기서 서킷을 열면 신규 `startAnalysis` 까지 막혀 더 나빠진다. 실제로 한 건 `getSuccess()` 판별 + WARN + 지표 + 즉시 FAILED 뿐이다.

**구현 중 실측으로 바뀐 것 2가지** — 둘 다 착수 전엔 몰랐던 것이다:
- 인덱스 2개 → 1개 (§4-1-3). 선두 컬럼이 같아 옵티마이저가 구분하지 못했다
- 상태 전이에 소유권 조건(CAS) 추가 (§4-3-1 하단). lease 를 잃은 발행기가 새 소유자의 상태를 덮어쓰는 경로가 있었다

---

## 결정 로그
- 2026-06-12: 문서 작성. 대안 6종(A~F) 비교, **A(Outbox) 추천·C 결합 옵션**. 방식·착수 **미결정**(§7).
- 2026-06-12: 추천 수정 — A+C 결합의 단점 검토 후 **순수 A + 내장 FAILED 처리**로 하향, C는 확장 옵션으로 강등(§3-1).
- 2026-07-29(1차): 줄 번호 갱신 + 관측성 접점(§4-4) 신설.
- 2026-07-29(재작성): 실코드 전면 재대조. **이전 판의 사실 오류 정정** — ① `endSession`은 `status`를 안 바꾼다(피해는 "불일치"가 아니라 **결과 유실 + FAILED 오분류**, §1-1), ② 유실 경로가 `onError` 말고 **서킷 OPEN 스킵**(`:257-260`)까지 둘이다(§1-2), ③ AI 상태가 in-memory 무-TTL이라 **outbox로도 못 고치는 한계**가 있다(§3-2). 추가: DDL 컨벤션(DATETIME) 정정, FK·정리 정책(§4-1-1·4-1-2), `stopAnalysis` 콜백→Future 전환이 최대 작업량(§4-2), T3는 기존 스케줄러 3개와 공통 갭(§4-3). 미결정 2건 신설. **새 결정 아님 — 방식·착수는 여전히 미결정.**
- 2026-07-29(**결정**): 착수 전 실코드 재확인에서 **문서를 넘는 사실 3건** 확인 — ⓐ `onNext:265-267`이 `getSuccess()`를 안 읽어 `success=false`가 **지금도 무증상으로 새고 있다**(§3-2-1 신설), ⓑ `deleteSession:230-233`이 IN_PROGRESS 삭제를 막아 **삭제 세션 PENDING 케이스가 문서보다 훨씬 좁다**(§4-1-1 정정), ⓒ `stopAnalysis`가 unary라 **동기 전환은 기계적** — "최대 작업량"은 과대평가였고 실제 덩어리는 발행기 로직이다(§4-2-1 정정). 이 위에서 **§7 미결정 8건 전건 확정**(사용자 confirm): 순수 A / blocking 동기 전환 / best-effort 미유지 / `success=false`는 터미널 FAILED / 삭제세션 무처리·SENT 주기 DELETE / 지수 백오프·retry>10 FAILED·알람은 지표로 대체 / SKIP LOCKED 1차 포함·T3 분리 / cid 컬럼·지표 3종 1차 포함. **구현 순서는 §7-1**, 선행 소품(ⓐ 수정)을 0번으로 분리. 상태: 분석/추천 → **결정 완료·착수 전**.
- 2026-07-29(**구현 완료**): PR #60(선행) → PR #63(본체) 머지. 문서를 실제 코드 기준으로 동기화 — §4-1 DDL 을 반영본으로 교체, §4-1-3(인덱스 실측 기록) 신설, §4-3-1 에 경량 펜싱(CAS) 추가, §7-1 진행 상태 갱신. **착수 전 설계에서 실측으로 바뀐 것 2건**: ① 인덱스 2개는 역효과라 1개로(선두 컬럼 중복), ② 상태 전이에 `locked_by` 조건을 걸어 lease 상실 시 상태 오염 차단. 남은 것은 §6 측정뿐이며, 그 전까지 "유실 0"은 **설계상 주장이지 실측이 아니다**.
- 2026-07-29(**측정 완료**): Docker 로 전체 스택을 띄워 §6 실측. ①-a 네트워크 단절(`docker pause`, 상태 보존) → 25초 적체 후 `SENT`+`COMPLETED`, 백오프 8→16→32s 확인. ①-b AI 재시작(상태 소실) → 재시도 0회로 터미널 `FAILED`(§3-2 한계의 실증). ② 서킷 OPEN → 이전 설계가 버리던 구간에서 행이 살아남아 복구 후 전달. **측정 중 별개 버그 발견** — `reports.updated_at` 누락으로 모든 세션 완료가 롤백되고 있었다(#66, PR #67). 단위 테스트·리뷰로는 안 잡히던 것이라, E2E 측정을 실제로 돌린 값어치가 여기서 나왔다. 미측정 잔여: 지연 p99 분포, 중복 흡수 실측, 다건·다중 발행기 거동(§6-5).
- 2026-07-29(리뷰 반영): PR #65 CodeRabbit 지적 3건 수정. **문서가 자기 내용과 모순되던 곳 2건** — ① 헤더의 "유실 0"이 모든 장애를 포함하는 것처럼 읽혔다(①-b 에선 분석 결과가 실제로 유실된다). ② "통보 전달은 exactly-once"라고 썼는데 §4-3-1 대로 크래시 회수 시 재전송이 남으므로 **at-least-once** 가 맞고, 멱등 수신과 합쳐야 *처리*가 effectively-once 다. ③ §4-1 DDL 스니펫에 실제 스키마의 테이블 COMMENT 누락. **새 결정 없음 — 표현 정확도 수정.**
