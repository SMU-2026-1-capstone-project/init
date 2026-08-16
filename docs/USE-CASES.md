# ShadowFit 유스케이스

작성: 2026-07-22
상태: 초안 — 기존 코드/문서와 캡스톤 발표자료 원본 "요구사항 정의"(HO-PT 6쪽, 바탕화면 `남은거.PNG`) 기준 역작성. [`PRD.md`](./PRD.md)와 짝을 이루는 문서.

---

## 1. 액터

| 액터 | 유형 | 설명 |
|---|---|---|
| 사용자 (Member) | 사람 | 앱을 사용해 운동하는 일반 회원 |
| 관리자 (Admin) | 사람 | `ROLE_ADMIN`, 운동별 싱크로율 임계값 등을 관리 |
| AI 서버 (FastAPI) | 시스템 | 포즈 추정·싱크로율 계산·영상 분석 담당. Spring과 gRPC + 내부 REST로 통신 |
| 세션 타임아웃 스케줄러 | 시스템 | Spring 내부 `@Scheduled`, 1분 주기로 방치된 세션을 정리 |

---

## 2. 유스케이스 목록

**담당** 열은 이미 완료된 유스케이스는 표기하지 않고(끝난 일), 남은 작업이 있는 것만 3자 분업([`docs/handoff/3way-meeting-agenda.md`]) 기준으로 표시 — **나** = Backend/Spring, **팀원(FE)** = React Native, **팀원(AI)** = FastAPI. 근거: `tasks/21-task-assignment.md`.

| ID | 이름 | 주 액터 | 우선순위 | 담당(남은 작업) |
|---|---|---|---|---|
| UC-01 | 회원가입 | 사용자 | Must | — (완료) |
| UC-02 | 로그인 / 로그아웃 | 사용자 | Must | — (완료) |
| UC-03 | 온보딩 정보 설정 | 사용자 | Must | — (완료) |
| UC-04 | 기준 동작 영상 등록 | 관리자/사용자 | Must | — (완료) |
| UC-05 | 운동 세션 시작~종료 | 사용자, AI 서버 | Must (핵심) | — (백엔드·AI 완료, 프론트 연동 검증 필요 → 팀원(FE)) |
| UC-05E | 세션 타임아웃 처리 | 스케줄러 | Must (예외) | — (완료) |
| UC-06 | 자세 피드백 발화 로그 저장 | AI 서버 | Must | — (완료) |
| UC-07 | 세션 상세 리포트 조회 | 사용자 | Must | 나 — BE-02 (worst 구간 precompute 보강) |
| UC-08 | 캘린더 / 주간 요약 조회 | 사용자 | Must | 팀원(FE) — 화면 데이터 연동(FE-09/10), API는 완료 |
| UC-09 | 일일 메모 작성 | 사용자 | Should | — (완료) |
| UC-10 | TTS 설정 변경 | 사용자 | Should | — (완료) |
| UC-11 | 싱크로율 임계값 변경 | 관리자 | Should | — (완료) |
| UC-12 | 회원 탈퇴 | 사용자 | Must | 나 — 개별 세션 삭제 API 미구현분 |
| UC-13 | 운동 타이머 표시 | 사용자 | Should (미구현 확인 필요) | 팀원(FE) — FE-12 |
| UC-14 | 관리자 카테고리 관리 | 관리자 | Could (미구현) | 나+팀원(FE) — BE-04 |
| UC-15 | 관리자 운동 영상 관리 | 관리자 | Could (미구현) | 나+팀원(FE) — 미배정 |
| UC-16 | AI 리포트 자동 생성 | 사용자, (LLM) | Could (미구현) | 나 — BE-03 |
| UC-17 | 운동 목표 설정/달성 현황 확인 | 사용자 | Could (미구현) | 나+팀원(FE) — BE-06 |
| UC-18 | 데이터 기반 개인화 루틴 추천 | 사용자 | 2학기 (미구현) | 나 — BE-08 |
| UC-19 | 사용자 운동 패턴 분석 | 사용자 | 2학기 (미구현) | 나 — BE-07 |
| UC-20 | 운동 세트 자동 구분 | 사용자 | 보류 (미구현) | 나+팀원(AI) — BE-09+AI-03 |

