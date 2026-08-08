# 백엔드 프로덕션 시그널 체크리스트 — 8개 기법 적용 가능성

작성일: 2026-07-05
상태: **분석/추천 (결정 전)** — 착수 여부·우선순위는 사용자 confirm 후 박제. 아래 어떤 항목도 "채택 확정" 아님.
대상 진로: 백엔드(Spring) 신입.
연관: [`db-portfolio-roadmap.md`](./db-portfolio-roadmap.md), [`load-test-strategy.md`](./load-test-strategy.md), [`pose-ingest-downsampling.md`](./pose-ingest-downsampling.md), [`redis-introduction.md`](./redis-introduction.md), [`reference-style-and-caching.md`](./reference-style-and-caching.md), [`realmysql-experiments.md`](../portfolio/realmysql-experiments.md), [[project_loadtest_env_constraint]]

---

## 0. 목적

사용자가 나열한 백엔드 성능/운영 시그널 8개(EXPLAIN 쿼리개선, 로컬캐시, 비동기/동기 외부호출, tcpdump, connection 설정, k6, thread/netty, JVM GC)가 **이미 이 프로젝트에 있는지 / 적용할 수 있는지**를 코드 근거로 점검한다. 새 결정 문서이며, 이미 다른 문서가 다룬 항목은 중복 작성하지 않고 링크로 연결한다.

---

## 1. 한눈에 보는 현재 상태

| # | 항목 | 현재 상태 | 적용 가능성 | 우선순위(추천) |
|---|---|---|---|---|
| 1 | MySQL EXPLAIN 쿼리개선 | ✅ **이미 광범위 적용** | — (충분) | 없음 |
| 2 | 로컬 캐시 | ✅ **구현+실측 완료**(FeedbackTemplateService, exercises, exercise_references) | — (완료) | 없음(§2-2-2·§2-2-3·§2-2-4 참고) |
| 3 | 비동기/동기 외부호출 | ✅ **Resilience4j 구현+실측 완료** | — (완료) | 없음(§2-3-3 참고) |
| 4 | tcpdump 패킷분석 | ❌ 미구현 | 🟡 제한적 | 낮음(선택) |
| 5 | connection 설정(HikariCP) | ✅ **실측 완료 + 명시 완료** | — (완료) | 없음(§2-5 참고) |
| 6 | k6 부하테스트 | 🔶 검토 후 미채택 | 🟡 제한적 | 낮음 |
| 7 | thread 수치 + netty(WebFlux) | 🔶 의존성만 존재, 미사용 | 🔴 불필요(역효과) | 낮음(정리만) |
| 8 | JVM GC/ratio 튜닝 | ❌ 미설정 | 🟡 제한적(환경 제약) | 낮음(프레이밍 전환 추천) |

---

## 2. 항목별 상세

### 2-1. MySQL EXPLAIN을 통한 쿼리 개선 — 이미 충분

`realmysql-experiments.md`에 이미 다수 실측:
- 인덱스 검증: 리포트 쿼리 `EXPLAIN ANALYZE` → filesort 0 확인 후 "인덱스 추가하면 더 빨라진다"는 애초 가설을 **폐기**(`load-test-strategy.md` 정정1) — EXPLAIN으로 이미 최적임을 먼저 확인하고 방향을 틀었다는 점 자체가 좋은 시그널.
- 페이지네이션: offset vs keyset을 `EXPLAIN`의 rows/cost로 비교(offset O(N), keyset 평탄).
- 파티션 pruning: `EXPLAIN`의 `partitions` 컬럼으로 날짜범위 쿼리는 pruning 되고 `session_id=` 쿼리는 안 된다는 걸 직접 확인(정직한 반증까지 포함).

**판단**: 추가로 더 할 필요 없음. 이미 "EXPLAIN으로 가설을 검증/반증한 사례"가 여러 건 있어 카드로 쓰기 충분.

### 2-2. 로컬 캐시를 이용한 성능개선 — 구현+실측 완료 (2026-07-11)

**현재 상태**: `FeedbackTemplateService.getTemplatesByExercise`에 Caffeine + Spring `@Cacheable`(cache-aside) 적용 완료. 아래는 착수 전 분석(§2-2 원문 유지, 히스토리 보존) → §2-2-2에 실측 결과. 후속으로 `exercises`(임계값) 캐싱도 같은 날 구현+실측 완료 — §2-2-3.

~~**과거 상태**: `redis-introduction.md:148` 실측 — `@Cacheable` 사용처 0건, Caffeine 의존성 0건, Redis 의존성 0건.~~ `reference-style-and-caching.md §6`에서 이미 "카탈로그 캐싱은 로컬 Caffeine으로 충분, Redis는 수평확장 시" 추천까지 나와 있었고, 이번에 그대로 구현함.

**적용 후보** (오늘 코드로 확인한 구체 지점):
- `FeedbackTemplateService.getTemplatesByExercise(exerciseId, persona)` — `exerciseId + persona` 조합별 조회, 쓰기가 거의 없는(관리자만 템플릿 수정) 데이터. 캐시 적중률 실측하기 좋은 작은 스코프.
- 선택형 스타일 기준(`ExerciseReference`) 카탈로그 — `reference-style-and-caching.md`에서 이미 설계된 대상, 아직 기능 자체가 미구현이라 캐싱도 그 뒤 순서.

**과설계 경계**: Redis 붙이지 말 것(이미 합의됨, [[redis-introduction]]). 무효화 전략도 복잡하게 가지 말고 TTL 또는 쓰기 시 evict 정도로 충분 — 데이터가 거의 안 바뀜.

**구현 방식**: 라이브러리는 Caffeine(현 표준, Guava Cache 후계·W-TinyLFU). 연동은 명령형 `Cache.get()` 직접 호출보다 **Spring `@Cacheable`/`@CacheEvict` 선언형**이 코드 변경 최소 — 서비스 메서드 본문 안 건드리고 어노테이션만 추가.

**캐싱 패턴 선택 — cache-aside**:

| 패턴 | 장점 | 단점 | 이 케이스에 맞나 |
|---|---|---|---|
| **Cache-aside** | 캐시 장애 시 DB로 자연 폴백, 요청된 것만 lazy 적재, `@Cacheable` 기본 지원 | 첫 조회 미스 페널티, 쓰기 시 무효화를 앱이 직접 챙겨야 함 | ✅ 맞음 — 쓰기(관리자 수정)가 극히 드물어 무효화 부담·정합성 리스크가 사실상 없음 |
| Write-through | 쓰기 직후 캐시 항상 최신 | 모든 쓰기가 캐시+DB 동기 완료돼야 함(지연↑), 안 읽힐 데이터도 채워짐(lazy 아님) | ❌ 과함 — 쓰기 지연 줄일 이유가 없는 워크로드(관리자 저빈도 수정)에 쓰기 경로만 복잡해짐 |
| Write-behind | 쓰기 처리량 최대화, 배치 병합 | 캐시-DB 반영 전 장애 시 데이터 유실 위험, 구현·복구 난이도 최고 | ❌ 불필요 — 쓰기 폭주 상황 자체가 없음(관리자 수동 수정), 유실 리스크 감수할 이유 없음 |

→ **주의**: 이 "쓰기"는 [`db-portfolio-roadmap.md §2`](./db-portfolio-roadmap.md)의 **운동 중 쓰기 축**(`pose_data` 고빈도 배치 INSERT)과 무관한 별개 대상 — `exercise_feedback_templates`에 대한 **관리자의 저빈도 수정**을 가리킴. 규모·주체가 다르므로 혼동 금지.

**추천**: FeedbackTemplateService에 Caffeine + `@Cacheable`(cache-aside)로 붙여서 "히트율 실측 + p50 before/after" 카드로 만들면 작고 정직한 캐싱 스토리가 됨.

#### 2-2-1. 테이블별 쓰기 경로 인벤토리

"카탈로그처럼 보여도 실제 쓰기 경로가 있는지는 테이블마다 다르다"(피드백 템플릿 vs 임계값 사례)를 스키마 10개 테이블 전체로 확인한 결과. **코드에서 실제 `save`/`saveAll`/dirty-checking/`@Modifying` 호출을 찾은 것만 "쓰기 있음"으로 표시**했고, 못 찾은 건 "쓰기 경로 없음(확인 시점 기준)"으로 정직하게 남긴다.

