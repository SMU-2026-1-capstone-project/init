# 포폴: RealMySQL 8.0 (1·2권) → 이 프로젝트 적용 실험 패키지

작성: 2026-06-02
대상: DB를 **현업/RealMySQL 1·2권 수준**으로 파기. 책 챕터를 이 프로젝트 데이터(시계열 `pose_data` · JSON · gRPC 적재 · 집계)에 **구체 실험**으로 매핑.
연관: [`./db-deep-dive.md`](./db-deep-dive.md), [`./problem-solving-log.md`](./problem-solving-log.md), [`../decisions/load-test-strategy.md`](../decisions/load-test-strategy.md), [`../tasks/26-vacation-semester2-portfolio-plan.md`](../tasks/26-vacation-semester2-portfolio-plan.md)

> ⬜=실험 미실시(설계만). 각 카드는 **4단**(책 개념/가설/설계/결과+회고)으로 채워나감. substrate 강도 🟢진짜 / 🟡부분·합성 / 🔴없음.
> 장 번호는 RealMySQL 8.0 개정판 기준 (판본 따라 ±1).

---

## 0. 포지셔닝 — 왜 이게 "현업 수준"으로 보이나

**클로드 시대엔 실험 코드가 아니라 "측정·발견·판단"이 차별점**([`25-doc §36`](../tasks/25-portfolio-strategy.md)). RealMySQL 실험의 가치는 "돌렸다"가 아니라:
- **가설 → 측정 → 발견(가설과 다름) → 회고**의 지적 작업 (AI가 못 하는 20%)
- `EXPLAIN ANALYZE`·`performance_schema`로 **증거 기반** (정량)
- "이건 이 프로젝트에 안 맞아서 안 했다"는 **substrate 정직성** (복제 등)

→ 각 실험 = STAR 면접 답변 그대로 (상황/과제/행동/결과).

---

## 1. 이 프로젝트 데이터 substrate (왜 RealMySQL에 적합)

| 특성 | 해당 | RealMySQL 연결 |
|---|---|---|
| 시계열 대용량 | `pose_data` (연 ~8억 행 가능, §4.3) | Ch.8·10·13 |
| JSON 컬럼 | `joint_coordinates` 1~3KB | Ch.15·11(projection) |
| 이벤트 적재 정합성 | gRPC 콜백, `@Version`, INSERT IGNORE | Ch.5 |
| cross-session 집계 | admin 통계·패턴 분석 | Ch.9·10·13(pruning) |
| 동기 쓰기 부하 | `SavePoseDataBatch` + ghz | Ch.4(버퍼풀)·18 |

---

## 2. 장별 적합도 맵

| 권 | 장 | 주제 | substrate | 우선 |
|---|---|---|---|---|
| 1 | Ch.4 | 아키텍처(InnoDB·버퍼풀·MVCC) | 🟢🟡 | ④ |
| 1 | Ch.5 | 트랜잭션·잠금·격리수준 | 🟢 | ③ |
| 1 | Ch.6·7 | 압축·암호화 | 🟡 | 선택 |
| 1 | Ch.8 | 인덱스 | 🟢 | ① |
| 1 | Ch.9 | 옵티마이저·힌트 | 🟢 | ⑤ |
| 1 | Ch.10 | 실행 계획(EXPLAIN) | 🟢 | ① |
| 2 | Ch.11 | 쿼리 작성·최적화 | 🟢 | ② |
| 2 | Ch.12 | 전문·공간 검색 | 🔴 | N/A |
| 2 | Ch.13 | 파티션 | 🟢 | ② |
| 2 | Ch.14 | 스토어드 프로그램 | 🟡 | 시딩 도구 |
| 2 | Ch.15 | 데이터 타입(JSON) | 🟢 | ④ |
| 2 | Ch.16·17 | 복제·InnoDB 클러스터 | 🔴 | 개념만 |
| 2 | Ch.18 | Performance Schema·sys | 🟢 | ⓪ 백본 |

---

## 3. 공통 선결 — 1억 row 합성 시딩 ✅ (2026-06-03 완료)

모든 대용량 실험의 전제. **"DAU 1,000 시뮬레이션 위해 합성"이라고 명시**. 결과: `pose_data_scale` **정확히 1억 행** (133,334 세션×750행, 2026년 12개월+ 분산), ~11GB.
- 방법: `_seq`(numbers 0~99만, digit cross join) × `_seq` cross join으로 session_id·날짜 분산. **더미 JSON `{}`** — 행수·payload 디커플링(§0.3): 실제 2.3KB JSON이면 1억 행이 **JSON 바이트만 ~230GB**라 로컬 불가, 더미로 ~11GB.
  - 📌 **이 값의 산식과 교차검증** (2026-08-12 정리). 산식은 **1억 × 2.3KB = 230GB** 로 **JSON 바이트만** 센 것이고, **테이블은 그보다 크다**(다른 컬럼 + InnoDB 페이지 오버헤드).
    - **실측 교차검증**: 아래 §②b AWS `pose_data_real_scale`(진짜 2.3KB JSON)에서 5,000세션 = 375만 행의 **실제 JSON 합이 8.19GB** 로 기록돼 있다 → 행당 ≈2,184B → 1억 행 환산 **≈218GB**. 같은 자릿수이고 230GB 와 정합한다.
    - 🔴 **정정**: 이 문서를 포함해 레포 4곳이 같은 값을 **255GB** 로 적고 있었다(`:125` · `loadtest/seed/*` · `FINAL-REPORT`). **255 의 산식은 어디에도 없다** — 행당 2,550B 를 함의하므로 오버헤드 포함 «테이블 크기» 로 보이나, 그렇게 적은 곳이 없어 근거를 댈 수 없었다. 같은 문서 `:108` 은 이미 **230GB** 를 쓰고 있어 한 파일 안에서도 갈려 있었다. **JSON 바이트 기준 230GB 로 통일한다.**
- 가속 교훈(실측): ① **버퍼풀 128MB→2GB** 필수(기본값은 롤백 64행/s, 2GB는 1만행/s). ② **인덱스는 시딩 후 일괄 빌드**(random insert 페이지 분할 회피, 375만 청크 3.4분→2분). ③ **청크 병렬**(세션 범위 3분할 동시 INSERT, 48분→16분). ④ `innodb_sort_buffer_size=1M→64M`로 인덱스 merge sort 가속.
- 스크립트: `loadtest/seed/seed_pose_scale.sh` (재현), `loadtest/measure_pagination.sh` (②c 측정).

---

## 4. 실험 카드 (우선순위 순)

### ⓪ Ch.18 — Performance Schema (측정 백본) 🟢
- **개념**: `performance_schema`·`sys` 스키마로 쿼리/락/I-O 관측.
- **가설**: 모든 후속 실험의 "증거"는 추측이 아니라 PS 지표로 댄다.
- **설계**: `events_statements_summary_by_digest`(쿼리 다이제스트), `data_locks`/`data_lock_waits`(락 대기), `table_io_waits_summary`(테이블 I-O), `file_summary`(디스크). sys: `sys.statement_analysis`, `sys.schema_index_statistics`.
- **지표**: 느린 쿼리 digest, 락 대기 건수, 버퍼풀 hit율.
- **결과 ✅ (별도 카드가 아니라 전 실험에 걸쳐 실제로 사용된 백본 — 여기서 모아서 정리)**:
  - **`data_locks`**: lost-update 실험(③b, `lock_lab`)에서 trx A `X,REC_NOT_GAP GRANTED` / trx B `WAITING` 관찰해 비관락의 직렬화를 실물 확인. MVCC 실험(④)에서 RR은 `data_locks` **0행**(락 없이 undo로 읽음) vs SERIALIZABLE은 `RECORD S GRANTED`(암묵적 잠금읽기)로 대조. 이번 세션 활성 세션 비관락(`findByIdForUpdate`)도 `REC_NOT_GAP GRANTED/WAITING`으로 갭 없는 레코드 락임을 확인.
  - **status 카운터(`Innodb_buffer_pool_reads`/`read_requests`/`read_ahead`, `Innodb_data_read`)**: 버퍼풀 실험(④)에서 표준 hit율 공식(1−reads/read_requests)이 read-ahead 때문에 거짓 99%로 나오는 함정을 발견 — `data_read`(실제 물리 I/O 바이트)로 봐야 진짜 84%/485MB가 드러남. `FLUSH STATUS`가 InnoDB 버퍼풀 카운터를 리셋 안 한다는 것도 이 과정에서 실측으로 확인(before/after 델타 수동 계산으로 우회).
  - **`EXPLAIN`/`ANALYZE TABLE`**: 인덱스(①), projection(②b), 파티션 pruning(②d), 옵티마이저 크로스오버(⑤) 전 실험의 1차 증거.
  - **`SET profiling=1`/`SHOW PROFILES`**: wall-clock(SSH+docker exec 왕복 ~50ms 오버헤드)이 sub-100ms 쿼리 측정을 오염시키는 문제를 발견하고 우회한 도구 — projection 실험 첫 시도(오버헤드 때문에 2x로 나왔던 것)를 이걸로 재측정해 진짜 29~41x를 찾음.
