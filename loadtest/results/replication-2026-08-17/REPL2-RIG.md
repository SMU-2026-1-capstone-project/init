# 복제 2대 rig — Q1(지연) · Q2(반동기의 대가)

설계: [`../../../docs/decisions/replication-lag-and-semisync.md`](../../../docs/decisions/replication-lag-and-semisync.md)
탑승: [`../../AWS-RIDE-ALONG.md`](../../AWS-RIDE-ALONG.md) 主-P4
같은 디렉터리의 [`README.md`](./README.md) 는 **로컬 라운드(Q3·Q4·Q5)** 의 결과다. 이 문서는 그 라운드가
「EC2 2대 필요」로 남긴 **Q1·Q2 를 재는 장치**를 다룬다. 아직 **한 판도 안 돌았다.**

| 파일 | 역할 |
|---|---|
| `repl2_rig.sh` | 공통부 — 접속·server_id·GTID·복제 연결·반동기·하트비트·샘플러·writer·집계 |
| `repl2_probe.sh` | 무대 세우기 + 게이트 G1~G3. **여기서 막히면 본 측정을 안 돈다** |
| `repl2_sweep.sh` | 본 측정 — 팔 A·B × (버림1 + 본판3) + 핫세션 대조 2판 |

---

## 0. 이 rig 의 자리

로컬 라운드(2026-08-17)는 Q3 을 **못 닫았다.** 이유가 현상이 아니라 계측이었다 —
`SOURCE_DELAY=5` 로 만든 정확한 5초를 7~11.5초로 찍었다. 질의 왕복이 현상보다 굵었다.

그래서 이 rig 의 첫 번째 설계 조건은 **계측 바닥을 같이 재는 것**이다.

```
lag_rep = 지금 - 리플리카가 들고 있는 heartbeat     ← 하트비트 주기 + 왕복 + 진짜 지연
lag_src = 지금 - 소스가 들고 있는 heartbeat         ← 하트비트 주기 + 왕복
lag_net = lag_rep - lag_src                         ← 정본 지표. 진짜 복제 지연만 남는다
```

`lag_src` 는 **버리는 값이 아니라 같은 표에 서는 값**이다. 「지연이 작다」와 「계측이 굵다」가
이 열 하나로 갈린다.

시계 함정(설계 §7)도 여기서 닫힌다 — 하트비트를 쓰는 것도 소스, 읽는 것도 **소스 박스의
시계**다. 리플리카의 시계는 어디에도 안 들어간다. 그래서 **러너는 소스 박스에서 돈다**
(P6 처럼 부하기가 따로 있는 구성이 아니다).

---

## 1. 사람이 먼저 해야 하는 것

1. **인스턴스 2대 — 타입이 같아야 한다.** 다르면 관측된 지연이 「복제 구조 때문」인지
   「기계 차이 때문」인지 원리적으로 안 갈린다(설계 §3). 「리플리카는 싼 걸로」는 운영
   선택지지 이 실험의 조건이 아니다
2. **보안그룹** — 소스 → 리플리카 **3306**, 소스 → 리플리카 **22**(SSH).
   리플리카 → 소스 **3306**(복제가 이 방향으로 붙는다). 08-12·08-13 라운드(1대)엔
   없던 요구다
3. **SSH 키를 소스 박스에** — 소스가 리플리카를 몬다(사본 붓기·컨테이너 기동)
4. **두 박스 다 `ROLE=db` 로 부트스트랩** — MySQL 만 있으면 된다. 이 rig 은 백엔드·AI 를
   안 쓴다(§3)
5. **AZ 를 정한다** — 같은 AZ / 다른 AZ. 설계 §9-1 ② 가 「이 문서에서 제일 중요한 미결정」
   이라 부른 항목이고, **이 선택이 Q2 의 답을 자릿수 단위로 바꾼다.** rig 은 정하지 않고
   `REPL_AZ_MODE` 라벨과 측정된 왕복만 조건 칸에 박는다

---

## 2. 실행

```bash
# 소스 박스에서
cd /root/init
export REPLICA_HOST=10.0.0.6
export REPLICA_SSH="ssh -i /root/.ssh/measure.pem -o StrictHostKeyChecking=no root@10.0.0.6"
export REPL_AZ_MODE="same-az(ap-northeast-2a)"     # 조건 칸에 그대로 들어간다

bash loadtest/results/replication-2026-08-17/repl2_probe.sh   # 무대 + 게이트
bash loadtest/results/replication-2026-08-17/repl2_sweep.sh   # 본 측정
```

무인으로 돌릴 때는 러너를 쓴다(단계·상한·S3 업로드·매니페스트가 붙는다).
🔴 `preflight` 가 아니라 **`repl_preflight`** 를 쓴다 — 일반 preflight 는 이 라운드에
필요 없는 percona-toolkit 이미지를 묻고, 정작 물어야 할 리플리카 도달성은 안 묻는다:

