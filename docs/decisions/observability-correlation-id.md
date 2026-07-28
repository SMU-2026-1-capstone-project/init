# 관측성 1차 — correlation id 전파 + 커스텀 메트릭

작성일: 2026-07-28
상태: **구현 완료 (1차)** — 5-1~5-5 + 커스텀 메트릭. JSON 구조화·분산추적은 의식적 제외(§6)
관련: [`portfolio-narrative.md §3·§6`](../portfolio/portfolio-narrative.md), [`27-implementation-gaps.md §3`](../tasks/27-implementation-gaps.md), [`production-signal-checklist.md`](./production-signal-checklist.md), [`outbox-reliable-messaging.md`](./outbox-reliable-messaging.md)

---

## 1. 문제 — 로그는 많은데 서로 안 엮였다

착수 전 상태: `log.` 호출 70여 건, `MDC`/`correlationId` 검색 결과 **0건**, `logback-spring.xml` **없음**(Boot 기본 콘솔 패턴).

세션 하나가 끝나는 경로가 **스레드 4~5개 + 프로세스 2개**에 걸쳐 있다:

```text
HTTP 요청(톰캣) → @Async(워커풀) → gRPC 송신 → FastAPI(별도 프로세스)
                                                    ↓
   Spring ← gRPC 콜백(grpc-server 스레드) ← 분석 완료
      +
   SessionTimeoutScheduler(스케줄러 스레드)가 같은 세션을 동시에 건드림
```

여기서 실제로 막히던 것 3가지:

| # | 증상 | 근거 |
|---|---|---|
| ① | 동시 세션이 섞이면 못 읽음 | `ExerciseAnalysisService:217` `log.error("gRPC 통신 장애: {}")` 에 **세션 ID조차 없음**. 스레드명으로 위쪽 로그와 잇는 건 추측이고, 스레드는 풀에서 재사용되므로 그 추측도 안전하지 않음 |
| ② | 두 서비스 로그가 단절 | AI 쪽 `exercise_servicer.py:104` 는 session_id 로만 찍힘. 세션 ID는 **긴 수명 식별자**(세션 1건에 요청 수십 건)라 "이 세션의 3번째 재시도"를 구분 못 함 |
| ③ | **헤드라인 서사가 관측 불가** | 포폴 §1이 "스케줄러 ↔ AI 콜백이 같은 세션을 두고 경쟁"인데, `ExerciseAnalysisService:282` "완료 처리 충돌 - 재시도 1/3"이 찍혔을 때 **상대가 누구였는지**가 안 남음 |

> ③이 핵심. "동시성을 낙관락으로 풀었다"는 주장이 코드와 재현 실험으로만 증명돼 있었고, **운영 중 실제로 그 경쟁이 일어난 순간을 짚을 수단이 없었다.**

---

## 2. 무엇을 했나

### 2-1. correlation id 전파 (5단계)

| 단계 | 내용 | 산출물 |
|---|---|---|
| 진입점 발급/수용 | HTTP `X-Request-Id` 수용 또는 생성, 응답에도 반환 / gRPC metadata `x-request-id` 수용 | `CorrelationIdFilter`, `GrpcCorrelationServerInterceptor` |
| MDC + 로그 패턴 | `%X{cid}` `%X{sessionId}` 노출 | `logback-spring.xml` |
| **스레드 경계** | `@Async` TaskDecorator, gRPC 콜백 옵저버 데코레이터 | `AsyncConfig`, `CorrelationIds.wrap/preserving` |
| 프로세스 경계 | Spring→AI 송신, AI 수신·재송신 | `GrpcCorrelationClientInterceptor`, `ai-server/app/grpc/correlation.py` |
| 원점 없는 흐름 | 스케줄러 tick 자체 발급 | `SessionTimeoutScheduler`, `PoseDataPartitionScheduler` |

**기존 `log.info(...)` 호출은 한 줄도 고치지 않았다.** MDC + 패턴 방식의 이점이 정확히 이것.

로그 출력 형태 (실제 테스트 실행 로그):
```text
2026-07-28 00:34:59.297  INFO [ionShutdownHook] [·|·] com.zaxxer.hikari.HikariDataSource : ...
                                                 ↑ cid|sessionId, 값 없으면 ·
```

### 2-2. 커스텀 메트릭 (`SessionMetrics`, Actuator 노출)

