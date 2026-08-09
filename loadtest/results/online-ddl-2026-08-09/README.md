# 무중단 스키마 변경 실측 — rig

상태: **사전 확인 통과 · 본 측정 미실행**
설계 문서: [`docs/decisions/online-ddl-vs-blocking-alter.md`](../../../docs/decisions/online-ddl-vs-blocking-alter.md)

`realmysql-experiments.md:140` 에 실측(96분)과 나란히 적혀 있던 «운영이면 pt-osc 무중단» 이라는 **권고**를 실측으로 바꾼다.

---

## 실행

```bash
docker compose up -d mysql
docker pull percona/percona-toolkit

bash loadtest/results/online-ddl-2026-08-09/probe.sh      # 전제 확인 — 반드시 먼저
bash loadtest/results/online-ddl-2026-08-09/ddl_sweep.sh  # 본 측정 (~3시간)
```

`probe.sh` 가 실패하면 `ddl_sweep.sh` 로 넘어가지 않는다. 특히 **[1] 이 «성공» 으로 나오면 실험 전제가 무너진 것**이다 — 도구 없이 무중단이 되는데 도구를 재는 셈이므로 설계부터 다시 본다.

## 파일

| 파일 | 역할 |
|---|---|
| `_rig.sh` | 공통부 — 시딩·writer 제어·지표·검증·로그 규약 |
| `probe.sh` | 사전 확인 — INPLACE 거절 여부, 트리거·binlog·디스크·PK, 도구 가용성 |
| `ddl_sweep.sh` | 본 측정 — 팔 A/B × 8판(버림 2 + 본판 6) |
| `writer.sql` | 백그라운드 writer 스토어드 프로시저 + 로그 테이블 |
| `ddl.tsv` | (실행 후) 판별 결과 |
| `*_writer.tsv` | (실행 후) 판별 writer 원시 로그 — 정지 구간의 근거 |
| `*_ptosc.log` | (실행 후) pt-osc 실행 로그 — 청크 인덱스 선택이 여기 있다 |

## D0 결과 — 전제가 섰다 (2026-08-09)

```
ERROR 1845 (0A000): ALGORITHM=INPLACE is not supported for this operation.
                    Try ALGORITHM=COPY.
```

`LOCK` 절을 빼고 `ALGORITHM=INPLACE` 만 던져도 같은 1845 다 — 거절 사유는 **INPLACE 자체**이지 `LOCK=NONE` 이 아니다. 원문은 [`probe_inplace_rejection.txt`](probe_inplace_rejection.txt).

> ⚠️ **1차 실행(22:58)의 «거절됨 ✅» 는 거짓이었다.** `PARTITION BY (...)` 뒤에 콤마로 `ALGORITHM` 을 이어 붙여 파서가 깨졌고(1064), `probe.sh` 가 rc≠0 만 보고 거절로 찍었다. 서버는 INPLACE 를 판정한 적이 없었다. MySQL 8.0 문법은 `alter_option [, ...] [partition_options]` 순서라 `PARTITION BY` 앞에 옵션이 와야 하고 콤마를 못 쓴다. 판정을 **에러 번호 기준**으로 바꿨다 — 1845/1846 만 거절, 1064 는 스크립트 결함으로 hard fail.

## [2] 함정 체크리스트 — 시딩 후 전부 판정됨

| 항목 | 값 | 판정 |
|---|---|---|
| 트리거 | 0개 | ✅ pt-osc 가 걸 자리가 비어 있다 |
| PK | `(id, created_at)` | 복합 PK 확인. pt-osc 의 청크 인덱스 선택을 실행 로그에서 볼 것 |
| 디스크 | 데이터 1,032MB · 여유 932,373MB | ✅ 사본까지 2배(2,064MB)를 담고도 남는다 |
| binlog | `log_bin=1 · ROW` | gh-ost 전제는 충족(본 측정은 제외) |
| 실 `pose_data` | 파티션 14개 | 대상 아님 — 이미 걸려 있다 |

