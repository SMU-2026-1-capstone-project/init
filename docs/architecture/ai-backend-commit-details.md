# Spring ↔ FastAPI 결합 — 커밋별 구체 변경

마지막 업데이트: 2026-05-23
범위: 각 커밋이 Spring(`backend/`)·FastAPI(`ai-server/`)·`proto`·`docker-compose.yml` 어디를 어떻게 바꿨는지 기능 단위로 정리.
연관: 시간순 요약은 [`ai-backend-changelog.md`](./ai-backend-changelog.md), 현재 결합 현황은 [`ai-backend-integration.md`](./ai-backend-integration.md).

읽는 법: "**proto**", "**Spring**", "**AI**", "**Infra**" 네 칸으로 변경면을 분리. proto는 항상 결합 인터페이스의 변경이라 가장 위.

---

## 그룹 1: 통신 기반 구축 (REST → gRPC 전환)

### 660e294 — feat: 유튜브 링크 업로드 기능 추가 (2026-04-08)
첫 Spring → AI 호출 경로. 이때는 REST.

- **proto**: 없음 (이 시점엔 gRPC 도입 전)
- **Spring**:
  - `ExercisesController` 신설 — 유튜브 URL 받는 endpoint
  - `dto/exercises/FastApiRequestDto` 신설 — AI 보낼 페이로드
  - `global/config/WebClientConfig` — `WebClient` 빈 등록
  - `service/Exercise/ExerciseAnalysisService` 신설 — `WebClient`로 AI HTTP 호출
  - `model/exercise/Exercises.java` → `Exercise.java` 로 rename
  - `repository/ExercisesRepository`, `repository/SessionRepository` 신설
- **AI**: 변경 없음
- **Infra**: 없음

### 0d89668 — feat: Youtube 좌표 데이터 송신 기능 추가 (2026-04-08)
첫 AI → Spring 콜백 경로. REST.

- **proto**: 없음
- **Spring**:
  - `controller/InternalExerciseController` 신설 — AI가 호출할 내부 REST endpoint (인증 없음)
  - `dto/exercises/PoseDataRequestDto` 신설 — 콜백 페이로드
  - `model/exercise/PoseData` 엔티티 신설
  - `repository/PoseDataRepository` 신설
  - `service/Exercise/PoseDataService` 신설
- **AI**: 변경 없음
- **Infra**: `mysql/data.sql` 시드 조정 (-20/+20)

> 이 두 컨트롤러/DTO는 나중에 `8ac8248`에서 모두 삭제됨 — gRPC로 완전 교체된 잔재.

### d6cfc2e — config: gRPC 의존성 추가 (2026-04-13)
gRPC 시대의 진입점. 코드는 아직 없음, 라이브러리만.

- **proto**: 없음
- **Spring**: `build.gradle` +26줄
  - `grpc-client-spring-boot-starter:3.1.0.RELEASE`
  - `grpc-server-spring-boot-starter:3.1.0.RELEASE`
  - `io.grpc:grpc-netty-shaded:1.62.2`, `grpc-protobuf:1.62.2`, `grpc-stub:1.62.2`
  - `com.google.protobuf:protobuf-java:3.25.1`
  - `protobuf-gradle-plugin` 설정
- **AI**: 변경 없음
- **Infra**: 없음

### 6ce9a43 — config: gRPC 설정 완료 (2026-04-13)
양쪽이 gRPC로 한 번이라도 통신 가능한 최소 상태.

- **proto**: `backend/src/main/proto/.proto` 임시 파일 (24줄, mock 정의)
- **Spring**:
  - `global/grpc/UserGrpcService.java` 신설 — gRPC 서버 mock
  - `application.yml`: `grpc.port: 6565`, `grpc.client.*` 설정 추가
  - `Dockerfile`, `.dockerignore` 보정
- **AI**: 변경 없음
- **Infra**: `docker-compose.yml` +1줄 (gRPC 포트 노출)

### 48bb0fc — feat: 유튜브 api gRPC버전 생성 (2026-04-13)
첫 비즈니스 RPC. 유튜브 분석을 REST → gRPC 로.

