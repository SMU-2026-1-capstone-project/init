# Spring ↔ FastAPI 결합 변경 이력

마지막 업데이트: **2026-08-08** (이전 2026-05-23 — 그 사이 2.5개월치를 §4 로 채웠다)
범위: `ai-server/`(FastAPI)와 `backend/`(Spring Boot) 사이의 결합 방식·통신 프로토콜·인증·데이터 흐름 변경에 직접 영향을 준 커밋들. UI/문서/단순 버그 수정은 제외.

> ⚠️ **범위 주의**: *"결합에 영향을 준"* 은 **파일 위치가 아니라 계약**으로 판정한다. 아웃박스(`cb26e4a`·`993dfa1`)는 **Spring 파일만** 건드렸지만 `StopAnalysis` 의 전달 의미론을 바꿨으므로 §4 에 들어간다 — `proto`·`ai-server` 경로만 훑으면 놓친다. 실제로 이 문서가 2.5개월 밀린 동안 **가장 큰 결합 변경이 그 커밋들이었다.**

연관 문서:
- 현재 결합 현황 → [`ai-backend-integration.md`](./ai-backend-integration.md)
- 앞으로의 결합 결정 → [`../decisions/ai-backend-coupling.md`](../decisions/ai-backend-coupling.md)

---

## 0. 큰 흐름 한눈에

```
2026-03-30  596b4ad   FastAPI 마이크로서비스로 분리 시작점
2026-04-08  660e294   Spring → AI 호출을 WebClient(REST) 기반으로 첫 구현
2026-04-08  0d89668   AI → Spring 송신을 REST InternalExerciseController로 첫 구현
2026-04-13  d6cfc2e   Spring gRPC 의존성 추가 (gRPC 시대 시작)
2026-04-13  6ce9a43   gRPC 설정 완료 (양쪽 첫 .proto, application.yml, docker-compose)
2026-04-13  48bb0fc   유튜브 API를 gRPC 버전으로 (user.proto / exercise.proto 분리)
2026-04-14  6ac0390   ai-server에 mock gRPC 서버 + 자체 proto 생성
2026-04-14  953bad6   AI → Spring 결과 수령 API (proto에 callback RPC 추가, ExerciseGrpcService 신설)
2026-04-15  4eb153b   운동 좌표 fastapi로 송신 API (ExerciseReference 엔티티 신설)
2026-04-17  ea1c636   운동 종료 기능 (SessionUpdate DTO, proto 보강)
2026-04-27  c52f677   gRPC 토큰 인증 추가 (InternalAuthInterceptor)
2026-04-27  f172933   운동 중간 저장 (proto에 PoseDataBatch 관련 필드)
2026-04-28  e8e1b65   ai-server에 gRPC 패키지 신설 (PR 흐름) — 양쪽 동시 결합 완성
2026-05-16  1a50c14   AI 서버 mock 제거하고 실제 gRPC 통합으로 전환
2026-05-16  4a0f456   AI gRPC 통합 복원 — 위 두 흐름의 충돌 잔재 정리
2026-05-16  7d51cf6   사용처 없는 GrpcConfig 삭제 (정리 단계)
2026-05-17  8ac8248   StopAnalysis session_id int32 손실 + 응답 DTO Long 통일
2026-05-17  c7657f1   AI 동시성·콜백 신뢰성 P1 Phase A (재시도·thread-local)
2026-05-17  143a2e4   PUT /stop 신설 + /complete 디프리케이트 (AI 단일 진실 원칙)
2026-05-25  baffa48   ReportFeedbackBatch 신설 (⚠ AI 호출부는 끝까지 안 생김)
2026-07-11  0c47598   Spring→AI 전 호출에 gRPC deadline (hang 을 실패로)
2026-07-11  215d49a   Resilience4j 서킷브레이커 aiServer 적용
2026-07-19  83ad508   AI protobuf 4.25.3 → 4.25.8 (양쪽 메이저가 갈림)
2026-07-28  aaf576a   correlation id 양방향 전파 (proto 밖 계약 하나 추가)
2026-07-29  993dfa1   ★ 종료 통보를 아웃박스로 — at-most-once → at-least-once
2026-07-31  084fac7   ReattachAnalysis 신설 — AI 상태 소실 복구 경로
2026-08-03  e28bc65   타임아웃으로 걷는 세션도 AI 에 통보
2026-08-07  e027889   머지로 드러난 proto 계약 불일치 2건
```

