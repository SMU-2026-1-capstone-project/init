# Spring ↔ FastAPI 결합 — 월별 작업 로그

마지막 업데이트: 2026-05-23
범위: 각 달에 어떤 파일을 어떻게 만졌는지 결합 관점에서 분석 정리. 큰 사실 + "왜 만졌는지" 한 줄.
연관:
- 커밋별 구체 변경 → [`ai-backend-commit-details.md`](./ai-backend-commit-details.md)
- 시간순 흐름 요약 → [`ai-backend-changelog.md`](./ai-backend-changelog.md)
- 현재 결합 현황 → [`ai-backend-integration.md`](./ai-backend-integration.md)

---

## 2026-03 — 마이크로서비스 분리의 출발

이 달의 결합 관점 핵심: **"하나로 굴러가던 코드를 두 프로세스로 쪼개기로 결정"**. 통신 방식은 아직 결정되지 않았다.

### 596b4ad (03-30) — MediaPipe + DTW를 Python FastAPI 마이크로서비스로 분리
- **새 디렉터리**: `ai-server/` 출생.
- **파일 단위 작업** (FastAPI 측):
  - `ai-server/app/main.py` — FastAPI 진입점
  - `ai-server/app/api/endpoints/sync.py` — DTW 싱크 분석 endpoint
  - `ai-server/app/core/dtw_calculator.py` — DTW 계산기
  - `ai-server/app/core/video_processor.py` — OpenCV 영상 처리
  - `ai-server/app/models/sync.py`, `models/video.py` — Pydantic 스키마
  - `ai-server/requirements.txt` — `mediapipe`, `opencv-python-headless`, `fastapi`, `dtaidistance`
- **Spring 측 변경**: 이 커밋에는 거의 없음 (스킬·문서 다수가 섞임).
- **결합 결과**: 두 프로세스 사이의 통신은 아직 정의되지 않음 → 다음 달에 REST → gRPC 순서로 채워짐.

### 80e9974 (03-31) — 피그마 UI 구현
- 결합 관점에서 직접 영향 거의 없음. `application.yml`, `docker-compose.yml` 잡설정 약간.

---

## 2026-04 — 통신 프로토콜 결정과 결합 표면 완성

이 달의 결합 관점 핵심: **"REST로 일단 연결 → gRPC로 전면 교체 → 양방향 콜백·인증·중간저장까지 완성"**. 결합 인터페이스(proto)가 거의 모든 RPC를 이 달에 갖춰진다.

### 04-02 ~ 04-08 — Spring 백엔드 본체 셋업

| 커밋 | 핵심 파일 | 작업 내용 |
|------|---------|---------|
| eccab13 (04-02) | `backend/` 41개 파일 신규 | 백엔드 본체를 별도 리포지토리에서 이 모노레포로 이주. JWT·Security·Member 도메인 일괄 도입. |
| 74b18c4 (04-02) | `backend/build.gradle`, `.env.example` | 의존성 정리, dotenv 보강. |
| 23293f8 (04-02) | `mysql/schema.sql` | DB docs 기준 재정의. |
| fec384f (04-07) | `global/config/WebClientConfig.java` 신설 | **AI에 REST 호출을 보낼 클라이언트 빈 준비** — 결합 표면 1차. |
| 6070f3c (04-07) | `model/exercise/Session.java` 신설 | 세션 엔티티 — 이후 모든 결합 흐름의 키 객체. |
| f5e54d2 (04-07) | `dto/exercises/...` | Exercise DTO 세트 |
| 7b9e1e9 / bbea216 (04-06) | `global/util/YoutubeValidator.java` | 유튜브 URL 검증 유틸 |

### 04-08 — REST 시대 (gRPC 도입 직전)

#### 660e294 (04-08) — feat: 유튜브 링크 업로드
- `controller/ExercisesController.java` — Spring 측 첫 endpoint.
- `dto/exercises/FastApiRequestDto.java` — AI에 보낼 페이로드.
- `service/Exercise/ExerciseAnalysisService.java` — **첫 Spring → AI 호출 (WebClient)**.
- `model/exercise/Exercises.java` → `Exercise.java` rename.
- 결합 결과: 단방향 REST 결합 동작.