- **proto** (`backend/src/main/proto/`):
  - `.proto` 삭제 → `user.proto` 로 rename
  - **`exercise.proto` 신설 (34줄)** — `ExerciseService` 정의의 출발점
    - `rpc startAnalysis(AnalyzeRequest) returns (AnalyzeResponse)`
    - `rpc GetFinalPoseData(SessionRequest) returns (PoseDataList)`
    - `AnalyzeRequest`: `exercise_id`, `youtube_id`, `session_id`
    - `AnalyzeResponse`: `success`
    - `PoseDataResponse`: `sessionId`, `timestampSec`, `jointCoordinates`
- **Spring**:
  - `global/grpc/GrpcConfig.java` 신설 — gRPC 채널 빈 (나중에 `7d51cf6`에서 삭제)
  - `global/grpc/UserGrpcService.java` 삭제 (-22줄)
  - `service/Exercise/ExerciseAnalysisService` 에 gRPC 호출 로직 +44줄
  - `dto/exercises/PoseDataRequestDto` -7줄 (REST DTO 슬림화)
  - `application.yml` gRPC 클라이언트 주소 추가
- **AI**: 변경 없음
- **Infra**: 없음

### 6ac0390 — test: 유튜브 분석 요청 gRPC api 테스트 완료 (2026-04-14)
AI 측에서 처음으로 gRPC를 받음 — **단, mock 서버**. proto 중복 동기화 시작.

- **proto** (`ai-server/app/proto/`):
  - **`exercise.proto` 신설 (75줄)** — Spring 측과 동일하지만 주석·구조 정리 + 다음 추가:
    - `rpc SavePoseDataBatch (PoseDataBatchRequest) returns (PoseDataResponse)` (AI → Spring 콜백)
    - `PoseDataRequest`: `timestamp_sec`, `joint_coordinates`
    - `PoseDataBatchRequest`: `session_id`, `pose_data[]`
    - `AnalyzeRequest`의 `youtube_id` 그대로
- **AI**:
  - `app/proto/.proto` 빈 placeholder
  - `exercise_pb2.py`, `exercise_pb2_grpc.py` 코드 생성 산출물 commit (당시 패턴)
  - **`mock_server.py` 신설 (27줄)** — gRPC 서버 mock 진입
  - `Dockerfile` 보정 (+5/-1)
  - `requirements.txt` +3줄 (grpcio 등)
- **Spring**: 변경 없음 (proto는 6ac0390 이후 953bad6에서 Spring 쪽도 동기화)
- **Infra**: 없음

---

## 그룹 2: 양방향 결합 완성 (콜백 RPC 추가)

### 953bad6 — feat: spring 운동 결과 수령 api 구현 (2026-04-14)
**Spring이 gRPC 서버도 되는 시점.** 콜백 RPC 정식 정의.

- **proto** (양쪽 동일하게 `+93/-30`):
  - **신규 RPC**: `CompleteAnalysis(SessionCompleteRequest) returns (SessionCompleteResponse)` — AI → Spring 종료 콜백
  - **신규 enum**: `SessionStatus { IN_PROGRESS=0, COMPLETED=1, FAILED=2 }`
  - **`AnalyzeRequest` 필드 변경**: `youtube_id` 제거 → `reference_source(string)` 추가 (의미 확장)
  - **`AnalyzeResponse` 필드 확장**: `success` 외에 `session_id`, `exercise_id`, `start_time(Timestamp)`, `status(SessionStatus)` 추가
  - **신규 메시지**: `SessionCompleteRequest`(session_id, total_reps, avg/max/min_sync_rate, calories_burned, difficulty_level), `SessionCompleteResponse`(session_id, status, end_time)
  - `google/protobuf/timestamp.proto` import 추가
- **Spring**:
  - **`service/Exercise/ExerciseGrpcService` 신설 (+27줄)** — `@GrpcService`로 콜백 수신 서버 진입
  - `service/Exercise/SessionService` +13줄 — `completeSession` 등 메서드 추가
  - `service/Exercise/ExerciseAnalysisService` ±7
- **AI**:
  - `mock_server.py` +28/-10 — `CompleteAnalysis` mock 응답 추가
  - `requirements.txt` 보정
  - `Dockerfile` +1
