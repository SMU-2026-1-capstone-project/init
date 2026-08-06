# 세션 생명주기(상태 관리) 설계 체크리스트

작성: 2026-07-15
대상: 백엔드(Spring) 포폴. 아키텍처 3축(① AI 좌표 송수신·gRPC, ② 보고서 조회·DB, ③ 세션 생명주기) 중 **③번 축**의 설계 점검 — 이게 실제 포폴 헤드라인(`portfolio-narrative.md` §1).
연관: [`../portfolio/portfolio-narrative.md`](../portfolio/portfolio-narrative.md) §1(헤드라인 서사), [`../portfolio/realmysql-experiments.md`](../portfolio/realmysql-experiments.md) §③(lost-update 실험), [`../portfolio/interview-qa-kandl.md`](../portfolio/interview-qa-kandl.md) §1(Q&A 카드), [`./report-read-path.md`](./report-read-path.md)·[`./grpc-integration-checklist.md`](./grpc-integration-checklist.md)(다른 두 축, 동일 형식)

> ✅=코드로 확인, 🔶=부분 적용/의도적 결정, ⬜=미착수/발견된 갭.

---

## 0. 사고 순서 — 왜 이 순서인가

다른 두 축(report-read-path.md의 8단계, grpc-integration-checklist.md의 7단계)과 같은 원리: **뒤 단계일수록 앞 단계의 답을 전제**하고, **앞쪽일수록 코드만 읽으면 답이 나오는 저비용·확정적 질문, 뒤쪽일수록 가정·판단이 필요한 고비용 질문**이다.

| # | 단계 | 왜 이 자리인가 |
|---|---|---|
| ① | 상태 모델 파악 | 어떤 상태·허용 전이가 있는지부터 — 순수 구조 사실 |
| ② | 전이 트리거 파악 | 각 전이를 누가/무엇이 발동하는지 — 이것도 코드로 확정되는 사실 |
| ③ | 동시 충돌 지점 식별 | ①②를 겹쳐보면 "여러 트리거가 같은 세션을 동시에 건드릴 수 있는 지점"이 드러남 — 아직 판단 아니고 사실 확인 |
| ④ | 규모/빈도 역산 | 이 충돌이 실제로 얼마나 자주 나는지 가정 시작(DAU 기준) |
| ⑤ | 동시성 제어 메커니즘 선택 | ④(빈도)를 모르면 락 방식(낙관/비관) 선택 근거가 없음 |
| ⑥ | 충돌 해소 정책(누가 이기는가) | ⑤(락 방식)가 정해진 다음에야 "충돌 시 누가 이기는가"를 논할 수 있음 |
| ⑦ | 멱등성(재수신 방어) | 같은 신호가 두 번 와도 안전한지 — ⑥과 별개 축(중복 vs 경합) |
| ⑧ | 미종결 방지(안전망) | 위 전이들이 하나도 안 일어나 영원히 미종결 상태로 남는 경우의 방어 |
| ⑨ | 부수효과 전파의 원자성 | 상태 전이에 딸려오는 다른 데이터 갱신이 같은 트랜잭션인지 |
| ⑩ | 외부 통지 시점(afterCommit) | 커밋 확정 후에만 외부 알림 — 롤백 시 오통지 방지 |

**다른 두 축과 다른 점**: 리포트(②)는 순수 읽기라 "상태 전이"라는 개념 자체가 없고, gRPC(①)는 통신 프로토콜 축이라 "세션 상태"라는 개념이 없음. ①~③(상태·전이·충돌)은 이 축 고유의 시작점이고, ⑨⑩(부수효과 전파·통지 시점)도 상태 전이가 있는 이 축에만 해당.

---

## 1. 체크리스트 (코드 대조 완료, 2026-07-15)

