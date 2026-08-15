# EC2 탑승 목록 — 인프라를 띄울 때 같이 돌릴 것

작성일: 2026-08-12
상태: **3회 탑승 완료 (P1 2026-08-12 · P3 2026-08-13 · P3-b 2026-08-13~14) + 팔 B 교정 1판 (2026-08-14)**
      — 남은 主 는 P2·P4·P5, 착수·채택은 사용자 confirm 후 박제
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
| ~~**P1**~~ | ~~**무중단 DDL 본 측정**~~ | 로컬은 MySQL·writer·도구가 2코어를 공유해 팔 간 차이가 잡음에 묻힐 수 있다. 설계 §9 «AWS 로 올릴지» 미결정 항목 | ✅ **완료 (2026-08-12)** — [결과](results/online-ddl-aws-2026-08-12/README.md) | **실측 29분** (예상 ~5.9시간은 `WRITER_MAX_SEC` 상한 기준이었고, 1,000만 행에선 판당 1~2분) |
| **P2** | **다운샘플 «1.7배» 다세션 재측정** | 🔴 [`one-pager.md:43`](../docs/portfolio/one-pager.md) 의 정본 수치인데 조건이 **단일 핫세션**(`batch.json`, session 801)이다. **fsync 3.47배를 1.03배로 무너뜨린 바로 그 조건**이고, 문서에 «다세션에서 재측정한 적 없다» 가 그대로 붙어 있다 | 🟡 rig 있음 · 페이로드는 재생성 필요(§4) | 2~3시간 |
| ~~**P3**~~ | ~~**백업/복구 RTO·RPO**~~ | 「몇 분인가」는 디스크 성능이 지배한다. 단 「PITR 이 되는가」는 이진 사실이라 **로컬에서 먼저** 확인하고 올린다 | ✅ **완료 (2026-08-13)** — [결과](results/backup-restore-aws-2026-08-13/README.md). 미검증 2건은 §6 결정 로그 | **실측 3.11시간**(러너 2.97h — 본 측정만 2h54m) |
| ~~**P3-b**~~ | ~~**백업 재측정 — 내구성·행 크기**~~ | 🔴 08-13 라운드가 두 곳에서 다른 것을 쟀다. ① 팔 B 복구가 **페이지 캐시**를 쟀고([#201](https://github.com/Shadowfit/init/issues/201)), ② 설계에서 확정됐던 **real-JSON 대조**가 rig 에 없었다([#202](https://github.com/Shadowfit/init/issues/202)). 둘 다 **디스크가 지배**해서 로컬로는 못 닫는다 | ✅ **완료 (2026-08-13~14)** — [b 라운드](results/backup-restore-aws-b-2026-08-13/README.md) · [팔 B 교정](results/restore-reflink-2026-08-14/README.md). #201 은 원인이 캐시가 **아니라 xfs reflink** 였고([#210](https://github.com/Shadowfit/init/issues/210)), #202 는 돌았는데 **예상과 방향이 반대**였다 | **실측 3.32시간**(러너 3.06h) + 교정 1판 |
| **P4** | **복제 지연 · 반동기 대가** | 인스턴스 2대가 전제. ⭐ **반동기 대가는 AZ 간 RTT 에 지배**되므로 로컬에선 구조적으로 과소평가된다 | 🟡 **설계 완료 (2026-08-13)** · rig 없음 — [`../docs/decisions/replication-lag-and-semisync.md`](../docs/decisions/replication-lag-and-semisync.md) | 게이트 2~3h + 측정 2~3h |
| **P5** | **세션 분산도 스윕** (1·2·5·20·100) | 🔴 정본 baseline **649.4 RPS 가 «100세션» 에서 나온 값**인데 그 100 은 잰 값이 아니다. 이 앱은 회원당 활성 세션이 1개라 **동시 세션 수 = 동시에 운동 중인 사람 수**다 — baseline 이 가정한 부하보다 분산된 조건일 수 있다. 4대 구성이라 로컬 불가 | 🟢 **설계·rig 완료 (2026-08-14)** — [`../docs/decisions/session-spread-sweep.md`](../docs/decisions/session-spread-sweep.md) · [rig](results/session-spread-2026-08-13/README.md) | 主 25판 + 從 6판 · **지켜보는 1~2h**(무인 아님) |

> P2 가 눈에 띈다 — **이미 한 번 헤드라인을 무효화한 것과 같은 계열의 조건**이 아직 정본에
> 열린 채로 남아 있다. P1 과 같은 라운드에 태울 수 있으면 가장 싸다(단 §7 오염 주의).

### 從 — 인프라가 살아 있을 때만 (추가 비용 거의 0)

| # | 항목 | 내용 | 근거 |
|---|---|---|---|
| **R1** | **worst-section 쿼리 1회** | `reports` 중 `detailed_analysis` 가 채워진 행이 있는지, `pose_data` 가 비어 있는지. **로컬에선 이미 확인했고 EC2 배포분만 미확인**이다. 쿼리 한 번이면 끝난다 | [`worst-section-rep-resolution.md:261`](../docs/decisions/worst-section-rep-resolution.md) — §0 의 그 항목 |
| **R2** | **MySQL 지표 수집** | pool-cliff 초판이 «병목이 백엔드 CPU 로 이동» 을 적었다 **철회**한 사유가 정확히 **MySQL 지표 미수집**이었다. 이번엔 처음부터 걷는다 (OS 샘플러와 같은 결) | [`pose-ingest-downsampling.md:387`](../docs/decisions/pose-ingest-downsampling.md) |
| **R3** | **3-way 조인 hash join** | `reports ⋈ sessions ⋈ users`. ⑤ 옵티마이저 카드가 «AWS엔 그 테이블들이 비어 있어 범위 밖» 으로 닫아둔 미완부 | [`realmysql-experiments.md:225`](../docs/portfolio/realmysql-experiments.md) |
| **R4** | **커넥션 수 스윕 (네트워크 축)** | 「`--connections` 1→16 이 230→211」이 **HTTP/2 멀티플렉싱 때문인지 fsync 에 가려진 것인지** 안 갈렸다. 완화 조건에서 다시 돌리면 갈린다. ⚠️ **P5 라운드 전용** — 부하기·페이로드가 그 rig 것이라 다른 라운드엔 못 얹는다 | [`four-axes-depth-experiments.md` §3-2](../docs/decisions/four-axes-depth-experiments.md) · rig `conn_ridealong.sh` |

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
- [ ] 🔴 **설계 문서의 «확정된 것» ↔ rig 대조** — 08-13 라운드에서 사용자 confirm 으로 확정됐던
  «real-JSON 축소 대조 1판» 이 **rig 에 아예 구현돼 있지 않았고**, 그대로 무인 실행됐다.
  표는 정상으로 나오므로 **사후에 안 보인다.** 설계의 확정 목록을 열어 **팔·무대가 실제로
  코드에 있는지** 한 줄씩 대조한다

---

## 5. 삭제 전 체크리스트 ⭐

**이 문서의 급소다. 인스턴스를 죽이기 전에 여기서 멈춘다.**

- [ ] **§1 從 항목(R1·R2·R3)을 돌렸는가** — 지금이 아니면 다음 인스턴스까지 못 잰다. 08-08 이 여기서 무너졌다
- [ ] **원시 파일을 회수했는가** (S3 sync 또는 stop 후 scp). pool-cliff 가 원시 39개를 커밋한 이유가 있다 — *"지난번에 안 남겨서 같은 질문에 두 번 돈을 냈다"*
  ```bash
  # 위치는 MANIFEST.txt 의 «S3 결과» 줄에 있다 (#198 로 추가). 읽기 권한은 #199 참조.
  aws s3 sync s3://<버킷>/<프리픽스>/<RUN_ID>/ loadtest/results/<주제>-aws-<날짜>/
  ```
  🔴 **삭제 전에 회수한다.** 인스턴스를 지우면 `/root/run_all.log` 는 사라지고 S3 사본만 남는다
- [ ] **조건을 기록했는가** — 인스턴스 타입 · 리전 · 디스크 종류/크기 · 측정 일시 · 판 수. 조건 없는 수치는 이 프로젝트에서 인용 불가다
- [ ] **판정이 뒤집혔는가** — 뒤집혔으면 설계 문서 결정 로그에 «뒤집힘» 으로 남긴다. 지우고 새로 쓰지 않는다
- [ ] **요금 태그가 붙어 있는가** (`Project=shadowfit-measure`, 인스턴스 + 볼륨). 살아 있을 때만 붙일 수 있다. 이 repo 에 EC2 요금 기록이 한 줄도 없는 이유가 이것이다 — 하루 이틀 뒤 Cost Explorer 에서 뽑아 `MANIFEST.txt` 의 `# 요금` 칸을 채운다
- [ ] 인스턴스·볼륨 **삭제** (볼륨이 남으면 요금이 계속 나간다)
  ```bash
  bash scripts/aws_teardown.sh list          # 무엇이 남아 있나 + 정지 인스턴스 경고
  bash scripts/aws_teardown.sh sweep --yes   # stopped 인스턴스 + 미연결 볼륨
  ```
  🔴 **`AUTO_SHUTDOWN=1` 은 stop 이지 terminate 가 아니다.** 그건 의도다(`/root/run_all.log` 가
  사라지면 사후 진단이 막힌다 — #203 이 그 로그로 잡혔다). 그래서 **지우는 일은 사람이 따로**
  해야 하고, 08-13 에 실제로 그 단계가 빠져 gp3 250GB 가 남아 있었다. `sweep` 은 **도는
  인스턴스를 안 건드리고** 다른 프로젝트 태그(`Project=DOCKin`)는 거부한다

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
- [x] **회수 경로** — ~~S3 sync vs stop 후 scp~~ → **S3 sync 로 확정.** 08-12 에 결정하고
  #198·#199 로 읽기 쪽을 닫았으며, **08-13 라운드에서 69개 객체 전량 회수로 실사용 확인**했다
- [ ] **P3·P4 착수 여부** — ~~설계 문서부터 써야 한다. 그 전엔 탑승 대상이 아니다~~
  → **둘 다 설계 완료.** P3 는 게이트 통과 후 08-13 라운드로 착수했고, **P4 는 설계만 서고 착수
  미결정**이다([`../docs/decisions/replication-lag-and-semisync.md`](../docs/decisions/replication-lag-and-semisync.md) §9 미결정 9건)
- [ ] 🔴 **P4 는 2대 구성이라 이 목록의 전제가 하나 깨진다** — 지금까지 라운드는 전부 1대였다.
  보안그룹 3306 인바운드 · `server_id` 분리 · (다른 AZ 대조판이면) AZ 간 전송 요금이 새로 붙는다

---

## 결정 로그

- 2026-08-12: 목록 초안. 08-08 에 놓친 사고(§0)를 계기로 흩어져 있던 «다음에 띄울 때» 항목을
  한 곳에 모았다. 主 4건 · 從 3건 · 「타지 않음」 3건. **실행 미착수**, §7 미결정 4건.

- **2026-08-12: 첫 탑승. P1 완료 · R2 완료 · R1·R3 은 못 탔다.** 인스턴스 1대(m6i.xlarge),
  부팅~정지 49분(러너 29분). 결과 [`results/online-ddl-aws-2026-08-12/`](results/online-ddl-aws-2026-08-12/README.md).
  - **이 목록이 실제로 값을 했다.** R2(MySQL 지표)가 같은 라운드에 실려 추가 비용 없이 걷혔다.
  - 🔴 **그런데 §5 체크리스트 1번(「從 항목을 돌렸는가」)이 또 절반 무너졌다.** R1·R3 이 둘 다
    **`reports` 테이블 부재**로 막혔다 — 러너는 DDL 측정에 필요한 mysql 만 띄우고 백엔드·Flyway 를
    올리지 않는다. **08-08 과 같은 실패가 아니라 새 실패다**(그때는 「목록이 흩어져서」, 이번엔
    「부트스트랩이 스키마를 안 만들어서」). 다음 라운드 전에 `bootstrap.sh` 에 Flyway 실행을 넣어야
    한다. 인프라가 살아 있을 때만 잴 수 있는 항목이라 또 놓치면 다음 인스턴스까지 밀린다.
  - **회수 경로에서 결함 2건**([#198](https://github.com/Shadowfit/init/issues/198)
    ·[#199](https://github.com/Shadowfit/init/issues/199)): 러너가 «어디에 올렸는지» 를 안 남기고,
    S3 를 읽을 권한을 가진 주체가 «업로드에 성공하면 스스로 꺼지는 인스턴스» 뿐이었다.
    §7 「회수 경로」 미결정 항목이 **S3 sync 로 정해졌지만 읽기 쪽이 안 닫혀 있었다.**
  - **요금 기록의 첫 사례**: 가동 0.80시간 / 러너 0.48시간. 차이 19분은 부팅 + `compose up` 실패
    디버깅 + 수정(`6996e1c`)이다. 청구액은 Cost Explorer 에서 `Project=shadowfit-measure` 로 뽑아
    `MANIFEST.txt` 의 `# 요금` 칸에 채운다.

- **2026-08-13: P4 설계 문서가 섰다** — [`replication-lag-and-semisync.md`](../docs/decisions/replication-lag-and-semisync.md).
  §7 「미결정」의 「P3·P4 착수 여부」 중 P4 쪽 전제(「설계 문서부터」)가 닫혔다. **착수 결정은
  아니다.** 반동기 플러그인은 착수 전에 이미 확인됐고(`semisync_source.so`·`semisync_replica.so`
  가 측정용 `mysql:8.0` 이미지에 있음), **리플리카 초기화는 08-13 백업 라운드의 G5(XtraBackup
  복구 유효)를 그대로 재사용**한다 — 主 항목이 다음 主 항목의 무대를 세운 첫 사례다.
  - 🔴 **이 항목만 2대 구성이라 §4·§5 의 전제가 하나 깨진다** — 보안그룹 3306 인바운드,
    `server_id` 분리, (다른 AZ 대조판이면) AZ 간 전송 요금. 지금까지 라운드는 전부 1대였다.

- **2026-08-13: 두 번째 탑승. 主 P3 완료 · 從 R1·R2 완료 · R3 만 못 탔다.** 인스턴스 1대
  (m6i.xlarge / ap-northeast-2c / gp3 200GB), **가동 3.11h · 러너 2.97h**, 무인 실행.
  결과 [`results/backup-restore-aws-2026-08-13/`](results/backup-restore-aws-2026-08-13/README.md).
  - ⭐ **§5 체크리스트 1번이 처음으로 안 무너졌다.** 08-08 은 「목록이 흩어져서」, 08-12 는
    「부트스트랩이 스키마를 안 만들어서」 從 항목을 놓쳤다. `bootstrap.sh` 에 Flyway 를 넣은
    수정이 여기서 효과를 확인했고, **R1 이 「테이블 부재」가 아니라 「테이블은 있고 전부 0행」**
    이라는 답을 냈다. R3(3-way 조인)은 시딩이 선행이라 여전히 SKIPPED — 이건 인프라 문제가
    아니라 무대 문제다.
  - **회수 경로가 실제로 값을 했다**(#198·#199 수정분의 첫 실사용). 러너가 스스로 정지했고
    (최종 업로드 성공 시에만 끄므로 그 자체가 완주 신호), 사람이 `aws s3 sync` 로 69개 객체를
    전량 회수했다. **08-12 에 열려 있던 「S3 를 읽을 주체가 없다」가 닫혔다.**
  - 🔴 **결과 표에서 결함 2건이 나왔다** — 둘 다 「숫자는 나오는데 조용히 다른 것을 잰」 부류다.
    ① 팔 B 복구 시간이 페이지 캐시를 쟀다([#201](https://github.com/Shadowfit/init/issues/201)),
    ② 설계에서 확정됐던 **real-JSON 축소 대조 1판이 rig 에 아예 없었다.**
    ②는 「탑승 목록」이 못 막는 종류다 — 이 문서는 «무엇을 태울지» 를 막지 «태운 것이 설계대로인지»
    를 막지 않는다. 다음 라운드 전에 **설계 §9-1 확정 항목 ↔ rig 대조**를 §4 준비에 넣어야 한다.
  - **삭제까지 끝냈다** — 인스턴스 terminate, 볼륨 200GB 는 `DeleteOnTermination=true` 로 함께
    삭제 확인(`InvalidVolume.NotFound`). 08-12 에 지적된 「stopped 인스턴스에 볼륨이 붙어 계속
    과금」 이 이번엔 반복되지 않았다. 청구액은 Cost Explorer 반영 후 `MANIFEST.txt` 에 채운다.