| 테이블 | 쓰기 경로 | 트리거 주체 | 빈도 | 즉시반영 요구 명시 | 캐싱 적합성 |
|---|---|---|---|---|---|
| `users` | ✅ 있음 — `MemberService.signup/deleteAccount`, `OnboardingService.updateOnboarding`(dirty-checking), `PreferenceService.updateTtsPreferences`(dirty-checking) | 사용자 본인 | 저빈도(가입·온보딩·설정변경 시) | 없음 | 🟡 애매 — row가 사용자별로 달라 카탈로그성 캐싱 대상 아님(엔티티 캐시는 별개 논의) |
| `exercises` | ✅ 있음 — `AdminExerciseService.updateThresholds`(dirty-checking) | 관리자 | 저빈도 | **명시**("즉시 갱신·신규 세션부터 적용") | 🟢 안전 — cache-aside + `@CacheEvict` 필수(즉시반영 요구 때문에 무효화 누락 시 버그) |
| `exercise_references` | ✅ 있음 — `PoseDataService.saveReferencePoses`(`saveAll`), AI(`extractReferenceData` gRPC 콜백)가 실행 | 관리자 트리거 + AI 콜백 | 매우 저빈도(신규 운동 등록 시만) | 없음 | 🟢 매우 안전 — 무효화가 사실상 거의 안 일어남 |
| `exercise_sessions` | ✅ 있음(다중 경로) — `SessionService.createSession/applyComplete/endSession/markAsFailedIfStillInProgress`, `ExerciseAnalysisService.applyCompleteFromApp` | 사용자 + AI 콜백(gRPC) + 스케줄러(`SessionTimeoutScheduler`) | 세션당 여러 번(생성·종료·타임아웃 경쟁) | 낙관적 락(`@Version`)으로 경쟁 자체를 설계에 반영 | 🔴 부적합 — 진행 상태가 여러 액터에서 계속 갱신되고 조회 시 최신성이 중요 |
| `pose_data` | ✅ 있음 — `PoseDataService.savePoseDataBatch`(`JdbcTemplate.batchUpdate`) | AI(gRPC) | **고빈도**(세션당 3~4초 간격 배치) — [`db-portfolio-roadmap.md §2`](./db-portfolio-roadmap.md) 운동 중 쓰기 축 그 자체 | 없음 | 🔴 대상 아님 — 캐싱이 아니라 적재 축 |
| `daily_logs` | ✅ 있음 — `DailyLogService.saveOrUpdateLog`(신규는 `save`, 기존은 dirty-checking) | 사용자 본인 | 저빈도(일지 작성/수정 시) | 없음 | 🟡 애매 — 개인화 keyed 데이터라 카탈로그성 캐싱 이득 낮음 |
| `reports` | ❌ **쓰기 경로 없음(원인 확인됨)** — `ReportService`는 `findBySessionId` 조회만, Java 코드 어디서도 `reportRepository.save`류 호출 없음. `mysql/data.sql` 시드로만 존재. **원인**: [`22-backend-tasks-detail.md`](../tasks/22-backend-tasks-detail.md) BE-03("GPT/Claude 리포트 자동 생성")이 `Report` 생성 책임을 지는 별도 작업인데 **아직 미착수** — `GptFeedbackService` 자체가 없고, 세션 종료 콜백에서 `ReportService.generateReportForSession()`을 호출하는 연결부도 없음. 버그·누락이 아니라 애초에 별도 작업으로 분리 설계된 게 착수 전인 상태 | — | — | — | ⚠️ 캐싱 이전에 BE-03 착수가 선결 — 지금은 생성 로직 자체가 없어 캐싱 논의 대상이 아님 |
| `exercise_feedback_templates` | ❌ **쓰기 경로 없음(확인 시점 기준)** — `FeedbackTemplateController`는 `GET`만, 수정 API 없음. `data.sql` 시드로만 존재 | — | 없음 | 없음 | 🟢 가장 안전 — 무효화 로직 자체가 불필요(§2-2 캐싱 1순위 후보인 이유) |
| `session_feedback_logs` | ✅ 있음 — `FeedbackLogService.saveBatch`(`JdbcTemplate.batchUpdate`, `INSERT IGNORE` 멱등) | AI(gRPC `ReportFeedbackBatch`) | 세션 진행 중 배치 단위(중빈도) | 없음(멱등성만 요구) | 🔴 부적합 — 세션 진행 중 지속 적재, 조회 시 최신 필요 |
| `body_records` | ❌ **완전 미구현** — `@Entity`·Repository·Service 어디에도 없음, `schema.sql`에 테이블만 정의 | — | — | — | 해당 없음 — 기능 자체가 아직 없음 |

> **2026-07-24 발견·수정**: `body_records.member_id` FK에 `ON DELETE CASCADE`가 없었음(RESTRICT 기본값). `MemberService.deleteAccount`는 `memberRepository.delete(member)` 하나로 회원 삭제 처리하고 나머지 테이블 정리를 전부 FK CASCADE에 의존하는 구조라, 이 테이블에 row가 하나라도 생기면 탈퇴가 FK violation으로 500 나는 구조 — `refresh_token`에서 실제로 터졌던 것과 동일 클래스 버그(`8efbca1`). 지금은 쓰기 경로 자체가 없어 dormant였을 뿐. `ON DELETE CASCADE` 선반영 완료(`mysql/schema.sql`).

**정리**: 10개 중 실제 쓰기가 있는 건 7개, 없는 건 3개(`reports`, `exercise_feedback_templates`, `body_records`). 캐싱 후보로 진짜 안전한 건 **쓰기가 없거나(feedback_templates) 극히 드문(exercise_references) 테이블** 뿐이고, `exercises`는 쓰기는 있지만 저빈도+즉시반영 요구가 명시돼 있어 evict만 잘 챙기면 가능. 나머지(세션·포즈데이터·피드백로그)는 쓰기 축 자체라 로컬 캐시 논의 대상이 아님. `reports`는 쓰기 없음의 원인이 BE-03 미착수로 확인됨 — 3개(feedback_templates/body_records/reports) 중 유일하게 "의도적 미구현"이 아니라 "예정된 작업 대기 중"인 경우.

#### 2-2-2. 구현 + 실측 결과 (2026-07-11)

**결정된 스펙** (사용자 confirm):
- 대상: `FeedbackTemplateService.getTemplatesByExercise` 단 하나 (`exercises`/`exercise_references`는 후속, 이번 스코프 아님 — "하나씩 붙이고 측정" 방식으로 진행, 여러 개 동시 적용은 효과 귀속이 안 돼서 기각)
- 라이브러리/연동: Caffeine + Spring `@Cacheable`(cache-aside), evict 없음(쓰기 경로 자체가 없어서)
- TTL: `expireAfterWrite=1h` — 히트율 손해 목적 아닌 안전판(재시작 시 리셋, staleness 심각도 낮음, 짧을수록 미래의 evict 누락 버그가 빨리 자연치유)
- 캐시 매니저 구성: Boot 자동설정(`spring.cache.type: caffeine`, `application.yml`) — 캐시 1개뿐이라 별도 `CacheConfig` 클래스 불필요
- 캐시 키: SpEL 문자열 조합(`#exerciseId + '_' + #persona`)
- 관측성: `spring-boot-starter-actuator` 추가, `/actuator/caches` + `/actuator/metrics/cache.gets`로 히트/미스 표준 Micrometer 카운터 노출 (`recordStats` + `spring.cache.cache-names`로 캐시를 기동 시점에 미리 등록해야 지연생성 캐시도 메트릭에 잡힘 — 안 하면 `cache.gets` 자체가 안 나타남, 실측 중 발견)

**변경 파일** (5개): `build.gradle`(caffeine·spring-boot-starter-cache·spring-boot-starter-actuator 추가), `ShadowfitApplication.java`(`@EnableCaching`), `application.yml`(캐시 스펙·actuator 노출·`/actuator/**` 화이트리스트), `application.properties`(화이트리스트 동기화), `FeedbackTemplateService.java`(`@Cacheable` 1줄).

**막혔던 지점**: `spring.cache.type=caffeine` + Caffeine 의존성만 넣고 띄웠더니 `entityManagerFactory depends on missing bean 'cacheManager'`로 기동 실패. 원인은 `org.springframework.cache.caffeine.CaffeineCacheManager` 클래스가 `spring-context-support` 모듈에 있는데 이게 클래스패스에 없었던 것 — Caffeine 라이브러리 자체와 Spring의 Caffeine 연동 클래스는 별도 모듈. `spring-boot-starter-cache` 추가로 해결. (`--debug` 조건평가 리포트로 `CaffeineCacheConfiguration: Did not match — @ConditionalOnClass did not find required class 'org.springframework.cache.caffeine.CaffeineCacheManager'`를 보고 확진.)

**실측 방법**: Docker로 실제 기동(mysql+backend+ai 컨테이너), `/member/signup`+`/login`으로 테스트 유저 발급 → JWT로 `GET /exercises/{id}/feedback-templates`를 스쿼트·런지·플랭크(exercise_id 1/2/3) 각각 콜드 1회 + 웜 20회 호출, `curl -w "%{time_total}"`로 개별 요청 지연시간 수집 + `/actuator/metrics/cache.gets`로 히트/미스 카운트 대조 검증.

**결과**:

| 지표 | 값 | 근거 |
|---|---|---|
| 히트율 | 95.2% (60 hit / 3 miss) | `/actuator/metrics/cache.gets` 실측 카운터 (curl 타이밍 추정이 아닌 애플리케이션 카운터) |
| HIT 평균 지연 | 23.6ms (n=60) | p50 20.7ms · p95 48.4ms · p99 71.6ms |
| MISS 평균 지연 | 43.0ms (n=2, 런지·플랭크만) | 스쿼트 첫 호출(140.7ms)은 백엔드 재시작 직후라 JVM/커넥션풀 워밍업 비용이 섞여 "순수 DB 조회 비용" 비교에서 제외 |
| 속도 개선 | 평균 기준 ~1.8배 | 절대수치 아닌 상대 delta로만 사용 |