| 지표 | 태그 | 왜 이것인가 |
|---|---|---|
| `shadowfit.session.transitions` | status, source | 같은 FAILED라도 `timeout-scheduler` / `circuit-open` / `grpc-error` 는 운영상 완전히 다른 사건 |
| `shadowfit.session.optimistic.lock.conflicts` | source, outcome(retry/yield/exhausted) | **§1-③ 의 직접 해답** — 경쟁 빈도를 집계로 관측 |
| `shadowfit.pose.batch.frames` | stage(received/stored) | 실측 다운샘플 비율(R≈5)이 운영 중 유지되는지 ([`pose-ingest-downsampling.md`](./pose-ingest-downsampling.md)) |

의존성 추가 없음 — actuator·micrometer가 이미 있었고 `management.endpoints` 노출 설정도 기존 그대로.

---

## 3. 설계 결정과 근거

### 3-1. 필터를 Security 체인이 아니라 서블릿 최상위에 등록
`@Component + @Order(HIGHEST_PRECEDENCE)`. Security 체인(기본 order −100)보다 먼저 돌아 **인증 실패 응답·Actuator·에러 페이지까지** cid를 갖는다. 체인 안에 넣으면 인증 거부 로그가 cid 없이 남는 사각지대가 생김. (결과적으로 `SecurityConfig` 는 수정 불필요 — 계획 대비 변경 파일이 하나 줄었다.)

### 3-2. gRPC 인터셉터는 전역 등록 + 최고 우선순위
`@GrpcGlobalServerInterceptor` / `@GrpcGlobalClientInterceptor`. 서비스별 `@GrpcService(interceptors=...)` 나열 대신 전역으로 둔 이유: (1) 새 gRPC 서비스가 생겨도 자동 적용, (2) `InternalAuthInterceptor` 보다 먼저 돌아 **인증 거부된 호출의 로그에도** cid가 남음. (Python 쪽도 대칭으로 `CorrelationServerInterceptor` 를 `AuthInterceptor` 앞에 배치.)

### 3-3. 리스너·옵저버를 감싸는 이유 — 이게 진짜 어려운 부분
MDC는 `ThreadLocal`, Python `ContextVar` 는 스레드마다 독립. **인터셉터가 도는 스레드와 핸들러가 도는 스레드가 다르다.**

- **Java 서버**: `interceptCall` 에서 MDC.put만 하면 정작 서비스 메서드가 도는 grpc-server 워커에는 아무것도 없음 → 리스너 콜백(`onHalfClose` 등)마다 세우고 되돌림
- **Java 클라이언트 콜백**: 비동기 스텁의 `onNext/onError` 는 gRPC 이벤트 루프 스레드 → `CorrelationIds.preserving()` 으로 호출 시점 MDC를 캡처해 복원. **감싸지 않으면 가장 중요한 `onError` 실패 로그에 cid가 안 붙는다**
- **`@Async`**: 복사는 제출 시점(부모 스레드), 복원은 실행 시점(워커). 이 타이밍을 뒤집으면 항상 비어 있음
- **Python 핸들러**: 인터셉터에서 set하지 않고 **핸들러 함수 자체를 감싸서** 핸들러가 도는 그 스레드 안에서 set/reset
- **Python 백그라운드 스레드**: `threading.Thread` 는 부모 `ContextVar` 를 **상속하지 않는다**(상속하려면 `contextvars.copy_context()`) → `correlation.wrap()` 으로 감싸 부모 스레드에서 캡처·워커에서 복원. `@Async` 와 동일한 발상 (§3-3-1)

전부 "복원 후 원상복구"를 `finally` 로 강제 — 워커 스레드는 재사용되므로 누수되면 다음 요청에 이전 cid가 새어나간다.

#### 3-3-1. 1차 구현에서 놓쳤던 구멍 (CodeRabbit 리뷰로 발견, 같은 PR에서 수정)

위 4가지 중 **Python 백그라운드 스레드만 1차에서 빠져 있었다.** `StopAnalysis` 핸들러가 `threading.Thread` 로 `_send_complete_analysis` 를 띄우는데, 그 스레드는 컨텍스트가 비어서 `correlation_metadata()` 가 매번 새 고아 id를 발급하고 있었다.

