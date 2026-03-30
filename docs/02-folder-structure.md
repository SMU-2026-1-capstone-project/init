# ShadowFit 폴더 구조

## 전체 프로젝트 구조
```
shadowfit/
├── docs/                          # 프로젝트 문서
├── frontend/                      # React Native 앱
│   ├── app/                       # Expo Router 화면 (파일 기반 라우팅)
│   │   ├── (auth)/                # 인증 관련 화면
│   │   │   ├── login.tsx
│   │   │   └── register.tsx
│   │   ├── (tabs)/                # 탭 네비게이션 화면
│   │   │   ├── _layout.tsx
│   │   │   ├── home.tsx           # 메인 대시보드
│   │   │   ├── exercise.tsx       # 운동 시작
│   │   │   ├── calendar.tsx       # 달력 일지
│   │   │   └── mypage.tsx         # 마이페이지
│   │   ├── onboarding/            # 온보딩(페르소나 설정)
│   │   │   └── index.tsx
│   │   ├── exercise/              # 운동 관련 상세 화면
│   │   │   ├── select.tsx         # 운동 종목 선택
│   │   │   ├── camera-guide.tsx   # 카메라 세팅 가이드
│   │   │   ├── session.tsx        # 실시간 운동 세션
│   │   │   └── result.tsx         # 운동 결과/보고서
│   │   ├── report/                # 운동 보고서
│   │   │   ├── index.tsx
│   │   │   └── [id].tsx           # 상세 보고서
│   │   └── _layout.tsx            # 루트 레이아웃
│   ├── components/                # 재사용 컴포넌트
│   │   ├── common/                # 공통 UI 컴포넌트
│   │   │   ├── Button.tsx
│   │   │   ├── Header.tsx
│   │   │   └── Loading.tsx
│   │   ├── exercise/              # 운동 관련 컴포넌트
│   │   │   ├── PoseOverlay.tsx    # 관절 포인트 오버레이
│   │   │   ├── SyncRateBar.tsx    # 싱크로율 표시
│   │   │   ├── VideoPlayer.tsx    # 기준 영상 플레이어
│   │   │   └── CameraView.tsx     # 카메라 뷰
│   │   ├── calendar/              # 달력 컴포넌트
│   │   │   ├── CalendarGrid.tsx
│   │   │   └── DayEntry.tsx
│   │   └── report/                # 보고서 컴포넌트
│   │       ├── ChartSection.tsx
│   │       └── SummaryCard.tsx
│   ├── hooks/                     # 커스텀 훅
│   │   ├── useCamera.ts
│   │   ├── usePoseDetection.ts    # AI Server 포즈 감지 호출
│   │   ├── useSyncRate.ts         # AI Server 싱크로율 호출
│   │   ├── useTTS.ts              # TTS 음성 안내
│   │   └── useAuth.ts
│   ├── services/                  # API 통신 레이어
│   │   ├── api.ts                 # Axios 인스턴스 (Backend)
│   │   ├── aiApi.ts               # Axios 인스턴스 (AI Server)
│   │   ├── authService.ts
│   │   ├── exerciseService.ts
│   │   ├── poseService.ts         # AI Server 포즈/싱크로율 호출
│   │   ├── recordService.ts
│   │   └── reportService.ts
│   ├── utils/                     # 유틸리티
│   │   └── dateUtils.ts
│   ├── types/                     # TypeScript 타입 정의
│   │   ├── exercise.ts
│   │   ├── pose.ts
│   │   └── user.ts
│   ├── constants/                 # 상수
│   │   ├── exercises.ts           # 운동 종목 데이터
│   │   ├── persona.ts             # 페르소나 기준값
│   │   └── theme.ts               # 디자인 토큰
│   ├── assets/                    # 정적 리소스
│   │   ├── images/
│   │   ├── fonts/
│   │   └── animations/
│   ├── app.json                   # Expo 설정
│   ├── package.json
│   ├── tsconfig.json
│   └── babel.config.js
│
├── ai-server/                     # Python AI 서버 (FastAPI)
│   ├── app/
│   │   ├── main.py                # FastAPI 진입점
│   │   ├── config.py              # 환경 설정
│   │   ├── api/
│   │   │   ├── router.py          # API 라우터 통합
│   │   │   └── endpoints/
│   │   │       ├── pose.py        # 실시간 포즈 감지 API
│   │   │       ├── sync.py        # DTW 동기화율 계산 API
│   │   │       └── video.py       # 영상 전처리 API
│   │   ├── core/                  # 핵심 AI 로직
│   │   │   ├── mediapipe_detector.py  # MediaPipe 관절 감지
│   │   │   ├── angle_calculator.py    # 관절 각도 계산
│   │   │   ├── dtw_calculator.py      # DTW 알고리즘
│   │   │   └── video_processor.py     # 영상 프레임 분석
│   │   ├── models/                # Pydantic 데이터 모델
│   │   │   ├── pose.py
│   │   │   ├── sync.py
│   │   │   └── video.py
│   │   └── utils/
│   │       ├── constants.py       # 관절 인덱스, 운동별 설정
│   │       └── image_utils.py     # Base64/이미지 변환
│   ├── tests/
│   ├── requirements.txt
│   ├── Dockerfile
│   └── .env.example
│
├── backend/                       # Spring Boot 서버
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/shadowfit/
│   │   │   │   ├── ShadowfitApplication.java
│   │   │   │   ├── config/                    # 설정
│   │   │   │   │   ├── SecurityConfig.java
│   │   │   │   │   ├── WebConfig.java
│   │   │   │   │   └── SwaggerConfig.java
│   │   │   │   ├── controller/                # REST 컨트롤러
│   │   │   │   │   ├── AuthController.java
│   │   │   │   │   ├── ExerciseController.java
│   │   │   │   │   ├── RecordController.java
│   │   │   │   │   ├── ReportController.java
│   │   │   │   │   └── UserController.java
│   │   │   │   ├── service/                   # 비즈니스 로직
│   │   │   │   │   ├── AuthService.java
│   │   │   │   │   ├── ExerciseService.java
│   │   │   │   │   ├── RecordService.java
│   │   │   │   │   ├── ReportService.java
│   │   │   │   │   ├── GptFeedbackService.java
│   │   │   │   │   └── UserService.java
│   │   │   │   ├── repository/                # JPA 리포지토리
│   │   │   │   │   ├── UserRepository.java
│   │   │   │   │   ├── ExerciseRecordRepository.java
│   │   │   │   │   ├── PoseDataRepository.java
│   │   │   │   │   └── ReportRepository.java
│   │   │   │   ├── entity/                    # JPA 엔티티
│   │   │   │   │   ├── User.java
│   │   │   │   │   ├── ExerciseRecord.java
│   │   │   │   │   ├── PoseData.java
│   │   │   │   │   ├── DailyLog.java
│   │   │   │   │   └── Report.java
│   │   │   │   ├── dto/                       # 데이터 전송 객체
│   │   │   │   │   ├── request/
│   │   │   │   │   └── response/
│   │   │   │   ├── exception/                 # 예외 처리
│   │   │   │   │   └── GlobalExceptionHandler.java
│   │   │   │   └── util/                      # 유틸리티
│   │   │   │       └── JwtUtil.java
│   │   │   └── resources/
│   │   │       ├── application.yml
│   │   │       ├── application-dev.yml
│   │   │       └── application-prod.yml
│   │   └── test/                              # 테스트
│   │       └── java/com/shadowfit/
│   ├── build.gradle
│   └── settings.gradle
│
└── database/                      # DB 관련
    ├── schema.sql                 # 테이블 생성 스크립트
    ├── seed.sql                   # 초기 데이터
    └── erd.md                     # ERD 설명
```

## 주요 디렉토리 설명

| 디렉토리 | 역할 |
|---------|------|
| `ai-server/app/api/` | 포즈 감지, 동기화율, 영상 전처리 REST API |
| `ai-server/app/core/` | MediaPipe, DTW, 각도 계산 핵심 로직 |
| `ai-server/app/models/` | Pydantic 요청/응답 데이터 모델 |
| `frontend/app/` | Expo Router 기반 파일 라우팅. 화면 단위 컴포넌트 |
| `frontend/components/` | 재사용 가능한 UI 컴포넌트 |
| `frontend/hooks/` | 카메라, 포즈 감지, TTS 등 커스텀 훅 |
| `frontend/services/` | 백엔드 + AI 서버 API 호출 레이어 |
| `backend/controller/` | REST API 엔드포인트 |
| `backend/service/` | 비즈니스 로직 (GPT 피드백 포함) |
| `backend/entity/` | DB 테이블 매핑 엔티티 |
| `backend/repository/` | JPA 데이터 접근 레이어 |
