# ShadowFit 아키텍처 문서 (arc42)

작성일: 2026-09-01
성격: **arc42 12섹션을 상위 스켈레톤으로 쓴다.** 각 섹션은 새로 쓰지 않고 이미 있는 문서를 연결·요약한다 — 사용자 확정(2026-09-01, [[feedback_decision_doc]]).
기존 [`architecture/README.md`](./architecture/README.md)(AI↔Backend 결합 4문서)는 이 문서의 §5·§6·§9 아래로 편입된다 — 폐기 아님, 더 좁은 스코프의 하위 문서로 유지.
갱신 트리거는 `architecture/README.md`와 동일 — RPC 추가/삭제, 전달 보장 변경, 컨테이너 추가/삭제, 실패 처리 변경, 판정 기준 변경.

---

## 1. 소개 및 목표 (Introduction and Goals)

무엇을 만드는지, 왜: [`01-project-overview.md`](./01-project-overview.md) · [`PRD.md`](./PRD.md) · [`REQUIREMENTS.md`](./REQUIREMENTS.md)

한 줄 요약: 사용자 카메라 자세와 기준 영상(로컬/YouTube)을 비교해 **싱크로율**을 실시간 계산하고, TTS·리포트·달력으로 돌려주는 운동 자세 교정 앱.

품질 목표는 §10으로 분리.

## 2. 제약사항 (Constraints)

- Spring Boot **3.5.16 고정, 4.x 메이저 업그레이드 미채택** — [[project_boot4_no_migration]], [`decisions/major-version-upgrade-policy.md`](./decisions/major-version-upgrade-policy.md)(springdoc/Gradle 9도 같은 문, 결정 대기)
- 사용자 노출 텍스트(TTS·메시지)는 **한국어 전용** — [[project_korean_only]]
- 부하테스트 환경이 i3-6100 2코어 단일 머신(MySQL+백엔드+ghz 동거) — 절대 RPS·천장 수치는 신뢰하지 말고 메커니즘·상대·델타만 — [[project_loadtest_env_constraint]]
- AI 서버는 프로세스당 GIL 한계로 **N=3 멀티프로세스**가 천장 — [[project_ai_ceiling_gil_n3_closed]]

## 3. 시스템 범위와 컨텍스트 (Context and Scope)

업무 컨텍스트(외부 액터·시스템): [`c4/README.md`](./c4/README.md) Level 1
기술 컨텍스트(포트·네트워크·프로토콜): [`architecture/ai-backend-integration.md`](./architecture/ai-backend-integration.md) §1·§2, `docker-compose.yml`

## 4. 솔루션 전략 (Solution Strategy)

핵심 구조 선택과 근거만 나열 — 각 항목의 대안 비교는 링크된 결정 문서에.

| 선택 | 근거 문서 |
|---|---|
| Spring ↔ FastAPI 간 gRPC(양방향) | [`decisions/grpc-vs-webclient.md`](./decisions/grpc-vs-webclient.md) — ⚠️ **미확정** 분석 문서, 아직 ADR 없음(추천은 현행 유지) |
| 세션 종료 통보에 아웃박스 패턴 | [`decisions/outbox-reliable-messaging.md`](./decisions/outbox-reliable-messaging.md) · [ADR-0001](./adr/0001-use-transactional-outbox-for-session-end-notification.md) |
| 실시간 프레임은 프론트→AI 직결(Spring 우회), 세션 고정 라우팅 | [`c4/README.md`](./c4/README.md) "이 다이어그램에서만 보이는 것" |
| AI 멀티프로세스 N=3 | [`decisions/per-process-ceiling-cause.md`](./decisions/per-process-ceiling-cause.md)(원인·N 확정) · [ADR-0002](./adr/0002-split-ai-server-into-3-processes.md) — 이전엔 [`decisions/ai-process-ceiling-cause.md`](./decisions/ai-process-ceiling-cause.md)(원인 후보를 못 가른 선행 조사 문서)를 잘못 링크하고 있었다 |
| 저장소는 MySQL 단일(Redis 미도입) | [`decisions/redis-adoption.md`](./decisions/redis-adoption.md), [`decisions/mysql-vs-postgresql.md`](./decisions/mysql-vs-postgresql.md) — ⚠️ **둘 다 미확정** 분석 문서, 아직 ADR 없음 |

## 5. 빌드 블록 뷰 (Building Block View)

컨테이너 단위 구조: [`c4/README.md`](./c4/README.md) Level 2
Spring 내부 패키지 구조: [`02-folder-structure.md`](./02-folder-structure.md)
AI↔Backend 결합면 상세(RPC 목록·필드): [`architecture/ai-backend-integration.md`](./architecture/ai-backend-integration.md) §3