하필 **가장 아픈 자리**였다 — 이 콜백이 곧 §1-③의 "스케줄러와 경쟁하는 AI 콜백"(`CompleteAnalysis`) 본인이라, 정작 증명하려던 경로에서 추적이 끊겨 있었다. 게다가 `report_complete_analysis` 가 재시도 루프 **안에서** metadata를 만들고 있어 같은 작업의 3번 시도에 서로 다른 id가 붙었다.

수정 3가지:
1. `correlation.wrap()` 신설 + 스레드 target에 적용 — 캡처는 부모, 복원은 워커
2. `ensure_correlation_id()` — fallback 발급 시 **컨텍스트에 심는다**. 발급만 하고 버리면 metadata의 id와 그 스레드 로그의 `·` 가 안 이어짐
3. metadata를 재시도 루프 **밖에서** 1회 생성

> 교훈: "스레드 경계를 다 감쌌다"는 주장은 **경계를 다 열거했을 때만** 참이다. Java 3종(`@Async`·gRPC 서버 리스너·클라이언트 옵저버)은 세어놓고 Python의 raw `threading.Thread` 는 세지 않았다. `test_unwrapped_thread_loses_id` 는 감싸지 않으면 실제로 끊긴다는 걸 못 박아, 이 구멍이 다시 열리면 테스트가 깨지게 했다.

### 3-4. try-with-resources 를 catch 바깥에 둔 이유
**자원은 catch 블록보다 먼저 닫힌다.** 한 겹으로 합치면 정작 실패 로그에서 cid/sessionId가 빠진다 — 가장 필요한 자리에서. 그래서 스코프를 바깥, `try/catch` 를 안쪽에 두는 형태로 통일했다 (`ExerciseGrpcService` 3개 메서드, 두 스케줄러).

### 3-5. 인바운드 id sanitize — 로그 인젝션 방어
외부에서 온 `X-Request-Id` / metadata를 그대로 MDC에 넣으면 **개행을 섞어 가짜 로그 줄을 만들 수 있다.** 화이트리스트 `[A-Za-z0-9_.:-]{1,64}` 를 벗어나면 버리고 새로 발급 (Java·Python 동일 규칙).

### 3-6. cid와 sessionId를 둘 다 넣은 이유
- **cid** = 요청 1건, 짧은 수명
- **sessionId** = 운동 세션 1건, 긴 수명(요청 수십 건)

그래서 **"cid는 다른데 sessionId가 같다" = 서로 다른 두 요청이 같은 세션을 건드렸다**가 로그에서 바로 읽힌다.

> ⚠️ **여기서 멈출 것.** 이건 경쟁의 *단서*지 *증거*가 아니다. 두 요청이 순차적으로 건드려도 똑같은 모양이 나오므로, 동시 실행이나 낙관락 충돌이 실제로 일어났다는 결론은 여기서 나오지 않는다. 확정은 같이 넣은 `shadowfit.session.optimistic.lock.conflicts` 지표(또는 충돌 재시도 로그)가 한다. **§1-③에 대한 답은 cid가 아니라 그 지표**이고, cid는 충돌이 잡혔을 때 "누구와 누구였는지"를 되짚는 용도다.

sessionId는 metadata가 아니라 메시지 payload 안에 있어 인터셉터가 볼 수 없으므로 `ExerciseGrpcService` 각 메서드에서 얹는다.

---

## 4. 변경 범위

**신규 (Java 8 · Python 1 · 설정 1)**
`global/observability/` 에 `CorrelationIds` `CorrelationIdFilter` `GrpcCorrelationServerInterceptor` `GrpcCorrelationClientInterceptor` `GrpcObservabilityConfig` `SessionMetrics`, `global/config/AsyncConfig`, `resources/logback-spring.xml`, `ai-server/app/grpc/correlation.py`

**수정 (Java 6 · Python 4)**
`ExerciseGrpcService`(세션 MDC) · `ExerciseAnalysisService`(세션 MDC·옵저버 데코레이터·충돌 지표) · `SessionService`(전이·충돌 지표) · `PoseDataService`(배치 지표) · `SessionTimeoutScheduler`(tick id·세션 MDC·지표) · `PoseDataPartitionScheduler`(tick id) / `ai-server` 의 `grpc/server.py` `grpc/spring_client.py`(재시도 metadata 고정) `grpc/exercise_servicer.py`(스레드 target `wrap`) `main.py`

