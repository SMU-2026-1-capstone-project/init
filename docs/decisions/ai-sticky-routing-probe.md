# 스티키 라우팅 축소 측정 — 「이미 갈라진 부하」캐비엇을 닫는다

작성일: 2026-08-26
상태: 🟡 **설계 + rig 완료, 미실행** — AWS 실행은 사용자 confirm 대기
대상: [`per-process-ceiling-cause.md` §9-4](./per-process-ceiling-cause.md)가 N 스윕(2026-08-24)
결과 뒤에 열어둔 캐비엇 — *"이 판은 rig 이 프로세스마다 따로 세션을 열어 «이미 갈라진» 부하를
준다. 실제 배포는 같은 세션의 프레임이 같은 프로세스로 가야 하고 그 비용은
`ai-sticky-routing.md` 몫이다."*
연관: [`./ai-sticky-routing.md`](./ai-sticky-routing.md) §5-1(㉮ 추천: Spring 이 알려준다) ·
[`../../loadtest/results/proc-count-sweep-2026-08-24/README.md`](../../loadtest/results/proc-count-sweep-2026-08-24/README.md)(대조군, N=3 451.2 rps) ·
[[feedback_measure_design_needs_repeats]] · [[feedback_no_arbitrary_threshold_values]]

---

## 0. 한 줄

**프로덕션 코드는 0줄도 안 바뀐다.** N=3 프로세스가 이미 답인 상태에서, 세션을 사람이 미리
갈라준 게 아니라 **해시 하나로 스스로 정했을 때도** 처리량이 그대로인지만 잰다. 스티키
라우팅을 "할지 말지"의 §8(`ai-sticky-routing.md`) 결정 셋과는 무관하다 — 그건 여전히
열려 있고, 이 문서가 대신 정하지 않는다([[feedback_user_decides_not_claude]]).

---

## 1. 왜 이 캐비엇이 값이 있나

`ai-sticky-routing.md` §5-1 이 이미 ㉮=ㄱ("Spring 이 세션 생성 응답에 AI 주소를 실어준다")을
추천해뒀다. 이 설계대로면 **매 프레임마다 도는 라우팅 홉은 원래 없다** — 세션 시작 시점에
한 번 정해지고, 그 뒤로 프론트는 정해진 주소로 직행한다. 그래서 "홉 비용"을 잴 필요는
없다.

**대신 안 닫힌 것은 이거다**: N 스윕의 세션 분배는 **인간이 미리 53/53/54로 쪼갠 것**이고,
rig 도 프로세스마다 따로 떠서 **처음부터 자기 몫만** 열었다. 실배포에서는 "이 세션이 어느
프로세스인가"를 **하나의 결정 지점**(Spring)이 실시간으로 정하고, 그 정보가 **한 곳에서
N개 프로세스를 향해 흩어진다.** 그 결정 지점이 병목이 될 수 있는지 — 특히 **파이썬 GIL이
이미 서버 쪽 천장의 원인으로 확정된 이 프로젝트**에서, 결정 지점 자체도 파이썬이라면 —
안 잰 채로 남아 있었다.

---

## 2. 무엇을 재는가 / 안 재는가

| | |
|---|---|
| **잰다** | 세션 160개를 **하나의 클라이언트 프로세스**가 `session_id % 3`으로 매번 스스로 정해 N=3 백엔드에 흩뿌렸을 때, 처리량이 정적 사전분할(N 스윕 C팔, 451.2 rps)과 산포 안에서 같은가 |
| **안 잰다** | 실제 라우팅 홉의 지연 — 추천 설계(㉮=ㄱ)에는 프레임마다 도는 프록시가 없다 |
| **안 잰다** | Spring `grpc-client-spring-boot-starter`로 인스턴스별 채널을 실제로 어떻게 관리하는지 — 이건 `ai-sticky-routing.md` §9가 이미 "미검증"으로 열어둔 자리이고, Spring 코드를 만지는 일이라 이 실험의 범위 밖이다 |
| **안 잰다** | N을 몇으로 할지 — N=3은 이미 답이 나왔다(`per-process-ceiling-cause.md` §9) |

