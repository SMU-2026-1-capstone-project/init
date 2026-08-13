# ShadowFit — 한 장

> 이 문서가 **정본**이다. 아래 수치는 전부 조건과 근거 링크를 달고 있고, 자세한 과정은
> 링크된 문서에 있다. **읽는 문서는 이거 하나면 된다.**
>
> 작성 2026-08-10 · 종료 조건 E2([`../decisions/project-destination-and-exit-criteria.md`](../decisions/project-destination-and-exit-criteria.md))의 대상 문서

---

## 프로젝트

실시간 스쿼트 자세 분석 앱. 카메라 프레임을 AI 서버(FastAPI·MediaPipe)가 분석하고,
**백엔드(Spring)가 초당 수십 행의 자세 데이터를 적재하고 세션 종료 시 리포트로 만든다.**

- **본인 담당**: 백엔드 전체 — 적재·조회 경로, gRPC 결합, 신뢰성, 관측성, DB 설계·튜닝
- **본인 담당 아님**: 자세 분석 알고리즘(MediaPipe·DTW)은 팀 내 AI 담당
- **스택**: Spring Boot 3.5.16 / Java 21 · MySQL 8.0 · gRPC · Flyway · Docker · Prometheus·Grafana(측정 오염을 막으려 `profiles: ["obs"]` 로 분리 — 기본 구동은 3개)
- **규모 전제**: DAU 1,000 가정 · `pose_data` 1억 행 합성 시딩 — **실제로 만든 것은 더미 JSON 11GB**(행수·payload 디커플링). 실제 2.3KB JSON 이면 **230GB**(1억 × 2.3KB 단순 곱)라 로컬에 못 올린다
  - **산식**: 1억 × 2.3KB = 230GB, **JSON 바이트만** 센 값이다(테이블은 다른 컬럼과 페이지 오버헤드로 더 크다). **실측 교차검증**: AWS `pose_data_real_scale`(진짜 JSON)에서 375만 행의 JSON 합이 8.19GB → 1억 행 환산 **≈218GB** 로 정합 ([§3](./realmysql-experiments.md))
  - 📌 실제로 **1억 행 real-JSON 테이블은 AWS 에서 만들어 봤다**(`pose_data_real_scale`, m6i.xlarge + EBS 700GB). 로컬에 못 올릴 뿐이다

---

# ① 백엔드(Spring) 버전

## 한 줄

> **순진한 적재·조회가 대용량에서 깨지는 지점을 측정으로 찾아 고치고, 고친 근거를 조건까지 적어 남긴 백엔드.**

## 핵심 3

| 무엇을 고쳤나 | 결과 | **조건** | 근거 |
|---|---|---|---|
| **JSON over-fetch** — 리포트 계산에 안 쓰는 2.3KB JSON까지 로드 | payload **−98.7%**, warm 쿼리 **8배** | 2026-06-02, warm, 750행/세션, 로컬 412만 행, **3컬럼 시절 프로젝션**. AWS 1억 행 재검증 29~41배. ⚠️ **precompute 잡이 부르는 쿼리**라 사용자 체감 지연이 아니라 잡 자원 절감 | [§②b](./realmysql-experiments.md) |
| **TTL 삭제** — 만료 데이터를 DELETE로 지움 | `DROP PARTITION`이 DELETE 대비 **625배** | 8.3M행 만료 기준(18.6분 → 1.8초). DELETE는 빈 952MB 파일도 남긴다. **조회 pruning 이득은 0**(별도 실측으로 반증) | [§②d](./realmysql-experiments.md) |
| **쓰기 천장** — 「커넥션 풀이 병목」 가설 | 다세션 천장 **649 RPS**, `pool=5`는 plateau의 **69%**, 10부터 평탄 | 2026-08-09 EC2 4차. **단일 핫세션 페이로드에선 이 절벽이 안 보인다** — 그 조건에선 천장이 fsync였고(231.6→803.1, 3.47배) 풀이 가려졌다 | [§5-1(9)](../decisions/pose-ingest-downsampling.md) |

## 그 외 실측

