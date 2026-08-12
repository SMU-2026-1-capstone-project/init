# EC2 탑승 목록 — 인프라를 띄울 때 같이 돌릴 것

작성일: 2026-08-12
상태: **목록 (실행 미착수)** — 착수·채택은 사용자 confirm 후 박제
연관: [`../docs/decisions/online-ddl-vs-blocking-alter.md`](../docs/decisions/online-ddl-vs-blocking-alter.md), [`../docs/portfolio/one-pager.md`](../docs/portfolio/one-pager.md), [`results/`](results/)

---

## 0. 이 문서가 있는 이유

**2026-08-08 에 한 번 놓쳤다.**

격자 재측정으로 EC2 3대를 띄웠고 `pose_data` 375만 행까지 시딩된 상태였는데, *"다시 띄울 일이
있으면 같은 쿼리를 한 번 돌려보는 것으로 충분하다"* 고 **문서에 적어둔 그 일이 실제로 생겼는데도**
안 돌리고 인프라를 삭제했다 ([`worst-section-rep-resolution.md:261`](../docs/decisions/worst-section-rep-resolution.md)).

원인은 게으름이 아니라 **목록이 흩어져 있었다는 것**이다. "다음에 인프라 띄울 때"는 서너 개
문서에 각자 적혀 있었고, 인프라를 띄운 사람은 그 문서들을 열 이유가 없었다.

이 파일이 그 한 곳이다. 그리고 이 프로젝트의 EC2 는 **항상 «임시 생성 → 측정 → 삭제»** 이므로
([`schema-migration-tracking.md:80`](../docs/decisions/schema-migration-tracking.md)),
실질적인 방어는 하나뿐이다 — **삭제 전에 §5 를 여는 것.**

---

## 1. 탑승 목록

승객은 두 등급이다. **主**는 그것 때문에 인스턴스를 띄우는 항목, **從**은 인프라가 이미 떠
있을 때만 값이 생기는 항목(단독으로는 EC2 비용을 정당화하지 못한다).

### 主 — 이것 때문에 띄운다

| # | 항목 | 왜 AWS 여야 하나 | 준비 상태 | 소요 |
|---|---|---|---|---|
| **P1** | **무중단 DDL 본 측정** | 로컬은 MySQL·writer·도구가 2코어를 공유해 팔 간 차이가 잡음에 묻힐 수 있다. 설계 §9 «AWS 로 올릴지» 미결정 항목 | ✅ rig 완성 · probe 통과 · #197 수정됨 | **~5.9시간** (파일 복원 시딩 채택 시 3.8) |
| **P2** | **다운샘플 «1.7배» 다세션 재측정** | 🔴 [`one-pager.md:43`](../docs/portfolio/one-pager.md) 의 정본 수치인데 조건이 **단일 핫세션**(`batch.json`, session 801)이다. **fsync 3.47배를 1.03배로 무너뜨린 바로 그 조건**이고, 문서에 «다세션에서 재측정한 적 없다» 가 그대로 붙어 있다 | 🟡 rig 있음 · 페이로드는 재생성 필요(§4) | 2~3시간 |
| **P3** | **백업/복구 RTO·RPO** | 물리 백업·복원·PITR 은 디스크 성능에 지배되므로 로컬 값이 의미 없다 | 🔴 **설계 문서 없음** — 착수 전 먼저 써야 한다 | 셋업 2~3h + 측정 2h |
| **P4** | **복제 지연 · 반동기 대가** | 인스턴스 2대가 전제 | 🔴 설계 문서 없음 | 반나절 |

> P2 가 눈에 띈다 — **이미 한 번 헤드라인을 무효화한 것과 같은 계열의 조건**이 아직 정본에
> 열린 채로 남아 있다. P1 과 같은 라운드에 태울 수 있으면 가장 싸다(단 §7 오염 주의).

### 從 — 인프라가 살아 있을 때만 (추가 비용 거의 0)

