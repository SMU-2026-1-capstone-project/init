# ShadowFit Postman 컬렉션

## 파일

- `shadowfit.postman_collection.json` — REST 21개 + gRPC 7개 RPC
- `shadowfit.postman_environment.json` — `baseUrl`, `grpcUrl`, `accessToken` 등 변수

## 임포트

1. Postman → `Import` → 두 파일 선택
2. 우상단 environment 드롭다운에서 **ShadowFit Local** 선택
3. `01. 인증·온보딩 > 로그인` 실행 → `accessToken` / `refreshToken` 자동 저장
4. 이후 REST 요청은 컬렉션 레벨 Bearer 인증으로 자동 토큰 사용

## 컬렉션 구조

| 폴더 | 내용 | 프로토콜 |
|---|---|---|
| 01. 인증·온보딩 | `/member/*` 6개 (signup, login, logout, delete, onboarding) | REST |
| 02. 운동 세션 | `/exercises/sessions`, `PATCH /sessions/{id}/end` (ET-H), 옛 PUT complete | REST |
| 03. TTS · 피드백 | `/preferences/tts`, `/feedback-templates`, `/feedbacks`, `/feedback-summary` | REST |
| 04. 운동 리포트 | `/reports/weekly-summary`, `/calendar`, `/daily-logs`, `/session/{id}` | REST |
| 05. 관리자 | `/admin/exercises/{id}/thresholds` (ADMIN 권한) | REST |
| 06. 유틸 | `/api/check-my-hash` (BCrypt 해시 확인) | REST |
| **07. gRPC ExerciseService** | 6개 RPC (Extract / Start / SavePose / Complete / Stop / **ReportFeedbackBatch**) | gRPC :6565 |
| 08. gRPC UserService | `GetUserInfo` | gRPC :6565 |

## gRPC 사용 방법

Postman 의 일반 HTTP 요청은 gRPC 호출 안 됨. 컬렉션의 gRPC 폴더는 *참고용 schema* 로 사용하고, 실제 호출은 다음 중 하나:

### 옵션 A. Postman gRPC Request 기능
1. 좌측 `New` → `gRPC Request`
2. Server URL: `localhost:6565`
3. `Select a method` → `Import a .proto file` → `backend/src/main/proto/exercise.proto`
4. Service / Method 선택
5. Metadata 에 `authorization: Bearer <internalToken>` 추가 (콜백 RPC 만 필요)
6. 컬렉션의 gRPC 요청 body 를 Message 에 복붙

### 옵션 B. grpcurl (CLI)
```powershell
grpcurl -plaintext `
  -H "authorization: Bearer $env:INTERNAL_API_TOKEN" `
  -d '{\"session_id\":1,\"set_no\":1,\"is_final\":false,\"events\":[{\"feedback_type\":\"KNEE_OUT\",\"sync_rate_at_trigger\":52.3,\"occurred_at\":\"2026-05-27T10:23:45Z\"}]}' `
  localhost:6565 `
  shadowfit.grpc.ExerciseService/ReportFeedbackBatch
```

## 환경 변수

| 변수 | 기본값 | 채워지는 시점 |
|---|---|---|
| `baseUrl` | `http://localhost:8080` | 수동 (배포 환경별 수정) |
| `grpcUrl` | `localhost:6565` | 수동 |
| `email` | `test@shadowfit.local` | 수동 |
| `exerciseId` | `1` | 수동 (운동 ID — 스쿼트가 1) |
| `sessionId` | (빈값) | `운동 세션 시작` 응답에서 자동 |
| `accessToken` | (빈값) | `로그인` 응답에서 자동 |
| `refreshToken` | (빈값) | 로그인 시 자동 |
| `internalToken` | (빈값) | 수동 — `application.yml` 의 `INTERNAL_API_TOKEN` 값 |
| `role` | (빈값) | 로그인 시 자동 |

## 주요 흐름 시연 순서

1. **회원가입** (01.01) → **로그인** (01.02) → 토큰 자동 박힘
2. **온보딩 저장** (01.06) — 페르소나·운동레벨·키·몸무게
3. **TTS 설정 조회** (03.01) — default `ttsEnabled=true, ttsSpeed=1.0`
4. **운동 피드백 템플릿 조회** (03.03) — 페르소나 자동 필터
5. **운동 세션 시작** (02.02) → `sessionId` 자동 저장
6. (실제 운동 — AI 서버 트래픽)
7. (AI 가 gRPC `ReportFeedbackBatch` 호출 — 07.06)
8. **세션 종료** (02.04, PATCH /sessions/{id}/end, ET-H)
9. **세션 피드백 리스트** (03.04) + **집계** (03.05) — 리포트 화면용

## 관련 문서

- 결정 문서: [`../docs/decisions/tts-design.md`](../docs/decisions/tts-design.md), [`../docs/decisions/session-end-trigger.md`](../docs/decisions/session-end-trigger.md), [`../docs/decisions/latency-perception.md`](../docs/decisions/latency-perception.md)
- API 명세: [`../docs/07-api-design.md`](../docs/07-api-design.md)
- proto: [`../backend/src/main/proto/exercise.proto`](../backend/src/main/proto/exercise.proto), [`../backend/src/main/proto/user.proto`](../backend/src/main/proto/user.proto)
- Swagger UI: http://localhost:8080/swagger-ui/index.html