- **Infra**: 없음

### 4eb153b — feat: 운동 좌표 fastapi로 보내는 api 구현 (2026-04-15)
DB의 기준 좌표를 Spring → AI로 송신. **`ExtractReferenceData` RPC 신설.**

- **proto** (양쪽 동일):
  - **신규 RPC**: `ExtractReferenceData(ExtractRequest) returns (ExtractResponse)` — 등록 단계 (관리자가 유튜브 URL → 기준 좌표 추출)
  - **`AnalyzeRequest` 필드 추가**: `repeated PoseDataRequest reference_poses = 4` — 실행 단계에서 Spring DB의 기준 좌표 리스트 전달
  - **신규 메시지**: `ExtractRequest`(exercise_id, youtube_url, extracted_poses[]), `ExtractResponse`(success, exercise_id, extracted_poses[])
- **Spring**:
  - **`model/exercise/ExerciseReference` 엔티티 신설 (+25줄)** — 기준 좌표 영속화
  - **`repository/ExerciseReferenceRepository` 신설 (+10줄)**
  - `controller/ExercisesController` +31줄 — 등록 endpoint 등
  - `service/Exercise/ExerciseAnalysisService` +87/-… — `extractReferencePoses` 추가, `sendAnalysisRequestToFastApi`에서 DB에서 reference 조회 후 첨부
  - `service/Exercise/ExerciseGrpcService` +50/-… — `extractReferenceData` 콜백 수신 + DB 저장
  - `service/Exercise/PoseDataService` +31줄 — `saveReferencePoses`
  - `service/Exercise/SessionService` +4
- **AI**:
  - `app/proto/exercise.proto` Spring 측과 동기 변경
  - `app/proto/exercise_pb2.py`, `exercise_pb2_grpc.py` 코드 생성물
  - `mock_server.py` +64/-? — extract/start mock 분기 확장
- **Infra**:
  - `docker-compose.yml` +1
  - **`mysql/schema.sql` +11** — `exercise_references` 테이블 컬럼 추가

---

## 그룹 3: 운동 종료 흐름

### ea1c636 — feat: 운동 종료 기능 변경 (2026-04-17)
사용자 중단(stop) 신호를 정식 RPC로.

- **proto** (Spring `+13`, AI도 동일 변경):
  - **신규 RPC**: `StopAnalysis(StopRequest) returns (StopResponse)`
  - **신규 메시지**: `StopRequest`(session_id), `StopResponse`(success, message, session_id)
- **Spring**:
  - `controller/ExercisesController` +35줄 — 종료 endpoint
  - DTO 폴더 재배치: `dto/exercises/ExercisesRequestDto.java` → `dto/exercises/session/ExercisesRequestDto.java`, `ExercisesResponseDto` 동일
  - **신규 DTO**: `dto/exercises/session/SessionUpdateRequestDto` (+17줄), `SessionUpdateResponseDto` (+18줄)
  - `service/Exercise/ExerciseAnalysisService` +109/-? — `stopAnalysis` 메서드
  - `service/Exercise/ExerciseGrpcService` +9
- **AI**:
  - `mock_server.py` +103/-? — Stop/Complete 분기 mock 응답
  - `exercise_pb2.py`, `exercise_pb2_grpc.py` 재생성
- **Infra**: 없음

### 2dd55e0 — chore: 운동분석 서비스로직+컨트롤러 수정 (2026-04-27)
proto 주석 정리 + 시드 보정 + 양쪽 흐름 조정.

- **proto** (`ai-server/app/proto/exercise.proto`):
  - 주석 변경: `// ✅ 사용자가 운동을 중단했을 때 …` 주석 추가
  - 섹션 헤더(`// ✅ 4. 분석 중단 관련 메시지` 등) 삭제
- **Spring**:
  - `controller/ExercisesController` ±37
  - `service/Exercise/ExerciseAnalysisService` ±27
  - `service/Exercise/SessionService` -27 (책임 축소)
  - `dto/exercises/VideoRequestDto` -4
  - `model/member/Member`, `SelectedPersona` 마이너 조정
  - `application.yml` ±2