네 단계로 요약:
1. **REST 시대 (2026-03-30 ~ 04-08)**: WebClient + InternalExerciseController. proto 없음.
2. **gRPC 전환 (2026-04-13 ~ 04-28)**: proto·인증·콜백 흐름 다 갖춤. 단 AI 쪽은 mock 서버.
3. **실통합·신뢰성 강화 (2026-05-16 ~ 05-17)**: mock 제거, 동시성·재시도 보강, API 디프리케이트 정리.
4. **전달 의미론·회복탄력성·관측성 (2026-05-25 ~ 08-08)** 🆕: **경로를 만드는 시기에서 경로의 보장을 바꾸는 시기로.** RPC 는 5 → 7 이지만 더 큰 변화는 같은 RPC 의 의미론이다 — 아웃박스로 Spring→AI 종료 통보가 at-least-once 가 됐고, deadline·서킷브레이커로 "안 죽고 느려지는" 경우가 실패로 분류됐고, correlation id 로 두 서비스 로그가 한 요청으로 이어졌다. ⚠️ **AI→Spring 방향은 여전히 3회 후 유실** — 한쪽만 닫혔다.

---

## 1. 결합 시작 (2026-03-30 ~ 2026-04-08)

### 596b4ad — MediaPipe + DTW를 Python FastAPI 마이크로서비스로 분리
**의도**: 모놀리스에서 영상/포즈 분석을 FastAPI 별도 서비스로 분리.
**중요 변경**: 마이크로서비스 분리의 출발점. (이 커밋 자체는 스킬·문서 다수 추가가 섞여 있음.)
**결합 영향**: 별도 프로세스가 됨 → 통신 방식을 정해야 하는 상황 발생.

### 660e294 — feat: 유튜브 링크 업로드 기능 추가
**의도**: Spring이 유튜브 URL을 받아 AI 서버로 전달.
**Spring 쪽**: `ExercisesController`, `FastApiRequestDto`, `WebClientConfig`, `ExerciseAnalysisService` 신설.
**통신 방식**: **REST (WebClient)**. proto 없음.
**결합 영향**: Spring → AI 방향 첫 호출 경로 확립.

### 0d89668 — feat: Youtube 좌표 데이터 송신 기능 추가
**의도**: AI 분석 결과 좌표를 Spring DB에 저장하는 endpoint 마련.
**Spring 쪽**: `InternalExerciseController`, `PoseDataRequestDto`, `PoseData` 엔티티, `PoseDataService` 신설.
**통신 방식**: **REST (Spring 측 controller)**. 인증 없음 (내부망 가정).
**결합 영향**: AI → Spring 콜백 경로 첫 구현. 이 컨트롤러가 나중에 `8ac8248`에서 gRPC로 대체되며 삭제됨.

### ef2e8e6 / 2b6b11c — 스쿼트 분석 로직 + 데모
**의도**: AI 측 스쿼트 분석기·필터·기준 좌표 JSON 추가.
**AI 쪽**: `squat_analyzer.py`, `pose_filter.py`, reference_data JSON.
**결합 영향**: AI 단독 진화 단계. Spring↔AI 통신 변화는 없음.

---

## 2. gRPC 전환 (2026-04-13 ~ 2026-04-28)

### d6cfc2e — config: gRPC 의존성 추가
**의도**: Spring에 grpc-spring-boot-starter, protobuf 라이브러리 도입.
**Spring 쪽**: `build.gradle` +26줄 (`grpc-{client,server}-spring-boot-starter`, `io.grpc:*`, `protobuf-java`).
**결합 영향**: 통신 프로토콜을 REST에서 gRPC로 전환할 토대 마련.

### 6ce9a43 — config: gRPC 설정 완료
**의도**: 최초 gRPC 핸드쉐이크 동작.
**Spring 쪽**: `UserGrpcService` (mock), `application.yml` gRPC 설정 추가, `backend/src/main/proto/.proto` 임시 파일.
**docker-compose**: gRPC 포트 노출 추가.
**결합 영향**: 양쪽이 gRPC로 한 번이라도 통신할 수 있는 최소 상태.

