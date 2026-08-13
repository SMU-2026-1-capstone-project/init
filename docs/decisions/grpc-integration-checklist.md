# gRPC 좌표 송수신(AI ↔ Backend) 설계 체크리스트

작성: 2026-07-15 / 갱신: 2026-08-12(코드 재대조 — §2)
대상: 백엔드(Spring) 포폴. 아키텍처 3축(① AI 좌표 송수신·gRPC, ② 보고서 조회·DB, ③ 세션 생명주기) 중 **①번 축**의 설계 점검.
연관: [`../architecture/ai-backend-integration.md`](../architecture/ai-backend-integration.md)(현황 스냅샷), [`./ai-backend-coupling.md`](./ai-backend-coupling.md)(결합 방식 트레이드오프), [`./report-read-path.md`](./report-read-path.md)(②번 축, 같은 형식의 체크리스트)

> ✅=코드로 확인, 🔶=부분 적용/의도적 결정, ⬜=미착수. `report-read-path.md`와 동일한 표기.

---

## 0. 사고 순서 — 왜 이 순서인가

`report-read-path.md`의 8단계(저장구조→over-fetch→인덱스→규모→동시성→precompute→보존정책→N+1)와 같은 원리: **뒤 단계일수록 앞 단계의 답을 전제**하고, **앞쪽일수록 코드만 읽으면 답이 나오는 저비용·확정적 질문, 뒤쪽일수록 가정·판단이 필요한 고비용 질문**이다.

| # | 단계 | 왜 이 자리인가 |
|---|---|---|
| ① | 계약 구조 파악 | proto·RPC 목록·호출 방향부터 알아야 나머지를 논할 수 있음(순수 사실) |
| ② | 호출 단위 설계(배치/윈도우) | "저장 vs 조회 컬럼 대조"와 같은 원리 — 불필요하게 잘게 쪼갠 호출을 피하는 것. 규모 몰라도 판단 가능한 구조적 결정 |
| ③ | 인증/권한 경계 | 누가 호출 가능한지 — 이것도 규모와 무관하게 코드로 확정되는 질문 |
| ④ | 규모 역산 | 여기서부터 **가정**이 시작(호출 빈도·페이로드 크기를 DAU 가정으로 역산) |
| ⑤ | 장애 격리(타임아웃·서킷브레이커) | ④(호출 빈도·규모)를 모르면 임계값 설정 자체가 의미 없음 |
| ⑥ | 전달 보장·멱등성 | ⑤(장애가 어떤 모양으로 나는지)를 알아야 "재시도 시 뭐가 위험한지" 판단 가능 |
| ⑦ | 방향 비대칭 재검토 | 양방향 gRPC 관계면, ③~⑥의 답이 방향마다 다를 수 있음 — 마지막에 재확인 |

**리포트(②) 축과 다른 점**: 리포트는 순수 읽기라 "장애 격리" 개념 자체가 없었음(외부 서비스 호출이 없으므로). gRPC는 외부 서비스 호출이 핵심이라 ⑤⑥(장애·재시도)이 새로 들어오고, 대신 "precompute·보존정책" 같은 데이터 수명주기 개념은 여기 해당 없음(gRPC 자체는 저장 계층이 아님).

---

## 1. 체크리스트 (코드 대조 완료, 2026-07-15 / 재대조 2026-08-12)

| # | 요소 | 이 프로젝트 현황 | 상태 |
|---|---|---|---|
| ① | 프로토콜 계약 관리 | `exercise.proto`가 Spring·AI 양쪽에 각각 파일로 존재 — 필드 추가 시 두 서버 동시 배포 필요, 자동 동기화 없음 | 🔶 수동 동기화 |
| ② | 호출 단위(배치/윈도우) | 프레임마다 실시간 호출 금지 — **rep 완성 시점에 그 rep의 프레임 전체를 batch 전송**(`ai-server/app/api/endpoints/pose.py:145-169`) | ✅ 설계됨 |
| ③ | 인증 | Spring→AI: Bearer 토큰 헤더 첨부(`ExerciseAnalysisService.getAuthenticatedStub`). AI→Spring: `InternalAuthInterceptor` | ✅ 양방향 모두 있음 |
| ④ | 규모 역산 | 세션당 rep 수 × rep당 배치 호출 1회 — DAU 가정으로 호출 빈도 역산 가능(별도 실측은 안 함) | 🔶 개념만 |
| ⑤ | 타임아웃/데드라인 | `withDeadlineAfter(GRPC_CALL_TIMEOUT_SECONDS)` — hang 상태도 `DEADLINE_EXCEEDED`로 귀결시켜 서킷브레이커가 실패로 잡을 수 있게 함 | ✅ 있음(Spring→AI만) |
| ⑤ | 서킷브레이커 | Resilience4j로 `extractReferenceData`·`startAnalysis`·`stopAnalysis`·`reattachAnalysis` **4개** 호출 보호(재부착은 `ExerciseAnalysisService.java:339-356`). CLOSED→OPEN→HALF_OPEN 전체 생명주기 Docker(`docker stop`/`docker pause`)로 실측 확인(`production-signal-checklist.md` §2-3-3, §2-3-4) | ✅ 실측 완료 |
| ⑥ | 전달 보장·멱등성 | Spring→AI: **`StopAnalysis` 만 outbox 로 at-least-once**(`OutboxEventType.STOP_ANALYSIS`). `startAnalysis`·`extractReferenceData` 는 여전히 fire-and-forget. AI→Spring: **콜백마다 다르다** — 아래 §2-2 | 🔶 경로별로 갈림 |
| ⑦ | 방향 비대칭 | ③~⑥ 전부 **Spring→AI 방향만** 보호됨. AI→Spring 콜백은 계약상 3개지만 **실제로 도는 것은 `savePoseDataBatch`·`completeAnalysis` 2개**다(`reportFeedbackBatch` 는 호출자 부재 — §2-3). 반대 방향이라 Spring이 느려지면 AI가 안 보호됨 — 타임아웃·서킷 부재는 AI 코드 스코프 제외 결정(`production-signal-checklist.md` §2-3-4-2) | 🔶 결정된 갭 |
| — | 메시지 크기 제한 | 명시적 설정 없음(gRPC 기본 4MB) — rep당 5~30프레임 배치라 현재 규모에선 문제 없음 | ⬜ 미설정, 리스크 낮음 |
| — | 스트리밍 vs unary | **7개** RPC 전부 **unary**(`stream` 키워드 없음) — 프레임 실시간 스트림 대신 rep 단위 batch unary로 모은 설계(②)와 일치 | ✅ 의도적 선택 |
| — | 계약 대비 구현 | 7개 중 **2개가 한쪽만 구현**돼 있다 — `ExtractReferenceData`(AI 가 스텁), `ReportFeedbackBatch`(AI 에 호출자 없음). §2-3 | 🔶 계약≠동작 |