**정직하게 짚을 점**:
- MISS 표본이 n=2로 극히 작음 — percentile(p95/p99) 자체를 낼 수 없는 규모, 평균만 표기.
- HIT의 p99(71.6ms)가 MISS 평균(43ms)보다 높게 나온 구간이 있음 — 캐시가 느려진 게 아니라 [[project_loadtest_env_constraint]](i3-6100 2코어, mysql+backend+curl 동거)의 코어 경쟁·GC·curl 자체 오버헤드로 보이는 꼬리 지연. n=60도 꼬리 추정엔 적은 표본.
- 로컬 개발 박스의 절대치이자 상대 delta일 뿐, 프로덕션 하드웨어 성능 주장에 쓰면 안 됨.
- 런지·플랭크는 AI(FastAPI) 쪽 실제 자세분석 로직이 아직 미구현([[project_squat_first]])이지만, 이 캐싱 대상(`exercise_feedback_templates` 카탈로그 조회)은 AI 분석 완성 여부와 완전히 무관한 별개 경로 — `data.sql`에 런지·플랭크 템플릿도 이미 시드돼 있어(NULL fallback) 캐싱 테스트 자체엔 문제 없음.
- 결과 시각화: 63건 실측 원본(요청 순서·운동·MISS/HIT 구분·지연시간) 아티팩트로 별도 정리(대화 내 링크, 문서에는 표만 위 요약 인용).

**남은 것(당시 기준)**: 테스트용 유저(`cachebench@test.com`) 정리 여부, 커밋 여부, ~~`exercises`(임계값) 캐싱 후속 착수 여부~~ — 전부 미결정, confirm 대기였음. `exercises`는 착수 확정 후 구현+실측 완료(2026-07-11) — §2-2-3.

#### 2-2-3. `exercises`(임계값) 캐싱 후속 — 구현 + 실측 결과 (2026-07-11)

**feedback_templates와의 구조적 차이**: `exercises`는 쓰기 경로(`AdminExerciseService.updateThresholds`, dirty-checking)가 있어 §2-2-2와 같은 "캐시만 붙이고 evict 없음" 패턴을 그대로 못 씀.

**구현 중 발견한 함정**: `ExercisesRepository.findById`에 그냥 `@Cacheable`을 붙였다면, 캐시 히트 시 반환되는 엔티티는 현재 트랜잭션의 영속성 컨텍스트에 없는 **detached 인스턴스**가 됨. `updateThresholds`가 이 인스턴스에 setter만 호출하고(명시적 `save()` 없음, dirty-checking에 의존) 트랜잭션을 커밋해도, Hibernate가 관리하지 않는 객체라 변경 감지가 안 걸려 **UPDATE SQL 자체가 안 나가는 채로 조용히 무시됨** — 예외도 없고 응답도 200이라 가장 늦게 발견되는 종류의 버그. 그래서 읽기와 쓰기 경로를 분리:
- `ExercisesRepository.findByIdCached`(신규, `@Cacheable(cacheNames="exercises", key="#id")` + 커스텀 `@Query`) — `SessionService.createSession`, `PoseDataService.saveReferencePoses`, `ExerciseAnalysisService.extractReferencePoses` 등 읽기 전용 3곳만 사용.
- `AdminExerciseService.updateThresholds`는 기존 `findById`(캐시 미적용, 관리 상태 보장) 그대로 두고 `@CacheEvict(cacheNames="exercises", key="#exerciseId")`만 추가.

**착수 전 확인한 사실** (착수 여부 판단 근거):
- 읽기 빈도: 세션 생성 시 1회뿐, 세션 진행 중 반복 조회 없음 — 카탈로그가 3 row(스쿼트/런지/플랭크)라 캐싱 효과 자체는 미미.
- **AI 서버 미연동 확인**: gRPC `AnalyzeRequest`(`exercise.proto`)에 threshold 필드가 없고, AI 서버(`ai-server/app/utils/constants.py`)는 `SYNC_THRESHOLDS`를 자체 하드코딩해서 씀. Spring 쪽도 `syncThresholdBeginner/Advanced`를 읽어 분석 로직에 쓰는 곳이 엔티티·관리자 DTO 외엔 없음 — 즉 관리자가 이 값을 바꿔도 실제 동기화율 판정엔 반영되는 경로가 없는 상태(저장만 됨). 캐싱과는 무관한 기존 갭이지만, "임계값 캐싱" 서사의 약점으로 정직하게 남김.
- "즉시 갱신·신규 세션부터 적용" 요구는 `AdminExerciseController` Swagger 설명에 명시 — 읽기 지점이 세션 생성 1회뿐이라 evict가 `updateThresholds` 트랜잭션 커밋과 함께 걸리면 바로 다음 세션 생성부터 새 값이 보장됨.

**변경 파일** (6개): `ExercisesRepository.java`(`findByIdCached` 추가), `AdminExerciseService.java`(`@CacheEvict`), `SessionService.java`/`PoseDataService.java`/`ExerciseAnalysisService.java`(읽기 호출을 `findByIdCached`로 교체), `application.yml`(`cache-names`에 `exercises` 추가, spec은 feedbackTemplates와 공유).

**실측 방법**: Docker 재기동 후 테스트 유저로 세션 생성 2회(`POST /exercises/sessions`, exerciseId=1) → `/actuator/metrics/cache.gets`로 MISS/HIT 확인 → 관리자 권한으로 `PATCH /admin/exercises/1/thresholds`(60/85 → 65/88) → 응답으로 쓰기 정상 확인 → 세션 재생성으로 evict 후 재적재(MISS로 복귀) 확인.

**결과**:
| 단계 | cache.gets 결과 | 의미 |
|---|---|---|
| 세션 생성 #1 | MISS | 최초 조회, DB에서 로드 후 캐시 적재 |
| 세션 생성 #2 | HIT | 캐시 히트 확인 |
| `updateThresholds`(65/88) | — | 응답에 새 값 정상 반영 — detached 엔티티 함정 없이 dirty-checking 정상 작동 |
| 세션 생성 #3(evict 후) | **MISS** | evict가 걸려 다음 조회가 다시 DB를 타고 프레시 값으로 재적재됨 — 정합성 증명 |

앞서 짚은 "detached 엔티티라 저장이 조용히 씹힐 수 있다"는 위험이 이 구현에서는 발생하지 않고, 읽기/쓰기 분리 + evict가 설계대로 동작한다는 걸 카운터 숫자로 확인.

**정리**: 테스트 유저는 실제 `DELETE /member/{email}` API로 삭제해 직전 커밋(`72e8924`, 회원탈퇴 500 에러 픽스)의 재검증까지 겸함(204, `users`/`refresh_token`/세션 전부 cascade 삭제 확인). 테스트 중 바꾼 `exercises.id=1` 임계값은 DB 기본값(60.00/85.00)으로 원복. 커밋 `8a2824f`.

#### 2-2-4. `exercise_references` 캐싱 — 구현 + 실측 결과 (2026-07-11)

**착수 전 조사**: §2-2-1에서 "🟢 매우 안전 — 무효화가 사실상 거의 안 일어남"으로 표시돼 있던 유일한 남은 후보. 처음엔 "프레임 수만큼 row가 많아 클 수 있다"고 우려했으나, 코드 확인 결과 AI 쪽 `build_reference_sequence(target_length=30)`가 리샘플링해서 **운동(exercise_id)당 정확히 30 row로 고정**, row당 payload도 각도 4개짜리 작은 JSON — `pose_data`(세션 중 실시간 랜드마크 전체)와는 무관한 작은 카탈로그임을 확인 후 착수.

**exercises와의 구조적 차이**: `exercises`는 조회(`findById`)가 쓰기 경로(`updateThresholds`)에서도 같이 쓰여서 읽기/쓰기 분리가 필요했지만, `exercise_references`는 조회(`findByExerciseId`)가 쓰기 경로(`saveReferencePoses`, `saveAll`만 호출)에서 전혀 재사용되지 않음 — detached 엔티티 함정 자체가 성립하지 않아 `@Cacheable`을 리포지토리 메서드에 바로 붙여도 안전.

**변경 파일** (3개): `ExerciseReferenceRepository.java`(`findByExerciseId`에 `@Cacheable("exerciseReferences")`), `PoseDataService.java`(`saveReferencePoses`에 `@CacheEvict`), `application.yml`(`cache-names`에 `exerciseReferences` 추가).

**실측 중 발견한 제약**: AI 서버의 실제 유튜브 좌표 추출(`ExtractReferenceData` RPC, Spring→AI 방향)이 로그상 "미구현" 스텁이라, `POST /exercises/{id}/reference`로 정상 파이프라인을 태워도 AI가 응답만 하고 실제로 좌표를 만들어 Spring에 콜백하지 않음([[project_squat_first]]·[[project_reference_style_feature]]와 일치하는 기존 갭). 우회책으로 `ai-server` 컨테이너에 이미 컴파일돼 있는 proto stub(`exercise_pb2_grpc.py`)을 이용해 Spring의 gRPC 콜백 엔드포인트(`ExerciseGrpcService.extractReferenceData`, AI→Spring 방향, 포트 6565)를 Python 스크립트로 직접 호출 — `InternalAuthInterceptor`가 요구하는 `Authorization: Bearer {INTERNAL_API_TOKEN}` 메타데이터만 맞추면 되는 걸 확인하고 실측에 사용.

**결과**:
| 단계 | cache.gets 결과 | 의미 |
|---|---|---|
| 좌표 등록(5건, 직접 gRPC 호출) | — | `exercise_references`에 5 row 저장 확인 |
| 세션 생성 #1 | MISS | 최초 조회, DB에서 로드 후 캐시 적재 |
| 세션 생성 #2 | HIT | 캐시 히트 확인 |
| 좌표 재등록(3건, evict) | — | 8 row(5+3)로 누적 확인 — evict가 데이터 자체를 건드리지 않음을 방증 |
| 세션 생성 #3(evict 후) | **MISS** | 다시 DB를 타고 프레시 값으로 재적재 — 정합성 증명 |

