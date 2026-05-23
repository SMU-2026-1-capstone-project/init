# Spring ↔ FastAPI 결합 변경 이력

마지막 업데이트: 2026-05-23
범위: `ai-server/`(FastAPI)와 `backend/`(Spring Boot) 사이의 결합 방식·통신 프로토콜·인증·데이터 흐름 변경에 직접 영향을 준 커밋들. UI/문서/단순 버그 수정은 제외.

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
```

세 단계로 요약:
1. **REST 시대 (2026-03-30 ~ 04-08)**: WebClient + InternalExerciseController. proto 없음.
2. **gRPC 전환 (2026-04-13 ~ 04-28)**: proto·인증·콜백 흐름 다 갖춤. 단 AI 쪽은 mock 서버.
3. **실통합·신뢰성 강화 (2026-05-16 ~ 05-17)**: mock 제거, 동시성·재시도 보강, API 디프리케이트 정리.

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

## 4. 결합 요소별 변경 시점

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
| 낙관적 락 + 타임아웃 양보 | (Spring 측, 명시 도입 커밋 불분명 — SessionTimeoutScheduler 도입 시점) | 143a2e4 흐름에서 의미 강화 |

---

## 5. 변경 트렌드 관찰

- **결합 표면이 줄어드는 방향으로 진행**: REST 콜백 controller(0d89668)·GrpcConfig(48bb0fc)·mock_server(6ac0390)·session_registry(e8e1b65) 등 초기 도입 컴포넌트가 모두 나중에 제거됨 (8ac8248, 7d51cf6, 1a50c14, 4a0f456). 양쪽 모두 인터페이스 갈래를 줄여가는 추세.
- **신뢰성 작업은 AI 쪽 코드 변경을 동반**: c7657f1에서 thread-local·재시도가 AI에 추가됐고, 그 외 AI 동작 변경은 1a50c14·4a0f456 같은 큰 통합 정리에만 몰려 있음 → [`feedback-minimize-python-changes`](../../../C:/Users/khjae/.claude/projects/E--init/memory/feedback_minimize_python_changes.md) 와 정합.
- **proto는 결합의 무게 중심**: 거의 모든 신기능 도입(953bad6, 4eb153b, ea1c636, f172933, c52f677 일부, 8ac8248 후속) 이 proto 동기 변경을 강제. → [`decisions/ai-backend-coupling.md`](../decisions/ai-backend-coupling.md) §5 분기 B의 근거.
- **권위 충돌 정리**: 143a2e4에서 “프론트가 운동 통계를 직접 만들지 않음” 원칙으로 결합 책임 경계 명확화.
