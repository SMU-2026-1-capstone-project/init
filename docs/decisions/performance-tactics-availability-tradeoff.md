# 아키텍처 품질속성 — 성능 택틱과 가용성 트레이드오프

작성: 2026-09-04
상태: 🔶 **분석/추천 — 결정 미확정** ([[feedback_user_decides_not_claude]] — 채택 여부는 사용자 confirm 후)
연관: [`./slo-baseline.md`](./slo-baseline.md) §4-2·§4-4(실측 판정선), [`./load-test-strategy.md`](./load-test-strategy.md) §4.2(DAU 1,000 가정 원본), [`./ai-sticky-routing.md`](./ai-sticky-routing.md) §1-1(피크 67 산식), [`./redis-adoption.md`](./redis-adoption.md) §3-3(fail-open/close 갈림 선례), [`./pool-cliff-vs-concurrency.md`](./pool-cliff-vs-concurrency.md)·[`./commit-count-and-mysql-metrics.md`](./commit-count-and-mysql-metrics.md)(풀 사이징 근거), [`./circuit-breaker-worker-aggregation.md`](./circuit-breaker-worker-aggregation.md)(현재 서킷브레이커 한계)

> 🔴 **이 문서는 "품질속성 → 택틱 → 트레이드오프"를 QAS(Quality Attribute Scenario) 형식으로 정리한다.**
> 값은 전부 이 프로젝트가 이미 못박은 가정([[feedback_state_assumption_design_to_it]] — DAU 1,000)이거나
> 실측 baseline이다. **새 임의값은 넣지 않았다**([[feedback_no_arbitrary_threshold_values]]).

---

## 0. 한 줄 요약

**성능 택틱 후보 3개**(읽기 캐싱 확장 · 커넥션 풀·동시성 사이징의 실측 기반 재확정 · 동기 경로의 비동기 전환 확대)는 **가용성을 깎지 않는 조건**을 충족한다 — 셋 다 "실패 시 기존 경로로 안전하게 후퇴"가 가능하기 때문이다. 반대로 **이미 이 프로젝트가 검토했던 두 후보**(커밋 내구성 완화, Redis fail-close 정책)는 성능은 오르지만 각각 데이터 안전·가용성을 깎는 대가가 있어 **제외 대상**으로 다시 확인한다.

---

## 1. 품질속성 시나리오(QAS) 정의

### 1-1. 성능 시나리오

| 요소 | 내용 |
|---|---|
| **자극원(source)** | 오픈 직후 앱을 켠 사용자 |
| **자극(stimulus)** | 세션 시작·종료(쓰기), 리포트·캘린더 조회(읽기) 요청 |
| **대상(artifact)** | Spring 백엔드 HTTP API |
| **환경(environment)** | DAU 1,000 가정 하 피크 시간대([`load-test-strategy.md` §4.2](./load-test-strategy.md)) |
| **응답(response)** | 정상 처리·응답 반환 |
| **응답 측정치** | **쓰기 p99 ≤ 300ms · 읽기 p99 ≤ 1s** — 이미 `slo-baseline.md` §4-2가 Nielsen 지각 임계(300ms=명확한 지연 시작점, 1s=답답함 시작점)를 앵커로 제안한 목표치 |

**이미 있는 실측(참고 baseline, 목표가 아니라 사실)**:
- 쓰기(`POST /sessions`, `PATCH /sessions/{id}/end`): 가정 피크의 **180배** 부하까지 p99 **17ms** (목표의 5.7%) — [`http-write-p99-aws-2026-08-24`](../../loadtest/results/http-write-p99-aws-2026-08-24/README.md)
- 읽기(리포트·캘린더): 가정 피크의 **360배** 부하까지 p99 **12ms** (목표의 1.2%) — [`http-read-p99-ec2-2026-08-28`](../../loadtest/results/http-read-p99-ec2-2026-08-28/README.md)
- 🔴 두 실측 다 **EC2 2대 분리 구성**(대상 c7i.2xlarge)이고, ×360·×180은 **이 실험이 건 상한이지 시스템의 진짜 천장이 아니다**(더 큰 배수는 미실행).

**읽는 법**: 지금 목표(300ms/1s) 대비 여유가 매우 크다 — 즉 **"현재 병목은 이 HTTP 경로가 아니다."** 아래 택틱은 이 사실을 무시하고 아무 데나 캐시를 넣는 게 아니라, **다른 축(가용성)에 해가 없는 선에서 이 여유를 어디에 더 쓸지**를 고르는 문제다.

### 1-2. 가용성 시나리오

