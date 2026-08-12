# 부하 테스트 (loadtest/)

전략·근거는 [`docs/decisions/load-test-strategy.md`](../docs/decisions/load-test-strategy.md). 이 디렉토리는 **실행 스크립트**.
용어(baseline/ramp/smoke, percentile, throughput, SLO 등)는 [`docs/decisions/load-test-glossary.md`](../docs/decisions/load-test-glossary.md).

확정 결정(2026-05-31): 목표 DAU 1,000 / 트랙 **② 백엔드 격리(ghz) → ⑤ 시딩→projection** 순차 / 도구 ghz + Locust.

> 핵심: 시스템 병목은 AI 추론(MediaPipe)이라 **MediaPipe 를 빼고** 본인 소유 경로(Spring+MySQL)만
> `SavePoseDataBatch` gRPC 로 격리 측정한다. (strategy §3.2·§5)

---

## ghz/ — ② 백엔드 격리

| 파일 | 용도 |
|------|------|
| `gen_batch.py` | `PoseDataBatchRequest` 1건(= rep 1회 프레임들) JSON 생성기. `--reps` = R 값 |
| `gen_batch_multi.py` | 위의 다세션 판 — 세션 N개를 순회하는 **메시지 배열** 생성 |
| **`batch_multi.json`** | **기본 페이로드** (session 901~1900, R=25). ghz 가 요청마다 다음 세션으로 순회 |
| `batch.json` | 단일 핫세션 판 (session 801, R=25, ~52KB / 프레임 ~2.1KB). **기본값 아님** — 아래 참조 |
| `run-save-pose-batch.ps1` | Windows 실행 (smoke / baseline / ramp) |
| `run-save-pose-batch.sh` | bash 실행 (Git Bash / Linux) |
| `results/` | 실행 출력. **일회성 리포트는 안 남기고, 실험 결과는 커밋한다** — 아래 |

### 사전조건

1. **백엔드 gRPC 가 :6565 에 떠 있음** — reflection 켜진 상태 (`application.yml` `grpc.server.reflection-enabled: true`).
   ghz 가 reflection 으로 스키마 자동 인식 → proto 파일·import 경로 지정 불필요.