## 6. 런타임 뷰 (Runtime View)

대표 시나리오만 — 상세 시퀀스는 링크된 문서에.

- 세션 시작~종료 정상 흐름: [`architecture/ai-backend-integration.md`](./architecture/ai-backend-integration.md) §3 RPC 표
- AI 상태 소실 후 재부착(`ReattachAnalysis`): [`decisions/session-resume-and-ai-state.md`](./decisions/session-resume-and-ai-state.md)
- 세션 라이프사이클 전반(타임아웃·중복 종료 등 경계 케이스): [`decisions/session-lifecycle-checklist.md`](./decisions/session-lifecycle-checklist.md)
- 그룹/실시간 동기화(WebSocket): [`decisions/multiuser-realtime-sync.md`](./decisions/multiuser-realtime-sync.md), [`decisions/group-websocket-heartbeat.md`](./decisions/group-websocket-heartbeat.md)

## 7. 배포 뷰 (Deployment View)

- 배포 절차·prod와 dev compose 차이: [`19-deployment.md`](./19-deployment.md), `docker-compose.prod.yml`
- 백업·복구(RTO/RPO 실측): [`decisions/backup-restore-rto-rpo.md`](./decisions/backup-restore-rto-rpo.md)
- 리버스 프록시·TLS: [`decisions/reverse-proxy-and-tls.md`](./decisions/reverse-proxy-and-tls.md)
- 관측 스택 구성(Prometheus/Grafana): [`../monitoring/README.md`](../monitoring/README.md)

## 8. 횡단 관심사 (Crosscutting Concepts)

| 관심사 | 문서 |
|---|---|
| 관측성(correlation id 전파) | [`decisions/observability-correlation-id.md`](./decisions/observability-correlation-id.md) |
| 회복탄력성(서킷브레이커·deadline) | `architecture/ai-backend-integration.md` §1 |
| 인증(내부 토큰, JWT) | [`decisions/token-lifecycle.md`](./decisions/token-lifecycle.md), [`decisions/oauth-implementation-considerations.md`](./decisions/oauth-implementation-considerations.md) |
| 동시성(낙관적 락) | `architecture/ai-backend-integration.md` §1, [`decisions/session-detector-ownership.md`](./decisions/session-detector-ownership.md) |
| 세션 타임아웃 | [`15-session-timeout-guide.md`](./15-session-timeout-guide.md) |

## 9. 아키텍처 결정 (Architecture Decisions)

- 확정된 결정의 짧은 요약: [`adr/`](./adr/) (2026-09-01 이후 신규 결정부터)
- 트레이드오프 분석·실측·미결정 사안 전체: [`decisions/`](./decisions/)

## 10. 품질 요구사항 (Quality Requirements)

- SLO·판정선: [`decisions/slo-baseline.md`](./decisions/slo-baseline.md)
- 부하테스트 전략·용어: [`decisions/load-test-strategy.md`](./decisions/load-test-strategy.md), [`decisions/load-test-glossary.md`](./decisions/load-test-glossary.md)
- 프로덕션 신호 체크리스트: [`decisions/production-signal-checklist.md`](./decisions/production-signal-checklist.md)

## 11. 리스크와 기술 부채 (Risks and Technical Debt)

- 아키텍처 리뷰 스냅샷: [`decisions/architecture-review-2026-08-11.md`](./decisions/architecture-review-2026-08-11.md)
- 메이저 버전 업 결정 대기(springdoc/Gradle 9): [`decisions/major-version-upgrade-policy.md`](./decisions/major-version-upgrade-policy.md)
- AI↔Backend 결합 미결 분기(콜백 신뢰성·proto 동기화 등): [`decisions/ai-backend-coupling.md`](./decisions/ai-backend-coupling.md)
- 문서 드리프트 패턴(기능은 맞고 보장·관측·운영이 빠짐): [[project_doc_drift_pattern]]

## 12. 용어집 (Glossary)

부하테스트 용어: [`decisions/load-test-glossary.md`](./decisions/load-test-glossary.md)

| 용어 | 의미 |
|---|---|
| 싱크로율 | 사용자 자세와 기준 자세 시퀀스 간 유사도 점수 |
| 아웃박스(outbox) | 세션 종료 통보를 DB에 먼저 적재하고 별도 발행기가 폴링·재시도하는 패턴 |
| 세션 고정 라우팅 | 한 세션의 프레임을 항상 같은 AI 워커 프로세스로 보내는 라우팅(`X-AI-Worker` 헤더) |
| 재부착(Reattach) | AI 서버가 세션 상태를 잃었을 때 DB 값으로 복구하는 gRPC 호출 |