| 요소 | 내용 |
|---|---|
| **자극원** | 오픈 직후 동시 유입 트래픽 |
| **자극** | 피크 동접 **67세션**(DAU 1,000 × 1.5세션/일 × 집중도 p=0.18 × 15분/60, [`ai-sticky-routing.md` §1-1](./ai-sticky-routing.md)) |
| **대상** | Spring↔MySQL 커넥션 풀, AI 서버(FastAPI) 처리 용량 |
| **환경** | 정상 운영, 단일 배포 인스턴스(현재 배포 대상 0~1대) |
| **응답** | 요청 거부·타임아웃 없이 큐잉으로 흡수 |
| **응답 측정치** | **`HikariCP timeout_total = 0`**(포화해도 붕괴 아님, [`slo-baseline.md` §4-4](./slo-baseline.md)) · **AI 동시 처리 용량이 피크 67을 상회** |

**이미 있는 실측(baseline)**:
- 커넥션 풀: pool=5·c=100에서 pending 95인데 **timeout 0** — 포화해도 큐가 빠짐. 현재 운영값 `maximum-pool-size: 15`는 이 실측(10부터 plateau, 5는 부족)의 여유값(`application.yml:33-55`)
- AI 처리 용량: 배포 구성 실측 천장 **89~105세션** vs 필요 **67** → 여유 **1.33~1.57배** ([`ai-load-budget.md`](./ai-load-budget.md))
- AI 워커 장애 복구: 워커 1개 크래시 시 컨테이너 전체(3개) 재기동, **다운타임 평균 25.3초(17.3~35.3초)** — [`circuit-breaker-worker-aggregation.md`](./circuit-breaker-worker-aggregation.md) §1. 서킷브레이커는 워커별로 분리돼 있으나 크래시 모드에서는 셋이 같이 죽는다는 것도 실측으로 확인됨

**읽는 법**: 가용성도 지금은 여유가 있다 — 그런데 **그 여유의 성격이 다르다.** HTTP 경로는 "일어나지 않을 만큼 안 아프다"이고, AI 용량은 "가정치보다 1.3~1.6배 여유"라 **성능처럼 압도적이지 않다.** 성능 택틱을 고를 때 **이 좁은 여유를 깎아 먹지 않는지**가 판정 기준이 된다.

---

## 2. 제약사항 (확인 필요 🔶)

아래는 이 프로젝트 문서에서 이미 확인 가능한 제약을 정리한 것이다. **원 질문의 "제약사항 ~~~"에 이것과 다른 항목(팀 규모, 일정, 특정 인프라 예산 등 과제·수업에서 주어진 제약)이 있다면 알려달라 — 그 내용으로 §3의 트레이드오프 판단이 바뀔 수 있다.**

| # | 제약 | 근거 |
|---|---|---|
| C1 | 배포 대상이 사실상 0~1대 — 다중 인스턴스 운영 경험 없음 | `architecture-review-2026-08-11.md` 결함③ |
| C2 | 로컬 개발 환경은 물리 2코어 — 성능 실측을 로컬에서 하면 이웃 프로세스 간섭으로 오염 | [[project_loadtest_env_constraint]] |
| C3 | AI 서버가 세션 상태를 프로세스 메모리에 보유(stateful) — 수평 확장에 필요한 어피니티 미구현 | `architecture-review-2026-08-11.md` §1 |
| C4 | 규모 설계 목표는 DAU 1,000(관측된 실사용 트래픽 아님, 가정) | `load-test-strategy.md` §4.2 |
| C5 | 신입 포트폴리오가 목적 — 과도한 인프라 투자(운영 전담 조직·유상 매니지드 서비스)는 범위 밖 | `project-destination-and-exit-criteria.md` |

---

## 3. 성능 택틱 후보 3개 — 가용성 트레이드오프 분석

각 택틱을 "성능에 주는 효과"·"가용성에 주는 영향"·"영향이 없다고 판단하는 근거"로 나눠 본다. **가용성을 깎는 판정이 나오면 그 택틱은 채택 후보에서 뺀다.**

### 택틱 A — 읽기 경로 로컬 캐싱 확장 (Caffeine)

| 항목 | 내용 |
|---|---|
| 적용 대상 | 관리자 대시보드 집계(현재 "보류" 상태, [`redis-adoption.md`](./redis-adoption.md) ㄷ) — 카탈로그 3종은 이미 적용 중 |
| 성능 효과 | 대시보드 비용의 98%를 차지하는 집계 쿼리(425ms+630ms급, [`admin-page-scope.md` §4-5](./admin-page-scope.md))를 캐시 히트 시 사실상 0으로 |
| 가용성 영향 | **없음(중립)** |
| 왜 없다고 보는가 | Caffeine은 **JVM 내부 로컬 캐시라 외부 네트워크 의존이 추가되지 않는다.** 캐시 미스·만료 시 그냥 기존 DB 쿼리 경로로 자연스럽게 후퇴한다 — 새로운 실패 모드(캐시 서버 다운 등)가 생기지 않는다 |
| 놓치면 안 되는 선결 조건 | `redis-adoption.md` §5가 이미 지적: **집계 전략(사전집계 vs on-read) 결정이 먼저** — 안 그러면 캐시가 느린 쿼리를 가리기만 하고 나중에 못 걷어낸다. 이건 성능·가용성 트레이드오프가 아니라 **설계 순서의 문제**라 별도로 남긴다 |

