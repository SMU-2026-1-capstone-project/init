# 구현 갭 정리 (2026-07-20 코드 검증 기준)

작성: 2026-07-20. 오늘 대화 중 실제 코드/문서로 재검증해서 확인한 미구현·미결정 항목 정리. `22-backend-tasks-detail.md`는 방대하지만 일부 항목이 실제 구현 상태를 못 따라가고 있어(예: 세션 피드백 조회 API는 실제로는 구현돼 있는데 그 문서엔 미구현으로 표시) 전체 신뢰는 어려움 — 이 문서는 오늘 직접 코드 확인한 것만 담음.

---

## 1. DB/백엔드 핵심 — 오늘 코드로 확인한 미구현

- [x] **precompute-on-write** — **완료(2026-07-24)**. `WorstSectionCalculator`로 계산 로직 분리, `SessionService.applyComplete`가 세션 완료와 같은 트랜잭션에서 `reports`에 worst 구간 저장(`detailed_analysis` JSON). `ReportService.getSessionReport`는 이제 이 값을 우선 읽고, precompute 이전 리포트(시드 등 `detailed_analysis` 없음)만 하위호환으로 즉석 재계산. 세부 설계 결정 4가지는 [`report-read-path.md §9`](../decisions/report-read-path.md). 설계는 [`db-deep-dive.md §B-3`](../portfolio/db-deep-dive.md) ✅.
- [x] **TTL 자동 만료 스케줄러** — **완료(2026-07-24)**. `PoseDataPartitionScheduler`(매일 새벽 4시) 신설 — 이번 달+지난 1개월 버퍼만 남기고 그 이전 파티션 `DROP PARTITION`(아카이빙 없음, 완전 폐기), `pfuture`가 실데이터를 안 떠안게 이번 달 기준 +2개월치 파티션을 `REORGANIZE`로 미리 생성. precompute-on-write가 선행돼 있어 원본 삭제돼도 리포트 요약은 보존됨. 세부 설계는 [`report-read-path.md §9-B`](../decisions/report-read-path.md).
- [x] **개별 세션 삭제 기능** — **완료(2026-07-24)**. `DELETE /sessions/{sessionId}`(`SessionController`) → `SessionService.deleteSession` — 소유권 확인, `IN_PROGRESS`는 409(`SESSION_DELETE_NOT_ALLOWED`, AI 분석 중일 수 있어 종료 후에만 삭제 가능), `pose_data`는 명시적 동기 삭제(FK 없음), `reports`·`session_feedback_logs`는 FK CASCADE로 자동 정리(§5-1 설계 그대로: 세션 1건 규모라 동기 삭제로 충분, 회원 탈퇴 같은 비동기 불필요). 구현 중 **entity-schema drift 발견·수정**: `Report`/`SessionFeedbackLog`의 `session` 필드가 Hibernate `@OnDelete` 없이 매핑돼 있어 테스트(H2, JPA 엔티티 기반 DDL)엔 실 `schema.sql`의 `ON DELETE CASCADE`가 반영 안 되고 있었음 — `@OnDelete(action=CASCADE)` 추가로 정정. [`pose-data-partition-fk-tradeoff.md §1-1·§5-1`](../decisions/pose-data-partition-fk-tradeoff.md).
- [x] **report 생성 멱등성** — `reports.session_id`에 UNIQUE(`uk_report_session`) 추가 완료(2026-07-24, `mysql/schema.sql`). 같은 날 precompute-on-write가 구현되면서 `SessionService.precomputeReport`가 실제 report 생성 경로가 됐고, `applyComplete`의 기존 멱등성 체크(이미 COMPLETED면 조기 반환)와 이 UNIQUE가 이중 방어를 이룸. [`db-deep-dive.md §C`](../portfolio/db-deep-dive.md).

## 2. 판단 완료, 착수 여부만 미결정

- [ ] **다운샘플링(1초 다운샘플)** — 위치(A: AI서버 / B: Spring / C: 안 함)도, 착수 여부도 미결정. 효과는 실측 완료(R≈5에서 RPS +126%, p99 −70%, 저장 5배↓). [`pose-ingest-downsampling.md §7`](../decisions/pose-ingest-downsampling.md).
- [ ] **Redis 도입** — "MVP 단계 MySQL 부족 미증명"으로 보류 확정(CLOSED). T1~T5 트리거 발생 시 재검토. [`redis-introduction.md`](../decisions/redis-introduction.md).

## 3. 이미 스스로 인지한 약점

- [x] **관측성** — **1차 완료(2026-07-28)**. correlation id 5단계 전파(HTTP·gRPC 진입점 발급/수용, MDC+`logback-spring.xml` 패턴, `@Async` TaskDecorator, Spring↔FastAPI 양방향 metadata, 스케줄러 tick 자체 발급) + 커스텀 메트릭 3종(상태 전이·낙관락 충돌·배치 행수). 기존 `log.` 호출 70여 건은 한 줄도 수정 안 함. JSON 구조화·분산추적은 규모상 의식적 제외. 설계·근거: [`observability-correlation-id.md`](../decisions/observability-correlation-id.md).
- [ ] **모니터링/N+1 점검** — 관측성과 함께 🔴로 묶여 있던 항목 중 남은 부분. N+1 점검은 미착수.
- [ ] **테스트 커버리지** — 테스트 파일 5개뿐.
- [ ] **외부 통합 다양성 부족** — OAuth2·S3·결제·푸시·검색 등 0개. [`25-portfolio-strategy.md`](./25-portfolio-strategy.md).
- [ ] **AI→Spring 콜백 방향 장애 보호 없음** — 서킷브레이커/재시도가 Spring→AI 방향에만 있음, 반대 방향은 fire-and-forget(의도적 스코프 제외, `production-signal-checklist.md` §2-3-4-2).

## 4. 남겨둔 검증 작업(코드 아님, 실험)

- [ ] **소량 DELETE 반복 시 파편화 실험** — 미실험. 메모리: `project_pending_delete_fragmentation_experiment`.
- [ ] **풀 사이징 AWS(RDS) 재검증** — 로컬 2코어 동거 환경 종속 결론, 비용 문제로 미착수·미결정. 메모리: `project_pending_aws_pool_sizing_reverify`.

## 5. 2학기 계획 범위(지금 범위 밖)

- [ ] **다른 운동 종목 확장**(런지·플랭크 등) — 지금은 스쿼트만. `project_squat_first` 방침.
- [ ] **베타 테스트/실사용자 피드백** — `24-semester2-plan.md` 참고.

---

## 우선순위 제안

인터뷰 준비 관점에서는 **1번(DB 핵심 미구현)**이 가장 중요했음 — 2026-07-24로 **4개 전부 완료**(report 멱등성·precompute-on-write·TTL 스케줄러·개별 세션 삭제). 2~3번은 "왜 아직 안 했는지"를 정직하게 설명할 수 있으면 충분. 4~5번은 지금 당장 급하지 않음.