```bash
S3_BASE=s3://버킷/shadowfit REPLICA_HOST=10.0.0.6 \
REPLICA_SSH="ssh -i /root/.ssh/measure.pem -o StrictHostKeyChecking=no root@10.0.0.6" \
REPL_AZ_MODE="same-az" PHASES="repl_preflight repl_gate repl ridealong collect" \
  nohup bash loadtest/aws/run_all.sh > /root/run_all.log 2>&1 &
```

### 손잡이

| 변수 | 기본 | 근거 |
|---|---|---|
| `SESSIONS` | `13334` (1,000만 행) | 설계 §9-1 ① |
| `CONNS` | `15` | `backend/.../application.yml:50` maximum-pool-size — 실 경로의 DB 동시성 상한 |
| `ROWS_PER_TX` | `25` | `loadtest/ghz/gen_batch_multi.py` 기본 `--reps` — 한 요청의 프레임 수 |
| `DUR` | `180` | 판당 초 |
| `REPS` / `ARM_ORDER` | `3` / (REPS 에서 생성) | 위치 합을 맞춘 배열(설계 §4). `REPS=3` → `A B B A A B`. `ARM_ORDER` 를 직접 주면 그쪽이 이기고, `REPS` 와 어긋나면 로그에 적는다 |
| `GTID` | `1` | 설계 §9-1 ④ — **무대에서만** 켠다. 실 compose 는 안 건드린다 |
| `REPLICA_INIT` | `xtrabackup` | 설계 §9-1 ③. 실패하면 논리 덤프로 자동 되돌림 |
| `PER_ROUND_REINIT` | `0` | §4 와 다른 지점 — 아래 §4 |
| `SEMISYNC_TIMEOUT_MS` | `10000` | MySQL 기본값. 안 건드리는 것도 조건이라 적는다 |

---

## 3. 무엇이 나오나

러너(`run_all.sh`)로 돌리면 `OUT` 을 `<결과>/repl/` 로 넘기므로 산출물이 **`repl/` 바로 아래**
떨어진다. `_out2/` 는 rig 을 손으로 돌릴 때의 기본값이다.

```
_out2/
├── D0_preflight.txt      ← 양쪽에 무엇이 켜져 있나 (측정 아님)
├── gates.tsv             ← G1·G2·G3 판정
├── G3_positive_control.tsv / G3_summary.txt
├── replica_build.txt     ← 초기화 경로·소요 + **따라잡기 시간**(관측, §9-1 🔶②)
├── rtt.txt               ← 리플리카까지의 왕복. Q2 를 지배하는 조건
├── conditions.txt        ← 이 라운드의 조건 전부
├── repl2.tsv             ← 판별 결과 (아래)
└── _raw/<판>/            ← lag.tsv(1초 샘플) · conn_*.tsv(트랜잭션별 커밋 지연)
```

러너로 돌리면 `<결과>/ridealong/` 도 같이 선다 — `PHASES` 에 `ridealong` 이 있어서다.
이 rig 의 산출물이 아니라 **從 항목**이고, 복제 라운드에 넣은 이유는 R2 하나다:

