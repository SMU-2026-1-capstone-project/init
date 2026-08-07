# REST API 설계 가이드

## Base URL
```
개발: http://localhost:8080/api/v1
운영: https://api.shadowfit.com/api/v1
```

## 인증 API

### POST /auth/register - 회원가입
```json
// Request
{
  "email": "user@example.com",
  "password": "password123",
  "nickname": "홈트초보"
}

// Response 201
{
  "id": 1,
  "email": "user@example.com",
  "nickname": "홈트초보",
  "token": "eyJhbGci..."
}
```

### POST /auth/login - 로그인
```json
// Request
{
  "email": "user@example.com",
  "password": "password123"
}

// Response 200
{
  "token": "eyJhbGci...",
  "user": {
    "id": 1,
    "nickname": "홈트초보",
    "persona": "BEGINNER"
  }
}
```

## 사용자 API

### PUT /users/me - 프로필 수정 (온보딩 포함)
```json
// Request (Header: Authorization: Bearer {token})
{
  "nickname": "홈트초보",
  "persona": "BEGINNER",
  "height": 175.0,
  "weight": 70.5
}

// Response 200
{
  "id": 1,
  "nickname": "홈트초보",
  "persona": "BEGINNER",
  "height": 175.0,
  "weight": 70.5
}
```

### GET /users/me - 내 정보 조회

## 운동 API

### GET /exercises - 운동 종목 목록
```json
// Response 200
[
  {
    "id": 1,
    "name": "스쿼트",
    "category": "LOWER",
    "description": "하체 전체 운동",
    "syncThresholdBeginner": 60.0,
    "syncThresholdAdvanced": 85.0
  }
]
```

### POST /exercises/sessions - 운동 세션 시작
```json
// Request
{
  "exerciseId": 1,
  "referenceSource": "youtube:https://youtu.be/xxx"
}

// Response 202 Accepted (비동기 - gRPC 호출이 백그라운드로 진행)
{
  "sessionId": 42,
  "exerciseId": 1,
  "startTime": "2026-03-30T14:00:00",
  "status": "IN_PROGRESS"
}
```
> 내부 흐름: Spring 이 DB에 세션 생성 → 즉시 202 응답 → `@Async` 로 gRPC `StartAnalysis` 송신 (AI 가 기준 좌표 받아 분석 시작). 결합 상세는 [`architecture/ai-backend-integration.md`](./architecture/ai-backend-integration.md).

### POST /exercises/{exerciseId}/reference - 기준 좌표 추출 요청 (관리자)
```
POST /exercises/1/reference?youtubeUrl=https://youtu.be/xxx

// Response 202
"운동 ID [1]에 대한 기준 좌표 추출이 시작되었습니다."
```
유튜브 URL → AI 가 MediaPipe로 프레임마다 관절 좌표 추출 → Spring 콜백으로 `exercise_references` 테이블 영속화.

### PUT /exercises/sessions/{sessionId}/stop - 운동 세션 중단 (2026-05-17 신설)
권장 종료 경로. 프론트가 종료 버튼을 누르면 호출.
```
PUT /exercises/sessions/42/stop

// Response 202 (본문 없음)
```
내부 흐름:
1. Spring → gRPC `StopAnalysis(session_id=42)` 송신, 즉시 202 반환
2. AI 가 누적 통계 정리 후 gRPC `CompleteAnalysis` 로 콜백 (3회 재시도)
3. Spring 콜백 수신 시점에 `status=COMPLETED`, `total_reps`, `avg_sync_rate` 등 DB에 영속화
4. 프론트는 별도 조회 API 로 결과 폴링

AI = 운동 통계의 단일 진실 원천 원칙. (커밋 143a2e4)

### [Deprecated] PUT /exercises/sessions/{sessionId}/complete
프론트가 자체 카운트한 통계로 DB를 직접 갱신하던 옛 경로. AI 분석 결과와 권위가 충돌해 디프리케이트. `/stop` 으로 마이그레이션 후 제거 예정.
```json
// Request
{
  "totalReps": 15,
  "avgSyncRate": 78.5,
  "maxSyncRate": 92.0,
  "minSyncRate": 55.3,
  "caloriesBurned": 120.5,
  "difficultyLevel": 2
}
// Response 200
{ "sessionId": 42, "status": "COMPLETED", "endTime": "2026-03-30T14:30:00" }
```

### GET /exercises/{exerciseId}/feedback-templates - 운동별 TTS 멘트 목록
```json
// Response 200
[
  {
    "feedbackType": "KNEE_OVER",
    "message": "무릎이 발끝을 넘었습니다",
    "priority": 10
  },
  {
    "feedbackType": "GOOD_FORM",
    "message": "좋은 자세입니다",
    "priority": 100
  }
]
```
세션 시작 시 클라이언트가 호출해 device TTS 재생용 멘트 매핑.