- **AI**: proto만
- **Infra**:
  - `mysql/data.sql` ±87 — 시드 정리
  - `mysql/schema.sql` ±12

---

## 그룹 4: 인증 (gRPC 토큰)

### c52f677 — feat: gRPC 토큰 검증 추가 (2026-04-27)
양쪽 동일 토큰 공유 방식의 시작.

- **proto**: 없음
- **Spring**:
  - **`global/config/InternalAuthInterceptor` 신설 (+40줄)** — `ServerInterceptor` 구현, `Authorization: Bearer {token}` 헤더 검증, 불일치 시 `Status.UNAUTHENTICATED.withDescription("유효하지 않은 토큰")`
  - `service/Exercise/ExerciseAnalysisService` +39/-… — client 측에서 매 호출 metadata에 토큰 첨부
  - `controller/ExercisesController` ±5
  - `service/Exercise/ExerciseGrpcService` ±3
  - `application.yml`: `internal.api.token: ${INTERNAL_API_TOKEN}`
  - `dto/onboarding/OnboardingDto`, `OnboardingRequestDto` 마이너 조정
  - `model/exercise/Exercise`, `model/member/Member` 마이너 조정
  - `service/Member/OnboardingService` ±2
- **AI**: 변경 없음 (AI 측 인증 인터셉터는 e8e1b65 / 1a50c14에서 추가)
- **Infra**:
  - `docker-compose.yml` ±7 — `INTERNAL_API_TOKEN` 환경변수 양쪽 컨테이너에 주입
  - `mysql/data.sql` ±17, `schema.sql` ±4

---

## 그룹 5: 실시간 데이터 (중간 저장)

### f172933 — feat: 운동 중간 저장 로직 추가 (2026-04-27)
포즈 데이터에 분석 결과 첨부.

- **proto** (Spring 측, +2줄):
  - **`PoseDataRequest`에 필드 추가**: `double sync_rate = 3`, `string feedback_message = 4`
  - (AI 측 proto는 2dd55e0에서 별도 동기)
- **Spring**:
  - `service/Exercise/PoseDataService` +72/-… — 배치 저장 로직, 분석 결과까지 영속
  - `service/Exercise/ExerciseGrpcService` ±19 — 콜백 수신 시 새 필드 처리
  - `controller/ExercisesController` ±4
- **AI**: 없음 (proto 동기는 2dd55e0)
- **Infra**: 없음

---

## 그룹 6: AI 실통합 (mock 제거)

### e8e1b65 — Add AI server gRPC integration flow (2026-04-28)
**AI 측 gRPC 패키지 첫 도입** (별도 PR 흐름).

- **proto**: 없음
- **Spring**: `service/Exercise/ExerciseAnalysisService` ±5
- **AI** (전부 신설):
  - `app/grpc/__init__.py` (+1)
  - **`app/grpc/exercise_servicer.py` (+85)** — gRPC 서버 서비서 진입
  - **`app/grpc/server.py` (+72)** — `grpc.server` 구동
  - **`app/grpc/auth_interceptor.py` (+26)** — Spring과 대칭 인증 (나중에 4a0f456에서 server.py로 흡수)
  - **`app/grpc/spring_client.py` (+61)** — 콜백 client
  - **`app/grpc/session_registry.py` (+71)** — 세션 in-memory 저장 (나중에 4a0f456에서 삭제)
  - **`app/services/pose_analysis_engine.py` (+320)** — 분석 엔진 (나중에 4a0f456에서 삭제)
  - `app/config.py` +39 — gRPC/토큰/Spring URL 설정
  - `app/main.py` ±8
  - `docs/grpc_ai_server_design.md` (+298) — AI 측 설계 문서
- **Infra**: 없음

### 1a50c14 — feat: AI 서버 mock 제거하고 실제 gRPC 통합으로 전환 (2026-05-16)
**mock_server.py 폐기.**