**정리**: 테스트 유저(`refcache@test.com`)는 실제 `DELETE` API로 삭제(회원탈퇴 버그 재검증 겸), 테스트로 생성한 `exercise_references` 8 row도 삭제(원래 로컬 DB엔 시드 자체가 없어 0건이 정상 상태). 커밋 `5754fc3`.

### 2-3. 비동기/동기를 이용한 외부호출 성능개선 — Resilience4j 구현+실측 완료 (2026-07-11)

**현재 상태**: `ExerciseAnalysisService`에 이미 비동기 패턴 존재 —
- `sendAnalysisRequestToFastApi`에 `@Async` + gRPC `StreamObserver`(비동기 stub) 사용, `startAnalysis`는 세션 생성 후 즉시 ID 반환하고 FastAPI 전송은 뒤로 미룸("응답 속도 최적화" 주석 명시).
- `extractReferencePoses`, `stopAnalysis`도 동일하게 `StreamObserver` 콜백 방식(비블로킹).
- 단, `WebClientConfig`가 만든 `fastapiWebclient` 빈은 `ExerciseAnalysisService:38`에 필드로 주입만 되고 **실제 호출 코드에서 쓰이는 곳이 0건** — 죽은 의존성. 실제 Spring↔FastAPI 통신은 전부 gRPC(가 됨).

~~**남은 갭**: `load-test-strategy.md §9.2`에 이미 OPEN으로 잡혀 있던 **Resilience4j Circuit Breaker 미도입**. 동기 콜백 구조(`completeSession`)라 AI가 느려지거나 죽으면 백엔드가 그대로 영향받을 수 있음 — 서킷브레이커/타임아웃/재시도로 "AI 장애로부터 백엔드 보호"(같은 문서 §9-2)가 아직 코드로 안 옮겨짐.~~ **✅ 완료(2026-07-11)** — §2-3-3 참고.

#### 2-3-3. Resilience4j 서킷브레이커 — 구현 + 실측 결과 (2026-07-11)

**대상**: `ExerciseAnalysisService`의 Spring→AI outbound gRPC 호출 3개(`extractReferenceData`, `startAnalysis`, `stopAnalysis`) — 전부 같은 AI 서버로 가므로 `"aiServer"` 서킷브레이커 하나를 공유.

**어노테이션(`@CircuitBreaker`) 대신 수동 연동한 이유**: 이 3개 호출은 전부 `@Async` + `StreamObserver` 콜백 구조라, 메서드 자체는 gRPC 요청을 던지자마자 반환되고 실제 성공/실패는 **별도 스레드의 콜백**(`onNext`/`onError`)에서 나중에 결정됨. 어노테이션 기반 AOP는 "메서드 호출이 예외를 던졌는가"만 감지하므로 이 구조에서는 실제 결과를 못 잡음 — `CircuitBreakerRegistry`를 주입해 호출 전 `tryAcquirePermission()`으로 게이트하고, `onNext`/`onError` 안에서 `cb.onSuccess()`/`cb.onError()`를 직접 기록.

**설정**(`application.yml`): COUNT_BASED 슬라이딩 윈도우 10, 최소 호출 5, 실패율 임계 50%, OPEN 유지 10s, HALF_OPEN 시험 호출 3회, 자동 HALF_OPEN 전환. actuator에 `circuitbreakers`/`circuitbreakerevents` 노출 + health indicator 등록(`/actuator/health`에도 `aiServer` 컴포넌트로 나타남).

**Docker 실측 — 전체 생명주기**:
| 단계 | 조작 | 결과 |
|---|---|---|
| 정상 | 세션 생성(AI 정상) | `state: CLOSED`, `bufferedCalls: 1`, `failedCalls: 0` |
| 장애 유발 | `docker stop shadowfit-ai` 후 세션 생성 7회 연속 | `failureRate: 80%`(>임계 50%) → `state: OPEN`, `notPermittedCalls: 3`(이후 요청은 gRPC 시도 자체 없이 스킵, 로그에 "AI 서버 서킷브레이커 OPEN — ... 스킵" 확인) |
| 복구 | `docker start shadowfit-ai`, 10초 대기 후 조회 | `state: HALF_OPEN`(자동 전환, 시험 호출 3회 허용) |
| 복구 확인 | HALF_OPEN 상태에서 세션 생성 3회 | 전부 성공 → `state: CLOSED` 복귀 |

**정직하게 짚을 점**: 이 세 호출은 이미 비동기(non-blocking)라 서킷브레이커가 "스레드 고갈 방지"를 해주는 건 아님 — 여기서의 실질 이득은 (1) AI가 죽은 걸 아는데도 매 요청마다 gRPC 커넥션 시도를 반복하지 않는 fail-fast, (2) `/actuator/health`·`/actuator/circuitbreakers`로 AI 장애를 운영 관점에서 즉시 관측 가능해진 것. ~~세션 자체의 후속 처리(예: OPEN 상태에서 스킵된 세션을 FAILED로 전환할지)는 이번 스코프에 포함하지 않음 — 기존 `SessionTimeoutScheduler`가 결국 타임아웃으로 정리하므로 별도 상태 전이 로직은 추가하지 않았고, 필요하면 별개 논의.~~ **✅ 해결(2026-07-11) — §2-3-4 참고.**

~~**최초 구현의 갭(사용자 지적으로 발견)**: 위 실측은 `docker stop`(AI가 즉시 연결을 끊어 `UNAVAILABLE` 에러)만 검증한 것이었고, gRPC 호출에 데드라인이 없어서 **AI가 죽지 않고 그냥 응답만 안 주는(hang) 상황은 못 잡는** 상태였음 — `onNext`/`onError` 둘 다 안 불려서 서킷브레이커가 그 호출을 영원히 실패/느림으로 기록 못 함(`slowCallRateThreshold: 100%`도 사실상 무력화 상태).~~

**✅ 해결(2026-07-11, 같은 날 후속)**: `getAuthenticatedStub()`에 `withDeadlineAfter(5s)` 추가 — 데드라인 초과 시 gRPC가 자동으로 `onError(DEADLINE_EXCEEDED)`를 호출해 기존 기록 로직을 그대로 재사용. Docker에서 `docker pause shadowfit-ai`(프로세스만 정지, TCP는 유지 — `docker stop`과 달리 진짜 hang 재현)로 검증: 호출 1회 hang → 5초 후 `DEADLINE_EXCEEDED`로 실패 기록 → hang 5회 누적 시 `failureRate 83%`로 `OPEN` 트립 → `docker unpause` 후 자동 `HALF_OPEN`→`CLOSED` 복귀까지 확인. 커밋 `0c47598`.

**정리**: 테스트 유저(`cbtest@test.com`)는 실제 `DELETE` API로 삭제. 커밋 `215d49a`.

#### 2-3-4. 남은 갭 3개 마무리 — 세션 후속처리 / 반대방향 / 설정값 근거 (2026-07-11)

사용자가 §2-3-3 완료 직후 "이거 스프링만 하면 되는가, 쉽게 끝날 일인가"로 재검토를 요청해서 짚었던 갭 3개를 정리한다.

**1) 스킵/실패한 세션의 후속 처리 — 해결**: `sendAnalysisRequestToFastApi`에서 (a) 서킷브레이커 `tryAcquirePermission()` 실패로 스킵되는 경우, (b) `onError`로 실제 gRPC 호출이 실패하는 경우(데드라인 초과 포함) 둘 다에서 기존 `SessionService.markAsFailedIfStillInProgress(sessionId, now)`를 즉시 호출하도록 추가. 이전엔 `SessionTimeoutScheduler`의 버퍼(기본 30분+)가 돌 때까지 세션이 `IN_PROGRESS`로 방치됐음. Docker 실측(`docker pause`로 재현): OPEN 스킵 경로로 처리된 세션 6건은 `start_time == end_time`(같은 초, gap 0초)로 즉시 `FAILED`, 데드라인을 실제로 탄 세션들은 5초(데드라인 값) 후 `FAILED` — 두 경로 모두 DB로 확인. 커밋 `50bcf82`.

**2) AI→Spring 반대 방향 — 의도적으로 스코프 밖 (해결 아님, 결정)**: AI가 Spring에 걸어오는 콜백 3개(`savePoseDataBatch`, `completeAnalysis`, `reportFeedbackBatch`)는 AI가 caller, Spring이 서버라 이번에 붙인 것과 반대 방향 — Spring이 느려지거나 죽었을 때 AI 쪽을 보호하려면 AI(Python) 코드에 서킷브레이커를 붙여야 함. [[feedback_minimize_python_changes]]·[[project_keep_server_ai_architecture]] 방침(AI 서버는 손대지 않음)에 따라 이번 스코프에 포함하지 않음 — 회피가 아니라 명시적 결정으로 남긴다.

**3) 서킷브레이커 설정값의 실측 근거 — 없음(정직하게 명시)**: `slidingWindowSize=10`·`minimumNumberOfCalls=5`·`failureRateThreshold=50%`·`waitDurationInOpenState=10s`·`permittedNumberOfCallsInHalfOpenState=3`은 Resilience4j 공식 예제·업계 통상값에 가까운 **보수적 기본값**이지 이 프로젝트의 실제 트래픽 패턴으로 튜닝한 값이 아님. [[project_loadtest_env_constraint]](i3-6100 2코어에 mysql+backend+ai+부하도구 동거) 때문에 "이 설정이 최적"이라고 실측으로 주장하는 것 자체가 이 환경에서는 의미가 약함 — 굳이 튜닝하려 해도 박스 노이즈에 묻힐 가능성이 높다는 게 이미 `pose-ingest-downsampling.md §5-1(5)`(HikariCP pool 10→30 실측)에서 반복 확인된 패턴과 같음. 이 설정값들은 "동작 메커니즘이 맞다"는 것만 실측으로 증명했고(§2-3-3·§2-3-4-1), "이 숫자가 최적"이라는 주장은 하지 않는다.