> 첫 실행에선 이 표의 트리거·PK 가 `pose_data_scale` 없이 「0개 ✅」·「PK 없음」으로 찍혔다. **없는 테이블에 대한 통과**였다. probe 는 시딩 전에도 돌게 설계돼 있으므로(그래서 [1] 이 소형 복제본을 따로 만든다), 테이블 유무로 갈라 `⏸ 판정 안 됨` 을 따로 두고 최종 판정문에도 보류 개수를 적게 고쳤다. 디스크 항목도 시딩 전엔 `현재 크기 0` 이라 2배 조건이 통째로 스킵되던 것을, 예상치(원본+사본 2,200MB) 판정으로 채웠다.

## 결정된 것 (설계 §9)

| 항목 | 결정 |
|---|---|
| 축소 규모 | **1,000만 행** (13,334세션×750). 버림판이 소요시간 프로브를 겸한다 |
| gh-ost | **본 측정 제외.** `probe.sh` 가 binlog 전제만 확인하고, `PARTITION BY` 지원 여부는 미검증으로 남긴다 |
| 환경 | **로컬 docker.** 승격 트리거는 데이터 기반 — 팔 A·B 의 `ddl_s` 범위가 겹치면 EC2 로 |
| 게시 위치 | 이 디렉터리 |

## 설계 문서와 다르게 구체화한 것

- 설계 §4 의 «버림판 1» → **팔당 1판(총 2판)** 으로 구현. 팔 하나만 버리면 다른 팔의 첫 판이 여전히 «첫 판» 이라 버림의 목적이 절반만 달성된다.
- 판 순서는 라틴 방격 대신 **위치 상쇄 배열**(`A B B A A B`). 팔이 2개라 3라운드로는 완전 상쇄가 불가능하다.

## 이 rig 가 안 재는 것

- **절대 소요 시간의 운영 의미** — 로컬 2물리코어 값이다. 팔 간 상대 배수까지만 쓴다
- **트리거 오버헤드의 크기를 환경에서 분리하기** — 2코어에서 writer 를 동시에 돌리면 CPU 를 더 쓰는 팔 B 가 구조적으로 불리하다. 이 편향의 크기는 이번 설계로 분리 못 한다(설계 §5)
- **현재 `pose_data` 의 값** — 시더 정의가 실 테이블과 어긋나 있다(이슈 #153). `_rig.sh` 의 `seed_scale()` 주석에 경고를 승계해 뒀다

## 착수 전 알아둘 것 — 이 컨테이너는 공유 자원이다

사전 확인 중에 **다른 세션이 같은 `pose_data_scale` 에 `seed_scale()` 을 동시에 돌려** 시딩이 두 번 깨졌다([[project_concurrent_sessions]]). `seed_scale()` 은 맨 앞에서 `DROP TABLE` 을 하므로, 겹치면 먼저 돌던 쪽의 청크가 사라진 테이블에 쓰다 죽는다.

본 측정은 8판 × (시딩 + DDL) 로 3시간을 쓴다. **착수 전에 다른 세션이 이 컨테이너를 안 쓰는지 확인할 것.** 판 하나가 아니라 그 뒤 전부가 날아간다.

```bash
# seed_scale 을 도는 다른 프로세스가 있는지
powershell -NoProfile -Command "@(Get-CimInstance Win32_Process -Filter \"Name='bash.exe'\" | Where-Object { \$_.CommandLine -like '*seed_scale*' }).Count"
```

## 미검증 (실행하면 밝혀질 것)

- `pt-online-schema-change` 가 `--alter` 로 `PARTITION BY` 를 받는지 — 원리상 빈 새 테이블에 ALTER 를 적용하는 방식이라 될 것으로 보지만 **확인 안 됨**
- 복합 PK `(id, created_at)` 에서 pt-osc 가 어느 인덱스로 청크를 나누는지
- 1,000만 행이 팔 간 차이를 노이즈 위로 띄우기에 충분한 규모인지 — 버림판에서 드러난다