> **참고**: 과거에 검토되었던 `POST /exercises/sessions/{id}/pose-data` (REST 배치 저장) 엔드포인트는 gRPC `SavePoseDataBatch` 콜백으로 대체되어 제거됨 (커밋 8ac8248).

## 기록 API

### GET /records/calendar?year=2026&month=3 - 월별 운동 기록
```json
// Response 200
{
  "year": 2026,
  "month": 3,
  "records": [
    {
      "date": "2026-03-15",
      "totalExerciseTime": 45,
      "totalCalories": 320.5,
      "sessionCount": 2,
      "mood": "GOOD"
    }
  ]
}
```

### GET /records/daily/{date} - 특정일 상세 기록
### POST /records/daily-logs - 일지 작성/수정
```json
// Request
{
  "logDate": "2026-03-30",
  "memo": "오늘 스쿼트 자세가 많이 좋아졌다!",
  "mood": "GREAT"
}
```

## 보고서 API

### GET /reports/session/{sessionId} - 세션 보고서
```json
// Response 200
{
  "reportId": 10,
  "sessionId": 42,
  "exerciseName": "스쿼트",
  "duration": "30분",
  "avgSyncRate": 78.5,
  "summary": "전체적으로 좋은 자세를 유지했습니다. 다만 후반부에 무릎이 발끝을...",
  "improvementTips": "1. 무릎 위치를 더 신경써주세요\n2. 허리를 곧게 유지해주세요",
  "comparisonWithPrevious": {
    "syncRateChange": +5.2,
    "repChange": +3
  },
  "syncRateTimeline": [82.5, 80.1, 75.0, ...]
}
```

### GET /reports/weekly - 주간 보고서
### GET /reports/monthly - 월간 보고서

## 사용자 환경설정 API (2026-05 추가)

### GET /preferences/tts - TTS 설정 조회
```json
// Response 200
{
  "ttsEnabled": true,
  "ttsSpeed": 1.0
}
```

### PATCH /preferences/tts - TTS 설정 변경
```json
// Request
{
  "ttsEnabled": true,
  "ttsSpeed": 1.5
}
// Response 200 — 갱신된 설정 반환
```
`ttsSpeed` 는 0.5~2.0 범위. device TTS 재생 시 클라이언트가 이 값을 그대로 `expo-speech` 의 `rate` 로 전달. ([`11-tts-youtube-guide.md`](./11-tts-youtube-guide.md))

## 관리자 API (2026-05 추가)

### PATCH /admin/exercises/{exerciseId}/thresholds - 싱크로율 임계값 변경
관리자 권한(`ROLE_ADMIN`) 필수. 신규 세션부터 적용.
```json
// Request
{
  "syncThresholdBeginner": 65.0,
  "syncThresholdAdvanced": 88.0
}
// 제약: beginner < advanced

// Response 200
{
  "exerciseId": 1,
  "syncThresholdBeginner": 65.0,
  "syncThresholdAdvanced": 88.0
}
```

### GET /admin/members - 회원 목록 조회 (2026-08 추가)
관리자 권한(`ROLE_ADMIN`) 필수. 필터 5종의 **임의 조합**(32가지) + 정렬 3종 + offset 페이징.

| 파라미터 | 기본값 | 설명 |
|---|---|---|
| `keyword` | — | `username`/`email` 부분일치. ⚠️ 선행 와일드카드라 인덱스 탐색 불가 |
| `persona` · `workoutLevel` · `onboardingCompleted` | — | 등치 필터 |
| `joinedFrom` · `joinedTo` | — | 가입일 범위(`joinedTo` 는 그날 포함) |
| `sort` | `CREATED_AT` | 화이트리스트 enum |
| `asc` | `false` | 최신순 기본 |
| `page` · `size` | `0` · `20` | `size` 최대 100 |

```json
// Response 200 — PageResponse<AdminMemberListItemDto>
{ "content": [ { "id": 1, "username": "...", "email": "...", "selectedPersona": "BEGINNER",
                 "workoutLevel": "STARTER", "onboardingCompleted": true, "createdAt": "..." } ],
  "page": 0, "size": 20, "totalElements": 1234, "totalPages": 62 }
```

### GET /admin/sessions - 세션 목록 조회 (2026-08 추가)
관리자 권한 필수. `exercise_sessions ⋈ users ⋈ exercises` 조인. 필터 4종 + 정렬 3종.

| 파라미터 | 설명 |
|---|---|
| `status` | 세션 상태 등치 |
| `exerciseId` | 운동 종목 |
| `startedFrom` · `startedTo` | 시작 시각 범위 |
| `keyword` | **회원** `username`/`email` 부분일치 (조인 너머) |
| `sort` | `START_TIME`(기본) / `AVG_SYNC_RATE` / `TOTAL_REPS` — 2차 정렬로 `id` 고정 |