| | |
|---|---|
| **R2** MySQL 지표 | `SHOW GLOBAL STATUS`·`VARIABLES` + top digest. 🔴 **복제 라운드에서 특히 값이 있다** — 반동기 상태 변수와 복제 지표가 같은 시점에 걷힌다. 「왜 그 수치였나」를 나중에 물을 때 유일한 재료이고, 그게 없어서 판정을 철회한 전례가 있다(pool-cliff 초판) |
| **R1** worst-section | ⚠️ **이 무대에서는 전부 0 이 나온다.** `reports`·`exercise_sessions` 를 아무도 안 채우기 때문이다(무대는 `pose_data_scale`·`replprobe` 뿐). 「데이터가 없다」는 R1 이 물은 질문의 답이 아니다 — 같이 도는 것을 막을 손잡이가 없어서 남을 뿐이다([#371](https://github.com/Shadowfit/init/issues/371)) |
| **R3** 3-way 조인 | 안 돈다. `reports`·`exercise_sessions`·`users` 시딩이 선행이고 이 라운드는 그걸 안 만든다. `SKIPPED.txt` 에 사유가 남는다 |

`repl2.tsv` 읽는 법:

| 열 | 뜻 |
|---|---|
| `kind` | `discard` 는 버림판이다 — **표에 넣지 말 것** |
| `status` | `FAIL` 은 **「재지 못했다」** 다. 나머지 열의 `-` 를 0 으로 읽지 말 것 |
| `tps` · `c_p99_us` | Q2. 팔 A ↔ 팔 B 를 **같은 페이로드끼리만** 비교 |
| `lag_p50/p95/max_us` | Q1. `lag_net` 기준(계측 바닥이 빠진 값). **음수가 나올 수 있고 버리지 않는다** — 실제 지연이 계측 잡음보다 작았다는 관측이다. 못 읽은 표본만 `NA` 로 빠진다 |
| `sbs_p50` · `sbs_max` | `Seconds_Behind_Source`. **정본이 아니라 대조용** — 로컬 라운드가 이미 「침묵한다」를 확인했다 |
| `yes_tx_d` · `no_tx_d` | 반동기가 실제로 돌았는지. 팔 B 인데 `no_tx` 가 늘면 강등이다 |
| `rows_before` | 판 시작 시점의 무대 크기. 판이 거듭될수록 커진다(§4 참고) |

---

## 4. 설계와 다른 지점 (의도적, 결과 문서에 승계할 것)

| 설계 | 이 rig | 왜 |
|---|---|---|
| §3 무대 표: 부하는 `gen_batch_multi.py`(ghz→Spring) | **rig 내장 SQL writer** | Q2 는 커밋 경로의 값을 묻는데 앱 경로는 Spring 풀·GC 를 교란 변수로 같이 들인다. **대가: 4차 라운드의 649 RPS 와 같은 단위로 비교 불가** |
| §4: 매 판 백업본에서 리플리카 재구성 | 초기 1회 + 판 사이엔 **좌표 일치로 따라잡기 판정** | 2대에서 그 절차는 판마다 사본·전송·붓기·기동이라, 무인 라운드에서 제일 잘 깨지는 경로를 10번 밟는다. 대신 판마다 `rows_before` 를 남겨 무대가 커지는 것을 표에서 보이게 했다. `PER_ROUND_REINIT=1` 이면 설계대로 돈다 |
| §9-1 ⑧: 게이트 G4(강등 관측) | **안 돈다** | Q4 는 2026-08-17 로컬 라운드가 닫았다. 리플리카를 죽였다 살리는 절차는 본 측정 앞에서 따라잡기를 새로 만든다 |

**「따라잡음」의 판정에 임계값을 쓰지 않는다.** 「지연 N초 이하면 안정」은 근거가 없다.
GTID 면 `WAIT_FOR_EXECUTED_GTID_SET`, 아니면 파일·오프셋 일치 — **좌표가 같아졌는가**
라는 이진 사실로만 본다.

---

## 5. 이 rig 이 미리 막아둔 함정

설계 §7 체크리스트에 이 무대에서 실제로 걸리는 것들이 있다. 각각 어디서 막는지:

| 함정 | 어디서 |
|---|---|
| `server_id` 중복 | `set_server_ids` — 두 값이 같으면 거기서 멈춘다 |
| 🔴 **`server_uuid` 중복** | `_rebuild_xtrabackup` — 물리 사본은 `auto.cnf` 를 데려온다. 안 지우면 **UUID 가 같아 복제가 거부된다.** server_id 만 갈라놨다고 끝난 게 아닌, 두 번째 문 |
| 소스의 `SET PERSIST` 값이 사본을 타고 옴 | 같은 자리에서 `mysqld-auto.cnf` 도 지운다 |
| 🔴 **하트비트 EVENT 가 리플리카에서도 돈다** | `harden_replica` — 물리 사본은 이벤트를 ENABLED 인 채 데려온다. 리플리카가 같은 행을 쓰면 **지연이 항상 0 으로 보인다**(재려는 값이 계측 때문에 사라진다) |
| caching_sha2 비 TLS 인증 | `GET_SOURCE_PUBLIC_KEY=1` 로 먼저 붙고, 막히면 native 로 1회 되돌린 뒤 **어느 쪽으로 붙었는지 기록** |
| 반동기를 한쪽만 켬 | `semisync_on` 이 양쪽을 켜고, G2 가 `status=ON` **+ `yes_tx` 증가**까지 확인 |
| 플러그인 신·구 이름 혼용 | `detect_semisync_names` — 파일 존재로 판정하고 상태 변수도 같은 계열로 읽는다 |
| 내구성 레버가 조건을 바꿈 | `assert_durability` — 기본(1/1)이 아니면 멈춘다 |
| 초기 따라잡기가 측정에 섞임 | 판 시작 전에 좌표 일치를 기다린다. 그 시간은 «관측» 으로 따로 기록 |
| `\G` 를 `-N -B` 와 같이 써서 빈 값 | `rstat` 이 정렬 출력에서만 뽑는다 |
| `asort` (gawk 전용) | 안 쓴다 — 정렬은 `sort(1)`, awk 는 세기만 |

---

## 6. 아직 안 밟아본 것

- **이 rig 은 EC2 에서 한 번도 안 돌았다.** 첫 실행은 `SESSIONS` 를 줄여
  (예: `SESSIONS=134`) `repl2_probe.sh` 만 끊어 돌리는 편이 싸다
- `_rebuild_xtrabackup` 의 원격 절차(사본 전송 → 볼륨 교체 → compose 기동)는 **코드로만
  있고 실행 이력이 없다.** 논리 덤프 되돌림이 그 대비다
- Q3(read-after-write)은 이 rig 의 범위가 아니다. 로컬 라운드가 「이 계측으로는 못 잰다」로
  닫았고, 그 재도전은 별건이다
