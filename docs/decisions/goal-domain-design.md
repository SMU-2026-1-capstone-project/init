# Decision: 운동 목표(Goal) 도메인 설계 (BE-06)

상태: **✅ 확정 (2026-08-30 사용자 confirm)** — §3·§4·§5·§7 권고안 그대로 채택(rolling window). 착수 시 §2(트리거 위치)·§6(마이그레이션 번호) 그대로 적용
작성: 2026-08-30
배경: BE-07·BE-08 착수를 확정하는 과정에서 BE-06(목표 CRUD)을 같이 할지 물었고, BE-07/08과 기술적 의존은 없어 별도 회차로 미루기로 했다. 다만 BE-06 자체가 원 문서(`22-backend-tasks-detail.md`)에서 "goalType 정의가 비개발자 합의 필요"라고 착수 전 결정을 요구하고 있어, 이 문서로 정리한다.
연관: [`../tasks/22-backend-tasks-detail.md`](../tasks/22-backend-tasks-detail.md) §BE-06(원 스코프), [`../tasks/24-semester2-plan.md`](../tasks/24-semester2-plan.md) Week3-4(원 일정), [`../PRD.md`](../PRD.md) §5-3, [[project_squat_first]]

> 결정 ✅ 는 사용자 confirm 후 박제. 본 문서는 분석·권고.

---

## 1. 기존에 확정된 스코프 (22-backend-tasks-detail.md 기준)

| 항목 | 내용 |
|---|---|
| 엔티티 | `Goal` — `id`, `member`(FK), `goalType`, `targetValue`, `currentValue`, `periodStart`, `periodEnd`, `status`(IN_PROGRESS/ACHIEVED/FAILED) |
| Endpoint | `POST/GET/PATCH/DELETE /goals` |
| MVP 권장 | `goalType` 1종(`WEEKLY_SESSIONS`)부터 |
| 진척 갱신 | "세션 종료 콜백 시점에 `currentValue` 자동 증가" — 원문은 트리거 지점을 구체적으로 안 짚음 |

원 추정 5h. 아래 §2가 이 중 "진척 갱신" 항목의 실제 트리거 지점을, §3~5가 나머지 미결 항목을 다룬다.

---

## 2. 핵심 발견 — 원 문서가 가리키는 트리거 지점이 지금 코드와 다르다

`22-backend-tasks-detail.md`·`PRD.md`는 진척 갱신 트리거를 "세션 종료 콜백(`CompleteAnalysis` 핸들러)"라고 적었지만, 실제로 그런 이름의 핸들러는 없다. 코드를 확인한 결과:

- 세션 완료 처리는 `SessionService.applyComplete(request)`(`backend/src/main/java/com/shadowfit/service/Exercise/SessionService.java:280-309`) 한 곳이다. AI의 gRPC `CompleteAnalysis` 콜백이 `completeSession`을 거쳐 `self.applyComplete()`를 호출하는 구조.
- 이 메서드는 이미 **같은 트랜잭션 안에서 두 가지 부수효과**를 실행 중이다 — `dailyLogService.accumulateStats(...)`(:303-304)와 `precomputeReport(session)`(:306, precompute-on-write 패턴). `saveAndFlush(session)`(:300) 다음, `sessionMetrics.sessionTransition(...)`(:308) 이전이 그 자리.
- `session.complete(...)`가 `transitioned=false`를 돌려주면(멱등 가드 — AI 재전송 등) `applyComplete`는 조기 `return`하고 부수효과를 전혀 안 태운다(:296-298). 즉 **Goal 진척 갱신을 이 가드 통과 이후에 두면 별도 멱등 장치 없이 자동으로 중복 증가가 막힌다.**
- `applyComplete` 호출부(`completeSession`)는 낙관적 락 충돌 시 최대 3회 재시도한다(:262-277) — 매 재시도가 `applyComplete`를 처음부터 다시 실행하므로, Goal 갱신 로직도 이 재시도 안에서 문제없이 반복 가능해야 한다(위 멱등 가드가 이미 이를 보장).

→ **추천**: Goal 진척 갱신은 `applyComplete`에 `dailyLogService.accumulateStats`·`precomputeReport`와 같은 자리(같은 트랜잭션, 같은 위치)에 세 번째 호출로 추가한다. 이 프로젝트의 세션 종료→AI 통보는 아웃박스(비동기 at-least-once)를 쓰지만, 세션 **완료 시점의 부수효과**(dailyLog·report)는 전부 동기·같은 트랜잭션이 기존 관례다. Goal도 이 관례를 따르는 것이 정합적 — 별도 이벤트·아웃박스는 과설계.

---

## 3. goalType 스코프

| 후보 | 세션 이벤트로 자동 갱신 가능? | squat-first 충돌 | 비고 |
|---|:--:|:--:|---|
| **`WEEKLY_SESSIONS`** (권고) | ✅ `applyComplete` 1회당 +1 | 없음 (exercise 무관 총량) | 원 문서 권장, 가장 단순 |
| **`WEEKLY_MINUTES`** (권고, 같이) | ✅ `exerciseMinutes`가 이미 `applyComplete`에서 계산됨(:302) | 없음 | `WEEKLY_SESSIONS`와 같은 자리에서 같이 처리 가능 — 추가 비용 거의 0 |
| `TARGET_WEIGHT` 등 신체 지표 | ❌ 세션 완료와 무관한 별도 트리거(체중 기록 API 등) 필요 | 없음 | 이번 스코프에서 제외 추천 — 트리거 설계가 통째로 다른 별개 작업 |
| 운동 종목별 목표 (예: "스쿼트 주 3회") | ✅ | **충돌** — 지금은 스쿼트뿐이라 "종목별" 자체가 무의미 (BE-08 B안과 같은 이유) | 2학기 운동 확장 후 |