#### 0d89668 (04-08) — feat: Youtube 좌표 데이터 송신
- `controller/InternalExerciseController.java` — **첫 AI → Spring 콜백 endpoint (REST, 무인증)**.
- `dto/exercises/PoseDataRequestDto.java` — 콜백 페이로드.
- `model/exercise/PoseData.java` + `repository/PoseDataRepository.java` 신설.
- `service/Exercise/PoseDataService.java` — 콜백 저장 로직.
- 결합 결과: 양방향 REST 결합 동작 — 단, 이 컨트롤러는 4월 말~5월 17일 사이 여러 번 삭제·복귀 후 최종 삭제됨.

### 04-09 — AI 분석 본체

#### 2b6b11c (04-09) — 스쿼트 분석 로직 & 실시간 데모
- AI 측 `app/core/squat_analyzer.py`, `dtw_calculator.py`, `reference_builder.py`, `video_processor.py` 보강.
- `scripts/live_squat_demo.py`, `record_demo_video.py` — 데모/녹화 스크립트.
- 결합 표면 변경 없음. AI 단독 진화.

#### ef2e8e6 (04-09 직전) — 스쿼트 데모 영상
- `ai-server/app/core/pose_filter.py` 신설 (+121).
- `reference_data/squat_*.json` — 기준 좌표 JSON 첫 도입.
- `ai-server/scripts/annotate_squat_video.py` 신설.

### 04-13 — gRPC 전환

| 커밋 | 핵심 파일 | 작업 내용 |
|------|---------|---------|
| 54872a9 → afbf2ac → 6abc3f5 → aa03cda → aa25c4b | `.gitignore`, `.gradle/`, `.idea/`, `.agents/skills/`, `frontend/` | 일련의 빌드 캐시·프론트 추적 정리(결합 표면 무관). |
| **d6cfc2e** (04-13) | `backend/build.gradle` | **gRPC 의존성 일괄 도입**: `grpc-{client,server}-spring-boot-starter:3.1.0`, `io.grpc:* 1.62.2`, `protobuf-java 3.25.1`, `protobuf-gradle-plugin`. |
| **6ce9a43** (04-13) | `application.yml`, `Dockerfile`, `global/grpc/UserGrpcService.java`, `backend/src/main/proto/.proto`, `docker-compose.yml` | **gRPC 첫 핸드쉐이크**: `grpc.port: 6565`, mock 서비스 1개, 포트 노출. |
| **48bb0fc** (04-13) | `backend/src/main/proto/exercise.proto` 신설, `global/grpc/GrpcConfig.java` 신설, `global/grpc/UserGrpcService.java` 삭제, `service/Exercise/ExerciseAnalysisService.java` ±44 | **첫 비즈니스 RPC**: `startAnalysis`, `GetFinalPoseData`. `.proto` → `user.proto` rename. |
| a1393f0 (04-13) | `service/Exercise/ExerciseGrpcService.java` 신설, `PoseDataService.java` ±22, `exercise.proto` ±20 | gRPC 양쪽 통신 첫 e2e 테스트. |

### 04-14 — AI 측 gRPC 합류 + 콜백 RPC 본격화

| 커밋 | 핵심 파일 | 작업 내용 |
|------|---------|---------|
| 54665b4 (04-14) | `backend/src/main/proto/exercise.proto` ±65 | proto 주석 보강. |
| **6ac0390** (04-14) | `ai-server/app/proto/exercise.proto` 신설(75줄), `exercise_pb2*.py`, **`mock_server.py` 신설(+27)** | **AI 측 첫 gRPC 수신** — mock 서버로. proto 중복 동기화 시작. |
| efb062d (04-14) | `service/Exercise/SessionService.java` 분리(+? -77) | 세션 서비스 단일 책임 분리. |
| **953bad6** (04-14) | 양쪽 `exercise.proto` ±93/-30, `service/Exercise/ExerciseGrpcService.java` ±27, `mock_server.py` ±28 | **콜백 RPC 정식**: `CompleteAnalysis`, `SavePoseDataBatch`. `SessionStatus` enum, `AnalyzeRequest`에 `reference_source`, `AnalyzeResponse` 4 필드 확장. |

