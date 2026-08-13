# 백업/복구 — 사전 확인 (게이트 G1~G4) · 2026-08-13

설계: [`backup-restore-rto-rpo.md`](../../../docs/decisions/backup-restore-rto-rpo.md) §2·§5·§9-1 ④

**이 디렉터리에 측정치는 없다.** 「전제가 서는가」만 본다. Q1·Q2(몇 분인가)는 디스크 성능이
지배하므로 EC2 에서 잰다(설계 §5).

## 0. 판정 — 4개 전부 통과, EC2 승격 조건이 섰다

| 게이트 | 질문 | 결과 |
|---|---|---|
| **G1** | binlog 이 PITR 에 쓸 수 있는 형태인가 | ✅ `log_bin=1` · `binlog_format=ROW` |
| **G2** | XtraBackup 이 8.0.46 에 붙는가 | ✅ 붙고 백업 완료 — ⚠️ **단서 있음**(§2) |
| **G3** | **PITR 이 실제로 되는가** | ✅ **사고 직전 20,500행으로 정확히 복원** |
| **G4** | 계측이 정지를 잡는가 | ✅ 3초 잠금에 **2,311~2,672ms** 포착 |

재현: `bash probe.sh` (일부만: `GATES=G1,G2 bash probe.sh`)

⚠️ **실 DB(`shadowfit`)를 건드리지 않는다.** scratch DB `backup_lab` 에서만 논다
(③ `lock_lab` · ④ `mvcc_lab` 관례). 이 repo 에는 세션이 둘 이상 붙고 백엔드가 떠 있을 수 있다.

---

## 1. G1 — binlog 전제 ✅

```
log_bin                     1
binlog_format               ROW
binlog_row_image            FULL
gtid_mode                   OFF
binlog_expire_logs_seconds  2592000
server_id                   1
version                     8.0.46
```

- **`docker-compose.yml` 에 binlog 지정이 없는데도 켜져 있다** — MySQL 8.0 의 `log_bin`
  기본값이 ON 이기 때문이다. 설계 §7 이 「기본값을 실제로 확인」하라고 건 항목이 여기서 닫힌다.
- 🔴 **`gtid_mode=OFF` → PITR 은 포지션 기반**(파일명 + 오프셋)이다. 설계 §2 가 「GTID 인가
  포지션인가 시각인가가 실제로 사람이 막히는 지점」이라 했는데, 이 프로젝트는 **포지션 기반**으로
  확정됐다. G3 가 그 절차를 밟았다.
- **RPO 상한 = binlog 보존 2,592,000초(30일).** 백업 주기가 이보다 짧으면 그 사이는 메울 수 있다.

원문 [`G1_binlog.txt`](G1_binlog.txt).

## 2. G2 — XtraBackup ↔ 서버 8.0.46 ✅ (단서 있음)

도구 `8.0.35-36` (MySQL 8.0.35 기반) ↔ 서버 `8.0.46`. **버전이 다른데도 붙었다** — 연결·
`LOCK INSTANCE FOR BACKUP`·InnoDB 설정 인식·데이터파일 복사까지 정상 수행.

⚠️ **그런데 로그에 이 줄이 있다:**

```
[Xtrabackup] perl binary not found. Skipping the version check
```

🔴 **도구가 자기 버전 검사를 건너뛴 채 돌았다.** 「붙는다」와 「호환이 검증됐다」는 다르다.
→ **유효성의 진짜 판정은 «복구해서 행수·체크섬이 맞는가»** 이고, 그건 본 측정의 일이다.
본 측정 결과에 이 조건을 반드시 승계한다.

원문 [`G2_xtrabackup.log`](G2_xtrabackup.log).

## 3. G3 — PITR ✅ ⭐ 이 문서의 급소

시나리오: T0 백업 → T1 계속 쓰기 → T2 «사고»(대량 DELETE) → T3 복원 + binlog 재생.

| 시점 | 행수 |
|---|---|
| 시드 | 20,000 |
| **사고 직전** | **20,500** |
| 사고 후(원본) | 0 |
| 덤프만 복원 | 20,000 ← 백업 시점이라 T1 분량이 빠진 게 정상 |
| **PITR 후** | **20,500** ✅ |

**사고 직전과 정확히 일치하고, 사고(DELETE)는 재생되지 않았다.** 재생 SQL 25,255바이트.
복구는 **별도 컨테이너**에서 했다(설계 §3 안전 규칙 — 원본 덮어쓰기 금지).

원문 [`G3_pitr.txt`](G3_pitr.txt).

### 🔴 G3 이 잡아낸 함정 3개 — 이것이 이 게이트의 진짜 산출물