### 2-1. 알려진 교란 변수 — 미리 적어둔다

**이 rig 하나가 N=3 백엔드 전체(합계 160세션·160 워커 스레드)를 몬다.** N 스윕은 그걸
프로세스 3개로 나눠 몰았다. 그래서 **부하기 자신이 GIL로 병목될 가능성**이 새로 생긴다 —
이 실험만의 것이지 프로덕션과는 무관한 잡음이다.

🔴 **이걸 판정에서 걸러내는 손잡이를 미리 박는다**: 오케스트레이터가 `rig` 프로세스의 CPU를
따로 샘플링한다(`ai-process-ceiling-cause.md`류 CpuSampler를 재사용). E팔에서 `rig` CPU가
1 vCPU 근처에 붙으면 **이 판은 "라우팅 비용"이 아니라 "부하기 자체 병목"을 잰 것**이라
무효로 접는다 — 결과가 나쁘게 나와도 그 원인이 서버 쪽 라우팅인지 부하기 쪽인지 갈라야
한다.

---

## 3. 설계

### 3-1. 무대

같은 조건으로 재현 가능하게 N 스윕과 최대한 맞춘다:

| | |
|---|---|
| 박스 | `c7i.4xlarge`(16 vCPU) 1대 — N 스윕과 같은 스펙 |
| 세션 · fps | 합계 **160세션 · 3.0fps** — N 스윕과 동일 |
| 풀 | 백엔드당 `201/3 + 1 = 68` — N 스윕 공식 그대로 |
| 판당 시간 | 90초 |
| 계측 | `FRAME_PATH_METRICS=true`, GIL 프로브 0.001초, `--floor-sec 5` — N 스윕 C팔과 동일 |

### 3-2. 팔 둘

| 팔 | 무엇 | 프로세스 구성 |
|---|---|---|
| **C** | 정적 사전분할(N 스윕 재현) — rig 3개, 각자 53/53/54세션을 자기 백엔드에만 연다 | ai-server 3개 · `overhead_rig.py` 3개 (병렬) |
| **E** | 🆕 단일 rig, `session_id % 3`으로 세션마다 스스로 담당 백엔드를 정해 3개 백엔드 모두를 향해 연다 | ai-server 3개(C와 동일하게 매판 재기동) · `sticky_rig.py` 1개 |