- **proto**: 없음
- **Spring**: 없음
- **AI**:
  - **`mock_server.py` 삭제 (-124줄)** — 첫 mock 종료
  - `app/grpc/exercise_servicer.py` (+164) — `StartAnalysis`/`StopAnalysis` 실제 구현 (SessionState 생성/제거, 백그라운드 스레드로 `_send_complete_analysis`)
  - `app/grpc/server.py` (+70) — 인터셉터·포트·서버 구동
  - **`app/grpc/session_state.py` 신설 (+95줄)** — `SessionState` 클래스, thread-safe dict
  - `app/grpc/spring_client.py` (+83) — `report_pose_data_batch`, `report_complete_analysis`
  - `app/api/endpoints/pose.py` +121 — 분석기 진입점 정리
  - `app/core/squat_analyzer.py` +115 — 실시간 스트리밍 분석기 보강
  - `app/config.py` +9
  - `app/main.py` +16
  - `app/models/pose.py` +11
  - `Dockerfile` ±9
- **Infra**: 없음

### 4a0f456 — fix: AI 서버 gRPC 통합 복원 및 충돌 잔재 제거 (2026-05-16)
e8e1b65 PR 흐름과 1a50c14 흐름이 머지에서 섞인 잔재 정리.

- **proto**: 없음
- **Spring**: 없음
- **AI**:
  - **`app/grpc/session_registry.py` 삭제 (-71)** — `session_state.py`와 중복
  - **`app/services/pose_analysis_engine.py` 삭제 (-320)** — `core/squat_analyzer.py`와 중복
  - `app/grpc/auth_interceptor.py` 삭제 (-26) — `server.py` 내부로 흡수
  - `app/grpc/exercise_servicer.py` +126 — 통합·정리
  - `app/grpc/server.py` +58 — `AuthInterceptor`를 클래스로 인라인
  - `app/grpc/spring_client.py` +63
  - `app/main.py` +16
  - `app/config.py` +9
- **Infra**: 없음

### 94acf6d — chore: ai-server/app/grpc 패키지 docstring 복원 (2026-05-16)
- **AI**: `app/grpc/__init__.py` +1줄 (docstring 한 줄). Spring↔AI 인터페이스 무변경.

### b568706 — chore: AI 서버 루트 로거를 INFO 로 설정 (2026-05-16)
- **AI**: `app/main.py` +4줄 (루트 로거 INFO). 인터페이스 무변경, 운영 가시성 ↑.

---

## 그룹 7: 결합 정리

### 7d51cf6 — refactor: 사용처 없는 GrpcConfig 삭제 (2026-05-16)
`grpc-spring-boot-starter`가 channel 빈을 자동 등록하므로 수동 설정 불필요.

- **Spring**: `global/grpc/GrpcConfig.java` -22줄 삭제
- **AI**: 없음
- **Infra**: 없음

### 8ac8248 — fix: gRPC StopAnalysis 세션 ID long 손실 + 응답 DTO 정수 타입 일관성 (2026-05-17)
proto의 `int64`와 Spring DTO 타입 정렬 + REST 시대 잔재 제거.

- **proto**: 없음
- **Spring** (총 -68/+7):
  - `service/Exercise/ExerciseAnalysisService.stopAnalysis`:
    ```diff
    - .setSessionId(sessionId.intValue())
    + .setSessionId(sessionId)
    ```
    (`Long` → `int32` wrap-around 위험 제거; `setSessionId(long)` 시그니처 사용)
  - `dto/exercises/session/ExercisesResponseDto` ±4 — `sessionId`/`exerciseId` 타입 `Long`으로 통일
  - `dto/exercises/session/SessionUpdateResponseDto` ±2 — 동일
  - `controller/ExercisesController` ±6 — 타입 일치 보정
  - **`controller/InternalExerciseController` 삭제 (-36줄)** — gRPC 전환 후 호출자 없음 (0d89668에서 도입된 REST 콜백의 마지막 잔재)
  - **`dto/exercises/PoseDataRequestDto` 삭제 (-25줄)** — `PoseDataService` 시그니처 불일치로 컴파일 차단 상태였음
- **AI**: 없음
- **Infra**: 없음

---

## 그룹 8: 신뢰성·보안 (P1 Phase A)

### c7657f1 — refactor: AI 서버 동시성·콜백 신뢰성 강화 (P1 Phase A) (2026-05-17)
재시도·thread-safety·외부 노출 차단.

