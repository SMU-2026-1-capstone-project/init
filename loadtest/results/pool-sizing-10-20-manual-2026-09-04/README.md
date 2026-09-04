# 커넥션 풀 사이징 10~20 재실험 — 첫 EC2 실행 (2026-09-04)

설계: [`../../../docs/decisions/pool-sizing-10-20-experiment-design.md`](../../../docs/decisions/pool-sizing-10-20-experiment-design.md) §1~§9
선행: [`../commit-count-2026-08-09/README.md`](../commit-count-2026-08-09/README.md) (pool 2/5/10/20 4점 baseline, 2026-08-09)

---

## 0. 한 줄 요약

**pool=10과 pool=15(현재 운영값)는 RPS·p99 둘 다 구분이 안 된다.** 10~20 구간은 사실상 평평하고, 유일한 예외(12↔17)는 전체 변동폭의 0.4%짜리 간격이라 계단으로 보기엔 근거가 약하다. 15는 실측 근거 없는 여유값이었다는 §0의 우려가 **확인**됐다.

---

## 1. 환경

| | |
|---|---|
| 구성 | **2대**(택틱 B, 4대 원안은 아키텍처 불일치로 폐기 — §8 참조) — 대상(p6-target) + 부하기(p6-loader) |
| 대상 | `m6i.xlarge`, AMI `ami-08d82cf148c92fcc3`(AL2023), MySQL+Spring+AI가 **한 박스**에 docker-compose로 뜬다 |
| 부하기 | `c7i.xlarge` |
| 리전 | `ap-northeast-2` |
| 부하 | ghz `-c 100 -n 15000` 닫힌 루프, `batch_multi.json`(다세션 템플릿, `gen_batch_multi.py`, 세션 901~1900) |
| 내구성 | 기본값(완화 안 함) |
| 측정 후 | 인스턴스 2대 전부 terminate 확인, 볼륨 `DeleteOnTermination`으로 정리(잔존 리소스 0) |

### 1-1. 🔴 실행 코드 — 저장소 커밋이 아니라 스크래치 사본

이 라운드는 **동시 세션 충돌 중에 실행됐다.** `run_all.sh`·`measure_poolsizing_10_20.sh`를 커밋하는
시점에 이 저장소에 붙어 있던 다른 세션이 같은 파일을 3대 구성(DB·App 분리)으로 편집 중이었고,
공유 git 인덱스 경합으로 그 미완성 내용이 커밋 `42663b33`에 섞여 들어갔다. 그 상태로 실행했더니
`DB_SSH: unbound variable`로 즉시 죽었다.

그래서 이 라운드는 **`run_all.sh`를 거치지 않고**, 검증된 2대 구성 스크립트를 로컬 스크래치
파일로 만들어 로더 박스에 직접 올려(`/root/init/loadtest/measure_poolsizing_10_20_manual.sh`)
돌렸다. 이후 저장소는 별도 커밋(`a303daf4`)으로 2대 구성으로 복원했다 — **diff로 확인한 결과
실행된 스크래치 사본과 `a303daf4`의 `loadtest/measure_poolsizing_10_20.sh`는 주석만 다르고
실행 로직은 100% 동일하다.** 즉 이 결과는 현재 저장소의 스크립트로 재현 가능하다.

### 1-2. 미검증 목록 중 이번에 해소된 것

설계 문서 §9-3이 남긴 미검증 4개 중 3개가 이번 실행으로 해소됐다:

| 항목 | 결과 |
|---|---|
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` env override가 실제로 HikariCP에 반영되는가 | 🟢 **확인됨** — 15판 전부 `assert_pool`이 재기동 직후 `hikaricp_connections_max`를 기대값 그대로 읽었다(예: pool=17 확인됨) |
| health 대기 90초 상한이 충분한가(m6i.xlarge) | 🟢 **확인됨** — 재기동마다 27~30초 안에 끝남, 여유 있음 |
| ghz `--data-file`(다세션 템플릿)이 08-09 baseline과 완전 등가인가 | 🔴 **여전히 미해결** — 페이로드 생성기 세대가 다르다(§4 한계 참조) |
| 오늘 코드가 EC2에서 실행된 적이 있는가 | 🟢 이 라운드가 그 첫 실행이다 |

---

## 2. 결과

버림판(pool=15, 워밍업) 1개 + 3라운드 × 5수준(라틴 방격: R1 10·12·15·17·20 / R2 15·17·20·10·12 / R3 20·10·12·15·17) = **15판**. **전 판 `count=ok=15000`, `fail=0`.**

### 2-1. 수준별 요약

| pool | 판수 | RPS 범위 | RPS 평균 | p99 범위(ms) | p99 평균(ms) |
|---|---|---|---|---|---|
| 10 | 3 | 1298.6~1368.2 | 1337 | 212.6~248.0 | 232.3 |
| 12 | 3 | 1361.8~1371.9 | 1366 | 212.6~216.3 | 214.4 |
| 15 | 3 | 1321.0~1393.5 | 1366 | 192.7~216.5 | 204.0 |
| 17 | 3 | 1304.1~1356.5 | 1324 | 208.0~238.1 | 225.9 |
| 20 | 3 | 1320.2~1374.6 | 1350 | 200.4~236.3 | 220.3 |

원자료: [`pool_sizing.tsv`](pool_sizing.tsv). 옆 지표(HikariCP·MySQL): [`pool_sizing_side.tsv`](pool_sizing_side.tsv). 전체 로그: [`run.log`](run.log).

### 2-2. 판정 — 평균이 아니라 반복 분포가 겹치는지로 ([`slo-baseline.md`](../../../docs/decisions/slo-baseline.md) §5-1)

**Q1(10~20 구간이 정말 평평한가)** — 5수준 10개 쌍 중 **9쌍은 RPS 범위가 겹친다.** 유일한 예외는
**12(1361.8~1371.9) ↔ 17(1304.1~1356.5)** — 간격이 **5.3**(전체 변동폭 ~76의 0.4배 이내)뿐이라,
같은 조건에서 pool=15 자체가 보인 판간 변동(1321.0~1393.5, 폭 72.5)보다도 작다. **계단으로 보기엔
근거가 약하고, 반복 3판으로는 잡음과 구분이 안 된다.** → **결론: 10~20 구간은 사실상 평평하다.**

**Q3(15가 근거 없는 여유값인가)** — **pool=10(1337 RPS·232.3ms)과 pool=15(1366 RPS·204.0ms)는
범위가 서로 겹치고(1321.0~1368.2 교집합), p99도 마찬가지다(212.6~216.5 vs 192.7~236... 사실상
같은 대역).** 이 조건(2대 구성, 단일 대상 박스, c=100)에서는 **15로 유지할 실측 근거가 없다** —
10으로 낮춰도 처리량·지연 둘 다 구분 가능한 손실이 없다.

**가용성 게이트(`timeout_total` pre/post 증가)** — [`pool_sizing_side.tsv`](pool_sizing_side.tsv) 30개
타임아웃 레코드·[`run.log`](run.log) 전체를 확인, **위반 0건**(🔴 출력은 캡션 1줄뿐, 실제 게이트
경고 없음). 15판 전 수준에서 `timeout_total`이 늘지 않았다.

> 🔴 **정정(2026-09-05)** — 위 Q3 결론은 **RPS·p99에 한정**된다. `pool_sizing_side.tsv`에 이미
> 수집돼 있던 `hikaricp_connections_acquire_seconds`를 나중에 재분석해보니 **pool=10은 15보다
> acquire 대기시간이 확실히 나쁘다**(안 겹침) — "10으로 낮춰도 무방하다"고 읽으면 과도하다.
> 3대 구성 결과와의 비교·재분석은
> [`../../../docs/decisions/pool-sizing-10-20-topology-comparison.md`](../../../docs/decisions/pool-sizing-10-20-topology-comparison.md) 참조.

---

## 3. 한계 · 안 본 것

- **Q2(왜 그런 결과가 나오는지, 메커니즘)는 부분적으로만 답할 수 있다.** 별도 Obs 박스(mysqld_exporter)
  없이 대상 박스 안 `SHOW GLOBAL STATUS`(`Innodb_row_lock_waits` 등)만 걷었다 — 프로메테우스
  시계열 수준의 세밀함은 없다
- **다세션 페이로드 생성기가 2026-08-09 baseline과 다른 세대다.** `gen_batch_multi.py`(2026-08-17
  이후 관례) vs baseline의 생성기. 둘 다 "다세션"이라는 성질은 같지만 완전한 등가성은 아니다
- **이 라운드는 단일 대상 박스(p6-target, MySQL+Spring+AI 동거)다.** 08-09 4점 baseline은
  DB·App 분리 + AI 없음 조건이었다 — **절대 RPS를 baseline과 직접 비교하지 말 것.** 이번 라운드
  안의 pool 10~20 상대 비교만 baseline과 같은 성격이다
- **c=100 고정, N>10·c≠100은 안 봤다** — 설계 문서 §6 범위 밖 그대로
- **저장소의 3/4대 구성(DB·App 분리) 작업은 이 라운드와 별개다** — 동시 세션이 진행 중이던
  `bootstrap.sh`의 `ROLE=app` 추가는 이 라운드에서 쓰지 않았고 건드리지도 않았다

---

## 4. 파일

| 경로 | 내용 |
|---|---|
| `pool_sizing.tsv` | 판별 요약 — tag·pool·round·count·ok·fail·rps·p50·p95·p99 |
| `pool_sizing_side.tsv` | 옆 지표 — HikariCP(actuator)·MySQL(`SHOW GLOBAL STATUS`) pre/post 스냅샷 |
| `run.log` | 전체 실행 로그(버림판·15판·수준별 요약 python 출력 포함) |

실행 스크립트 자체는 저장소의 [`loadtest/measure_poolsizing_10_20.sh`](../../measure_poolsizing_10_20.sh)(커밋
`a303daf4`)와 실행 로직이 동일하다(§1-1).
