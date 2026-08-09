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

- [x] **소량 DELETE 반복 시 파편화 실험** — **완료(2026-08-09)**, [`loadtest/results/delete-fragmentation-2026-08-09/`](../../loadtest/results/delete-fragmentation-2026-08-09/README.md).
  `outbox_events` 행 모양으로 200,000행 고정 · 25,000행/사이클. **FIFO 삭제는 누적 없음**(3회전 내내 1,348페이지,
  재구축본보다 11% 조밀). **구멍 뚫기 삭제는 계단 한 번**(1,348 → 1,668, +24%) 후 평탄이고,
  **그 계단은 구멍 밀도와 무관**하다(FAILED 비율 1%→20%, 20배 올려도 동일).
  - 🔴 **이 항목이 가리키던 대상 서술이 틀려 있었다.** 문서 4곳이 `pose_data` 로 적었는데 근거는 *"세션 종료 시
    다운샘플로 750행 삭제"* 였다. **다운샘플은 저장 «전» 에 걸린다**(`PoseDataService.java:78`) — 줄어든 행은
    INSERT 되지도 삭제되지도 않는다. 다만 **~750행 규모의 소량 DELETE 자체는 실재**한다 —
    **사용자의 세션 삭제**(`SessionService.java:392,404`)와 탈퇴(`PoseDataCleanupService.java:35`)다.
  - ⚠️ **남은 미검증**: `pose_data` 는 행 모양(`joint_coordinates` JSON)이 훨씬 커 페이지당 행 수가 다르고,
    삭제가 살아있는 파티션 안에서 일어나 **파티션 DROP 과의 상호작용**이 있다. 둘 다 재지 않았다.
