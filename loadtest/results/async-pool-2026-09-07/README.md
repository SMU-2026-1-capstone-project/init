# `@Async` 풀 백프레셔 재측정 — H1 드디어 판정, 그리고 더 큰 결함 발견 (2026-09-07, 로컬)

측정일: 2026-09-07 · 박스: 로컬(i3-6100, 물리 2코어) · 커밋: `9c7969b0`(#667 포함)
설계: [`../../../docs/decisions/async-pool-backpressure-experiment.md`](../../../docs/decisions/async-pool-backpressure-experiment.md)
선행: [2026-08-28 1차 본실험](https://github.com/Shadowfit/init/tree/measure/async-pool-backpressure-r2/loadtest/results/async-pool-2026-08-28)(병합 안 된 브랜치 — H2·H3만 실측, H1은 계측 채널 부재로 미답)
rig: [`run_async_pool_arms.sh`](./run_async_pool_arms.sh)(원본 rig에 로깅 경합 버그 수정 + 큐 폴러 추가)

---

## 0. 한 줄 요약

**H1은 "큐가 안 자란다"로 판정됐는데, 이유가 예상과 다르다 — 큐가 안전해서가 아니라 `@Async`가 `applicationTaskExecutor`를 아예 안 쓰기 때문이다.** 6판 내내 `executor_queued_tasks`·`executor_active_threads`·`executor_pool_size_threads`가 전부 **0.0에서 한 번도 안 움직였다**(AI 정지 상태에서도). 직접 호출 테스트로 확인한 결과, `sendAnalysisRequestToFastApi`가 실제로 실행되는데도(`FastAPI 응답 수신` 로그 확인) `executor_completed_tasks_total{name="applicationTaskExecutor"}`은 여전히 0 — **이 실행 경로가 그 executor를 전혀 거치지 않는다는 뜻**이다. 로그의 스레드 이름도 `TaskExecutor-77`, `TaskExecutor-78`... 처럼 호출마다 새 번호로 계속 올라가(8개짜리 코어 풀의 재사용 패턴이 아니다), Spring이 `@Async`에 대해 이름 미상의 대체 실행기(스레드마다 새로 만드는 무제한 실행기로 추정)로 **조용히 폴백**하고 있는 것으로 보인다.

즉 **설계 문서의 반증조건표(§1)가 이미 예견한 다섯 번째 갈래("큐가 애초에 안 자란다 → H1이 틀린 것")가 실제로 일어났는데, 그 원인은 "무언가가 큐 앞에서 막고 있다"가 아니라 "이 실행 경로가 애초에 그 큐를 통과하지 않는다"였다.** 이건 예상보다 심각한 결함이다 — 무제한 큐 대신 **무제한 스레드 생성**이 실제 리스크다.

H2·H3는 원 라운드와 정합적으로 재현됐다.

---

## 1. 무대 — 원본 rig 재사용 + 버그 둘 수정

| | |
|---|---|
| rig 원본 | `measure/async-pool-backpressure-r2`(병합 안 된 브랜치)의 `run_async_pool_arms.sh` — `git show`로 내용만 추출, 브랜치는 merge 안 함 |
| 수정 1 — 로깅 경합 | 원본 `fire_burst`가 여러 백그라운드 서브셸을 같은 파일에 동시 append해서 응답을 유실했다(원 라운드 15개 중 1~3개만 로그에 잡힘). **요청마다 별도 파일에 쓰고 나중에 합치는 방식**으로 고쳤다 — 이번 라운드는 **15/15 전부 유실 없이 잡혔다**(정량적 주장 가능) |
| 수정 2 — 큐 폴러 | #667(`ca1ae4f2`, 2026-09-04)이 `applicationTaskExecutor`를 non-lazy로 고쳐 Micrometer가 이제 이 executor를 잡는다. 실측으로 확인한 정확한 지표명: `executor_queued_tasks{name="applicationTaskExecutor"}`·`executor_active_threads{name="applicationTaskExecutor"}`·`executor_pool_size_threads{name="applicationTaskExecutor"}`. `/actuator/prometheus`를 직접 짧은 간격(체감 ~2초/틱, `docker exec` MySQL 조회가 지배)으로 폴링 |
| 계정 | 원본 계정(비밀번호 불명, 토큰 만료)을 재사용 못 해 **20개(부하용) + 1개(관리 폴링용) 신규 생성**. `preferredUrl` 온보딩이 없으면 세션 시작이 400으로 막힌다는 걸 착수 중 새로 발견(원본 rig README의 "계정 풀 + preferred_url 세팅" 언급이 이유였다) — `PATCH /member/onboarding/{email}`로 21개 계정 전부 처리 |
| 컨테이너 | `docker compose build shadowfit-backend`로 재빌드 필수 확인 — 재빌드 전엔 Flyway 마이그레이션 충돌(V11/V13 체크섬 어긋남, 다른 동시 세션이 남긴 상태)로 기동 자체가 안 됐다(원인·조치는 코디네이터가 처리, 이 문서 범위 밖) |
| 팔·판 | 원 설계 그대로 — A(AI 정상)/B(`docker pause shadowfit-ai`), 6판 `A B B A A B`, `N_SESSIONS=15` |

⚠️ [[project_loadtest_env_constraint]] 그대로 적용 — 절대 처리량·절대 시간은 하드웨어 종속, 메커니즘과 팔 간 델타만 신뢰.

---

## 2. 결과

### 2-1. 큐·워커 지표 — 6판 전부 0.0

| 판 | 팔 | 최대 `q`(큐) | 최대 `active` | 최대 `poolsize` |
|---:|---|---:|---:|---:|
| 1 | A | 0.0 | 0.0 | 0.0 |
| 2 | B | 0.0 | 0.0 | 0.0 |
| 3 | B | 0.0 | 0.0 | 0.0 |
| 4 | A | 0.0 | 0.0 | 0.0 |
| 5 | A | 0.0 | 0.0 | 0.0 |
| 6 | B | 0.0 | 0.0 | 0.0 |

부하 전(baseline)도 0.0, AI 완전 정지 상태(팔 B, 15개 세션 전부 gRPC 데드라인 대기 중)에도 0.0 — **차이가 없다.**

### 2-2. 직접 확인 — `@Async`가 `applicationTaskExecutor`를 거치지 않는다

```
BEFORE: executor_completed_tasks_total{name="applicationTaskExecutor"} 0.0
[세션 시작 성공, sessionId=104115, HTTP 202]
AFTER 1s: executor_completed_tasks_total{name="applicationTaskExecutor"} 0.0   ← 그대로
```

같은 요청의 백엔드 로그:

```
01:30:56.087 [TaskExecutor-92] ... 비동기 분석 요청 시작 - 세션 ID: 104115
01:30:56.146 [ult-executor-28] ... FastAPI 응답 수신 - 세션: 104115
01:30:56.146 [ult-executor-28] ... FastAPI 전송 완료
```

`@Async` 메서드가 **실제로 실행됐다**(로그 확인) — 그런데 실행한 스레드 이름이 `TaskExecutor-92`다. 같은 창(15~16개 동시 호출)에서 관측된 스레드 번호가 `TaskExecutor-77`부터 `TaskExecutor-92`까지 **호출마다 새로 올라간다** — 코어 8개짜리 풀이 재사용되는 패턴(`task-1`~`task-8` 반복)이 아니라, **호출마다 새 스레드를 만드는 무제한 실행기**의 패턴이다. `executor.pool.core`(#667 회귀 테스트가 확인한 그 게이지)는 여전히 8.0으로 존재하지만, 이 실행 경로가 그 풀을 안 쓴다.

**결론 — 이 코드베이스에서 `applicationTaskExecutor`는 `@Async sendAnalysisRequestToFastApi`에 대해 사실상 죽은 설정이다.** 원인은 이 라운드에서 코드로 확정하지 않았다(추정: Spring의 `@Async` 기본 실행기 탐색이 이름("taskExecutor")이나 유일성 조건을 못 만족해 대체 실행기로 폴백하는 것으로 보이나, 검증은 별도 착수 필요).

### 2-3. H2·H3 — 원 라운드와 정합적으로 재현

| 판(B, 오염 없음) | FAILED 전이 | 서킷 OPEN |
|---|---:|---:|
| rep2 | t≈4.2s | t≈6.4s |
| rep6 | t≈2.7s | t≈5.8s |

원 라운드(2026-08-28)의 3.6~3.9초(FAILED)·5~6초(OPEN)와 **같은 자릿수, 같은 방향**으로 재현됐다.

rep3(B)는 **판 사이 리셋이 불완전했다** — rep2의 서킷이 `waitDurationInOpenState=10s`를 다 못 채운 채(unpause 후 3초만 대기) HALF_OPEN 상태로 rep3이 시작돼, 15개 중 8개만 FAILED로 전이하고 7개는 폴링 종료 시점(9.6초)까지 IN_PROGRESS로 남았다. **이건 결함이 아니라 rig의 판 사이 대기 시간(`sleep 3`)이 서킷 완전 복구(≈10~12초 창, 원 라운드가 이미 발견)보다 짧아서 생긴 교락**이다 — 설계 문서 §3 "판 사이 리셋" 항목이 경고한 바로 그 위험이 실제로 한 번 발생한 것. rep4(A)도 그 잔재(2 CLOSED + 1 HALF_OPEN으로 시작)를 물려받았지만 AI가 정상이라 결과에 영향 없음.

---

## 3. §1 반증조건표 재적용 — 최종 판정

| 반증 조건 | 이번 라운드 결과 | 판정 |
|---|---|---|
| 큐 길이가 자라지 않는다 → H1이 틀렸다 | **그대로 일어남**(6판 전부 q=0.0) | **H1 틀림.** 단, 원래 가설이 예상한 이유("무언가가 큐 앞에서 막는다")가 아니라 **"이 경로가 그 executor를 아예 안 쓴다"**로 원인이 특정됨(§2-2) |
| 워커가 8을 넘어 늘어난다 → "max-size는 죽은 설정"이 틀렸다 | `applicationTaskExecutor`의 `poolsize`는 0에서 안 움직였으니 이 조건 자체가 성립 안 함(그 풀은 관찰 대상이 아니었다) | 판정 불가 — 이 풀 기준으로는 의미 없는 질문이 됨 |
| 서킷 OPEN 뒤에도 큐가 계속 자란다 → H2 틀림 | 큐가 애초에 안 자랐으므로 해당 없음 | 판정 불가(위와 같은 이유) |
| FAILED 전이 지연이 큐 길이와 무관하다 → H3 틀림 | 큐가 0인 채로 FAILED 전이가 일관되게 재현(2.7~4.2s) — "큐 대기" 때문이 아니라 **gRPC 데드라인/연결 실패 자체**가 지연의 원인임이 오히려 더 뚜렷해짐 | H3의 "전이가 큐 대기만큼 밀린다"는 표현은 이 결과로 보면 **부정확** — 정확히는 "AI 무응답이 gRPC 레벨에서 감지되는 데 걸리는 시간"이 지연의 실체다(큐 대기가 아니라) |
| 서킷이 5건이 아닌 곳에서 열린다 | 오염 없는 두 판(rep2·rep6) 모두 설정대로(`minimumNumberOfCalls=5`) 열림 | 설정대로 동작 확인 |

---

## 4. 한계

- 물리 2코어 로컬 박스([[project_loadtest_env_constraint]]) — 절대 시간 인용 금지, 메커니즘·델타만
- rep3·rep4는 판 사이 서킷 미완전 복구로 부분 오염(§2-3) — H2·H3 판정은 오염 없는 rep2·rep6 기준
- **`@Async`가 왜 `applicationTaskExecutor`를 안 쓰는지는 이 라운드가 원인을 코드로 확정하지 않았다** — 추정(Spring 기본 실행기 탐색 실패로 대체 실행기 폴백)만 남긴다. 원인 규명과 수정은 별도 착수 필요(결함 성격상 [[feedback_troubleshooting_to_issues]]대로 이슈 등록 대상)
- 큐 폴링 간격이 설정값(0.5s)보다 실제로 느렸다(`docker exec` MySQL 조회가 지배, 체감 ~2초/틱) — 그래도 6판 전부 최댓값이 0.0으로 일관돼 해상도 문제로 결과가 뒤집힐 가능성은 낮다고 판단(만약 진짜 풀을 썼다면 15개 동시 투입 순간 즉시 8~15 사이로 튀었어야 하고, 그 정도 크기 변화는 2초 해상도로도 놓치기 어렵다)

---

## 결정 로그

- 2026-09-07: #667(non-lazy 빈) 적용 후 재측정 착수. 원본 rig(병합 안 된 브랜치)의 로깅 경합 버그를
  파일 분리 방식으로 고치고, 확인된 지표명(`executor_queued_tasks` 등)으로 큐 폴러를 추가했다.
  6판 실행 결과 큐가 전혀 안 자랐는데, 직접 호출 테스트(`executor_completed_tasks_total` 불변 +
  스레드 이름 `TaskExecutor-N`이 코어 8개 재사용 패턴이 아니라 계속 올라감)로 **`@Async`가
  `applicationTaskExecutor`를 아예 안 거친다**는 새 결함을 발견했다. H1은 "틀림"으로 판정하되
  원인이 설계가 예상한 것과 다르다는 것까지 함께 박제한다. H2·H3는 원 라운드와 정합적으로 재현.