### 48bb0fc — feat: 유튜브 api gRPC버전 생성
**의도**: 유튜브 분석 요청을 REST → gRPC로 교체.
**Spring 쪽**:
  - `GrpcConfig.java` 신설 (channel 빈; 나중에 7d51cf6에서 삭제됨)
  - `backend/src/main/proto/exercise.proto` 신설 — `ExerciseService` 정의의 시작
  - `backend/src/main/proto/.proto` → `user.proto` 로 rename
  - `ExerciseAnalysisService`에 gRPC 호출 로직 추가
**결합 영향**: 첫 비즈니스 RPC(`ExtractReferenceData`) 도입. proto 파일이 결합의 단일 인터페이스가 됨.

### 6ac0390 — test: 유튜브 분석 요청 gRPC api 테스트 완료
**의도**: AI 측에서 Spring의 gRPC 요청을 받을 mock 서버 마련.
**AI 쪽**:
  - `ai-server/app/proto/exercise.proto` 신설 (Spring의 동일 파일과 수동 동기화 시작)
  - `exercise_pb2.py`, `exercise_pb2_grpc.py` 코드 생성 산출물
  - `mock_server.py` 신설 — gRPC 서버 mock
**결합 영향**: **proto 중복 동기화 부담의 시작점**. 양쪽이 같은 파일을 따로 들고 있게 됨.

### 953bad6 — feat: spring 운동 결과 수령 api 구현
**의도**: AI → Spring 콜백을 gRPC로 정의·구현.
**proto 변경**: 양쪽 `exercise.proto`에 콜백 RPC(`SavePoseDataBatch`, `CompleteAnalysis` 계열) 추가.
**Spring 쪽**: `ExerciseGrpcService` 신설 (콜백 수신 서버), `SessionService` 보강.
**AI 쪽**: `mock_server.py` 응답 흐름 확장.
**결합 영향**: 양방향 gRPC 결합 구조 완성. Spring 컨테이너가 client + server 둘 다 됨.

### 4eb153b — feat: 운동 좌표 fastapi로 보내는 api 구현
**의도**: 운동 시작 시 Spring DB의 기준 좌표를 AI로 전송.
**Spring 쪽**: `ExerciseReference` 엔티티·리포지토리 신설, `ExerciseAnalysisService` 확장, `PoseDataService` 신설.
**DB**: `mysql/schema.sql`에 `exercise_references` 테이블.
**docker-compose**: `+1` (구성 보정).
**결합 영향**: 결합 표면이 “proto + DB 스키마(기준 좌표 직렬화)” 둘로 늘어남.

### ea1c636 — feat: 운동 종료 기능 변경
**의도**: 운동 종료 시 결과 통계 흐름 정리.
**proto 변경**: `SessionUpdateRequest/Response` 등 종료 메시지 보강.
**Spring 쪽**: DTO를 `dto/exercises/session/` 하위로 옮기고 `SessionUpdateRequestDto`/`SessionUpdateResponseDto` 신설.
**AI 쪽**: `mock_server.py`가 종료 응답 mock 처리.
**결합 영향**: 종료 시점의 책임이 Spring↔AI 사이에서 처음으로 명시화됨.

### 2dd55e0 — chore: 운동분석 서비스로직+컨트롤러 수정
**의도**: 시드/스키마/서비스 정리, proto 마이너 보정.
**결합 영향**: 인터페이스 본체 변경은 아니지만 양쪽 proto 동기화 한 번 더 강제.

### c52f677 — feat: gRPC 토큰 검증 추가
**의도**: 내부 gRPC 채널에 인증 도입.
**Spring 쪽**: `InternalAuthInterceptor.java` 신설 (Authorization Bearer 검증).
**docker-compose**: `INTERNAL_API_TOKEN` 환경변수 양쪽에 주입.
**결합 영향**: 인증을 위해 양쪽이 **동일 토큰** 공유 필요 → 새 결합 항목 추가. (당시 AI 쪽 인터셉터는 e8e1b65/1a50c14 에서 추가됨.)