---

## 3. 핵심 유스케이스 상세

### UC-01. 회원가입

- **액터**: 사용자
- **사전조건**: 없음
- **기본 흐름**
  1. 사용자가 이메일/비밀번호(+기본 정보)를 입력한다.
  2. 앱이 `POST /member/signup`을 호출한다.
  3. 서버가 이메일 중복을 확인하고 `Member`를 생성한다.
  4. 가입 완료 응답을 반환한다.
- **대안/예외 흐름**
  - 3a. 이메일이 이미 존재 → 409/400 계열 오류 반환, 가입 거부
- **사후조건**: `Member` 레코드 생성, `onboardingCompleted = false`

### UC-02. 로그인 / 로그아웃

- **액터**: 사용자
- **사전조건**: 회원가입 완료
- **기본 흐름**
  1. 사용자가 이메일/비밀번호로 `POST /member/login` 호출.
  2. 서버가 인증 후 JWT(액세스+리프레시)를 발급한다 (`LoginResponseDto`).
  3. 이후 요청은 `JwtAuthFilter`가 토큰을 검증한다.
  4. 로그아웃 시 `POST /member/logout` → 토큰을 `JwtBlacklist`에 등록해 무효화.
- **대안/예외 흐름**
  - 1a. 비밀번호 불일치 → 인증 실패 응답
  - 3a. 만료/블랙리스트 토큰으로 요청 → `CustomAuthenticationEntryPoint`가 401 반환
  - 3b. 인가 안 된 리소스 접근 → `CustomAccessDeniedHandler`가 403 반환
- **사후조건**: 로그인 성공 시 클라이언트가 유효한 JWT 보유, 로그아웃 시 기존 토큰 무효

### UC-05. 운동 세션 시작 ~ 종료 (핵심 유스케이스)

- **액터**: 사용자, AI 서버 (FastAPI)
- **사전조건**: 로그인 완료, 온보딩 완료, 대상 운동에 대한 `ExerciseReference`(기준 좌표) 존재
- **기본 흐름**
  1. 사용자가 운동 화면에서 시작 버튼을 누른다.
  2. 앱 → Spring `POST /exercises/sessions` 호출.
  3. Spring이 `Session`(status=`IN_PROGRESS`)을 생성하고, gRPC로 AI 서버에 분석 시작을 알린 뒤 **즉시 `sessionId`를 반환**한다 (AI 분석 완료를 기다리지 않음).
  4. 사용자가 카메라 앞에서 운동을 수행하는 동안 AI 서버가 rep 단위로 관절 좌표·싱크로율·자세 오류를 계산한다.
  5. AI 서버가 rep 완성 시마다 포즈 데이터를 배치로 `POST /internal/exercises/pose-data`(`X-Internal-Token` 인증)로 Spring에 저장한다.
  6. 사용자가 종료 버튼을 누르면 앱 → Spring `PATCH /sessions/{sessionId}/end` 호출. 앱은 결과값을 보내지 않는다 — 종료 신호만 보낸다(ET-H, 분기 2.A.ET).
  7. Spring이 `endTime`을 기록하고 아웃박스를 통해 AI에 gRPC `StopAnalysis`를 보낸다. AI가 분석을 마치고 gRPC `CompleteAnalysis`로 보고하면 Spring이 `pose_data`에서 싱크 통계를 직접 집계해 `Session.status = COMPLETED`로 갱신한다.
