# 커넥션 풀 사이징 재실험 설계 — 10~20 사이 좁히기

작성: 2026-09-04
상태: 🔶 **3대(DB·App 분리) 구성으로 재재설계 완료 — 아직 미실행.** §8(1차 시도, 4대 중단)·§9(2대 p6-target+p6-loader 재설계, 실행 전 비교가능성 문제로 폐기)·§10(3대 재재설계, 2026-09-04) 참조. `performance-tactics-availability-tradeoff.md`(택틱 B 원 출처 문서)는 이후 다른 세션이 정리한 것으로 보여 현재 워킹트리에 없다 — 이 문서만으로도 실행에는 지장 없음
선행: [`commit-count-and-mysql-metrics.md`](./commit-count-and-mysql-metrics.md) §0-1(방법론·라틴 방격 교훈), [`pool-cliff-vs-concurrency.md`](./pool-cliff-vs-concurrency.md)(c 상한 정책), [`slo-baseline.md`](./slo-baseline.md) §5-1(델타 판정 규칙)

---

## 0. 왜 이 실험인가 — 열린 질문을 정확히 쪼갠다

`application.yml:33-55`의 실측 근거는 딱 4점이다(2026-08-09, 다세션 페이로드·기본 내구성·c=100):

| pool | 처리량(plateau 대비) |
|---:|---:|
| 2 | 69% |
| 5 | 69% |
| 10 | **100%** |
| 20 | **100%(plateau)** |

**10과 20이 이미 둘 다 100%다.** 그런데 현재 운영값 `maximum-pool-size: 15`는 이 넷 중 어느 것도 아니고, "10부터 plateau니까 그 위 여유값"이라는 추론으로 골랐다 — **실측점이 아니라 보간(추정)이다.** 열린 질문은 처리량이 아니라 그 사이에서 다른 것이다:

- **Q1** — RPS가 10~20 전 구간에서 정말 평평한가, 아니면 10·20 두 점만 우연히 100%이고 사이에 비단조 구간(예: 12에서 살짝 처짐)이 있는가?
- **Q2** — RPS는 같아도 **큐잉 지표**(`pending`, `acquire_seconds`)는 pool 크기에 따라 달라지는가 — 지금 "15"라는 안전마진이 실제로 뭘 사는지
- **Q3** — 10(최소 충분값)과 15(현재값) 사이에 **실질적 차이**가 있는가, 아니면 15는 근거 없는 관성값이라 10으로 낮춰도 되는가(→ 낮추면 DB 연결 자원을 덜 쓰므로 다른 서비스와의 자원 경합 여유가 늘어난다는 것이 [`performance-tactics-availability-tradeoff.md`](./performance-tactics-availability-tradeoff.md) 택틱 B의 "상한을 실측 범위 안에서만" 원칙과 맞물림)

**이 실험이 답하지 않는 것**: durability 완화(별도 축, 이미 기각됨), AI 워커 수(N=3, [[project_ai_ceiling_gil_n3_closed]]로 이미 닫힘), c>150(`pool-cliff-vs-concurrency.md`가 "DAU 1,000 가정에서 현실성 낮음"으로 이미 범위 밖 처리).

---

## 1. 통제 변수 — 기존 실측과 비교 가능하게 고정

| 변수 | 값 | 근거 |
|---|---|---|
| 페이로드 | **다세션**(여러 session_id에 분산 INSERT) | 단일 세션 페이로드는 락 경합으로 별개 병목(fsync)이 생겨 풀 사이징 질문과 섞인다([`commit-count-and-mysql-metrics.md`](./commit-count-and-mysql-metrics.md) §0-1-ㄱ) |
| 내구성 | **기본값**(완화 안 함) | 완화는 별도 축이고 이미 채택 안 하기로 함 |
| 동시성 c | **100** | 기존 4점(2/5/10/20)과 같은 조건이라야 새 점을 그 곡선에 끼워 넣을 수 있다 |
| 배치 | **EC2 분리**(부하기/대상 별도 박스) | 로컬 2코어는 이웃 프로세스 간섭으로 풀 사이징처럼 미세한 차이를 가릴 수 있다([[project_loadtest_env_constraint]]) |
| 앱 코드 | **실험 직전 diff 확인** | `commit-count-and-mysql-metrics.md` §1-1이 이미 "적재 경로가 바뀌었는지 먼저 확인 안 하면 절대값을 잘못 비교한다"고 못박음 — 이번에도 선행 |