### f172933 — feat: 운동 중간 저장 로직 추가
**의도**: 진행 중 포즈 데이터를 배치로 저장.
**Spring 쪽**: `ExerciseGrpcService`, `PoseDataService` 보강.
**proto 변경**: 중간 저장 관련 필드 2개 추가.
**결합 영향**: 실시간성을 일부 도입 — 단, 실제 AI 송신 흐름은 1a50c14 이후 완성.

### e8e1b65 — Add AI server gRPC integration flow
**의도**: AI 측 gRPC 구현을 본격 도입 (별도 브랜치/PR 흐름).
**AI 쪽**: `ai-server/app/grpc/` 패키지 신설
  - `exercise_servicer.py` (서버 진입점)
  - `server.py` (구동)
  - `auth_interceptor.py` (Spring과 대칭)
  - `spring_client.py` (콜백 client)
  - `session_registry.py` (세션 in-memory 저장)
  - `pose_analysis_engine.py` (분석 엔진 신설)
  - `docs/grpc_ai_server_design.md` (AI 측 설계 문서)
**결합 영향**: AI가 “mock 서버”에서 **실제 gRPC 서버**로 진화. 이때 두 개의 평행 구현(mock_server.py vs grpc/) 이 한동안 공존.

---

## 3. 실통합·신뢰성 강화 (2026-05-16 ~ 2026-05-17)

### 1a50c14 — feat: AI 서버 mock 제거하고 실제 gRPC 통합으로 전환
**의도**: 평행 구현 종료, mock 제거.
**AI 쪽 변경 요약**:
  - `mock_server.py` **삭제** (-124줄)
  - `app/grpc/exercise_servicer.py` 본격 구현 (+164줄)
  - `app/grpc/server.py` 본격 구동 (+70줄)
  - `app/grpc/session_state.py` 신설 — in-memory 세션 상태 (+95줄)
  - `app/grpc/spring_client.py` 콜백 client (+83줄)
  - `app/core/squat_analyzer.py` 실시간 스트리밍 분석기 확장
  - `app/api/endpoints/pose.py` 분석기 진입점 정리
**결합 영향**: 비로소 mock 아닌 **실 통신**. proto·인증·콜백 모두 살아 있는 통합 상태.

### 4a0f456 — fix: AI 서버 gRPC 통합 복원 및 충돌 잔재 제거
**의도**: e8e1b65(`session_registry.py`, `pose_analysis_engine.py`) 흐름과 1a50c14(`session_state.py`) 흐름이 머지에서 섞이며 남은 파일 정리.
**AI 쪽 변경 요약**:
  - `session_registry.py` -71줄 삭제
  - `pose_analysis_engine.py` -320줄 삭제
  - `exercise_servicer.py` +126줄 (정리·통합)
  - `auth_interceptor.py` -26줄 (server.py 내부로 합침)
  - `spring_client.py` +63줄
**결합 영향**: AI 쪽 결합 표면이 한 갈래로 수렴. ([`feedback-preview-scope-before-bulk-changes`](../../../C:/Users/khjae/.claude/projects/E--init/memory/feedback_preview_scope_before_bulk_changes.md) 의 계기가 된 작업.)

### 94acf6d — chore: ai-server/app/grpc 패키지 docstring 복원
**Spring↔AI 결합 영향**: 없음 (단순 docstring 1줄).

### b568706 — chore: AI 서버 루트 로거를 INFO 로 설정
**AI 쪽**: `app/main.py` +4줄.
**결합 영향**: 운영 가시성 ↑. 인터페이스 무변경.

### 7d51cf6 — refactor: 사용처 없는 GrpcConfig 삭제
**Spring 쪽**: `global/grpc/GrpcConfig.java` -22줄.
**결합 영향**: grpc-spring-boot-starter가 channel 빈을 자동 등록하므로 수동 설정 제거. 결합 단순화.

### 0fe056e / 8e3fdf1 — MySQL charset/줄바꿈
**의도**: 한글 데이터 정합성 + `.cnf` 줄바꿈 LF 강제 (Windows CRLF로 mysql이 무시하던 문제).
**docker-compose**: `+1`.
**결합 영향**: AI↔Spring 직접 인터페이스 무변경. 단, 한글 메시지(=AI 콜백의 feedback_message)가 DB에 깨지지 않고 들어가게 됨.