- [x] **풀 사이징 AWS 재검증** — **완료(2026-07-25)**. EC2 임시 인스턴스 2대(DB 전용 + 백엔드/ghz)로 분리 배포 실측 → **로컬 결론이 반전**: 고부하(c≥50)에서 풀=30이 풀=10 대비 확실히 우세하고 c=100은 풀=10이 붕괴(47% 타임아웃). "풀 무용"은 로컬 2코어 동거 환경 종속 결론이었음. pool=15·20 추가 측정으로 cliff를 10~15 사이로 좁혀 **실측 스위트스폿 ~15**(이론 공식 ≈5의 3배). 인프라는 측정 후 삭제. 남은 것은 pool=11~14 정밀 cliff 위치뿐. [`pose-ingest-downsampling.md §5-1(7)(8)`](../decisions/pose-ingest-downsampling.md).
  - 🔴 **후속 (2026-08-08) — 위 결론의 전제가 우리 코드 변경으로 무효화됐다.** EC2 **3대**(obs 분리)로 c 10~100 × pool 5·20 격자를 재측정하니 **초당 ~205건 수준에서는 절벽이 없다**(풀 4배 차이에도 ~205 RPS 고정, 실패 0). **풀이 병목이 아닌 것은 확실하다** — `pool=20` 은 20개 중 2~3개만 쓰고(대기 0) `pool=5` 는 포화인데(95 대기) RPS 가 같다. 그리고 `DOWNSAMPLE_WINDOW` 를 1 로 되돌린 대조군에서 RPS 1.7배·p99 4.9배 차이를 확인했으니, **위 47% 붕괴는 «배치당 25행을 쓰던 코드»의 현상**이다.
  - ⚠️ **정정 (같은 날 리뷰)**: 초판은 원인을 *"다운샘플이 병목을 백엔드 CPU 로 옮긴 것(백엔드 p90 128% vs MySQL 53%)"* 으로 적었으나 **두 수치 다 커밋된 원본에 없어**(MySQL 지표 미수집, 백엔드 CPU 는 0.51~0.56) **철회**했다. 그 자리에 1순위 용의자로 «부하기 커넥션 1개» 를 적었는데 **그것도 기각됐다 — 답은 아래.**
  > ✅ **후속 — 천장의 정체가 밝혀졌고, 그 «1순위 용의자» 는 기각됐다** (2026-08-08 저녁, [`../../loadtest/results/ceiling-fsync-2026-08-08/`](../../loadtest/results/ceiling-fsync-2026-08-08/)).
  >
  > **답은 커밋 `fsync` 였다.** c=100 · pool=20 · `-n 12000` 고정, MySQL 내구성 설정만 바꿨다:
  >
  > | `innodb_flush_log_at_trx_commit` / `sync_binlog` | RPS | p50 |
  > |---|---|---|
  > | `1` / `1` (기본값, 최대 내구성) | **231.6** | 429ms |
  > | `1` / `0` | 411.5 | 240ms |
  > | `2` / `0` | **803.1** | 121ms |
  >
  > 커밋마다 fsync 를 두 번(redo 플러시 + binlog 동기화) 돌고 있었고, EBS 가 네트워크 연결 디스크라 그 왕복이 처리량을 결정했다. **3.47배.**
  >
  > 🔴 **그리고 위에 적은 «1순위 용의자» 는 측정으로 기각됐다** — `--connections` 만 흔들었더니 `1`→**230.2**, `4`→**208.0**, `16`→**210.9** 로 **안 움직인다**(16배로 늘려도 그대로거나 약간 낮다). 세 박스 CPU 도 전부 유휴였다(부하기 13~20% · 백엔드 27~37% · MySQL 50~59%) — **전부 놀면서 처리량이 고정이면 병목은 «계산» 이 아니라 «대기»** 이고, 그래서 커밋 경로를 의심한 것이 맞았다.
  >
  > 📌 **이것이 오전 관측을 전부 설명한다.** 풀 5 vs 20 이 같았던 것은 직렬화 지점이 커넥션이 아니라 **로그 플러시**였기 때문이고, `pool=20` 에서 2~3개만 쓰인 것은 커넥션당 DB 작업이 짧고 시간이 **커밋 대기**에 있었기 때문이다. 다운샘플이 절벽을 지운 이유도 같다 — R=1(25행)일 때는 커밋당 행 작업이 커서 풀이 레버였는데, R=5 로 줄자 **행 작업이 fsync 비용 아래로 내려갔다. 커밋 «횟수» 는 그대로다.**
  >
  > ⚠️ **채택하지 않았다.** 3.47배는 **데이터 안전을 판 대가**이고 DAU 1,000 가정에서 231 RPS 는 한참 위다. 안 아픈 것을 고치며 안전을 깎는 셈이라 «천장의 위치를 안다» 로 닫았다. ~~내구성을 안 깎는 유일한 레버(여러 rep 을 한 트랜잭션으로 묶어 **커밋 횟수**를 줄이기)는 미측정이다.~~
  >
  > 🔴 **2026-08-09 정정 두 건** ([4차](../../loadtest/results/commit-count-2026-08-09/), [#166](https://github.com/Shadowfit/init/issues/166))
  > 1. **3.47배는 «단일 핫세션» 조건의 값이다.** 페이로드만 다세션으로 바꾸면 **1.03배**로 사라진다
  > 2. **«커밋 횟수 줄이기» 는 측정됐고, 기대와 반대였다** — 처리량 **−27~32%**, p99 **50배** 악화. 커밋을 줄이면 그룹 커밋에 묶일 상대도 줄어 `fsync/커밋` 이 0.15 → 0.71 로 오른다. **«유일한 레버» 가 아니라 «음의 레버» 다**
  >
  > ⚠️ `conn` 스윕 3판은 `fail=100` 이 찍혀 있다 — 지속시간 모드(`-z`) 종료 시 in-flight 가 끊기는 **알려진 아티팩트**(`load-test-strategy.md` §7.7)이고 판정에는 영향이 없다.
  >
  > 🔴 **그래서 «절벽이 없다» 의 뜻이 좁아진다** — 정확히는 **«fsync 가 천장이던 구간에서는»** 이다. 내구성을 완화해 803 RPS 를 내는 상태에서 풀 스윕을 다시 하면 **절벽이 다시 나타날 수 있다**(미측정). fsync 가 천장인 동안은 풀이 가려져 있었다.
    → 위 줄의 *"남은 것은 pool=11~14 정밀 위치뿐"* 은 **해소가 아니라 소멸**이다(좁힐 절벽이 없다). 새로 열린 미결은 `maximum-pool-size` 값을 어떻게 할지 하나다. [`pool-cliff-vs-concurrency.md`](../decisions/pool-cliff-vs-concurrency.md) §8 · [`pose-ingest-downsampling.md §5-1(9)`](../decisions/pose-ingest-downsampling.md) · 원본 [`loadtest/results/pool-cliff-2026-08-08/`](../../loadtest/results/pool-cliff-2026-08-08/)
    📌 **남는 교훈**: PR #53 이 다운샘플과 풀 사이징을 **같이** 넣었는데, 앞의 것이 뒤의 것의 근거를 지웠다. 그걸 3개월 뒤 재측정에서야 알았다.

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
| 소량 DELETE 파편화 실험 | §4 | ✅ 완료(2026-08-09) — FIFO 는 누적 없음, 구멍 뚫기는 계단 1회(+24%) 후 평탄. `pose_data` 행 모양·파티션 상호작용은 미검증 |
| 다른 운동 종목 확장 / 베타 테스트 | §5 | 2학기 범위 |

이와 별개로 **보강 3축 중 남은 착수 순서(outbox vs 회복탄력성)**가 최상위 미결정 — [`portfolio-narrative.md §7`](../portfolio/portfolio-narrative.md).
