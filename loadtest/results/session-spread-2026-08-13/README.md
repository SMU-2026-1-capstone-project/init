# 세션 분산도 스윕 — rig (2026-08-13)

설계: [`../../../docs/decisions/session-spread-sweep.md`](../../../docs/decisions/session-spread-sweep.md)
공통부: [`../commit-count-2026-08-09/_rig.sh`](../commit-count-2026-08-09/_rig.sh) (4차 rig 을 그대로 쓴다)

> ⚠️ **이 디렉터리는 아직 rig 뿐이다. 측정값은 없다.**
> 측정 라운드는 관례대로 `session-spread-aws-<측정일>/` 로 따로 남는다.

---

## 무엇을 재는가

**부하가 몇 개 세션에 흩어져 있는가** 하나만 흔든다. 세션 수 1 · 2 · 5 · 20 · 100.

정본 baseline **649.4 RPS 는 «100세션» 에서 나온 값**인데 그 100 은 측정으로 고른 값이
아니다. 그리고 이 앱은 회원당 활성 세션이 1개라 **동시 세션 수 = 동시에 운동 중인 사람 수**다.
자세한 동기는 설계 문서 §0.

## 파일

| 파일 | 역할 |
|---|---|
| `sessions_sweep.sh` | 主 스윕 본체. 라틴 방격 판 배치 · 사전 확인 · 페이로드 생성/전송 |
| `spread_writer.sql` | 무관한 세션에 초당 5회 쓰는 백그라운드 writer(«번짐 반경» 채널) |
| `conn_ridealong.sh` | **從** — 「커넥션 수가 정말 무의미한가」(네트워크 축). 커넥션 3수준 × 내구성 2수준 |

## 산출물 (측정 시)

| 파일 | 내용 |
|---|---|
| `sessions.tsv` | RPS · rows/s · p50/95/99 · fail · 커밋 · fsync — 4차 형식 그대로 |
| `spread.tsv` | 판별 **시간 창(UTC)** · `Innodb_row_lock_waits` 델타 · 락 대기 시간 · dirty pages |
| `writer.tsv` | 무관한 세션(1001)의 쓰기 지연 — 시도 · 에러 · p50/p95/max · **최대 시도 간격** |
| `conn.tsv` | **從** 커넥션 스윕 — 기본↔완화 내구성 × `--connections` 1·4·16 |
| `_payload/` | 레벨별 ghz 페이로드(gitignore 대상 — 수 MB) |
| `_conditions.txt` | 고정 조건 기록(풀 크기 등) |

`sessions.tsv` 와 나머지는 **`tag` 로 조인**한다(`s20_r3` = 레벨 20, 반복 3).

## 먼저 확인할 것 (판이 통째로 날아가는 것들)

전부 **틀려도 표는 정상으로 보이는** 부류라 스크립트가 시작 전에 직접 확인하고, 아니면 멈춘다.

- 세션 시드 `901~1000` 이 **정확히 100개** — 없으면 FK 로 전 요청이 실패하는데 그건
  «측정했더니 낮다» 처럼 보인다
- writer 세션 `1001` 존재 — 없으면 writer 가 **에러를 삼키며** 계속 돌아 표가 조용히 빈다
- 내구성이 **기본값**(`flush=1 / sync_binlog=1`) — 4차 pool 스윕이 완화 상태를 남겼을 수 있다
- 백엔드 풀 크기 — 조작 변수가 아니라 **고정 조건**이라 값을 기록만 한다
- 페이로드가 부하기에 **실제로 올라갔는지**(원격 파일 크기 대조)

## 판 배치만 먼저 보기 (EC2 불필요)

```bash
PLAN_ONLY=1 bash sessions_sweep.sh
```

버림판 1 + 본판 25 의 순서와 레벨·위치 분포를 출력하고 끝난다. `_rig.sh` 를 source 하기
**전에** 끝나므로 `PEM`·IP 없이 로컬에서 돈다.

## 실행

```bash
export PEM=~/.ssh/shadowfit-measure.pem
export DB_PUB=... APP_PUB=... LOADER_PUB=... OBS_PUB=... DB_PRIV=... APP_PRIV=...
OUT=$PWD/../session-spread-aws-$(date +%Y-%m-%d) bash sessions_sweep.sh

# 從 — 본 스윕이 **끝난 뒤에** 돌린다(이쪽이 내구성을 흔든다)
OUT=$PWD/../session-spread-aws-$(date +%Y-%m-%d) bash conn_ridealong.sh
```

🔴 **무인 실행이 아니다.** 이 rig 은 랩톱에서 EC2 4대를 SSH 로 몬다 — 「밤새 돌리고 아침에
받기」는 `loadtest/aws/run_all.sh` 계열의 성질이고, 이쪽은 **지켜보는 1~2시간**이다.
무인화는 설계 문서 §8 의 미결정 항목이다.

## 규약 (4차에서 물려받은 것)

- **FAIL ≠ 0** — «측정했더니 0» 과 «측정하지 못했다» 는 다른 행이다
- **버림판은 표에서 지운다** — 남기면 다음 사람이 레벨 하나만 6판인 채로 평균을 낸다
- **훅이 판을 죽이지 않는다** — 추가 관측(writer·기제 지표)이 실패하면 그 칸만 비운다