- **면접**: "감으로 '느리다'가 아니라 `data_locks`로 락 대기를 실물 관찰하고, `Innodb_data_read`로 진짜 물리 I/O를 확인하고, wall-clock 오염을 `SET profiling`으로 우회하는 식으로 — 이번 프로젝트의 모든 실측 결론이 PS 지표 위에 서 있다."

### ① Ch.8 + Ch.10 — 인덱스 & 실행계획 🟢
- **개념**: 클러스터링 인덱스, covering index, 카디널리티 / `EXPLAIN` type·key·rows·Extra.
- **이 프로젝트**: `pose_data.idx_session_timestamp(session_id, timestamp_sec)`.
- **가설(반증된)**: "리포트 쿼리가 인덱스 없어 풀스캔" → **측정으로 폐기**: 이미 covering(lookup+정렬 한 인덱스, filesort 없음), 세션 단위라 테이블 성장 무관(§4.3).
- **설계**: 리포트 쿼리 `EXPLAIN ANALYZE` → `IGNORE INDEX`로 풀스캔 강제 비교.
- **결과 ✅ (2026-06-02, 로컬 412만 행)**:
  - 인덱스 사용: `type=ref`, `key=idx_session_timestamp`, **`Extra=NULL` (filesort 없음)** — WHERE+ORDER BY를 인덱스가 완결.
  - `IGNORE INDEX` 강제 풀스캔: **4.13M 행 스캔 + filesort = 85,000ms (85초)**.
  - → **"인덱스 추가"가 아니라 "이미 최적임을 측정으로 발견"**(§4.3 확인). 인덱스가 85초→lookup으로 바꾸는 핵심 역할도 대조로 입증.
  - **✅ 인덱스 vs 무인덱스 직접 대조 재측정(2026-07-20)**: 위 85초는 풀스캔 단독 비용이라 "인덱스 있을 때 대비 몇 배냐"가 별도로 명시된 적 없었음(과거엔 이 배수를 다른 실험 수치와 짜깁기해 "7,000배"로 추정했었는데, 이는 근거 없는 추론이라 폐기). 실제로 같은 테이블·같은 조건에서 인덱스 있음 vs `IGNORE INDEX` 강제 풀스캔을 직접 재측정(`SET profiling`, wall-clock 오염 배제):
    - 더미 JSON(412.5만 행): 인덱스 있음 **~1.76ms** vs 강제 풀스캔 **~1.17초** → **약 660배**.
    - 실제 2.1KB JSON(412.5만 행, 원 실험과 동일 조건): 인덱스 있음 **~3.0ms** vs 강제 풀스캔 **~27.1초** → **약 9,000배**.
    - **더미 JSON은 이 배수를 과소평가함** — 실제 JSON은 행이 커서 페이지당 담기는 행 수가 줄어 풀스캔이 훑을 페이지 수 자체가 늘어나는 반면(인덱스 조회는 750행만 보므로 거의 영향 없음, 1.76ms→3.0ms), 그 결과 배수가 660배→9,000배로 크게 벌어짐. **payload 크기가 "인덱스 유무의 체감 차이" 자체를 증폭시킨다**는 걸 확인.
- **면접**: "인덱스 넣기 전에 EXPLAIN으로 이미 최적 확인(filesort 0). 실제 조건(2.1KB JSON, 412만 행)으로 인덱스 유무를 직접 대조하니 약 9,000배 차이. 진짜 병목은 payload(Ch.11 projection)."

### ② Ch.11 + Ch.13 — 쿼리 최적화 & 파티션 🟢⭐
- **개념**: bulk INSERT, 페이지네이션(offset→cursor), projection / Range 파티션·프루닝.
- **이 프로젝트 (a) 쓰기**: ✅ batch insert 완료 — JdbcTemplate `batchUpdate`, throughput **+99%**, p99 −37% (§7.6). Ch.11 bulk insert 그 자체.
- **이 프로젝트 (b) projection**: ✅ `ReportService` JSON blob 헛로드(~3MB) → 3컬럼 DTO.
- **이 프로젝트 (c) 페이지네이션**: ✅ 전체 테이블 시간순 페이지네이션 offset → keyset(cursor). 1억 행 합성 rig로 실측 — offset O(N) 선형 저하 vs keyset 평탄 입증.
- **이 프로젝트 (d) 파티션**: `pose_data` 날짜 Range 파티션 → **버퍼 TTL의 DROP PARTITION**(주용도, [`db-deep-dive §2-0`](./db-deep-dive.md) raw=버퍼) + cross-session 집계 pruning(부차). ⚠️ PK를 `(id, created_at)`로 변경 선결. 샤딩은 미적용(과설계).
  - **언제 파티셔닝하나? — "행 수"가 아니라 "용도"** ⭐: 업계 감각치(단일 테이블이 버퍼풀 초과 / 수천만~1억 행 / 수십~100GB↑)는 **필요조건일 뿐 충분조건 아님**. RealMySQL도 "몇 행"으로 안 박고 "감당·관리(특히 오래된 데이터 삭제)가 부담일 때"로 설명. 정당화 트리거 3: ①TTL/보존(대량 DELETE 부담→DROP PARTITION) ②쿼리가 항상 파티션 키로 범위 좁힘(pruning) ③파티션 단위 백업·아카이빙.
  - **이 프로젝트의 정직한 포지션**: DAU 작아 파티션 **실수요 0**, 1억 행은 "DAU 1,000 합성", 세션 리포트는 인덱스로 이미 빠름(pruning 이득 없음, §4.3). → 유일한 정당화 = **raw를 단기 버퍼로 보고 `DROP PARTITION`으로 싸게 버리는 TTL**. "1억이니까 파티션"이 아니라 "크기는 충분조건 아님 → 용도(buffer TTL)가 있어서 → 그래서 pruning 아닌 DROP이 헤드라인"이라는 **판단 과정 자체가 차별점** (AI의 "1억이면 파티션 추천"과 정반대 결).
  - **왜 ALTER인가 (CREATE vs ALTER)**: 시딩은 **일부러 비파티션 일반 테이블**로 채움(파티션 걸면 INSERT마다 라우팅 계산으로 시딩 느려짐). 이미 1억 행 든 테이블에 파티션을 거니 `CREATE` 불가 → `ALTER TABLE ... PARTITION BY`만 가능. 그런데 이건 메타데이터만 바꾸는 게 아니라 **임시 테이블(`#sql-…`) 생성 → 1억 행 전부 읽어 파티션별 재배치 → 인덱스 재빌드 → 원본 교체**(=`copy to tmp table`).
  - **같은 `ALTER`라도 비용 정반대** ⭐: `PARTITION BY`(전환)=1억 행 풀 리빌드 71분 vs `DROP PARTITION`(만료)=O(1) 메타데이터. 이 대비가 카드 핵심 — "파티션 전환은 비싸니 운영이면 처음부터 파티션 테이블로 만들거나 pt-osc 무중단, 일단 걸어두면 만료는 DROP으로 거의 공짜".
  - **운영 캐비엇**: 이 in-place `ALTER ... PARTITION BY`는 1억 행 **copy-to-tmp 풀 리빌드**(로컬 실측 ~85분, ~24,700행/초, 그동안 테이블 락) → 현업 운영 DB라면 `pt-online-schema-change`/`ALGORITHM=INPLACE`로 무중단 전환할 이유. 회고 포인트.
  - **✅ 결과 — 그 「무중단 전환할 이유」를 실측으로 바꿈 (2026-08-12, EC2 m6i.xlarge, `pose_data_scale` 1,000만 행)**: 위 캐비엇은 **권고**였다. 팔 A(차단 `ALTER`) ↔ 팔 B(`pt-osc`) 를 같은 rig 에서 버림 2판 + 본판 6판(`A B B A A B` 위치 상쇄)으로 대조했다. 상세 [`online-ddl-aws-2026-08-12`](../../loadtest/results/online-ddl-aws-2026-08-12/README.md).

    | | 팔 A (차단 ALTER) | 팔 B (pt-osc) |
    |---|---|---|
    | DDL 소요 | 68~70초 | 112~114초 (**1.64배 느림**) |
    | **최대 쓰기 정지** | **68,109~69,355 ms** | **30~364 ms** (**최소 187배 짧음**) |
    | writer 성공 | 18회 | 553~558회 |
    | binlog 증가 | **0 MB** | **441 MB** |
    | 디스크 최대 | 2,359~2,377 MB | 2,348~2,352 MB |

    - ⭐ **팔 A 의 최대 정지(≈69초) ≈ 팔 A 의 DDL 소요(≈69초)** — 「느리지만 조금씩 통과」가 아니라 **전 구간 차단**이다. errors 는 6판 전부 0 이라 writer 는 죽은 게 아니라 **기다린** 것이고, 그래서 이 정지값이 의미를 갖는다.
    - ⭐ **디스크는 두 팔이 같다.** 「pt-osc 는 복사본을 만드니 디스크를 더 쓴다」가 여기선 틀린다 — 팔 A 의 `ALGORITHM=COPY` 도 똑같이 전체 사본을 만들기 때문이다. **pt-osc 의 대가는 디스크가 아니라 binlog** 다(441MB, 복제 지연으로 이어지는 비용이라 P4 의 질문이 된다).
    - **애초에 선택지가 둘뿐이었다**: `PARTITION BY RANGE` 는 서버가 `INPLACE` 를 거절한다(errno 1845). `LOCK` 절을 빼도 같은 1845 라 거절 사유는 **INPLACE 자체**다 → 「온라인 DDL 쓰면 되잖아」가 이 DDL 엔 성립하지 않는다.
    - **pt-osc 의 실제 동작**: 복합 PK `(id, created_at)` 를 별도 인덱스 없이 `FORCE INDEX(PRIMARY)` 로 청킹, 트리거 3개(`_ins`·`_upd`·`_del`) 생성 후 `RENAME TABLE` 원자 스왑.
    - ⚠️ **위 로컬 96분(1억 행)과 다른 규모·다른 기계다.** 배수는 **같은 라운드 안에서만** 유효하고 절대 시간은 하드웨어 종속이라 인용하지 않는다. 트리거 오버헤드를 환경에서 분리하지도 못했다(팔 B 가 CPU 를 더 쓰는데 writer 를 동시에 돌린다).
