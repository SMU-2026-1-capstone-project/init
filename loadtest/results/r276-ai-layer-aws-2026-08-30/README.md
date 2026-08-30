# #276 ③ — AI 재시도 계층을 실제로 통과시킨 exhausted 첫 실측

작성일: 2026-08-30
연관: [`../r276-retry-latency-2026-08-27`](../r276-retry-latency-2026-08-27/README.md)(이 판의 동기) ·
[`../r276-ceiling-rank-aws-2026-08-26`](../r276-ceiling-rank-aws-2026-08-26/README.md)(상한 5 채택 근거) ·
[`../../measure_r276_ai_layer.py`](../../measure_r276_ai_layer.py)(이 판의 rig) ·
[`../../../docs/decisions/slo-baseline.md`](../../../docs/decisions/slo-baseline.md) §4-9 ·
[#276](https://github.com/Shadowfit/init/issues/276) ③ · [#151](https://github.com/Shadowfit/init/issues/151)

## 0. 왜 이 판인가

지금까지의 모든 r276 라운드는 ghz 가 Spring gRPC `SavePoseDataBatch` 를 **직접** 때렸다 — Spring
내부 데드락 재시도(상한 5, 백오프 0ms)만 잰 것이고, AI 서버의 실제 바깥쪽 재시도 계층
(`ai-server/app/grpc/spring_client.py::report_pose_data_batch` — 3회 시도, 1s→3s 백오프,
시도마다 5초 gRPC 데드라인)을 통과시킨 적이 한 번도 없었다.

`r276-retry-latency-2026-08-27` 이 Spring 내부 재시도 1회 왕복의 지연을 처음 쟀더니
**p99 3,989ms · max 4,343ms** 였다 — AI 쪽 5초 데드라인에 근접한다. Spring 이 정상적으로
재시도를 도는 중에도 AI 가 먼저 `DEADLINE_EXCEEDED` 로 포기할 가능성이 있는데, 그 상호작용은
한 번도 실측된 적이 없었다. `docs/decisions/slo-baseline.md` §4-9 는 이미 「exhausted 는
1건이라도 = 조사」로 판정선을 확정해 뒀지만, 그 지표로 **한 판도 안 돌았다**고 스스로 적어뒀다.
이 판이 그 두 공백을 같이 메운다.

## 1. 방법

ghz 대신 **AI 서버의 실제 프로덕션 함수**(`spring_client.report_pose_data_batch`)를 새 rig
(`loadtest/measure_r276_ai_layer.py`)으로 동시 스레드에서 직접 호출했다. HTTP로 ai-server
전체를 흉내내지 않는다 — 그 함수 자체가 측정 대상이다. `_is_retryable` 을 관측용으로만
몽키패치해(원래 리턴값은 그대로 위임) 매 attempt 의 실제 gRPC 상태코드를 별도로 셌다.

가정 — 새로 고른 값이 아니라 기존 라운드와 비교 가능하게 유지하려는 재사용:

- 동시성 **16**, 중복키 100%(`rep_number` 고정) — `r276-ceiling-rank-aws-2026-08-26` ·
  `r276-retry-latency-2026-08-27` 과 같은 조건
- 세션 901-1000(100개), 배치당 25프레임 — `measure_r276_app_retry.sh` 기본값과 동일
- Spring 쪽 상한(현재 배포 기본값 **5**)·백오프(0ms)는 **건드리지 않았다** — 지금 이 값이
  AI 계층과 만났을 때 무엇을 만드는지가 질문이지, 팔 비교가 아니다
- 대상: c7i.xlarge 단일 박스에 MySQL + Spring 컨테이너(빌드 후 기동), AI 컨테이너는 **안
  띄웠다** — 이 rig 이 파이썬 venv 로 AI 의 역할을 직접 수행한다
- 판당 300요청 · 6블록(첫 블록 warm-up으로 버림) · 판마다 대상 세션의 `pose_data` 삭제 +
  `last_active_at` 갱신(기존 r276 rig 관행 그대로)

## 2. 결과 (5블록 유지, 블록당 300요청 = 1,500요청)

| 블록 | AI ok | AI retried | AI exhausted | Spring exhausted(=ABORTED) | rpc 상태코드 |
|---|---:|---:|---:|---:|---|
| 1 | 300 | 11 | **0** | 11 | ABORTED × 11 |
| 2 | 300 | 14 | **0** | 14 | ABORTED × 14 |
| 3 | 300 | 6 | **0** | 6 | ABORTED × 6 |
| 4 | 300 | 4 | **0** | 4 | ABORTED × 4 |
| 5 | 300 | 4 | **0** | 4 | ABORTED × 4 |
| **합(5블록)** | **1,500** | **39** | **0** | **39** | — |

(버려진 블록 0: ok 300 · retried 5 · exhausted 0 · Spring exhausted 5 — 같은 모양)

전체 6블록 누적(`ai_metrics_snapshot.prom`, 프로세스 전체 수명 기준): `ok=1800 · retried=44 ·
rejected=0 · exhausted=0`.

## 3. 판정

**이 조건(동시성16·중복100%·상한5·백오프0)에서 exhausted 는 0/1,800 — 처음으로 관측치를
얻었고, 값은 0 이다.** `DEADLINE_EXCEEDED` 도 전 요청에서 단 한 번도 나오지 않았다
(`rpc_status_codes` 에 `ABORTED` 만 있고 다른 코드가 없다).

- **두 지표가 정확히 교차검산된다** — 매 블록 「AI retried」와 「Spring exhausted」가 완전히
  같은 수다(11=11, 14=14, 6=6, 4=4, 4=4). Spring 이 내부 재시도 5회를 다 쓰고 `ABORTED` 를
  낸 요청은, AI 가 그 즉시(자신의 재시도 1회 이내) 전부 회수했다 — 그래서 exhausted 가 0 이다.
- **08-27 라운드가 걱정했던 충돌은 이 조건에서 실현되지 않았다.** 08-27 의 전체 지연분포
  p99(3,989ms)는 5초 데드라인에 근접했지만, 그건 OK 요청까지 섞은 값이었다. 08-27 이 따로 낸
  「Aborted(상한 소진)」서브셋의 지연은 **p50 1,946ms · max 3,617ms**로 이미 5초 아래였다 —
  이번 판은 그 서브셋의 실제 그림자(=Spring이 ABORTED로 답하는 case)를 실제 AI 코드로
  1,800회 통과시켜 그 관측을 정면으로 재확인한 것에 가깝다.
- `docs/decisions/slo-baseline.md` §4-9 의 판정선(「exhausted 1건이라도 = 조사」)은 **아직
  발동할 사건을 못 만났다** — 나쁜 소식이 아니라, 지금 배포된 상한 5·백오프 0 조합이 이
  조건에서는 실제로 버틴다는 근거다.

## 4. `docs/decisions/slo-baseline.md` §4-9 에 대한 영향 (초안 — 문서 반영은 사용자 판단)

§4-9 의 "⚠️ 다만 이 판정선은 아직 «관측» 이 아니다. 이 지표로 한 판도 안 돌았다" 라는 문장은
더 이상 사실이 아니다 — 이 판이 처음으로 그 지표를 돌렸고, 첫 관측치는 **0** 이다. 다만:

- 이 판은 **동시성 16, 단일 박스, 단일 상한값(5)** 조건 하나다. 「어떤 조건에서도 0」이라고
  일반화하면 안 된다 — 특히 §4-9 자신이 남긴 미결(「상한의 동시성 의존」)이 여기에도 그대로
  적용된다. 더 높은 동시성에서도 AI 재시도가 Spring exhausted 를 전부 흡수하는지는 **안 쟀다**
- 「1건이라도 = 조사」라는 판정선 자체(정의)는 이번 결과로 바뀌지 않는다 — 이 판은 그 정의가
  맞는 값(0)을 처음 관측했을 뿐이다

## 5. 한계

- **부하기(이 rig)가 대상과 같은 박스에 산다.** 절대 지연·처리량은 이 환경 고유
  ([[project_loadtest_env_constraint]]) — 다만 이 판이 읽는 것은 **비율·상태코드**지 절대
  지연이 아니라서, 그 한계의 영향은 다른 r276 라운드보다 작다고 본다(검증하지 않은 판단)
- **Python GIL 하 스레드 동시성**이 실제 uvicorn/gRPC 서버 프로세스의 동시성 프로파일과
  100% 같다는 보장은 없다. `report_pose_data_batch` 는 attempt 마다 블로킹 gRPC 호출 +
  `time.sleep` 이라 대부분 GIL 을 놓는 I/O 구간이라 왜곡이 작을 것으로 보지만, 확인하지 않았다
- **표본 5블록·1,500요청, 단일 조건.** exhausted=0 이 통계적으로 「이 조건에서 항상 0」을
  증명하지 않는다 — 특히 Spring exhausted 자체가 블록마다 4~14 로 흔들린다(같은 조건인데도).
  더 큰 표본이나 더 높은 동시성에서 exhausted>0 이 나올 여지는 열려 있다
- **상한·백오프 팔 비교는 안 했다.** 이 판은 배포 기본값(상한5·백오프0) 하나만 AI 계층까지
  통과시켰다 — 「상한을 낮추거나 백오프를 켜면 어떻게 되나」는 별도 판이 필요하다
- **DEADLINE_EXCEEDED 관측 수단**(`_is_retryable` 몽키패치)은 이 rig 안에서만 사는 훅이다.
  프로덕션에 이 계측이 상시로 있는 것은 아니다 — 필요하면 별도 결정 사안이다