---

## 2. 독립 변수 — pool 수준

기존 2점(10, 20)을 재사용하고 사이를 3등분 근사로 채운다: **10 · 12 · 15(현재 운영값) · 17 · 20**

- 10·20은 **재현 판**(이전 실측과 같은 조건에서 다시 재는가 확인 — §0-1의 «판 순서=버퍼풀 워밍업» 가설처럼 재현 안 되는 경우가 실제로 있었다)
- 15는 **현재 운영값**이라 반드시 포함 — 이게 실험의 실질적 목적지
- 12·17은 계단 형태(Q1)를 판별하는 데 필요한 최소한의 중간점

---

## 3. 판 순서 — 라틴 방격 + 버림판 (필수)

`commit-count-and-mysql-metrics.md` §0-1-ㄷ의 교훈: **팔당 1판이면 "팔"과 "판 순서"가 분리 불가.** 그 실험은 순서를 뒤집자 결론이 반대로 나온 전례가 있다. 그러므로:

- **팔당 최소 3반복**, 5수준 × 3반복 = **15판**
- **라틴 방격**으로 순서 배치 — 같은 수준이 항상 같은 시간대(예: 항상 마지막)에 오지 않도록
- **버림판 1개** — 라운드 시작 직후 첫 판은 워밍업으로 버리고 분석에서 제외 (총 실행 16판)

---

## 4. 측정 지표

| 지표 | 용도 |
|---|---|
| RPS(처리량) | Q1 — 10~20 구간이 정말 평평한지 |
| `hikaricp_connections_pending` | Q2 — 대기 큐 길이가 pool 크기에 따라 어떻게 줄어드는지 |
| `hikaricp_connections_acquire_seconds` | Q2 — 획득 대기 시간의 분포(baseline 0.957s@pool=5·c=100과 비교) |
| `hikaricp_connections_timeout_total` | **가용성 판정선.** 어느 수준에서도 `>0`이면 그 수준은 즉시 탈락([`slo-baseline.md` §4-4](./slo-baseline.md)) |
| p50/p95/p99 | 지연 분포 — RPS는 같아도 tail이 다를 가능성 |
| (사후 분석용) `Innodb_row_lock_waits` 등 mysqld_exporter 지표 | 이상치가 나오면 원인 규명(다세션이면 거의 0이 정상 — 이 프로젝트 실측 전례) |

---

## 5. 판정 규칙 — 임의 기준 없음

- **"차이가 있다"의 기준**: 두 수준의 반복 측정 분포가 **겹치지 않을 때만** 실효 차이로 인정([`slo-baseline.md` §5-1](./slo-baseline.md) 델타 판정 규칙 재사용). 평균만 비교해 "3% 높다" 같은 식으로 판정하지 않는다
- **가용성 게이트**: 어느 수준이든 `timeout_total > 0`이 한 번이라도 나오면 그 수준은 이유 불문 제외
- **결론 형태**: "10~20 사이는 [겹치는 구간]과 [갈리는 구간]으로 나뉜다" 또는 "전 구간 차이 없음 — 15는 근거 없는 여유값이었다, 10으로 낮춰도 무방" 둘 중 하나로 나올 것이고, **사전에 어느 쪽이 맞다고 정하지 않는다**

---

## 6. 범위 밖 (명시)

- Durability 완화와의 교차 실험 — 별도 축, 이미 이 라운드에서 안 섞기로 함(§1 고정)
- c=150 이상 — `pool-cliff-vs-concurrency.md`가 이미 범위 밖으로 정리
- AI 워커 프로세스 수 — N=3으로 닫힌 질문, 이 실험과 무관

---

## 7. 실행 전 확인 필요 (사용자 결정)