| # | 항목 | 이 프로젝트 현황 | 상태 |
|---|---|---|---|
| ① | 상태 모델 | `Status`: `IN_PROGRESS`/`COMPLETED`/`CANCELED`/`FAILED`. **`CANCELED`는 enum에만 정의돼 있고 실제 전이 트리거가 코드 어디에도 없음** — 죽은 상태 | 🔶 갭 발견 |
| ② | 전이 트리거 | `endSession`(사용자, endTime만 기록) / `completeSession→applyComplete`(AI 콜백, COMPLETED) / `markAsFailedIfStillInProgress`(스케줄러, FAILED) | ✅ 3개 확인, `CANCELED` 트리거 없음(①과 동일 갭) |
| ③ | 동시 충돌 지점 | 스케줄러(→FAILED)와 AI 콜백(→COMPLETED)이 같은 `Session` row를 동시에 갱신 가능 | ✅ 식별 완료 |
| ④ | 규모/빈도 역산 | DAU 1,000 가정 → "저경합·서로 다른 세션 위주"로 판단(`realmysql-experiments.md` §③) | ✅ 가정 명시 |
| ⑤ | 동시성 제어 메커니즘 | 비관락(상시 블로킹 비용) 대신 낙관락(`Session.java:66 @Version`) 채택 — 실험(naive RMW/원자UPDATE/FOR UPDATE/CAS 비교)으로 근거 확보 | ✅ 실측 완료 |
| ⑥ | 충돌 해소 정책 | 낙관락 충돌(`ObjectOptimisticLockingFailureException`) 시 **AI 콜백 결과 우선**, 스케줄러가 양보 + 콜백은 최대 3회 재시도(`SessionService.completeSession`) | ✅ 구현 완료 |
| ⑦ | 멱등성 | `applyComplete`: `status==COMPLETED`면 즉시 return(first-write-wins). 중복 콜백 안전 | ✅ 구현 완료 |
| ⑧ | 미종결 방지(안전망) | `SessionTimeoutScheduler`가 1분마다 `IN_PROGRESS` 훑어 기대시간+버퍼 초과 시 `FAILED` 전환 | ✅ 구현 완료 |
| ⑨ | 부수효과 전파의 원자성 | `DailyLog` 누적(`accumulateStats`)은 세션 완료와 같은 트랜잭션(`applyComplete`)에서 처리(2026-07-15 배선). `reports` precompute는 **미착수**(`report-read-path.md` §9) | 🔶 절반만 완료 |
| ⑩ | 외부 통지 시점 | `endSession`에서 `TransactionSynchronization.afterCommit`으로 AI에 `stopAnalysis` 통보 — DB 커밋 확정 후에만 외부 호출 | ✅ 구현 완료 |

---

## 2. 발견된 갭 정리

- **`Status.CANCELLED` 죽은 상태**: 사용자가 운동 중 "취소"할 방법이 현재 API 표면에 없음(중단은 `endSession`이 곧 완료 취급). 실제로 "취소"라는 사용자 시나리오가 필요한지, 아니면 enum에서 정리할지는 **결정 필요** — 지금은 기록만.
  - 2026-08-06: 상수 이름이 `CANCELED`(L 하나)라 스키마 ENUM `'CANCELLED'`와 어긋나 있던 것을 정정([#106](https://github.com/Shadowfit/init/issues/106)). **죽은 상태라는 사실 자체는 그대로**고, 존치 여부 결정도 여전히 열려 있다 — 철자만 맞췄다.
- **상태 전이 이력(감사 로그) 없음**: 현재 상태만 컬럼에 남고, "언제 무엇이 이 상태로 바꿨는지"는 로그(`log.warn` 등) 레벨에서만 남지 DB 테이블로 남지 않음. 운영 디버깅·분쟁 대응엔 아쉬울 수 있으나 지금 규모에선 과설계 소지.
- **⑨ reports precompute 미착수** — `report-read-path.md` §9에서 이미 다룸, 착수 시 이 축(②)과 ③번 축이 실제로 맞물리는 지점.

---

## 3. 관련 문서
- [`../portfolio/portfolio-narrative.md`](../portfolio/portfolio-narrative.md) §1 — 세션 정합성 헤드라인 서사
- [`../portfolio/realmysql-experiments.md`](../portfolio/realmysql-experiments.md) §③ — lost-update 재현·낙관락 근거
- [`../portfolio/interview-qa-kandl.md`](../portfolio/interview-qa-kandl.md) §1 — 관련 Q&A 카드
- [`./report-read-path.md`](./report-read-path.md) — ②번 축(보고서 조회), 동일 형식
- [`./grpc-integration-checklist.md`](./grpc-integration-checklist.md) — ①번 축(gRPC), 동일 형식
- 코드: `Session.java`, `SessionService.java`(`endSession`·`completeSession`·`applyComplete`·`markAsFailedIfStillInProgress`), `SessionTimeoutScheduler.java`, `DailyLogService.java`(`accumulateStats`)
