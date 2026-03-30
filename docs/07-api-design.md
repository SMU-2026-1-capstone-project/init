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

// Response 201
{
  "sessionId": 42,
  "exerciseId": 1,
  "startTime": "2026-03-30T14:00:00",
  "status": "IN_PROGRESS"
}
```

### PUT /exercises/sessions/{sessionId}/complete - 운동 세션 종료
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
{
  "sessionId": 42,
  "status": "COMPLETED",
  "endTime": "2026-03-30T14:30:00"
}
```

### POST /exercises/sessions/{sessionId}/pose-data - 자세 데이터 저장 (배치)
```json
// Request - 1초 단위 배치 전송
{
  "poseDataList": [
    {
      "timestampSec": 1,
      "jointCoordinates": { "landmarks": [...] },
      "syncRate": 82.5,
      "isCorrect": true,
      "feedbackMessage": null
    },
    {
      "timestampSec": 2,
      "jointCoordinates": { "landmarks": [...] },
      "syncRate": 65.0,
      "isCorrect": false,
      "feedbackMessage": "무릎이 발끝을 넘었습니다"
    }
  ]
}

// Response 201
{ "savedCount": 2 }
```

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