- [x] 이 설계(수준 5개 × 3반복 + 버림판 1 = 16판)로 실행할지 — 반복 수 3 유지, 확정(2026-09-04)
- [x] EC2 라운드 착수 승인 — 4대(DB·App 분리+Loader+Obs) 원안으로 승인(2026-09-04, 위험 설명 두 번 거친 뒤)
- [ ] 실행 직전 "3차 이후 적재 경로 코드 변경 여부" 확인 — §8 시도가 이 단계 전에 중단돼 미실시

## 8. 실행 1차 시도 기록 (2026-09-04) — 중단, 데이터 없음

4대(DB·App·Loader·Obs, `ami-08d82cf148c92fcc3`, DB/App=`m6i.xlarge`·Loader/Obs=`c7i.large`,
인스턴스 ID `i-0ede14b4cbd54780f`·`i-08e06d46bd2918f5d`·`i-0a5aaeaeaac6d9f8b`·`i-05e7fe8f6716f2bf9`)를
실제로 띄우고 DB(`ROLE=db`)·Loader(`ROLE=p6-loader`) 부트스트랩까지는 정상 진행됐다. **App 박스(순수
Spring bare jar)·Obs 박스(원격 mysqld_exporter)를 짜다가 중단했다** — 이유:

- `bootstrap.sh`의 `p6-loader`가 실제로 만드는 페이로드는 `/root/batch_multi.json`(Go 템플릿,
  `gen_batch_multi.py`, 2026-08-17 이후 관례)인데, 이 스윕 스크립트(`pool_sizing_10_20_sweep.sh`)와
  `_rig.sh`는 그보다 오래된 `/tmp/batch_n1.json`(평문 JSON, `gen_batch.py` 계열) 관례를 그대로
  물려받고 있었다 — **둘이 다른 세대의 페이로드 방식**이라 그대로 못 물린다
- App 박스가 필요로 하는 `env.sh`(`JWT_SECRET`·`INTERNAL_API_TOKEN` 등)와 Loader의 `meta.json`
  인증 토큰이 서로 맞아야 하는데, 그 매칭 절차가 `run_all.sh`(111KB, 미검토) 안에만 있고
  이 스윕 경로에는 없다
- 이 셋을 손으로 다 맞추려면 이 대화 안의 위험 설명대로 **실전 위에서 새 통합 코드를 처음
  디버깅하는 상황**이 되므로, 추가 손실 전에 멈췄다

**조치**: 4대 전부 `terminate-instances`로 종료·확인 완료(볼륨도 `DeleteOnTermination`으로 정리됨,
잔존 리소스 0). 가동 시간은 약 15~20분이라 비용은 무시할 수준([`loadtest/aws/README.md`](../../loadtest/aws/README.md)
관례대로 정확한 금액은 추후 Cost Explorer에서 `Project=shadowfit-measure` 태그로 대조).

**남은 선택지** (§7 미결 재오픈):
- **A. 2대(p6-target+p6-loader) 구성으로 축소** — 어제(2026-09-03) 실제로 검증된 경로 그대로 재사용,
  mysqld_exporter(Obs) 없이 RPS·HikariCP만으로 Q1·Q3 판정(Q2 메커니즘 분석은 포기)
- **B. 4대 원안을 다시 시도** — `run_all.sh`를 먼저 완전히 읽고 페이로드·토큰 매칭 절차를 이 스윕에
  맞게 새로 짠 뒤 재시도(오늘 밤 안에는 무리, 별도 세션 필요)

이 문서는 결정하지 않는다([[feedback_user_decides_not_claude]]) — 다음 실행 전에 사용자가 A/B 중 선택.

## 9. B(4대 원안 재시도) 대신 실제로 한 것 (2026-09-04, 같은 날) — 설계·코드만, 미실행

사용자가 "B"를 선택했으나, `run_all.sh`(1,860줄) 전체를 읽어보니 **4대(DB·App 분리+Obs 원격)
구조 자체가 지금 유지되는 관례와 근본적으로 안 맞는다는 것**을 추가로 확인했다:

- `mysqld-exporter` 서비스가 `--mysqld.address=shadowfit-mysql:3306`(컨테이너명)으로 하드코딩돼
  있어 **같은 docker 네트워크 안에서만** 동작한다 — 별도 Obs 박스가 원격으로 못 붙는다
- App 박스가 기대하는 "순수 Spring bare jar" 배포 방식은 지금 `bootstrap.sh`의 어떤 ROLE에도
  없다(`db`·`p6-target`·`p6-loader`·`ai-venv` 넷뿐이고, `p6-target`은 MySQL+Spring+AI를
  **한 박스**에 docker-compose로 묶는 구조라 DB·App 분리 자체가 안 된다)

**즉 4대 원안은 "새로 짜면 되는" 정도가 아니라 지금 아키텍처와 구조적으로 어긋난다.** 그래서
아래는 B를 그대로 강행한 게 아니라, **A(2대 p6-target+p6-loader)의 인프라 위에 원 설계(라틴
방격·판정 규칙)를 그대로 얹은 재설계**다 — 구성은 A, 실험 설계는 원안 유지.

### 9-1. 알아낸 것

- `run_all.sh`의 `PHASES` 메커니즘: `phase_<이름>()` 함수를 정의하고 끝의 `case`에
  `run_phase <이름> phase_<이름>` 한 줄만 추가하면 새 라운드가 생긴다. `httpwrite`/`httpread`가
  가장 단순한 선례(부하기에서 돌며 `TARGET_HOST`/`TARGET_SSH`로 대상을 원격 조작)
- `coresidency_sweep.sh`(P6 동거 용량 rig)의 `snap_side()`가 이미 **actuator(9090)의
  `hikaricp_connections_*` 지표**를 대상 박스 SSH로 긁고 있었다(`SIDE_RE` 정규식에 포함) —
  **별도 Obs 박스 없이도 HikariCP 지표는 이미 회수 가능한 경로가 있었다**
- `p6-target`의 `.env`는 `bootstrap.sh`가 `AI_PUBLIC_TOKEN`/`INTERNAL_API_TOKEN`을 **직접
  생성해서 화면에 출력**하고, `p6-loader`도 부트스트랩 마지막에 `gen_batch_multi.py`로
  `/root/batch_multi.json`을 만든 뒤 **그대로 복붙 가능한 실행 명령을 출력**한다 — 1차 시도가
  겪은 "토큰·페이로드 배선이 안 보인다"는 문제는 **부트스트랩 출력을 그대로 쓰면 애초에 안
  생기는 문제**였다(1차 시도가 이 출력을 못 보고 손으로 다른 관례를 짜려다 막힌 것)
- `shadowfit-backend` 서비스는 `docker-compose.yml`에서 명시적 `environment:` 맵을 쓰고
  `env_file`이 아니다 — override 파일로 `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`를
  얹으면(Spring Boot relaxed binding) 기존 arm C/D의 `docker-compose.cap.yml` 패턴을 그대로
  재사용해 pool 값을 주입할 수 있다(코드로 확인, 실행 검증은 아직 없음 — §9-3)

### 9-2. 다시 짠 것

- **`loadtest/measure_poolsizing_10_20.sh`**(신규) — `docs/decisions/pool-sizing-10-20-experiment-design.md`
  §1~§5의 설계(5수준·라틴 방격 3라운드+버림판·`slo-baseline.md` §5-1 판정 규칙)를 그대로 유지하되:
  - 부하는 `p6-loader`가 만드는 `/root/batch_multi.json` + 대상 `.env`의 실제
    `INTERNAL_API_TOKEN`을 쓴다(1차 시도가 못 맞췄던 그 배선)
  - pool 전환은 `docker-compose.pool.yml` override(arm C/D와 같은 패턴)로 하고, 적용 후
    `hikaricp_connections_max`를 actuator에서 다시 읽어 **실제로 그 값이 물렸는지 확인**한다
    (arm C/D의 `assert_caps`와 같은 원칙 — "단언"으로 끝내지 않는다)
  - 옆 지표(HikariCP pending/acquire/timeout, MySQL 락 대기)는 `coresidency_sweep.sh`의
    `snap_side` 패턴을 그대로 복사해 pool 스윕용으로 축소
  - 부하 방식은 **원 설계 그대로 `ghz -c 100 -n 15000` 닫힌 루프**를 유지한다 — P6 rig의
    `--rps` 열린 루프(가정 P1 배수)는 "여유가 얼마인가"를 묻는 다른 질문에 맞는 형태라
    안 가져왔다. 페이로드 파일·인증만 P6 관례를 빌리고, 부하 방식은 2026-08-09 4점
    baseline과 **같은 조건**을 유지해 비교 가능성을 지켰다