🔑 **C를 이 세션 안에서 재현하는 이유**: N 스윕의 451.2 rps는 다른 날 다른 판이다.
[라운드 간 비재현](./round-to-round-nonreproducibility.md)([#498](https://github.com/Shadowfit/init/issues/498))이 걸리므로,
**핵심 비교(C↔E)는 반드시 같은 세션 안에서** 한다. 옛 451.2는 맥락으로만 인용한다.

### 3-3. 배열 — 버림 1 + 8판

`per-process-ceiling-cause.md` §8의 검증된 패턴을 그대로 옮긴다(라벨만 B→C, A→E):

```
버림(C) · C E C C E C E E C   = 9판, C 5회(버림 포함, 유효 4) · E 4회
```

각 판 사이 `--settle 3`초, 매 판 ai-server 3개 전부 재기동(콜드 아티팩트를 양쪽에 동일하게
물린다 — N 스윕·§8과 같은 이유).

### 3-4. 판정선 (값 보기 전에 못박는다)

| # | 물음 | 판정 |
|---|---|---|
| **ㄱ 정확성** | E팔 `nolease`가 0인가 | 0이 아니면 **해시 라우팅 코드 버그** — 결과를 아예 못 쓴다. 원인부터 고친다 |
| **ㄴ 처리량** | C팔 합계 rps와 E팔 rps의 차가 **양쪽 판 간 산포 합**보다 큰가 | 안 크면 🟢 **"이미 갈라진 부하" 캐비엇이 닫힌다** — 정적 사전분할이 결과에 영향 없었다. 크게 낮으면 🔴 뭔가 비용이 있다(§2-1 교란부터 확인) |
| **ㄷ 배정 균형** | E팔의 `assigned_dist`가 53/53/54 근처인가 | 크게 치우치면 **`session_id % 3`이 이 세션 ID 범위에서 안 고르다** — 해시 자체를 재검토 |
| **ㄹ 교란 배제** | E팔의 `rig` CPU가 얼마인가 | 1 vCPU 근처면 §2-1의 "부하기 자체 병목" 가능성이 서고, ㄴ의 결과를 그 값으로 다시 읽어야 한다 |

**"크다/작다"의 기준은 이 라운드 자신의 판 간 산포다** — 절대 임계값을 미리 안 박는다
([[feedback_no_arbitrary_threshold_values]]).

---

## 4. 만든 것 — 코드 변경 0, 전부 load-test harness

| 파일 | 무엇 |
|---|---|
| [`../../loadtest/results/sticky-routing-probe-2026-08-26/sticky_rig.py`](../../loadtest/results/sticky-routing-probe-2026-08-26/sticky_rig.py) | E팔 클라이언트. `session_id % N`으로 백엔드를 정해 gRPC `StartAnalysis`와 이후 프레임을 그 백엔드로만 보낸다 |
| [`../../loadtest/results/sticky-routing-probe-2026-08-26/run_sticky_probe.py`](../../loadtest/results/sticky-routing-probe-2026-08-26/run_sticky_probe.py) | 오케스트레이터. 매 판 ai-server 3개를 재기동하고, C팔은 기존 `overhead_rig.py`를 3개 병렬로, E팔은 `sticky_rig.py`를 1개 돌린다. CPU 샘플러로 `ai0`·`ai1`·`ai2`·`rig`(들)를 각각 잰다 |

⚠️ **ai-server·Spring·프론트 어디도 안 건드린다.** `sticky_rig.py`는 기존
`overhead_rig.py`가 이미 하던 것(Spring 대신 gRPC `StartAnalysis`를 직접 침)과 같은
자리에 서고, "어느 프로세스로 보낼지"를 정하는 로직만 클라이언트 안에 새로 있다.

### 4-1. 라우팅 규칙이 결정적인 이유

`backend_idx = session_id % len(backends)`. CPython은 정수 해시가 `hash(n) == n`이라
(오버플로·음수 등 예외 제외) `PYTHONHASHSEED`에 안 흔들린다 — 매 실행마다 같은 세션 ID가
같은 백엔드로 간다는 것을 코드가 아니라 인터프리터 자체가 보장한다.

---

## 5. 자원

| | 추정 |
|---|---|
| 인스턴스 | `c7i.4xlarge` 1대 |
| 소요 | 판당 (기동 ~30초 + 부하 90초 + settle 3초) × 9판 ≈ **20~25분** |
| 비용 | 인스턴스 요금 기준 이전 라운드들과 같은 자릿수 — **1,000원 안팎** |
| 종료 후 | 인스턴스 삭제 확인([[project_unattended_aws_round_recipe]]) |

---

## 6. 이 실험이 답하지 못하는 것 (미리 박제)

- **Spring의 실제 gRPC 채널 다중화** — `ai-sticky-routing.md` §9가 이미 미검증으로 열어둔 자리, 이 실험은 그걸 흉내만 낸다(파이썬 클라이언트가 채널 3개를 직접 든다)
- **㉮·㉯·㉰의 결정** — 이 실험이 "이미 갈라진 부하 캐비엇"을 닫아도, `ai-sticky-routing.md` §8의 세 갈래(엔드포인트 계약·매핑 위치·장애 재배치)는 그대로 미결이다
- **N>3, 더 큰 박스** — `per-process-ceiling-cause.md` §9-4가 이미 범위 밖으로 적어둔 것과 같다
- **실제 프론트/Spring 배포 시의 지연** — 이 rig은 로컬 gRPC 채널 3개를 파이썬에서 직접 여는 것이라, Java `@GrpcClient` 리졸버의 성격과 다를 수 있다

---

## 결정 로그

- 2026-08-26: 문서·rig 작성. `per-process-ceiling-cause.md` §9-4가 연 캐비엇을 겨냥한
  축소 측정 — 프로덕션 코드 0줄, load-test harness만. AWS 실행은 미착수, 사용자 confirm
  대기.