### GET /admin/stats/overview - 대시보드 통계 (2026-08 추가)
관리자 권한 필수. 위젯 5종을 **실시간 집계**로 반환(사전집계·캐시 없음).

```json
// Response 200
{ "todaySessionCount": 2653, "sessionStatusDistribution": { "COMPLETED": 500000, "FAILED": 249554, "IN_PROGRESS": 250446 },
  "averageSyncRate": 75.0, "newMemberCount": 548, "activeMemberCount": 19019 }
```

> 📌 **세 API 의 설계 근거·실측은 [`decisions/admin-page-scope.md`](./decisions/admin-page-scope.md).**
> 인덱스 커버리지(§4-3), 드라이빙 테이블(§4-4-1), 집계 비용(§4-5) 이 거기 있다.
>
> 🔶 **총건수(`totalElements`)는 `LIMIT` 이 없어 조건에 맞는 행을 전부 센다.** 대부분의 필터
> 조합에서 전수 스캔이고, 이건 **감수하기로 한 것**(㉮)이다 — 페이지 번호 UI 를 그리려면
> 필요하고, 무한 스크롤로 정해지면 keyset 을 얹으면서 안 부르면 된다(§4-3 "2026-08-06").
>
> 🔶 **대시보드는 b(상태별 분포)·e(활성 회원) 둘이 비용의 대부분**이다(실측 각각 ~357ms /
> ~307ms, 나머지 셋 합쳐 5ms 미만). 캐시·인덱스 도입은 미결(§4-5-1 ④).

## 내부 API (AI ↔ Spring, gRPC 단일 채널)

> **2026-05-26 갱신**: AI → Spring 내부 호출은 *전부 gRPC* 로 통일. `Authorization: Bearer {INTERNAL_API_TOKEN}` (metadata) 로 인증. REST `/internal/*` endpoint 는 폐기됨 (기존 `POST /internal/feedback/batch` → `ExerciseService.ReportFeedbackBatch`). proto 정의는 `backend/src/main/proto/exercise.proto`. 박제: [`./decisions/tts-design.md`](./decisions/tts-design.md) 상단 박스.

### gRPC ExerciseService.ReportFeedbackBatch — 세션별 TTS 발화 이벤트 batch 저장
**호출자**: AI 서버 (FastAPI → Spring). BT-SET 모델 (분기 2.A.BT) — *세트 경계마다 mini-batch + 세션 종료 시 final batch*. 매 rep 실시간 호출 금지.
**인증**: gRPC metadata `Authorization: Bearer {INTERNAL_API_TOKEN}` (`InternalAuthInterceptor`).
**멱등성**: `(session_id, occurred_at, feedback_type)` uniqueKey + `INSERT IGNORE`. 같은 events 재송신 안전.

```proto
// Request
message FeedbackBatchRequest {
  int64 session_id = 1;
  int32 set_no = 2;                            // 1-based. BT-NONE 호환 시 1 고정
  bool is_final = 3;                           // 마지막 batch 여부
  repeated FeedbackEvent events = 4;
}

message FeedbackEvent {
  string feedback_type = 1;                    // 8종 enum 중 하나 (KNEE_OUT 등)
  double sync_rate_at_trigger = 2;
  google.protobuf.Timestamp occurred_at = 3;
}

// Response
message FeedbackBatchResponse {
  int64 session_id = 1;
  int32 saved_count = 2;                       // INSERT 된 row 수 (중복 흡수 제외)
}
```

### 기존 gRPC RPC (참고)

- `StartAnalysis (AnalyzeRequest) returns (AnalyzeResponse)` — Spring → FastAPI 세션 시작
- `StopAnalysis (StopRequest) returns (StopResponse)` — Spring → FastAPI 강제 중단 (ET-H, `SessionService.endSession` afterCommit 에서 호출)
- `SavePoseDataBatch (PoseDataBatchRequest) returns (PoseDataResponse)` — FastAPI → Spring 포즈 batch
- `CompleteAnalysis (SessionCompleteRequest) returns (SessionCompleteResponse)` — FastAPI → Spring 세션 최종 통계
- `ExtractReferenceData (ExtractRequest) returns (ExtractResponse)` — Spring → FastAPI YouTube 좌표 추출

## 공통 응답 형식

### 성공
```json
{
  "status": 200,
  "data": { ... }
}
```

### 에러
```json
{
  "status": 400,
  "error": "BAD_REQUEST",
  "message": "유효하지 않은 이메일 형식입니다."
}
```

## 인증 방식
- JWT Bearer Token
- Header: `Authorization: Bearer {token}`
- 토큰 만료: 24시간
- `/auth/*` 엔드포인트는 인증 불필요