| 항목 | 결과 | 조건 | 근거 |
|---|---|---|---|
| 배치 INSERT (`JdbcTemplate.batchUpdate`) | 처리량 **+99%**(23.5 → 46.7 RPS), p99 **−37%**(7,549 → 4,784ms) | 로컬. JPA `saveAll`은 IDENTITY PK 때문에 Hibernate 배치가 원천 차단됨을 확인 후 우회 | [§②a](./realmysql-experiments.md) · [`load-test §7.6`](../decisions/load-test-strategy.md) |
| 다운샘플 R≈5 (대표 프레임 추출) | 처리량 **1.7배**(98.3 → 168.7 RPS), p99 **4.9배**, 저장 행 **5배↓** | 2026-08-08 EC2 3대, c=100·pool=10, cold vs cold, `DOWNSAMPLE_WINDOW=1` 대조군. ⚠️ **단일 핫세션 페이로드**(`batch.json`, session 801) — fsync 3.47배를 1.03배로 만든 그 조건이고, **다세션에서 재측정한 적 없다** | [`pool-cliff §3`](../../loadtest/results/pool-cliff-2026-08-08/README.md) · [`pose-ingest-downsampling.md`](../decisions/pose-ingest-downsampling.md) |
| 인덱스 유무 대조 | 약 **9,000배** | 실제 2.1KB JSON·412만 행. 더미 JSON이면 660배로 **과소평가**된다(행 크기가 배수를 증폭) | [`realmysql-experiments.md`](./realmysql-experiments.md) · rig `loadtest/measure_admin_index.sh` |
| offset → keyset 페이지네이션 | 최대 **489,868배** | 1억 행. offset은 깊이에 O(N), keyset은 평탄. ⚠️ keyset 절대값은 SSH 왕복 오버헤드(~50ms)가 바닥에 깔려 **액면가로 안 쓴다** — 배수 결론만 유효 | [`realmysql-experiments.md`](./realmysql-experiments.md) · rig `loadtest/measure_pagination.sh` |
| 무중단 스키마 변경 — 차단 비용 | `ALTER ... PARTITION BY` = **96분**(5,767초) 차단 | 더미 JSON 1억 행 풀 리빌드(~24,700행/초). `ALGORITHM=INPLACE`는 서버가 거절(errno 1845, "Try ALGORITHM=COPY") | [`online-ddl-vs-blocking-alter.md`](../decisions/online-ddl-vs-blocking-alter.md) · [rig](../../loadtest/results/online-ddl-2026-08-09/README.md) |
| 무중단 스키마 변경 — pt-osc 대조 ⭐ | 쓰기 정지 **최소 187배 단축**(68.1~69.4초 → 0.03~0.364초). 대가는 DDL **1.64배**(69 → 113초) + **binlog 441MB**(차단 ALTER 는 0MB). 디스크 최대치는 **두 팔이 같다** | 2026-08-12 EC2 m6i.xlarge, `pose_data_scale` **996만 행**, 버림 2판 + 본판 6판(`A B B A A B` 위치 상쇄), errors 0. ⚠️ **위 96분과 다른 규모·다른 기계** — 배수는 같은 라운드 안에서만 유효하고, 절대 시간은 하드웨어 종속이라 인용 금지 | [결과](../../loadtest/results/online-ddl-aws-2026-08-12/README.md) · [`online-ddl-vs-blocking-alter.md`](../decisions/online-ddl-vs-blocking-alter.md) |
| 백업/복구 — RTO ⭐ | 논리 백업(`mysqldump`)에서 **복구 약 21분**(1,282~1,304초). 백업 자체는 **거의 안 멈춘다** — 쓰기 최대 정지 **42~126ms** | 2026-08-13 EC2 m6i.xlarge, gp3 200GB(125MB/s), `pose_data_scale` **1억 행**, 팔당 버림판 1 + 본판 3, errors 0. 판정이 서는 이유는 **양성 대조군**이다 — 명백히 잠그는 팔 C 가 같은 rig 에서 2,692ms 로 잡혔다. ⚠️ 절대 시간은 하드웨어 종속이라 「운영에서 N분」 인용 금지. ⚠️ **물리 백업(XtraBackup) 쪽 복구 시간은 캐시를 재서 배수 인용 불가**([#201](https://github.com/Shadowfit/init/issues/201)) | [결과](../../loadtest/results/backup-restore-aws-2026-08-13/README.md) · [`backup-restore-rto-rpo.md`](../decisions/backup-restore-rto-rpo.md) |
| 백업/복구 — RPO | **PITR 로 「사고 직전」까지** 복원(대량 DELETE 재생 안 됨). 상한은 binlog 보존 **30일** | 같은 라운드. `gtid_mode=OFF` → **포지션 기반**(파일명 + 오프셋). ⚠️ binlog 가 datadir 과 **같은 디스크**다 — 디스크를 통째로 잃으면 같이 잃는다 | [결과 §5](../../loadtest/results/backup-restore-aws-2026-08-13/README.md) |
| 동시성 결함 — 검출기가 세션이 아니라 **스레드**에 붙어 있었다 | 검출률 **~30% → 95.6%** | 2026-08-11, 4세션·3fps·개발 장비. 실사용 페이스에서 프레임의 44~78%가 «직전에 다른 세션을 본 스레드»에 배정돼 트래킹이 깨졌다. ⚠️ **«바쁠수록 안전하고 한가할수록 위험»** — 포화 상태에선 충돌 0~2.6%로, 원 이슈의 추측과 반대. ⚠️ 합성 프레임 | [#164](https://github.com/Shadowfit/init/issues/164) |
| AI 추론 용량 재측정 | 프레임당 **103.4ms → 17.6ms**, 물리 코어당 **16.4세션** | 2026-08-11 `c7i.2xlarge`(Xeon 8488C) 베어메탈. **«DAU 1,000에 5~10배 부족»은 개발 장비(i3-6100) 탓이었다.** ⚠️ 상한이지 권장치가 아니다 | [`ai-recalibrate`](../../loadtest/results/ai-recalibrate-2026-08-11/) |

## 설계·신뢰성

- **아웃박스 + 멱등 수신** — 세션 종료 통보를 at-least-once로. 전달 의미론을 문서와 코드에서 일치시킴
- **낙관적 락 + 3회 재시도** — AI 콜백과 타임아웃 스케줄러가 같은 세션을 경쟁. 충돌을 지표로 집계
- **gRPC deadline + 서킷브레이커** — 단 `INVALID_ARGUMENT`(요청이 틀림)는 건강 신호가 아니므로 서킷 집계에서 제외
- **precompute-on-write** — 리포트 조회 때마다 하던 `pose_data` 재스캔을 세션 종료 시 1회로
- **파티션 TTL 자동화** — 만료 파티션 DROP + 미래 파티션 선확보. `pfuture MAXVALUE`로 적재 실패 자체를 구조적으로 차단
- **자원 상한을 «실측에서 유도»한다** — 동시 세션 상한을 숫자로 박지 않고 `(컨테이너 메모리 한도 − 기본 RSS) ÷ 검출기 1개 98.7MB`([실측](../../loadtest/results/detector-memory-2026-08-11/))로 계산한다. 환경(로컬/EC2)이 바뀌어도 코드가 안 바뀌고, **근거 없는 기준값이 코드에 안 들어간다.** 한도도 설정도 없으면 기동을 거부한다
- **관측성** — correlation id 5단계 전파 + 커스텀 지표 **9종**(코드 확인: [`SessionMetrics.java`](../../backend/src/main/java/com/shadowfit/global/observability/SessionMetrics.java) — 상태전이·낙관락충돌·배치행수·AI중단결과·아웃박스 3종·고아행 2종) + Prometheus·Grafana

## 정직하게 적는 한계

- **실트래픽 0.** 전부 합성이다. 「부하 테스트 기반 검증」이지 운영 경험이 아니다
- **절대 성능치 인용 불가.** 로컬은 2물리코어에 MySQL·백엔드·부하기가 동거한다. **델타와 메커니즘만** 신뢰한다
- **값 분포는 균일하다.** 단일 템플릿 복제라 카디널리티가 가짜 → 선택도·옵티마이저 실험은 **의도적으로 안 했다**
- **MySQL을 고른 건 기술 우위가 아니다.** 「이 워크로드가 MySQL에 유리하다」는 근거 3개를 자체 실측으로 **전부 반증**했다. greenfield였다면 PostgreSQL을 골랐을 것이고, 지금 유지하는 실제 이유는 **전환 비용과 1인 운영 여력** 둘이다 ([`mysql-vs-postgresql.md`](../decisions/mysql-vs-postgresql.md))

---

# ② DBA 버전

같은 자산이다. **부르는 이름만 다르다.**

## 한 줄

> **1억 행 테이블 하나의 적재·조회·보존·스키마 변경을 전 구간 측정하고, 판단 근거를 조건까지 남긴 기록.**

## 직무별 매핑

> 수치의 **조건은 ①표와 같다**(같은 실측을 다르게 부르는 것이므로). 여기서 처음 나오는 값에는 조건을 직접 단다.

| DBA 업무 | 이 프로젝트에서 | 결과 | 근거 |
|---|---|---|---|
| **쿼리 튜닝** | JSON off-page over-fetch 제거 | payload −98.7% (조건: ①표) | [§②b](./realmysql-experiments.md) |
| **인덱스 설계·검증** | `EXPLAIN`으로 이미 최적임을 확인 후 **가설 폐기**, `IGNORE INDEX` 강제 풀스캔과 직접 대조 | 약 9,000배 (조건: ①표) | [`realmysql-experiments.md`](./realmysql-experiments.md) · rig [`measure_admin_index.sh`](../../loadtest/measure_admin_index.sh) |
| **보존정책 운영** | 월별 RANGE 파티션 + 자동 DROP/선확보 스케줄러 | DELETE 대비 625배 (조건: ①표) | [§②d](./realmysql-experiments.md) |
| **용량 산정** | 커넥션 풀 사이징 EC2 4차 실측 · **자원 상한을 실측 상수에서 유도**(메모리 한도 ÷ 98.7MB) | plateau 시작점 = 10 · 코어당 16.4세션 (조건: ①표) | [§5-1(9)](../decisions/pose-ingest-downsampling.md) · [`ai-recalibrate`](../../loadtest/results/ai-recalibrate-2026-08-11/) |
| **내구성 트레이드오프** | fsync 완화 시 3.47배 — **채택하지 않음** | 안 아픈 것을 고치며 데이터 안전을 파는 셈이라 판단. ⚠️ **3.47배는 단일 핫세션 조건의 값**이고 다세션에선 1.03배로 사라진다 | [`ceiling-fsync`](../../loadtest/results/ceiling-fsync-2026-08-08/) · [#166](https://github.com/Shadowfit/init/issues/166) |
| **대용량 데이터 이관** | 1억 행 시딩 파이프라인 가속 | 48분 → 16분. **조건**: 세션 범위 3분할 동시 INSERT. 같은 작업의 다른 레버 셋(버퍼풀 128MB→2GB · 인덱스 후행 빌드 · `innodb_sort_buffer_size` 1M→64M)은 별도 | [§3 가속 교훈](./realmysql-experiments.md) · rig [`seed/README.md`](../../loadtest/seed/README.md) |
| **스키마 변경 운영** | 차단 ALTER를 pt-osc와 대조 — **무중단이 무엇을 사고 무엇을 파는지** | 쓰기 정지 **최소 187배 단축**, 대가는 DDL 1.64배 + binlog 441MB. 디스크는 두 팔이 동일 (조건: ①표) | [결과](../../loadtest/results/online-ddl-aws-2026-08-12/README.md) · [`online-ddl-vs-blocking-alter.md`](../decisions/online-ddl-vs-blocking-alter.md) |
| **백업·복구 운영** | 논리·물리 백업을 같은 rig 에서 대조하고 **PITR 로 사고 직전까지 되돌림** | 복구 약 21분(논리) · 백업 중 쓰기 정지 42~126ms · RPO 는 사고 직전, 상한 binlog 30일 (조건: ①표) | [결과](../../loadtest/results/backup-restore-aws-2026-08-13/README.md) · [`backup-restore-rto-rpo.md`](../decisions/backup-restore-rto-rpo.md) |
| **잠금·격리수준** | `performance_schema.data_locks`로 락 실물 관찰, MVCC/SERIALIZABLE 대조 | 재현 rig 보유. ⚠️ **락 «비용» 은 이 환경에서 못 쟀다** — 판 간 변동이 재려던 효과보다 커서 앞선 −35.3% 도 기각했다 | rig [`measure_lock.sh`](../../loadtest/measure_lock.sh) · [`measure_mvcc.sh`](../../loadtest/measure_mvcc.sh) · [#87](https://github.com/Shadowfit/init/issues/87) |

## 방법론

- **반증 조건을 먼저 쓴다.** 가설과 함께 「무엇이 나오면 틀린 것인가」를 측정 전에 적는다
- **결론이 뒤집히면 뒤집힌 채로 남긴다.** 쓰기 천장은 4차까지 재측정하며 결론이 세 번 바뀌었고, 그 과정이 그대로 문서에 있다
- **수치에는 조건을 단다.** 「3.47배」를 인용한 13곳에 조건을 소급 표기한 커밋이 있다(`0d68b52`) — 값이 틀린 게 아니라 조건이 안 적혀 있었다
- **측정 장치의 오염을 의심한다.** wall-clock 왕복 오버헤드가 sub-100ms 측정을 삼킨 전례를 찾아 `SET profiling`으로 우회했다

## 아직 비어 있는 것 (숨기지 않는다)

| 축 | 상태 |
|---|---|
| ~~무중단 스키마 변경~~ | ✅ **채워짐 (2026-08-12)** — pt-osc 대조 6판 실측. 남은 미검증: `gh-ost` 적용 가능 여부, 트리거 오버헤드의 크기 |
| ~~백업/복구 (RTO·RPO)~~ | ✅ **채워짐 (2026-08-13)** — 1억 행에서 논리·물리 대조 9판 + PITR. 남은 미검증 2개: **물리 백업의 복구 시간이 캐시를 쟀다**([#201](https://github.com/Shadowfit/init/issues/201)), **real-JSON 축소 대조 1판 미실시**(설계에서 확정됐던 규모의 후반부 — `mysqldump` 가 off-page 를 타므로 논리 백업의 불리함이 지금 과소평가돼 있다) |
| 복제/HA | **의도적 미적용** — 규모상 실수요 0. 가짜 복제 데모를 만들지 않기로 판단 |

---

## 근거 문서

| 문서 | 무엇이 있나 |
|---|---|
| [`realmysql-experiments.md`](./realmysql-experiments.md) | 실험 전체 카드 (인덱스·projection·파티션·락·MVCC) |
| [`db-deep-dive.md`](./db-deep-dive.md) | 읽기/쓰기 경로 심층 분석 |
| [`problem-solving-log.md`](./problem-solving-log.md) | 문제 → 판단 → 결과 로그 |
| [`interview-qa-kandl.md`](./interview-qa-kandl.md) | 예상 질문과 답 |
| [`failure-modes.md`](./failure-modes.md) | 실패 모드 정리 |
| [`../decisions/architecture-review-2026-08-11.md`](../decisions/architecture-review-2026-08-11.md) | **«설계에서 아쉬운 점은?»의 답** — 결함 9건·재조립 방향·축별 점수 |
| [`../decisions/mysql-vs-postgresql.md`](../decisions/mysql-vs-postgresql.md) | 엔진 선택 — 왜 MySQL이고, greenfield면 왜 PG인가 |
| [`../tasks/32-deferred-items.md`](../tasks/32-deferred-items.md) | **안 한 것과 그 이유** — 각 항목의 «열 조건»까지 |
| [`../decisions/`](../decisions/) | 설계 분기점별 트레이드오프 문서 (40+) |
