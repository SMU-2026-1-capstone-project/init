# AI ↔ Backend 결합 현황

마지막 업데이트: 2026-05-23
범위: `ai-server/` (Python, FastAPI + gRPC) ↔ `backend/` (Spring Boot, Java/Kotlin)
목적: 현재 어떻게 결합돼 있는지 사실만 정리한 스냅샷. 트레이드오프·대안 비교는 [`docs/decisions/ai-backend-coupling.md`](../decisions/ai-backend-coupling.md) 참조.

---

## 1. 결합 형태 요약

| 차원 | 현재 방식 |
|------|---------|
| 통신 프로토콜 | gRPC (양방향, 동기 unary RPC) |
| 스키마 공유 | `exercise.proto` 양쪽 저장소에 동일 파일 중복 |
| 인증 | 내부 공유 토큰(`INTERNAL_API_TOKEN`) 기반, gRPC metadata `Authorization: Bearer …` |
| 네트워크 | Docker Compose `shadowfit-net` 브리지, 컨테이너명 DNS |
| 호출 패턴 | Spring → AI: 비동기(`@Async`, 202 Accepted) / AI → Spring: 콜백 (3회 재시도) |
| 상태 저장 | AI: in-memory `SessionState`, Spring: MySQL (`Session`, `PoseData`, `ExerciseReference`) |
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
                          gRPC StopAnalysis     │  - REST 8080     │
                       ┌─────────────────────►  │  - gRPC 6565     │
                       │                        │                  │
                       │                        └───────┬──────────┘
                       │                                │ JDBC
                       │                                ▼
                       │                        ┌──────────────────┐
                       │                        │ shadowfit-mysql  │
                       │                        │ (MySQL 8.0)      │
                       │                        └──────────────────┘
              ┌────────┴─────────┐
              │  shadowfit-ai    │  gRPC SavePoseDataBatch (실시간 콜백)
              │  (FastAPI)       │  gRPC CompleteAnalysis  (종료 콜백, 3회 재시도)
              │                  │  gRPC ExtractReferenceData (관리자, 비동기)
              │  - HTTP 8000     │ ──────────────────────────────────────►  (backend:6565)
              │  - gRPC 8585     │
              │  MediaPipe 0.10  │
              └──────────────────┘
```

Docker 네트워크는 `shadowfit-net` 브리지 한 개. 외부 노출은 backend REST(8080)만, gRPC(6565)와 AI HTTP(8000)/gRPC(8585)는 컨테이너 내부 전용.

---

## 3. gRPC 인터페이스

스키마 파일:
- `backend/src/main/proto/exercise.proto`
- `ai-server/app/proto/exercise.proto`
- 두 파일은 **수동으로 동기화** 필요. 변경 시 양쪽 모두 수정 + 코드 생성 재실행.

`ExerciseService` 정의된 RPC:

| RPC | 방향 | 호출자 | 수신자 | 용도 |
|-----|------|--------|--------|------|
| `ExtractReferenceData` | Spring → AI | `ExerciseAnalysisService.extractReferencePoses` | `ExerciseServicer.ExtractReferenceData` | YouTube URL에서 기준 포즈 추출 (현재 AI 구현 비어 있음) |
| `StartAnalysis` | Spring → AI | `ExerciseAnalysisService.sendAnalysisRequestToFastApi` | `ExerciseServicer.StartAnalysis` | 세션 시작 + 기준 좌표 전달 |
| `StopAnalysis` | Spring → AI | `ExerciseAnalysisService.stopAnalysis` | `ExerciseServicer.StopAnalysis` | 사용자 중단 신호, 즉시 응답 + 백그라운드에서 완료 콜백 |
| `SavePoseDataBatch` | AI → Spring | `spring_client.report_pose_data_batch` (트리거: `app/api/endpoints/pose.py:116` rep 완성 시) | `ExerciseGrpcService.savePoseDataBatch` | rep 단위 실시간 포즈 데이터 저장. **프론트가 `POST /pose` 를 프레임마다 호출해야 동작** |
| `CompleteAnalysis` | AI → Spring | `spring_client.report_complete_analysis` | `ExerciseGrpcService.completeAnalysis` | 세션 종료 + 최종 통계 전달 (핵심 콜백) |

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

### 중단
1. 프론트 → `PUT /exercises/sessions/{id}/stop` (커밋 `143a2e4`에서 신설)
2. `ExerciseAnalysisService.stopAnalysis` → gRPC `StopAnalysis` 송신
3. AI: `SessionState` 제거 + 백그라운드 스레드에서 `_send_complete_analysis` 호출
4. AI: 누적 통계 계산 → `spring_client.report_complete_analysis` (gRPC `CompleteAnalysis`)
5. Spring: `ExerciseGrpcService.completeAnalysis` 수신 → `SessionService.completeSession`
6. Spring: `Session(status=COMPLETED, total_reps=…, avg_sync_rate=…)` 갱신

`/exercises/sessions/{id}/complete` 는 디프리케이트됨 — 프론트가 직접 통계를 보내던 경로였고, AI 단일 진실 원칙으로 폐기.

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

영구 큐(Kafka/RabbitMQ 등)는 사용하지 않음. AI 콜백이 3회 모두 실패하면 ERROR 로그만 남고 수동 복구 필요.

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

라이브러리:
- AI: `mediapipe==0.10.21`, `opencv-python-headless==4.11.0.86`, `grpcio>=1.62.1`, `protobuf==4.25.3`, `dtaidistance==2.3.12`
- Spring: `grpc-{client,server}-spring-boot-starter:3.1.0.RELEASE`, `io.grpc:* 1.62.2`, `protobuf-java 3.25.1`

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

- **proto 중복 파일** — 양쪽이 손으로 동기화. 한쪽만 바꾸면 런타임에 직렬화 실패까지 잡히지 않음.
- **영구 큐 없음** — AI 콜백 3회 실패 시 데이터 유실 가능. 수동 복구 절차 없음.
- **AI in-memory 세션 상태** — AI 컨테이너 재시작 시 진행 중 세션 전부 소실 (Spring DB에는 `IN_PROGRESS` 상태로 남음, 결국 스케줄러가 `FAILED` 처리).
- **단일 AI 인스턴스 가정** — 메모리 `SessionState`가 인스턴스 로컬이라 수평 확장 불가.
- **gRPC reflection / health check 표준 미적용** — 현재 헬스체크는 AI HTTP `/health`만, gRPC 채널 상태는 별도 모니터 없음.
- **proto에 `exercise_type` 없음** — 스쿼트 외 운동 추가 시 proto + 양쪽 코드 변경 필요 ([`project-squat-first`](../../C:/Users/khjae/.claude/projects/E--init/memory/project_squat_first.md) 결정으로 후순위).

---

## 10. 참고 파일

- 결합 결정·트레이드오프: [`docs/decisions/ai-backend-coupling.md`](../decisions/ai-backend-coupling.md)
- 폴더 구조: [`docs/02-folder-structure.md`](../02-folder-structure.md)
- API 설계: [`docs/07-api-design.md`](../07-api-design.md)
- Docker 셋업: [`docs/13-docker-setup.md`](../13-docker-setup.md)
- 세션 타임아웃: [`docs/15-session-timeout-guide.md`](../15-session-timeout-guide.md)
