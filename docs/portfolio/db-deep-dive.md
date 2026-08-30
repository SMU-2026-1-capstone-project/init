# 포폴: DB 깊이 — 시계열·정합성·격리수준

작성: 2026-06-02
대상: 백엔드(Spring) 신입 포폴. 이 프로젝트(ShadowFit)의 DB 영역에서 **교체 불가능한 깊이**를 어디서 어떻게 뽑는지 정리.
연관: [`../tasks/25-portfolio-strategy.md`](../tasks/25-portfolio-strategy.md), [`../decisions/load-test-strategy.md`](../decisions/load-test-strategy.md), [`./problem-solving-log.md`](./problem-solving-log.md)

> 살아있는 문서. ✅=코드/측정으로 확인된 사실, 🔶=후보(개발하면 스토리화), ⬜=계획. **결정 마크는 사용자 confirm 후에만.**

---

## 0. 포지셔닝 (클로드 시대 기준)

### 0.1 두 번 평준화된다 — 포화 + AI

1. **표면적 DB 최적화는 이미 포화** — N+1·인덱스·단순 Redis 캐싱을 부트캠프·독학이 쇼핑몰/게시판 클론 위에 똑같이 찍어낸다. 교체 가능한 부품.
2. **클로드가 코드·아키텍처를 평준화** — "복잡한 gRPC 시스템·파티셔닝·락 만들었다"는 자랑은 클로드가 누구한테나 만들어주는 순간 **차별점이 아니다**([`25-doc §36`](../tasks/25-portfolio-strategy.md)). 면접관은 모두가 AI 쓴다고 전제한다.

→ **"무엇을 만들었나"로는 못 이긴다.**

### 0.2 그래서 차별점 = AI가 못 만드는 것

| 진짜 차별점 (희소) | 보조 (천장 있음) |
|---|---|
| **① 측정·판단** — 가설→측정→발견→"안 하기로 한 결정". AI 못 하는 인간 20% | 운영 인식(HikariCP·파티셔닝·마이그레이션) — **시뮬레이션 천장**(실트래픽 없음). 헤드라인❌, "운영을 의식한다" 증거로만 |
| **② 실신호** — 베타 사용자 실버그·실피드백. AI 절대 못 만듦 ([`24-plan`](../tasks/24-semester2-plan.md) 베타 테스트) | 아키텍처 복잡도 — 평준화됨. 조연 |
| **③ 내 AI 코드 100% 이해·라이브 방어** — 옆 클로드 유저와 나를 가르는 유일한 것 | |

### 0.3 이 프로젝트의 교체 불가능 지점 (reframed)

1. **측정·판단 깊이** — "인덱스 넣었어요"❌ → *"측정해보니 인덱스 이미 최적, 진짜 병목은 payload"*(§4.3 발견). 카고컬트 반대 시그널. ⭐ AI 시대 핵심.
2. **이벤트 적재 정합성** (gRPC×DB) — 사용자 "저장" 클릭(CRUD)이 아니라 별도 서비스가 at-least-once로 미는 배치를 정합성 있게 받음(낙관락·멱등성). **단순 CRUD가 절대 안 만나는 문제**, 실제 구현됨.
3. **실신호** — 베타로 확보할 authentic 트러블 1~2개.

### 0.4 경쟁 포지션 (정직하게 — 어디서 이기고 지나)

| 상대 | 결과 |
|---|---|
| 단순 CRUD 포폴 | **이김** — 이벤트 적재 정합성 + 시계열 (CRUD엔 측정·결정할 게 적음) |
| 실운영 MAU1000 (단순) | **실신호는 짐**(AI 못 만듦), **측정·판단 깊이는 경쟁**. 아키텍처는 무승부(둘 다 AI) |
| 잘 만든 MSA | **breadth·키워드는 짐** — SQS 도입으로 키워드 갭 일부 보완(동기 콜백 디커플링, 진짜 개선) |
| 카고컬트 MSA | **이김** — "왜 MSA 안 했나" 판단 + 실제 분산 문제 해결 |

### 0.5 정직한 천장

- ❌ "1~2년차/운영 경력 위장" (실트래픽·실장애 못 만듦)
- ✅ **"CRUD만 있는 median 신입보다 한 단계 위"** — 분산·이벤트·정합성을 *의식하고 구현·측정해본* 신입
- → **헤드라인 = "측정·판단 + 이벤트 적재 정합성 + 베타 실신호".** 운영·아키텍처 복잡도는 조연.

---