| # | 항목 | 내용 | 근거 |
|---|---|---|---|
| **R1** | **worst-section 쿼리 1회** | `reports` 중 `detailed_analysis` 가 채워진 행이 있는지, `pose_data` 가 비어 있는지. **로컬에선 이미 확인했고 EC2 배포분만 미확인**이다. 쿼리 한 번이면 끝난다 | [`worst-section-rep-resolution.md:261`](../docs/decisions/worst-section-rep-resolution.md) — §0 의 그 항목 |
| **R2** | **MySQL 지표 수집** | pool-cliff 초판이 «병목이 백엔드 CPU 로 이동» 을 적었다 **철회**한 사유가 정확히 **MySQL 지표 미수집**이었다. 이번엔 처음부터 걷는다 (OS 샘플러와 같은 결) | [`pose-ingest-downsampling.md:387`](../docs/decisions/pose-ingest-downsampling.md) |
| **R3** | **3-way 조인 hash join** | `reports ⋈ sessions ⋈ users`. ⑤ 옵티마이저 카드가 «AWS엔 그 테이블들이 비어 있어 범위 밖» 으로 닫아둔 미완부 | [`realmysql-experiments.md:225`](../docs/portfolio/realmysql-experiments.md) |

⚠️ **R1 과 R3 은 값 분포 한계에 걸린다.** 시딩이 단일 템플릿 복제라 카디널리티가 균일하다.
분포에 의존하는 결론은 내지 말고, **구조·존재 여부만** 본다.

---

## 2. 타지 않는 것 (이미 «AWS 불필요» 로 판정됨)

이 절이 목록을 짧게 유지한다. 「AWS니까 이것도 겸사겸사」로 부풀면 **또 안 보게 된다.**

| 항목 | 판정 근거 |
|---|---|
| 락 · MVCC · 격리수준 | «동시성 메커니즘 실험이라 AWS 불필요» — [`realmysql-experiments.md:188`](../docs/portfolio/realmysql-experiments.md) |
| admin `EXPLAIN` 스윕 | «`EXPLAIN` 은 옵티마이저의 선택이라 코어 수·경합과 무관. 같은 시딩이면 같은 답» — [`admin-page-scope.md:548`](../docs/decisions/admin-page-scope.md) |
| DELETE 파편화 후속 | 로컬 rig 로 이미 실측 완료 (`results/delete-fragmentation-2026-08-09/`). 남은 미검증(행 모양·파티션 상호작용)도 로컬로 충분 |

## 3. 이번엔 안 태우는 것 (비싸서)

| 항목 | 왜 |
|---|---|
| 통계 정확도 ↔ 플랜 흔들림 | 무대였던 1억 행 real-JSON(~230GB, m6i.xlarge+EBS 700GB)이 삭제됐다. **재구축 비용이 실험을 압도한다** |

---

## 4. 탑승 전 준비

매번 새 인스턴스라 **매번 든다.** 이 목록이 빠지면 판이 통째로 날아간다.

- [ ] `.env` 생성 — `MYSQL_ROOT_PASSWORD` 를 rig 기본 PW(`1234`)와 맞춘다. 안 맞으면 전 판 실패
- [ ] `docker pull percona/percona-toolkit` — 없으면 팔 B 4판이 전부 «DDL실패» 로 찍힌다. **도구의 성질이 아니라 환경 결함인데 표에는 똑같이 보인다**
- [ ] 🔴 **`batch_multi*.json` 은 `.gitignore` 되어 있다**(`ghz/.gitignore`, ~64MB). EC2 에는 없으므로 `gen_batch_multi.py` 로 재생성한다. FK 통과를 위해 **`exercise_sessions` 시드가 먼저** 있어야 한다
- [ ] `probe.sh` 재실행 — 환경이 바뀌었으므로 로컬 통과 이력을 재사용하지 않는다
- [ ] **`WRITER_MAX_SEC` 상향** — 기본 5,400s(90분)인데 팔 B 로컬 실측이 2,360s 다. EBS 가 로컬 NVMe 보다 느려 2~3배 늘면 **writer 가 DDL 도중 먼저 죽어 `max_stall`·`p50` 이 통째로 구멍난다**
- [ ] **축소 리허설** — `SESSIONS=134` 로 8판(~15분). 전 경로를 먼저 밟는다
- [ ] 디스크 — 팔 B 는 사본을 만든다. binlog 도 B판당 ~445MB 누적

---

