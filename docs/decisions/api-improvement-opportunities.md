# 지금까지 만든 API 표면 감사 — 더 디벨롭할 게 있는지

작성일: 2026-07-11
상태: **전체 해결 완료 (2026-07-15)** — 9개 항목 전부 커밋 반영, 근거는 각 절 및 §8 결정 로그 참고
대상 진로: 백엔드(Spring) 신입. API 완성도·기본기 증명이 목표.
연관: [`report-read-path.md`](./report-read-path.md)(리포트 읽기 경로의 **쿼리/성능** 축 — 본 문서는 같은 엔드포인트의 **인가** 축), [`production-signal-checklist.md`](./production-signal-checklist.md)(캐싱·서킷브레이커·커넥션 설정은 여기서 이미 다룸, 본 문서는 중복 없음)

> ⚠️ 이 문서는 성능/부하 튜닝이 아니다. 캐싱·서킷브레이커·HikariCP·다운샘플링은 `production-signal-checklist.md`·`pose-ingest-downsampling.md`에서 이미 다뤄졌다. 여기서 보는 건 **REST 컨트롤러 10개 + gRPC 서비스 1개의 API 표면 자체** — 인가, 검증, 에러 응답 일관성, CRUD 완성도, 문서화.

---

## 0. 방법론

`backend/src/main/java/com/shadowfit/controller/` 10개 컨트롤러 전체 + 각 컨트롤러가 호출하는 서비스 계층, `backend/src/main/proto/exercise.proto`, `GlobalExceptionHandler`, `SecurityConfig`/`SecurityPathConfig`, `application.yml`의 `security.whitelist`를 코드로 직접 읽고 확인. 추측 없음 — 모든 항목에 파일:라인 근거.

ai-server(Python)는 범위 밖 ([[feedback_minimize_python_changes]]).

---

## 1. 한눈에 보는 결과

| 컨트롤러 | 인가 | 검증 | 비고 |
|---|---|---|---|
| `AdminExerciseController` | ✅ `@PreAuthorize("hasRole('ADMIN')")` | ✅ `@Valid` | 모범 사례 |
| `SessionController` | ✅ 소유권 체크(서비스 레벨) | — | 모범 사례 (§2-③ 참고 패턴) |
| `SessionFeedbackController` | ✅ 소유권 체크(서비스 레벨) | — | 페이징 없음은 **협의 #18로 이미 의도된 결정**, 갭 아님 |
| `PreferenceController` | ✅ 본인 스코프 | ✅ `@Valid` | 모범 사례 |
| `ExerciseReportController` | ✅ **해결(2026-07-15, `52049d0`)** | — | §2-① 참고 |
| `ExercisesController` | ✅ **해결(2026-07-15, `a2e2fd1`/`23c8953`)** | — | §2-②·§2-③ 참고 |
| `MemberController` | ✅ **해결(2026-07-15, `450df7c`)** | ⚠️ 편차 | §2-② 참고 |
| `FeedbackTemplateController` | — (공개 리소스라 무관) | — | 이상 없음 |
| `TestController` | ✅ **삭제됨(2026-07-15, `2342710`)** | — | §4-① |
| gRPC `ExerciseGrpcService` | ✅ `InternalAuthInterceptor` | — | §3-④ **해결(`85d6bff`)** |

---

## 2. 발견 — 인가(Authorization) — 최우선

이 프로젝트엔 이미 **소유권 체크를 올바르게 하는 패턴**이 존재한다 — `SessionController.endSession`([`SessionController.java:26-32`](../../backend/src/main/java/com/shadowfit/controller/SessionController.java))가 `SessionService.endSession`([`SessionService.java:132-138`](../../backend/src/main/java/com/shadowfit/service/exercise/SessionService.java))에서 `session.getMember().getId().equals(currentMemberId)`로 검증 후 `ACCESS_DENIED`를 던지고, `SessionFeedbackController`도 동일 패턴으로 `memberId`를 서비스에 넘긴다. **아래 항목들은 이 패턴이 이미 사내에 있는데도 빠뜨린 케이스**라는 게 핵심 — "몰라서"가 아니라 "일관되게 안 지켜서"인 게 인터뷰 서사상으로도 더 정직하다.