## 1. 강점 축 vs 안 파는 축 (정직하게)

| 🟢 깊게 파도 됨 (희소) | 🔴 억지로 팔면 털림 |
|---|---|
| 대용량 시계열 (연 ~8억 행/~800GB — ⚠️ 조건은 아래 박스) | 고 throughput/QPS (DAU 1,000은 작음, §4.2) |
| JSON 컬럼 운영 (projection·하이드레이션) | 샤딩 (규모상 불필요) |
| 파티셔닝 + TTL/DROP PARTITION | read replica (실수요 없음 → "확장 대비 설계"로만) |
| gRPC 결합 정합성 (락·멱등성) | 무거운 락 경합·데드락 (append-only라 자연 hotspot 없음 → 합성) |
| 측정 기반 읽기 최적화 (projection·캐싱) | 인덱스 튜닝 단독 (리포트 쿼리 이미 최적, §4.3) |

> "안 한 것/규모상 불필요한 것을 정직하게 구분"하는 게 없는 QPS 자랑보다 강함 — median 신입보다 위인 판단 시그널(§0.5).

> 🔴 **「연 ~8억 행/~800GB」의 조건 — 우리가 스스로 무효화했다** (2026-08-12 확인)
>
> 이 값의 산식은 [`load-test-strategy §4.3`](../decisions/load-test-strategy.md) 이 **「R≈25 추정(§4.5)」** 으로 명시한다.
> 그런데 **R≈5 다운샘플이 PR #53(2026-07-25)로 코드에 들어가 있다** — 프레임을 저장 «전» 에 5분의 1로
> 줄이므로([`pose-ingest-downsampling.md`](../decisions/pose-ingest-downsampling.md)), **지금 적재율은 이 추정의 약 1/5** 이다.
>
> | 추정 | 산식 | 나오는 곳 |
> |---|---|---|
> | 연 ~8억 행 / ~800GB | R≈25, ~1,500행/세션 | `load-test-strategy §4.3` · 이 문서 · `realmysql §1` |
> | 연 ~2.7억 행 | 365,000세션 × 750행 | [`session-index-composition.md:233`](../decisions/session-index-composition.md) |
>
> **둘을 맞춰본 적이 없고, 다운샘플 이후로 재산정한 적도 없다.** 새 값을 여기서 임의로 만들지 않는다 —
> 아래 §0·§2-D 의 「~800GB 오염」 서술은 **이 조건에서 읽어야 한다.** 다만 **결론(raw 를 영속시키지 말 것)은
> 값의 크기가 아니라 «무한 누적» 이라는 성질에서 나오므로, 1/5 이 되어도 방향은 그대로다.**

---

## 2. 항목별 깊이 트랙

### ⭐ 0. 데이터 아키텍처 (현업 reframe) — 진짜 헤드라인

> 아래 A~E(projection·trim·파티셔닝 등)는 **이 판단 아래의 국소 최적화**. 현업 기준 핵심은 "프레임 raw 시계열이 OLTP에 영구 누적되는 구조 자체"다.

**현업 평결**: 프레임 단위 raw 좌표 JSON을 OLTP MySQL `pose_data`에 1행/프레임으로 영구 적재 = **안티패턴** (시계열을 row-oriented OLTP에 opaque JSON으로, 연 ~800GB가 주 DB 오염). 학생 프로젝트라서 봐주는 게 아니라 현업이면 리뷰에서 막힘.

**재설계 — raw는 휘발성, derived가 영속** (접근 패턴 정반대라 분리):

| 테이블 | 역할 | 쓰기/읽기 | 수명 |
|---|---|---|---|
| **`pose_data`** (적재 버퍼) | raw 프레임 | 🔴쓰기 많음 / 🟢읽기 거의 없음(precompute 1회) | **단기 TTL** |
| **`reports`** (서빙, 기존 테이블) | derived(worst 구간·sync 추세) | 🟢세션당 1회(precompute) / 🔴조회마다 읽음 | **영구·작음** |

- **연결 = precompute-on-write**: 세션 종료 시 worst 구간 계산 → `reports`에 저장. 조회는 `reports` 단일 행만(pose_data 스캔 0).
- **⚠️ 이건 CQRS 아님** — 단일 DB·동기 갱신. 정확히는 **precompute된 materialized read model / denormalization**. "CQRS 했다"는 과claim(이벤트소싱·별도 읽기DB 없음 → 면접서 무너짐).
- **before/after**: MySQL 영속 풋프린트 ~800GB raw → **derived 수십 MB**, 리포트 조회 스캔→O(1).
- **결과적으로 "33→13 트림" 문제가 녹는다**: raw가 단기면 그 JSON이 33이든 13이든 영구 비용 아님 → 트림은 부차.