---

## 2. 재대조 결과 (2026-08-12)

### 2-1. 2026-07-15 에 적었던 갭은 해소됐다

`docs/tasks/22-backend-tasks-detail.md` 의 BE-10 이 "🔴 미착수"로 남아 있던 문제는 갱신됐다 — 현재 `:280` 에 **✅ 완료(2026-07-11)** 로 표시돼 있다.

### 2-2. 전달 보장은 「방향」이 아니라 「경로」마다 다르다

2026-07-15 판은 ⑥에 *"AI→Spring 콜백 재전송: `INSERT IGNORE` + `uk_session_event` 로 멱등 방어(✅)"* 라고 적었다. **이것은 세 경로 중 하나를 방향 전체로 일반화한 서술이었다.** 실제로 도는 두 경로 중 그 방어를 받는 것은 없다.

| AI→Spring 경로 | 재전송(AI) | 수신측 멱등(Spring) | 실질 |
|---|---|---|---|
| `CompleteAnalysis` | ✅ 지수 백오프(`spring_client.py:71~`) | ✅ `COMPLETED` 가드(`SessionService.java:229-232`) + 낙관락 재시도 | 재전송·중복 모두 방어됨 |
| `SavePoseDataBatch` | ❌ 없음(#188) | ❌ `pose_data` 에 유니크 키 없음(`idx_session_timestamp` 는 비유니크, DB 실측) | 실패하면 **rep 하나가 통째로 유실**. 중복이 안 나는 것은 방어가 아니라 **재전송 자체가 없어서**다 |
| `ReportFeedbackBatch` | — | ✅ `INSERT IGNORE` + `uk_session_event`(DB 에 실재 확인) | **멱등 장치만 완비돼 있고 그 경로가 안 돈다** |

Spring→AI 방향도 마찬가지로 갈린다 — `StopAnalysis` 만 outbox 를 타고, `startAnalysis`·`extractReferenceData` 는 여전히 fire-and-forget 이다.

### 2-3. 계약에 있으나 동작하지 않는 RPC 2개

proto 는 Spring·AI 양쪽 파일이 **완전히 동일**하다(diff 무차이). 그런데 계약이 곧 동작은 아니다.

| RPC | 상태 | 이슈 |
|---|---|---|
| `ExtractReferenceData` | AI 가 빈 응답 스텁 — 로그에 `미구현` 을 찍는다(`exercise_servicer.py:282-297`). 결과로 `exercise_references` 가 **0행**(2026-08-12 로컬 실측)이고, 정답지가 없으면 AI 는 전 rep 을 `sync_rate` 0 으로 채점한다 | #192 |
| `ReportFeedbackBatch` | AI 에 **호출자가 없다** — `spring_client.py` 의 전송 함수는 2개뿐. 원인은 전송 함수 부재가 아니라 **자세 문제 유형 감지기의 부재**다: Spring 은 8종 enum 을 요구하는데 AI 는 3종 심각도 문자열만 만든다(`squat_analyzer.py:382-387`) — 매핑이 원리적으로 불가능 | #193 |

두 번째 항목이 ⑦의 "콜백 3개" 서술을 무너뜨린 지점이다. **계약을 세면 3개이고, 도는 것을 세면 2개다.**

---

## 3. 관련 문서
- [`../architecture/ai-backend-integration.md`](../architecture/ai-backend-integration.md) — 결합 현황 스냅샷
- [`./ai-backend-coupling.md`](./ai-backend-coupling.md) — 결합 방식 트레이드오프(OPEN)
- [`./production-signal-checklist.md`](./production-signal-checklist.md) §2-3 — 서킷브레이커 구현·실측
- [`./report-read-path.md`](./report-read-path.md) — ②번 축(보고서 조회), 동일 형식의 체크리스트
- [`../tasks/22-backend-tasks-detail.md`](../tasks/22-backend-tasks-detail.md) BE-10 — 갱신 완료(§2-1)
- 이슈 #192 — 기준 좌표 파이프라인 부재(§2-3), #193 — 피드백 유형 감지기 부재(§2-3), #188 — `SavePoseDataBatch` 무재시도(§2-2)
