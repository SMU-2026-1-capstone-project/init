# ADR-0010: 세션 종료는 클라이언트가 Spring 하나만 호출하고, Spring이 AI에 단일 분배한다(ET-B/ET-H)

작성일: 2026-05-26
상태: Accepted
근거 문서: [`../decisions/session-end-trigger.md`](../decisions/session-end-trigger.md)

## Context

세션 종료 신호 경로에 원래 결정(ET-A)이 있었다 — 클라이언트가 Spring(`PATCH /sessions/{id}/end`)과 AI(신규 HTTP endpoint) 양쪽을 직접, 병렬로 호출하는 방식이다. 그런데 이후 두 전제가 바뀌었다 — ① feedback batch 송신 경로가 REST에서 gRPC(`ReportFeedbackBatch`)로 통일되면서 ET-A의 근거였던 "AI가 batch 송신 주체이니 종료 신호도 AI가 직접 받는 게 책임이 일관된다"는 논거가 약해졌다. ② 클라이언트가 아직 어느 endpoint도 구현하지 않은 상태라 마이그레이션 부담이 사실상 0이었다. 게다가 실제 코드는 이미 두 endpoint가 어중간하게 공존하고 있었다 — `PATCH /sessions/{id}/end`(Spring만, AI 호출 없음)와 `PUT /exercises/sessions/{id}/stop`(Spring→AI gRPC, ET-B 흐름의 90% 완성본).

## Decision

**ET-B/ET-H(클라→Spring→AI, Spring이 단일 분배자)를 채택한다.** 클라이언트는 `PATCH /sessions/{id}/end` 하나만 호출한다. `SessionService.endSession`이 `Session.endTime`을 기록하고, 트랜잭션 커밋 이후(`TransactionSynchronization` afterCommit)에 `exerciseAnalysisService.stopAnalysis(sessionId)`를 호출해 gRPC `StopAnalysis`로 AI에 종료를 알린다. 중복이던 `ExercisesController.stopSession`(`PUT /exercises/sessions/{id}/stop`) endpoint는 폐기한다.

## Consequences

- 얻는 것: 내부 통신이 전부 gRPC로 정합된다(ET-A라면 클라→AI 구간만 HTTP가 남아 채널이 분산됐을 것). AI 측 코드 변경이 0이다 — 이미 `StopAnalysis` 핸들러가 존재해 신규 endpoint를 만들 필요가 없다(ai-server 변경 최소화 원칙과 부합). 실제 코드가 이미 이 형태에 90% 가까웠던 만큼 정리량이 적다(삭제가 추가보다 크다). 클라이언트 구현이 endpoint 1개 호출로 단순해지고, 강제 종료·부분 실패에 대한 safety net(`SessionTimeoutScheduler`)을 그대로 활용할 수 있다.
- 감수하는 것: Spring이 죽으면 AI가 종료 신호를 아예 못 받아 그 세션의 batch 결과가 손실될 수 있다 — ET-A였다면 AI가 클라이언트로부터 직접 신호를 받아 이 손실을 피할 수 있었다는 게 ET-A의 유일한 진짜 장점이었다. 트랜잭션 경계 안에서 외부 gRPC 호출을 직접 하면 안 되므로 afterCommit으로 분리해야 한다는 구현 제약이 남는다.
- 다음에 영향받는 결정: 이 결정이 만든 "Spring afterCommit 직접 호출"이라는 단일 장애점은 이후 [ADR-0001](./0001-use-transactional-outbox-for-session-end-notification.md)이 트랜잭셔널 아웃박스로 대체하며 정면으로 다룬다 — 이 결정 자체가 아웃박스 도입의 전제 구조를 만든 셈이다.

## Alternatives considered

- ET-A(클라이언트가 Spring·AI 양쪽을 직접, 병렬 호출) — gRPC 통일 이후 "책임 일관" 근거가 약해졌고, AI 측에 신규 endpoint가 필요해 ai-server 변경 최소화 원칙에 어긋난다. 클라이언트가 병렬 호출과 부분 실패 처리를 직접 감당해야 해 구현 부담이 크다. 유일한 실질 장점(Spring 다운 시 AI batch 손실 방지)도 `SessionTimeoutScheduler`와 AI 측 재시도 큐로 ET-B에서 충분히 보강 가능하다고 판단해 기각했다.