**right-sizing (과설계 금지)**:
- **샤딩 ❌** — 단일 MySQL로 충분(DAU 작음). 스케일 시에도 샤딩 전에 raw를 S3로 티어링이 먼저.
- **파티셔닝 🟡** — 쿼리 pruning 아니라 **buffer의 값싼 TTL(DROP PARTITION) 도구로만**. 볼륨 커지면.
- **S3/TSDB 데이터레이크 🔵** — "ML·재분석 needs 생기면" 트리거. 지금 X.

→ 면접: *"적재(write-heavy)와 조회(read-heavy)가 정반대라 buffer/serving을 역할·수명으로 분리, precompute로 연결. raw는 OLTP에 안 남김. 샤딩은 규모상 불필요, 파티션은 TTL 용도."*

### A. 쓰기 최적화 — batch insert ✅ 완료

- **문제**: `PoseDataService.savePoseDataBatch`의 JPA `saveAll`이 `@GeneratedValue(IDENTITY)` 때문에 Hibernate batch가 원천 차단 → 개별 INSERT N방.
- **해결**: `JdbcTemplate.batchUpdate` multi-row INSERT로 전환 (`FeedbackLogService` 패턴). IDENTITY 우회, INSERT 25방→1방.
- **결과** (공정 측정, 워밍업 통제, [`load-test-strategy §7.6`](../decisions/load-test-strategy.md)):
  - throughput **23.5 → 46.7 RPS (+99%)**, p50 −64%, p99 7,549→4,784ms (**−37%**) — [조건](../decisions/load-test-strategy.md#batch-insert-99): 2026-05-31 로컬, warmup 60초 폐기 후 ramp 5→100. **절대 RPS 는 인용하지 않는다**
- 면접: "왜 config(`hibernate.jdbc.batch_size`)로 안 풀고 JdbcTemplate? → IDENTITY라 Hibernate batch 미발동, 드라이버 레벨 batch가 정석."
- **면접(꼬리질문 대비)**: "왜 ID 전략을 SEQUENCE로 안 바꿨나? → SEQUENCE는 INSERT 전에 미리 ID를 확보할 수 있어 이론상 Hibernate batch가 가능하지만, MySQL엔 네이티브 SEQUENCE가 없어 별도 ID발급 테이블로 흉내내야 함(추가 오버헤드) + 엔티티 전체의 PK 생성 전략을 바꾸는 더 큰 변경. `JdbcTemplate.batchUpdate`는 이 저장 경로 하나만 국소적으로 우회해 같은 효과를 더 작은 변경으로 냄."

### B. 읽기 최적화 — projection / 캐싱 / precompute ✅ projection·precompute 완료 / 캐싱만 미착수

목표 = `GET /reports/sessions/{id}` 응답시간 before/after.

1. **projection** ✅ 측정 — `ReportService`가 엔티티 전체 로드 → worst 구간 계산은 당시 `syncRate`·`feedbackMessage`·`timestampSec` 3개만 쓰는데 `joint_coordinates`(JSON 2.3KB)까지 끌어옴. → projection DTO(**측정 시점 3컬럼, 현재 4컬럼** — `feedbackMessage` 제거·`repNumber`·`smoothedKneeAngle` 추가. `joint_coordinates` 를 안 싣는 방침은 유지). **실측(2026-06-02, warm, 750행/세션, 로컬 412만 행): payload 1,716.8KB→22.4KB (−98.7%), warm 쿼리 12.1ms→1.5ms (8x)**. **AWS 1억 행 재검증(2026-07-15): payload 1,740.1KB→22.6KB (−98.7%, 동일), warm 쿼리 40.6ms→1.4ms (29~41x)** — 버퍼풀(2GB) 대비 테이블이 25배(~230GB) 커지며 작업셋 비율이 나빠져 배수가 오히려 커졌다(결론 강화). 같은 인덱스 — 차이는 JSON이 InnoDB **off-page 저장**이라 SELECT 시 overflow 페이지 random I/O, projection이 회피([`realmysql-experiments ②` · 인용 조건](./realmysql-experiments.md#projection-98-7)). ⚠️ 이 쿼리는 precompute가 세션당 1회 도는 비동기 잡이라 사용자 체감 지연이 아니라 **잡의 자원 소모 절감**으로 읽어야 한다. 🔴 **정상 리포트 조회는 이 쿼리를 아예 안 탄다** — precompute 결과(JSON)를 읽고 끝난다. 도는 자리는 precompute 잡과 그 컬럼이 비었을 때의 폴백 둘뿐이다(코드 판독, 미측정).
2. **Redis 캐싱** ⬜ — 세션 종료 후 리포트 불변 → cache-aside, 높은 적중률. stampede 방지.
3. **precompute-on-write** ✅ **완료(2026-07-24)** — worst 구간을 세션 종료 시 1회 계산(`WorstSectionCalculator`)해 `reports.detailed_analysis`(JSON)에 저장, `SessionService.applyComplete`와 같은 트랜잭션. `ReportService.getSessionReport`는 이제 GET 때 `pose_data` 재스캔 없이 이 값을 읽기만 함(precompute 이전 리포트만 하위호환 fallback). 세부 설계 4가지(계산 위치·트랜잭션 경계·실패정책·백필)는 [`report-read-path.md §9`](../decisions/report-read-path.md).

> ⚠️ 구 "인덱스 추가 → 850ms→12ms" 가설은 **폐기**. [`V1__baseline.sql`](../../backend/src/main/resources/db/migration/V1__baseline.sql) 의 `idx_session_timestamp(session_id, timestamp_sec)`이 이미 쿼리에 최적이고 세션 단위라 테이블 성장에 무관(§4.3). 헤드라인은 인덱스가 아니라 **payload 축소**.

### C. 동시성 / 정합성 — gRPC × DB 교집합 ⭐

| 항목 | 상태 | 근거 |
|---|---|---|
| **낙관적 락**: 타임아웃 스케줄러 vs FastAPI 완료 콜백 경합 | ✅ | `Session.java:66 @Version`, `SessionTimeoutScheduler.java:84` 충돌 시 양보 |
| **멱등성**: at-least-once gRPC 콜백 재전송 | ✅ | `FeedbackLogService.java:33` `INSERT IGNORE` + `uk_session_event` |
| **일일 집계 lost-update** | ✅ 해결(2026-07-15) | `SessionService.applyComplete`에서 세션 완료 시 `DailyLogService.accumulateStats` 호출로 배선. `DailyLogRepository.upsertStats`(네이티브 `INSERT ... ON DUPLICATE KEY UPDATE` 한 문장)로 같은 날 두 세션 동시 종료돼도 lost-update 없음. **함정 발견**: 처음엔 원자 UPDATE 먼저 시도 후 실패 시(첫 기록) JPA `save()`로 INSERT, 그마저 유니크 위반이면 catch해서 재시도하는 방식으로 짰다가 동시성 테스트에서 실패(`org.hibernate.AssertionFailure: don't flush the Session after an exception occurs`) — `save()` 실패가 Hibernate 세션 자체를 손상시켜 같은 트랜잭션 내 후속 쿼리가 깨짐. 네이티브 upsert 한 문장으로 바꿔 해결 |
| **report 생성 멱등성** | ✅ 완료 | `reports.session_id` UNIQUE(`uk_report_session`, [`V1__baseline.sql`](../../backend/src/main/resources/db/migration/V1__baseline.sql)) + `SessionService.precomputeReport`(같은 날 구현된 precompute-on-write)가 실제 report 생성 경로. `applyComplete`의 기존 멱등성 체크(이미 COMPLETED면 조기 반환)와 UNIQUE 제약이 이중 방어 |

상세 스토리는 [`problem-solving-log.md #3·#4`](./problem-solving-log.md).

### D. 시계열 보존 — pose_data 버퍼의 TTL ✅ 완료(2026-07-24, 자동 만료 스케줄러 포함)

> §0 재설계의 하위 도구. pose_data를 **단기 버퍼**로 만드는 만료 메커니즘.

- **완료(2026-07-24)**: `PoseDataPartitionScheduler`(`@Scheduled`, 매일 새벽 4시) 신설. 이번 달(쓰기 중) + 지난 1개월(버퍼)만 남기고 그 이전 파티션을 `DROP PARTITION` — 아카이빙 없이 완전 폐기(개인정보보호법 제21조 "지체없이 파기" 취지, S3 이전은 파기가 아니라는 판단). `pfuture`가 실데이터를 안 떠안도록 이번 달 기준 +2개월치 파티션을 `REORGANIZE`로 미리 생성. 안전마진: 파티션 이름이 `pYYYY_MM` 패턴과 정확히 일치할 때만 드롭 후보로 인정(정보스키마 조회 + 이름 파싱 이중 확인), `pfuture`는 쿼리 단계에서부터 제외. 세부 설계(보존기간·아카이빙·안전마진·실행주기)는 [`report-read-path.md §9-B`](../decisions/report-read-path.md).
- **precompute-on-write 선행 완료(2026-07-24, §B-3)** — TTL이 안전하게 켜질 수 있었던 전제. 세션 완료 시 worst 구간이 이미 `reports`에 저장되므로, `pose_data` 원본이 드롭돼도 리포트는 영향 없음.
- **pose_data는 중간 산출물(버퍼)** — precompute로 worst 구간을 `reports`에 옮긴 직후 cold → TTL 안전(UX 손실 0).
- **구현 방향은 DELETE 아니라 DROP PARTITION**: 날짜 Range 파티셔닝 → 가장 오래된 파티션을 **O(1) 메타데이터 연산**으로 제거(락 거의 없음). 대량 DELETE는 락·undo 폭발. → **실측 확인**(1억 행 rig, [`realmysql-experiments §②(d)`](./realmysql-experiments.md)): 같은 ~8M행 만료가 **DELETE 18.6분(빈 952MB 파일 잔존) vs DROP PARTITION 1.8초(파일째 회수) ≈ 625x**.
- **파티셔닝의 진짜 가치 = 쿼리 pruning 아니라 "값싼 TTL"** (쿼리는 이미 인덱스로 빠름, §4.3). 이 reframe이 핵심.
- ⚠️ **파티션 전제 = PK에 파티션 키 포함** — pose_data PK `id`만으론 created_at 파티션 불가, PK를 `(id, created_at)`로 변경 완료(위 참고).
- **샤딩은 안 함** — 단일 MySQL로 충분. 스케일 시에도 샤딩 전에 raw를 S3 티어링이 먼저(§0).
- **S3 아카이빙은 안 함 — 완전 폐기로 결정(2026-07-24)**. precompute로 요약이 이미 `reports`에 남으므로 원본을 따로 옮겨 보관할 이유가 없고, S3 이전은 "저장 위치 변경"일 뿐 개인정보보호법상 "파기"가 아니라 오히려 압축·수명주기 관리 부담만 늘어남.
- ⚠️ **FK는 이미 제거됨**: `session→pose_data ON DELETE CASCADE`는 파티셔닝과 호환 안 돼(`ERROR 1506`) 제거됐고, 회원 탈퇴 시 정리는 애플리케이션 레벨 비동기 트리거(B5, `PoseDataCleanupService`)로 대체됨 — 상세 트레이드오프는 [`pose-data-partition-fk-tradeoff.md`](../decisions/pose-data-partition-fk-tradeoff.md).
- **트리거 명시**: DAU 50엔 불필요. 버퍼 보존기간×볼륨이 커져 DELETE 만료가 부담될 때 발동.
- 포폴 가치: 보존정책 + partition-drop은 신입이 거의 안 함 (§4.7).

### E. CS 토픽 → 내 코드 매핑 (MVCC·격리수준)

> 교과서 재현 단독❌ (누구나 함, "공부 노트" 인상). **실제 코드 결정에 앵커할 때만** 강함.

3단 답변(정의 + 본인 코드 위치 + 트레이드오프):

| 토픽 | 정의 | 내 코드 위치 | 트레이드오프 (30초) |
|---|---|---|---|
| **낙관적 vs 비관적 락** | @Version 충돌 감지 vs FOR UPDATE 블로킹 | 타임아웃 vs 콜백 (`SessionTimeoutScheduler`) | 저경합·읽기위주 → 낙관. 비관은 블로킹 비용 |
| **격리수준** | RC / RR / Serializable | 일일 집계 lost-update | RC는 lost-update 못 막음 → 원자 UPDATE·락 필요 |
| **MVCC** | 스냅샷 일관성 읽기 | batch insert ↔ 리포트 조회 | 읽기-쓰기 비블로킹, undo 비용 |

- **정합성/이상현상 추론** = 🟢 진짜 (위 앵커 실재). **throughput 벤치마킹**(MVCC가 락보다 빠르다 류) = 🟡 합성(저경합).
- RealMySQL 적합도: Ch.13 파티셔닝🟢 · Ch.8 인덱스🟢(이미 최적 발견 포함) · Ch.9·11 옵티마이저🟢 · Ch.18 운영🟢 · Ch.4·5 잠금/MVCC🟡 · Ch.16 복제🔴.

### F. 패턴 분석 API — 계정 크기와 무관한 조회 ✅ 완료(2026-08-30)

`GET /patterns/{periodicity,intensity-trend,consistency}` — 요일·시간대 분포, 4주 강도 추세, 연속운동일수. 기존 DB 강점(인덱스 실측·정직한 분포 한계 인지)을 새 기능에 그대로 적용한 사례.

- **설계**: 균등분산 시드로는 이 기능 자체를 검증 못한다는 걸 먼저 인지 — [`pattern-analysis-implementation.md`](../decisions/pattern-analysis-implementation.md) §2가 "패턴 있는" 전용 시드([`gen_pattern_seed.py`](../../loadtest/seed/gen_pattern_seed.py))를 별도 작업으로 분리.
- **EXPLAIN 실측** — `findStartTimesByMemberAndRange`·`findIntensitySamplesByMemberAndRange`는 `status` 등치 조건이 없어 기존 관례(`idx_session_member_status_start`가 등치 없이는 `start_time` seek 못 함, §D 인접 이슈 #541과 같은 함정)대로면 계정 전체 이력을 읽어야 정상인데, `Handler_read_next` 실측(5개 케이스 재현 포함)은 전부 **창 안의 행만 읽었다** — MySQL 8.0이 저카디널리티 컬럼에 등치 없이도 range 분해를 적용. 새 인덱스 불필요로 결론. [`report-query-explain-be07-2026-08-30`](../../loadtest/results/report-query-explain-be07-2026-08-30/README.md).
- **응답시간 실측** — 16세션 계정과 2,000세션 계정(125배 차이)의 응답시간이 1.1~1.2배 차이뿐 — EXPLAIN이 예측한 "계정 크기 무관"이 응답시간에도 그대로 나타남. [`response-time-be07-2026-08-30`](../../loadtest/results/response-time-be07-2026-08-30/README.md).
- **데이터 부족 신호** — 가입 4주 미만 신규 사용자는 `sufficientData:false`로 "아직 데이터가 없다"와 "충분히 썼는데 이번엔 조용하다"를 응답 모양으로 구분(세션7).
- 🔴 **정직한 한계**: EXPLAIN·응답시간 둘 다 로컬 도커 단일 요청 실측([[project_loadtest_env_constraint]]) — 부하(동시 요청) 아래 p99는 범위 밖. 응답시간 판은 n=5로 얇다([[feedback_measure_design_needs_repeats]]) — "배수가 1에 가깝다"는 방향성 확인이지 정밀한 배수 확정이 아니다.
- 면접: "왜 status 조건 없는 쿼리가 문제없다고 판단했나? → 처음엔 기존 findDistinctActiveDates 사례(#541)처럼 계정 전체 스캔을 의심했는데, Handler_read_next로 직접 재보니 아니었다. 가정으로 넘기지 않고 실측으로 뒤집은 사례."

---

## 3. 측정 방법론 원칙 (함정 회피)

수치 개선 사례의 신뢰성을 가르는 것:
- **워밍업 통제 필수** — cold JVM은 초반 인터프리터 모드로 2~5배 느림. before만 warm/after는 cold면 "개선안이 더 느림"으로 오측정 (§7.6 1차 무효 교훈).
- **동일 환경 비교** — 같은 절차(재빌드→cold 기동→warmup 60s→리셋→ramp)끼리만 비교.
- **금지**: 인위적 개악 후 개선, 숫자만 외움, 측정 방법 모름, 다른 시점 머신부하 섞기.

---

## 4. 관련 문서

- [`./problem-solving-log.md`](./problem-solving-log.md) — 문제해결 경험 카드 (3~10월)
- [`../decisions/load-test-strategy.md`](../decisions/load-test-strategy.md) — 부하 테스트 전략·측정값(§7), 용량 산정(§4)
- [`../decisions/redis-introduction.md`](../decisions/redis-introduction.md) — Redis 도입 보류 결정 (안 하기로 한 결정)
- [`../tasks/25-portfolio-strategy.md`](../tasks/25-portfolio-strategy.md) — 진로 전략 회고, 깊이 트랙 후보
- 코드: `backend/.../service/Exercise/PoseDataService.java`(batch insert), `SessionTimeoutScheduler.java`(낙관적 락), `FeedbackLogService.java`(멱등성), `model/exercise/Session.java:66`(@Version)
- [`pattern-analysis-implementation.md`](../decisions/pattern-analysis-implementation.md) — §F(패턴 분석 API) 구현 계획 전체, 9세션 진행 기록
