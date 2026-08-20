# MySQL vs PostgreSQL — 이 프로젝트에서 무엇이 결정하는가

작성일: 2026-08-11
상태: **분석/추천** — 새로 확정한 것 없음. 현행(MySQL) 유지가 권고이고, 그 근거를 «자산» 에서 «조건» 으로 다시 세운다 ([[feedback_user_decides_not_claude]])
대상: "왜 MySQL이냐" 에 답이 필요한 모든 자리 — 면접, 포폴 한 장, 그리고 앞으로 같은 질문이 다시 왔을 때
연관: [`./db-portfolio-roadmap.md §11-1`](./db-portfolio-roadmap.md)(이 문서로 이관), [`./project-destination-and-exit-criteria.md`](./project-destination-and-exit-criteria.md) E4, [`../portfolio/realmysql-experiments.md`](../portfolio/realmysql-experiments.md)

---

## 0. 이 문서가 닫는 것

기존 §11-1(2026-07-05)은 **"엔진 우위 주장 3개가 전부 과장이었다"** 까지 갔고, 거기서 멈췄다.
그 자리에 남은 답이 *"이미 MySQL로 재놨으니까"* 였는데, 이건 **면접에서 한 번 더 밀면 무너진다** —
"자산이 없었으면 뭘 고르셨겠어요?" 에 답이 없기 때문이다.

이 문서는 **자산을 빼고** 다시 세운다. 결론은 안 바뀌지만 **근거가 바뀐다.**

---

## 1. 결론 4줄

1. **기술적으로는 무승부에 가깝다.** 이 워크로드가 MySQL에 특별히 맞아서가 아니다.
2. **greenfield 였다면 PostgreSQL을 골랐을 것이다** — 관측·PITR·VACUUM 축이 잴 표면을 더 준다.
3. **지금 이 조건(운영자 1명 · 마감 · 이미 도는 시연)에서는 MySQL이 맞다.**
4. **MySQL을 지지하는 진짜 근거는 딱 둘 — 전환 비용과 운영 여력.** "현업에서 많이 써서"는 아니다.

---

## 2. 엔진 우위 주장 3개 — 전부 과장이었다 (§11-1 에서 이관)