### 2-① `ExerciseReportController.getSessionReport` — 타인 세션 리포트 열람 가능 — ✅ 해결(`52049d0`)

[`ExerciseReportController.java:24-31`](../../backend/src/main/java/com/shadowfit/controller/ExerciseReportController.java) — `CustomUserDetails customUserDetails`를 파라미터로 받아놓고 **한 번도 안 씀**. `ReportService.getSessionReport(sessionId)`([`ReportService.java:33`](../../backend/src/main/java/com/shadowfit/service/report/ReportService.java))도 `sessionId`만으로 조회, 세션 소유자와 요청자 비교 로직이 전혀 없음.

**영향**: 로그인만 하면 `sessionId`를 순차 대입해 **다른 사용자의 운동 리포트(자세 분석·싱크율·피드백)를 전부 열람 가능** — OWASP Top 1 (Broken Access Control) 정확히 해당하는 IDOR.

**추천**: `SessionController.endSession`과 동일 패턴으로 `getSessionReport(sessionId, currentMemberId)`에 소유권 체크 추가. 변경량 작음(서비스 메서드 시그니처 + if문 하나), 리스크 대비 효과 큼 — **1순위 추천**.

### 2-② `MemberController` — 이메일 경로 3개 전부 소유권 체크 없음 — ✅ 해결(`450df7c`)

- `deleteMember`([`MemberController.java:51-56`](../../backend/src/main/java/com/shadowfit/controller/MemberController.java)): `DELETE /member/{email}` — `@AuthenticationPrincipal` 자체가 없음. 로그인한 아무 사용자나 **타인의 이메일을 알면 그 계정을 탈퇴시킬 수 있음**.
- `getOnboarding`([`:58-63`](../../backend/src/main/java/com/shadowfit/controller/MemberController.java)): `GET /member/onboarding/{email}` — 타인의 온보딩 정보(PII) 열람 가능.
- `updateOnboarding`([`:65-72`](../../backend/src/main/java/com/shadowfit/controller/MemberController.java)): `PATCH /member/onboarding/{email}` — 타인의 온보딩 정보 수정 가능. `@Valid`도 없음(§3-② 참고).

`SecurityConfig`(`anyRequest().authenticated()`)가 로그인을 요구하긴 하지만, **"로그인했다"와 "본인이다"는 다른 문제** — 세 엔드포인트 전부 후자를 검증 안 함.

**추천**: 경로의 `email`을 신뢰하지 말고 `@AuthenticationPrincipal`에서 얻은 이메일과 비교(또는 애초에 경로에서 `email` 제거하고 인증 주체 기준으로만 동작). 계정 삭제는 특히 되돌릴 수 없는 작업이라 **가장 먼저 고칠 후보**.

### 2-③ `ExercisesController` — 엔드포인트 3개의 인가 수준이 제각각 — ✅ 해결(`a2e2fd1`, `23c8953`)

- `extractReference`([`ExercisesController.java:32-44`](../../backend/src/main/java/com/shadowfit/controller/ExercisesController.java)): 주석엔 "✅ 기준 좌표 추출 (관리자/등록용)"이라 적혀 있는데 `AdminExerciseController`와 달리 **`@PreAuthorize` 없음** — 아무 로그인 사용자나 임의 `exerciseId`에 임의 `youtubeUrl`을 넣어 서버 쪽 추출 작업을 트리거 가능. `youtubeUrl`도 형식 검증이 없어 URL이 아닌 문자열이 그대로 서비스로 흘러감.
- `completeSession`([`:83-107`](../../backend/src/main/java/com/shadowfit/controller/ExercisesController.java)): `@Deprecated`라고 표시돼 있지만 **라우팅은 여전히 살아있고, 인증 자체가 없음**(`@AuthenticationPrincipal`도 없음). `SessionController.endSession`으로 대체됐다는 주석까지 있는데 실제로 지워지지 않아, 익명 요청으로 **임의 세션을 원하는 결과값(총 횟수·싱크율 등)으로 강제 완료 처리 가능**한 그림자 엔드포인트가 남아있음.

