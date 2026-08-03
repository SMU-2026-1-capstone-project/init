# 구현 갭 정리 (2026-07-20 코드 검증 기준)

작성: 2026-07-20 / **최종 갱신: 2026-07-28**(§2 다운샘플·§3 테스트 커버리지·§4 풀 사이징 항목이 실제 완료를 못 따라가던 드리프트 정정). 오늘 대화 중 실제 코드/문서로 재검증해서 확인한 미구현·미결정 항목 정리. `22-backend-tasks-detail.md`는 방대하지만 일부 항목이 실제 구현 상태를 못 따라가고 있어(예: 세션 피드백 조회 API는 실제로는 구현돼 있는데 그 문서엔 미구현으로 표시) 전체 신뢰는 어려움 — 이 문서는 오늘 직접 코드 확인한 것만 담음.

---

## 1. DB/백엔드 핵심 — 오늘 코드로 확인한 미구현

- [x] **precompute-on-write** — **완료(2026-07-24)**. `WorstSectionCalculator`로 계산 로직 분리, `SessionService.applyComplete`가 세션 완료와 같은 트랜잭션에서 `reports`에 worst 구간 저장(`detailed_analysis` JSON). `ReportService.getSessionReport`는 이제 이 값을 우선 읽고, precompute 이전 리포트(시드 등 `detailed_analysis` 없음)만 하위호환으로 즉석 재계산. 세부 설계 결정 4가지는 [`report-read-path.md §9`](../decisions/report-read-path.md). 설계는 [`db-deep-dive.md §B-3`](../portfolio/db-deep-dive.md) ✅.
- [x] **TTL 자동 만료 스케줄러** — **완료(2026-07-24)**. `PoseDataPartitionScheduler`(매일 새벽 4시) 신설 — 이번 달+지난 1개월 버퍼만 남기고 그 이전 파티션 `DROP PARTITION`(아카이빙 없음, 완전 폐기), `pfuture`가 실데이터를 안 떠안게 이번 달 기준 +2개월치 파티션을 `REORGANIZE`로 미리 생성. precompute-on-write가 선행돼 있어 원본 삭제돼도 리포트 요약은 보존됨. 세부 설계는 [`report-read-path.md §9-B`](../decisions/report-read-path.md).
- [x] **개별 세션 삭제 기능** — **완료(2026-07-24)**. `DELETE /sessions/{sessionId}`(`SessionController`) → `SessionService.deleteSession` — 소유권 확인, `IN_PROGRESS`는 409(`SESSION_DELETE_NOT_ALLOWED`, AI 분석 중일 수 있어 종료 후에만 삭제 가능), `pose_data`는 명시적 동기 삭제(FK 없음), `reports`·`session_feedback_logs`는 FK CASCADE로 자동 정리(§5-1 설계 그대로: 세션 1건 규모라 동기 삭제로 충분, 회원 탈퇴 같은 비동기 불필요). 구현 중 **entity-schema drift 발견·수정**: `Report`/`SessionFeedbackLog`의 `session` 필드가 Hibernate `@OnDelete` 없이 매핑돼 있어 테스트(H2, JPA 엔티티 기반 DDL)엔 실 `schema.sql`의 `ON DELETE CASCADE`가 반영 안 되고 있었음 — `@OnDelete(action=CASCADE)` 추가로 정정. [`pose-data-partition-fk-tradeoff.md §1-1·§5-1`](../decisions/pose-data-partition-fk-tradeoff.md).
- [x] **report 생성 멱등성** — `reports.session_id`에 UNIQUE(`uk_report_session`) 추가 완료(2026-07-24, `mysql/schema.sql`). 같은 날 precompute-on-write가 구현되면서 `SessionService.precomputeReport`가 실제 report 생성 경로가 됐고, `applyComplete`의 기존 멱등성 체크(이미 COMPLETED면 조기 반환)와 이 UNIQUE가 이중 방어를 이룸. [`db-deep-dive.md §C`](../portfolio/db-deep-dive.md).

## 2. 판단 완료, 착수 여부만 미결정 → **둘 다 결착됨(2026-07-28 기준)**