#### 2-3-1. 쓰기(Create) 경로 — MediaPipe vs gRPC 비율, GPU 가정 시 역전

**질문**: `startAnalysis`(세션 생성)가 이미 FastAPI 호출을 `@Async`로 분리해놨는데, 그럼 쓰기 한 건을 만들어내는 데 드는 시간은 MediaPipe(추론)와 gRPC(전송) 중 뭐가 더 큰가.

**CPU 기준 (현재, `latency-perception.md` §3·§10.1, `ai-load-budget.md §8` 추정치)**:

| 구간 | 시간 |
|---|---|
| MediaPipe landmark 추출 (프레임당) | ~20~50ms |
| gRPC `ReportFeedbackBatch` RTT (배치당 1회) | 10~50ms |

배치(R=25프레임, `load-test-strategy.md §7.9` 기준) 단위로 누적하면 MediaPipe ≈ 25×20ms=500ms(변동비, 프레임마다 선형 누적) vs gRPC 10~50ms(고정비, 배치당 1회) — **10~50배 차이로 MediaPipe가 압도**. gRPC는 병목이 아니라 노이즈 수준.

**GPU 가정 시 (AWS GPU 인스턴스, `ai-load-budget.md:45` 기존 추정치 "GPU면 ~5ms")**:

```
CPU: MediaPipe(20~50ms)  >>>  gRPC(10~50ms)   ← MediaPipe 압도
GPU: MediaPipe(~5ms)     ≈    gRPC(10~50ms)   ← 비율 역전, gRPC가 같거나 더 큼
```

MediaPipe를 GPU로 5ms까지 줄이면 "MediaPipe 지배적이라 gRPC는 신경 안 써도 됨"이라는 지금 결론이 깨짐. 단, 이 역전이 실제로 이득이려면 아래 세 가지가 같이 성립해야 함 — **하나라도 깨지면 GPU 도입은 손해이거나 무의미**:

1. **네트워크 위치**: 지금 gRPC 10~50ms 추정은 AI·Spring이 같은 로컬망이라는 전제. AI만 AWS GPU로 옮기고 Spring은 그대로면 gRPC가 WAN을 타 수십~수백ms로 뛸 수 있음 — Spring도 같은 VPC/리전으로 같이 옮겨야 GPU 이득이 살아남음.
2. **배치화 전제**: `ai-load-budget.md §6.2 (a)`가 이미 "인프라 변경, **단일 노드에선 어려움**"이라고 명시. MediaPipe pose 모델은 모바일용 경량 모델이라, 지금처럼 세션당 프레임을 하나씩 동기 스트림으로 처리하면 GPU 커널 실행 오버헤드 때문에 5ms까지 안 날 수 있음 — 여러 세션 프레임을 배치로 묶어야 GPU 이득이 실현되는데, 이건 지금 구조(실시간 동기 스트림)와 상충하는 아키텍처 변경.
3. **수평 확장 블로커는 별개**: GPU는 인스턴스 1개의 처리 시간만 줄임. `ai-backend-coupling.md 분기 D`의 진짜 확장 제약(`session_state` in-memory → 인스턴스 여러 개 못 띄움)은 GPU와 무관하게 그대로 남음. `ai-load-budget.md §6.2`도 GPU(a)보다 세션 상태 외부화(b)를 정식 출시 시 우선 검토로 추천.

**판단**: 이 프로젝트에 GPU/AWS 인스턴스를 실제로 도입할 계획은 없음([[project_keep_server_ai_architecture]]) — 위 분석은 구현 대상이 아니라 **면접 설계 서사용 사고실험**. "GPU 쓰면 빨라진다"를 반사적으로 답하지 않고 네트워크 위치·배치화 전제·수평확장 블로커까지 같이 짚는다는 게 이 항목의 포인트. 이 프로젝트 자체 착수 우선순위(§3)에는 영향 없음 — AI 서버는 손대지 않는 대상([[project_keep_server_ai_architecture]], [[feedback_minimize_python_changes]]).

#### 2-3-2. 현재 배포 구조(Docker 번들) + 3구간(MediaPipe·gRPC·MySQL) 시간 분해

**현재 배포 구조 확인** (`docker-compose.yml`): `mysql`·`shadowfit-backend`·`shadowfit-ai`가 전부 같은 `shadowfit-net` bridge 네트워크, 같은 compose, 사실상 같은 물리 박스에 번들돼 있음. `ai-server/Dockerfile`도 `python:3.12-slim` base — CUDA/GPU 런타임 없음, 지금은 순수 CPU-only. 즉 §2-3-1의 "조건1(네트워크 위치)"이 지금은 **구조상 이미 충족**돼 있음 — Spring↔AI gRPC가 인터넷/리전 간이 아니라 같은 호스트의 Docker bridge.

같은 호스트 bridge 통신은 물리 NIC·스위치를 안 타고 커널 내부 veth pair만 거치므로, 순수 네트워크 전송 자체는 **~0.1~1ms** 수준으로 추정됨(loopback ~0.02~0.05ms와 물리 LAN ~0.2~1ms 사이). 즉 문서들에 박아둔 "gRPC RTT 10~50ms"의 대부분(9~49ms)은 네트워크가 아니라 **protobuf 직렬화 + gRPC 프레이밍 + 서버측 처리** 몫.

**3구간 종합 — 배치(R=25프레임, `load-test-strategy.md §7.9` 기준) 단위 누적**:

| 구간 | CPU (현재) | GPU (가정) |
|---|---|---|
| MediaPipe 추론 (25프레임 누적, 프레임당 20~50ms / GPU면 ~5ms — `ai-load-budget.md:45`) | 500~1,250ms | 125ms |
| gRPC 전송 (배치당 1회, 순수망 <1ms + 직렬화/처리 나머지) | 10~50ms | 10~50ms (동일 — 코로케이션 유지 전제) |
| Spring MySQL `INSERT` batch (`latency-perception.md:56`) | 20~100ms | 20~100ms |
| **배치 1개 총합** | **530~1,400ms** | **155~275ms** |
| MediaPipe가 차지하는 비중 | **89~94%** | **45~81%** |

**MediaPipe : (gRPC+MySQL) 비율**

| | 비율 | 의미 |
|---|---|---|
| CPU | 약 **8~17배** | MediaPipe 압도, gRPC+MySQL은 노이즈 |
| GPU | 약 **0.8~4배** | 격차 급격히 좁혀짐, 최악 케이스(gRPC 직렬화 49ms+MySQL 100ms=149ms > MediaPipe 125ms)는 **역전 가능** |

**GPU 가정 시 Spring 쪽이 신경 써야 할 것** (latency 축과 capacity 축을 구분):
- **latency 축** — MediaPipe가 더는 압도적이지 않으므로 Spring/MySQL 쓰기 경로가 새 병목 후보. `load-test-strategy.md §9.3`("병목은 이동한다 — AI 수평 확장 후 다음 병목은 MySQL 커넥션·gRPC 콜백 경로 = 백엔드 차례")과 정확히 같은 원리, 컴포넌트 분리 테스트(§7.5~7.8)로 이미 메커니즘은 확인됨(batch 적재 +99% throughput·−37% p99, 천장 잠정 ~64 RPS). 단 그 숫자는 i3-6100 2코어 박스([[project_loadtest_env_constraint]]) 종속값이라 AWS엔 재측정 필요 — 메커니즘만 이전 가능.
- HikariCP pool 사이징(현재 "10 유지" 근거)도 물리 2코어 박스 종속 결론 — 새 인스턴스 코어 수 기준으로 재계산·재실측 필요.
- Resilience4j 서킷브레이커(§2-3 기존 갭)가 "있으면 좋음"에서 "필수"로 격상 — AI가 GPU로 자연 스로틀(20~50ms/프레임)을 잃으면 Spring 쓰기 경로가 지금보다 빠른 속도로 밀려들 수 있고, 동기 콜백 구조상 Spring이 못 받으면 AI worker까지 막힘(§9-1).
- 배치 크기 R·flush 주기(현재 3~4초, AI CPU 페이싱 전제로 산정된 값) 재튜닝 필요.
- **capacity 축**(동시 사용자 수)은 GPU와 무관 — `ai-backend-coupling.md 분기 D`(`session_state` in-memory)가 여전히 결정. 이건 Spring이 아니라 AI 쪽 구조 문제.

**판단**: §2-3-1과 동일 — 실제 착수 대상 아닌 면접 설계 서사용 사고실험. 다만 이 항목이 보여주는 서사(코로케이션 확인 → GPU 도입 시 비율 역전 → latency/capacity 축 분리 → Spring 쪽 재점검 항목 4개)는 "반사적으로 GPU 붙이자"가 아니라 **각 변경이 기존 가정(물리 박스 종속 pool 사이징, batch 페이싱, 네트워크 위치)에 어떤 파급을 주는지 짚는 능력**을 보여주는 카드로 쓸 수 있음.

### 2-4. tcpdump를 이용한 패킷분석 장애해결 — 제한적

**현재 상태**: 프로젝트 어디에도 tcpdump 사용/네트워크 장애 재현 이력 없음.

