# 에러 코드 & 예외 처리 가이드

마지막 업데이트: 2026-08-23 (시도 제한 A007·A008 추가 — 이슈 #394. 같이 밀린 것 둘도 정정: A006 누락·핸들러 부재 메모)
범위: Spring 백엔드의 에러 코드 enum, 응답 포맷, gRPC 에러 매핑. 사용자가 받게 되는 메시지는 한국어 단일 ([[project_korean_only]]).

---

## 1. 에러 응답 형식 (REST)

코드상 정의된 응답 DTO:
```java
// global/error/ErrorResponseDto.java
{
  "status": 400,                          // HTTP 상태 코드
  "message": "올바르지 않은 입력값입니다.",   // 사용자 표시용 한국어
  "timestamp": "2026-05-23T14:30:00"      // 발생 시각
}
```

> ~~**현재 상태 메모**: … **GlobalExceptionHandler 는 아직 코드에 없음** … 본 문서는 도입 후를 가정한 약속.~~
>
> 🔄 **2026-08-23 정정 — 이 메모는 낡았다.** `global/error/GlobalExceptionHandler.java` 가 **있다**(`@RestControllerAdvice`). `BusinessException` 뿐 아니라 `@Valid` 실패·`AccessDeniedException`·타입 변환 실패·`NoResourceFoundException` 까지 같은 포맷으로 통일돼 있다. 즉 이 문서는 더 이상 「약속」이 아니라 **동작 서술**이다. (기능은 붙는데 문서의 「보장·운영」 절이 뒤처지는 [[project_doc_drift_pattern]] 의 그 모양이다 — 시도 제한을 적으러 왔다가 발견했다)

---

## 2. 에러 코드 카탈로그 (`ErrorCode.java` 기준)

### 공통 (C00x)
| 코드 | HTTP | 메시지 |
|------|------|--------|
| `C001` `INVALID_INPUT_VALUE` | 400 | 올바르지 않은 입력값입니다. |
| `C002` `METHOD_NOT_ALLOWED` | 405 | 허용되지 않은 HTTP 메서드입니다. |
| `C003` `INTERNAL_SERVER_ERROR` | 500 | 서버 내부 오류가 발생했습니다. |
| `C004` `INVALID_TYPE_VALUE` | 400 | 입력값의 타입이 적절하지 않습니다. |
| `C005` `HANDLE_ACCESS_DENIED` | 403 | 접근이 거부되었습니다. |

### 인증/인가 (A00x)
| 코드 | HTTP | 메시지 |
|------|------|--------|
| `A001` `UNAUTHORIZED` | 401 | 로그인이 필요한 서비스입니다. |
| `A002` `ACCESS_DENIED` | 403 | 해당 리소스에 대한 접근 권한이 없습니다. |
| `A003` `TOKEN_EXPIRED` | 401 | 인증 토큰이 만료되었습니다. |
| `A004` `INVALID_TOKEN` | 401 | 잘못된 인증 토큰입니다. |
| `A005` `LOGIN_INPUT_INVALID` | 401 | 비밀번호가 틀렸습니다. |
| `A006` `REFRESH_TOKEN_REUSED` | 401 | 만료된 로그인 정보입니다. 보안을 위해 다시 로그인해 주세요. |
| `A007` `TOO_MANY_LOGIN_ATTEMPTS` | **429** | 로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요. |
| `A008` `TOO_MANY_AUTH_REQUESTS` | **429** | 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요. |

> 📌 `A006` 은 표에 **빠져 있던 것**을 이번에 채웠다(이슈 #135 때 enum 에는 들어갔으나 문서에 안 왔다).

#### A007·A008 — 시도 제한 (이슈 #394)

429 를 쓰는 이유는 401/403 과 **클라가 할 일이 다르기 때문**이다. 자격증명이 틀린 것도 권한이 없는 것도 아니라 **너무 잦다**는 뜻이고, 답이 「고쳐서 다시」가 아니라 **「기다렸다 다시」** 다.

그래서 둘 다 **`Retry-After` 헤더를 같이 싣는다** — 초 단위다. 429 는 「언제 다시 오라」를 못 주면 반쪽이다.

| | 키 | 어디서 | 무엇을 막나 |
|---|---|---|---|
| `A007` | **계정**(이메일, 소문자 정규화) | `MemberService.login` | 여러 IP 에서 **한 계정**을 두드리는 것. **실패만** 세고 성공하면 초기화 |
| `A008` | **IP**(경로별 버킷) | `AuthRateLimitFilter` | 한 곳에서 **여러 계정**을 훑는 것 — 가입 스팸·계정 열거 |

⚠️ **`Retry-After` 값은 정확한 잔여가 아니라 상한**(창 길이)이다. 틀리는 방향이 「필요보다 오래 기다린다」(안전)라서 그렇게 뒀다.

⚠️ **A008 은 MVC 밖(서블릿 필터)에서 나간다.** `GlobalExceptionHandler` 를 안 타므로 그 필터가 같은 본문·같은 헤더를 **직접** 쓴다. 두 429 의 응답 모양이 갈리면 클라가 「어떤 429 는 다르다」를 배워야 하므로, 형식을 맞춘 것이 계약이다.

한도 값과 그 **유도 근거**(가정 넷)는 `backend/src/main/resources/application.yml` 의 `security.rate-limit` 블록에 있다. 🔴 값만 보고 옮겨 적지 말 것 — 그 숫자는 가정에 매달려 있다.

### 사용자/페르소나 (U00x)
| 코드 | HTTP | 메시지 |
|------|------|--------|
| `U001` `USER_NOT_FOUND` | 404 | 존재하지 않는 사용자입니다. |
| `U002` `INVALID_PERSONA_TYPE` | 400 | 유효하지 않은 페르소나 설정입니다. |
| `U003` `USERID_DUPLICATION` | 400 | 이미 가입된 사용자입니다. |
| `U004` `USERNAME_DUPLICATION` | 400 | 이미 사용 중인 닉네임입니다. |

> `U003`(email)과 `U004`(username)를 나눈 것은 **문구 때문**이다 (이슈 #195). `ErrorResponseDto` 가
> `code` 를 싣지 않아 프론트는 `message` 로만 구분한다 — 닉네임만 겹친 신규 사용자에게
> "이미 가입된 사용자입니다"가 나가면 있지도 않은 자기 계정을 찾으러 간다.
> 두 코드 모두 400 인 것도 의도다(같은 폼의 두 필드가 다른 status 를 내지 않도록).

### 운동 세션 (W00x)
| 코드 | HTTP | 메시지 |
|------|------|--------|
| `W001` `EXERCISE_NOT_FOUND` | 404 | 존재하지 않는 운동 종목입니다. |
| `W002` `METADATA_NOT_FOUND` | 404 | 운동 메타데이터(JSON/Video)를 찾을 수 없습니다. |
| `W003` `SESSION_NOT_FOUND` | 404 | 진행 중인 운동 세션을 찾을 수 없습니다. |
| `W004` `S3_UPLOAD_ERROR` | 500 | 파일 저장소(S3) 연결에 실패했습니다. |
| `W005` `SESSION_ALREADY_IN_PROGRESS` | **409** | 이미 진행 중인 운동 세션이 있습니다. |
| `W006` `SESSION_DELETE_NOT_ALLOWED` | **409** | 진행 중인 세션은 삭제할 수 없습니다. |
| `W007` `EXERCISE_NOT_SUPPORTED` | **400** | 아직 분석을 지원하지 않는 운동입니다. |
| `W008` `SESSION_REATTACH_EXPIRED` | **410** | 재개할 수 있는 시간이 지난 세션입니다. 새로 시작해 주세요. |
| `W009` `SESSION_REATTACH_UNAVAILABLE` | **503** | 지금은 이어하기를 할 수 없습니다. 잠시 후 다시 시도해 주세요. |
| `W010` `WITHDRAWAL_BLOCKED_BY_ACTIVE_SESSION` | **409** | 운동을 종료한 뒤 탈퇴할 수 있습니다. |

> 🔴 **W005~W010 은 2026-08-08 에 추가한 것이다** — 코드에는 있었는데 이 문서에만 없었다(`ErrorCode.java:35-60`). 프론트가 이 문서를 계약으로 읽으면 **여섯 가지 응답을 «모르는 에러» 로 처리한다.**
>
> 특히 프론트가 지금 다뤄야 하는 것들이 여기 몰려 있다:
> - **`W008`(410)·`W009`(503)** — 이어하기 분기. [`handoff/frontend-session-lifecycle.md`](./handoff/frontend-session-lifecycle.md) §4 가 요구하는 처리의 응답 코드다. 410 은 «포기하고 새 세션», 503 은 «잠시 후 재시도» 로 **동작이 갈린다**
> - **`W005`(409)** — 진행 중 세션이 있는데 새로 시작하려 할 때. `GET /sessions/active` 로 되찾는 경로와 짝이다
> - **`W010`(409)** — 진행 중 세션이 있는 회원의 탈퇴 차단([`decisions/withdrawal-with-active-session.md`](./decisions/withdrawal-with-active-session.md))
>
> ⚠️ **`W008` 이 410(Gone)인 것은 의도된 선택이다.** 404 가 아니라 410 을 쓰면 *"있었지만 이제 못 쓴다"* 가 구분된다 — 프론트가 «남의 세션/없음»(404)과 «시간 초과»(410)를 다르게 안내할 수 있다.

### 필터링 엔진 (V00x)
| 코드 | HTTP | 메시지 |
|------|------|--------|
| `V001` `LOW_SYNC_RATE` | 400 | 운동 싱크로율이 너무 낮아 기록되지 않았습니다. |
| `V002` `INVALID_WORKOUT_DATA` | 400 | 부정행위 또는 유효하지 않은 움직임이 감지되었습니다. |
| `V003` `INSUFFICIENT_COUNT` | 400 | 최소 운동 횟수를 채우지 못했습니다. |
| `V004` `DATA_INTEGRITY_VIOLATION` | 422 | 전달된 좌표 데이터가 손상되었거나 형식이 맞지 않습니다. |

### AI/GPT (AI00x)
| 코드 | HTTP | 메시지 |
|------|------|--------|
| `AI001` `AI_FEEDBACK_FAILED` | 503 | AI 피드백 생성 중 오류가 발생했습니다. |
| `AI002` `PROMPT_TEMPLATE_ERROR` | 500 | GPT 프롬프트 생성 로직에 오류가 발생했습니다. |
| `AI003` `AI_QUOTA_EXCEEDED` | 429 | AI 서비스 호출 할당량을 초과했습니다. |

### 인프라/캐시 (I00x)
| 코드 | HTTP | 메시지 |
|------|------|--------|
| `I001` `REDIS_CONNECTION_FAILURE` | 500 | 캐시 서버 연결에 실패했습니다. |
| `I002` `API_RESPONSE_TIMEOUT` | 504 | API 응답 시간이 초과되었습니다. (Threshold: 500ms) |
| `I003` `DATABASE_LOCK_FAILURE` | 500 | 데이터베이스 트랜잭션 처리 중 오류가 발생했습니다. |

### 리포트 (R00x)
| 코드 | HTTP | 메시지 |
|------|------|--------|
| `R001` `REPORT_NOT_FOUND` | 404 | 리포트를 찾을 수 없습니다. |

---

## 3. 코드에서 던지는 방법

```java
// 사용자가 없을 때
if (!memberRepository.existsByEmail(email)) {
    throw new BusinessException(ErrorCode.USER_NOT_FOUND);
}

// 운동 세션 조회 실패
Session session = sessionRepository.findById(sessionId)
    .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
```

> 외부 API 응답 매핑 시에도 같은 패턴: catch 한 후 적절한 `ErrorCode` 로 다시 throw.

---

## 4. gRPC 에러 매핑 (Spring ↔ AI)

내부 gRPC 채널에서는 별도 코드 체계 — `io.grpc.Status` 를 직접 사용. REST `ErrorCode` 와는 분리.

| 상황 | gRPC Status | 어디서 |
|------|-------------|--------|
| 내부 토큰 불일치 | `UNAUTHENTICATED` | `InternalAuthInterceptor` (Spring), `AuthInterceptor` (AI `server.py`) |
| 세션 ID 없음·잘못된 요청 | `INVALID_ARGUMENT` | (현재 명시적 매핑 없음, 일반 RuntimeException → `UNKNOWN`) |
| AI 분석 실패 | `INTERNAL` | (현재 명시적 매핑 없음) |
| 콜백 재시도 한계 초과 | (Spring 측 로그만) | `spring_client.report_complete_analysis` — gRPC error 자체를 swallow, ERROR 로그 |

> **메모**: 현재 양쪽 모두 `UNAUTHENTICATED` 외에는 명시적 Status 매핑이 거의 없음. 일반 예외는 gRPC 기본 `UNKNOWN` 으로 떨어진다. 매핑 강화는 [`decisions/ai-backend-coupling.md`](./decisions/ai-backend-coupling.md) 의 분기 A (콜백 신뢰성) 와 같이 다룰 수 있음.

---

## 5. 알려진 미흡점 (도입 작업 후보)

1. **`GlobalExceptionHandler` 미구현** — 위에 명시. `@RestControllerAdvice` + `@ExceptionHandler(BusinessException.class)` 도입 시 응답 형식 통일.
2. **Validation 에러 매핑** — `@Valid` 실패의 `MethodArgumentNotValidException` 을 `C001 INVALID_INPUT_VALUE` 로 매핑하는 핸들러 필요.
3. **`OptimisticLockingFailureException` 매핑** — 현재는 `SessionService.completeSession` 에서 3회 재시도 후 throw. 핸들러에서 `I003 DATABASE_LOCK_FAILURE` 로 매핑 가능.
4. **AI/gRPC 에러 → REST 매핑** — AI 콜백 실패가 사용자 요청 응답에 즉시 영향을 주는 경로는 없음 (비동기). 향후 동기 경로 추가 시 `AI001 AI_FEEDBACK_FAILED` 활용.
5. **에러 코드 → ErrorCode 객체 노출** — 응답 DTO 가 `code(String)` 를 포함하지 않음. 클라이언트에서 분기할 때 메시지 문자열 매칭이라 깨지기 쉬움. `code` 필드 추가 권장.

---

## 6. 클라이언트 처리 권장 (한국어 UX)

- 401(`A001`, `A003`, `A004`, `A006`) → 로그인 화면 강제 이동
- **429(`A007`, `A008`) → `Retry-After` 초만큼 기다렸다 재시도.** 사용자에게는 남은 시간을 보여주는 것이 맞다 (🔶 프론트 미구현)
- 403(`A002`, `A005`, `C005`) → "권한이 없습니다" 토스트
- 404(`U001`, `W001`, `W003`, `R001`) → 빈 화면 + 새로고침 안내
- 422·400 (`V00x`, `INVALID_*`) → 메시지를 그대로 사용자에게 표시 (이미 한국어)
- 429(`AI003`) → "잠시 후 다시 시도해주세요" 토스트
- 503·504(`AI001`, `I002`) → 재시도 버튼 + "서버 응답이 느려요" 안내
- 500 (`C003`, `W004`, `I001`, `I003`) → "잠시 후 다시 시도" + 자동 재전송 X

---

## 관련 파일
- `backend/src/main/java/com/shadowfit/global/error/ErrorCode.java`
- `backend/src/main/java/com/shadowfit/global/error/BusinessException.java`
- `backend/src/main/java/com/shadowfit/global/error/ErrorResponseDto.java`
- (예정) `backend/src/main/java/com/shadowfit/global/error/GlobalExceptionHandler.java`
- `backend/src/main/java/com/shadowfit/global/config/InternalAuthInterceptor.java` (gRPC `UNAUTHENTICATED`)
- `ai-server/app/grpc/server.py` (AI 측 인증 인터셉터)