### 8ac8248 — fix: gRPC StopAnalysis 세션 ID long 손실 + 응답 DTO 정수 타입 일관성
**Spring 쪽**:
  - `ExerciseAnalysisService.stopAnalysis` 에서 `.intValue()` 제거 → `long` 보존
  - `ExercisesResponseDto`, `SessionUpdateResponseDto` 의 sessionId/exerciseId 타입 `Long` 으로 통일
  - `InternalExerciseController` **삭제** (-36줄) — gRPC 전환 이후 잔재
  - `PoseDataRequestDto` 삭제 (-25줄)
**결합 영향**:
  - proto의 `session_id(int64)` 와 Spring DTO·서비스 코드 타입 정렬
  - 초기 REST 콜백 컨트롤러를 비로소 완전 제거 → 결합 표면 단일화

### c7657f1 — refactor: AI 서버 동시성·콜백 신뢰성 강화 (P1 Phase A)
**AI 쪽**:
  - `pose.py` async → sync 전환 (MediaPipe 블로킹이 이벤트 루프 점유하던 문제)
  - `mediapipe_detector` Singleton → `threading.local` (race 제거)
  - `spring_client.report_complete_analysis` 3회 재시도 + 1s/3s 지수 백오프
**docker-compose**: AI 포트를 `ports`(외부 노출) → `expose`(내부 전용) 로 변경.
**결합 영향**:
  - 콜백 신뢰성 1차 강화 (3회 재시도가 이때 도입)
  - 보안: AI HTTP/gRPC를 내부망 전용으로 — 무인증 endpoint 외부 노출 차단

### 143a2e4 — feat: PUT /exercises/sessions/{id}/stop 추가 + /complete 디프리케이트
**Spring 쪽**: `ExercisesController` +29/-4.
**결합 영향**:
  - 프론트가 자체 계산한 통계를 `/complete` 로 직접 넣던 경로 폐기 (Spring↔AI 권위 충돌 제거)
  - 신경로: 프론트 → Spring `/stop` → gRPC `StopAnalysis` → AI 분석기 → AI 콜백 `CompleteAnalysis` → Spring DB
  - “AI = 운동 통계의 단일 진실 원천” 원칙 코드 레벨 확립

---

## 4. 전달 의미론·회복탄력성·관측성 (2026-05-25 ~ 2026-08-08) 🆕

> 🔴 **이 절은 2026-08-08 에 뒤늦게 채웠다.** 이 문서는 2026-05-23 에서 멈춰 있었고 그 사이 결합면에 커밋 21건이 들어갔다. **§1~§3 이 "경로를 만드는" 시기였다면 이 시기는 "그 경로의 보장을 바꾸는" 시기다** — RPC 는 2개 늘었지만(`ReattachAnalysis`·`ReportFeedbackBatch`) 더 큰 변화는 **같은 RPC 의 전달 의미론**이었다.

### baffa48 — feat(tts,session): AI→Spring 피드백 batch gRPC 통일 + 세션 종료 ET-H (2026-05-25)

- **proto**: `ReportFeedbackBatch` 신설. TTS 발화 이벤트를 REST 가 아니라 gRPC 단일 채널로
- **Spring**: `ExerciseGrpcService.reportFeedbackBatch` → `FeedbackLogService`. REST endpoint 폐기
- **AI**: proto 만 동기. ⚠️ **호출부는 이때도, 지금도 없다** — 아래 §6 참조

### 83ad508 / dc701ab — fix(security): AI 의존성 취약점 (2026-07-19)

- **AI**: `protobuf 4.25.3 → 4.25.8`(DoS), `python-multipart → 0.0.32`
- 📌 **결합면에서 의미**: Spring 은 `protobuf-java 3.25.x` 라인이라 **양쪽 protobuf 메이저가 갈렸다.** wire format 은 호환이지만 버전을 같이 올려주는 장치는 없다

### d8349f3 — feat(persona): 페르소나별 싱크로율 임계값 실제 연결 (2026-07-22)

- **AI**: 임계값이 하드코딩에서 페르소나 기반으로. proto 계약 변경 없이 **AI 내부 판정 기준만** 바뀐 첫 사례

### 0c47598 · 215d49a — feat/fix(resilience): deadline + 서킷브레이커 (2026-07-11) ⭐