**추천**: `extractReference`는 관리자 전용이 맞다면 `AdminExerciseController`처럼 `@PreAuthorize("hasRole('ADMIN')")` 추가. `completeSession`은 "deprecated 주석 + 방치"가 아니라 **실제 삭제** — 죽은 코드가 아니라 살아있는 무방비 엔드포인트라 위생이 아니라 보안 문제.

### 2-④ actuator 전체 공개 — 데모 편의 vs 운영 관행 — ✅ 해결(`4129200`, health만 공개)

`application.yml:76`의 `security.whitelist`에 `/actuator/**`가 통째로 있고, `management.endpoints.web.exposure.include: health,caches,metrics,circuitbreakers,circuitbreakerevents`(`application.yml:83`)까지 켜져 있어 **누구나 인증 없이** 캐시 히트율·서킷브레이커 상태·메트릭을 조회 가능.

이건 순수 버그는 아님 — `production-signal-checklist.md`의 관측성 실측(§2-2, §2-3)이 이 엔드포인트들에 의존하고 있어 데모 편의상 열어둔 것으로 보임. 다만 실무 관행은 actuator를 별도 포트/내부망/`hasRole('ADMIN')`으로 잠그는 것. **결정 필요 항목**: 지금처럼 데모 편의 유지 vs `/actuator/health`만 공개하고 나머지는 인증 요구.

---

## 3. 발견 — 검증·에러 응답 일관성

### 3-① `GlobalExceptionHandler`가 `BusinessException`만 처리 — ✅ 해결(`3cd0ba8`)

[`GlobalExceptionHandler.java:16-30`](../../backend/src/main/java/com/shadowfit/global/error/GlobalExceptionHandler.java) 주석에 명시: "다른 예외는 Spring 기본 핸들러에 위임". 즉:
- `@Valid` 실패(`MethodArgumentNotValidException`)는 앱의 `ErrorResponseDto` 형식이 아니라 **Spring 기본 에러 응답**으로 나감 — 클라이언트가 에러 바디를 두 가지 스키마로 파싱해야 함.
- 그 외 미처리 예외(NPE 등)는 Spring Boot 기본 500 처리로 감 — `server.error.include-message`/`include-stacktrace` 설정에 따라 내부 정보 노출 여지 있음(현재 설정값 미확인, 확인 필요).

**추천**: `@ExceptionHandler(MethodArgumentNotValidException.class)`와 캐치올 `@ExceptionHandler(Exception.class)`를 추가해 모든 에러 응답을 `ErrorResponseDto`로 통일. 신입 기본기로 채용 시그널상 가치 있고 구현 비용 작음.

### 3-② `@Valid` 누락 지점 (편차, 전면 부재 아님) — ✅ 해결(`85d6bff`)

`MemberController`의 로그인/로그아웃/회원가입, `AdminExerciseController`, `PreferenceController`는 전부 `@Valid` 사용 — 이미 컨벤션이 있다는 뜻. 다만:
- `ExerciseRecordController.saveDailyLog`([`ExerciseRecordController.java:48-51`](../../backend/src/main/java/com/shadowfit/controller/ExerciseRecordController.java)) — `DailyLogRequestDto`에 `@Valid` 없음.
- `MemberController.updateOnboarding`([`MemberController.java:66-68`](../../backend/src/main/java/com/shadowfit/controller/MemberController.java)) — `OnboardingRequestDto`에 `@Valid` 없음.

**추천**: 이미 있는 컨벤션을 두 곳에 마저 적용. 사소하지만 "일관성" 자체가 신입 코드 리뷰에서 자주 보는 기준.

