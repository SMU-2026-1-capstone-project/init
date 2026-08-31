# AI ↔ Backend 결합 현황

마지막 업데이트: **2026-08-08** (이전 2026-05-23 → 2.5개월치 반영, §0 참조)
범위: `ai-server/` (Python, FastAPI + gRPC) ↔ `backend/` (Spring Boot, Java/Kotlin)
목적: 현재 어떻게 결합돼 있는지 사실만 정리한 스냅샷. 트레이드오프·대안 비교는 [`docs/decisions/ai-backend-coupling.md`](../decisions/ai-backend-coupling.md) 참조.

---

## 0. 🔴 2026-08-08 갱신 — 이 문서가 2.5개월 뒤처져 있었다

2026-05-23 판을 마지막으로 멈춰 있었고, 그 사이 결합면(`proto`·`ai-server`·gRPC 경로)에 **커밋 21건**이 들어갔다. 특히 **송신 경로의 전달 의미론이 바뀐 것**(dual-write → outbox)이 반영되지 않아 아래 §1·§6·§9 가 실제와 어긋나 있었다.

| 반영한 것 | 어디 |
|---|---|
| **아웃박스** — 세션 종료 통보가 at-most-once → **상한 있는 재시도**(유실이 «조용히» 에서 «조회 가능» 으로) | §1 · §4 중단 · §6 · §9 |
| **회복탄력성** — gRPC deadline + Resilience4j 서킷브레이커 | §1 · §6 |
| **관측성** — correlation id 가 gRPC 양방향·`@Async`·스케줄러를 넘어 전파 | §1 · §6 |
| **`ReattachAnalysis`** — AI 상태 소실 시 세션 이어하기 (RPC 신설) | §3 · §4 재부착 |
| **`ReportFeedbackBatch`** — proto·Spring 수신부는 있는데 **AI 가 안 부른다** | §3 · §9 |
| ~~**`user.proto`/`UserService`** — 선언만 있고 **양쪽 다 구현·호출 없음**~~ → **삭제됨(2026-08-12, #133)** | §3 · §9 |
| 라이브러리 버전 드리프트 (gRPC 1.62.2→1.83.1 등) | §7 |
| Flyway·관리포트 9090·관측 스택 | §7 |

⚠️ **이 문서는 «현황 스냅샷»이라 날짜 박힌 기록 보존 규칙의 예외**다 — 낡은 서술은 취소선이 아니라 **교체**한다. 변화 과정은 [`ai-backend-changelog.md`](./ai-backend-changelog.md)·[`ai-backend-monthly-log.md`](./ai-backend-monthly-log.md) 가 담는다.

---

## 1. 결합 형태 요약

| 차원 | 현재 방식 |
|------|---------|
| 통신 프로토콜 | gRPC (양방향, 동기 unary RPC) |
| 스키마 공유 | `exercise.proto` 양쪽 저장소에 동일 파일 중복. (`user.proto` 는 아무도 안 써서 2026-08-12 삭제 — #133) |
| 인증 | 내부 공유 토큰(`INTERNAL_API_TOKEN`) 기반, gRPC metadata `Authorization: Bearer …` |
| 네트워크 | Docker Compose `shadowfit-net` 브리지, 컨테이너명 DNS |
| 호출 패턴 | Spring → AI: 비동기(`@Async`, 202 Accepted) / AI → Spring: 콜백 (3회 재시도) |
| **전달 의미론 (Spring → AI 종료 통보)** | **아웃박스 + 발행기 폴링 = «상한 있는 재시도»**(기본 10회, 초과 시 터미널 `FAILED`). 🔴 **전달 보장이 아니다** — 의도를 DB 에 보존하고 실패를 조회 가능하게 만든다. `effectively exactly-once` 는 *전달이 성공한 건에 한해*, 그리고 AI `StopAnalysis` 가 멱등하다는 전제에서만 성립 (2026-07-29) |
| **회복탄력성** | 모든 Spring → AI 호출에 **gRPC deadline**(`withDeadlineAfter`) + **Resilience4j 서킷브레이커**(`aiServer`) |
| **관측성** | **correlation id** 가 HTTP(`X-Request-Id`) → MDC → gRPC 메타데이터(`x-request-id`) → FastAPI `ContextVar` 로 전파. 커스텀 지표 9종 |
| 상태 저장 | AI: in-memory `SessionState`, Spring: MySQL (`Session`, `PoseData`, `ExerciseReference`). **소실 시 `ReattachAnalysis` 로 복구 가능**(2026-07-31) |
| 동시성 | Spring `Session` 엔티티에 `@Version` 낙관적 락, 충돌 시 3회 재시도 |

---

## 2. 컴포넌트 구성

```
┌─────────────────────┐    REST API (8080)     ┌──────────────────┐
│  Frontend (RN)      │ ─────────────────────► │                  │
│                     │                         │   shadowfit-     │
└─────────────────────┘                         │   backend        │
                                                │   (Spring Boot)  │
                          gRPC StartAnalysis    │                  │
                          gRPC ReattachAnalysis │  - REST  8080    │
                          gRPC StopAnalysis ⚠   │  - gRPC  6565    │
                       ┌─────────────────────►  │  - 관리  9090    │
                       │                        │                  │
                       │                        └───────┬──────────┘
                       │                                │ JDBC
                       │                                ▼
                       │                        ┌──────────────────┐
                       │                        │ shadowfit-mysql  │
                       │                        │ (MySQL 8.0)      │
                       │                        │  + outbox_events │
                       │                        └──────────────────┘
              ┌────────┴─────────┐
              │  shadowfit-ai    │  gRPC SavePoseDataBatch (실시간 콜백)
              │  (FastAPI)       │  gRPC CompleteAnalysis  (종료 콜백, 3회 재시도)
              │                  │  gRPC ExtractReferenceData (관리자, 비동기)
              │  - HTTP 8000     │ ──────────────────────────────────────►  (backend:6565)
              │  - gRPC 8585     │
              │  MediaPipe 0.10  │  ✗ ReportFeedbackBatch — 수신부만 있고 안 부른다 (§3-1)
              └──────────────────┘
```

⚠️ **`StopAnalysis` 만 화살표의 의미가 다르다.** Spring 코드가 직접 부르는 게 아니라 `outbox_events` 를 거쳐 `OutboxPublisher` 가 폴링해 보낸다(§4 중단). 나머지 Spring → AI 호출은 호출 스레드에서 바로 나간다.

Docker 네트워크는 `shadowfit-net` 브리지 한 개. 외부 노출은 backend REST(8080)만, gRPC(6565)·관리포트(9090)·AI HTTP(8000)/gRPC(8585)는 컨테이너 내부 전용 — **9090 은 dev compose 에서만 호스트에 열리고, 그것도 `127.0.0.1` 로 묶여 있다**(2026-08-08, #128).

---

## 3. gRPC 인터페이스

스키마 파일:
- `backend/src/main/proto/exercise.proto`
- `ai-server/app/proto/exercise.proto`
- 두 파일은 **수동으로 동기화** 필요. 변경 시 양쪽 모두 수정 + 코드 생성 재실행.

`ExerciseService` 정의된 RPC — **7개** (2026-05-23 판에는 5개만 적혀 있었다):

| RPC | 방향 | 호출자 | 수신자 | 용도 |
|-----|------|--------|--------|------|
| `ExtractReferenceData` | Spring → AI | `ExerciseAnalysisService.extractReferencePoses` | `ExerciseServicer.ExtractReferenceData` | YouTube URL에서 기준 포즈 추출 |
| `StartAnalysis` | Spring → AI | `ExerciseAnalysisService.sendAnalysisRequestToFastApi` | `ExerciseServicer.StartAnalysis` | 세션 시작 + 기준 좌표 전달 |
| **`ReattachAnalysis`** 🆕 | Spring → AI | `ExerciseAnalysisService.reattachSession` | `ExerciseServicer.ReattachAnalysis` | **이미 `IN_PROGRESS` 인 세션의 AI 상태를 DB 값으로 되살린다.** 2026-07-31 신설(#59 2단계) |
| `StopAnalysis` | Spring → AI | **아웃박스 발행기**(`OutboxPublisher`) ← `ExerciseAnalysisService.stopAnalysis` 가 이벤트만 적재 | `ExerciseServicer.StopAnalysis` | 사용자 중단 신호. **호출자가 바뀌었다** — §4 중단 |
| `SavePoseDataBatch` | AI → Spring | `spring_client.report_pose_data_batch` (트리거: rep 완성 시) | `ExerciseGrpcService.savePoseDataBatch` | rep 단위 실시간 포즈 데이터 저장. **프론트가 `POST /pose` 를 프레임마다 호출해야 동작** |
| `CompleteAnalysis` | AI → Spring | `spring_client.report_complete_analysis` | `ExerciseGrpcService.completeAnalysis` | 세션 종료 + 최종 통계 전달 (핵심 콜백) |
| **`ReportFeedbackBatch`** ⚠️ | AI → Spring | **없음 — 아무도 안 부른다** | `ExerciseGrpcService.reportFeedbackBatch` → `FeedbackLogService` | TTS 피드백 발화 이벤트 batch 저장. **수신부만 있는 반쪽 경로**(§3-1) |

### 3-1. ⚠️ 선언돼 있지만 흐르지 않는 두 경로

이 문서가 «현황»을 말하므로 **선언과 실제 사용을 구분해 적는다.**

| | 상태 | 근거 |
|---|---|---|
| **`ReportFeedbackBatch`** | **반쪽** — proto 양쪽 ✅ · Spring 수신부 ✅([`ExerciseGrpcService.java:120`](../../backend/src/main/java/com/shadowfit/service/exercise/ExerciseGrpcService.java)) · **AI 호출부 ❌** | `ai-server/app/grpc/spring_client.py` 가 부르는 것은 `report_pose_data_batch`·`report_complete_analysis` **둘뿐**이고, `ai-server/` 전체에 `FeedbackBatch` 사용처가 0건이다 |
| ~~**`user.proto` / `UserService.GetUserInfo`**~~ | **삭제됨 (2026-08-12, #133)** | 선언은 `backend/src/main/proto/user.proto` 에만 있었고 **양쪽 어디에도 구현·호출이 없었다**(전 저장소 검색 0건). `ai-server/app/proto/` 에는 파일 자체가 없었다 — 한 번도 양쪽 계약이 된 적이 없다. 지우는 쪽으로 닫았다 |

> 📌 **`ReportFeedbackBatch` 는 이미 알려진 갭이다** — [`../tasks/30-ai-remaining-work.md`](../tasks/30-ai-remaining-work.md) §1 이 *"결함 분류 → 송신 통째로 미구현, 1순위"* 로 잡아뒀다. *"proto·테이블·시드가 다 있는데 AI 가 안 불러서 TTS 피드백 기능 전체가 시연용 더미로만 존재한다."* 이 문서에는 그 사실이 없어서, **결합 구조만 보면 동작하는 경로처럼 보였다.**
>
> ✅ **`user.proto` 는 2026-08-12 에 삭제됐다(#133).** 「지우거나 쓰거나 정하는 게 맞다」는 위 판단을 **지우는 쪽**으로 닫은 것이다. 근거: 전 저장소에서 구현·호출 0건이고 `ai-server` 쪽엔 파일 자체가 없어 한 번도 계약이 된 적이 없다. 그때까지 Java 클래스(`UserProto`·`UserServiceGrpc` 등)가 빌드마다 생성되고 있었고, 그게 다음 사람에게 "닉네임은 gRPC 로 가져오나?" 를 의심하게 만드는 비용이었다.

핵심 메시지:
- `AnalyzeRequest`: `exercise_id(int64)`, `session_id(int64)`, `reference_poses(PoseDataRequest[])`
- `SessionCompleteRequest`: `session_id(int64)`, `total_reps(int32)`, `avg_sync_rate(double)`, `max/min_sync_rate(double)`, `calories_burned(double)`
- `PoseDataRequest`: `timestamp_sec(double)`, `joint_coordinates(string=JSON)`, `sync_rate(double)`, `feedback_message(string)`

`SessionStatus` enum: `IN_PROGRESS=0`, `COMPLETED=1`, `FAILED=2`.

---

## 4. 세션 라이프사이클

### 시작
1. 프론트 → `POST /exercises/sessions` (Spring REST)
2. `ExercisesController.startAnalysis` 수신
3. `SessionService.createSession` → DB에 `Session(status=IN_PROGRESS)` 생성, sessionId 즉시 반환 (202)
4. `@Async` 스레드에서 `sendAnalysisRequestToFastApi` 실행
5. DB에서 `ExerciseReference` 조회 → gRPC `StartAnalysis` 송신
6. AI: `ExerciseServicer.StartAnalysis` 수신 → 메모리 `SessionState` 생성 (thread-safe dict)

### 진행 중
- 프론트가 카메라 프레임을 base64 로 인코딩해 **`POST /pose` (FastAPI HTTP) 에 프레임 단위로 전송**
- AI: `StreamingSquatAnalyzer.process_frame` 가 rep 감지 (`app/api/endpoints/pose.py:77`)
- rep 1회 완성 시 → `spring_client.report_pose_data_batch` 호출 → gRPC `SavePoseDataBatch` → Spring `pose_data` 테이블에 영속화 (`pose.py:116`)
- 프론트가 `/pose` 를 호출하지 않으면 `pose_data` 테이블은 빈 채로 남음. 프론트 책임.

### 중단 — 🔄 아웃박스 도입으로 경로가 바뀌었다 (2026-07-29)

1. 프론트 → **`PATCH /sessions/{sessionId}/end`** (`SessionController.java:57`, 멱등 — 이미 종료된 세션 재호출도 200)
   🔴 **2026-08-08 정정**: 이 자리에 적혀 있던 `PUT /exercises/sessions/{id}/stop`(커밋 `143a2e4` 신설)은 **지금 컨트롤러에 없다** — 전 컨트롤러 `stop` 매핑 0건. 프론트도 `PATCH …/end` 를 부른다(`exercisesService.ts:23`).
   📌 **의도적 삭제였다** — [`../decisions/session-end-trigger.md`](../decisions/session-end-trigger.md) §박힌 코드(2026-05-26)가 *"`ExercisesController.stopSession`(`PUT …/stop`) **삭제**"* 를 기록하고 있다. **ET-H(단일 endpoint 분배자) 확정**의 일부다 — 클라는 `PATCH /sessions/{id}/end` **한 번만** 부르고 Spring 이 AI 통보를 분배한다. 즉 결정 문서는 처음부터 맞았고 **이 현황 문서만 2.5개월 안 따라왔다**
2. `ExerciseAnalysisService.stopAnalysis` — **gRPC 를 직접 부르지 않는다.** 세션 상태 변경과 **같은 트랜잭션**에서 `outbox_events(type=STOP_ANALYSIS, payload={sessionId})` 를 적재
3. `OutboxPublisher` — `@Scheduled` 폴링(기본 1초, `outbox.publisher.poll-interval-ms`)으로 `PENDING` 을 집어 gRPC `StopAnalysis` 송신. 실패하면 재시도(기본 상한 10회, `outbox.publisher.max-retry`) 후 `FAILED`
4. AI: `SessionState` 제거 + 백그라운드 스레드에서 `_send_complete_analysis` 호출
5. AI: 누적 통계 계산 → `spring_client.report_complete_analysis` (gRPC `CompleteAnalysis`)
6. Spring: `ExerciseGrpcService.completeAnalysis` 수신 → `SessionService.completeSession`
7. Spring: `Session(status=COMPLETED, total_reps=…, avg_sync_rate=…)` 갱신

> 🔴 **왜 바꿨나 — dual-write 였다.** 예전 경로는 DB 커밋과 gRPC 송신이 별개 동작이라, 커밋은 됐는데 통보가 실패하면 **Spring 은 `COMPLETED` 인데 AI 에는 orphan 세션이 남았다.** `afterCommit` 으로 미뤄도 "커밋 후 송신 실패"는 그대로 남는다 — 유실을 **0으로 만들 수 없는 구조**였다. 아웃박스는 통보 의도를 DB 에 같이 커밋해 **재시도할 수 있게** 만든다 — ⚠️ 유실을 0 으로 만드는 게 아니라 **상한(10회)까지 자동 재시도하고, 넘으면 `FAILED` 로 남긴다.** 상세: [`../decisions/outbox-reliable-messaging.md`](../decisions/outbox-reliable-messaging.md).
>
> ⚠️ 재시도가 있으므로 **같은 통보가 두 번 갈 수 있다** — AI 측 `StopAnalysis` 가 멱등해야 성립한다(이미 없는 세션이면 성공 반환).

### 재부착 🆕 (2026-07-31, #59 2단계)

AI 컨테이너가 재시작되면 in-memory `SessionState` 가 사라지는데, Spring DB 에는 세션이 `IN_PROGRESS` 로 남는다. 그 간극을 메우는 경로다.

1. 프론트 → `POST /exercises/sessions/{id}/reattach` (`SessionController`)
2. `SessionService.findReattachableSession` — 소유자·상태 검증 후 DB 에서 재구성 재료를 읽는다
3. **트랜잭션을 닫고** gRPC `ReattachAnalysis` 송신 — DB 커넥션을 쥔 채 외부 호출을 기다리지 않는다(`ExerciseAnalysisService.reattachSession` 주석에 근거)
4. AI: 상태가 **이미 있으면 보존하고 성공 반환**, 없으면 DB 값으로 생성

> 📌 **`StartAnalysis` 와 왜 분리했나** — proto 주석이 직접 적어놨다: 이 RPC 의 계약은 *"있으면 보존"* 이고 정상 시작은 *"새로 만든다"* 라 **멱등 규칙이 정반대**다. 한 핸들러에 섞으면 **정상 시작 요청이 조용히 no-op** 이 될 수 있다. 상세: [`../decisions/session-resume-and-ai-state.md`](../decisions/session-resume-and-ai-state.md) §4-B.

`/exercises/sessions/{id}/complete` 는 제거됨 — 프론트가 직접 통계를 보내던 경로였고, AI 단일 진실 원칙으로 폐기. 엔드포인트는 `23c8953`(2026-07-11), 뒤에 남아 있던 서비스·DTO 는 이슈 #179 에서 걷어냈다.

### 타임아웃
- `SessionTimeoutScheduler` @Scheduled(fixedDelay=1분)
- `IN_PROGRESS` 세션 중 `startTime + expectedDurationMinutes + 30분` 초과 → `status=FAILED`
- AI 완료 콜백과 동시에 들어오면 `OptimisticLockingFailureException` 발생, 스케줄러가 양보 (AI 결과 우선)

---

## 5. 인증

- 양쪽 컨테이너 환경변수 `INTERNAL_API_TOKEN` 동일하게 주입 (`docker-compose.yml`)
- Spring 측: `InternalAuthInterceptor`(`backend/src/main/java/com/shadowfit/global/config/InternalAuthInterceptor.java`)
- AI 측: `AuthInterceptor`(`ai-server/app/grpc/server.py`)
- gRPC metadata `authorization: Bearer {token}` 불일치 시 `UNAUTHENTICATED`
- JWT(사용자 인증)와는 별개 채널. JWT는 프론트↔Spring REST에서만, 내부 토큰은 Spring↔AI gRPC에서만.

---

## 6. 동시성·신뢰성 메커니즘

| 메커니즘 | 위치 | 동작 |
|---------|------|------|
| Spring 비동기 호출 | `@Async sendAnalysisRequestToFastApi` | REST 응답을 막지 않고 gRPC 송신 |
| AI thread-local MediaPipe | `mediapipe_detector` (커밋 `c7657f1`) | 분석기 인스턴스를 thread별로 분리, race 제거 |
| AI sync 분석 루프 | `pose.py` (커밋 `c7657f1`) | MediaPipe 블로킹을 async 이벤트 루프에서 분리 |
| AI 콜백 재시도 | `spring_client.report_complete_analysis` | 1초 → 3초 백오프, 최대 3회 |
| Spring 낙관적 락 재시도 | `SessionService.completeSession` | `@Version` 충돌 시 최대 3회 |
| 타임아웃 양보 | `SessionTimeoutScheduler` | AI 완료 콜백이 늦게 와도 충돌 시 AI 결과 우선 |
| **아웃박스 (상한 있는 재시도)** 🆕 | `OutboxPublisher` + `outbox_events` 테이블 | 종료 통보를 DB 에 같이 커밋 → 폴링 발행 → **재시도 상한 10회 초과 시 터미널 `FAILED`**(`OutboxPublisher.java:148-149`). 🔴 **무한 재시도가 아니므로 «반드시 전달» 이 아니다** |
| **gRPC deadline** 🆕 | `ExerciseAnalysisService` — 모든 스텁에 `withDeadlineAfter` | AI 가 hang 해도 호출 스레드가 무한 대기하지 않는다 |
| **서킷브레이커** 🆕 | Resilience4j `aiServer` 인스턴스 (`CircuitBreakerRegistry`) | AI 연속 실패 시 회로 개방 — 죽은 서버에 계속 밀어넣지 않는다 |
| **correlation id 전파** 🆕 | `CorrelationIdFilter` · `GrpcCorrelation{Client,Server}Interceptor` · `CorrelationIds` | HTTP `X-Request-Id` → MDC(`cid`) → gRPC 메타데이터 `x-request-id` → FastAPI `ContextVar`. `@Async`·스케줄러 경계도 넘긴다 |

> 🔄 **2026-05-23 판의 마지막 문장을 교체했다.** 그 판은 *"영구 큐를 쓰지 않음. AI 콜백이 3회 모두 실패하면 ERROR 로그만 남고 수동 복구 필요"* 라고 적었다. 지금은 **방향에 따라 다르다**:
>
> | 방향 | 전달 의미론 | 실패 시 |
> |---|---|---|
> | **Spring → AI** (종료 통보) | **상한 있는 재시도**(아웃박스, 기본 10회) — *at-least-once 는 전달이 성공한 건에 한해* | 상한까지 자동 재시도, 그 뒤 터미널 `FAILED`. **유실은 여전히 가능하지만 «조용히» 는 아니다** — 행이 남아 조회·재처리 가능 |
> | **AI → Spring** (완료 콜백) | 여전히 **최대 3회 후 유실** | ERROR 로그만. **수동 복구 절차 없음** — §9 |
>
> 🔴 **«한쪽이 닫혔다» 고 쓰면 과장이다** (2026-08-08 리뷰 지적 반영). 아웃박스가 바꾼 것은 **유실의 성질**이다:
>
> | | 이전 (dual-write) | 지금 (아웃박스) |
> |---|---|---|
> | 커밋 후 송신 실패 | **통보 의도가 사라진다** — 흔적 없음 | 의도가 행으로 남는다 |
> | 재시도 | 없음 | 자동, **상한 10회** |
> | 상한 초과 후 | — | 터미널 `FAILED` — **여전히 미전달이지만 조회 가능** |
> | 재처리 | 불가 | 가능(행이 있으므로) |
>
> 즉 **«유실 0» 이 아니라 «유실이 관측 가능해졌다»** 다. 지표 `shadowfit_outbox_pending` 의 **기울기**를 보는 이유가 이것이고, 🔴 **`FAILED` 를 사람이 보고 재처리하는 운영 절차는 아직 없다.**
>
> 카프카·RabbitMQ 같은 외부 브로커는 쓰지 않는다(아웃박스는 MySQL 테이블 + 스케줄러).
>
> ⚠️ **«유실 0» 은 실험 결과다.** [`../decisions/outbox-reliable-messaging.md`](../decisions/outbox-reliable-messaging.md) §6 의 실측은 *그 실험 조건에서 상한 안에 전부 전달됐다* 는 뜻이고, **운영 보장으로 인용하면 안 된다.**
>
> ⚠️ MDC 는 ThreadLocal 이라 스레드가 바뀌면 사라진다. 그래서 `@Async`·gRPC 콜백·스케줄러 경계마다 **명시적으로 넘긴다** — 실제로 2026-07-28 에 백그라운드 스레드 경계에서 끊긴 버그를 한 번 고쳤다(`bfa4d50`).

---

## 7. 인프라

`docker-compose.yml` 기준:

| 서비스 | 포트 노출 | 환경변수 (핵심) | 헬스체크 |
|--------|---------|---------------|---------|
| `shadowfit-mysql` | 내부 3306 | `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD` | `mysqladmin ping` |
| `shadowfit-backend` | 외부 8080, 내부 6565 | `DB_HOST`, `INTERNAL_API_TOKEN`, `AI_SERVER_HOST=shadowfit-ai`, `AI_SERVER_GRPC_PORT=8585`, `JWT_SECRET`, `OPENAI_API_KEY` | (별도 정의 시 추가) |
| `shadowfit-ai` | expose만 8000/8585 | `INTERNAL_API_TOKEN`, `POSE_MODEL_COMPLEXITY=1`, `BACKEND_URL=http://shadowfit-backend:8080/api/v1` | `urllib.request.urlopen('http://localhost:8000/health')` |

- 컨테이너간 DNS: `shadowfit-mysql`, `shadowfit-backend`, `shadowfit-ai`
- gRPC 주소 설정:
  - Spring: `application.yml` 의 `grpc.client.fastapi-client.address: static://shadowfit-ai:8585`
  - AI: `app/config.py` 의 `SPRING_GRPC_TARGET = "shadowfit-backend:6565"` (또는 환경변수)

라이브러리 (2026-08-08 `requirements.txt`·`build.gradle` 확인):
- AI: `mediapipe==0.10.21`, `opencv-python-headless==4.11.0.86`, `grpcio>=1.62.1`, **`protobuf==4.25.8`**, `dtaidistance==2.3.12`
- Spring: `grpc-{client,server}-spring-boot-starter:3.1.0.RELEASE`, **`io.grpc:* 1.83.1`**, **`protobuf-java 3.25.5`**

> 🔄 **버전 드리프트 정정.** 2026-05-23 판은 AI `protobuf 4.25.3` · Spring `io.grpc 1.62.2` / `protobuf-java 3.25.1` 로 적고 있었다. AI 쪽 protobuf 는 **DoS 취약점 대응으로 4.25.3 → 4.25.8**(`83ad508`, 2026-07-19), Spring gRPC 는 1.62.2 → 1.83.1 로 올라갔다.
>
> ⚠️ **양쪽 protobuf 메이저가 다르다**(AI 4.x / Spring 3.25.x). wire format 은 호환이지만 **같은 `.proto` 를 서로 다른 런타임으로 컴파일하고 있다**는 사실은 알고 있어야 한다 — 그리고 이 둘의 버전은 **아무 장치도 함께 올려주지 않는다**(§9 proto 중복과 같은 뿌리).

### 7-1. 이 문서에 없던 인프라 (2026-08 추가)

| | 내용 |
|---|---|
| **Flyway** | 스키마 마이그레이션 추적. 예전엔 docker initdb 가 `schema.sql` 을 한 번 실행하는 게 전부라 "이 DB 가 어디까지 갔는지" 기록이 없었다. 2026-08-01 도입(#115) — [`../decisions/schema-migration-tracking.md`](../decisions/schema-migration-tracking.md). ⚠️ `mysql/dev-seed.sql`(부하테스트용 세션 801 픽스처)은 **의도적으로 마이그레이션에서 제외** — 배포 환경에 가면 안 되는 데이터라, 부하테스트 전에 손으로 넣어야 한다 |
| **관리 포트 9090** | 액추에이터를 앱 포트(8080)에서 분리(`management.server.port`). `/actuator/prometheus` 를 인증 없이 열어야 하는데 8080 은 외부 노출이라 지표가 공개된다. 🔴 **보호 수단은 인증이 아니라 포트 경계 하나**다 |
| **관측 스택** | Prometheus + Grafana, compose profile `obs` (기본으로 안 뜬다) — [`../../monitoring/README.md`](../../monitoring/README.md). ⚠️ **ai-server 지표는 없다** — FastAPI 에 계측이 없어 타깃으로 넣지 않았다(넣으면 영원히 DOWN 인 타깃이 생긴다). 즉 **관측성은 지금 Spring 쪽만 덮는다** |

---

## 8. 변경 영향 매트릭스

| 변경 항목 | Spring 코드 | AI 코드 | proto 재생성 | DB 마이그레이션 | 동시 배포 |
|---------|-----------|-------|------------|---------------|---------|
| proto 필드 추가 | O | O | O | — | 권장 |
| proto 필드 삭제 | O | O | O | — | 필수 |
| proto 필드 타입 변경 | O | O | O | 데이터 검토 | 필수 |
| `ExerciseReference` 컬럼 추가 | O | O (송수신 코드만) | — | O | Spring 선행 |
| `INTERNAL_API_TOKEN` 변경 | env | env | — | — | 필수 |

| gRPC 포트 변경 | yml | config.py | — | — | 필수 |
| 타임아웃 정책 변경 | O | — | — | — | Spring만 |
| 콜백 재시도 정책 변경 | — | O | — | — | AI만 |
| 새 운동 종목 추가 (proto 변경 없이) | data.sql | analyzer 추가 | — | — | 권장 |
| 새 운동 종목 추가 (`exercise_type` 일반화) | O | O | O | — | 필수 |

---

## 9. 알려진 약점

- **proto 중복 파일** — 양쪽이 손으로 동기화. 한쪽만 바꾸면 런타임에 직렬화 실패까지 잡히지 않음. 실제로 2026-08-07 에 **머지로 계약 불일치 2건이 드러났다**(`e027889`).
- ⚠️ **`ReportFeedbackBatch` 가 반쪽이다** — proto·Spring 수신부·DB 테이블·시드까지 있는데 **AI 가 안 부른다.** TTS 피드백 기능 전체가 시연용 더미로만 존재한다(§3-1). [`../tasks/30-ai-remaining-work.md`](../tasks/30-ai-remaining-work.md) §1 이 1순위로 잡아둔 항목.
- ✅ **`user.proto`/`UserService` 는 삭제됐다(2026-08-12, #133)** — 선언만 있고 양쪽 다 구현·호출이 없었다(§3-1). Java 클래스가 계속 생성되던 것도 같이 사라졌다. **어느 문서에도 안 적혀 있던 것**이라 여기 처음 기록했고, 그 기록이 삭제 결정으로 이어졌다.
- **양방향 모두 유실이 가능하다 — 성질이 다를 뿐이다.** AI → Spring 완료 콜백은 3회 실패 시 **흔적이 로그뿐**이고, Spring → AI 종료 통보는 상한 10회 초과 시 **`FAILED` 행으로 남는다**(§6). 🔴 **둘 다 «사람이 보고 재처리하는 절차» 는 없다.** «아웃박스로 닫혔다» 는 서술은 과장이라 걷어냈다(2026-08-08 리뷰).
- **AI in-memory 세션 상태** — AI 컨테이너 재시작 시 진행 중 세션 소실은 그대로다. ✅ 다만 **복구 경로가 생겼다** — `ReattachAnalysis`(§4 재부착)로 DB 값에서 되살릴 수 있다. ⚠️ **자동은 아니다** — 프론트가 재부착을 호출해야 하고, 아무도 안 부르면 결국 스케줄러가 `FAILED` 처리한다.
- **단일 AI 인스턴스 가정** — 메모리 `SessionState`가 인스턴스 로컬이라 수평 확장 불가. (재부착은 이 문제를 **줄이지 않는다** — 상태가 여전히 인스턴스 로컬이다.)
- **gRPC reflection / health check 표준 미적용** — 헬스체크는 AI HTTP `/health`만, gRPC 채널 상태는 별도 모니터 없음. ⚠️ 서킷브레이커가 회로를 열어도 **그 사실을 볼 지표가 ai-server 쪽엔 없다**(§7-1 — 관측 스택이 Spring 만 덮는다).
- **proto에 `exercise_type` 없음** — 스쿼트 외 운동 추가 시 proto + 양쪽 코드 변경 필요 ([[project_squat_first]] 결정으로 후순위).

---

## 10. 참고 파일

- 결합 결정·트레이드오프: [`docs/decisions/ai-backend-coupling.md`](../decisions/ai-backend-coupling.md)
- 폴더 구조: [`docs/02-folder-structure.md`](../02-folder-structure.md)
- API 설계: [`docs/07-api-design.md`](../07-api-design.md)
- Docker 셋업: [`docs/13-docker-setup.md`](../13-docker-setup.md)
- 세션 타임아웃: [`docs/15-session-timeout-guide.md`](../15-session-timeout-guide.md)