- **대안/예외 흐름**
  - 3a. AI 서버가 gRPC 응답 불가(다운) → Resilience4j 서킷브레이커/재시도 동작 (Spring→AI 방향만 보호됨)
  - 4a→7 사이 사용자가 앱을 종료/네트워크 단절 등으로 종료 요청을 못 보냄 → **UC-05E(세션 타임아웃)**로 이어짐
  - 6a. 이미 타임아웃 스케줄러가 `FAILED` 처리한 세션에 뒤늦게 완료 요청이 온 경우 → `@Version` 낙관락으로 **FastAPI(정상 완료) 결과를 우선** 반영, 타임아웃 처리보다 실제 완료가 신뢰됨
- **사후조건**: `Session.status ∈ {COMPLETED, FAILED}`, `pose_data`에 세션 전체 rep 기록 존재

### UC-05E. 세션 타임아웃 처리 (예외 흐름의 독립 유스케이스)

- **액터**: 세션 타임아웃 스케줄러
- **트리거**: 1분 주기 스케줄 실행
- **기본 흐름**
  1. 스케줄러가 `status = IN_PROGRESS`인 세션 중 `시작시간 + 예상시간 + 30분 버퍼`를 초과한 세션을 조회한다.
  2. 해당 세션들을 `FAILED`로 갱신한다.
- **동시성 규칙**: AI 서버의 정상 완료 콜백과 스케줄러의 타임아웃 처리가 같은 세션을 동시에 갱신하려 할 경우, `Session`의 `@Version` 낙관락으로 충돌을 감지하고 **AI 서버발 완료 결과가 우선**한다 (사용자가 실제로 운동을 끝냈다면 그 결과가 진실에 더 가까우므로).
- **사후조건**: 방치된 세션이 영구 `IN_PROGRESS`로 남지 않음

### UC-07. 세션 상세 리포트 조회

- **액터**: 사용자
- **사전조건**: 완료된 세션(`COMPLETED`) 존재
- **기본 흐름**
  1. 사용자가 세션 상세 화면 진입 → `GET /reports/session/{sessionId}` 호출.
  2. 서버가 해당 세션의 `pose_data`를 조회해 worst 구간, 파트별 점수, 이전 세션 대비 비교를 계산한다.
  3. `SessionReportResponseDto`를 반환한다.
- **현재 알려진 한계**: worst 구간 등은 **조회 시점에 즉석 재계산**된다 (`ReportService.getSessionReport`). 세션 종료 시 미리 계산해두는 precompute-on-write 구조는 아직 없음 — 조회량이 늘면 매번 재계산 비용이 발생하는 구조적 갭으로 인지되어 있음.
- **사후조건**: 없음 (읽기 전용)

---

## 4. 그 외 유스케이스 (요약)

| ID | 기본 흐름 요약 | 예외/비고 |
|---|---|---|
| UC-03 온보딩 정보 설정 | `PATCH /member/onboarding/{email}`로 페르소나·키/몸무게·선호영상 단계별 저장, `YoutubeValidator`로 URL 검증 | 잘못된 유튜브 URL → 검증 실패 응답 |
| UC-04 기준 동작 영상 등록 | `POST /exercises/{exerciseId}/reference?youtubeUrl=` → YouTube 다운로드 → MediaPipe로 관절 좌표 추출 → `ExerciseReference` 저장 (운동당 1회성 사전 작업) | 실패 시 해당 운동은 세션 시작 불가 (기준 데이터 없음) |
| UC-06 자세 피드백 발화 로그 저장 | 세션 종료/세트 경계 시점에 AI 서버가 gRPC `ExerciseService.ReportFeedbackBatch`로 배치 전송 → `SessionFeedbackLog` 저장 | 실시간 매 rep 호출 금지(부하 분리 설계), REST 배치 경로는 2026-05-26 gRPC로 통일되며 폐기 |
| UC-08 캘린더/주간 요약 조회 | `GET /reports/calendar?year&month`, `GET /reports/weekly-summary` | 읽기 전용 |
| UC-09 일일 메모 작성 | `POST /reports/daily-logs`로 메모/기분(`Mood`) upsert | 같은 날짜 재작성 시 덮어쓰기(upsert) |
| UC-10 TTS 설정 변경 | `PATCH /preferences/tts`로 on/off, 속도(0.5~2.0) 변경 | 범위 밖 값 → 검증 실패 |
| UC-11 싱크로율 임계값 변경 | 관리자가 `PATCH /admin/exercises/{exerciseId}/thresholds` 호출 | `beginner < advanced` 제약 위반 시 거부, `ROLE_ADMIN` 아니면 403 |
| UC-12 회원 탈퇴 | `DELETE /member/{email}` → 회원 및 연관 데이터 전체 삭제 | 개별 세션만 선택 삭제하는 기능은 없음(전체 삭제 경로만 존재) — 알려진 갭 |

