# 포폴: 문제해결 경험 로그 (3~10월)

작성: 2026-06-02 · 갱신: 2026-08-07 (#10·#11 추가, §3 다섯 개 구현 확인)
대상: 백엔드(Spring) 신입 포폴. 프로젝트 기간(2026-03~10) 동안 **본인이 실제로 해결한 문제**를 problem→root cause→solution→result→면접답변 형식으로 박제.
연관: [`./db-deep-dive.md`](./db-deep-dive.md), [`../tasks/25-portfolio-strategy.md`](../tasks/25-portfolio-strategy.md), [`../decisions/load-test-strategy.md`](../decisions/load-test-strategy.md)

> ✅=완료·검증, 🔶=개발하면 스토리화, ⬜=계획. 강도: 🔴 헤드라인 / 🟠 트러블슈팅 / 🟡 소형.

---

## 0. 정직성 전제 (먼저 박을 것)

- **backend/ 커밋 55/62가 본인(Khyojae)** → Spring 백엔드 문제해결은 떳떳하게 본인 것.
- ⚠️ **ai-server(FastAPI) 측 커밋은 본인/팀원 경계 모호** → 포폴에 넣기 전 `git blame`으로 본인 작업분만 분리. (팀원: jiho/demetergod, hojin — AI·프론트 위주)
- 면접은 "본인이 실제 한 작업"만. AI-server 트러블을 본인 것처럼 말하면 라이브 질문에서 무너짐.

---

## 1. 타임라인

| 기간 | 단계 | 성격 |
|---|---|---|
| **3~5월** | 1학기 MVP (✅) | 기능 구축 + 문제해결 일부 (#1~#9) |
| **6~8월** | 방학 (지금~) | **수치 문제해결 본편** (읽기 최적화·동시성 개발) |
| **9~10월** | 2학기 시작 | 운영(SLO·Resilience4j)·발표 |

> 솔직한 현실: "기능 구축"은 거의 끝났고, **"수치로 증명하는 문제해결"은 대부분 6~10월에 있음.** 지금이 그걸 만드는 적기.

---

## 2. 문제해결 카드 (완료, 본인 backend)

### #1 🔴 batch insert N방 → JdbcTemplate (throughput +99%)
- **문제**: pose_data 적재가 동시성 부하에서 느림(p99 수 초).
- **Root cause**: `PoseDataService.savePoseDataBatch`의 JPA `saveAll`이 `@GeneratedValue(IDENTITY)` 때문에 Hibernate batch 원천 차단 → 개별 INSERT N방.
- **Solution**: `JdbcTemplate.batchUpdate` multi-row INSERT (IDENTITY 우회, 25방→1방).
- **Result**: throughput **23.5→46.7 RPS(+99%)**, p50 −64%, p99 **−37%** (공정 측정, [`load-test §7.6`](../decisions/load-test-strategy.md)).
- **면접**: "config `batch_size`로 왜 안 풀었나? → IDENTITY라 Hibernate batch 미발동, 드라이버 레벨 batch가 정석."

### #2 🔴 타임아웃 스케줄러 vs FastAPI 콜백 경합 → 낙관적 락
- **문제**: 백그라운드 타임아웃 스케줄러(IN_PROGRESS→FAILED, 1분마다)와 FastAPI 완료 콜백이 **같은 세션 row 동시 갱신**. 타임아웃 직전 결과 도착 시 경합 → 완료된 세션이 잘못 FAILED 될 수 있음.
- **Solution**: `Session.java:66 @Version` 낙관적 락. 충돌 시 `ObjectOptimisticLockingFailureException`을 잡아 **스케줄러가 양보**(결과 데이터 우선, `SessionTimeoutScheduler.java:84`). yield 건수 로깅.
- **Result**: 정합성 깨짐 방지 + 관측 가능.
- **면접**: "왜 비관적 락(FOR UPDATE)·SERIALIZABLE이 아니라 낙관적 락? → 경합 빈도 낮고 읽기 위주라 블로킹 비용이 아까움. 누가 이기는 게 옳은지(충돌 해소 정책)까지 설계." ⭐ gRPC×DB 교집합.

### #3 🔴 at-least-once gRPC 콜백 → 멱등성 (INSERT IGNORE)
- **문제**: AI가 BT-SET 피드백을 batch로 송신, 네트워크 재시도 시 **중복 전송** 가능.
- **Solution**: `(session_id, occurred_at, feedback_type)` 유니크키 + `FeedbackLogService.java:33` **`INSERT IGNORE`**로 중복 흡수. inserted/skipped 카운트 반환.
- **Result**: 재전송돼도 중복 row 0.
- **면접**: "exactly-once가 아니라 at-least-once 전제에서 멱등성으로 푼다 — 분산 시스템 정석. DB 유니크 제약에 위임."

### #4 🟠 gRPC long 정밀도 손실 버그
- **문제**: `StopAnalysis` 세션 ID(long)가 gRPC/JSON 경계에서 정밀도 손실 + 응답 DTO 정수 타입 불일치.
- **Solution**: 타입 일관성 정리 + DTO 정수 타입 통일 (commit 2026-05-17).
- **Result**: 세션 ID 정합성 버그 해결.
- **면접**: "JSON number의 정밀도 한계(53bit) ↔ Java long(64bit) 경계 문제. 직렬화 계약을 의식." (※ 정확한 수정 라인은 코드 재확인 권장.)

### #5 🔴 Redis 도입 보류 — "안 하기로 한 결정"
- **상황**: 캐싱으로 Redis 도입 압박.
- **판단**: "MySQL이 부족하다는 게 **엄격하게 미증명**"이라 측정 없이 도입 거부 ([`redis-introduction.md`](../decisions/redis-introduction.md)).
- **면접**: "도입하면 인프라 복잡도·일관성 비용. 병목을 측정으로 입증한 뒤 도입하는 게 맞다." ⭐ 카고컬트 Redis 스토리의 정반대 = 시니어 시그널.

### #6 🟠 부하 측정 방법론 함정 (cold JVM)
- **문제**: 1차 측정에서 "batch 개선안이 오히려 느림"으로 잘못 나옴.
- **Root cause**: before만 warm(58분), 개선안은 cold(기동 직후). cold JVM 인터프리터 모드가 ramp 저동시성 구간 오염 → 측정한 게 "batch 효과"가 아니라 "워밍업 차이".
- **Solution**: 공정 절차 확립(재빌드→cold 기동→**warmup 60s 폐기**→리셋→ramp). 같은 절차끼리만 비교.
- **면접**: "JVM 서비스 부하 측정은 워밍업 통제가 필수." ([`load-test §7.6`](../decisions/load-test-strategy.md))

### #7 🟠 측정 종료 에러 100건 원인 규명
- **문제**: 모든 ramp에 `Unavailable` 100건 고정 재현.
- **Root cause**: `details` timestamp 분석 → 에러가 **측정 종료 직전 ~1~2ms에 전부** 몰림(그 전 210초간 0건). 정확히 `--concurrency-end=100`과 일치 = ghz `-z` 종료 시 잘린 in-flight 요청. **서버 결함 아님**(max-connections/GC 등 추정 기각).
- **면접**: "에러 숫자만 보고 서버 한계로 단정 안 함. 데이터로 측정 아티팩트임을 증명." ([`load-test §7.7`](../decisions/load-test-strategy.md))

### #8 🟡 data.sql 연동 실패
- data.sql 정보가 연동 안 되던 문제 디버깅→해결 (commit 2026-04-25~26). 소형 트러블슈팅.

### #9 🟡 LocalDateTime 직렬화
- `write-dates-as-timestamps=false`로 LocalDateTime ISO string 직렬화 정정 (commit 2026-05-31). 직렬화 계약 일관성. 소형.

### #10 🔴 측정 데이터가 재려던 성질을 갖고 있지 않았다 (2026-08-06~07)

**#6 과 같은 계열이되 한 층 아래다.** #6 은 *절차*(워밍업)가 틀렸고, 여기는 *데이터*가 틀렸다. 절차를 아무리 정확히 해도 안 잡힌다.

- **문제**: 관리자 세션 목록의 필터 조합별 실행 계획을 쟀는데, **"핵심"이라고 지목해둔 조합(상태+검색어)의 결과가 0건**이었다. 계획(`type`·`key`·`Extra`)은 멀쩡해 보였고 스크립트는 매번 성공했다.
- **Root cause**: 시딩 SQL 두 줄이 같은 변수의 함수였다.
  ```text
  member_id = 1 + (n % 200000)
  status    = ELT(1 + (n % 4), ...)     ← 200000 % 4 == 0
  ```
  `n mod 4` 가 `member_id` 로 **완전히 결정**되어 **회원 20만 중 19만 9,920명(99.96%)이 평생 한 가지 상태의 세션만** 가졌다. 검색어에 걸리는 회원 2,000명이 전부 COMPLETED 라 `FAILED × kim` 이 **구조적으로 0건**.
- **Solution — 한 번에 안 고쳐졌다.** `CRC32` 로 바꿨는데 그것도 틀렸다. CRC32 는 GF(2) 위에서 선형이라 등차 입력의 구조가 하위 비트에 남는다:

  | 오프셋 | CRC32 | MD5 | 무작위 기대 |
  |---|--:|--:|--:|
  | n vs n+1 | 0.0044 | 0.2508 | 0.25 |
  | **n vs n+200,000** | 🔴 **0.0000** | 0.2524 | 0.25 |

  `n vs n+200,000 = 0.0000` — **회원의 연속된 세션이 절대 같은 상태가 될 수 없다.** 종속이 사라진 게 아니라 방향만 뒤집혔다. `MD5` 로 재교체.
- **Result**:
  - 시딩 결함 **3건** 정정 (`status`·`start_time` 종속, `CROSS JOIN` 오용으로 모든 행이 2벌)
  - 재측정으로 **미측정으로 남아 있던 3건**이 닫혔다 — 드라이빙 테이블은 `users`, 조건부 조인은 **SQL 을 줄이지 않음**(Hibernate 가 안 쓰는 to-one 조인을 이미 지운다 — 반사실과 SQL 이 글자 그대로 같았다), 6번째 인덱스의 쓰기 대가는 실제 삽입 패턴에서 **1.007배**
  - 재발 방지로 **시딩 자기검증 7종** 추가 — 하나라도 실패하면 측정을 시작하지 않는다
- **면접 (이 카드의 핵심)**:
  - *"0건을 잡아낸 건 `EXPLAIN` 이 아니라 **결과값에 대한 의심**이었습니다. 계획만 보면 `type`·`key` 가 멀쩡했거든요. 실행 계획을 읽기 전에 결과가 말이 되는지부터 봐야 한다는 걸 그때 배웠습니다."*
  - *"CRC32 로 고친 판도 틀렸는데, **고쳤는지를 다시 쟀기 때문에** 드러났습니다. '고쳤다'고 선언하기 전에 고쳐졌는지 재는 것까지가 수정이라고 봅니다."*
  - *"CRC32 가 나쁜 함수라서가 아니라 **목적이 다른 갈래에서 빌려왔기** 때문입니다. 오류 검출용 체크섬을 값 분산에 쓴 거고, 그 선형성이 네트워크에선 장점인데 여기선 정확히 약점이었습니다."* → [`hash-function-selection.md`](../decisions/hash-function-selection.md)
- **곁가지 — 교과서 값 하나를 반증했다**: *"무작위 삽입은 B+tree 페이지를 50:50 으로 쪼개 채움률이 ln2≈69% 로 떨어진다"* 고 적었다가, 단일 인덱스 20만 행으로 재보니 순차·무작위 **모두 289 페이지**였다. `innodb_change_buffering='none'` 으로 꺼도 같았다. **이유는 규명하지 못해 미규명으로 남겼다** — 다른 메커니즘을 추측해 채우지 않았다.
- **근거**: [`admin-page-scope.md`](../decisions/admin-page-scope.md) §4-2(결함 #4·#5·#6)·§4-4-1·§4-5-1, [`hash-function-selection.md`](../decisions/hash-function-selection.md), PR [#107](https://github.com/Shadowfit/init/pull/107), 이슈 [#108](https://github.com/Shadowfit/init/issues/108)·[#109](https://github.com/Shadowfit/init/issues/109)·[#110](https://github.com/Shadowfit/init/issues/110)

### #11 🟠 측정 장치가 측정 대상을 바꾸고 있었다 (2026-08-06)

- **문제**: 실행 계획 캡처를 돌릴 때마다 스크래치 DB 의 세션 상태가 조금씩 달라졌다.
- **Root cause**: 캡처 장치가 `@SpringBootTest` 라 **전체 컨텍스트**가 뜨고, 거기 포함된 `SessionTimeoutScheduler` 가 시딩된 `IN_PROGRESS` 세션을 실제로 `FAILED` 로 UPDATE 했다. 1회 실행에 160건, **오염량은 테스트가 도는 시간에 비례**.
- **Solution**: `scheduling.enabled` 프로퍼티로 스케줄링을 끌 수 있게 하고(기본값 켜짐 — 운영 동작 불변) 캡처 장치 3종에서 껐다. **주기 프로퍼티로는 못 막는다** — `initialDelay` 가 30초 고정이라 한 번은 반드시 돌고, 그 한 번이 `findByStatus(IN_PROGRESS)` 로 25만 엔티티를 메모리에 올린다.
- **면접**: *"측정 장치가 측정 대상을 바꾸면 그 결과는 무효인데, 이건 실행이 성공하니까 신호가 없습니다. 로그에 스케줄러 WARN 이 찍힌 걸 보고 알았습니다."*
- **근거**: [`admin-page-scope.md`](../decisions/admin-page-scope.md) §4-2 결함 #4, 이슈 [#108](https://github.com/Shadowfit/init/issues/108)

> 📌 **#6 · #10 · #11 을 묶으면 하나의 서사가 된다** — "측정을 믿기 전에 측정 장치를 믿을 수 있는지 확인한다." 층이 각각 **절차(워밍업) → 데이터(분포) → 장치(부작용)** 로 내려간다. 세 개를 따로 말하는 것보다 이 순서로 엮는 편이 세다.

---

## 3. 개발 예정 카드 → ✅ **다섯 개 전부 구현됨** (2026-08-07 확인)

이 절은 2026-06-02 에 "만들면 스토리"로 적어둔 것인데, **다섯 개가 다 끝났다.** 코드로 확인한 위치를 같이 적는다 — 카드로 승격하려면 각각 *problem → root cause → solution → result* 형식으로 §2 에 옮겨야 한다(아래 ⚠️).

| 카드 | 성격 | 상태 · 코드 위치 |
|---|---|---|
| **읽기 최적화 (projection)** | 🔴 헤드라인 | ✅ `PoseDataRepository:29` `PoseFrameProjection`(**현재 4컬럼**, 측정은 3컬럼 시점). 실측 payload **−98.7%**, warm 쿼리 **8x**(로컬 412만 행) → **29~41x**(AWS 1억 행, 2026-07-15) ([`report-read-path.md`](../decisions/report-read-path.md) ①)<br>⚠️ precompute가 세션당 1회 도는 비동기 잡 — "리포트가 빨라졌다"가 아니라 **잡의 I/O·버퍼풀 점유 절감** |
| **일일 집계 lost-update** | 🟠 동시성 | ✅ `DailyLogRepository:30` `ON DUPLICATE KEY UPDATE` 원자 upsert. 재현·비교는 `loadtest/measure_lock.sh`(scratch `lock_lab`) |
| **report 생성 멱등성** | 🟠 정합성 | ✅ `mysql/schema.sql:204` `UNIQUE KEY uk_report_session (session_id)` |
| **파티셔닝 + TTL** | 시계열 운영 | ✅ `mysql/schema.sql:148` `PARTITION BY RANGE(UNIX_TIMESTAMP(created_at))` + 자동 운영 스케줄러. **DROP PARTITION 625배** 실측 |
| **Resilience4j** | 운영 신뢰성 | ✅ `ExerciseAnalysisService:83` `aiCircuitBreaker()` + gRPC deadline |

> ⚠️ **"구현됨"과 "카드가 됨"은 다르다.** 위 다섯은 코드가 있다는 것까지만 확인했고, §2 카드들처럼 **문제 → 원인 → 해결 → 수치**로 정리된 상태가 아니다. 특히 *일일 집계 lost-update* 와 *report 멱등성* 은 **"경합이 실제로 일어나 손실이 났다"는 재현 근거**가 카드에 필요한데, 지금은 scratch 테이블 실험(`measure_lock.sh`)만 있고 실코드 경로의 사례가 아니다. 면접에서 "실제로 겪었나"를 물으면 갈린다.

### 3-1. 다음에 만들 카드 (미착수)

| 카드 | 성격 | 비고 |
|---|---|---|
| ⬜ **CD 워크플로 + 배포** | 운영 | 남은 4덩어리 중 하나 ([`../tasks/28-remaining-work-plan.md`](../tasks/28-remaining-work-plan.md) §2) |
| ⬜ **외부 통합 1개** (S3 / OAuth2) | 키워드 | 〃 |
| ⬜ **Prometheus + Grafana** | 실험 관측 | 부하 실험 중 시계열을 남길 수단 — [`§1-1`](#1-1-채점-중-정정된-것-같은-오류-반복-방지용) 이 "남는 감점"으로 지목한 항목 |
| 🔶 **인덱스 구성 재설계** | 🔴 DB 깊이 | `member_id` 선두 3종이 겹치는지 + 6번째를 얹을지. 오늘 양방향 증거가 하나씩 나옴(이슈 [#110](https://github.com/Shadowfit/init/issues/110)) |

---

## 4. 면접 답변 정련 가이드

각 🔴/🟠 카드를 **3단 길이**로 준비:
- **30초**: 문제 → 핵심 해결 → 수치 결과 (예: "JPA saveAll이 IDENTITY로 batch 차단돼 개별 INSERT N방 → JdbcTemplate batch로 throughput +99%")
- **2분**: + root cause 진단 과정 + 트레이드오프 + 왜 다른 대안 아님
- **10분**: + 측정 방법 + 코드 + 회귀 위험 + 다음에 할 것

> 포화 시장의 승부처(§25-doc §36): 산출물 자체보다 **"왜 이렇게? 단점은? 대안은? 측정은?"에 라이브로 답**하는 능력. 위 카드 전부 그 질문을 견디게 준비.

---

## 5. 관련 문서
- [`./db-deep-dive.md`](./db-deep-dive.md) — DB 깊이 (시계열·정합성·격리수준)
- [`../decisions/load-test-strategy.md`](../decisions/load-test-strategy.md) — 부하 테스트 측정값·방법론
- [`../decisions/redis-introduction.md`](../decisions/redis-introduction.md) — Redis 보류 결정
- [`../tasks/25-portfolio-strategy.md`](../tasks/25-portfolio-strategy.md) — 진로 전략 회고