### 택틱 B — 커넥션 풀·AI 동시성 사이징의 실측 기반 재확정

| 항목 | 내용 |
|---|---|
| 적용 대상 | `HikariCP maximum-pool-size`(현재 15, 실측 근거는 "10부터 plateau, 5는 부족"뿐 — 10~20 사이는 안 좁혀짐), AI 워커 프로세스 수(현재 N=3, GIL 회피 목적으로 이미 확정·실측됨: 270.7→352.6rps, 1.30배, [`architecture-review-2026-08-11.md`](./architecture-review-2026-08-11.md) §6) |
| 성능 효과 | 동시 처리량 개선 — 이미 N=3 도입분만으로 1.30배 실증됨 |
| 가용성 영향 | **조건부 중립 — 상한을 두면 안전, 상한 없이 올리면 오히려 위험** |
| 왜 조건부인가 | 커넥션 풀을 DB의 `max_connections`보다 높게 잡거나, 프로세스 수를 물리 코어 이상으로 무한정 늘리면 **자원 경합으로 오히려 전체가 느려지거나(스와핑·컨텍스트 스위칭), DB가 연결을 거부**할 수 있다. `commit-count-and-mysql-metrics.md`의 실측이 보여준 것은 "10~20 사이에서는 안전"이지 "높을수록 좋다"가 아니다 — **사이징은 실측된 범위 안에서만** 하고, 그 밖으로 나가는 안은 이 택틱에서 제외한다 |
| 이미 있는 안전판 | `timeout_total > 0`을 "가용성 붕괴"로 이미 판정선에 박아둠([`slo-baseline.md` §4-4](./slo-baseline.md)) — 사이징을 조정해도 이 지표가 0을 유지하는지로 항상 검증 가능 |

### 택틱 C — 동기 처리의 비동기·배치 전환 확대

| 항목 | 내용 |
|---|---|
| 적용 대상 | 현재 세션 종료·피드백 배치(`ReportFeedbackBatch`)·아웃박스에 이미 적용된 패턴을, 아직 동기인 무거운 조회·집계 경로로 확대 |
| 성능 효과 | 사용자 체감 latency 감소(응답을 먼저 반환하고 후속 처리는 비동기) + **커넥션 점유 시간 단축**으로 풀 회전율 개선 |
| 가용성 영향 | **긍정적** |
| 왜 긍정적인가 | 동기 대기가 짧아지면 스레드·커넥션이 더 빨리 반환되어 **같은 풀 크기로도 더 많은 동시 요청을 흡수**한다. 이미 아웃박스가 "실패해도 재시도로 회수 가능"이라는 성질을 증명했다([`slo-baseline.md` §4-5](./slo-baseline.md) — 정상 재시도로 13.5분 안에 결판) — 같은 안전망을 확대 적용하는 것이라 새 리스크가 작다 |
| 대가 | 즉시 일관성이 결과적 일관성으로 바뀌는 지점이 늘어난다 — **사용자 대면 화면 중 "즉시 반영이 필요한 곳"에는 적용하면 안 된다**(이 프로젝트가 이미 ms 영역/s 영역을 구분해온 원칙, [`latency-perception.md` §9](./latency-perception.md)) |

---

## 4. 제외 대상 — 가용성(또는 인접 축)을 깎는 택틱

요청에 따라 가용성을 떨어뜨리는 방안은 후보에서 제외한다. 이미 이 프로젝트가 실측·검토까지 마친 두 사례를 근거로 남긴다.