---

## 5. 계획 단계 유스케이스 (원본 요구사항 정의 기준, 현재 미구현/부분구현)

원본 PPT(HO-PT 6쪽)에는 있으나 아직 코드로 실현되지 않았거나 검증이 안 된 유스케이스. 정식 사전/기본/예외 흐름 템플릿은 실제 구현 시점에 채운다 — 지금은 "무엇을 해야 하는지"와 "왜 아직인지"만 정리.

| ID | 이름 | 사전조건(예정) | 기본 흐름 요약(예정) | 현재 막힌 지점 |
|---|---|---|---|---|
| UC-13 | 운동 타이머 표시 | 세션 진행 중 | 세션 시작 시각부터 경과 시간을 화면에 표시 | 백엔드에 `start_time`/`end_time`은 있으나 프론트 타이머 UI 존재 여부 미확인 |
| UC-14 | 관리자 카테고리 관리 | 관리자 로그인 | 운동 카테고리 CRUD | `ExerciseCategory` enum만 존재, 관리 API·화면 없음 |
| UC-15 | 관리자 운동 영상 관리 | 관리자 로그인, 기준 영상 등록됨(UC-04) | 등록된 기준 영상 조회/삭제/재등록 | 등록(UC-04) 외 조회·삭제·재등록 API 없음 |
| UC-16 | AI 리포트 자동 생성 | 세션 완료(UC-05) | 세션 데이터를 LLM에 요약 요청 → 자연어 리포트 생성 | `GptFeedbackService` 등 호출 코드 자체가 없음, `OPENAI_API_KEY` 환경변수만 준비됨 |
| UC-17 | 운동 목표 설정/달성 현황 확인 | 로그인 | 사용자가 목표(횟수·기간 등) 설정 → 누적 데이터와 비교해 달성률 표시 | 목표 엔티티/API가 아예 없음 |
| UC-18 | 데이터 기반 개인화 루틴 추천 | 세션 기록 누적 | 과거 세션 데이터 분석 → 다음 루틴 추천 | 추천 알고리즘 설계부터 필요한 큰 작업, 2학기 범위 |
| UC-19 | 사용자 운동 패턴 분석 | 세션 기록 누적 | 사용자별 운동 습관/추세 분석 및 표시 | 분석 로직 자체가 없음, 2학기 범위 |
| UC-20 | 운동 세트 자동 구분 | 세션 진행 중 | rep을 세트 단위로 자동 그룹핑 | DB 스키마에 set 개념이 없음, AI 서버 분석기도 세트 미지원. [`project_squat_first`] 방침상 다른 운동 확장 시점과 함께 보류 |

## 관련 문서
- [`PRD.md`](./PRD.md) — 목표/범위/우선순위
- [`REQUIREMENTS.md`](./REQUIREMENTS.md) — API 레벨 요구사항 원본
- [`tasks/27-implementation-gaps.md`](./tasks/27-implementation-gaps.md) — 유스케이스 상 "알려진 한계"의 근거
- [`decisions/session-lifecycle-checklist.md`](./decisions/session-lifecycle-checklist.md) — UC-05/UC-05E 동시성 설계 근거