| 과장했던 주장 | 반증 |
|---|---|
| InnoDB 클러스터드 인덱스가 시계열 append 에 유리 | 절반만 맞음. `pose_data.id` AUTO_INCREMENT(단조 PK)로 InnoDB 특유의 함정을 피한 것뿐. PostgreSQL 힙 테이블은 PK 값과 무관하게 자연 append 라 애초에 이 함정이 없다 → 무승부 |
| 파티션 `DROP` TTL 패턴이 MySQL 에 맞음 | 자체 실측([`realmysql-experiments.md:116`](../portfolio/realmysql-experiments.md))이 이미 반증 — `session_id=` 조회는 pruning 이득 **0**. 파티셔닝의 값은 보존정책(`DROP PARTITION` O(1))인데 PG 선언적 파티셔닝의 `DETACH`+`DROP` 도 동일하게 O(1) |
| JSON 오프페이지 회피(projection −98.7%, [조건](../portfolio/realmysql-experiments.md#projection-98-7))가 MySQL 강점 | InnoDB off-page 저장과 PostgreSQL **TOAST** 가 원리상 동일. 큰 가변길이 컬럼을 다루는 RDBMS 의 일반 원리지 엔진 차별점이 아니다 |

**면접에서 엔진 자체의 기술적 필연성을 주장하지 말 것.**

---

## 3. 흔히 도는 분류표를 이 프로젝트에 대보면

> *"읽기위주 + 단순스키마 + CDC필요 = MySQL / 복잡한 도메인 + JSON + 분석쿼리 = PostgreSQL"*

틀린 표는 아니지만 **6칸의 근거 수준이 제각각이고, 이 프로젝트는 어느 칸에도 세게 안 걸린다.**

| 칸 | 채점 | 알맹이 |
|---|---|---|
| 읽기위주 = MySQL | **거의 유물** | MyISAM 시절의 잔향. 파고들면 못 버틴다 |
| 단순스키마 = MySQL | **방향이 반대** | MySQL 의 장점이 아니라 **PG 장점이 안 아쉬운 조건**이다 |
| CDC = MySQL | **셋 중 제일 튼튼** | binlog 가 서버 단위 순서 로그로 1급. PG logical decoding 은 replication slot 이 밀리면 WAL 이 쌓인다 |
| JSON = PG | 참 | `jsonb` + GIN. MySQL 은 generated column 또는 multi-valued index 우회 |
| 분석쿼리 = PG | 참 | 병렬 쿼리 · partial/expression 인덱스 · BRIN · hash join 성숙도 |
| 복잡한 도메인 = PG | 참 | 타입 시스템 · exclusion constraint · **트랜잭셔널 DDL** |

**PG 쪽 세 칸이 이름만 해당하고 실질이 비어 있다:**

- **JSON 칸** — 코드베이스 전체에 JSON path 질의가 **0건**(`JSON_EXTRACT`/`JSON_TABLE`/`->>` 없음, 2026-08-11 확인).
  `joint_coordinates` 는 통째 저장·통째 조회이고, projection −98.7%([조건](../portfolio/realmysql-experiments.md#projection-98-7)) 의 승리 방식은 **아예 안 읽는 것**이었다.
  즉 이 프로젝트의 JSON 컬럼은 구조화 데이터가 아니라 사실상 **BLOB** 이라 jsonb/GIN 이 이길 표면이 없다.
- **분석쿼리 칸** — 실제 집계는 [`SessionFeedbackLogRepository.java:30`](../../backend/src/main/java/com/shadowfit/repository/exercise/SessionFeedbackLogRepository.java) ·
  [`PoseDataRepository.java:58`](../../backend/src/main/java/com/shadowfit/repository/exercise/PoseDataRepository.java) 처럼
  `session_id =` 로 좁힌 뒤 `COUNT`/`AVG` + `GROUP BY` 다. 윈도우 함수도 재귀 CTE 도 넓은 스캔도 없다.
- **CDC 칸** — 현재 **0**. outbox 까지만 갔고 Debezium 은
  [`outbox-reliable-messaging.md:125`](./outbox-reliable-messaging.md) 에서 «outbox 가 공급하는 하류» 로 언급만 돼 있다.
  ⚠️ 다만 **앞으로 걸릴 수 있는 유일한 칸**이다 — E3 «복제 축» 을 측정으로 열면 binlog 가 실습 난이도를 낮춘다.

---

## 4. 이 프로젝트의 워크로드 모양 — 쓰기 주도

엔드포인트 수는 거의 반반(GET 13 / 쓰기 18)이지만 **엔드포인트 수는 틀린 잣대다.**

| | 사실 | 근거 |
|---|---|---|
| 볼륨 | `pose_data` 는 **프레임당 1행**, rep 당 5~30행 | [`pose-ingest-downsampling.md:30`](./pose-ingest-downsampling.md) |
| 읽기 | 그걸 읽는 건 리포트 조회 **세션당 1회**, `GROUP BY` 집계 한 방 | 위 리포지터리 |
| 실측 병목 | **DB INSERT** (batch 로 throughput +99%, [조건](./load-test-strategy.md#batch-insert-99)) | `realmysql-experiments` ②a |
| 핫 행 | `exercise_sessions` 가 rep 마다 `totalReps`·`avgSyncRate`·`version`·`lastActiveAt` 갱신 | [`Session.java`](../../backend/src/main/java/com/shadowfit/model/exercise/Session.java) |

> **가장 많이 쓴 데이터가 가장 적게 읽힌다** — 이 한 줄이 이 제품의 모양이다.

사용자가 보는 화면은 전부 읽기(리포트·캘린더·주간요약)라 **UX 는 읽기 주도, 부하는 쓰기 주도**로 축이 갈린다.

**되먹임**: PG 가 이기는 자리(옵티마이저·인덱스 표현력)는 **작은 테이블**에서 작동하고,
MySQL 이 이기는 자리(핫 UPDATE·vacuum 없음)는 **큰 테이블·실제 병목**에서 작동한다.

---

## 5. 파트별 판정

| 파트 | 유리 | 크기 | 왜 |
|---|---|:--:|---|
| 세션 행 반복 UPDATE | **MySQL** | 큼 | InnoDB in-place vs PG 새 튜플. HOT update 가 완화하지만 **fillfactor 여유가 있어야 걸리고 기본값은 100(여유 0)** — 즉 튜닝해야 겨우 동등. 세션 종료 시 `status` 변경은 인덱스 컬럼(`idx_session_member_status_start`)이라 HOT 이 깨진다 |
| 커넥션 모델 | **MySQL** | 큼 | PG 는 커넥션이 늘면 PgBouncer 가 사실상 필수 부품 |
| 조회의 예측 가능성 | **MySQL** | 중 | PG index-only scan 은 visibility map 의존 → **쓰기 잦은 테이블에서 같은 쿼리가 시점 따라 다르다.** MySQL 은 느릴 때도 예측 가능하게 느리다 |
| binlog | **MySQL** | 중 | 복제·PITR·CDC·`gh-ost` 가 메커니즘 하나 위에 올라탄다. 배울 것도 관리할 것도 하나 |
| `pose_data` 대량 append | 무승부 | — | 양쪽 다 쓰기 증폭이 있고 모양만 다르다(doublewrite vs full_page_writes) |
| 파티션 TTL `DROP` | 무승부 | — | §2 참조 |
| outbox 폴링 | 무승부 | 작음 | `FOR UPDATE SKIP LOCKED` 양쪽 다 있음 |
| 실행계획 관측 | **PostgreSQL** | 큼 | `EXPLAIN (ANALYZE, BUFFERS)` 가 추정 대 실제·페이지 히트를 한 번에. MySQL 은 `Handler_*`·`innodb_buffer_pool_reads` 를 **걷어서 조립**해야 한다 |
| 인덱스 표현력 | **PostgreSQL** | 큼 | partial · expression · `INCLUDE` 커버링 · **BRIN**(1억 행 시계열 컬럼에 쓰면 인덱스가 GB→KB). 대안 없음 |
| 백업 / PITR | **PostgreSQL** | 큼 | `pg_basebackup` + WAL 아카이빙이 **내장·1급**. MySQL 은 XtraBackup(서드파티) 또는 `mysqldump`(느림) |
| 스키마 마이그레이션 | **PostgreSQL** | 중 | `CREATE INDEX CONCURRENTLY` 내장, `ADD COLUMN` 기본값이 메타데이터 연산, **트랜잭셔널 DDL** |
| 파티션 전환 DDL | **MySQL** | 중 | PG 에는 `ALTER TABLE ... PARTITION BY` 가 **아예 없다** — 96분([조건](../portfolio/realmysql-experiments.md#drop-partition-625x))짜리 그 변환이 «손으로 짠 이전 절차» 가 된다 |
| 메모리 발자국(idle) | **PostgreSQL** | 중 | MySQL 8 은 `performance_schema` 때문에 컨테이너 idle 이 무겁다. PG 의 커넥션당 프로세스 약점은 풀 5~9 에서 안 걸린다 |
| 배포·비용 생태계 | **PostgreSQL** | **큼** | Neon·Supabase 등 무료/서버리스 티어. **DB 를 박스 밖으로 뺄 싼 경로** |

---

## 6. 양방향 포기 목록

### MySQL 을 택하면

| 축 | 포기 |
|---|---|
| **백엔드(쓰기·동시성)** | **사실상 없음** — 병목이 INSERT 와 핫 행 UPDATE 인데 둘 다 손해 보는 자리가 아니다 |
| **DBA** | 내장 PITR · `EXPLAIN (ANALYZE, BUFFERS)` · 트랜잭셔널 DDL · 인덱스 표현력(BRIN 등) · VACUUM/bloat 축 |
| **프로덕트** | 서버리스 무료 티어(→ 박스에서 DB 를 뺄 싼 길이 막힘) · `jsonb`+GIN(미래를 닫음) · `pgvector` |

트랜잭셔널 DDL 포기의 **증거가 레포에 있다** — [`2026-08-07-consolidate-session-member-indexes.sql:84-87`](../../mysql/migrations/2026-08-07-consolidate-session-member-indexes.sql) 의 손으로 쓴 롤백 블록이 정확히 그 비용이다.

### PostgreSQL 을 택하면

| 축 | 포기 |
|---|---|
| **백엔드(쓰기·동시성)** | **여기서 대부분** — 핫 행 UPDATE 무손실성 · autovacuum 상시 관리 · 조회의 단조로움 · 부품(PgBouncer) · XID wraparound |
| **DDL·파티션** | `ALTER TABLE ... PARTITION BY` 부재 → 비교 대상이 «서버» 가 아니라 «내 코드» 가 된다. ACCESS EXCLUSIVE 가 긴 조회 뒤에 줄 서면 그 뒤 전부를 막아 `lock_timeout`+재시도가 필수 관행 |
| **운영·생태계** | `mysqld-exporter` → `postgres_exporter` 대시보드 재작성 · `pt-osc`/`gh-ost` 대신 `pgroll`/`pg_repack`(자료 양이 다름) · 막혔을 때 물어볼 사람 |
| **프로덕트** | **거의 없음. 오히려 얻는 쪽** |

⚠️ `pose_data` 는 TTL 이 `DROP PARTITION` 이라 **vacuum 과 무관**하다. PG 의 vacuum 부담은 세션 테이블 쪽에 집중된다.

### 두 목록은 성격이 다르다

| | 대가의 성격 |
|---|---|
| MySQL 의 포기 | 대부분 **일회성 구축** — XtraBackup 붙이고, 롤백 스크립트 쓰고, 카운터 조립 쿼리 만들면 끝 |
| PostgreSQL 의 포기 | 대부분 **지속적 주의** — autovacuum·fillfactor·락 큐·슬롯을 계속 봐야 함 |

**운영자가 1명이고 마감이 있으면 계속 내는 쪽이 더 비싸다.** 운영자가 여럿이거나 오래 갈 제품이면 뒤집힌다.

---

## 7. 축 넷 — 무엇이 무엇을 결정하나

| 축 | 묻는 것 | 이기는 쪽 |
|---|---|---|
| 포폴/DBA | 뭘 배우고 보여주나 | **PostgreSQL** |
| 프로덕트 | 배포·비용·메모리·백업 | **PostgreSQL** (매니지드 전제) |
| 운영 여력 | 1인이 유지 가능한가 | **MySQL** — 단 매니지드면 상당 부분 무력화 |
| 전환 비용 | 지금 바꾸면 뭘 잃나 | **MySQL** (압도적) |

🔴 **함정**: 프로덕트 축에서 PG 가 이기는 조건이 «DB 를 매니지드로 뺀다» 인데,
**그렇게 빼면 운영 축에서 MySQL 을 지지하던 근거(방치 내성·부품 수)도 같이 사라진다.**
워크로드 성질에서 오는 것(핫 UPDATE·index-only scan 조건부성)만 남는다.

> 그래서 **진짜 갈림길은 «MySQL이냐 PG냐» 가 아니라 «DB 를 박스 밖으로 뺄 거냐» 다.**

**그리고 지금 MySQL 을 권하는 이유는 결국 하나 — 전환 비용.**
남은 국면이 «측정이 아니라 압축»([`project-destination-and-exit-criteria.md §5`](./project-destination-and-exit-criteria.md))인데,
엔진 교체는 E2(모든 수치가 조건·근거를 갖는다)를 통째로 다시 여는 일이다.
**"더 나은 엔진을 안 골랐다" 가 아니라 "지금 국면에서 바꿀 이유가 없다" 가 정확한 문장이다.**

---

## 8. 기업이 MySQL 을 쓰는 이유 — 그리고 이 중 몇 개가 해당하나

⚠️ 아래는 검색으로 확인한 것이 아니라 **일반적으로 알려진 배경**이다.

| 기업의 이유 | 이 프로젝트 |
|---|---|
| **경로 의존** — LAMP 시대의 기본값, 그 위에서 자란 의사결정자 | ❌ 신규다 |
| **사람을 구할 수 있다** — 장애 때 아는 사람이 있는가 | ✅ 막혔을 때 답이 있느냐 |
| **복제가 쉽고 스케일 패턴이 정형화** — read replica, 샤딩 문화(Vitess) | ❌ 복제 안 한다 |
| **Aurora MySQL** — 복제·페일오버·백업이 사실상 해결 | ❌ 안 쓴다 |
| **대규모 운영 사고가 덜 난다** — VACUUM 없음, 커넥션 가벼움 | ✅ 1인 운영이라 그대로 걸린다 |
| **워크로드가 생각보다 단순** — 분석은 어차피 DW(BigQuery 등)로 뺀다 | ❌ DW 없다 |
| **검증된 대규모 도구** — pt-toolkit · gh-ost · Vitess · Orchestrator | 🔶 pt-osc 는 실제로 쓴다 |

**기업이 MySQL 을 쓰는 이유 대부분은 «규모» 에서 오는데 이 프로젝트엔 그 규모가 없다.**
→ **"현업에서 많이 쓰니까요" 는 답이 아니라 답을 안 한 것이다.**

### 반대 흐름도 정직하게

신규 채택은 PostgreSQL 로 기울고 있다(개발자 설문 기준 PG 가 앞선 지 몇 년 됐다 — 단 그건 «사용률» 이지 «기업 프로덕션 점유» 가 아니다).
대략 **레거시·대형은 MySQL, 신규·스타트업은 PG**. 국내는 MySQL 이 더 강하게 남아 있다고 보는데
**이건 채용 공고 체감이지 실측이 아니다**([[user_career_target]]).

그리고 **둘은 수렴 중이다** — MySQL 8 이 CTE·윈도우 함수·CHECK 제약·INSTANT DDL 을 받으면서
2010년대 초반의 «MySQL 은 장난감» 담론은 지금 안 맞는다.

---

## 9. 면접 답변 (E4 대상)

### Q. 왜 MySQL 인가요?

> "이 프로젝트는 쓰기 주도입니다 — `pose_data` 가 프레임당 1행이고 실측 병목도 INSERT 였습니다.
> 세션 행이 rep 마다 갱신되는 핫 행이라 MVCC 튜플 증식이 부담이고, 운영자가 저 혼자라
> autovacuum·fillfactor 를 상시 관리하는 비용이 큽니다.
> 그리고 조회가 잦으면서 쓰기도 잦은 테이블이라 PG 의 index-only scan 은 visibility map 때문에
> 시점에 따라 성능이 달라지는데, MySQL 은 느릴 때도 예측 가능하게 느립니다."

### Q. 요즘 PostgreSQL 이 낫다던데요?

> "**PG 가 이기는 영역이 더 넓은 건 맞습니다.** 관측·PITR·인덱스 표현력에서 확실히 위입니다.
> 다만 제 워크로드는 거기 안 들어갑니다. greenfield 였으면 PG 를 골랐을 거고, 그 경우
> 핫 UPDATE 와 커넥션에서 손해를 봤을 겁니다. 지금 안 바꾸는 이유는 엔진 우열이 아니라
> 이미 재놓은 수치 전부가 InnoDB 조건이라 그걸 다시 여는 비용 때문입니다."

### Q. 저 분류표(읽기=MySQL, JSON=PG)는요?

> "대봤는데 어느 칸도 안 걸렸습니다. JSON 은 쓰지만 **안을 질의하지 않아서** — 코드에 JSON path 질의가 0건이고,
> projection 최적화의 승리 방식이 아예 안 읽는 것이었습니다. 집계도 세션 단위라 옵티마이저 차이가 안 걸립니다."

🔴 **저 분류표를 그대로 인용하지 말 것.** "읽기위주=MySQL" 에서 "왜요?" 가 들어오면 못 버틴다.

---

## 10. 미결정

- [ ] **E3 «복제 축» 을 열 것인가** — 열면 §3 의 CDC 칸이 처음으로 실질을 갖는다
- [ ] **DB 를 박스 밖 매니지드로 뺄 것인가** — §7 의 진짜 갈림길. 빼기로 하면 엔진 재검토가 성립한다
- [ ] 이 문서를 [`../portfolio/one-pager.md`](../portfolio/one-pager.md) 에서 링크할지

---

## 결정 로그

- 2026-07-05: §11-1(`db-portfolio-roadmap.md`)에 초판. 엔진 우위 주장 3개 반증까지.
- 2026-08-11: 별도 문서로 분리·확장. **새 결정 없음** — 현행 MySQL 유지가 그대로 권고이고,
  근거만 «이미 재놨으니까» 에서 «전환 비용 + 운영 여력» 으로 다시 세웠다.
  §11-1 은 이 문서로 가는 포인터로 축약.