### 04-15 — 기준 좌표 전달

#### 4eb153b (04-15) — feat: 운동 좌표 fastapi로 보내는 api 구현
- **proto** (양쪽): `ExtractReferenceData` RPC 신설, `AnalyzeRequest.reference_poses[]` 추가, `ExtractRequest/Response` 메시지 신설.
- **Spring**:
  - `model/exercise/ExerciseReference.java` 신설 — 기준 좌표 엔티티.
  - `repository/ExerciseReferenceRepository.java` 신설.
  - `service/Exercise/ExerciseAnalysisService.java` +87 — `extractReferencePoses`, `sendAnalysisRequestToFastApi`에서 DB → gRPC 첨부.
  - `service/Exercise/ExerciseGrpcService.java` +50 — extract 콜백 수신.
  - `service/Exercise/PoseDataService.java` +31 — `saveReferencePoses`.
  - `controller/ExercisesController.java` +31.
- **AI**: `mock_server.py` ±64 — extract/start 분기.
- **DB**: `mysql/schema.sql` — `exercise_references` 테이블 컬럼.

### 04-16 — e2e 안정화

| 커밋 | 핵심 파일 | 작업 |
|------|---------|------|
| aae77bb (04-16) | `controller/ExercisesController.java` ±59, `model/exercise/ExerciseReference.java` ±5, `application.yml`, `docker-compose.yml`, `build.gradle` | 운동 세션 시작 API e2e 테스트 통과. |
| 8256873 (04-16) | `dto/exercises/ExercisesResponseDto.java` +2 | 응답 양식 미세 변경. |

### 04-17 — 종료 RPC

#### ea1c636 (04-17) — feat: 운동 종료 기능 변경
- **proto** (양쪽): `StopAnalysis(StopRequest) → StopResponse` 신설.
- **Spring**:
  - `controller/ExercisesController.java` +35 — 종료 endpoint.
  - DTO 폴더 재배치: `dto/exercises/*` → `dto/exercises/session/*`.
  - `dto/exercises/session/SessionUpdateRequestDto.java` (+17), `SessionUpdateResponseDto.java` (+18) 신설.
  - `service/Exercise/ExerciseAnalysisService.java` +109 — `stopAnalysis` 추가.
  - `service/Exercise/ExerciseGrpcService.java` ±9.
- **AI**: `mock_server.py` +103 — Stop/Complete mock 분기.

### 04-19 — 문서화

| 커밋 | 파일 | 작업 |
|------|------|------|
| 28a65cc (04-19) | `controller/ExercisesController.java`, `ExerciseAnalysisService.java`, `ExerciseGrpcService.java`, `PoseDataService.java`, `SessionService.java` | Swagger 주석 추가, 로직 정리. |

### 04-23 — 보고서(report) 도메인

`dto/report/*`, `model/report/Report.java`, `BaseTimeEntity.java`, `ReportType.java`, `repository/ReportRepository.java`, `controller/ExerciseReportController.java`, `ExerciseRecordController.java`, `service/Report/ReportService.java`, `DailyLogService.java` 등 신설.

결합 관점: **AI ↔ Spring 인터페이스 자체는 무변경**. AI가 보낸 결과를 Spring이 가공/조회하는 도메인이 따로 자라났다.

### 04-24 ~ 04-26 — 마이너 정리