- [x] **다운샘플링(1초 다운샘플)** — **완료(2026-07-25, PR #53)**. 위치는 **B(Spring)**로 확정 — `PoseDataService.savePoseDataBatch`에서 R≈5 대표추출(윈도우별 `sync_rate` 최저 프레임만 저장). 같은 PR에서 HikariCP `maximum-pool-size` 10 → 15. 단 실측 재검증 결과 **다운샘플은 쓰기 천장의 해법이 아니었고**(천장은 풀 사이즈 + 단일세션 rig 아티팩트에 귀속), 저장·배치 효율 부수 카드로 강등된 상태로 착수한 것임. [`pose-ingest-downsampling.md §5-1·§7`](../decisions/pose-ingest-downsampling.md).
- [ ] **Redis 도입** — "MVP 단계 MySQL 부족 미증명"으로 보류 확정(CLOSED). T1~T5 트리거 발생 시 재검토. [`redis-introduction.md`](../decisions/redis-introduction.md).

## 3. 이미 스스로 인지한 약점

- [x] **관측성** — **1차 완료(2026-07-28)**. correlation id 5단계 전파(HTTP·gRPC 진입점 발급/수용, MDC+`logback-spring.xml` 패턴, `@Async` TaskDecorator, Spring↔FastAPI 양방향 metadata, 스케줄러 tick 자체 발급) + 커스텀 메트릭 3종(상태 전이·낙관락 충돌·배치 행수). 기존 `log.` 호출 70여 건은 한 줄도 수정 안 함. JSON 구조화·분산추적은 규모상 의식적 제외. 설계·근거: [`observability-correlation-id.md`](../decisions/observability-correlation-id.md).
- [ ] **모니터링/N+1 점검** — 관측성과 함께 🔴로 묶여 있던 항목 중 남은 부분. N+1 점검은 미착수.
- [x] **테스트 커버리지** — **대폭 확장 완료(2026-07-26, PR #52)**. 5개 → **31개**(서비스 계층 전부 + 컨트롤러/통합 6개 + 보안 2개 + 관측성 4개). 확장 과정에서 **진짜 버그 5건 발견·수정**: `@Async` self-invocation(비동기가 실제로는 동기 실행), `@OnDelete` 누락 2건(entity-schema drift), 그 외 2건. 정직 단서: **커버리지 도구(JaCoCo)를 붙이지 않아 수치(%)는 없다** — "파일 수·계층 커버"까지만 주장할 것.
- [ ] **외부 통합 다양성 부족** — OAuth2·S3·결제·푸시·검색 등 0개. [`25-portfolio-strategy.md`](./25-portfolio-strategy.md). **후보 비교(2026-08-01)**: [`external-integration-candidates.md`](../decisions/external-integration-candidates.md) — S3 1순위 / OAuth2 조건부 / 푸시·결제·검색 제외 추천, **1개만 할 것**. 미확정.
- [ ] **AI→Spring 콜백 방향 장애 보호 없음** — 서킷브레이커/재시도가 Spring→AI 방향에만 있음, 반대 방향은 fire-and-forget(의도적 스코프 제외, `production-signal-checklist.md` §2-3-4-2).

## 4. 남겨둔 검증 작업(코드 아님, 실험)

- [ ] **소량 DELETE 반복 시 파편화 실험** — 미실험. 메모리: `project_pending_delete_fragmentation_experiment`.
- [x] **풀 사이징 AWS 재검증** — **완료(2026-07-25)**. EC2 임시 인스턴스 2대(DB 전용 + 백엔드/ghz)로 분리 배포 실측 → **로컬 결론이 반전**: 고부하(c≥50)에서 풀=30이 풀=10 대비 확실히 우세하고 c=100은 풀=10이 붕괴(47% 타임아웃). "풀 무용"은 로컬 2코어 동거 환경 종속 결론이었음. pool=15·20 추가 측정으로 cliff를 10~15 사이로 좁혀 **실측 스위트스폿 ~15**(이론 공식 ≈5의 3배). 인프라는 측정 후 삭제. 남은 것은 pool=11~14 정밀 cliff 위치뿐. [`pose-ingest-downsampling.md §5-1(7)(8)`](../decisions/pose-ingest-downsampling.md).

## 5. 2학기 계획 범위(지금 범위 밖)

- [ ] **다른 운동 종목 확장**(런지·플랭크 등) — 지금은 스쿼트만. `project_squat_first` 방침.
- [ ] **베타 테스트/실사용자 피드백** — `24-semester2-plan.md` 참고.

---

## 우선순위 제안

인터뷰 준비 관점에서는 **1번(DB 핵심 미구현)**이 가장 중요했음 — 2026-07-24로 **4개 전부 완료**(report 멱등성·precompute-on-write·TTL 스케줄러·개별 세션 삭제). 2~3번은 "왜 아직 안 했는지"를 정직하게 설명할 수 있으면 충분. 4~5번은 지금 당장 급하지 않음.

**2026-07-28 기준 잔여 목록** (위 체크박스에서 아직 `[ ]`인 것만):

| 항목 | 절 | 성격 |
|---|---|---|
| Redis 도입 | §2 | 보류 확정(CLOSED) — 트리거 발생 시 재검토 |
| 모니터링/N+1 점검 | §3 | 관측성 1차가 빠져나가면서 남은 조각. N+1은 미착수 |
| 외부 통합 다양성(OAuth2·S3·푸시 등) | §3 | 착수 여부 미결정 — 후보 비교 완료([`external-integration-candidates.md`](../decisions/external-integration-candidates.md)), 선택은 미확정 |
| AI→Spring 콜백 방향 장애 보호 | §3 | 의도적 스코프 제외 |
| 소량 DELETE 파편화 실험 | §4 | 미실험 — 검증 전엔 사실처럼 쓰지 말 것 |
| 다른 운동 종목 확장 / 베타 테스트 | §5 | 2학기 범위 |

이와 별개로 **보강 3축 중 남은 착수 순서(outbox vs 회복탄력성)**가 최상위 미결정 — [`portfolio-narrative.md §7`](../portfolio/portfolio-narrative.md).