- **`loadtest/aws/run_all.sh`**(수정) — `phase_poolsizing()` 추가 + `case` 디스패치 + `OUTDIR`
  라운드명 매핑(`pool-sizing-10-20`) + 헤더 사용례 주석. `httpwrite`/`httpread`와 같은 얇은
  래퍼 패턴(전제 조건 체크 후 `measure_poolsizing_10_20.sh` 호출)
- 구성은 **2대(p6-target+p6-loader)**로 축소됐다 — Obs가 빠진 대가로 **Q2(왜 그런 결과가
  나오는지 메커니즘)는 MySQL `SHOW GLOBAL STATUS`의 락 대기 지표(`Innodb_row_lock_waits` 등)로
  부분적으로만 답할 수 있다**(mysqld_exporter 수준의 세밀함은 없음) — Q1·Q3(핵심 질문)는
  영향 없음

### 9-3. 🔴 다음 실행 전 반드시 확인할 것 (미검증 목록)

`measure_poolsizing_10_20.sh` 머리에도 같은 목록이 있다 — 여기 요약:

1. **`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` env override가 실제로 HikariCP에 반영되는지
   실전에서 확인된 적이 없다** — 코드(Spring Boot relaxed binding 규칙)로만 확인했다.
   `assert_pool()`이 재기동 직후 `hikaricp_connections_max`를 다시 읽어 스스로 검증하도록
   짜뒀지만, 그 검증 로직 자체도 아직 안 돌려봤다
2. 재기동 후 health 대기 루프 상한(90초)이 실제 인스턴스에서 충분한지 미검증
3. `batch_multi.json`(다세션 템플릿, 2026-08-17 이후 생성기)이 `-c 100 -n 15000` 닫힌 루프와
   조합됐을 때, 2026-08-09 4점 baseline(다른 페이로드 생성기)과 **완전히 동등한 조건인지는
   아니다** — 둘 다 "다세션"이라는 성질은 같지만 생성기가 다르다는 점은 결과 문서에 명시할 것
4. 이 phase는 **오늘 코드만 작성됐고 EC2에서 단 한 번도 실행된 적이 없다** — preflight 게이트도
   없이 바로 본판(라틴 방격)으로 들어가는 구조라, 첫 실행은 사실상 리허설을 겸한다는 것을
   실행자가 인지하고 있어야 한다(1차 시도가 겪은 것과 같은 부류의 실전 디버깅 여지가 아직 있음)

### 9-4. §9(2대) 폐기 — 실행 전에 비교가능성 문제가 드러났다 (2026-09-04)

§9의 2대(p6-target+p6-loader) 설계는 실행 직전 점검에서 **원 baseline과 아키텍처가 다르다**는
것이 발견돼 실행하지 않고 폐기했다:

| | 08-09 4점 baseline | §9 2대(p6-target) |
|---|---|---|
| DB·App | **별도 박스**(t4g.medium 둘) | **같은 박스**(docker 컨테이너 간) |
| AI | 없음 | 있음(유휴지만 옆에서 자원을 나눈다) |
| 인스턴스 | t4g(ARM, 버스터블) | c7i(Intel) |

이 실험의 존재 이유가 「10~20 사이를 08-09 곡선에 끼워 넣는 것」인데, 아키텍처가 이만큼
다르면 새 점이 그 곡선 위에 있다고 주장하기 어렵다 — 특히 DB 접속이 네트워크 홉(별도 박스)
↔ localhost(같은 박스 컨테이너)로 바뀌는 것은 HikariCP acquire 시간에 직접 영향을 줄 수 있는
조건이라 이 실험의 핵심(Q2, 큐잉 지표)과 정면으로 겹친다. **사용자 결정(2026-09-04): 먼저
DB·App 분리로 재설계.**