| 커밋 | 파일 | 작업 |
|------|------|------|
| cb160d8 (04-24) | `ExerciseRecordController.java`, `ExerciseReportController.java` | Swagger 주석. |
| f14b82f (04-25) | `dto/exercises/...`, `model/*`, `repository/*` 다수 (+253/-226) | 프론트 양식에 맞춘 DTO·엔티티 정렬. `SessionUpdateRequestDto`/`ResponseDto` 보강. |
| **5ce1872** (04-25) | `controller/InternalExerciseController.java` -36 | **REST 콜백 컨트롤러 1차 삭제** (안쓰는 컨트롤러 정리). |
| b0f8003 (04-25) | `repository/*` 폴더 재배치 (`repository/exercise/`, `member/`, `report/`), `application.yml`, `data.sql`, `schema.sql` 대대적 보정 | 도메인별 폴더링 + 시드 정리. |
| 46968e9 (04-25) | `controller/TestController.java` 신설 | 시드 테스트 헬퍼. |
| 3170577 (04-26) | `mysql/data.sql` ±36 | data.sql 적용 테스트 완료. |
| 016690e (04-26) | `dto/onboarding/OnboardingDto.java`, `model/member/Member.java`, `mysql/schema.sql`, `data.sql` | 온보딩 기준 영상 시드. |

### 04-27 — 인증, 중간 저장, 호환성 작업

#### c52f677 (04-27) — feat: gRPC 토큰 검증
- **Spring 신규**: `global/config/InternalAuthInterceptor.java` (+40) — `Authorization: Bearer {token}` 검증, 불일치 시 `UNAUTHENTICATED`.
- `service/Exercise/ExerciseAnalysisService.java` ±39 — client 측 metadata에 토큰 첨부.
- `application.yml`: `internal.api.token: ${INTERNAL_API_TOKEN}`.
- `docker-compose.yml` ±7 — `INTERNAL_API_TOKEN` 양쪽 컨테이너 주입.
- (AI 측 인터셉터는 이때 아직 없음 → e8e1b65/1a50c14에서 추가)

#### f172933 (04-27) — feat: 운동 중간 저장 로직
- **proto** (Spring 측): `PoseDataRequest`에 `sync_rate(3)`, `feedback_message(4)` 필드 추가.
- `service/Exercise/PoseDataService.java` +72 — 배치 저장 + 분석 결과 영속.
- `service/Exercise/ExerciseGrpcService.java` ±19.

#### 2dd55e0 (04-27) — chore: 운동분석 서비스로직+컨트롤러 수정
- AI 측 `exercise.proto` 동기 (Spring 쪽 f172933 변경에 맞춤).
- `controller/ExercisesController.java`, `service/Exercise/ExerciseAnalysisService.java`, `SessionService.java` ±수십.
- `mysql/data.sql` ±87 (시드 정리).

#### e5f5b29 (04-27) — chore: 호환성 체크
- `controller/InternalExerciseController.java` **+36줄로 다시 추가**.
- `dto/exercises/ExercisesRequestDto.java` (+23), `ExercisesResponseDto.java` (+36) 신설/보강.
- `application.properties` (+44), `application.yml` 일부.
- `.env.example` 대폭 보강 (+92).

#### e66bce8 (04-27) — chore: 에러수정
- `service/Exercise/PoseDataService.java` ±74, `InternalAuthInterceptor.java` ±2, `ExerciseAnalysisService.java` ±9 잔손질.

#### a9f9017 (04-27) — 인증/인가/온보딩 프론트,백엔드api 연동
- `controller/InternalExerciseController.java` **-36 (또 삭제)** — 5ce1872에서 한 번 지운 걸 e5f5b29에서 되돌렸다가 다시 지움.
- `docker-compose.yml` +1, `docs/14-how-to-run.md` +48.
- 프론트 인증·온보딩 흐름 정착.

### 04-28 — AI 측 gRPC 패키지 첫 도입

#### e8e1b65 (04-28) — Add AI server gRPC integration flow
- **AI 측 신규 패키지** `ai-server/app/grpc/`:
  - `__init__.py` (+1)
  - `exercise_servicer.py` (+85) — gRPC 서비서 진입
  - `server.py` (+72) — `grpc.server` 구동
  - `auth_interceptor.py` (+26) — Spring과 대칭 인증
  - `spring_client.py` (+61) — 콜백 client
  - `session_registry.py` (+71) — 세션 in-memory 저장 (이후 삭제)
  - `pose_analysis_engine.py` (+320, `app/services/`) — 분석 엔진 (이후 삭제)