**적용 가능성 판단**: 낮음~제한적. 이유:
- 로컬 단일 박스([[project_loadtest_env_constraint]])라 진짜 네트워크 장애(패킷 손실, 지연, 파티션)가 발생할 여지가 구조적으로 적음.
- 실사고 없이 "tcpdump 켜봤다"는 인위적 데모라 서사 설득력이 약함.

**단, 이미 실제로 겪은 사건 하나가 있음**: `load-test-strategy.md §7.7`의 "ghz 측정 종료 시 in-flight 100건 `Unavailable`" — 원인을 로그 타임스탬프로 규명했지만, **TCP 레벨 증거(FIN/RST 시퀀스)는 안 봤음**. 이걸 재현하면서 tcpdump로 캡처해 "애플리케이션 로그 분석 + TCP 패킷 증거"로 이중 검증하면, 인위적 데모가 아니라 **이미 있는 실제 사건에 계층을 하나 더 얹는 것**이라 훨씬 정직함.

**추천**: 새 장애를 억지로 만들지 말고, §7.7 재현 시나리오에 한정해서만 사용. 우선순위는 낮음(선택 카드).

### 2-5. properties/yml의 connection 설정 — 문서화 완료 (2026-07-11)

> 🔴 **이 절은 2026-07-11 스냅샷이고, 아래 "현재 상태"는 더 이상 현재가 아니다** (2026-08-08 확인). 날짜가 박힌 기록이라 본문은 그대로 두고 실제 현재 상태만 여기 적는다.
>
> | | 이 절이 적은 것 (07-11) | **실제 (2026-08-08 코드 확인)** |
> |---|---|---|
> | 값 | `10` | **`15`** (`application.yml:25`) |
> | 위치 | `application.properties` | **`application.yml` 에서만** — `application.properties:10` 은 *"yml에서만 관리"* 라는 주석만 남아 있다 |
>
> **왜 옮겼나**: 2026-07-25(PR #53)에 15 로 올렸는데, `properties` 에 남아 있던 `10` 이 우선순위(properties > yml)로 **yml 설정을 조용히 무시**하고 있었다 — CodeRabbit 이 Major 로 잡았다. 그래서 한 곳에서만 관리하도록 정리했다.
>
> ⚠️ **그리고 "15" 의 근거도 이미 낡았다**: 2026-08-08 격자 재측정에서 pool 5~20 전 구간이 동급으로 나왔고(절벽 없음), 병목이 커넥션이 아니라 **백엔드 CPU** 로 옮겨간 것이 확인됐다 — 원인은 우리가 넣은 다운샘플(R=5)이다. 값을 어떻게 할지는 [`pool-cliff-vs-concurrency.md`](./pool-cliff-vs-concurrency.md) §8 미결. 아래 §2-5 본문의 *"왜 10인가"* 논의는 **두 단계 낡은 상태**로 읽을 것.

**현재 상태**:
- ~~`application.properties`: `hikari.initialization-fail-timeout`, `hikari.connection-timeout`만 명시. `maximum-pool-size`는 Spring Boot 기본값(10)에 암묵적으로 의존.~~ **✅ `maximum-pool-size=10` 명시 완료** — 실측 근거 주석과 함께 `application.properties`에 추가.
- `pose-ingest-downsampling.md §5-1(5)`에서 이미 **pool 10→30 재생성 실측** — 개선 0, 오히려 저~중 동시성에서 악화. 결론: "천장은 풀이 아니라 박스(물리 2코어)", HikariCP 스위트스폿(물리코어×2+1≈5) 기준 이미 pool=10이 위.

**판단**: 이미 실측 기반으로 "왜 10을 유지하는지" 설명 가능한 상태였음 — 코드/동작 변경 없이(Spring Boot 기본값도 10) "기본값에 우연히 의존"에서 "실측 후 의도적으로 10 유지"로 의도만 드러냄. Docker 재기동 후 `/actuator/metrics/hikaricp.connections.max`로 10.0 반영 확인. 커밋 `d07d25a`.

**⚠️ 이 "10 유지" 결론의 전이 한계(2026-07-11, 사용자 질의로 확인)**: HikariCP 스위트스폿 공식(코어×2+스핀들)이 원래 가리키는 건 **DB 서버의 코어수**다. 지금 실측은 mysql·backend가 **같은 물리 2코어를 공유**하는 로컬 docker-compose 환경이라 "박스가 병목"이라는 결론이 앱 서버 코어 병목인지 DB 서버 코어 병목인지 구분이 안 된 채 나온 것. 실제 프로덕션(관리형 DB로 앱 서버와 DB를 분리하는 게 표준 — RDS 등)이라면 백엔드 코어수는 커넥션 풀 사이징과 무관해지고 DB 서버 코어·디스크 기준으로 재실측해야 함. 상세: [`pose-ingest-downsampling.md §5-1(5)`](./pose-ingest-downsampling.md) 추가 정정 참고. 결론 자체(코드에 `10` 명시)는 유효하지만, "왜 10인가"의 근거는 이 로컬 동거 환경에 종속된 것으로 정정.

**분리 배포 가정 시 이론 계산**: `db.t4g.micro`(2 vCPU) RDS를 가정하면 공식상 스위트스폿은 **5**(`(2×2)+1`) — 로컬 실험에서 나온 숫자와 우연히 같지만 이유는 다름(로컬은 backend+mysql 코어 공유, RDS 가정은 DB 전용 코어). 실제 RDS는 안 띄움(비용 발생, 미결정) — 순수 이론 계산이며 실측 아님. 상세: [`pose-ingest-downsampling.md §5-1(6)`](./pose-ingest-downsampling.md).

### 2-6. k6을 이용한 부하테스트 — 검토 후 미채택, 재검토 가치 낮음

**현재 상태**: `load-test-strategy.md §8, §11`에서 이미 도구 선택을 끝냄 — gRPC 부하(쓰기 축 핵심 경로)는 **ghz**("gRPC용 k6"), E2E는 **Locust**(프레임 리플레이가 Python이라 유리) 채택, k6 자체는 안 씀.

**적용 가능성**: 제한적. 이유:
- 이 프로젝트 부하의 핵심(pose_data 배치 적재)은 REST가 아니라 gRPC라 k6보다 ghz가 정확히 맞는 도구 — 이미 그렇게 결론 남.
- k6이 의미 있으려면 REST 엔드포인트(`ExerciseReportController`의 `GET /reports` 등, projection 개선을 검증한 그 경로) 부하테스트인데, 이미 EXPLAIN·응답시간 실측이 끝난 지점이라 **같은 결론을 다른 도구로 재확인**하는 정도의 가치.

**과설계 경계**: "k6도 써봤다"처럼 도구 나열 목적으로 추가하는 건 지양 — 면접관은 왜 이 도구를 왜 안 썼는지(gRPC엔 ghz가 맞다는 판단) 설명하는 쪽을 더 신뢰함.

**추천**: 굳이 하려면 REST 리포트 엔드포인트 1회성 부하테스트로만, 메인 카드로는 비추천.

### 2-7. thread 수치 튜닝 + tomcat 대신 netty(WebFlux) — 불필요(역효과), 정리만 추천

**현재 상태**: `build.gradle`에 `spring-boot-starter-web`(Tomcat)**과** `spring-boot-starter-webflux`가 **둘 다** 의존성으로 존재. Spring Boot 오토컨피그는 서블릿 스택이 클래스패스에 있으면 그쪽을 우선하므로 **실제 구동 서버는 Tomcat**. WebFlux는 리액티브 `WebClient` 빈(`WebClientConfig.fastapiWebclient`)을 쓰려고 끌려온 것으로 보이는데, 정작 그 빈은 `ExerciseAnalysisService:38`에 주입만 되고 **실사용 0건**(FastAPI 통신은 전부 gRPC). Tomcat 스레드 풀 크기도 `application.yml`에 별도 설정 없이 기본값(200) 그대로 — 튜닝한 적 없음.

**적용 가능성 판단**:
- **Netty(WebFlux) 전면 전환**: 비추천. DB 액세스가 JPA/`JdbcTemplate`(블로킹 JDBC)인 이상, 서버만 논블로킹으로 바꿔도 스레드가 JDBC 호출에서 결국 블로킹되어 이득이 없음(R2DBC로 갈아타지 않는 한). 오히려 "블로킹 스택 위에 리액티브를 얹는 건 안티패턴"이라고 판단해서 **안 했다**는 설명이 더 시니어 시그널.
- **Tomcat thread 수치 튜닝**: 가능은 하지만 [[project_loadtest_env_constraint]] — 물리 2코어 로컬 박스에서 스레드 풀 크기를 바꿔가며 절대 처리량을 비교해봐야 코어 경합 노이즈에 묻힘. `pose-ingest-downsampling.md §5-1(5)`에서 HikariCP 풀 사이징도 동일하게 "박스가 진짜 천장"으로 나왔던 것과 같은 함정.

**대신 제안**:
- 미사용 `webflux`/`WebClient` 의존성을 정리(dead code 청소)하거나, 남기려면 "왜 WebFlux 서버로 안 갔는지"를 판단 근거로 문서화 — 코드 정리보다 **판단 근거를 남기는 쪽이 포폴 가치가 큼**.
- 스레드 풀 수치 자체는 "코어수 기반 산정 공식(예: Tomcat 권장 = I/O 대기비율 고려한 200 기본값이 이 환경엔 과함)"을 이론적으로 설명하는 정도로만, 실측 절대치 자랑은 금지.

### 2-8. JVM을 통한 성능개선 (GC, ratio 튜닝) — 제한적, 프레이밍 전환 추천

**현재 상태**: `Dockerfile`의 `ENTRYPOINT ["java", "-jar", "app.jar"]`에 GC 관련 JVM 옵션 전혀 없음(기본 GC 그대로). 어떤 문서에도 GC 튜닝 실측 없음. 단, `load-test-strategy.md §7.7`에서 에러 원인을 추정할 때 "GC stall"을 후보로 올렸다가 **데이터로 기각**한 사례는 있음(원인은 측정 종료 시 in-flight 강제 종료였음).

**적용 가능성 판단**: 제한적. [[project_loadtest_env_constraint]] 그대로 적용됨 — 물리 2코어 로컬 박스에서 GC 정책/힙 비율을 바꿔가며 "before/after 처리량 개선"을 주장하면, `pose-ingest-downsampling.md §5-1(5)`의 풀 사이징 실험과 똑같이 **박스 노이즈에 묻혀 무의미**할 가능성이 큼. "GC 튜닝으로 N% 개선!" 식 절대수치는 스킵 권장.

**이 환경에서도 의미 있게 보여줄 수 있는 형태**:
- **"GC 배제 증거"로 격하 활용**: §7.7에서 이미 "GC stall 아니었다"고 텍스트로만 결론 냈던 걸, 실제로 `-Xlog:gc` 켜고 배치 INSERT 부하 중 GC 이벤트/일시정지시간을 관찰해서 **로그로 뒷받침**하면 정직성이 강화됨(주장 → 증거로 격상). 개선 효과 주장이 아니라 반증 카드.
- **컨테이너 인식 설정만 최소 적용**: `-XX:MaxRAMPercentage` 같은 컨테이너 메모리 인지 옵션을 명시하는 정도는 "운영 감각(컨테이너 환경에서 JVM 기본 휴리스틱에 기대지 않음)" 시그널로 작게 추천 — 단 이것도 "효과 실측"이 아니라 "모범 사례 적용"으로만 포지셔닝.

**추천**: GC "튜닝 효과" 카드는 스킵, "GC가 병목이 아니었다는 걸 로그로 재확인"하는 반증 카드로 전환.

### 2-9. gRPC 구간 DB 쓰기(pose_data/session_feedback_logs) 추가 최적화 후보 — 조사 완료, 착수 전

**착수 예정: 2026-07-06(내일)** — 아래 중 어느 것부터 할지는 미정, 사용자 confirm 대기.

**이미 적용 확인됨** (그래서 아래 후보에서 제외):
- `rewriteBatchedStatements=true`(`application.yml:17`), IDENTITY PK 확인 후 `JdbcTemplate.batchUpdate` 전환, `INSERT IGNORE`+UNIQUE KEY 멱등성, HikariCP pool 10 유지(30 실측 후 기각).

**신규 후보 3개** (코드로 확인, 우선순위순):

1. ~~**`session_feedback_logs` 중복 인덱스**~~ — **✅ 해결(2026-07-24)**. 스크래치 테이블(`session_feedback_logs_scale`, 100만 행, [`loadtest/measure_redundant_index.sh`](../../loadtest/measure_redundant_index.sh))로 실측: EXPLAIN 상 실사용 쿼리 2종(`findBySessionIdOrderByOccurredAtAsc`, `GROUP BY feedback_type` 집계) 둘 다 `idx_session_feedback` 존재 시에도 옵티마이저가 `uk_session_event`만 선택(읽기 이득 0 확인) + 동일 규모(10만 행) batch INSERT가 3-index 7,894ms → 2-index(drop 후) 6,202ms(약 −21%, 로컬 2코어 환경이라 절대값보단 방향 신뢰). `idx_session_feedback` 제거, `mysql/schema.sql`에 반영.
2. **배치 전 동기 존재검증 라운드트립** — `savePoseDataBatch`/`saveBatch`가 batch INSERT 전 `sessionRepository.existsById(sessionId)`를 별도 SELECT로 선실행, 배치당 왕복 2회. FK(`ON DELETE CASCADE`)가 이미 무결성 보장하므로 사전체크 제거 + FK violation을 예외로 변환하면 왕복 1회로 축소 가능 — 단 에러 처리 계약 변경 트레이드오프 있음.
3. **트랜잭션 격리수준 미검토** — MySQL 기본 REPEATABLE READ 그대로, 배치 INSERT 위주 워크로드에서 READ COMMITTED로 gap lock 범위 축소 여지 미탐색. 단 우리 배치가 대부분 INSERT뿐이라 gap lock 경합 자체가 적을 수 있어 이득이 있을지부터 이론적으로 먼저 따져봐야 함.

---

## 3. 종합 추천 순서 (우선순위, 결정 아님)

> **2026-07-11 정정**: 원래 BE-03(기능 갭)을 0번 선결 전제로 올려뒀었으나, 사용자 확인 결과 **캐시가 BE-03보다 우선순위가 높음**으로 정정. BE-03은 "이게 돼야 다른 게 논의 가능"한 강제 선결 조건이 아니라, 그냥 성능 시그널 8개와 별도 카테고리(기능 갭)인 항목 중 하나로 순위 재조정.

1. ~~**로컬 캐시(Caffeine)** — `FeedbackTemplateService` 대상, 작고 명확한 스코프, 히트율 실측 가능.~~ **✅ 완료(2026-07-11)** — §2-2-2 실측 결과 참고. ~~`exercises`(임계값) 등 후속 캐싱 후보는 별개 미결정.~~ **✅ exercises도 착수+완료(2026-07-11)** — §2-2-3 참고(evict 필요한 케이스로 차별화, AI 서버 미연동 갭은 별개로 정직하게 남김). **✅ exercise_references도 착수+완료(2026-07-11)** — §2-2-4 참고(마지막 남은 캐싱 후보, detached 엔티티 함정 없는 케이스). 10개 테이블 인벤토리 기준 캐싱 후보는 이걸로 소진.
2. **BE-03 착수(GPT/Claude 리포트 자동 생성)** — [`22-backend-tasks-detail.md`](../tasks/22-backend-tasks-detail.md) 기준 `GptFeedbackService` 신설 + LLM 호출 후 `Report` 저장 + 세션 종료 콜백 연결(추정 6h). **선결 미결정**: OpenAI vs Anthropic 선택(같은 문서 "리스크/의존"에 `decisions/llm-provider.md` 분리 추천됨). `reports`가 §2-2-1에서 확인했듯 쓰기 경로 자체가 없어 실제 세션 완료 시 404가 나는 상태라는 기능 갭은 여전히 유효 — 다만 다른 항목의 강제 선결 조건은 아님.
3. ~~**Resilience4j 서킷브레이커** — 이미 OPEN이던 항목 재확인, 비동기 골격 위에 얹기만 하면 됨.~~ **✅ 완료(2026-07-11)** — §2-3-3 참고. CLOSED→OPEN→HALF_OPEN→CLOSED 전체 생명주기 Docker 실측 확인.
4. ~~**connection 설정 문서화** — `maximum-pool-size=10` 명시(코드 변경 아닌 설정 동기화).~~ **✅ 완료(2026-07-11)** — §2-5 참고. 커밋 `d07d25a`.
5. (선택) **tcpdump** — §7.7 재현 한정.
6. (선택) **GC 반증 카드** — 튜닝 아닌 "GC 아니었다"는 증거 보강.
7. **k6 / netty 전환** — 비추천, 필요 시 언급만.

---

## 결정 로그
- 2026-07-05: 문서 최초 작성. 8개 항목(EXPLAIN·로컬캐시·비동기외부호출·tcpdump·connection설정·k6·thread/netty·JVM GC) 코드 근거로 현재상태·적용가능성 분석. 착수 여부·순서는 미결정, 사용자 confirm 대기.
- 2026-07-05: §2-2-1 추가. 스키마 10개 테이블 전체 쓰기 경로 인벤토리 — 쓰기 있음 7개, 없음 3개(`reports`·`exercise_feedback_templates`·`body_records`). `reports`는 쓰기 경로 자체가 없어 미구현 기능 흔적으로 별도 확인 필요, `body_records`는 엔티티조차 없음. 새 결정 아닌 기존 캐싱 후보 판단의 근거 보강.
- 2026-07-05: `reports` 쓰기 경로 없음의 원인 확인 — [`22-backend-tasks-detail.md`](../tasks/22-backend-tasks-detail.md) BE-03("GPT/Claude 리포트 자동 생성") 미착수가 원인. 버그·누락 아닌 별도 작업 대기 상태로 §2-2-1 표에 반영. 새 결정 아닌 원인 규명.
- 2026-07-11: §2-2 로컬 캐시 **구현+실측 완료**. 사용자 confirm 거쳐 대상(`FeedbackTemplateService.getTemplatesByExercise` 단독)·TTL(1시간 안전판)·패턴(cache-aside)·매니저 구성(Boot 자동설정)·키(SpEL) 확정 후 착수. 실제 Docker 환경에서 히트율 95.2%(60 hit/3 miss, actuator 실측)·HIT 평균 23.6ms·MISS 평균 43ms(n=2, 표본 작음 명시) 확인. 착수 중 `spring-boot-starter-cache` 누락으로 기동 실패(`CaffeineCacheManager` 클래스가 별도 모듈) 겪고 수정, `spring.cache.cache-names` 미등록 시 지연생성 캐시가 Micrometer에 안 잡히는 것도 실측 중 발견해 반영. §1 표·§3 우선순위 갱신. 후속(`exercises` 캐싱 등)은 별개 미결정으로 남김.
- 2026-07-05: §3 우선순위에 BE-03 착수를 0번(기능 갭, 성능 시그널과 별개 카테고리)으로 추가. LLM provider(OpenAI/Anthropic) 선택이 선결 미결정으로 남음 — 착수 여부·순서는 여전히 사용자 confirm 대기.
- 2026-07-05: §2-3-1 추가. 쓰기(Create) 경로의 MediaPipe vs gRPC 비율을 CPU 기준(MediaPipe 압도, 10~50배)과 GPU 가정 시(비율 역전, gRPC와 비등)로 분해. GPU 도입은 네트워크 위치(AI·Spring 동일 리전 필요)·배치화 전제(단일 노드에선 어려움, `ai-load-budget.md §6.2`)·수평확장 블로커(`session_state` 외부화는 별개, 분기 D)가 안 깨져야 성립한다는 조건부 판단. 실제 착수 대상 아닌 면접 설계 서사용 사고실험 — §3 우선순위 변경 없음.
- 2026-07-05: §2-3-2 추가. `docker-compose.yml` 확인 결과 mysql·backend·ai가 이미 같은 bridge 네트워크·같은 박스에 번들 — §2-3-1 "조건1(네트워크 위치)"이 구조상 이미 충족돼 있음을 코드로 확인. 이를 근거로 MediaPipe·gRPC·MySQL 3구간 시간을 배치(R=25) 단위로 표로 종합(CPU 총합 530~1,400ms vs GPU 155~275ms, MediaPipe:gRPC+MySQL 비율 CPU 8~17배 → GPU 0.8~4배로 역전 가능) + GPU 가정 시 Spring 쪽 재점검 항목 4개(HikariCP 재실측·Resilience4j 격상·배치 R 재튜닝·capacity 축은 AI 쪽 문제로 분리) 정리. 새 결정 아닌 기존 추정치들의 종합·시각화, 착수 대상 아님.
- 2026-07-05: §2-9 추가. gRPC 구간 DB 쓰기(pose_data/session_feedback_logs) 추가 최적화 후보 3개(중복 인덱스, 배치 전 동기 존재검증 라운드트립, 트랜잭션 격리수준 미검토) 코드로 확인. 2026-07-06(내일) 착수 예정 — 어느 후보부터 할지·전부 할지는 미정, 사용자 confirm 대기.
- 2026-07-11: §2-2-2 "남은 것"으로 미결정 남겨뒀던 `exercises`(임계값) 캐싱 후속, 사용자 confirm 후 착수+완료. §2-2-3 추가 — feedback_templates와 달리 쓰기 경로(`updateThresholds`, dirty-checking)가 있어, 캐시가 반환하는 detached 엔티티에 setter를 호출하면 변경 감지가 안 걸려 저장이 조용히 무시되는 함정을 구현 전에 식별하고 읽기(`findByIdCached`)/쓰기(`findById`+`@CacheEvict`) 경로를 분리해 회피. 착수 전 조사로 gRPC `AnalyzeRequest`에 threshold 필드가 없고 AI 서버가 자체 상수를 써서 이 값이 실제 분석 로직엔 미반영 상태(캐싱과 무관한 기존 갭)임을 코드로 확인해 정직하게 기록. Docker 실측으로 MISS→HIT→(관리자 갱신)→evict 후 재-MISS 사이클을 `cache.gets` 카운터로 검증. 테스트 유저는 실제 삭제 API로 정리하며 직전 회원탈퇴 버그(`72e8924`)도 재검증. 커밋 `8a2824f`. §1 표·§3 우선순위 갱신.
- 2026-07-11: §3 우선순위 정정. 기존에 BE-03(GPT/Claude 리포트 자동 생성)을 0번 "선결 전제"로 올려뒀던 것을, 사용자 확인 결과 **캐시가 BE-03보다 우선순위 높음**으로 정정 — BE-03을 다른 항목의 강제 선결 조건이 아닌 순위 2번(기능 갭, 여전히 유효하지만 비강제)으로 재조정. 캐시(§2-2)는 feedback_templates·exercises 모두 완료된 상태라 실질적으로 1번 자리 그대로, BE-03·Resilience4j 이하 항목의 상대 순서만 밀림.
- 2026-07-11: §2-2-4 추가. 남은 캐싱 후보 재조사 결과 `exercise_references`가 유일하게 남은 대상으로 확인(그 외 `findAll` 계열 카탈로그 조회·enum 등은 후보 성립 안 함) — 착수+완료. `exercises`와 달리 조회(`findByExerciseId`)가 쓰기 경로에서 재사용되지 않아 detached 엔티티 함정 자체가 없어 리포지토리 메서드에 바로 `@Cacheable` 적용. 실측 중 AI 서버의 실제 좌표 추출이 미구현 스텁임을 확인(기존 갭) — 정상 파이프라인 대신 ai-server 컨테이너의 컴파일된 proto stub으로 Spring gRPC 콜백을 직접 호출해 우회 검증. MISS→HIT→(재등록 evict)→재-MISS 사이클을 `cache.gets`로 확인. 커밋 `5754fc3`. 10개 테이블 인벤토리 기준 캐싱 후보 소진 — §1 표·§3 우선순위 갱신.
- 2026-07-11: 인덱스 갭(캘린더/주간활동) 별도 착수·완료(`docs/decisions/report-read-path.md` §4 문제④) — `exercise_sessions`에 `(member_id, start_time)` 복합 인덱스 추가, EXPLAIN ANALYZE로 1675행 Filter→143행 Index range scan 실측(커밋 `dbb0fec`). 부수 발견으로 `calculateConsecutiveDays`의 `java.sql.Date`→`LocalDate` 컨버터 누락 500 에러도 재현·수정(커밋 `eed807e`).
- 2026-07-11: §2-3 Resilience4j 서킷브레이커 **구현+실측 완료**. §2-3-3 추가 — `ExerciseAnalysisService`의 Spring→AI outbound 호출 3개가 `@Async`+`StreamObserver` 비동기 콜백 구조라 어노테이션 기반(`@CircuitBreaker`)이 실제 성공/실패를 못 잡는다는 걸 확인하고, `CircuitBreakerRegistry`를 수동 연동(`tryAcquirePermission()` 게이트 + 콜백 안에서 `onSuccess`/`onError` 기록)하는 방식으로 구현. Docker에서 `docker stop shadowfit-ai`로 AI 장애를 실제로 유발해 CLOSED→OPEN(`notPermittedCalls`로 fail-fast 확인)→10초 후 자동 HALF_OPEN→CLOSED 전체 생명주기를 실측 검증. 커밋 `215d49a`. §1 표·§3 우선순위 갱신.
- 2026-07-11: 사용자가 "이거 스프링만 하면 되는가, 쉽게 끝날 일인가"라고 재검토 요청 — 셀프 리뷰 결과 gRPC 호출에 데드라인이 없어서 AI가 죽는 게 아니라 그냥 응답 없이 멈추는(hang) 상황은 서킷브레이커가 전혀 못 잡는 갭을 발견해 정직하게 공유(§2-3-3에 갭으로 먼저 기록). 사용자가 "hang 대응(타임아웃) 먼저"로 우선순위 지정 후 착수 — `getAuthenticatedStub()`에 `withDeadlineAfter(5s)` 추가로 데드라인 초과 시 자동 `onError(DEADLINE_EXCEEDED)` 발생하도록 해서 기존 기록 로직 재사용. Docker에서 `docker pause`(TCP 유지, 응답만 없음 — `docker stop`과 다른 진짜 hang)로 재현·검증, hang 누적 시 OPEN 트립·복구까지 확인. 커밋 `0c47598`. §2-3-3 "갭" 서술을 "해결"로 갱신 — AI→Spring 반대 방향 무방비, 스킵된 세션 후속처리 없음, 설정값 미실측 등 남은 갭은 여전히 정직하게 남겨둠.
- 2026-07-11: 사용자가 "남은 갭도 마저 정리해달라"고 요청 — §2-3-4 추가로 나머지 3개 갭 정리. (1) **해결**: 스킵/실패한 세션을 `markAsFailedIfStillInProgress`로 즉시 `FAILED` 전환하도록 추가, Docker 실측(`docker pause`)으로 OPEN-스킵 경로(gap 0초)와 데드라인 경로(gap 5초) 둘 다 확인, 커밋 `50bcf82`. (2) **의도적으로 스코프 밖으로 확정**: AI→Spring 반대 방향은 AI(Python) 코드 수정이 필요해 [[feedback_minimize_python_changes]] 방침상 이번엔 안 함 — 회피가 아니라 명시적 결정으로 기록. (3) **정직하게 미실측으로 명시**: 서킷브레이커 설정값은 업계 통상값에 가까운 보수적 기본값이지 이 프로젝트 트래픽으로 튜닝한 게 아님 — [[project_loadtest_env_constraint]] 때문에 튜닝 실측 자체가 이 환경에서 의미가 약하다는 점도 근거로 남김.
- 2026-07-11: §2-5 connection 설정 문서화 완료. `application.properties`에 `spring.datasource.hikari.maximum-pool-size=10`을 실측 근거 주석과 함께 명시 — 코드/동작 변경 없음(HikariCP 자체 기본값도 10), "기본값에 우연히 의존"에서 "실측 후 의도적으로 10 유지"로 의도만 드러냄. Docker 재기동 후 `/actuator/metrics/hikaricp.connections.max=10.0`으로 반영 확인. 커밋 `d07d25a`. §1 표·§3 우선순위 갱신.