## 10. 3대 재재설계 — DB·App 분리를 되살린다 (2026-09-04)

### 10-1. 구성

**3대 — DB(`ROLE=db`) · App(`ROLE=app`, 신규) · Loader(`ROLE=p6-loader`)**. Obs 박스는
여전히 안 띄운다(§9와 같은 이유 — MySQL 옆 지표는 DB 박스에서 직접 `SHOW GLOBAL STATUS`).

- **DB** — 기존 `ROLE=db` 그대로(MySQL 컨테이너 하나). 08-09 baseline의 DB 박스와 같은 성격.
- **App** — `bootstrap.sh`에 신규 `ROLE=app` 추가. Spring을 **bare jar**로 systemd 유닛
  (`shadowfit-app`)에 태운다 — 08-09 baseline의 App 박스(t4g.medium, bootJar 직접 실행)와
  같은 성격. **도커·AI 없음.** gRPC AI 채널(`ManagedChannelBuilder.forAddress`)이 지연
  연결이라 AI가 없어도 기동에 지장이 없다(08-09 원판이 이미 이렇게 돌았다) — 코드로 확인
  (`ExerciseAnalysisService.java:109-119`).
- **Loader** — 기존 `ROLE=p6-loader` 그대로. 페이로드·토큰 배선은 §9-1이 이미 푼 것을 그대로 쓴다.

### 10-2. §9와 달라지는 것 — 풀 전환 메커니즘

App이 bare jar라 `docker-compose.pool.yml` override를 못 쓴다. 대신 systemd 유닛의
`Environment=SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=` 한 줄을 `sed`로 갈아 끼우고
`systemctl restart` 한다. `assert_pool()`(actuator에서 `hikaricp_connections_max` 재확인)은
배포 방식과 무관하므로 그대로 재사용한다.

옆 지표 수집도 갈린다 — Spring actuator는 그대로 `TARGET_SSH`(App)로, MySQL
`SHOW GLOBAL STATUS`는 이제 **`DB_SSH`**(DB 박스)로 나뉜다(§9는 한 박스라 `TARGET_SSH`
하나였다).

### 10-3. 사용자 확인 2건 (2026-09-04)

- **비교가능성 문제 발견 후**: 「그대로 진행(비교가능성 캐비어트 명시)」vs 「먼저 4대 분리로
  재설계」 중 **후자를 선택** — 지금 이 §10이 그 결과다.
- **App 박스 구현 방식**: 프로세스 관리는 **systemd**(nohup+pkill의 자기 자신 매칭 함정,
  08-09 §3-1, 을 구조적으로 피한다) · 코드 위치는 **`bootstrap.sh`에 `ROLE=app` 신규 추가**
  (임시 스크립트 대신 커밋돼 재사용 가능하게 — 08-09가 이걸 안 남겨서 이번에 다시 짜야 했다).

### 10-4. §9-3 미검증 목록 — 3대에서 다시 보면

§9-3의 항목들은 배포 방식이 바뀌어도 성격이 같다(override 메커니즘의 실전 미검증 →
systemd sed 치환의 실전 미검증). 추가된 것 하나: **App↔DB가 진짜 다른 박스로 갈라진 채
끝까지 도는 것을 한 번도 못 봤다** — DB 커넥션이 네트워크 홉을 타는 첫 실행이라 health 대기
상한(60초)이 로컬 MySQL이던 §9 전례보다 빠듯할 수 있다.

### 10-5. 다음에 할 일 (사용자 판단)

- 인스턴스 3대(DB·App·Loader)를 실제로 띄워 실행할지, 아니면 로컬에서 `ROLE=app` bootstrap
  블록만 먼저 문법·로직 리뷰 이상으로 시험해볼지(로컬엔 실제 EC2가 없어 SSH 경로 자체는
  로컬 시험이 안 된다 — 리뷰가 사실상 최선)
- 이 문서·스크립트는 결정하지 않는다 — 실행 여부와 시점은 사용자 몫이다