- **AI**: `app/config.py` ±39, `app/main.py` ±8.
- **Spring**: `service/Exercise/ExerciseAnalysisService.java` ±5.
- **AI 문서**: `ai-server/docs/grpc_ai_server_design.md` (+298) — AI 측 설계 문서.
- 결합 결과: AI도 진짜 gRPC 서버를 띄움 — 단, mock_server.py 와 평행 공존 시작.

### 04-29 — 프론트 잡일

| 커밋 | 파일 | 작업 |
|------|------|------|
| 0a3f577 (04-28) | `frontend/app/(tabs)/activity.tsx`, `index.tsx` | 활동 화면 프론트-백 연동 |
| 5fc487c (04-29) | `frontend/services/api.ts`, `stores/authStore.ts` | JWT 만료 시 로그인 화면 이동 |

결합 관점: Spring↔AI 인터페이스 무변경.

---

## 2026-05 — 실통합, 신뢰성, 결합 정리

이 달의 결합 관점 핵심: **"mock 제거 → 평행 구현 잔재 정리 → 동시성·재시도·보안·API 흐름 정리"**. 새 RPC 추가 없음, 결합 표면을 좁히고 신뢰성을 높이는 방향만.

### 05-08 ~ 05-09 — 부수 도메인 (스케줄러, TTS)

#### 136f0e6 (05-08) — feat: 스케줄러 기능 추가
- **Spring 신규**:
  - `global/config/SchedulerConfig.java` (+36) — `@EnableScheduling`
  - `service/Exercise/SessionTimeoutScheduler.java` (+102) — **AI 콜백 누락 시 IN_PROGRESS 세션을 FAILED 로 떨어뜨림 (1분 fixedDelay, 30분 버퍼)**
  - `model/exercise/Session.java`, `Status.java`, `Exercise.java` 약간
  - `service/Exercise/SessionService.java` +55, `ExerciseAnalysisService.java` ±36 — 낙관적 락 / `@Version` 흐름
  - `test/.../SessionTimeoutSchedulerTest.java` (+231) — e2e 테스트
- **docs**: `15-session-timeout-guide.md` (+285), `16-implementation-summary.md` (+455) — 후자는 2026-05-23 에 `16-archive-2026-05.md` 로 전환됨.
- 결합 결과: **AI 콜백이 영영 안 와도 Spring 단독으로 상태를 정리**할 안전망. 결합 신뢰성 1단계 강화.

#### 2f48526 (05-09) — feat: tts 백엔드 로직 추가
- **Spring 신규**: `AdminExerciseController`, `FeedbackTemplateController`, `InternalFeedbackController`, `PreferenceController` 등 신규 controller 4개. `model/exercise/ExerciseFeedbackTemplate`, `FeedbackType`, `SessionFeedbackLog`. 관련 service·dto.
- `controller/InternalExerciseController.java` ±18 — **다시 사용처가 생긴 듯 보였지만**, 이후 8ac8248에서 결국 다시 삭제됨.
- **DB**: `mysql/schema.sql` (+26), `data.sql` (+45).
- 결합 결과: AI↔Spring 인터페이스 자체는 무변경. 백엔드 도메인이 확장됨.

#### 7b755fb (05-12) — 싱크로율 수정
- 프론트만(`frontend/app/(tabs)/activity.tsx`, `exercise.tsx`, `index.tsx`). 결합 인터페이스 무변경.

### 05-15 — 새 리포지토리 정착 (first commit)

| 커밋 | 파일 | 작업 |
|------|------|------|
| 7af9cc2 (05-15) | `README.md` | 새 리포지토리 first commit (이전 흐름 통합) |

### 05-16 — AI 실통합·정리의 날 ([`feedback-preview-scope-before-bulk-changes`](../../../C:/Users/khjae/.claude/projects/E--init/memory/feedback_preview_scope_before_bulk_changes.md) 의 계기)