- **proto**: 없음
- **Spring**: 없음
- **AI**:
  - `app/api/endpoints/pose.py` ±9 — 핸들러 `async def` → `def` 로 전환 (MediaPipe 블로킹 이슈, FastAPI threadpool 위임)
  - `app/core/mediapipe_detector.py` ±14 — `PoseDetector` Singleton → `threading.local` 인스턴스 (thread-safety)
  - **`app/grpc/spring_client.py` ±64** — `report_complete_analysis` 에 재시도 추가:
    - `_COMPLETE_MAX_ATTEMPTS = 3`
    - `_COMPLETE_BACKOFF_SECONDS = (1.0, 3.0)`
    - 1회 실패 후 1초 sleep, 2회 실패 후 3초 sleep, 3회 실패 시 ERROR 로그만 남기고 포기
- **Infra**:
  - **`docker-compose.yml` ±9** — `shadowfit-ai`의 8000/8585 포트를 `ports` → `expose` 로 변경
    ```diff
    -    ports:
    -      - "8000:8000"
    -      - "8585:8585"
    +    expose:
    +      - "8000"
    +      - "8585"
    ```

### 143a2e4 — feat: PUT /exercises/sessions/{id}/stop 추가 + /complete 디프리케이트 (2026-05-17)
프론트 → Spring 결제 흐름의 권위 충돌 해소.

- **proto**: 없음
- **Spring** (`controller/ExercisesController` +29/-4):
  - **신규 endpoint**: `PUT /exercises/sessions/{sessionId}/stop` — `analysisService.stopAnalysis(sessionId)` 호출 후 `202 Accepted`
  - **기존 endpoint** `PUT /exercises/sessions/{sessionId}/complete` 에 `@Deprecated` 부착 + Swagger description에 "사용 자제" 명시
  - 흐름:
    ```
    프론트 → Spring /stop
           → gRPC StopAnalysis
           → AI 분석기 종료
           → gRPC CompleteAnalysis (콜백)
           → Spring SessionService.completeSession
           → DB Session.status = COMPLETED
    ```
- **AI**: 없음
- **Infra**: 없음

---

## 보조 그룹: 직접 영향 적은 잡정리

### 0fe056e — fix: MySQL 클라이언트 charset 을 utf8mb4 로 강제 (2026-05-16)
- **Infra**: `mysql/my.cnf` +8줄 (`[client] default-character-set=utf8mb4` 등), `docker-compose.yml` +1줄.
- Spring↔AI 인터페이스 무변경, 단 한글 `feedback_message`가 DB에 깨지지 않고 들어감.

### 8e3fdf1 — chore: mysql/*.cnf 줄바꿈을 LF 로 강제 (2026-05-16)
- **Infra**: `.gitattributes` +1줄. Windows CRLF로 MySQL이 `.cnf`를 무시하던 문제 차단.

---

## 부록: 한 화면에 보는 RPC 진화

| 시점 | 커밋 | 추가된/변경된 RPC | 비고 |
|------|------|----------------|------|
| 2026-04-13 | 48bb0fc | `startAnalysis`, `GetFinalPoseData` | Spring 측 첫 정의 |
| 2026-04-14 | 6ac0390 | (AI측 동기 + `SavePoseDataBatch` 추가) | AI proto 첫 도입 |
| 2026-04-14 | 953bad6 | + `CompleteAnalysis`, `SessionStatus` enum, `AnalyzeRequest`에 `reference_source`, `AnalyzeResponse` 대폭 확장 | 콜백 RPC 본격화 |
| 2026-04-15 | 4eb153b | + `ExtractReferenceData`, `AnalyzeRequest.reference_poses[]` | 기준 좌표 전달 |
| 2026-04-17 | ea1c636 | + `StopAnalysis`(`StopRequest`/`StopResponse`) | 중단 RPC |
| 2026-04-27 | f172933 | `PoseDataRequest`에 `sync_rate`, `feedback_message` | 실시간 결과 동반 |
| 2026-04-27 | 2dd55e0 | (AI측 동기 + 주석 정리) | 양쪽 proto 정렬 |
| 이후 | — | RPC 추가 없음 | 안정화 |