전부 **EC2 에서 만났으면 돈을 쓰고 「PITR 안 되네」로 끝났을** 것들이다. 셋 다 **에러 없이
조용히 틀린다**는 공통점이 있다.

**ㄱ. `mysqlbinlog` 이 `mysql:8.0` 공식 이미지에 없다.**
`mysql`·`mysqladmin`·`mysqldump`·`mysqlpump`·`mysqlsh` 는 있는데 **그것만 빠져 있다.**
→ **「binlog 켜져 있으니 PITR 된다」가 이 환경에서 거짓인 이유가 바로 이것이다.** binlog 은
켜져 있어도 **읽을 도구가 없다.** percona 이미지(`percona-xtrabackup`·`percona-toolkit`)에는
있으므로 datadir 를 읽기 전용으로 물려 거기서 돌린다.

**ㄴ. `--stop-datetime` 은 «mysqlbinlog 를 돌리는 프로세스» 의 로컬 타임존으로 해석된다.**
서버 시각도, 이벤트 저장 시각도 아니다. 서버 컨테이너가 KST 인데 UTC 값을 주니 9시간 과거로
읽혀 **모든 이벤트가 잘리고 replay.sql 이 0바이트로 나왔다 — 에러는 없었다.**
→ 헬퍼 컨테이너에 `TZ=UTC` 를 못박고 UTC 경계를 주는 것으로 구조적으로 막았다.

**ㄷ. `mysqladmin ping` 으로 복구 컨테이너를 기다리면 안 된다.**
MySQL 엔트리포인트는 초기화 중 **임시 서버**를 띄우는데 ping 이 거기 붙어 「떴다」고 오판한다.
그 뒤 서버가 재시작하면서 이어지는 명령이 전부 빈 값으로 돌아왔다.
→ **실제 쿼리(`SELECT 1`)가 성공할 때까지** 기다리도록 고쳤다.

> 그리고 rig 자체에서도 하나 고쳤다 — 초판은 **환경 실패와 PITR 실패를 구분하지 못해**
> 「디스크가 찼다」를 「PITR 결과가 다르다」로 찍었다. 이 repo 가 08-08 부터 경계해 온
> *「환경 결함이 측정 결과로 찍힌다」* 그 자체라 판정을 3분기(환경 실패 / 재생 0 / 값 불일치)로
> 갈랐다.

## 4. G4 — 계측이 정지를 잡는가 ✅

`FLUSH TABLES t FOR EXPORT` 로 3초 잠그고 그 사이 INSERT 를 시도 → **2,311~2,672ms 대기**.

⭐ **왜 이 게이트가 필요한가.** H1 은 「`--single-transaction` 은 사실상 안 멈춘다」인데,
**「안 멈춘다」는 관측은 「계측이 정지를 못 잡았다」와 구분되지 않는다.** 명백히 잠그는 팔(C)이
같은 rig 에 있어야 그 구분이 선다. 무중단 DDL 에서 팔 A 가 69초 정지를 잡아준 덕에 팔 B 의
30~364ms 를 믿을 수 있었던 것과 같은 구조다.

원문 [`G4_lock_observability.txt`](G4_lock_observability.txt).

---

## 5. 환경 메모 (EC2 에선 안 겪을 것들)

로컬 Windows + Docker Desktop 고유 문제라 **EC2(Linux)에서는 해당 없다.** 다만 로컬 재현 시
다시 밟게 되므로 남긴다.

- **Git Bash 경로 변환** — `docker run -v /var/lib/mysql:...` 이 `C:/Program Files/Git/var/...`
  로 바뀐다. `MSYS_NO_PATHCONV=1` 필요.
- **Docker Desktop VM 의 `/tmp` 는 795M tmpfs** 다. 백업 대상 디렉터리를 `/tmp` 아래 두면
  금방 차고, 그 증상이 **컨테이너 생성 실패**(`OCI runtime exec failed: no space left on device`)로
  나타나 원인이 안 보인다. rig 는 작업 디렉터리를 **호스트 디스크(`_work`)** 에 둔다.
- **percona 이미지 uid ↔ `mysql:8.0` datadir 소유자 불일치** → `--user 0` 필요(errno 13).

## 6. 다음 — EC2 본 측정

게이트가 섰으므로 §5 의 「얼마나」로 넘어간다. 무대는 **더미 JSON 1억 행 + real-JSON 소규모
대조 1판**(설계 §9-1). 결과는 `loadtest/results/backup-restore-aws-<날짜>/` 에 둔다.

⬜ **아직 안 만든 것**: 본 측정 스윕(`backup_sweep.sh`) — 팔 A·B 각 버림 1 + 본판 3, 팔 C 1판,
판 사이 컨테이너 재시작 + `drop_caches`, 비압축 파일 출력(전부 §9-1 확정 사항).