이 날 하루에 결합 표면의 평행/잔재 컴포넌트들이 한꺼번에 정리됐다. 사용자가 "너무 많이 바뀐다"고 경계심을 표명한 작업 — 메모리에 남은 그날.

#### 1a50c14 (05-16) — feat: AI 서버 mock 제거하고 실제 gRPC 통합으로 전환
- **AI 삭제**: `mock_server.py` **(-124, 첫 mock 종료)**.
- **AI 신규/보강**:
  - `app/grpc/session_state.py` 신설 (+95) — in-memory `SessionState` 클래스 (`session_registry.py`와 평행)
  - `app/grpc/exercise_servicer.py` (+164) — `StartAnalysis`/`StopAnalysis` 실제 구현
  - `app/grpc/server.py` (+70) — 인증 인터셉터 인라인
  - `app/grpc/spring_client.py` (+83) — `report_pose_data_batch`, `report_complete_analysis`
  - `app/api/endpoints/pose.py` (+121) — 분석기 진입
  - `app/core/squat_analyzer.py` (+115) — 스트리밍 분석기 확장
  - `app/config.py` (+9), `main.py` (+16), `models/pose.py` (+11)
  - `Dockerfile` ±9

#### 4a0f456 (05-16) — fix: AI 서버 gRPC 통합 복원 및 충돌 잔재 제거
- **AI 삭제**:
  - `app/grpc/session_registry.py` **(-71)** — `session_state.py`와 중복
  - `app/services/pose_analysis_engine.py` **(-320)** — `core/squat_analyzer.py`와 중복
  - `app/grpc/auth_interceptor.py` **(-26)** — `server.py`로 흡수
- **AI 신규/보강**:
  - `app/grpc/exercise_servicer.py` (+126) — 통합·정리
  - `app/grpc/server.py` (+58) — `AuthInterceptor` 인라인 클래스
  - `app/grpc/spring_client.py` (+63)
  - `app/main.py` (+16), `config.py` (+9)

#### 94acf6d (05-16) — chore: docstring 복원
- `ai-server/app/grpc/__init__.py` (+1).

#### b568706 (05-16) — chore: AI 서버 루트 로거를 INFO 로
- `ai-server/app/main.py` (+4).

#### 7d51cf6 (05-16) — refactor: 사용처 없는 GrpcConfig 삭제
- `backend/src/main/java/com/shadowfit/global/grpc/GrpcConfig.java` **(-22)** — `grpc-spring-boot-starter` 자동 채널 등록으로 불필요.

#### 0fe056e (05-16) — fix: MySQL 클라이언트 charset utf8mb4 강제
- `mysql/my.cnf` (+8) — `[client] default-character-set=utf8mb4`, `[mysql]` 동일.
- `docker-compose.yml` (+1) — `command: --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci`.
- 결합 결과: AI가 콜백으로 보내는 한글 `feedback_message`가 DB 진입 시 깨지지 않게 됨. ([`project-korean-only`](../../../C:/Users/khjae/.claude/projects/E--init/memory/project_korean_only.md))

#### 8e3fdf1 (05-16) — chore: mysql/*.cnf LF 강제
- `.gitattributes` (+1) — `mysql/*.cnf text eol=lf`. Windows CRLF로 MySQL이 my.cnf를 무시하던 문제 차단.

### 05-17 — 신뢰성·보안 강화 + API 정리 (P1 Phase A)

#### 8ac8248 (05-17) — fix: gRPC StopAnalysis 세션 ID long 손실 + 응답 DTO 정수 타입 일관성
- **Spring 수정**:
  - `service/Exercise/ExerciseAnalysisService.java` ±2 — `setSessionId(sessionId.intValue())` → `setSessionId(sessionId)` (int32 wrap-around 제거)
  - `dto/exercises/session/ExercisesResponseDto.java` ±4 — `sessionId`/`exerciseId` `Long`으로 통일
  - `dto/exercises/session/SessionUpdateResponseDto.java` ±2 — 동일
  - `controller/ExercisesController.java` ±6 — 타입 맞춤