총 정의된 RPC: `ExtractReferenceData`, `StartAnalysis`, `StopAnalysis`, `SavePoseDataBatch`, `CompleteAnalysis`, `GetFinalPoseData` (미사용).

---

## 부록: 결합 표면(Spring 측 핵심 파일) 라이프사이클

| 파일 | 도입 | 마지막 큰 변경 | 현재 상태 |
|------|------|-------------|---------|
| `controller/ExercisesController` | 660e294 | 143a2e4 | 유지 (현역 endpoint) |
| `controller/InternalExerciseController` | 0d89668 | — | **8ac8248에서 삭제** |
| `dto/exercises/FastApiRequestDto` | 660e294 | — | (REST DTO, 사실상 사용처 없음) |
| `dto/exercises/PoseDataRequestDto` | 0d89668 | 8ac8248 | **8ac8248에서 삭제** |
| `dto/exercises/session/SessionUpdateRequestDto` | ea1c636 | — | 유지 (`/complete` deprecated) |
| `dto/exercises/session/SessionUpdateResponseDto` | ea1c636 | 8ac8248 | 유지 (`Long` 정렬) |
| `dto/exercises/session/ExercisesResponseDto` | 660e294 (구버전) | 8ac8248 | 유지 (`Long` 정렬) |
| `service/Exercise/ExerciseAnalysisService` | 660e294 | 8ac8248 | gRPC 클라이언트 본체 |
| `service/Exercise/ExerciseGrpcService` | 953bad6 | f172933 | gRPC 서버 본체 (콜백 수신) |
| `service/Exercise/PoseDataService` | 0d89668 | f172933 | 콜백 저장 본체 |
| `service/Exercise/SessionService` | (기존) | 143a2e4 흐름 | 세션 상태 전이 본체 |
| `global/grpc/GrpcConfig` | 48bb0fc | — | **7d51cf6에서 삭제** |
| `global/grpc/UserGrpcService` | 6ce9a43 | — | **48bb0fc에서 삭제** |
| `global/config/InternalAuthInterceptor` | c52f677 | — | 유지 (gRPC 인증) |
| `global/config/WebClientConfig` | 660e294 | — | 유지 (단, gRPC 전환 후 사실상 미사용) |

## 부록: 결합 표면(AI 측 핵심 파일) 라이프사이클

| 파일 | 도입 | 마지막 큰 변경 | 현재 상태 |
|------|------|-------------|---------|
| `app/proto/exercise.proto` | 6ac0390 | 2dd55e0 | 유지 (Spring과 수동 동기) |
| `mock_server.py` | 6ac0390 | ea1c636 | **1a50c14에서 삭제** |
| `app/grpc/__init__.py` | e8e1b65 | 94acf6d | 유지 |
| `app/grpc/exercise_servicer.py` | e8e1b65 | 4a0f456 | 유지 (gRPC 서비서) |
| `app/grpc/server.py` | e8e1b65 | 4a0f456 | 유지 (구동 + 인증 인라인) |
| `app/grpc/auth_interceptor.py` | e8e1b65 | — | **4a0f456에서 삭제** (`server.py`로 합침) |
| `app/grpc/spring_client.py` | e8e1b65 | c7657f1 | 유지 (콜백 client + 재시도) |
| `app/grpc/session_registry.py` | e8e1b65 | — | **4a0f456에서 삭제** |
| `app/grpc/session_state.py` | 1a50c14 | — | 유지 (in-memory 세션) |
| `app/services/pose_analysis_engine.py` | e8e1b65 | — | **4a0f456에서 삭제** |
| `app/core/squat_analyzer.py` | 2b6b11c | 1a50c14 | 유지 (스트리밍 분석기) |
| `app/core/mediapipe_detector.py` | (초기) | c7657f1 | 유지 (thread-local) |
| `app/api/endpoints/pose.py` | (초기) | c7657f1 | 유지 (sync 핸들러) |
| `app/config.py` | (초기) | e8e1b65/1a50c14 | 유지 (gRPC 타깃·토큰) |
| `app/main.py` | (초기) | b568706 | 유지 (로거·라우터 등록) |