→ **추천**: `WEEKLY_SESSIONS` + `WEEKLY_MINUTES` 2종으로 시작(둘 다 세션 완료 한 지점에서 갱신되어 원가가 같음). `TARGET_WEIGHT`·종목별 목표는 이번 스코프 제외.

---

## 4. 주간 period 정책

원 스코프의 `periodStart`/`periodEnd` 컬럼을 어떻게 채우고 넘길지가 미결.

| 후보 | 설명 | 장단점 |
|---|---|---|
| (a) 스케줄러 자동 롤오버 | 매주 월요일 등 새 period 레코드 생성 + 이전 period status 확정 | `SessionTimeoutScheduler`류 패턴과 통일감. 새 스케줄러 1개 추가, 타임존/기준 요일 정의 필요 |
| (b) 사용자 수동 설정 | 목표 생성 시 사용자가 `periodStart/End` 지정, 끝나면 갱신 안 함(끝난 목표는 그대로 종료) | 구현 단순하지만 "매주 자동 반복"이라는 목표의 자연스러운 기대와 어긋남 |
| **(c) rolling window** (권고) | `periodStart/End` 컬럼을 없애고 "최근 7일" 조회 시점 계산으로 대체 | 스케줄러·컬럼·롤오버 로직 자체가 사라져 스코프가 크게 줄어듦. 다만 "이번 주(월~일) 목표"가 아니라 "최근 7일 누적"이 되어 원 스펙과 의미가 살짝 달라짐 — 이 차이가 허용되는지 확인 필요 |

→ **추천**: (c). 스케줄러 신규 도입 없이 원 추정(5h) 안에서 끝낼 수 있고, `applyComplete`에서의 갱신도 "최근 7일 COMPLETED 세션 재집계" 또는 "currentValue 단순 +1, 매 조회 시 7일 넘은 값은 자동 제외" 둘 중 하나로 단순하게 구현 가능. 단 (a)/(b) 대비 스펙 변경이라 confirm 필요.

---

## 5. status(ACHIEVED/FAILED) 판정 시점

- (c) rolling window를 택하면 `status`는 저장할 필요 없이 조회 시 `currentValue >= targetValue` 비교로 계산 가능 — 배치 불필요.
- (a)/(b)를 택하면 period 마감 시점에 별도로 status를 확정하는 배치/스케줄러가 필요.

→ §4에서 (c)를 택하면 이 항목도 같이 단순해진다.

---

## 6. 마이그레이션 번호

- 현재 워킹트리에 `V11__add_trainer_assignments_table.sql`(커밋 전, 다른 작업)이 있다. `goals` 테이블은 착수 시점에 워킹트리를 재확인해 `V12` 이후 번호로 잡아야 충돌하지 않는다.

---

## 7. 결정 항목 (✅ 2026-08-30 확정)

| 결정 | 후보 | 확정 | 비고 |
|---|---|:--:|---|
| goalType 범위 | `WEEKLY_SESSIONS`만 / `+WEEKLY_MINUTES` / `TARGET_WEIGHT` 포함 | ✅ **WEEKLY_SESSIONS + WEEKLY_MINUTES** | §3 |
| 진척 갱신 위치 | `applyComplete` 내 동기 / 별도 이벤트·아웃박스 | ✅ **`applyComplete` 동기** (기존 precomputeReport와 같은 자리) | §2 |
| period 정책 | (a) 스케줄러 / (b) 수동 / (c) rolling window | ✅ **(c) rolling window** | §4 |
| status 판정 | 조회 시 계산 / 배치 확정 | ✅ **조회 시 계산** | §5 |
| 마이그레이션 번호 | — | 착수 시점 재확인, V12+ | §6 |

---

## 결정 로그

- **2026-08-30 (초안)**: BE-06 분석 문서 신설. 핵심 발견 — 원 문서의 트리거 지점("CompleteAnalysis 콜백 핸들러")이 낡은 서술이고, 실제로는 `SessionService.applyComplete()`가 유일한 세션 완료 처리 지점이며 이미 `dailyLogService.accumulateStats`·`precomputeReport` 두 부수효과가 같은 트랜잭션에서 실행 중임을 확인(§2) — Goal 갱신도 그 자리에 추가하는 것을 권고. goalType은 WEEKLY_SESSIONS+WEEKLY_MINUTES로 스코프 축소 권고(§3), period는 rolling window로 단순화 권고(§4, 원 스펙에서 컬럼 자체를 없애는 변경이라 confirm 필요).
- **2026-08-30 (확정)**: 사용자가 rolling window(§4 (c)안)로 확정. §3·§5·§7 권고안 전체 그대로 채택. `periodStart`/`periodEnd` 컬럼은 원 스펙에서 제외 — `goals` 테이블 설계 시 반영. BE-06 착수는 BE-07·BE-08 이후로 유지([[결정은 사용자가, Claude 는 추천만]]).

---

## 관련 문서

- [`../tasks/22-backend-tasks-detail.md`](../tasks/22-backend-tasks-detail.md) — BE-06 원안
- [`../tasks/24-semester2-plan.md`](../tasks/24-semester2-plan.md) — Week3-4 원 일정(참고용, 위 정정 반영 안 됨)
- [`../../backend/src/main/java/com/shadowfit/service/Exercise/SessionService.java`](../../backend/src/main/java/com/shadowfit/service/Exercise/SessionService.java) — `applyComplete`(:280-309), `precomputeReport`(:372-392)가 갱신 위치의 근거
- [`./recommendation-algorithm.md`](./recommendation-algorithm.md) — 같은 형식·같은 "결정은 사용자가" 원칙으로 작성된 BE-08 분석 문서