- **Spring**: 모든 Spring → AI 스텁에 `withDeadlineAfter` 적용(`0c47598` — *"hang 을 실패로 잡음"*), Resilience4j 서킷브레이커 `aiServer` 인스턴스 적용(`215d49a`)
- **AI**: 없음
- 📌 **이때부터 "AI 가 안 죽고 느려지는" 경우가 결합 실패로 분류된다.** 그전에는 호출 스레드가 무한 대기했다

### aaf576a · bfa4d50 — feat(backend,ai): 관측성 1차 — correlation id 전파 (2026-07-28) ⭐

- **Spring**: `CorrelationIdFilter`(HTTP `X-Request-Id`) · `GrpcCorrelation{Client,Server}Interceptor`(메타데이터 `x-request-id`) · `CorrelationIds`(MDC) · 커스텀 지표
- **AI**: `app/grpc/correlation.py` — `ContextVar` 기반 수신·전파
- **결합면에서 의미**: **처음으로 두 서비스의 로그를 한 요청으로 이어 볼 수 있게 됐다.** gRPC 메타데이터에 결합 계약이 하나 늘었다(proto 밖의 계약)
- `bfa4d50`: **백그라운드 스레드 경계에서 cid 가 끊기던 것** 수정 — MDC 가 ThreadLocal 이라 생긴 문제

### cb26e4a · 993dfa1 · eebf852 — feat(outbox): 세션 종료 통보를 아웃박스로 (2026-07-29) ⭐⭐ 결합 의미론 변경

- **Spring**: `OutboxEvent` 엔티티·`OutboxEventRepository`(`cb26e4a`) → `stopAnalysis` 가 gRPC 직접 호출을 버리고 **세션 상태와 같은 트랜잭션에 이벤트를 적재**, `OutboxPublisher` 가 폴링 발행(`993dfa1`)
- **AI**: 없음 — ⚠️ **단, AI 의 `StopAnalysis` 가 멱등해야 성립한다**(재시도가 같은 통보를 두 번 보낼 수 있다)
- **의미**: `StopAnalysis` 의 전달 의미론이 **at-most-once → at-least-once**. dual-write(커밋과 송신이 별개라 커밋 후 송신 실패 시 유실)를 구조적으로 닫았다. *"유실 0"* 은 실측
- `eebf852`: 소유권 조건부 갱신(CAS) — 발행기가 둘 이상일 때 같은 행을 두 번 집는 것 방지 (CodeRabbit)
- 📌 **이 커밋 묶음이 §1~§3 전체를 통해 가장 큰 결합 변경이다.** RPC 목록은 그대로인데 **보장이 달라졌다** — 그래서 [`ai-backend-integration.md`](./ai-backend-integration.md) §6 이 2.5개월간 틀린 채로 있었다

### 084fac7 · c98d405 — feat(session): 세션 재부착 (2026-07-31, #59 2단계) ⭐

- **proto**: `ReattachAnalysis(ReattachRequest) → ReattachResponse` 신설
- **Spring**: `ExerciseAnalysisService.reattachSession` — DB 조회를 트랜잭션에서 끝내고 **트랜잭션 밖에서** gRPC 호출(커넥션 점유 방지). `SessionController` 엔드포인트
- **AI**: `ExerciseServicer.ReattachAnalysis` — **있으면 보존, 없으면 DB 값으로 생성**
- 📌 **`StartAnalysis` 에 합치지 않은 이유가 proto 주석에 박혀 있다**: 멱등 규칙이 정반대라 한 핸들러에 섞으면 **정상 시작이 조용히 no-op** 이 될 수 있다
- **의미**: §3 시기의 알려진 약점(*"AI 재시작 시 진행 중 세션 전부 소실"*)에 **복구 경로**가 생겼다. ⚠️ 자동은 아니다 — 프론트가 불러야 한다

### e28bc65 · 025a014 · d440cae — 타임아웃과 재부착이 만나는 자리 (2026-08-03)