| 후보 | 성능 효과 | 제외 사유 |
|---|---|---|
| **트랜잭션 커밋 내구성 완화**(`sync_binlog=0` 등) | 실측 **+78%**, 배수로는 **3.47배**(231.6→803.1 RPS) — 이 프로젝트에서 가장 큰 단일 레버 | 🔴 **채택 안 함.** 가용성 자체보다는 **데이터 안전(내구성)을 판 대가**다 — 인접 축이지만 같은 이유로 배제: "안 아픈 곳을 고치며 안전을 깎는" 셈이라 이미 기각([`pool-cliff-vs-concurrency.md`](./pool-cliff-vs-concurrency.md)) |
| **Redis 캐시의 fail-close 정책** | 캐시로 얻는 성능 이득은 fail-open과 동일 | 🔴 **가용성 직접 저해.** Redis 장애 시 전면 401 처리(fail-close)를 택하면 **Redis가 SPOF가 되어 전체 서비스가 멈춘다**([`redis-adoption.md` §3-3](./redis-adoption.md)). 같은 캐시 도입이라도 **fail-open + 경보**로 정책을 잡으면 이 배제 대상에서 빠진다 — 정책이 갈림길이지 캐시 자체가 문제가 아니다 |

---

## 5. 요약 표

| 택틱 | 성능 | 가용성 | 채택 후보 |
|---|:--:|:--:|:--:|
| A. 로컬 캐싱 확장(Caffeine) | ↑ | 중립 | 🟢 |
| B. 풀·동시성 사이징 재확정(실측 범위 내) | ↑ | 조건부 중립(상한 준수 시) | 🟢 |
| C. 동기→비동기 전환 확대 | ↑(체감) | ↑ | 🟢 |
| 커밋 내구성 완화 | ↑↑(최대) | — (데이터 안전 저해) | 🔴 제외 |
| Redis fail-close 캐시 | ↑ | ↓ (SPOF) | 🔴 제외(fail-open이면 재검토 가능) |

---

## 6. 미결정 (사용자 confirm 필요)

- [ ] §2 제약사항이 이 문서가 추정한 C1~C5로 충분한지, 원래 갖고 있던 제약사항 목록과 다른 항목이 있는지
- [x] 택틱 A~C 중 실제로 착수할 것 — **A부터 착수** (2026-09-04 사용자 confirm). B·C는 보류
- [x] 택틱 A 착수 시 선결 조건(집계 전략 결정)을 먼저 닫을지, 병행할지 — **병행** (2026-09-04 사용자 confirm). `redis-adoption.md` §5의 "집계 전략 먼저 → 그래도 남는 비용 있으면 캐시" 권고 순서를 뒤집는 결정
- [ ] 택틱 B의 "실측 범위 내" 상한을 구체적으로 어디로 잡을지(10~20 사이 재실측 여부)

## 결정 로그

- 2026-09-04: 택틱 A(관리자 대시보드 집계 Caffeine 캐싱)부터 착수, 집계 전략(`admin-page-scope.md` §4-5-2 ④가 남긴 b — ㉯ 캐시 TTL / ㉰ 사전집계 / ㉮ 감수)은 병행 결정한다 — 순서를 강제하지 않는다([[feedback_user_decides_not_claude]]).
- 2026-09-04: 택틱 A 구현 세부 확정(사용자 confirm) — **무효화는 TTL 만료만**(explicit evict 배선 안 함, 이 문서 §3 택틱A의 "새 실패모드 없음" 근거를 그대로 지키기 위함), **TTL=5분**은 새로 지어낸 값이 아니라 `admin-page-scope.md` §2("몇 분 늦어도 된다")를 해석한 `redis-adoption.md` §5·§9의 기존 "1~5분" 범위 중 상한을 그대로 채택한 것. 카탈로그 3종(`application.yml:117-121`, `expireAfterWrite=1h`)과는 목적이 달라 별도 `adminDashboardStats` spec으로 분리.
- 2026-09-04: 택틱 A 구현 완료 — `SessionRepository.countGroupedByStatus()`(b, `admin-page-scope.md` §4-5-2 ④가 남긴 유일한 비용)에 `@Cacheable("adminDashboardStats")` 배선, `CacheConfig.java` 신설(카탈로그 3종 spec과 분리), `application.yml`의 `spring.cache.*` 프로퍼티 배선은 제거(단일 spec 제약으로 더 이상 못 씀). 구현 중 `AdminStatsService.statusDistribution()`이 `private`+같은 클래스 내부 호출이라 거기에 캐시를 걸면 Spring AOP self-invocation으로 조용히 무시되는 것을 발견 — 기존 카탈로그 3종과 같은 자리(레포지토리 메서드)로 옮겨 피함. 캐시 키가 파라미터 없는 고정값(`'all'`)이라 `AdminStatsServiceTest`의 `@Transactional` 롤백(DB만 되돌림)과 Spring 컨텍스트 재사용(CacheManager 싱글턴 유지)이 만나 테스트 간 캐시가 새는 문제가 실측으로 드러남 — 테스트 `@BeforeEach`에서 `adminDashboardStats` 캐시를 직접 clear하는 것으로 해결(운영 코드의 TTL-only 설계는 그대로 유지, 테스트만 별도 조치).
