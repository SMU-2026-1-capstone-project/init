# `applicationTaskExecutor` 큐 길이 — 다른 채널로 잴 방법

작성일: 2026-08-28
갱신: 2026-09-07 — **채널 문제가 이미 해결돼 있었다(문서 드리프트).** 이 문서가 다루는 "①JMX
②디버그 엔드포인트 ③`@Lazy` 제거" 세 후보 중 **③이 이미 다른 목적의 PR(#667, `ca1ae4f2`,
2026-09-04)로 적용돼 있었다** — `AsyncConfig.java`가 `applicationTaskExecutor`를 non-lazy
빈으로 직접 정의하도록 고쳐졌고, 회귀 테스트(`ApplicationTaskExecutorMetricsTest`)까지 있다.
그래서 아래 §2~§5의 "채널을 새로 골라야 한다"는 전제가 **낡았다** — 이미 Micrometer/
`/actuator/prometheus`로 `executor_queued_tasks{name="applicationTaskExecutor"}` 등이 정상
관측된다(2026-09-07 실측 확인). 이 채널로 원 실험(H1)을 재측정했고, 결과는
[`../../loadtest/results/async-pool-2026-09-07/README.md`](../../loadtest/results/async-pool-2026-09-07/README.md) —
그런데 **큐 길이가 6판 내내 0으로, `@Async`가 이 executor를 아예 안 쓴다는 새 결함이 나왔다.**
이 문서(①JMX·②디버그 엔드포인트 비교)는 그 새 결함과는 무관해졌고, 참고 자료로만 남긴다.
상태: **채널 문제 해결됨(#667로 부수 해소, 2026-09-04) · 재측정 완료(2026-09-07) · 아래 채널
비교 내용은 낡음**
대상: `applicationTaskExecutor`(Boot 자동 구성 `@Async` 풀)의 큐 길이·워커 수 계측
연관: [`./async-pool-backpressure-experiment.md`](./async-pool-backpressure-experiment.md)(H1이
이 계측 부재로 판정 불가로 끝난 원 실험) · 이슈 [#582](https://github.com/Shadowfit/init/issues/582) ·
[[feedback_no_arbitrary_threshold_values]] · [[feedback_user_decides_not_claude]]

---

## 0. 왜 이 문서가 있는가

`async-pool-backpressure-experiment.md`의 2026-08-28 본실험이 H1(큐 상한 없음)을 재려다
계측 채널 자체가 없다는 걸 확인했다 — 세션을 실제로 시작해 `@Async` 빈을 깨운 뒤에도
`applicationTaskExecutor`가 `/actuator/prometheus`·`/actuator/metrics`에 **한 번도 안 잡힌다**
(이슈 [#582](https://github.com/Shadowfit/init/issues/582)). 그 실험 §8이 "H1을 다른 채널로
다시 잴지"를 미결정으로 남겼다 — 이 문서가 그 채널을 설계한다.

---

## 1. 원인 재확인

`AsyncConfig.java`(1~29줄 전체)를 다시 읽었다 — `@Bean ThreadPoolTaskExecutorCustomizer`
하나만 있고, `applicationTaskExecutor` 빈 자체는 정의하지 않는다(주석이 스스로
"Boot가 자동 구성하는 `applicationTaskExecutor`를 커스터마이즈하는 방식"이라고 적어뒀다).
그 자동 구성 클래스(`TaskExecutorConfigurations$TaskExecutorConfiguration`)의 빈 정의에
`@Lazy`가 붙어 있다 — **첫 `@Async` 호출 전까지 빈 자체가 생성되지 않는다.** Micrometer의
executor 바인더는 컨텍스트 리프레시 시점에 존재하는 `Executor` 빈을 스캔해서 등록하는
방식으로 추정되고(코드로 확정한 건 아니다 — async-pool 문서도 "추정만 남긴다"로 적어뒀다),
`@Lazy`라 그 시점에 빈이 없으면 나중에 태어나도 바인더의 관찰 대상에 다시 들어가지
않는다. **한 줄 요약: `@Lazy`가 Micrometer 자동 계측을 구조적으로, 영구히 비켜간다.**

---

## 2. 후보 채널 셋

### ① JMX로 `ThreadPoolExecutor` 직접 노출

`applicationTaskExecutor` 빈을 어딘가에서 주입받아(`@Qualifier("applicationTaskExecutor")`)
`ThreadPoolTaskExecutor.getThreadPoolExecutor()`로 raw `java.util.concurrent.ThreadPoolExecutor`를
꺼낸 뒤, `MBeanExporter`나 `ManagementFactory.getPlatformMBeanServer().registerMBean(...)`으로
MBean 등록.

| | |
|---|---|
| 장점 | 표준 JMX 도구(JConsole·VisualVM)로 실시간 관찰 가능. Micrometer를 안 거치므로 `@Lazy` 문제와 무관 |
| 단점 | **원격 JMX 접근 자체가 이 프로젝트에 없다.** `application.yml`이 관리 포트(9090)를 의도적으로
       내부망에만 열어둔 이유(외부에 지표가 새는 걸 막으려고, §[6] 주석)와 같은 이유로, JMX 원격
       포트를 새로 여는 것은 그 설계 방향과 반대다. SSH 터널로 우회할 수는 있지만 **측정 한 번을
       위해 배포 토폴로지에 새 구멍을 낸다** — 이 실험(짧은 폴링, 6판)에 비해 과한 인프라 비용 |
| 코드 변경 면적 | 새 `@Configuration` 클래스 하나(빈 주입 + MBean 등록), 수 줄 |

### ② 임시 디버그 엔드포인트

기존 관리 포트(9090)에 새 액추에이터 엔드포인트 또는 평범한 `@RestController`를 하나 추가해
`((ThreadPoolTaskExecutor) applicationTaskExecutorBean).getThreadPoolExecutor()`의
`getQueue().size()`·`getActiveCount()`·`getPoolSize()`를 그대로 JSON으로 반환.

| | |
|---|---|
| 장점 | 기존 9090 포트·화이트리스트 체계를 그대로 재사용(새 포트·새 인프라 없음). `curl`로 폴링하면
       되므로 rig 쪽 변경이 거의 없다 — async-pool 실험의 서킷브레이커 폴링(`/actuator/circuitbreakers`)과
       같은 모양 |
| 단점 | **프로덕션 코드에 "임시" 딱지가 붙은 진단용 엔드포인트가 남는다.** 실험이 끝난 뒤 지우지
       않으면 잊혀진 디버그 경로가 되고, 지우면 다음에 같은 질문이 나올 때 또 만들어야 한다.
       인증 경계도 새로 정해야 한다 — 화이트리스트에 넣을지(그럼 인증 없이 큐 길이가
       새 나간다), 기존 Bearer 인증을 그대로 태울지(그럼 async-pool rig처럼 토큰을 매번
       실어야 한다) |
| 코드 변경 면적 | 컨트롤러 클래스 하나(또는 기존 액추에이터 엔드포인트에 커스텀 `@Endpoint` 추가),
       10~20줄. **가장 작다** |

### ③ `@Lazy` 제거 — 원인 수정

`TaskExecutorConfigurations$TaskExecutorConfiguration`은 Boot 내부 클래스라 직접 못 고친다.
대신 `AsyncConfig`에 `applicationTaskExecutor` 이름으로 **직접 빈을 재정의**(현재 커스터마이저
방식 대신)하면서 `@Lazy`를 안 붙이면, Micrometer가 기동 시점부터 정상 계측한다.

| | |
|---|---|
| 장점 | Micrometer·`/actuator/prometheus`를 그대로 쓴다 — 새 엔드포인트·새 포트 없음. 회귀
       테스트·모니터링에도 영구히 남아 다음에 같은 질문이 또 나와도 계측이 이미 있다 |
| 단점 | **이건 "다른 채널로 우회"가 아니라 "원인을 고친다"** — 즉 이 갈래를 택하면 애초에
       #582를 "다른 채널이 필요한 계측 결함"이 아니라 "고칠 수 있는 결함"으로 재분류하는
       셈이다. 부작용도 있다: `@Lazy`가 있던 이유는 "쓰기 전까지 스레드 풀을 안 만든다"는
       지연 초기화 최적화라, 없애면 **앱 기동 시점에 풀 생성 비용이 옮겨간다**(async-pool
       문서 §3 1단계가 이미 "`@Lazy` 자체가 오염원 — 첫 요청이 풀 생성 비용을 문다"고
       적어뒀다 — 그 비용이 사라지는 게 아니라 위치만 바뀐다). 정말 얼마나 되는 비용인지는
       **미측정**이다 |
| 코드 변경 면적 | `AsyncConfig`에 `@Bean(name = "applicationTaskExecutor") @Primary` 형태로
       executor를 직접 정의(Boot 기본값 그대로 옮기면 동작은 안 바뀜) — 수십 줄, 단
       **`AsyncConfig.java`의 존재 이유였던 주석**("별도 executor 빈을 새로 정의하지
       않는다")과 정면으로 배치되는 변경이라 문서·주석도 같이 고쳐야 한다 |

---

## 3. 비교 요약

| | ① JMX | ② 디버그 엔드포인트 | ③ `@Lazy` 제거 |
|---|---|---|---|
| 새 인프라(포트 등) | 필요(원격 JMX) | 불필요 | 불필요 |
| 코드 변경 면적 | 작음 | **가장 작음** | 중간, 주석·설계 문서도 같이 |
| 실험 종료 후 정리 | MBean 등록 코드 제거 | 컨트롤러 제거 | **원상복구 안 함(영구 계측)** |
| 성격 | 우회 | 우회 | 원인 수정(다른 질문이 된다) |
| 부작용 미측정분 | 없음(관찰만 추가) | 없음(관찰만 추가) | 기동 지연 이동분 — 크기 모름 |

**추천 — ②(임시 디버그 엔드포인트).** 이 실험 자체가 6판짜리 단발 측정이라 ①의 새 인프라
비용이나 ③의 영구 코드 변경(+ 기동 비용 재배치)을 감수할 이유가 약하다. ②는 async-pool
rig이 이미 하고 있는 "액추에이터를 짧은 간격으로 직접 폴링"(§4-2) 패턴에 항목 하나만
추가하는 것과 다름없다. **다만 이건 추천이지 확정이 아니다** — 어느 채널을 쓸지는 사용자
확인 후 박제한다([[feedback_user_decides_not_claude]]).

---

## 4. 재실험 계획 — 채널이 정해지면

`async-pool-backpressure-experiment.md` §3 2단계(팔 A/B, 3판씩 순서 반전)를 그대로 재사용한다
— 새 판정 기준을 만들지 않는다. 바뀌는 것은 딱 하나, 큐 샘플러 하나가 늘어난다:

- 폴링 대상에 새 채널(② 채택 시 `GET /internal/debug/executor` 같은 경로)을 추가
- §4-2(프로메테우스 스크레이프 간격이 해상도 하한)의 경고가 그대로 적용된다 — **액추에이터를
  직접 짧은 간격으로 폴링**해야 톱니(H2가 묻는 "서킷 OPEN 전 큐 성장")가 안 뭉갠다. 간격
  자체는 폴링이 오염원이 되지 않는 수준에서 골라야 하고(§4-2 "샘플러 자체가 부하다"), 구체적
  숫자는 이 문서가 정하지 않는다 — 채널이 정해진 뒤 새 rig을 만들 때 결정
- §4-1(물리 2코어) 오염원도 그대로 물려받는다 — 절대 큐 길이는 인용 금지, 메커니즘과 팔 간
  델타만 신뢰
- rig의 로깅 경합 버그(§8 미결정 두 번째 항목, 판당 동시 요청 수를 못 셌던 문제)는 이
  재실험과 **별개 결정**이다 — 고치고 갈지는 채널 선택과 독립적으로 판단할 것

---

## 5. 미결정 (사용자 confirm 필요)

- [ ] **채널 선택** — ①/②/③ 중 하나. §3의 추천은 ②지만 결정은 아니다
- [ ] **재실험 착수 여부·시점** — 채널이 정해진 뒤에도 별도 확인 필요
- [ ] **rig 로깅 경합 버그 수정 여부** — §4에 적었듯 채널 선택과 독립된 결정

---

## 결정 로그

- 2026-08-28: 설계 초안. `@Lazy`가 Micrometer 자동 계측을 영구히 비켜가는 원인을
  `AsyncConfig.java` 재확인으로 다시 못박고, 후보 채널 셋(JMX·디버그 엔드포인트·`@Lazy`
  제거)을 비교. ②(디버그 엔드포인트)를 추천으로 표시했으나 확정 아님 — 채널 선택·재실험
  착수 둘 다 미결정으로 남긴다.
