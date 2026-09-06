# `@Async` 풀과 서킷브레이커 — 「AI 가 멈추면 Spring 은 무엇을 쌓는가」

작성일: 2026-08-11
갱신: 2026-09-07 — **H1 재측정 완료·더 큰 결함 발견.** #667(2026-09-04)이 `applicationTaskExecutor`를
non-lazy로 고쳐 계측 채널(#582)이 살아났고, 그 채널로 재측정한 결과 **큐는 6판 내내 한 번도 안
자랐다.** 그런데 이유가 "안전해서"가 아니라 **`@Async sendAnalysisRequestToFastApi`가
`applicationTaskExecutor`를 아예 안 쓰기 때문**이었다(직접 호출 테스트로 확인 —
`executor_completed_tasks_total`이 실제 실행 후에도 0, 스레드 이름이 `TaskExecutor-N`으로 코어
8개 재사용 패턴 없이 계속 올라감). 큐 상한 문제보다 **무제한 스레드 생성**이 실제 리스크로
새로 열렸다 — 원인 규명·수정은 별도 착수 필요. 상세: [`../../loadtest/results/async-pool-2026-09-07/README.md`](../../loadtest/results/async-pool-2026-09-07/README.md)
상태: **본 실험 완료 (2026-08-28, 로컬) — 그런데 계획한 방법으로는 답을 못 냈다.** 완화책 채택 여부는 미결정 — 실행은 사용자 confirm 후 진행([[feedback_user_decides_not_claude]]), 결과 해석·다음 행동은 그대로 사용자 몫.
      0-a 가 H1(Boot 기본값)을 바이트코드로 확정했고, 0-b 는 §2 의 «Micrometer 자동 계측» 한 줄을 **반증**했다(`@Lazy` 라 첫 `@Async` 호출 전에는 빈도 지표도 없다). **2026-08-28 본실험이 한 단계 더 나쁜 사실을 확인했다 — 빈이 태어난 뒤에도 Micrometer 가 영영 안 잡는다**(이슈 [#582](https://github.com/Shadowfit/init/issues/582)). 큐 길이(H1)는 그래서 **여전히 미답**이고, 대신 관측 가능했던 서킷브레이커·세션 FAILED 전이(H2·H3)만 실측했다. 결과: [`../../loadtest/results/async-pool-2026-08-28/README.md`](../../loadtest/results/async-pool-2026-08-28/README.md). 경위는 결정 로그
대상: Spring 축의 미측정 1순위. **FastAPI 실험이 아니다** — AI 정지는 자극이고 관측 대상은 Spring 의 큐다
연관: [`./four-axes-depth-experiments.md`](./four-axes-depth-experiments.md) §3-4·§4,
[`./architecture-review-2026-08-11.md`](./architecture-review-2026-08-11.md) 결함 ⑥,
[`./outbox-reliable-messaging.md`](./outbox-reliable-messaging.md) §6,
[`../tasks/32-deferred-items.md`](../tasks/32-deferred-items.md) P3,
[[feedback_measure_design_needs_repeats]], [[project_loadtest_env_constraint]]

---

## 0. 왜 이 자리인가

`AsyncConfig.java:15-16` 이 스스로 적어 뒀다:

> *"Boot가 자동 구성하는 `applicationTaskExecutor`를 커스터마이즈하는 방식이라
> **기존 풀 설정·기본값을 그대로 둔다**(별도 executor 빈을 새로 정의하지 않는다)."*

그리고 `application.yml` 에 `spring.task.execution` 이 **없다**(전수 확인, 2026-08-11).
`spring.threads.virtual` 도 없으므로 플랫폼 스레드다.

즉 이 프로젝트에서 **AI 분석 요청이 지나가는 풀은 한 번도 정해진 적이 없고, 한 번도 측정된 적이 없다.**
«기본값을 그대로 둔다» 는 문장이 근거 없이 서 있는 유일한 자리에 가깝다 — 다른 숫자들(pool=15,
batch_size=25, 다운샘플 R≈5)은 전부 실측이 붙어 있다.

여기에 두 사실이 겹친다.

| | 근거 |
|---|---|
| AI 분석 요청이 `@Async` 다 | `ExerciseAnalysisService.java:226` — `sendAnalysisRequestToFastApi` |
| 그 안의 gRPC 데드라인이 5초다 | 같은 파일 `:79` — 주석이 *"빠른 ack 성격의 제어 호출"* 이라 적었다 |

**AI 가 멈추면 워커 하나가 5초씩 잡힌다.** 그 뒤가 이 문서의 질문이다.

---

## 1. 가설 — 그리고 무엇이 나오면 내가 틀린 것인가

### H1. 큐는 상한이 없다

Boot 기본 `applicationTaskExecutor` 는 **core 8 · 큐 무제한**이고, `ThreadPoolExecutor` 는
**큐가 가득 찼을 때만** core 를 넘겨 스레드를 만든다. 큐가 무제한이면 그 조건이 영영 오지 않으므로
`max-size` 는 사실상 죽은 설정이 된다 → **워커는 8에서 고정, 초과분은 무한정 적재.**

> ✅ **확인됨 (2026-08-11, 정적).** 초안에서는 통념이라 미검증으로 달아 뒀으나, **§3 의 0-a 단계를
> 앱을 띄우지 않고 끝냈다.** 이 프로젝트가 실제로 컴파일하는
> `spring-boot-autoconfigure-3.5.16.jar` 의 `TaskExecutionProperties$Pool` 생성자 바이트코드:
>
> ```
> queueCapacity          = 2147483647   (Integer.MAX_VALUE)
> coreSize               = 8
> maxSize                = 2147483647
> allowCoreThreadTimeout = true
> keepAlive              = 60s
> ```
>
> 그리고 **Boot 자신의 프로퍼티 설명이 메커니즘까지 명시한다** — 추론이 아니다:
>
> - `max-size` — *"**Ignored if the queue is unbounded.**"*
> - `queue-capacity` — *"An unbounded capacity does not increase the pool and therefore **ignores the max-size property**."*
>
> 오버라이드가 없다는 것도 전수 확인했다: `application.yml`·`application.properties` 에
> `spring.task.execution` 없음 · executor 빈 재정의 없음(`AsyncConfig` 는 `ThreadPoolTaskExecutorCustomizer`
> 로 `TaskDecorator` 만 심는다) · `spring.threads.virtual` 없음(플랫폼 스레드).
>
> ⚠️ **다만 이것은 «설정이 그렇다» 이지 «런타임이 그렇다» 가 아니다.** 확정은 0-b 에서.

### H2. 그런데 서킷브레이커가 먼저 개입할 것이다

H1 만 보면 «큐 폭주» 인데, 그 앞에 이미 장치가 하나 있다. `application.yml:184-192`:

```
slidingWindowSize: 10 · minimumNumberOfCalls: 5 · failureRateThreshold: 50
waitDurationInOpenState: 10s · permittedNumberOfCallsInHalfOpenState: 3
```

AI 정지 → `DEADLINE_EXCEEDED` 가 실패로 집계 → **5건 만에 서킷 OPEN** → 그 뒤 호출은
`:250-259` 에서 즉시 반환. **큐가 무한히 자라기 전에 소진 속도가 올라간다.**

여기서 갈린다 — 서킷은 **큐 유입을 막지 않는다.** OPEN 판정은 이미 큐에 들어간 작업이
워커에 올라온 뒤에야 일어난다(`:249`). 즉 서킷이 하는 일은 «안 쌓이게» 가 아니라
**«빨리 비우게»** 다. 이 구분이 이 실험의 핵심이다.

> **진짜 질문은 «큐가 터지나» 가 아니라 «두 장치가 어떻게 만나나» 다.**
> 서킷이 열리기 전 구간에서 큐가 얼마나 자라고, `waitDurationInOpenState=10s` 뒤
> half-open 3건이 다시 실패하는 주기에서 큐가 **톱니 모양으로 재성장하는가.**

### H3. 세션 FAILED 전이가 큐 대기만큼 밀린다

서킷 OPEN 분기든 gRPC 에러 분기든 `markAsFailedIfStillInProgress` 로 세션을 즉시 걷어낸다
(`:255`·`:290`). 그런데 그 코드는 **워커에 올라온 뒤에** 실행된다. 큐에서 기다린 시간만큼
사용자는 «응답 없는 IN_PROGRESS 세션» 을 붙들고 있다.

### 반증 조건 (먼저 적는다)

| 무엇이 나오면 | 무엇이 틀린 것인가 |
|---|---|
| 큐 길이가 자라지 않는다 | **H1 이 틀렸다.** 설정은 0-a 로 확정됐으므로, 그렇다면 큐에 **닿기 전에** 무언가가 막고 있다는 뜻이다 |
| 워커가 8을 넘어 늘어난다 | H1 의 «max-size 는 죽은 설정» 이 틀렸다 |
| 서킷 OPEN 뒤에도 큐가 계속 자란다 | **H2 가 틀렸다.** 서킷이 이 경로의 소진 속도를 못 올린다 → 🔴 결함, 이슈 |
| FAILED 전이 지연이 큐 길이와 무관하다 | H3 이 틀렸다 |
| 서킷이 5건이 아닌 곳에서 열린다 | 설정 다섯 개 중 최소 하나가 의도대로 동작하지 않는다 → 🔴 결함, 이슈 |

**어느 쪽으로 나와도 산출물이 있다.** 맞으면 「측정으로 확인한 주장」, 틀리면 **결함 발견**이고
[[feedback_troubleshooting_to_issues]] 대로 이슈로 간다.

---

## 2. 무대 — 새로 만들 게 거의 없다

| 필요한 것 | 이미 있는 것 |
|---|---|
| AI 정지 수단 | `docker pause` — 아웃박스 §6 ①-a 에서 **이미 쓴 수법** |
| 큐·워커 지표 | ⚠️ **이 줄은 틀렸다 — 0-b 참고.** Micrometer 가 자동 계측하는 것은 맞으나 `applicationTaskExecutor` 가 `@Lazy` 라 **첫 `@Async` 호출 전에는 지표가 아예 없다.** 그리고 `/actuator/metrics` 는 401 이라 **`/actuator/prometheus`** 로 읽어야 한다 |
| 서킷 상태 | `/actuator/circuitbreakers` · `/actuator/circuitbreakerevents` — `application.yml:170` 에 **이미 노출돼 있다** |
| 디스크·OS 샘플러 | `_rig.sh` 의 `start_disk_sampler` (`:315`) — four-axes §4 가 CPU·컨텍스트 스위치 확장을 권고 |
| 결과 디렉터리 관례 | `loadtest/results/<축>-<날짜>/` |

신규는 **큐 샘플러 하나**뿐이다(§4-2 참고).

---

## 3. 단계

### 0-a단계 — 설정 확인 ✅ **완료 (2026-08-11)**

**앱을 띄우지 않고 끝났다.** 근거는 §1 H1 의 인용 블록 — jar 바이트코드 + Boot 프로퍼티 설명 +
오버라이드 부재 전수 확인.

> **이 단계만으로 이미 산출물이 하나 나왔다** — `AsyncConfig.java:15-16` 의
> *"기존 풀 설정·기본값을 그대로 둔다"* 에 **숫자가 붙었다.** 그 문장은 이제
> «core 8 · 큐 2147483647 · max-size 는 죽은 설정» 을 뜻한다.
>
> 부수 소득: **`max-size` 를 올려도 아무 일도 안 일어난다.** 큐가 무제한인 한 무시되기 때문에,
> 나중에 완화를 논할 때 «스레드를 늘린다» 는 선택지가 **애초에 존재하지 않는다** —
> 손댈 곳은 `queue-capacity` 다. 이걸 모르고 튜닝했으면 안 먹는 노브를 돌렸을 것이다.

### 0-b단계 — 런타임 확인 ✅ **완료 (2026-08-13)** — 🔴 **예상과 다르다**

원래 계획은 «`executor.pool.core`·`executor.pool.max`·`executor.queued` 를 한 번 읽어 0-a 와
일치하는지만 본다» 였다. 부하가 없으므로 다른 세션과 충돌하지 않는다는 판단도 그대로 유효했다.
(문서가 «`shadowfit-backend` 가 떠 있지 않다» 고 적어 둔 것은 **낡았다** — 2026-08-13 확인 시 4시간째 up.)

**결론: `applicationTaskExecutor` 의 런타임 지표가 존재하지 않는다.**

읽은 것 (`/actuator/prometheus`):

```
executor_pool_core_threads{name="taskScheduler"}     5.0
executor_pool_max_threads{name="taskScheduler"}      2.147483647E9
executor_queued_tasks{name="taskScheduler"}          5.0
executor_completed_tasks_total{name="taskScheduler"} 15239.0
```

`name=` 태그를 전수로 뽑아 보면 계측된 executor 는 **`taskScheduler` 하나뿐**이고,
`applicationTaskExecutor` 는 응답 전체에서 **0 회** 등장한다.

**원인 — 0-a 와 같은 수법(바이트코드)으로 확정했다.**
`TaskExecutorConfigurations$TaskExecutorConfiguration` 의 빈 정의에 `@Lazy` 가 붙어 있다:

```
ThreadPoolTaskExecutor applicationTaskExecutor(ThreadPoolTaskExecutorBuilder)
  @Bean(value=["applicationTaskExecutor"])
  @Lazy                                          ← 이것
  @ConditionalOnThreading(...)
```

즉 **첫 `@Async` 호출 전까지 빈이 생성되지 않는다.** 빈이 없으면 Micrometer 가 감쌀 대상도 없고
지표도 안 생긴다. `taskScheduler` 만 보이는 이유도 같은 논리다 — 그쪽은 lazy 가 아니라 기동 시
스케줄 등록으로 즉시 만들어진다(완료 15,239 건이 그 증거다).

| | |
|---|---|
| ✅ 액추에이터로 executor 지표를 읽는 **경로**는 있다 | 단 `/actuator/metrics` 는 **401** 이다. 9090 화이트리스트가 `/actuator/health`·`/actuator/prometheus` 뿐이라 샘플러는 **`/actuator/prometheus` 를 폴링**해야 한다 |
| 🔴 0-a 의 «core 8 · queue 2147483647» 이 **런타임에 실현됐는지는 아직 모른다** | 대조할 런타임 객체가 없다. 0-b 가 찾은 것은 «다르다» 가 아니라 **«아직 태어나지 않았다»** 다 |

**본 실험 절차에 미치는 영향 3건:**

1. **큐 샘플러가 폴링할 지표가 부하 전에는 존재하지 않는다.** §3 1단계 앞에
   «첫 `@Async` 호출로 빈을 깨우고 지표 등장을 확인» 단계가 들어가야 한다
2. **판 사이 리셋의 «`executor.queued` 가 0 인 것을 확인»** 도 빈이 살아난 뒤에나 가능하다
3. **`@Lazy` 자체가 오염원이다** — 첫 요청이 풀 생성 비용을 문다. 버림판이 이걸 흡수해야 하고,
   흡수됐는지 확인할 방법도 같이 필요하다

> 📌 **«부하 없는 곁다리 확인» 이 실험 절차를 바꿨다.** §2 「새로 만들 게 거의 없다」 표의
> *«큐·워커 지표 → Micrometer 가 `applicationTaskExecutor` 를 자동 계측»* 한 줄이 **틀렸다.**
> 자동 계측되기는 하는데, **그 전에 빈이 태어나야 한다**는 조건이 빠져 있었다.
> 0-a 가 «설정» 을 확정했다면 0-b 는 **«설정과 런타임 사이에 lazy 라는 문이 하나 더 있다»** 를 찾았다.

### 1단계 — 버림판 ✅ **완료 (2026-08-28)** — 🔴 계산이 무의미해졌다

계획대로 «큐 길이가 관측 가능해지는 최소 투입»을 캘리브레이션하려 했으나, **큐 길이 자체를 잴 방법이 없다는 게 이 단계에서 드러났다**(§0-b 이후 새 발견, 결정 로그 2026-08-28). 대신 세션 시작 API가 실제로 동작하는지(전용 계정 20개 풀 + `preferred_url` 세팅), 서킷브레이커·세션 상태 폴링이 되는지만 확인하고 다음 단계로 넘어갔다.

### 2단계 — 본 실험 ✅ **완료 (2026-08-28, 로컬)**

**팔 둘.**

| 팔 | 자극 |
|---|---|
| **A** | AI 정상 (대조) |
| **B** | AI 정지 (`docker pause`) — 데드라인 5초를 전부 소진시킨다 |

**팔당 3판, 순서 교차** — 팔당 1판이면 「팔」과 「판 순서」가 분리되지 않는다([[feedback_measure_design_needs_repeats]]).
2팔이므로 라틴 방격 대신 순서 반전으로 충분하다:

```
판1: A B    판2: B A    판3: A B
```

각 팔이 1번·2번 자리에 고르게 놓인다.

**판 사이 리셋** — 새는 것이 셋 있다:

1. **서킷 상태** — 판 시작 전 `/actuator/circuitbreakers` 로 `CLOSED` 확인. OPEN 인 채로 시작하면 그 판은 다른 실험이 된다
2. **이전 판의 IN_PROGRESS 세션** — 남아 있으면 타임아웃 스케줄러가 중간에 끼어든다
3. **큐 잔량** — `executor.queued` 가 0 인 것을 확인하고 시작

### 3단계 — 판정

§1 의 반증 조건 표를 그대로 대입한다. 새 판정 기준을 여기서 만들지 않는다.

---

## 4. 오염원 — 미리 적어 두는 것들

### 4-1. 🔴 물리 2코어

[[project_loadtest_env_constraint]] 가 그대로 걸린다. **절대 처리량·절대 큐 길이는 인용 금지**이고,
읽을 수 있는 것은 **메커니즘(큐가 자라는가·서킷이 언제 여는가)과 팔 간 델타**뿐이다.

관측 스택 3개가 같은 박스에 있다는 것(회고 ⑤)도 여기 얹힌다.

### 4-2. 🔴 프로메테우스 스크레이프 간격이 해상도의 하한이다

**이게 이 실험 고유의 함정이다.** 큐 길이는 초 단위로 변하는데 스크레이프 간격이 그보다 크면
톱니(H2)가 통째로 안 보인다. 없는 것과 «못 본 것» 을 구분할 수 없게 된다.

→ **프로메테우스를 보지 말고 액추에이터를 짧은 간격으로 직접 폴링하는 샘플러를 쓴다.**
`_rig.sh` 의 `start_disk_sampler` 와 같은 모양으로 붙이면 된다.

⚠️ 샘플러 자체가 부하다. 2코어에서 폴링 간격을 너무 좁히면 그것이 오염원이 된다 —
간격 선택의 근거를 결과에 같이 적는다.

### 4-3. 지표는 «걷는 것» 과 «보는 것» 이 다르다

- **걷는 것**: 큐 길이·워커 수·서킷 전이 시각 — 시계열로 상주 수집
- **보는 것**: 세션별 IN_PROGRESS→FAILED 전이 지연 — 판이 끝난 뒤 DB 에서 집계

둘을 같은 파이프로 뽑으려 하면 둘 다 나빠진다.

### 4-4. 세션 시작 API 응답시간은 «안 변하는 것» 이 증거다

`startAnalysis` 는 세션 id 를 즉시 반환하고 gRPC 는 afterCommit 이후 비동기다(`:210-217`).
**따라서 AI 가 멈춰도 API 응답시간은 안 변해야 정상**이고, 그것이 바로 문제의 모양이다 —
사용자는 성공 응답을 받는데 세션은 시작되지 않았다. 이 지표를 «괜찮다» 로 읽으면 안 된다.

---

## 5. 의식적으로 안 할 것

| 안 함 | 이유 |
|---|---|
| **풀 크기 스윕**(core 8→16→32) | 그건 튜닝이고, 튜닝은 병목을 안 뒤에 할 일이다. 물리 2코어에서 워커를 늘려봐야 CPU 가 없다 |
| **가상 스레드 전환** | 별건이고 더 비싸다. 이 문서는 **현 설정의 거동 확인**만 다룬다 |
| **AI 지연 주입 팔(C)** | «느리지만 응답» 조건이 «정지» 보다 현실적이지만, `tc netem` 은 NET_ADMIN 이 필요하고 ai-server 를 고치는 것은 [[feedback_minimize_python_changes]] 에 걸린다. **열 조건**: A/B 에서 서킷 개입 지점이 확인된 뒤, 「부분 포화」가 별개 질문으로 남으면 |
| **완화 구현** | 이 문서는 **측정**이다. 큐 상한·거절 정책은 결과가 나온 뒤 별건으로 판단한다(§7) |

---

## 6. 종료 조건과의 관계 (정직하게)

`four-axes-depth-experiments.md §6` 과 같은 논리다. **이 실험은 E1~E4 에 직접 걸리지 않는다.**
목적지 기준으로는 잉여이고, 착수하면 도착이 그만큼 밀린다. 현재 확정된 우선순위는 «한 장 먼저» 다.

다만 **결함이 나오면 E4 로 넘어온다** — 「예상 질문에 «모른다»로 답할 자리가 없다」에 걸리기 때문이다.
그리고 결함일 확률이 낮지 않다: 이 자리는 코드 주석이 **스스로 «기본값을 그대로 뒀다» 고 선언한 곳**이라,
검증된 적이 없는 것이 확실하다.

---

## 7. 결과별 후속 (미리 갈라둔다) — 🔴 **다섯 번째 갈래로 떨어졌다**

| 결과 | 다음 |
|---|---|
| 서킷이 큐 성장을 실질적으로 막는다 | ✅ «서킷브레이커가 백프레셔 역할까지 한다» 를 실측으로 확보. 회고 ⑥에 대한 서버 쪽 답 |
| 서킷 OPEN 뒤에도 큐가 자란다 | 🔴 결함. 이슈 등록 |
| 서킷이 설정과 다른 지점에서 연다 | 🔴 결함. 이슈 |
| 큐가 애초에 안 자란다 | H1 이 틀린 것. 그 자체가 산출물 |
| 🆕 **큐 길이를 잴 방법 자체가 없다** | **실제로 이렇게 나왔다.** 위 네 갈래 전부 «큐 길이를 봤다» 를 전제하는데, 그 전제가 깨졌다. 이슈 [#582](https://github.com/Shadowfit/init/issues/582)로 등록(계측 결함) — H1 은 미답인 채 남는다 |

**실제 결과 (2026-08-28):**
- **H1(큐 상한 없음)**: 판정 불가 — 계측 채널이 없다(위 §0-b·이슈 #582).
- **H2(서킷이 소진 속도를 올린다)**: 부분 확인 — 서킷이 설정대로(≈5~6초, `minimumNumberOfCalls=5` 와 정합) OPEN 되고, OPEN 이후 `notPermittedCalls` 가 계속 늘어 즉시 거절되는 것은 확인. 다만 «그 앞에서 큐가 얼마나 자랐는지» 는 못 봤다.
- **H3(FAILED 전이가 큐 대기만큼 밀린다)**: 지연 자체는 확인(AI 정지 후 **3.6~3.9초**, 5초 데드라인보다 먼저 — `docker pause` 조건에서). 그런데 **큐 길이와의 상관은 못 봤다** — 계측이 없어서.
- 🆕 **설계에 없던 관측**: AI 가 실제로 회복된 뒤에도 서킷이 다시 `CLOSED` 로 돌아오기까지 **~10~12초 창**이 있다(`waitDurationInOpenState=10s` + half-open 시도). 이 창 동안은 정상 요청도 거절될 수 있다.
- 🆕 `docker pause`(정지)는 SIGKILL 과 달리 **컨테이너를 재기동시키지 않는다**(`RestartCount` 전 구간 0) — `ai-channel-pool-hardening.md §2-4`의 재기동 시나리오와는 다른 장애 모양.

상세: [`../../loadtest/results/async-pool-2026-08-28/README.md`](../../loadtest/results/async-pool-2026-08-28/README.md)

---

## 8. 미결정 (사용자 confirm 필요)

- [x] ~~**착수 여부**~~ — **2026-08-28 착수·완료(로컬).**
- [x] ~~**0단계만 먼저 할지**~~ — 0-a·0-b 완료.
- [x] ~~**결과 위치**~~ — `loadtest/results/async-pool-2026-08-28/` 관례대로.
- [x] ~~**H1(큐 길이)을 다른 채널로 다시 잴지**~~ — #667(2026-09-04)이 코드 원인(`@Lazy`)을 이미
      고쳤다(다른 목적의 PR이었으나 이 이슈도 부수적으로 해소). 2026-09-07 그 채널로 재측정 완료 —
      결과: [`async-pool-2026-09-07/README.md`](../../loadtest/results/async-pool-2026-09-07/README.md).
- [x] ~~**rig 의 로깅 경합 버그를 고쳐 재측정할지**~~ — 2026-09-07 재측정 시 파일 분리 방식으로
      수정, 15/15 유실 없이 잡혔다(정량적 주장 가능해짐).
- [ ] **`@Async`가 `applicationTaskExecutor`를 안 쓰는 원인 규명·수정** — 2026-09-07 재측정이 새로
      연 항목. 추정(Spring 기본 실행기 탐색 실패 → 대체 실행기 폴백)만 있고 코드로 확정 안 됨.
      [[feedback_troubleshooting_to_issues]]대로 이슈 등록 여부·착수는 사용자 결정.
- [ ] **완화책 착수 여부** — 질문 자체가 바뀌었다: "무제한 큐가 위험한가"가 아니라 "무제한 스레드
      생성(추정)이 위험한가"이고, 후자가 더 심각할 수 있다. §5(안 할 것)에 있던 항목들(풀 크기
      스윕·거절 정책 등)은 그대로 미착수 — 원인 규명이 선행돼야 한다.

---

## 결정 로그

- 2026-08-11: 설계 초안. **실행 미착수.** 가설 3개와 **반증 조건 5개**를 먼저 고정.
  설계 중 방향이 한 번 바뀌었다 — 처음엔 «무한 큐 폭주» 단독 가설이었으나,
  서킷브레이커가 **큐 유입이 아니라 소진 속도**에 개입한다는 점을 확인하고
  **«두 장치의 상호작용»** 으로 질문을 다시 세웠다(§1 H2).
  H1(Boot 기본값)은 통념 기반이라 **미검증으로 표시**하고 0단계로 분리했다.
  §8 미결정 5건.
- 2026-08-11(**0-a 완료**): H1 을 **앱을 띄우지 않고 확정**했다 — jar 바이트코드에서
  `queueCapacity=2147483647 · coreSize=8 · maxSize=2147483647` 을 읽고, Boot 프로퍼티 설명이
  *"Ignored if the queue is unbounded"* 로 **메커니즘까지 명시**하고 있음을 확인.
  오버라이드 부재도 전수 확인(yml·properties·executor 빈·virtual threads).
  **추측이 문서상 사실로 바뀌었고, 강도가 예상보다 셌다.**
  부수 발견: **`max-size` 는 손댈 수 없는 노브다** — 큐가 무제한인 한 무시되므로,
  완화를 논할 때 «스레드를 늘린다» 는 선택지가 애초에 없다. 손댈 곳은 `queue-capacity` 하나다.
  남은 것은 0-b(런타임 대조, 부하 없음)와 §3 2단계 본 실험.
- 2026-08-13(**0-b 완료**): **런타임에 `applicationTaskExecutor` 지표가 없다.**
  계측된 executor 는 `taskScheduler` 하나뿐이고 `applicationTaskExecutor` 는 0 회 등장.
  원인은 빈 정의의 **`@Lazy`** (바이트코드 확인) — 첫 `@Async` 호출 전까지 빈 자체가 없다.
  **0-a 의 설정값이 런타임에 실현됐는지는 여전히 미확인**이고, 0-b 는 «다르다» 가 아니라
  **«아직 태어나지 않았다»** 를 찾았다. 부수 발견: `/actuator/metrics` 는 401 이라
  샘플러는 `/actuator/prometheus` 를 폴링해야 한다.
  **이 «곁다리 확인» 이 §2 표 한 줄을 반증하고 본 실험 절차를 3 군데 바꿨다** —
  빈 깨우기 단계 추가 · 리셋 절차 전제 변경 · `@Lazy` 를 오염원 목록에 추가.
  📌 문서가 «`shadowfit-backend` 가 안 떠 있다» 고 적어 둔 것도 낡아 있었다(실제 4시간째 up).
- 2026-08-28(**본실험 완료, 로컬**): 사용자 승인으로 착수. 계획한 큐 샘플러(§2)를 만들다가
  **0-b보다 한 단계 나쁜 사실**을 확인했다 — 세션을 실제로 시작해 `@Async` 빈을 깨운 뒤(202,
  `IN_PROGRESS` 정상 생성)에도 `applicationTaskExecutor`가 `/actuator/prometheus`·
  `/actuator/metrics`에 **한 번도 안 잡힌다**(동시 20개로 재확인해도 동일). 이슈
  [#582](https://github.com/Shadowfit/init/issues/582)로 등록.
  큐 길이 계측이 불가능해져 대신 **서킷브레이커 상태**·**세션 FAILED 전이**로 6판(A B/B A/A B)
  실험을 진행했다. 결과: AI 정지(`docker pause`) 후 세션이 **3.6~3.9초**만에 FAILED로
  전이(5초 데드라인보다 먼저), 서킷은 **5~6초**에 OPEN(설정 `minimumNumberOfCalls=5`와 정합),
  회복 후 서킷이 다시 `CLOSED`로 돌아오기까지 **~10~12초 창**이 있다는 것을 새로 발견했다.
  `docker pause`는 SIGKILL과 달리 컨테이너를 재기동시키지 않는다(RestartCount 전 구간 0).
  🔴 **rig 자체의 로깅 경합 버그**로 판당 정확한 동시 요청 수는 못 셌다(DB 전수 확인으로
  실제 요청은 다 들어간 것은 확인, 서버 결함 아님). **H1(큐가 실제로 자라는가)은 여전히
  미답** — 이번 라운드는 «이 방법으로는 못 잰다»는 것 자체가 산출물이다.
  상세: [`../../loadtest/results/async-pool-2026-08-28/README.md`](../../loadtest/results/async-pool-2026-08-28/README.md).
  완화책 채택 여부는 미결정으로 남긴다.
- 2026-09-07(**H1 재측정 완료**): #667(2026-09-04, `ca1ae4f2`)이 `applicationTaskExecutor`를
  non-lazy로 고쳐 계측 채널(#582)이 부수적으로 해소된 것을 확인하고 재측정에 착수했다. 원본
  rig(병합 안 된 `measure/async-pool-backpressure-r2` 브랜치)를 `git show`로 추출해 재사용하되,
  로깅 경합 버그(파일 분리로 수정)와 큐 폴러(확인된 지표명 `executor_queued_tasks` 등)를 추가했다.
  **결과: 6판 전부 큐·활성스레드·풀크기가 0.0에서 안 움직였다** — AI 정지 상태에서도. 직접 호출
  테스트로 원인을 더 파봤더니 **`@Async`가 `applicationTaskExecutor`를 아예 안 거친다**는 게
  드러났다(`executor_completed_tasks_total`이 실행 후에도 0 · 로그의 스레드 이름이
  `TaskExecutor-77`→`92`로 코어 8개 재사용 없이 계속 올라감, 무제한 대체 실행기로 폴백하는
  패턴). H1은 "틀림"으로 판정하되, 원인이 "무언가가 큐를 막는다"가 아니라 "이 경로가 그 큐를
  아예 안 쓴다"로 특정됐다 — 무제한 큐보다 **무제한 스레드 생성**이 실제 리스크일 수 있다는,
  설계 당시엔 없던 새 결함이 열렸다. H2(서킷 5~6초 OPEN)·H3(FAILED 2.7~4.2초 전이)는 원 라운드와
  정합적으로 재현됐다. 상세: [`../../loadtest/results/async-pool-2026-09-07/README.md`](../../loadtest/results/async-pool-2026-09-07/README.md).
  원인 규명·이슈 등록·완화책은 미결정으로 남긴다.