- **설계**: (b) projection before/after payload·응답 / (c) offset N=10만 vs cursor 응답 곡선 / (d) 파티션 후 `EXPLAIN`의 `partitions` 컬럼 pruning 확인 + **DROP PARTITION vs DELETE WHERE 실측 시간**(O(1) vs 락·undo).
- **결과 (b) projection ✅ (2026-06-02, warm, 세션 750행, 412만 행 테이블)**:
  - payload **1,716.8 KB → 22.4 KB (−98.7%)**, warm 쿼리 **12.1ms → 1.5ms (8x, −87%)**. **인덱스는 동일** — 차이는 `joint_coordinates`(2.3KB JSON)가 InnoDB **off-page(overflow) 저장**이라 SELECT 시 추가 random I/O, projection이 회피(Ch.15).
  - ⚠️ cold 721ms → warm 12ms(같은 쿼리) — 워밍업 통제 필수 재확인(§7.6). 절대 ms는 로컬 기준, 상대 delta는 신뢰 가능.
  - **✅ AWS 1억 행 real-JSON 재검증(2026-07-15)**: 면접 준비 중 로컬 하드웨어 제약(~230GB 문제, §3)을 실제로 AWS EC2(m6i.xlarge)+EBS 700GB로 우회해 **진짜 1억 행 × 실제 2.3KB JSON**(`pose_data_real_scale`, 133,334세션×750행 정확히 1억)으로 재현. payload **1,740.1 KB → 22.6 KB (−98.7%)** — 412만 행 때와 거의 동일(행수와 무관, 세션당 바이트 비율이라 예상대로). **warm 쿼리는 오히려 훨씬 크게 개선**: 40.6ms → 1.4ms(**약 29배**, 반복 측정 시 최대 41배까지 관찰) — 412만 행 때의 8배보다 큼. 이유: 버퍼풀(2GB)은 동일한데 테이블이 25배 커져서(~230GB) **작업셋 대비 버퍼풀 비율이 더 나빠짐** → 풀엔티티 로드의 off-page 랜덤 I/O가 캐시에 덜 걸리고 실제 디스크 I/O를 더 많이 탐 → projection이 그 I/O를 회피하는 효과가 스케일이 커질수록 더 커짐. **결론이 뒤집힌 게 아니라 강화됨**: "이미 최적 인덱스, projection이 진짜 병목 해결" 결론은 동일, 배수만 스케일에 비례해 커짐.
    - ⚠️ **측정 도구 자체의 함정도 잡아냄**(§0 Performance Schema 백본 참고): 이 29~41배는 재측정 결과 — 첫 시도는 SSH+`docker exec` 왕복 wall-clock(~50ms 오버헤드)로 쟀다가 **2배로 축소돼 나왔음**. sub-100ms 쿼리 차이가 그 오버헤드에 묻힌 것. `SET profiling=1`(DB 내부 타이밍, 네트워크 왕복 배제)로 바꿔 재측정해서 진짜 29~41배를 찾음 — 첫 숫자를 그대로 안 믿고 측정 도구 자체를 의심·교정한 과정.
  - **✅ 풀스캔 대조도 1억 행으로 재검증(2026-07-15)**: `IGNORE INDEX` 강제 풀스캔 = **2,120.9초(35분 20초)**. 412만 행의 85초 대비 1억 행은 412만 행의 약 24.27배인데, 85×24.27≈2,063초(34.4분) 선형 추정치와 **거의 정확히 일치**(2,120.9초) — 풀스캔 비용이 행 수에 선형(O(N))으로 늘어난다는 걸 실제 1억 행 규모에서 재확인.
  - ⚠️ **실무 프레임 명확화**: 이 쿼리는 사용자가 매번 누르는 조회가 아니라 **세션 종료 시 precompute가 세션당 딱 한 번 도는 것**([`db-deep-dive §2-0`](./db-deep-dive.md)). 그래서 8~40배 개선의 의미는 "사용자 체감 지연이 빨라졌다"가 아니라 — precompute는 비동기라 사용자는 이 지연을 원래 못 느낌 — **"세션마다 반복되는 이 잡의 I/O·버퍼풀 점유를 줄여 시스템 전체 자원 부담을 낮췄다"**는 쪽. "리포트가 빨라졌다"로 말하면 부정확, "정기적으로 도는 무거운 잡의 자원 소모를 줄였다"가 정직한 프레임.

  - 📌 **이 수를 인용할 때 같이 가야 하는 조건 (2026-08-17 박제)** — 값은 정확하다. 빠져 있던 것은 **조건**이다. 인용처 12곳에 이 표를 단서로 달았다(«3.47배» 13곳에 조건을 단 `0d68b52` 와 같은 작업).

    | 축 | 조건 |
    |---|---|
    | **경로** | projection 쿼리(`findFramesBySessionId`)는 **두 곳에서만 돈다** — ① `SessionService.precomputeReport`(세션 종료 시 1회, 비동기), ② `ReportService.resolveDetailedAnalysis` 의 **폴백**(`detailed_analysis` 가 비었거나 구버전일 때). **정상 리포트 조회는 저장된 JSON 을 파싱하고 끝나 pose_data 를 안 읽는다** → 정상 경로에서 이 수의 응답시간 기여는 **0**. 🔴 이건 **코드 판독이고 아직 측정이 아니다**([`../decisions/projection-end-to-end-remeasure.md`](../decisions/projection-end-to-end-remeasure.md) Q1 이 «0 임을 재는 것» 을 1차 산출물로 세워둔 상태) |
    | **층** | **DB→앱 payload** 다. 앱→클라이언트 API 응답 바이트가 아니다 — API 응답이 안 변하는 건 정상이며 «효과 없음» 으로 읽으면 틀린다 |
    | **형상** | **3컬럼 시절**(`timestampSec`·`syncRate`·`feedbackMessage`)의 값이다. 지금 `PoseFrameProjection` 은 **4컬럼**(`repNumber` [#78](https://github.com/Shadowfit/init/issues/78) 추가 · `smoothedKneeAngle` 추가 · `feedbackMessage` [#80](https://github.com/Shadowfit/init/issues/80) 제거). `joint_coordinates`(2.3KB)를 안 싣는 핵심은 그대로라 배수가 크게 흔들리진 않겠지만, **«현재 값» 으로 인용된 적은 검증된 바 없다** |
    | **환경** | payload −98.7% 는 로컬 412만 행(2026-06-02)과 AWS 1억 행(2026-07-15)에서 **둘 다 동일**. 반면 warm 쿼리 배수는 환경 종속이다 — 8x(로컬 412만) → 29~41x(AWS 1억). 배수를 인용할 땐 어느 쪽인지 밝힐 것 |
- **결과 (c) 페이지네이션 ✅ (2026-06-03, 1억 행 합성[측정 시점 9,750만, 결론 동일], warm 3회째, EXPLAIN ANALYZE)**:

  | 깊이(OFFSET) | offset ms | keyset ms | speedup |
  |---|---|---|---|
  | 0 | 0.035 | 0.056 | 1x |
  | 1만 | 3.04 | 0.079 | 39x |
  | 10만 | 27.7 | 0.035 | 798x |
  | 100만 | 290 | 0.034 | 8,504x |
  | 1,000만 | 2,933 | 0.046 | 64,039x |
  | **5,000만** | **25,963 (26초)** | **0.053** | **489,868x** |

  - **offset = O(N) 선형** — 깊이 10배마다 시간 ~10배(`EXPLAIN`: rows 스캔량 = OFFSET+20, cost 0.09→149,953). keyset = **평탄(≈O(log n))**, PK 범위 점프라 깊이 무관.
  - ⚠️ cold/warm: OFFSET 1,000만 cold **8.9초** → warm **2.9초**(버퍼풀 캐시). **캐시돼도 선형 유지** → 병목은 디스크 I/O가 아니라 **행 스캔/폐기 자체(CPU)**. keyset은 그 스캔을 안 함이 핵심.
  - rig: `loadtest/measure_pagination.sh`(재현), 133,334 세션×750행=정확히 1억 행, 더미 JSON `{}`(행수·payload 디커플링 §0.3 — 실제 2.3KB JSON이면 JSON 바이트만 ~230GB라 불가, 더미로 ~11GB). 버퍼풀 2GB·sort_buffer 64M로 시딩·인덱스 빌드 가속(`docker-compose.yml`).
  - **✅ AWS 1억 행 real-JSON 재검증(2026-07-16)**: 기존 실험은 더미 JSON(`{}`)이라 payload 크기가 offset 비용에 미치는 영향은 안 담겨 있었음. `pose_data_real_scale`(진짜 2.3KB JSON, m6i.xlarge)에서 동일 깊이대로 재현.

    | 깊이(OFFSET) | offset ms | keyset ms |
    |---|---|---|
    | 0 | 53.3 | 55.5 |
    | 1만 | 59.3 | 54.7 |
    | 10만 | 96.3 | 54.8 |
    | 100만 | 1,996 | 55.8 |
    | 1,000만 | 164,411 (2.7분) | 56.3 |
    | 5,000만 | 941,218 (15.7분, run1/2 평균 — run3 도중 정리 과정에서 종료) | 56.3 |

    - **offset이 더미보다 훨씬 나쁘게 커짐** — 같은 5,000만 깊이에서 더미 JSON은 26.0초인데 실제 JSON은 941.2초(**약 36배**). 실제 행이 무거워(2.3KB) 페이지당 담기는 행 수가 적어지고, OFFSET의 O(N) 스캔-후-폐기가 밟는 페이지 수(=물리 I/O)가 그만큼 늘어난 것 — "payload가 무거울수록 offset 문제가 악화된다"는 가설(§5 캐비엇 이전 기록)을 실측으로 확인.
    - ⚠️ **keyset 절대값은 측정 한계**: 이번 측정은 `SET profiling`이 아니라 SSH+`docker exec` 왕복 wall-clock(`date`) 기반이라 ~50ms 클라이언트 오버헤드가 바닥에 깔림 — keyset이 깊이 무관 평탄(55~56ms)한 건 진짜지만 이 절대값은 쿼리 자체 비용이 아니라 오버헤드 바닥. offset은 100만 깊이 이상부터 이 바닥보다 압도적으로 커서 오염 영향이 무시할 만큼 작음. **결론(offset O(N) 폭증 vs keyset 평탄)은 유효, keyset 절대치만 액면가로 안 씀.**
- **결과 (d) 파티션 ✅ (2026-06-03, 1억 행 → `created_at` 월별 14파티션, in-place ALTER)**:
  - **파티션 전환 비용**: `ALTER TABLE ... PARTITION BY RANGE(UNIX_TIMESTAMP(created_at))` = **5,767초(96분)** copy-to-tmp 풀 리빌드(~24,700행/초). → 운영이면 처음부터 파티션 테이블 or `pt-osc`/`INPLACE` 무중단(위 운영 캐비엇 실측 확인).
  - **pruning (EXPLAIN `partitions` 칼럼)**: 날짜범위 `WHERE created_at∈[6월]` → **`p2026_06` 1개만**(13개 안 읽음) / 범위 없으면 14개 전부 / ⚠️ **`session_id=…` → 14개 전부**(rows:750이나 파티션-로컬 인덱스라 14개 트리 모두 탐색) — **세션 쿼리는 pruning 이득 0, 오히려 미세 손해**. §4.3·정직 포지션 실측 확인.
  - **⭐ DROP PARTITION vs DELETE WHERE (같은 ~8M 행 만료)**:

    | 작업 | 행수 | 소요 | 파티션 | `.ibd` 디스크 |
    |---|---|---|---|---|
    | `DELETE WHERE created_at<…` | 8,301,450 | **1,118,936 ms (18.6분)** | 잔존(0행) | **952MB 그대로**(공간 미회수) |
    | `ALTER … DROP PARTITION` | 7,560,000 | **1,790 ms (1.8초)** | 제거 | **파일 삭제 → ~910MB 즉시 회수** |

  - **~625배** (행당 정규화 ~570배). DELETE는 8.3M행 행단위 삭제(undo·인덱스 유지·락) + **빈 952MB 파일 잔존**(OPTIMIZE 없인 공간 안 돌아옴), DROP은 **파티션 `.ibd` 파일째 unlink** = 행단위 작업 0. ⚠️ DROP 1.8초도 진짜 O(1)이 아니라 ~910MB 파일 삭제 I/O(로컬 디스크 느림) — 빠른 스토리지면 sub-100ms. "O(1)"은 *행단위 비용 없음*을 뜻함.
  - rig: `loadtest/measure_partition.sh`(재현). 측정 후 p2026_01(DELETE로 빈 채 잔존)·p2026_02(DROP) 제거됨 → rig 재현은 `seed_pose_scale.sh`.
  - **✅ AWS real-JSON 서브셋 재검증(2026-07-15)**: 전체 1억 행(~230GB) 테이블을 통째로 파티션 변환하는 건 시간·비용 리스크가 커서(로컬 96분의 최대 20배 이상까지 갈 수 있어 예측 불가), 대신 별도 소규모 real-JSON 테이블(2파티션, old 52.5만 행/~1.2GB 실제 JSON)로 축소 재현. `DELETE WHERE` = **66.552초**, `DROP PARTITION` = **0.158초** → **약 421배**. 원본(더미, 830만 행, 625배)보다 배수는 작지만(스케일 축소 영향) 행당 DELETE 비용은 오히려 비슷(원본 0.135ms/행 vs 이번 0.127ms/행) — **DELETE 비용이 payload 크기가 아니라 행당 오버헤드(undo·인덱스·락)에 지배된다**는 걸 확인. 메커니즘(O(1) vs O(n))은 real JSON에서도 동일하게 재현됨.
- **면접**: "세션 리포트는 안 느려지지만(§4.3), payload는 JSON off-page 페치라 projection이 −98.7%. **파티션은 pruning(세션 쿼리엔 이득 0)이 아니라 TTL이 핵심 — 8M행 만료가 DELETE 18.6분 vs DROP PARTITION 1.8초(625x), DELETE는 빈 파일까지 남는다.**"

### ③ Ch.5 — 트랜잭션·잠금·격리수준 🟢
- **개념**: 격리수준(RC/RR), 레코드/갭/넥스트키 락, 낙관적 vs 비관적.
- **이 프로젝트 (a) 낙관락**: ✅ 구현 — 타임아웃 스케줄러 vs FastAPI 콜백(`Session.java:66 @Version`, `SessionTimeoutScheduler.java:84` 양보).
- **이 프로젝트 (b) lost-update**: `DailyLog.updateStats()` 배선 완료(2026-07-15) — 네이티브 upsert(`upsertStats`, `INSERT ... ON DUPLICATE KEY UPDATE`) 한 문장으로 구현, 동시 종료 경합 방지 ✅. 첫 시도(원자 UPDATE→실패 시 `save()`→실패 시 catch 재시도)는 동시성 테스트에서 Hibernate 세션 손상으로 실패해 폐기 — 실측으로 잡은 함정.
- **이 프로젝트 (c) 멱등성**: ✅ INSERT IGNORE (`FeedbackLogService.java:33`).
- **이 프로젝트 (d) 회원당 활성 세션 제약**: ✅ 추가(2026-07-16) — `createSession()`에 이미 진행 중인 세션이 있으면 409(`SESSION_ALREADY_IN_PROGRESS`)로 생성 자체를 거부. 원래는 이 체크가 없어 멀티 디바이스·네트워크 재시도로 한 회원이 세션 두 개를 동시에 `IN_PROGRESS`로 가질 수 있었음(한 사람이 물리적으로 두 운동을 동시에 할 수 없으니 원래 불가능해야 하는 상태) — (b) daily_logs 경합이 발생할 수 있는 사실상 유일한 경로였는데, 이 가드로 그 경합 자체가 구조적으로 불가능해짐. **동시성 방어의 우선순위: ① 경합이 아예 생길 수 없게 정책으로 막기(이번 가드) → ② 그래도 못 막는 경로(레거시 데이터·배치 등)에 대한 방어적 원자 연산((b)의 native upsert)**. ①을 추가했다고 ②가 무의미해지는 게 아니라 depth-in-defense로 함께 유지.
  - **구현 과정에서 TOCTOU 레이스 발견 → 제약 시도 → MySQL 제약으로 막힘 → 비관락으로 최종 해결**(3단계 실측):
    1. 첫 시도(`existsByMemberIdAndStatus` 체크 → `save()`)는 **check-then-act TOCTOU**: 두 요청이 거의 동시에 오면 둘 다 "커밋 전이라 상대가 안 보여" 체크를 통과해버림 — 격리수준과 무관(RC든 RR이든 커밋 안 된 데이터는 어차피 안 보임).
    2. DB 유니크 제약으로 원천 차단 시도(`active_session_marker` generated column, `status=IN_PROGRESS`일 때만 `member_id` 값·나머지 NULL, 유니크 인덱스는 NULL끼리 안 부딪힘) — 로컬 MySQL에 실제로 적용하다 **`ERROR 1215 Cannot add foreign key constraint`**로 막힘. 원인: `member_id`가 `ON DELETE CASCADE` FK 컬럼인데, MySQL은 CASCADE FK 컬럼을 generated column의 재료로 못 쓰게 막음(캐스케이드 발생 시 생성값을 재계산할 방법이 없어서). 설계 단계에선 안 보였고 실제로 적용해봐야 나온 제약 — 실측 없이 설계만 했으면 그대로 배포됐을 접근.
    3. 최종: `MemberRepository.findByIdForUpdate`(`@Lock(PESSIMISTIC_WRITE)`)로 회원 row를 잠그고, 그 잠금 안에서 `existsByMemberIdAndStatus` 체크 → 생성. 스키마 변경 없이 TOCTOU를 닫음 — 세션 생성이 유저가 연타하는 핫패스가 아니라 락 보유 비용도 무시할 만함. **이 프로젝트의 첫 실제 비관락 사용처**(그 전까진 `FOR UPDATE` 0건).
  - **검증**: `SessionCreateConcurrencyTest` — 같은 회원이 세션 두 개를 정확히 동시에 생성 시도 → 정확히 1개만 성공, 나머지는 `SESSION_ALREADY_IN_PROGRESS`(409)로 거절, 실제 생성된 세션도 1개 확인.
  - **제약 vs 락 선택 기준**(일반화): ① 위반 시 그냥 거절이면 충분한가(제약), 대기 후 통과시켜야 하는가(락) ② 단순 무결성 규칙 하나인가(제약), 여러 단계 절차 직렬화가 필요한가(락) ③ 호출 빈도·락 보유 시간이 큰가(제약 선호) ④ 제약으로 표현 가능한 걸 우선(모든 경로에서 보장되므로) — 단, 이번처럼 **스키마 제약이 기술적으로 막히면 락이 유일한 대안**이 됨.
- **설계**: (b) 두 트랜잭션으로 lost-update **재현**(RC) → 원자 UPDATE / `SELECT FOR UPDATE` / `@Version` 비교, `performance_schema.data_locks`로 락 관찰, `SHOW ENGINE INNODB STATUS` → (a) 낙관락 충돌 시 양보 정책 근거.
- **지표**: 손실 갱신 발생/방지, 락 종류·대기.
- **결과 (b) lost-update 재현·방지 ✅ (2026-06-05, scratch `lock_lab`, daily_logs.updateStats 동형, 매 run 초기화)**:
  - 시나리오: 같은 사용자·같은 날 두 세션 동시 종료 = `total_exercise_time += A(10)`, `+= B(20)`, 기댓값 **30**.

    | 전략 | 방식 | 최종값 | 손실 | 락 비용 |
    |---|---|---|---|---|
    | ❌ naive RMW (RC) | `SELECT @x → SLEEP → UPDATE=@x+v` | **10 or 20** | **유실(commit 순서 따라 10/20)** | 없음(둘 다 0 읽음) |
    | ✅ 원자 UPDATE | `SET x = x + v` | **30** | 없음 | UPDATE 1문장 X락 직렬화 |
    | ✅ 비관락 | `SELECT … FOR UPDATE` | **30** | 없음 | **트랜잭션 내내 X락 점유(블로킹)** |
    | ✅ 낙관락 CAS | `… WHERE id=? AND version=@v` | **30** | 없음 | 블로킹 0, **충돌 시 재시도 1회** |

  - **재현 안정성**: 3회 반복 모두 naive 는 유실(10 또는 20), 세 방지책 모두 30 복구. CAS 는 매 run **정확히 1 worker 충돌→재시도 2회차 성공**(version 0→1→2).
  - **락 증거(`performance_schema.data_locks`, B 가 FOR UPDATE 대기 중 스냅샷)**: 양 trx 가 테이블 `IX`(의도) GRANTED + 동일 레코드(PK=1)에 trx A `X,REC_NOT_GAP **GRANTED**` / trx B `X,REC_NOT_GAP **WAITING**` — 비관락이 단일 핫로우를 **직렬화**함을 그대로 관찰. naive·원자·CAS 경로엔 이 WAITING 이 없음(블로킹 무).
  - **해석**: RC 격리만으론 read-modify-write 의 lost-update 를 **못 막는다**(MVCC 스냅샷 읽기라 둘 다 옛값). 막으려면 ① 읽기 자체를 없애거나(원자 UPDATE) ② 읽기에 락을 걸거나(FOR UPDATE) ③ 쓰기에 버전 가드(CAS). **단일 카운터 누적이면 원자 UPDATE 가 최적**(왕복·블로킹 0). `@Version` 은 충돌이 드물고 블로킹을 피하고 싶을 때(이 프로젝트의 타임아웃 vs 콜백 경합).
  - rig: `loadtest/measure_lock.sh`(재현, 4단계 + 락 스냅샷). 🟡 갭/넥스트키 락은 단일 PK 핫로우라 미관찰 — append-only 저경합 substrate 한계, 명시.
- **(a) 낙관락 양보(실코드) 근거**: 타임아웃 스케줄러 vs FastAPI 콜백은 **저경합·서로 다른 세션 위주**라 비관락의 상시 블로킹 비용이 아깝다 → `@Version` 으로 충돌만 감지, 충돌 시 콜백이 재시도(`SessionService.completeSession` 최대 3회, FastAPI 결과 우선). 위 (b) 의 CAS 가 바로 이 정책의 축소 재현.
  - **✅ 반사실 실측(2026-07-16, 로컬 도커 MySQL, `users` PK 행 대상)**: "만약 세션 완료에도 비관락(`FOR UPDATE`)을 썼다면"을 직접 재현 — 트랜잭션 A가 5초간 행을 잠그고, 그 사이 트랜잭션 B가 `innodb_lock_wait_timeout=2`로 같은 행을 잠그려 시도 → **2.831초 만에 `ERROR 1205 Lock wait timeout exceeded`**로 실패(2초 타임아웃+클라이언트 왕복 오버헤드). 대조: 실제로 쓰는 낙관락(CAS)은 위 (b) 표에서 이미 확인했듯 **블로킹 0, 충돌 시 즉시 재시도** — "비관락은 최악의 경우 초 단위로 대기하다 에러까지 날 수 있는데, 낙관락은 그 자리에서 바로 재시도할 수 있다"는 게 추론이 아니라 실측 대조로 확인됨.
  - **✅ `data_locks`로 레코드 락(갭 없음) 실물 확인(2026-07-16)**: `findByIdForUpdate`가 쓰는 PK 등치 조건(`WHERE id=?`)의 락 범위를 직접 관찰 — 두 트랜잭션이 같은 회원 행을 동시에 `FOR UPDATE`로 잠그려 하면 `performance_schema.data_locks`에 `RECORD X,REC_NOT_GAP GRANTED`(LOCK_DATA=해당 PK) / `RECORD X,REC_NOT_GAP WAITING`(같은 LOCK_DATA)만 찍힘 — **갭 락 없이 그 행 하나만** 잠그는 것을 실물로 확인(PK 등치 조회는 레인지가 아니라서 예상대로).
  - rig: `loadtest/measure_lock_timeout.sh`(재현, 로컬 도커 MySQL — 동시성 메커니즘 실험이라 AWS 불필요, ①②를 하나의 스크립트로 재현).
- **면접**: "RC로는 lost-update 못 막음(MVCC 스냅샷이라 둘 다 옛값 읽음) → 단일 카운터 누적은 **원자 UPDATE**가 최적(왕복·블로킹 0). `data_locks`로 FOR UPDATE의 X,REC_NOT_GAP WAITING을 직접 관찰해 비관락이 핫로우를 직렬화함을 확인. 타임아웃 vs 콜백은 저경합·세션 분리라 낙관락(@Version)으로 블로킹 비용 회피, 충돌 시 3회 재시도. 회원당 활성 세션 제약은 처음엔 유니크 제약(DB가 모든 경로에서 보장)으로 막으려 했는데, `member_id`가 CASCADE FK라 MySQL이 generated column을 못 쓰게 막아서 — 세션 생성이 핫패스가 아니라는 점 근거로 `SELECT FOR UPDATE`(이 프로젝트 첫 비관락)로 대체, 콘커런시 테스트로 정확히 1개만 성공함을 검증. 이 비관락(PK 등치 조건)이 실제로 레코드 락만 걸고 갭은 안 거는지, 그리고 '만약 세션 완료도 비관락이었다면' 최악의 경우 lock wait timeout으로 몇 초 만에 에러가 나는지(2.831초)까지 `data_locks`·반사실 재현으로 실측 확인." 🟡 갭/넥스트키 락은 단일 PK 핫로우라 미관찰 — 명시.

### ④ Ch.4 + Ch.15 — 아키텍처(MVCC·버퍼풀) & JSON 🟢🟡
- **개념**: InnoDB 버퍼풀, undo, MVCC 스냅샷 / JSON 저장·함수·generated column 인덱스.
- **설계 (MVCC)**: 긴 batch insert 트랜잭션 open 중 동시 리포트 조회가 **이전 스냅샷** 읽음(RR) 관찰 — gRPC 적재↔조회 사이 일관성.
- **설계 (버퍼풀)**: 1억 행 > `innodb_buffer_pool_size`면 디스크 읽기 급증 — hit율(`sys`) 관찰, 버퍼풀 사이징 영향. ⚠️ **대상은 세션 리포트 조회가 아니라 cross-session 집계**(admin 통계·패턴분석, `SELECT SUM(sync_rate) ... WHERE created_at BETWEEN`류 — `measure_bufferpool.sh`) — 개인 리포트는 precompute로 `reports` 한 행만 보므로 `pose_data` 크기와 무관하게 안 느려짐(§5 캐비엇 "대용량 쿼리 저하"와 동일 구분).
- **설계 (JSON)**: `joint_coordinates` 저장 비용 측정, JSON path에 **generated column + 인덱스** vs 정규화 테이블 트레이드오프. 🟡 (현재 불필요, 데모용).
- **설계 (트림 33→13)** 🔶: `_landmarks_to_json`이 MediaPipe **33개 전부 저장**(필터 없음, `constants.py LANDMARK`는 13개만 참조) → JSON 함수로 사용 13개만 추출해 `AVG(LENGTH)` before/after 측정(33→13 ≈ ~60% 절감). **단 이건 국소 미봉책** — 진짜 해법은 [`db-deep-dive §2-0`](./db-deep-dive.md)의 raw→buffer/serving 분리(raw 단기화하면 트림 영구비용 자체가 사라짐).
- **결과 (트림 33→13) ✅ (2026-06-07, `_pose_template` 현실 JSON)**:
  - `joint_coordinates` 평균 **2,344 B → 916 B (−60.9%)** — 33개 landmark 중 사용 13개(`0,11~16,23~28`)만 `JSON_ARRAY(JSON_EXTRACT(...))`로 남긴 결과. 구조적 절감이라 값 균일과 무관하게 유효.
  - **키는 못 줄임(천장)**: `z`(angle_calculator 3D 각도)·`visibility`(squat_analyzer 추적신뢰도) 둘 다 실사용 → `x,y`만 남기는 추가 트림은 분석이 깨짐. 13 landmark × 4 key 가 정직한 상한.
  - ⚠️ rig JSON 은 `{"landmarks":[{x,y,z,visibility}×33]}`(index 필드 없음)인데 실코드 `_landmarks_to_json`(`pose.py:93`)은 `[{index,x,y,z,visibility}]`라 index 키만큼 더 큼 → **실제 절감폭 ≥ 60.9%**. 진짜 수정위치는 `pose.py:93`(이번 미변경, ai-server 별도 결정).
  - 스케일 투영: `pose_data` 412만 행 ~10.7GB → 트림 시 ~40%대. DAU 1,000 1억 행이면 ~234GB → ~92GB. **단 국소 미봉책** — 진짜 해법은 raw→buffer/serving 분리([`db-deep-dive §2-0`](./db-deep-dive.md)).
- **결과 (MVCC) ✅ (2026-06-07, scratch `mvcc_lab` 100행, `measure_mvcc.sh`)**:
  - **RR(기본)**: Reader 트랜잭션 첫 SELECT=100 → 그 사이 Writer 가 50행 INSERT+COMMIT → 같은 트랜잭션 재SELECT **여전히 100**(스냅샷 고정) → COMMIT 후 **150**. 일관된 읽기(consistent read) 입증.
  - **RC 대비**: 같은 시나리오에서 재SELECT가 **150**(매 문장 새 스냅샷). RR↔RC 차이를 직접 관찰.
  - **읽기↔쓰기 비블로킹(RR)**: Reader 가 3초 트랜잭션 점유 중에도 Writer INSERT+COMMIT 이 **461ms** 에 완료(안 막힘). `performance_schema.data_locks` **0행** — 일반 SELECT 는 락 없이 undo 로 과거 버전 재구성.
  - **SERIALIZABLE 대비**: 같은 시나리오를 SERIALIZABLE 로 돌리면 일반 SELECT 가 암묵적 잠금읽기(S 넥스트키락)가 됨 → `data_locks` 에 `RECORD S GRANTED 101`(100행+supremum), Writer INSERT 가 **1,998ms** 블로킹(RR 461ms 의 4배). **읽기가 락을 잡아 직렬화 = MVCC 비블로킹의 반대편.** 단일 카운터엔 과함(원자 UPDATE/낙관락이 답) — ③ 락 실험과 연결.
  - 실코드 매핑(**DAU 1,000 가정**): 사용자 A의 라이브 세션이 `pose_data`/`exercise_sessions`에 적재 중일 때, 사용자 B의 주간/월간 요약(`findByMemberIdAndStartTimeBetween`, 범위 조회)이 같은 테이블에 동시 진입 — RR 스냅샷이 그 조회를 대기 없이 일관되게 보장(단일 세션은 쓰기→읽기 순차, 동시성은 교차 사용자·테이블 수준). ⚠️ "1,000 동시부하 TPS 실측"은 주장 안 함 — 메커니즘·일관성 입증이지 처리량 자랑 아님(§5 throughput 캐비엇).
- **결과 (버퍼풀) ✅ (2026-06-07, `pose_data_scale` 8,256만 행/10.4GB vs 풀 128MB, `measure_bufferpool.sh`)**:
  - **작업셋 ≫ 풀**: 월 파티션 풀스캔(540MB ≫ 128MB) → cold/warm 모두 **매번 ~485MB 디스크 읽기**, hit ~84% 고정, ~10초. 작업셋이 풀을 초과해 **warm 재실행도 캐시 무력**(eviction 으로 재miss).
  - **작업셋 < 풀**: 좁은 PK 범위(수MB) → warm **디스크 0MB, hit 100%**, ~0.45초. 핫 데이터 메모리 상주. (⚠️ 이 구간 cold 도 직전 실행 잔여로 이미 resident — 진짜 cold 아님, 명시)
  - **⭐ read-ahead 함정**: 순차 스캔은 InnoDB 선읽기(`Innodb_buffer_pool_read_ahead`)가 페이지를 비동기로 당겨와 **표준 hit율 공식(1−`reads`/`read_requests`)이 거짓 99.9%** 가 됨(`reads`는 동기 미스만 카운트). 진짜 물리 I/O = `reads`+`read_ahead`, 또는 `Innodb_data_read`(바이트)로 봐야 84%/485MB 가 보임. **"hit율 공식은 read-ahead 가 가린다"** 가 핵심 교훈.
    - **계산식 명시**: 위 84%는 naive 공식이 아니라 **corrected 공식** `hit% = (1 − (reads+read_ahead)/read_requests) × 100`(`measure_bufferpool.sh`의 `hit=(1-$phys/$drq)*100`, `phys=drd+dra`와 동일)으로 계산한 값. 이 실행의 naive 공식(`1−reads/read_requests`) 원시 카운터는 로그로 안 남겨 재구성 불가 — 같은 대조를 raw 값까지 포함해 명확히 보여주는 건 아래 AWS 재검증(95.36% naive vs 변화 없는 `data_read`)쪽 참고.
  - 같은 뿌리: 페이지네이션 cold/warm(②c §3)·시딩 가속(버퍼풀 128MB→2GB)·라우팅 모두 "작업셋 vs 메모리". 사이징 판단 = ⓐraw 단기화·파티션 만료로 작업셋 축소 ⓑ풀 증설.
  - **✅ AWS 1억 행 real-JSON 재검증(2026-07-15)**: 기존 실험은 더미 JSON(`{}`, 2바이트)이라 off-page 저장 자체가 발동 안 했을 가능성이 있어, `pose_data_real_scale`(진짜 2.3KB JSON, 버퍼풀 2GB, m6i.xlarge)에서 재현.
    - **작업셋 ≫ 풀(세션 70000~75000, 5,000세션, 실제 JSON 합 8.19GB)**: cold **675.2초**, warm(같은 범위 즉시 재실행) **675.85초** — **거의 동일, warm 이득 0**. `data_read` 델타도 cold 12.79GB vs warm 12.78GB로 동일 — 작업셋이 풀(2GB)의 4배가 넘어 캐시가 전혀 안 먹힘.
    - **read-ahead 함정 재확인**: warm 구간의 naive hit율 공식 `1-reads/read_requests` = 1-779,614/16,804,676 = **95.36%**(그럴듯해 보임) — 근데 실제 물리 I/O(`data_read`)는 cold·warm 둘 다 ~11.9GB로 **하나도 안 줄어듦**. 공식만 보면 "잘 캐시되고 있다"고 착각하기 딱 좋은 사례.
    - **작업셋 < 풀(세션 80000~80010, 11세션, 19.3MB)**: cold **1.557초**(reads=1740, data_read=27.2MB) → warm **0.461초**(reads=**0**, data_read=**0바이트**, 3.4배) — **완전한 캐시 히트**, 진짜 cold부터 시작한 깨끗한 대조.
    - **결론 강화**: "작업셋 vs 버퍼풀 비율"이 진짜 원인이라는 게 더미 데이터가 아니라 실제 off-page JSON에서도 그대로 재현됨 — 오히려 실제 JSON이 더 무거워서(off-page 랜덤 I/O) 대조가 더 극적으로 나타남(작은 쪽 3.4배 vs 큰 쪽 0배).
    - ⚠️ **675.2초를 절대 성능 기준으로 일반화 금지**: 이 숫자는 "MySQL/이 시스템이 원래 이 정도 느리다"는 성능 주장이 아니라, 캐시 무력화를 보여주려고 일부러 만든 최악의 케이스(5,000세션·375만 행·8.19GB를 단일 쿼리로 통째 스캔, 병렬화 없음)의 결과. 요점은 절대값이 아니라 **cold(675.2초) vs warm(675.85초)의 델타 = 0**이라는 상대 비교. 이런 무거운 cross-session 집계는 애초에 즉석 동기 조회가 아니라 배치 사전계산이나 별도 분석 경로(S3/데이터레이크, §0)로 빼야 한다는 근거로만 쓸 것 — "우리 쿼리는 11분 걸린다"는 헤드라인으로 오버클레임 금지.
- **결과 (generated column) ⬜ 미수행 결정**: ⓐ프로젝트 실수요 0(JSON 내부값으로 검색 안 함, `sync_rate`는 별도 컬럼) ⓑ합성 rig 가 단일 템플릿 복제라 **값 분포(카디널리티) 균일**(`_pose_template` 750행 `d_whole_json=1`) → 선택도 실험 불가. **세 번째 축(값 분포) 한계**로 정직 박제(아래 §5 캐비엇). ⑤ 옵티마이저도 동일 제약.
- **면접**: "MVCC 덕에 적재 트랜잭션이 조회를 블로킹 안 함(RR 스냅샷, data_locks 0). 트림은 33→13 으로 −60.9%지만 z·visibility 실사용이라 키는 못 줄임, 진짜 해법은 raw 를 OLTP 에서 빼는 것. 버퍼풀은 **작업셋 vs 메모리** — 540MB 스캔이 128MB 풀에선 warm 도 매번 485MB 재읽기, 그리고 **read-ahead 때문에 표준 hit율 공식이 거짓 99% 라 `data_read` 바이트로 봐야 한다**. AWS로 진짜 2.3KB JSON·1억 행에서도 재확인 — 8.6GB 작업셋은 warm 해도 675초 그대로(데이터 읽기 12.8GB 불변), 19MB 작업셋은 warm 시 0.46초·디스크 0바이트로 완전 대조."

### ⑤ Ch.9 — 옵티마이저·힌트 🟢
- **개념**: 통계정보, 비용기반 최적화, 조인 알고리즘(8.0 hash join), 힌트.
- **이 프로젝트**: `pose_data_real_scale`(AWS 1억 행 real-JSON)에서 `session_id` 선택도에 따른 인덱스 vs 풀스캔 전환 — `reports ⋈ sessions ⋈ users` 3-way 조인(hash join 관찰)은 그 테이블들이 AWS엔 비어있어 별도 시딩 필요, 이번엔 범위 밖(단일 테이블 선택도 실험으로 축소).
- **설계**: `session_id BETWEEN 1 AND N`의 N을 좁게(세션 1개)→넓게(세션 10만+)로 늘려가며 `EXPLAIN` — `type`이 `range`(idx_session_timestamp)에서 `ALL`(풀스캔)로 바뀌는 선택도 임계값 관찰, 실제 실행시간으로 그 전환이 진짜 비용에 부합하는지 검증.
- **결과 ✅ (2026-07-16, AWS `pose_data_real_scale` 1억 행, `idx_session_timestamp(session_id, timestamp_sec)`)**:

  | session_id 범위(1~N) | 세션 수 | type | rows(추정) | filtered |
  |---|---|---|---|---|
  | 1 | 1 | ref | 750 | 100% |
  | 100 | 100 | range | 157,650 | 100% |
  | 1,000 | 1,000 | range | 1,544,918 | 100% |
  | 10,000 | 10,000 | range | 15,502,716 | 100% |
  | 11,000~15,000 | 11,000~15,000 | range | ~1,675만~2,492만 | 100% |
  | **16,000** | 16,000 | **ALL** | 79,825,338 | 34.81% |
  | 17,000 | 17,000 | range(!) | 2,608만 | 100% |
  | 18,000+ | 18,000+ | **ALL** | 79,825,338 | ~36~50% |
  | 20,000~133,334 | 20,000+ | ALL | 79,825,338 | 40~50% |

  - **크로스오버는 15,000~18,000세션(전체 133,334세션의 11~13%) 사이** — 딱 한 지점이 아니라 16,000에서 ALL로 넘어갔다가 17,000에서 다시 range로 돌아오는 등 **좁은 구간에서 흔들림**. 두 플랜의 비용 추정치가 그 근방에서 거의 같아서, 통계(`ANALYZE TABLE` 샘플링) 오차가 선택을 좌우하는 "아슬아슬한 경계" 구간임을 그대로 보여줌 — 깔끔한 이분점이 아니라는 것 자체가 실측 포인트.
  - **실제 실행시간으로 크로스오버 검증**: `session_id BETWEEN 1 AND 10000`(자연 선택: range, 750만 행 북마크 룩업) 실행 **919.7초**. 이미 측정된 풀스캔 비용(1억 행, 2,120.9초, §4 ②b)과 결합해 행당 비용 역산 — range(북마크 룩업) **122.6μs/행** vs 풀스캔(순차) **21.2μs/행**(range가 **5.8배** 비쌈, 랜덤 I/O 대가). 이 두 행당 비용으로 "몇 행부터 풀스캔이 더 싸지는가"를 역산하면 **약 1,730만 행(≈23,067세션)** — 실제 EXPLAIN 전환 지점(15,000~18,000세션)과 **거의 일치**. 옵티마이저의 선택이 감이 아니라 실측 가능한 비용 모델에 부합함을 확인.
  - rig: `loadtest/measure_optimizer.sh`(재현 — 위 EXPLAIN 스윕 + 타이밍).
- **면접**: "session_id 범위를 넓혀가며 EXPLAIN을 보면 15,000~18,000세션 근방에서 range→풀스캔으로 전환되는데, 이게 감이 아니라 실측: range는 북마크 룩업이라 행당 122.6μs, 풀스캔은 순차라 21.2μs — 5.8배 차이를 그대로 역산하면 크로스오버가 약 23,000세션으로 나오고, 이게 실제 EXPLAIN 전환 지점과 거의 일치한다. 옵티마이저가 통계 기반으로 계산한 비용이 실측 비용과 맞아떨어짐을 확인했다."

### 선택 — Ch.6 압축 🟡
- `pose_data` JSON 시계열은 압축률 높음 → InnoDB page compression 적용 시 스토리지·I-O 트레이드오프 측정. niche, 여유 시.

---

## 5. 정직 캐비엇 (오버셀 금지)

| 항목 | 캐비엇 |
|---|---|
| Ch.16·17 복제·클러스터 | 🔴 **substrate 없음** — 규모상 실수요 0. "개념 학습 + 왜 미적용인지"로만. 가짜 replication 데모 금지 |
| Ch.5 갭/넥스트키 락 | 🟡 append-only 저경합이라 일부 **합성 구간** — 명시 |
| 합성데이터 **값 분포** | 🟡 rig 가 단일 템플릿 복제라 **카디널리티 균일**(`d_whole_json=1`). 행수·payload(크기)는 진짜지만 값 분포는 가짜 → 분포 의존 실험(generated column 선택도·옵티마이저 카디널리티)은 **미수행**. "구조 실험만 골라 돌렸다"가 정직 |
| 버퍼풀 hit율 | 🟡 표준 공식(1−`reads`/`req`)은 **read-ahead 가 가려 거짓 99%** — 순차 스캔은 `read_ahead`+`Innodb_data_read`(바이트)로 봐야 함(§4 ④) |
| 옵티마이저 크로스오버 지점 | 🟡 15,000~18,000세션 구간은 **깔끔한 이분점이 아니라 흔들림**(16,000→ALL, 17,000→range, 18,000+→ALL) — 두 플랜 비용 추정치가 그 근방에서 거의 같아 통계(`ANALYZE TABLE` 샘플링) 오차가 선택을 좌우함. "여기서 정확히 전환된다"가 아니라 "이 근방(11~13%)에서 전환된다"가 정직한 표현(§4 ⑤). **절대 세션 수(15,000~18,000)도 이 테이블이 지금 133,334세션이라 나온 값** — 본질은 비율(~11~13%)이고, 테이블이 커지면 절대 수치도 비례해 커질 것으로 추정되나 실측·확인은 안 함 |
| Ch.8 인덱스 | "추가로 빨라짐" ❌ → "**이미 최적임을 측정으로 발견**"이 정직한 헤드라인(§4.3) |
| 대용량 쿼리 저하 | 세션 리포트는 안 느려짐. **cross-session 집계**에서만 성립 |
| throughput | DAU 작음(§4.2) — 처리량 자랑 ❌, 데이터량·정합성 축 |

---

## 6. 우선순위 · 일정 (방학 배치, [`26-plan`](../tasks/26-vacation-semester2-portfolio-plan.md))

| 순서 | 실험 | 시점 |
|---|---|---|
| ⓪ | Ch.18 PS 셋업 (측정 백본) | 6월 초 |
| ① | Ch.8+10 인덱스·EXPLAIN | 6월 |
| ② | Ch.11+13 projection·파티션·DROP PARTITION | 6~7월 |
| ③ | Ch.5 lost-update·격리·락 | 7월 |
| ④⑤ | Ch.4·15 MVCC·JSON / Ch.9 옵티마이저 | 7~8월 |

> 선결: 1억 row 합성 시딩(§3) 먼저.

---

## 7. 관련 문서
- [`./db-deep-dive.md`](./db-deep-dive.md) — DB 깊이 (시계열·정합성·격리수준)
- [`./problem-solving-log.md`](./problem-solving-log.md) — 문제해결 카드
- [`../decisions/load-test-strategy.md`](../decisions/load-test-strategy.md) — 부하 측정·R·천장
- [`../tasks/26-vacation-semester2-portfolio-plan.md`](../tasks/26-vacation-semester2-portfolio-plan.md) — 방학~2학기 배분
