# ADR-0001: 세션 종료 통보에 트랜잭셔널 아웃박스 패턴 채택

작성일: 2026-07-29
상태: Accepted
근거 문서: [`../decisions/outbox-reliable-messaging.md`](../decisions/outbox-reliable-messaging.md)

## Context

`SessionService.endSession`은 한 논리 작업으로 두 시스템에 쓴다 — MySQL(`exercise_sessions.end_time`, `@Transactional` 커밋으로 원자적)과 FastAPI(`StopAnalysis` gRPC 통보, 원자성 없음)다. 이 통보가 유실되는 경로가 둘 있었다 — gRPC 호출 실패(`onError`에 로그만 남기고 끝)와 서킷브레이커 OPEN 시 송신 자체를 스킵하는 경로. AI 서버는 `StopAnalysis`를 받아야만 `CompleteAnalysis`를 촉발하므로, 통보 하나가 새면 세션이 `IN_PROGRESS`로 방치되다 타임아웃 스케줄러가 30분 뒤 `FAILED`로 걷어가고, 사용자가 실제로 한 운동의 결과(reps·sync)가 영구 소실된다. 수신측은 이미 멱등(`applyComplete`가 first-write-wins)이라 송신 쪽 신뢰성만 보강하면 완결된다.

## Decision

**순수 트랜잭셔널 아웃박스(대안 A) + 폴링 발행기**를 채택한다. `endSession`의 트랜잭션 안에서 `outbox_events` 행을 INSERT하고(afterCommit 직접호출은 제거), 별도 `@Scheduled` 발행기가 `PENDING`을 폴링해 `StopAnalysis`를 동기(blocking stub + deadline)로 송신한다. 결과는 셋으로 분류한다 — 성공(`SENT`), 재시도 대상(gRPC 실패·서킷 OPEN 스킵 모두 `PENDING` 유지 + 지수 백오프), 터미널 실패(`success=false` → 재시도 없이 `FAILED`). 다중 인스턴스 확장을 겨냥해 `SELECT ... FOR UPDATE SKIP LOCKED` + `PROCESSING` 상태 + `lock_expires_at` 리스 + 소유권 조건(CAS) 갱신을 1차부터 포함했다. correlation id를 행에 저장해 시간·프로세스 경계를 넘는 추적을 확보했다.

## Consequences

- 얻는 것: at-least-once 송신 + 기존 멱등 수신 = 통보 전달의 effectively-exactly-once. 서킷 OPEN 구간에서도 통보가 버려지지 않고 보류된다. `docker pause`(네트워크 단절)·서킷 OPEN 두 시나리오에서 유실 0·`COMPLETED` 회수를 실측 확인했다(2026-07-29).
- 감수하는 것: **AI 프로세스 자체가 재시작하면(상태가 in-memory) outbox로도 분석 결과는 회수되지 않는다** — 이건 outbox의 결함이 아니라 명시적 범위 밖이며, `docker restart` 시나리오에서 재시도 0회로 터미널 `FAILED`로 종결되는 것을 의도된 동작으로 실측 확인했다. 테이블 1개(`outbox_events`) + 발행기 1개가 늘고, 폴링 간격(1초)만큼 통보가 지연된다. `SENT` 행 정리는 소량 반복 DELETE를 채택했고(파편화 실측 완료, 누적 없음 확인) `FAILED` 행은 조사 가치 때문에 길게 보존한다.
- 다음에 영향받는 결정: 다중 인스턴스로 갈 때 발행기끼리는 SKIP LOCKED로 자연히 분산되지만, `SessionTimeoutScheduler` 등 다른 스케줄러 3개의 중복 tick 문제(T3)는 ShedLock이 필요한 별개 카드로 분리해뒀다 — 아직 미착수.

## Alternatives considered

- B(동기 재시도) — 재시도 중 인스턴스가 죽으면 "보낼 일"이 메모리에서 증발한다. 서킷 OPEN 구간은 재시도해도 계속 거부라 애초에 못 푼다.
- C(상태기반 리컨실리에이션) — 이 프로젝트의 실제 스키마로는 기존 컬럼만으로 판별 가능해 *기술적으로는* 충분했지만, 이벤트 타입이 하나만 늘어도 도메인 쿼리를 다시 짜야 하고 "outbox"만큼 채점되는 패턴명이 아니다. 확장 옵션으로만 문서에 남기고 구현은 보류.
- D(메시지 브로커, Kafka/SQS) — dual-write 문제를 안 푼다. "DB commit + 브로커 enqueue"가 여전히 두 시스템이고, 이 프로젝트는 소비자가 FastAPI 단일이라 브로커 자리를 gRPC 직접 호출이 대신한다.
- E(2PC/XA) — FastAPI/gRPC가 XA 참여자가 아니고 blocking·코디네이터 SPOF·운영 복잡도로 기각.
- F(현행 유지) — 타임아웃 스케줄러가 orphan을 수렴은 시키지만 "포기 처리"일 뿐 결과를 회수하지 못한다. 정직한 baseline으로만 남긴다.