### 3-③ `ExerciseRecordController.saveDailyLog`의 수동 null 체크 + 디버그 로그 잔재 — ✅ 해결(`85d6bff`)

[`:52-58`](../../backend/src/main/java/com/shadowfit/controller/ExerciseRecordController.java)에 `customUserDetails == null` 수동 체크와 `log.error("#### [ERROR] ...")`/`log.info("#### [DEBUG] ...")` 스타일이 남아있음 — 다른 컨트롤러들의 slf4j 사용과 스타일이 다르고, `anyRequest().authenticated()` 하에서 인증된 요청이면 원래 null일 수 없어 이 분기는 죽은 코드일 가능성. 삭제 또는 이유가 있다면(예: 특정 필터 우회 케이스 대응) 주석으로 명시 권장.

### 3-④ gRPC 에러 처리 — 4개 메서드 중 1개만 다른 패턴 — ✅ 해결(`85d6bff`)

`ExerciseGrpcService`의 `savePoseDataBatch`·`extractReferenceData`·`reportFeedbackBatch`는 예외를 `io.grpc.Status.XXX.asRuntimeException()`으로 감싸는데, `completeAnalysis`([`ExerciseGrpcService.java:98-101`](../../backend/src/main/java/com/shadowfit/service/exercise/ExerciseGrpcService.java))만 `responseObserver.onError(e)`로 원본 예외를 그대로 전달 — AI 서버 쪽에 내부 예외 클래스/메시지가 그대로 노출됨. `InternalAuthInterceptor`로 보호되는 서버-서버 통신이라 위험도는 낮지만, 3:1 패턴 불일치는 리뷰에서 지적받을 지점.

---

## 4. 발견 — CRUD/설계 완성도

### 4-① `TestController` — 프로덕션에 남은 디버그 엔드포인트 — ✅ 삭제됨(`2342710`)

[`TestController.java`](../../backend/src/main/java/com/shadowfit/controller/TestController.java) 전체 — `GET /api/check-my-hash?password=...`가 원문 비밀번호를 쿼리 파라미터로 받아 bcrypt 해시를 반환. 문제 3개:
1. 비밀번호가 GET 쿼리 스트링으로 전달 → 액세스 로그·프록시 로그·브라우저 히스토리에 평문 잔존 위험.
2. `@Tag`/`@Operation` 없음 — Swagger 문서에서 의도적으로 숨겨진 정황(실수로 남은 디버그 코드로 보임).
3. 클래스 주석("파일명과 클래스명 일치", "효재님이 편한 걸로 쓰셔도 됩니다")이 학습/스캐폴딩 흔적 — 실제 기능이 아님.

**추천**: **삭제.** 신입 포트폴리오 코드 리뷰에서 이런 잔재는 "정리 안 된 코드"로 바로 감점되는 항목이라 다른 어떤 항목보다 우선순위 높음 (비용 0, 리스크 제거).

### 4-② `ExercisesController.completeSession` 죽은 코드 — §2-③에서 이미 다룸 — ✅ 삭제됨(`23c8953`)

보안 문제(무방비 라우팅)이자 동시에 위생 문제(deprecated 주석 + 미삭제). 삭제 하나로 양쪽 다 해결.

### 4-③ 운동 목록 조회(`GET /exercises`) API 자체가 없음

10개 컨트롤러 전체의 `@GetMapping`을 훑어도 운동 카탈로그를 그냥 나열하는 엔드포인트가 없음(`FeedbackTemplateController`는 `exerciseId`가 given일 때 템플릿만 조회, 운동 자체 목록 아님). [[project_squat_first]] 방침대로 지금은 스쿼트 단일 운동이라 프론트가 하드코딩했을 가능성이 높고, 그렇다면 **지금 만들 필요 없음** — 여러 운동으로 확장하는 시점의 일이지 지금의 갭이 아니다. 새로 발견했다고 과장하지 않고 참고용으로만 기록.