**계획 대비 차이**: `SecurityConfig` 수정 불필요해짐(§3-1), 대신 `GrpcObservabilityConfig` 신설(§3-2). **`build.gradle` 변경 없음.** 리뷰 반영으로 `correlation.py` 에 `wrap()`·`ensure_correlation_id()` 추가, `exercise_servicer.py` 가 수정 대상에 합류(§3-3-1).

---

## 5. 검증

| 테스트 | 커버 |
|---|---|
| `CorrelationIdFilterTest` (4) | 발급/수용/인젝션 차단/요청 후 MDC 정리 |
| `AsyncMdcPropagationTest` (3) | 워커 전파, **스레드 재사용 시 누수 없음**, sessionId 전파. 실제 `AsyncConfig` 커스터마이저를 그대로 적용 |
| `GrpcCorrelationInterceptorTest` (5) | 송신 부착·미보유 시 발급, **수신이 핸들러 실행 컨텍스트에서 노출**, 왕복 동일 id |
| `GrpcObservabilityWiringTest` (2) | 전역 인터셉터 등록 — 로직이 맞아도 **등록이 빠지면 조용히 아무 일도 안 일어나서** 별도 확인 |
| `SessionTimeoutSchedulerTest` (수정) | 양보/전이 지표가 실제로 올라가는지 (`SimpleMeterRegistry` 실측) |
| `SessionMetricsRecordingTest` (11) + `PoseDataServiceTest` (1) | **지표 3종의 전 기록 지점** — §5-1 |
| `ai-server/tests/test_correlation.py` (10) | sanitize, 핸들러 컨텍스트 노출, 재송신 시 동일 id 유지, LogRecord 주입, **스레드 경계 전파 + 안 감쌌을 때 끊김 + 재시도 id 고정**(§3-3-1) |

백엔드 전체 스위트 통과(181건). Python은 venv에 pytest가 없어 함수 직접 호출로 10건 실행(전부 통과) — **pytest는 requirements.txt에 없어서 설치하지 않았다.** 이 실행 방식(한 프로세스에서 순차 호출) 때문에 전역 상태를 건드리는 테스트는 반드시 원복해야 한다 — `test_log_record_factory_injects_cid` 가 `LogRecordFactory` 를 `finally` 로 되돌리는 이유.

### 5-1. 지표 검증 — 계수기는 조용히 실패한다 (리뷰 지적 반영, 2026-07-28)

1차에는 `SessionTimeoutSchedulerTest` 하나만 지표를 검증했다. 나머지 기록 지점은 무검증이었는데, **계수기는 빠져도 예외가 안 난다** — 호출이 누락되든 태그를 오타 내든 조용히 0으로 남고, 나중에 대시보드의 "충돌 0건"이 진짜 0건인지 계측 고장인지 구분할 수 없게 된다. 관측성이 이 PR의 주제인데 그 관측 장치 자체가 무검증인 건 앞뒤가 안 맞아서 채웠다.

| 기록 지점 | 검증 |
|---|---|
| `ExerciseAnalysisService` 서킷 OPEN | FAILED/`circuit-open` 1건, **세션이 이미 종료면 0건**(조건 분기까지) |
| `ExerciseAnalysisService` gRPC `onError` | FAILED/`grpc-error` 1건 — 목 스텁이 `onError` 를 직접 발화 |
| `ExerciseAnalysisService` 앱 콜백 | COMPLETED/`app-callback` 1건, **멱등 재전송 시 중복 0건** |
| `ExerciseAnalysisService` / `SessionService` 낙관락 충돌 | 1회 충돌 → `retry` 1건 / 3회 전패 → `retry` 2 + `exhausted` 1 후 예외 전파 |
| `PoseDataService.savePoseDataBatch` | 7프레임 → `received` 7 / `stored` 2 (실제 저장 행수와 일치하는지) |

설계 결정 3가지:

- **목이 아니라 진짜 `SimpleMeterRegistry`.** `verify(metrics).sessionTransition(...)` 는 지표 **이름·태그** 오타를 못 잡는다. 실제 레지스트리에 그 이름·그 태그로 조회해 값이 나와야 통과.
- **단위 테스트(원객체 직접 호출).** 낙관락 충돌과 gRPC `onError` 는 통합 컨텍스트에서 결정적으로 재현하기 어렵다(전자는 실제 동시 커밋, 후자는 죽은 AI 서버 + 테스트 트랜잭션 밖 콜백 스레드). 프록시를 안 거치면 `@Async`/`@Transactional` 없이 그 분기만 정확히 때릴 수 있다. `self` 는 `ReflectionTestUtils` 로 교체.
- **"안 올라가는 것"도 검증.** 이미 종료된 세션·멱등 재전송에서 **0건**임을 함께 확인한다. 지표가 실제보다 부풀면 없느니만 못하다.