- **Spring 삭제** (이 커밋의 핵심):
  - `controller/InternalExerciseController.java` **(-36)** — gRPC 전환 후 호출자 없음. 0d89668에서 도입 → 5ce1872에서 삭제 → e5f5b29에서 복원 → 2f48526에서 부활 → 결국 여기서 **최종 종료**
  - `dto/exercises/PoseDataRequestDto.java` **(-25)** — `PoseDataService` 시그니처와 불일치로 컴파일 차단되던 상태였음

#### c7657f1 (05-17) — refactor: AI 서버 동시성·콜백 신뢰성 강화 (P1 Phase A)
- **AI 수정**:
  - `app/api/endpoints/pose.py` ±9 — 핸들러 `async def` → `def` (MediaPipe 블로킹을 FastAPI threadpool에 위임)
  - `app/core/mediapipe_detector.py` ±14 — `PoseDetector` Singleton → `threading.local` (thread-safety)
  - `app/grpc/spring_client.py` ±64 — `report_complete_analysis` 재시도 (`_COMPLETE_MAX_ATTEMPTS=3`, 백오프 `(1.0, 3.0)`)
- **Infra**:
  - `docker-compose.yml` ±9 — `shadowfit-ai` 포트 `ports` → `expose` 전환 (외부 노출 차단, 컨테이너 내부 통신 유지)

#### 143a2e4 (05-17) — feat: PUT /exercises/sessions/{id}/stop 추가 + /complete 디프리케이트
- **Spring 수정**:
  - `controller/ExercisesController.java` +29/-4:
    - 신규 endpoint `PUT /exercises/sessions/{sessionId}/stop` → `analysisService.stopAnalysis(sessionId)` → `202 Accepted`
    - 기존 `PUT /exercises/sessions/{sessionId}/complete` 에 `@Deprecated` 부착, Swagger description "사용 자제"
- 결합 결과: **AI = 운동 통계의 단일 진실 원천** 원칙이 코드 레벨에서 확립. 프론트가 자체 카운트해서 DB를 직접 갱신하던 경로 (=권위 충돌) 폐기 흐름 시작.

---

## 월별 한 줄 요약

| 월 | 결합 관점 한 줄 |
|----|------|
| 2026-03 | FastAPI 별도 프로세스 분리, 통신 방식 미정. |
| 2026-04 (전반) | REST(WebClient + InternalExerciseController)로 1차 연결. |
| 2026-04 (중반) | gRPC로 전면 교체 — proto·콜백 RPC·기준 좌표 전달·종료 RPC 완성. |
| 2026-04 (후반) | gRPC 토큰 인증 + 중간 저장 필드 + AI 측 gRPC 패키지 첫 도입(평행). |
| 2026-05 (전반) | 스케줄러로 안전망, TTS 도메인 추가 (인터페이스 무변경). |
| 2026-05 (16일) | AI mock 제거, 평행 구현 잔재 일괄 정리. |
| 2026-05 (17일) | 동시성·재시도·외부 노출 차단·`/stop` API — **신뢰성 강화 P1 Phase A**. |

---

## 월별 결합 표면 변화량

| 월 | proto 변경 커밋 | Spring 결합 핵심 파일 변경 | AI 결합 핵심 파일 변경 | docker-compose 변경 |
|----|---------------|------------------------|---------------------|------------------|
| 2026-03 | 0 | 0 | 신설 (FastAPI 본체) | 0 |
| 2026-04 | 9개 (48bb0fc, 6ac0390, 953bad6, 4eb153b, ea1c636, 2dd55e0, f172933 외) | 매우 많음 (controller·service·DTO·entity 전부) | mock_server → grpc 패키지 신설 | 4건+ |
| 2026-05 | 0 | 7d51cf6, 8ac8248, 143a2e4 (전부 정리/수정) | 1a50c14, 4a0f456, c7657f1 (정리·신뢰성) | c7657f1, 0fe056e |