### 4-④ 페이지네이션 부재 — 이미 의도된 결정, 갭 아님

`SessionFeedbackController`의 두 엔드포인트는 문서 주석에 "MVP — 페이징 없음, 협의 #18"이라고 명시돼 있어 **이미 사용자가 결정한 사항**. 새 발견처럼 다루지 않음. 다른 리스트형 엔드포인트(`weekly-summary`, `calendar`)는 기간으로 이미 스코프가 좁혀져 있어 페이지네이션이 필요한 무한 목록이 아님 — 갭 없음.

---

## 5. 발견 — OpenAPI 문서화

`TestController`(§4-①, 어차피 삭제 대상)를 빼면 나머지 9개 컨트롤러는 전 엔드포인트에 `@Tag`+`@Operation`이 일관되게 붙어있음 — 이 축은 **이미 잘 돼 있다**. 새로 손댈 것 없음.

---

## 6. 오버엔지니어링 주의 — 안 할 것

| 하지 말 것 | 이유 |
|---|---|
| 모든 리스트 API에 커서 페이지네이션 선제 도입 | `SessionFeedbackController`는 이미 "페이징 없음"이 협의된 결정(§4-④). 필요치도 않은 곳에 규모 대비 과한 추상화 금지 |
| DTO ↔ Entity 매핑 계층을 MapStruct 등으로 전면 교체 | 코드 확인 결과 컨트롤러들은 이미 DTO만 반환(엔티티 직접 노출 없음) — 문제 없는 걸 "더 세련되게" 바꾸는 건 이 프로젝트 범위 밖 |
| 운동 목록 API를 지금 설계 | §4-③ — 스쿼트 단일 운동 단계에서 불필요, 확장 시점의 일 |
| API 버저닝(`/v1/`, `/v2/`) 도입 | 캡스톤 규모에 오버엔지니어링, 언급만 가능한 카드 |

---

## 7. 종합 추천 순서 — 전부 완료

1. **`TestController` 삭제** — ✅ `2342710`
2. **`ExercisesController.completeSession` 삭제** — ✅ `23c8953`
3. **`MemberController` 이메일 경로 3개 소유권 체크** — ✅ `450df7c`
4. **`ExerciseReportController.getSessionReport` 소유권 체크 추가** — ✅ `52049d0`
5. **`ExercisesController.extractReference`에 `@PreAuthorize("hasRole('ADMIN')")` 추가** — ✅ `a2e2fd1`
6. **`GlobalExceptionHandler`에 검증 실패·캐치올 핸들러 추가** — ✅ `3cd0ba8`
7. **`@Valid` 누락 2곳 보완, gRPC 에러 처리 패턴 통일, actuator 노출 범위 재검토** — ✅ `85d6bff`(검증·gRPC), `4129200`(actuator)

항목 1~5는 전부 **접근 제어/보안 기본기**라 "CRUD를 더 채워라"가 아니라 "이미 짠 코드의 결함을 코드 리뷰로 찾아 고쳤다"는 포트폴리오 서사로 쓸 수 있음 — 신입 백엔드 면접에서 IDOR를 스스로 찾고 고친 경험은 실제로 가치 있는 시그널.

---

## 결정 로그
- 2026-07-11: 문서 최초 작성 — 분석/추천만, 착수 항목은 전부 사용자 confirm 대기.
- 2026-07-15: 9개 항목 전부 커밋으로 반영 완료 확인(docs 감사 중 발견) — `52049d0`(IDOR), `450df7c`(MemberController 소유권), `a2e2fd1`(extractReference 권한), `23c8953`(completeSession 삭제), `4129200`(actuator 축소), `3cd0ba8`(예외 핸들러), `85d6bff`(@Valid·gRPC 에러·디버그 잔재 정리), `2342710`(TestController 삭제). 문서 상태를 "분석/추천"에서 "전체 해결 완료"로 갱신.