> 🔴 **기본 페이로드는 `batch_multi.json` 이다 (2026-08-12, [#166](https://github.com/Shadowfit/init/issues/166)).**
> 전 요청이 `session_id=801` 하나로 가면 모든 INSERT 가 같은 인덱스 리프로 몰려 커밋이 직렬화되고,
> 그때 나오는 천장은 시스템의 천장이 아니라 **그 경합의 천장**이다. 3차(2026-08-08)의
> «천장 = 커밋 fsync» 결론이 그 위에서 나왔고, 4차가 같은 코드·같은 행수에서 페이로드만 바꿔
> **220.4 → 649.4 RPS** 로 반증했다.
>
> `batch.json` 은 지우지 않았다 — 단일 핫세션은 이제 버그가 아니라 **4차가 규명한 조건**이고,
> 그 조건을 재현할 수단이 필요하다. 쓰려면 명시적으로: `-DataFile batch.json` / `DATA_FILE=batch.json`.
> 스크립트가 세션 범위(801 vs 901~1900)를 따라 리셋·프리플라이트 대상을 같이 바꾼다.

2. **세션 row 존재** — 페이로드의 `sessionId` 들이 DB 에 있어야 함. 기본값이면 **901~1900 (1,000개)**:
   ```bash
   docker exec -i shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit < seed/seed-multi-sessions.sql
   ```
   없으면 판이 «완주» 하고 `count` 는 찬 채 `OK 0` 인 결과가 남는다 — 실패로 안 보인다.
   그래서 스크립트가 판 시작 전에 **프리플라이트**로 막는다 (`-SkipPreflight` 로 해제).
   단일 핫세션 판(`batch.json`)을 쓸 때만 아래 더미 801 이 필요하다:
   ([`PoseDataService.savePoseDataBatch`](../backend/src/main/java/com/shadowfit/service/Exercise/PoseDataService.java) 가 `findById` 로 세션 먼저 조회 → 없으면 `SESSION_NOT_FOUND`).
   더미 801 은 [`mysql/dev-seed.sql`](../mysql/dev-seed.sql) 에 있다.
   ⚠️ **자동으로 안 들어간다** — Flyway 도입(이슈 #115) 후 initdb 마운트가 없어졌고, 이 픽스처는
   마이그레이션에서 일부러 제외했다(배포 환경에 가면 안 되는 데이터라서). 부하테스트 전에 직접 넣을 것:
   ```bash
   docker exec -i shadowfit-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit < mysql/dev-seed.sql
   ```
3. **`INTERNAL_API_TOKEN`** — 서버와 동일 값. 인증은 메타데이터 `authorization: Bearer <token>`
   ([`InternalAuthInterceptor`](../backend/src/main/java/com/shadowfit/global/config/InternalAuthInterceptor.java)).
4. **ghz 설치** — 아래.

### ghz 설치

```powershell
scoop install ghz            # Windows (scoop)
# 또는
go install github.com/bojand/ghz/cmd/ghz@latest   # go 있으면
```
릴리스 바이너리: https://github.com/bojand/ghz/releases

> **스크립트가 ghz 를 찾는 순서: ① 저장소 `loadtest/.bin/ghz.exe` → ② PATH** ([#194](https://github.com/Shadowfit/init/issues/194)).
> 규칙은 `ghz/_ghz-path.ps1` 한 곳에 있고 bash 판도 같은 순서다. 둘 중 아무 쪽이나 채우면 rig 전체가 돈다.
>
> `.bin/` 을 먼저 보는 이유는 재현성이다 — 저장소 안에 일부러 받아둔 바이너리가 있으면 그걸로 재는 게 맞다.
> 머신 전역 PATH 사정에 따라 판마다 다른 버전이 돌면 그 차이는 결과에 남지 않고 조용하다.
> 그래서 판 시작 시 **어느 바이너리를 썼는지 버전과 함께 한 줄 찍는다**: `[ghz] 저장소 .bin — …\ghz.exe (v0.121.0)`
>
> ⚠️ `.bin/` 은 gitignore 대상(`loadtest/.gitignore:6`)이라 **clone 만으로는 생기지 않는다.**
> 전에는 스크립트마다 찾는 방법이 세 갈래여서, 어느 쪽으로 설치하든 절반이 「미설치」라며 죽었다.

### 실행

```powershell
$env:INTERNAL_API_TOKEN = "<server-token>"
cd loadtest\ghz
.\run-save-pose-batch.ps1 -Mode smoke      # 1) 경로·인증 OK 확인 (5 call)
.\run-save-pose-batch.ps1 -Mode baseline   # 2) 단일 세션 순차 — batch 1건 p50/95/99
.\run-save-pose-batch.ps1 -Mode ramp       # 3) 동시성 5->100 step — throughput 천장 + p99
```

---

## 실행 순서 (strategy §10)

### 0단계 — R 값 실측 (가장 먼저) ⭐

모든 시딩량이 R(= rep 당 프레임 수)에서 나오는데 현재는 추정값(R≈20~30, strategy §4.5).
**실제 스쿼트 세션 1회** 돌린 뒤 Spring 로그에서 확정:

```
세션 {} : 포즈 데이터 {}개 일괄 저장 성공   ← 이 "{}개" 가 batch 당 행 수 ≈ R
```

확정한 R 로 `batch.json` 재생성:
```bash
python gen_batch.py --session 801 --reps <측정 R> --out batch.json
```
(host 에 Python 없으면 README 하단 PowerShell 생성 블록 사용. ai-server 는 손대지 않음.)

→ 측정한 R 을 strategy §7 / §11 에 박제.

### 1~3단계 — ② 백엔드 격리

`-Mode smoke` → `baseline` → `ramp` 순. ramp 에서 **throughput 가 평탄해지는 동시성 = 백엔드 천장**,
그 지점의 **콜백 p99** 를 SLO(strategy §10-1, "콜백 p99 < 20ms")와 비교.

캡처할 숫자 → strategy §11 SLO 행 / §7 에 박제:
- baseline: batch 1건 저장 지연 p50/95/99
- ramp: 천장 throughput(req/s), 그 지점 p99, 에러율 0 여부

### 다음 — ⑤ 시딩 → projection (별도 작업)

ramp 가 이미 session 801 에 pose_data 를 대량 적재함(side effect). 이걸 GET /reports projection
전/후 비교(payload ~3MB→0.05MB, strategy §4.6-1)의 시드로 재활용 가능.

---

## results/ — 무엇을 남기고 무엇을 버리나

| | |
|---|---|
| **버린다** | smoke·ramp 처럼 되돌려 볼 일 없는 일회성 실행 출력 |
| **커밋한다** | **인프라를 띄워 돈을 쓴 실험의 원시 결과** — ghz JSON, 지표 시계열, 그래프, 실행 스크립트 |

> 🔴 **이 구분은 실제로 손해를 보고 생겼다.** 2026-07-25 분리 배포 실측(풀 사이징)은 결과 JSON 을
> 로컬 세션에만 두고 인프라와 함께 지웠다. 그래서 2026-08-08 에 같은 질문을 다시 물으려면
> **처음부터 다시 비용을 냈다.** 원시 파일은 수 MB 인데 재생성 비용은 반나절 + 실비다.

| 디렉터리 | 실험 |
|---|---|
| [`results/pool-cliff-2026-08-08/`](./results/pool-cliff-2026-08-08/) | 풀 사이징 cliff × 동시성 (c 10~100 × pool 5·20, EC2 3대). **초당 ~205건 수준에서 절벽 없음** — 다운샘플(R=5)이 풀을 병목에서 빼냈다. ⚠️ 병목이 **어디로 갔는지는 미상**(초판의 "백엔드 CPU" 는 철회 — 근거 수치가 원본에 없었다). 설계는 [`pool-cliff-vs-concurrency.md`](../docs/decisions/pool-cliff-vs-concurrency.md), 경위는 그 폴더 README §5 |

> 🔴 **이 실험이 남긴 rig 쪽 숙제 2개** — 다음 부하 실험 전에 확인할 것:
> 1. **ghz 커넥션 수.** 결과 14판이 전부 `"connections": 1` 이다. `-c 100` 을 줘도 TCP 커넥션 하나에 다중화했다면 **측정한 천장이 서버가 아니라 부하기의 것**일 수 있다. `--connections` 를 동시성에 맞춰 올린 뒤 같은 판을 다시 재는 게 1번이다
> 2. **`scrape_interval` 15초 vs 판 ~10초.** 판당 지표 샘플이 0~1개라 **게이지를 판별로 귀속시킬 수 없다.** 부하 실험용으로는 스크레이프를 5초 이하로 낮추거나, 판 길이를 늘려야 한다

각 디렉터리의 `README.md` 가 그 실험의 결과 문서이고, 원시 파일로 그래프를 다시 그릴 수 있게
`plot.py`(의존성 없음)까지 같이 둔다.

---

## measure_*.sh — 실행 계획·비용 측정 (ghz 와 별개)

ghz 는 **처리량**을 재고, 이쪽은 **쿼리 하나의 실행 계획과 비용**을 잰다. 대부분 스크래치 DB
(`shadowfit_explain`)에서 돌아 실 DB 를 건드리지 않는다.

| 스크립트 | 재는 것 | 근거 문서 |
|---|---|---|
| `measure_admin_filter_explain.sh` | **시딩 + A·B 필터 조합별 EXPLAIN.** 다른 admin 측정의 전제 | [`admin-page-scope.md`](../docs/decisions/admin-page-scope.md) §4-3·§4-4 |
| `measure_admin_b_actual.sh` | B 의 **실제 rows·시간**(EXPLAIN ANALYZE), 조건부 조인 반사실 비교, 인덱스 5개 vs 6개 쓰기 비용 | 같은 문서 §4-4-1 |
| `measure_admin_stats_actual.sh` | D 대시보드 집계 5종의 추정 vs 실제, e 인덱스 가설 | 같은 문서 §4-5-1 |
| `measure_admin_stats_curve.sh` | 집계 b 의 볼륨별 비용 곡선(선형성) | 같은 문서 §4-5 ②-1 |
| `measure_admin_index.sh` | 관리자 인덱스 추가 전후 (초판 rig) | 같은 문서 §4-1 |
| `measure_pagination.sh` · `measure_partition.sh` · `measure_json.sh` · `measure_bufferpool.sh` · `measure_lock*.sh` · `measure_mvcc.sh` · `measure_redundant_index.sh` | 각 주제별 단독 실험 | [`realmysql-experiments.md`](../docs/portfolio/realmysql-experiments.md) 등 |

**실행 순서** — `measure_admin_filter_explain.sh` 가 스크래치 DB 를 만들고 시딩하므로 **항상 먼저**
돌린다. `_b_actual` · `_stats_actual` · `_stats_curve` 는 그 DB 를 재사용하고, 규모가 안 맞으면
스스로 멈춘다.

```bash
bash loadtest/measure_admin_filter_explain.sh   # 시딩 + 자기검증 + A·B EXPLAIN
bash loadtest/measure_admin_b_actual.sh         # B 실측
bash loadtest/measure_admin_stats_actual.sh     # D 실측
```

### 🔴 시딩 자기검증 (2026-08-07 추가)

`measure_admin_filter_explain.sh` 는 시딩 직후 **7가지를 검사하고, 하나라도 실패하면 측정을
시작하지 않는다** — 행 수 / 중복 행 / 회원당 세션 수 종류 / status 1종 비율 / `FAILED × kim` 교차 /
하루치 `member_id` 퍼짐 / distinct `start_time`.

이게 생긴 이유는 2026-08-06 에 시딩 결함 **3건**이 연달아 나왔기 때문이다. 셋 다 **스크립트는
성공했고 행 수도 맞았다** — 틀린 것은 행 수가 아니라 **행들 사이의 관계**였고, 그 위에서 나온
실행 계획은 그럴듯해 보였다. 자세한 것은
[`admin-page-scope.md`](../docs/decisions/admin-page-scope.md) §4-2 결함 #5·#6.

> ⚠️ **측정 스크립트를 고칠 때 주의** — `set -euo pipefail` 이라 `grep` 이 못 찾기만 해도
> 파이프라인이 실패로 전파돼 스크립트가 즉사한다. 실제로 이 rig 가 그걸로 두 번 죽었다.
> 값 추출은 `{ ... || true; }` 로 감쌀 것.
>
> ⚠️ **실행 중인 스크립트를 편집하지 말 것.** bash 는 파일을 조금씩 읽어가며 실행해서, 주석만
> 고쳐도 바이트 오프셋이 밀려 엉뚱한 지점부터 읽을 수 있다.

---

## 주의

- **ramp 는 session 801 에 실제 row 를 누적 INSERT** 한다. 측정 후 정리:
  ```sql
  DELETE FROM pose_data WHERE session_id = 801;   -- 더미 세션 자체는 보존
  ```
- E2E(③, Locust)는 별도. MediaPipe 가 진짜 이미지여야 부하가 흐름(strategy §6) — 실제 프레임 리플레이 필요.

---

## batch.json 재생성 (Python 없을 때 — PowerShell)

```powershell
# loadtest/ghz 에서 실행. $reps 를 측정 R 로.
$reps = 25; $session = 801
$fb = @("","","KNEE_OUT","BACK_BENT","HIP_HIGH","KNEE_IN","","KNEE_OUT")
$frames = New-Object System.Collections.Generic.List[string]
for ($f=0; $f -lt $reps; $f++) {
  $lm = New-Object System.Collections.Generic.List[string]
  for ($i=0; $i -lt 33; $i++) {
    $b = (($f*31 + $i*7) % 1000)/1000.0
    $x=[math]::Round(0.30+$b*0.40,6); $y=[math]::Round(0.20+(($b*17)%1.0)*0.60,6)
    $z=[math]::Round(-0.25+(($b*13)%1.0)*0.50,6); $v=[math]::Round(0.85+(($b*11)%1.0)*0.15,6)
    $lm.Add('{"x":'+$x+',"y":'+$y+',"z":'+$z+',"visibility":'+$v+'}')
  }
  $c = ('['+($lm -join ',')+']') -replace '"','\"'
  $ts=[math]::Round($f*0.1,1); $sr=[math]::Round(45.0+($f*7%50),2); $m=$fb[$f%8]
  $frames.Add('{"timestampSec":'+$ts+',"jointCoordinates":"'+$c+'","syncRate":'+$sr+',"feedbackMessage":"'+$m+'"}')
}
$batch = '{"sessionId":'+$session+',"poseData":['+($frames -join ',')+']}'
[System.IO.File]::WriteAllText("$PWD\batch.json", $batch, (New-Object System.Text.UTF8Encoding($false)))
```