## 5. 삭제 전 체크리스트 ⭐

**이 문서의 급소다. 인스턴스를 죽이기 전에 여기서 멈춘다.**

- [ ] **§1 從 항목(R1·R2·R3)을 돌렸는가** — 지금이 아니면 다음 인스턴스까지 못 잰다. 08-08 이 여기서 무너졌다
- [ ] **원시 파일을 회수했는가** (S3 sync 또는 stop 후 scp). pool-cliff 가 원시 39개를 커밋한 이유가 있다 — *"지난번에 안 남겨서 같은 질문에 두 번 돈을 냈다"*
- [ ] **조건을 기록했는가** — 인스턴스 타입 · 리전 · 디스크 종류/크기 · 측정 일시 · 판 수. 조건 없는 수치는 이 프로젝트에서 인용 불가다
- [ ] **판정이 뒤집혔는가** — 뒤집혔으면 설계 문서 결정 로그에 «뒤집힘» 으로 남긴다. 지우고 새로 쓰지 않는다
- [ ] **요금 태그가 붙어 있는가** (`Project=shadowfit-measure`, 인스턴스 + 볼륨). 살아 있을 때만 붙일 수 있다. 이 repo 에 EC2 요금 기록이 한 줄도 없는 이유가 이것이다 — 하루 이틀 뒤 Cost Explorer 에서 뽑아 `MANIFEST.txt` 의 `# 요금` 칸을 채운다
- [ ] 인스턴스·볼륨 **삭제** (볼륨이 남으면 요금이 계속 나간다)

---

## 6. 결과를 어디에 기록하나

측정이 끝나면 **repo 에 남기는 것까지가 한 판**이다. 경로는 기존 관례를 따른다.

| 대상 | 위치 | 무엇을 |
|---|---|---|
| 원시 데이터 · rig | `loadtest/results/<주제>-<날짜>/` + `README.md` | 판별 결과 · 측정 조건 · 재현 절차 · **방법론 오류까지** (pool-cliff §5 전례) |
| 실험 카드 | [`docs/portfolio/realmysql-experiments.md`](../docs/portfolio/realmysql-experiments.md) | 해당 카드에 «결과 ✅» 를 이어붙인다 |
| 정본 수치 | [`docs/portfolio/one-pager.md`](../docs/portfolio/one-pager.md) | **조건 칸을 비우지 않는다** |
| 서류 | `career-statement-dba.md` · `application-smilegate-dba.md` | 정본에서만 인용. 옮길 때 **조건을 떼지 않는다** |
| 판단이 바뀌면 | 해당 설계 문서 `결정 로그` | 뒤집힌 과정을 남긴다 |
| 결함이면 | GitHub 이슈 | 재현 절차 · 근거 `file:line` · **미검증 항목 명시** |

> 🔴 P2 가 «1.7배» 를 뒤집으면 **`one-pager.md:43` 뿐 아니라 그 수치를 인용한 곳 전부**를
> 같이 고쳐야 한다. 「3.47배를 인용한 13곳에 조건을 소급 표기」한 전례와 같은 작업이다.

---

## 7. 미결정 (사용자 confirm 필요)

- [ ] **인스턴스 타입·대수** — 4차 관례(c7i.2xlarge / m6i.xlarge). P4 는 2대 필수
- [ ] **P1 과 P2 를 같은 인스턴스에 태울지** — ⚠️ P1 의 DDL 이 디스크를 크게 먹어서 P2 의 쓰기 부하와 섞이면 **둘 다 오염된다.** 순차로 돌리거나 인스턴스를 나눠야 한다
- [ ] **회수 경로** — S3 sync(무인 실행이면 필수) vs stop 후 scp(단순하지만 다음 날 사람이 필요)
- [ ] **P3·P4 착수 여부** — 설계 문서부터 써야 한다. 그 전엔 탑승 대상이 아니다

---

## 결정 로그

- 2026-08-12: 목록 초안. 08-08 에 놓친 사고(§0)를 계기로 흩어져 있던 «다음에 띄울 때» 항목을
  한 곳에 모았다. 主 4건 · 從 3건 · 「타지 않음」 3건. **실행 미착수**, §7 미결정 4건.