`savePoseDataBatch` 만 통합 테스트에 뒀다 — 다운샘플 실제 저장 행수와 지표가 일치하는지가 핵심이라 진짜 DB 경로를 타야 의미가 있다. 공유 컨텍스트라 절대값이 아니라 **증분**으로 본다.

| 안 함 | 이유 |
|---|---|
| **JSON 구조화 로깅** | 수집기(ELK/Loki)가 없어 파싱해줄 대상이 없다. 지금 실익은 "수집기 붙이면 바로 쓸 수 있게 준비" 수준이라 1차에서 제외 |
| **분산 추적**(Micrometer Tracing / Zipkin) | 인프라(컨테이너)가 붙는데 이 규모에서 도입 근거를 측정으로 정당화하기 어렵다 — [`redis-introduction.md`](./redis-introduction.md) 의 "측정 전 도입은 premature" 기조와 동일 |
| **로그 수집기** | 단일 인스턴스 콘솔 로그. 오버엔지니어링 |
| HTTP(REST) 경로의 sessionId MDC | 해당 메서드들(`endSession`/`deleteSession`)에 로그 호출이 없어 실익 없음. cid만으로 요청 특정 가능 |

> 정직 포지셔닝: 위 3개는 "안 해서 부족한 것"이 아니라 **규모에 맞춰 자른 것**이지만, 면접에서 자랑거리로 쓰지 말 것. 물어보면 "임계 이하라 안 했고 붙일 자리는 만들어뒀다"로 답한다.

---

## 7. 남은 것

- [ ] JSON 구조화 로깅 — 로그 수집기 도입 시 동반
- [ ] 보강 축 나머지 착수 순서: **outbox vs 회복탄력성** ([`portfolio-narrative.md §7`](../portfolio/portfolio-narrative.md)) — 관측성이 빠지면서 2개로 좁혀짐
- [ ] gRPC 콜백 처리 시간 Timer — 이번 범위에서 제외(지표 3종만)
- [ ] AI 쪽 HTTP 엔드포인트(`pose.py`)의 cid 전파 — 이번엔 gRPC 경로만
- [x] ~~신규 메트릭 3곳의 `SimpleMeterRegistry` 카운트 검증 테스트~~ — **완료(2026-07-28)**, §5-1

---

## 결정 로그
- 2026-07-28: 보강 3축(관측성·outbox·회복탄력성) 중 **관측성을 1순위로 착수·완료**. 근거: 착수 시점에 Actuator·Resilience4j·gRPC deadline은 이미 있어 회복탄력성은 실질적으로 채워져 있었고, 관측성만 🔴 빈칸이라 ROI가 가장 높았음. 범위는 correlation id 5단계 + 커스텀 메트릭 3종, 분산추적·JSON 구조화는 제외(§6).
- 2026-07-28: PR #54 CodeRabbit 리뷰 반영. **Python 백그라운드 스레드 경계 누락을 수정**(§3-3-1) — 지적은 "재시도마다 id가 달라진다"(Minor)였으나 검증해 보니 `threading.Thread` 가 컨텍스트를 상속하지 않아 `CompleteAnalysis` 콜백 전체가 원 요청과 끊겨 있었음(더 큼). §3-6의 "cid가 다르고 sessionId가 같다 = 경쟁의 증거" 주장도 **과했음을 인정하고 "단서"로 완화** — 확정은 낙관락 충돌 지표가 한다(Java javadoc·logback 주석 동일 정정). `install_log_record_factory()` 멱등화 지적은 기동 시 1회 호출이라 미적용.
- 2026-07-28: 보류했던 **메트릭 검증 테스트도 착수·완료**(§5-1). 1차에는 `SessionTimeoutSchedulerTest` 하나만 지표를 검증하고 나머지 기록 지점은 무검증이었는데, 관측성이 주제인 PR에서 관측 장치 자체가 무검증인 건 앞뒤가 안 맞다고 판단. 12건 추가(백엔드 총 181건).