- **Spring**: 타임아웃으로 걷어가는 세션도 AI 에 통보(`e28bc65`, #98) — 그전엔 Spring 만 `FAILED` 로 바꾸고 AI 엔 orphan 이 남았다. 종료가 먼저 온 경우 통보를 **중복 적재하지 않게** 수정(`025a014`, CodeRabbit #100)
- `d440cae`: **재부착 ↔ 타임아웃 레이스**의 결과를 테스트로 확정(#77 → #98)
- **의미**: 아웃박스·재부착이 들어오면서 **"누가 세션을 끝내는가"의 경쟁 조합이 늘었다.** 이 세 커밋이 그 조합을 좁힌다

### e7e04b7 · 35c8d1e — fix(ai): rep 판정·버퍼 (2026-08-03)

- **AI**: 휴식 중 앉았다 일어나기를 rep 으로 세지 않음(#93), rep 프레임 버퍼 상한(#91)
- proto 무변경. **결합 계약이 아니라 AI 판정 품질** — 다만 `SavePoseDataBatch` 로 흐르는 **행 수와 내용이 달라진다**

### d4cfca7 · 4000530 — feat(report): 대표 프레임 = "가장 깊게 앉은 순간" + `is_correct` 삭제 (2026-08-01)

- **proto/DB**: `is_correct` 제거, 대표 프레임 선택 전략 변경(ㄹ안). DB 마이그레이션 동반(`4000530` — 두 `ALTER` 를 한 문장으로)
- **의미**: `PoseDataRequest` 가 나르는 의미가 바뀌었다 — 저장되는 프레임이 "윈도우 최저 sync_rate" 에서 "가장 깊은 지점"으로

### e027889 — fix(test): 머지로 드러난 계약 불일치 2건 (2026-08-07)

- 📌 **§5 «proto 는 결합의 무게 중심» 관찰의 실물 증거.** 브랜치를 합칠 때 proto 계약이 갈라져 있던 것이 **테스트로만** 드러났다 — 런타임 직렬화 실패 전에 잡힌 건 운이 좋았던 쪽이다

### 📌 이 시기의 결합면 요약

| | §1~§3 (2026-03~05) | **§4 (2026-05~08)** |
|---|---|---|
| 주된 활동 | 경로를 **만든다** | 경로의 **보장을 바꾼다** |
| RPC 수 | 0 → 5 | 5 → **7** |
| Spring→AI 전달 | at-most-once | **at-least-once**(종료 통보만) |
| AI→Spring 전달 | 3회 재시도 후 유실 | **그대로** — 한쪽만 닫혔다 |
| 실패 처리 | 무한 대기 가능 | **deadline + 서킷브레이커** |
| 추적성 | 없음 | **correlation id 양방향** |
| proto 밖 계약 | 토큰 메타데이터 | **+ `x-request-id` 메타데이터** |

---

## 5. 결합 요소별 변경 시점

| 결합 요소 | 도입 커밋 | 그 이후 큰 변경 |
|---------|---------|--------------|
| REST 통신 (WebClient) | 660e294, 0d89668 | gRPC 도입 후 8ac8248에서 잔재 제거 |
| Spring gRPC 클라이언트 | d6cfc2e, 6ce9a43 | 48bb0fc에서 첫 비즈니스 RPC, 7d51cf6에서 수동 설정 제거 |
| Spring gRPC 서버 (콜백 수신) | 953bad6 | f172933, ea1c636 등에서 RPC 추가 |
| proto 양쪽 동기 | 48bb0fc / 6ac0390 | 953bad6, 4eb153b, ea1c636, c52f677, f172933, 8ac8248 (계속 손이 감) |
| AI gRPC 서버 (실제) | e8e1b65 / 1a50c14 | 4a0f456 통합 정리, c7657f1 thread-safety |
| AI 콜백 client | e8e1b65 / 1a50c14 | c7657f1 재시도 도입 |
| 내부 토큰 인증 | c52f677 (Spring) + e8e1b65/1a50c14 (AI) | — |
| Docker 네트워크 / 토큰 주입 | 6ce9a43 / c52f677 | c7657f1에서 외부 노출 차단 |
| 세션 in-memory 상태 (AI) | e8e1b65 (`session_registry`) → 1a50c14 (`session_state`) | 4a0f456에서 registry 삭제 |
| 낙관적 락 + 타임아웃 양보 | (Spring 측, 명시 도입 커밋 불분명 — SessionTimeoutScheduler 도입 시점) | 143a2e4 흐름에서 의미 강화, **e28bc65·025a014·d440cae 에서 재부착·아웃박스와의 경쟁 정리** |
| **TTS 피드백 채널** (`ReportFeedbackBatch`) | baffa48 (proto + Spring 수신부) | ⚠️ **AI 호출부가 도입된 적이 없다** — §6 |
| **회복탄력성** (deadline·서킷브레이커) | 0c47598 · 215d49a | — |
| **관측성** (correlation id) | aaf576a | bfa4d50(스레드 경계 누락 수정) |
| **아웃박스** (at-least-once) | cb26e4a · 993dfa1 | eebf852(CAS), e28bc65·025a014(타임아웃 경로 편입) |
| **세션 재부착** (`ReattachAnalysis`) | 084fac7 | c98d405, d440cae(타임아웃 레이스 확정) |
| **`user.proto` / `UserService`** | (초기 — 커밋 불명) | 🔴 **한 번도 구현·호출된 적이 없다** — §6 |

---

## 6. 변경 트렌드 관찰

- **결합 표면이 줄어드는 방향으로 진행**: REST 콜백 controller(0d89668)·GrpcConfig(48bb0fc)·mock_server(6ac0390)·session_registry(e8e1b65) 등 초기 도입 컴포넌트가 모두 나중에 제거됨 (8ac8248, 7d51cf6, 1a50c14, 4a0f456). 양쪽 모두 인터페이스 갈래를 줄여가는 추세.
- **신뢰성 작업은 AI 쪽 코드 변경을 동반**: c7657f1에서 thread-local·재시도가 AI에 추가됐고, 그 외 AI 동작 변경은 1a50c14·4a0f456 같은 큰 통합 정리에만 몰려 있음 → [`feedback-minimize-python-changes`](../../../C:/Users/khjae/.claude/projects/E--init/memory/feedback_minimize_python_changes.md) 와 정합.
- **proto는 결합의 무게 중심**: 거의 모든 신기능 도입(953bad6, 4eb153b, ea1c636, f172933, c52f677 일부, 8ac8248 후속) 이 proto 동기 변경을 강제. → [`decisions/ai-backend-coupling.md`](../decisions/ai-backend-coupling.md) §5 분기 B의 근거.
- **권위 충돌 정리**: 143a2e4에서 “프론트가 운동 통계를 직접 만들지 않음” 원칙으로 결합 책임 경계 명확화.

### 🔄 2026-08-08 추가 관찰 — §4 시기가 위 네 줄을 어떻게 바꿨나

- **"결합 표면이 줄어드는 방향"은 §4 에서 깨졌다.** 이 시기에 RPC 가 5 → 7 로 늘고, proto 밖 계약(`x-request-id` 메타데이터)도 하나 늘었다. 다만 **늘어난 이유가 다르다** — §1~§3 의 표면은 *"같은 일을 하는 갈래가 여럿"* 이라 줄일 수 있었고, `ReattachAnalysis` 는 *"멱등 규칙이 정반대라 일부러 나눈 것"* 이다. **줄일 표면과 나눠야 할 표면은 다르다.**
- **"신뢰성 작업은 AI 쪽 코드 변경을 동반"은 §4 에서 뒤집혔다.** 이 시기 신뢰성 작업 3건(deadline·서킷브레이커·아웃박스)은 **전부 Spring 단독**이다. 아웃박스는 AI 에 코드를 한 줄도 안 넣고 전달 의미론을 바꿨다 — ⚠️ 대신 **AI 의 `StopAnalysis` 가 멱등하다는 가정에 의존**한다. **코드 변경이 없는 것과 계약 변경이 없는 것은 다르다.**
- **"proto 는 결합의 무게 중심"은 그대로 유효하고, 증거가 하나 더 생겼다** — `e027889`(머지로 드러난 계약 불일치 2건).
- 🔴 **새 관찰: 선언된 결합면이 흐르지 않는 채로 오래 남는다.** `ReportFeedbackBatch` 는 2026-05-25 에 만들어져 **2.5개월 넘게 수신부만 있는 상태**이고, `user.proto` 는 한 번도 쓰인 적이 없다. 둘 다 **문서에는 "있다"로만 적혀 있어서** 결합 구조만 보면 동작하는 경로처럼 보였다. → 이 문서와 [`ai-backend-integration.md`](./ai-backend-integration.md) §3-1 은 이제 **선언과 실제 사용을 구분해 적는다.**
