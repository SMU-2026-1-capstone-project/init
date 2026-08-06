# Decision: 동적 쿼리 계층(QueryDSL / Criteria API) 도입 여부

상태: **✅ QueryDSL 채택 확정 (2026-07-31 사용자 confirm)** — 단 적용 범위·착수 시점은 §1-1 참조
작성: 2026-07-31 / 결정: 2026-07-31
배경: "우리 프로젝트에 QueryDSL이나 Criteria API를 쓰나 / 앞으로 쓸 건가 / 남은 요구사항에 적용해야 하나"라는 질문(2026-07-31 사용자). 코드 감사 결과 **현재 동적 쿼리 빌더 계층이 아예 없고**, 도입 여부가 문서로 논의된 적도 없어 열린 항목으로 정리.
연관: [`../tasks/27-implementation-gaps.md`](../tasks/27-implementation-gaps.md)(잔여 항목), [`./report-read-path.md`](./report-read-path.md) §0-B ⑬(페이징 전략 미착수), [`./recommendation-algorithm.md`](./recommendation-algorithm.md)(BE-08 스코프 A/B 분리), [`../tasks/25-portfolio-strategy.md`](../tasks/25-portfolio-strategy.md), [`./portfolio-benchmark.md`](./portfolio-benchmark.md)

> 결정 ✅ 는 사용자 confirm 후 박제. 본 문서는 분석·권고.
> 🟢=추천, 🔶=열림, ⬜=계획, ❌=배제 권고

---

## 0. 한 줄 요약

동적 쿼리 빌더는 **"요청마다 WHERE 절이 늘었다 줄었다" 할 때만** 값을 한다. 남은 요구사항 8개를 코드 기준으로 판정하면 그 조건을 만족하는 건 **현재 시점 기준 `관리자 대시보드` 하나뿐**이다. 따라서 이 문서의 질문은 사실상 기술 선택이 아니라 **"관리자 페이지를 만들 것인가"라는 기능 결정 하나로 환원**된다. Criteria API 직접 사용은 어느 시나리오에서도 권하지 않는다.

---

## 1. 추천 / 열림 구분

| 항목 | 상태 |
|---|---|
| Criteria API(`CriteriaBuilder`) 직접 사용 | ❌ 배제 (확정) |
| 동적 쿼리 계층으로 **QueryDSL** 채택 | ✅ **결정 (2026-07-31)** |
| 관리자 페이지를 만들 것인가 | ✅ **결정 — 만든다** (QueryDSL 채택의 전제, §1-1) |
| 적용 대상 = 관리자 목록/검색 경로 | ✅ 결정 (§4-1의 범위 구분 준수) |
| 통계 위젯·단건 조회는 기존 방식 유지 | ✅ 결정 (§4-1) |
| 관리자 화면 **필터 항목 정의** | 🔶 **열림 — 남은 선결 과제** (§9) |
| 착수 시점 (의존성 선행 vs 기능과 동시) | 🔶 열림 (§8-1) |
| `JpaSpecificationExecutor` 병용 | ❌ **배제 결정 (2026-07-31)** — 조건 개수와 무관하게 동적 조회는 QueryDSL로 통일 (§6-1) |
| 리포트 목록 + keyset 페이징 신설 | 🔶 열림 ([`report-read-path.md`](./report-read-path.md) §0-C에서 의도적 보류 중) |

### 1-1. 결정 기록 (2026-07-31)

**결정**: 동적 쿼리 계층으로 QueryDSL을 채택하고, 관리자 페이지를 그 첫 적용처로 삼는다.

**경위**: §3 판정에서 남은 요구사항 8개 중 QueryDSL을 정당화하는 건 관리자 대시보드 하나뿐으로 좁혀졌고(§3-1에서 루틴 추천은 근거에서 탈락), "관리자 페이지를 만들 것인가"가 실질적 분기점으로 남아 있었다. 사용자가 관리자 페이지를 QueryDSL로 가는 것으로 confirm.

**이 결정이 뒤집히는 조건**: §9의 선결 과제(관리자 화면 필터 항목 정의) 결과 **필터가 1~2개로 확정되면** QueryDSL의 근거가 사라진다(그 규모는 `Specification`으로 충분). 그 경우 이 결정을 재검토할 것 — 지금은 "관리자 목록에 필터가 3개 이상 붙는다"는 통상적 가정 위에 서 있다.

**확정되지 않은 것**: 착수 시점(§8-1), QueryDSL 버전(§7), 필터 항목(§9).

---

## 2. 현재 상태 — 코드 감사 (2026-07-31)

**동적 쿼리 빌더 계층이 존재하지 않는다.** 다음 전부 0건:

| 확인 대상 | 결과 |
|---|---|
| `build.gradle`의 querydsl 의존성 / annotationProcessor / Q타입 경로 | 없음 |
| `com.querydsl.*`, `JPAQueryFactory` import | 0건 |
| `CriteriaBuilder` / `CriteriaQuery` / `JpaSpecificationExecutor` / `Specification` | 0건 |
| `EntityManager` 직접 주입 (main 소스) | 0건 |

**대신 쓰는 조회 방식 3가지:**

| 방식 | 위치 | 용도 |
|---|---|---|
| 파생 쿼리 + `@Query`(JPQL) | `repository/**` 12개 리포지토리 | 일반 조회 |
| `@Query(nativeQuery = true)` | `DailyLogRepository:34`(upsert), `OutboxEventRepository`(2건) | JPQL로 표현 불가한 것 |
| `JdbcTemplate` | `PoseDataService:70`, `FeedbackLogService:62`, `PoseDataPartitionScheduler` | 대량 배치 INSERT, `INSERT IGNORE`, 파티션 DDL |

**왜 지금 자리가 없는가** — 기존 조회는 전부 조건 개수가 컴파일 타임에 고정이다. 리포트 읽기 경로를 예로 들면:

- `ReportRepository:10` `findBySessionId` — 조건 1개
- `SessionRepository:38` `findWeeklySessionsWithExercise` — `memberId` + 기간, **항상 둘 다 존재**
- `SessionRepository:48` `findDistinctActiveDates` — 동일

조건이 "있을 수도 없을 수도" 있는 지점이 하나도 없다. 이 상태에서 QueryDSL을 얹으면 Q타입 생성 설정만 늘고 코드는 더 길어진다.

---

## 3. 남은 요구사항 8개 — 항목별 판정

대상은 [`27-implementation-gaps.md`](../tasks/27-implementation-gaps.md) 및 요구사항 정의 슬라이드(2026-05-23) 대조로 확정한 미구현 8개.

| 남은 기능 | 동적 쿼리 필요? | 근거 |
|---|:--:|---|
| 관절 색깔 시각화 | **무관** | 프론트 렌더링. `exercise.tsx`에 카메라·포즈 폴링은 있으나 스켈레톤 오버레이 없음. DB 쿼리 자체가 없는 영역 |
| 운동 타이머 | **무관** | 프론트 상태값. 백엔드는 `startTime~endTime`으로 `workoutMinutes`만 계산 |
| 운동 세트 자동 구분 | **아니오** | `set_no` 컬럼 추가 + `GROUP BY` 집계. 조건은 `sessionId` 고정 → JPQL로 충분. 현재 `SessionService:407`에 `"BE-09 세트 도입 시 확장"`으로 보류 명시 |
| 사용자 운동 패턴 분석 | **아니오** | `memberId` + 기간 고정 집계. 무거워지면 방향은 QueryDSL이 아니라 배치 + native |
| 운동 목표 달성현황 | **아니오** | `memberId` + 기간 + 목표유형. 조건이 항상 다 존재 → 파생 쿼리로 종결 |
| 데이터 기반 개인화 루틴 추천 | **아니오 (지금은)** | §3-1 참조 — 권고 스코프 A(강도·볼륨)는 직전 세션 읽기라 동적 조건 없음. 동적 후보 필터는 스코프 B(종목 추천)에서만 발생하고 B는 2학기 |
| 카테고리 관리 | **약함** | CRUD. 목록에 검색어 1개 붙는 수준이면 `Specification`으로 충분 |
| **관리자 대시보드** | **예 (유일한 1순위)** | 목록 + 다중 필터 + 가변 정렬 + 페이징 (§4) |

**결론: 8개 중 6개는 무관 또는 불필요, 1개(카테고리 관리)는 Specification 수준, QueryDSL을 정당화하는 건 관리자 대시보드 1개.**

### 3-1. 루틴 추천에 대한 정정

초기 판단은 "추천 후보 필터가 런타임 조합이므로 동적 쿼리 자리"였으나, [`recommendation-algorithm.md`](./recommendation-algorithm.md) §3을 대조한 결과 **틀렸다**:

- 권고 스코프 **A(강도·볼륨 추천, now 트랙)** = 직전 세션 기록을 읽어 progressive overload 계산. 조회는 `memberId` + `exerciseId` 고정 → **동적 조건 없음**.
- 스코프 **B(운동 종목 루틴 추천)** = 후보 운동을 `레벨/부위/최근 수행 제외/페르소나`로 거르는 구조라 동적 조건이 실제로 발생. 단 B는 squat-first 제약(분석 가능한 운동이 스쿼트뿐)으로 **2학기 운동 확장 이후**로 이미 밀려 있음.

→ 루틴 추천은 QueryDSL 도입 근거로 쓸 수 없다. 쓰려면 2학기까지 기다려야 한다.

---

## 4. 왜 관리자 대시보드만 성립하는가

관리자 화면의 본질은 "목록 + 검색"이다. 회원/세션/영상 목록에 `검색어 / 상태 / 기간 / 레벨` 같은 필터가 붙고 정렬 키가 바뀌면:

- 파생 쿼리로는 조합마다 메서드가 필요하다. 조건 3개 → 8개, 4개 → 16개 (부분집합 수 2ⁿ).
- QueryDSL은 `null`인 조건이 자동 탈락한다:

```java
where(member.id.eq(memberId),
      statusEq(cond.status()),        // null 반환 시 조건 제외
      levelEq(cond.level()),
      joinedBetween(cond.from(), cond.to()))
```

**가변 정렬 + keyset 페이징이 붙으면 격차가 더 벌어진다.** offset 대신 커서를 쓰면 정렬 키마다 커서 WHERE 절 자체가 달라진다 — 최신순이면 `(createdAt, id) < (:ts, :id)`, 이름순이면 `(name, id) > (:name, :id)`. JPQL 문자열로는 정렬 키 개수만큼 쿼리를 복제해야 하고, QueryDSL은 커서 조건을 함수로 조립한다. 현재 프로젝트에 keyset이 없고([`report-read-path.md`](./report-read-path.md) §0-B ⑬), [`portfolio-benchmark.md`](./portfolio-benchmark.md)에서 keyset을 "채울 키워드"로 분류해둔 점을 감안하면 관리자 목록이 둘을 한 번에 정당화하는 자리가 된다.

### 4-1. 같은 화면 안에서도 적용 범위를 갈라야 함

관리자 대시보드를 만들더라도 **전부 QueryDSL로 감싸면 안 된다.**

| 대시보드 구성요소 | 성격 | 적합한 방식 |
|---|---|---|
| 통계 위젯 (오늘 세션 수, 평균 싱크로율, 활성 사용자) | 조건 고정 집계 | JPQL / native |
| 목록 + 필터 + 정렬 + 페이징 | **런타임 조건 조합** | QueryDSL |
| 단건 상세 조회 | 조건 1개 | 파생 쿼리 |

> ⚠️ **§6-1 의 "동적 조회는 QueryDSL 로 통일"과 이 절은 서로 어긋나지 않는다.** 통일의 대상은
> 위 표 <b>둘째 줄</b>(필터가 붙는 목록)이고, 뜻은 "같은 동적 조회에 도구를 둘(QueryDSL +
> `Specification`) 두지 않는다"이다. 첫째 줄은 애초에 동적 조회가 아니라 통일 대상이 아니다.
> 관리자 페이지를 한 덩어리로 기억하면 통일 쪽만 남아 헷갈리기 쉬워 적어둔다 —
> 구현 시점의 같은 메모가 [`admin-page-scope.md`](./admin-page-scope.md) §3-D 에도 있다.
> 실제 구현(2026-08-06)도 이 표대로 갈렸다: A·B 목록은 QueryDSL, D 위젯 5종은 JPQL.

---

## 4-2. 동적 조립의 위험 — QueryDSL이 고치는 것과 못 고치는 것

"동적 쿼리가 왜 위험한가"를 정리한다. 도입 근거이자 **도입해도 남는 위험**의 목록이라, §4의 인덱스 논의와 직결된다. 예시는 관리자 회원 목록(필터 5개, [`admin-page-scope.md`](./admin-page-scope.md) §3-A) 기준.

### ① 조합 폭발 — 테스트로 덮이지 않는다

필터 5개가 각각 optional이면 가능한 쿼리는 **2⁵ = 32가지**, 정렬 키 3개를 곱하면 96가지다. 통합 테스트로 검증 가능한 건 현실적으로 3~5가지. 정적 쿼리는 하나를 테스트하면 그 하나가 끝나지만, 동적 쿼리는 **테스트한 조합만** 검증된 상태이고 나머지는 사용자가 처음 그 조합을 누를 때 처음 실행된다.

### ② 기동 시 검증까지 잃는다

`@Query`의 JPQL은 Spring Data가 리포지토리 프록시를 만들 때 파싱되므로, 필드명 오타·문법 오류는 **애플리케이션 기동 시점에** 드러난다(요청 시점이 아니다). 그런데 조건을 런타임에 문자열로 이어 붙이면 기동 시점엔 완성된 쿼리가 없어 검증 대상 자체가 사라진다.

```java
String jpql = "SELECT m FROM Member m WHERE 1=1";
if (level != null)   jpql += " AND m.workoutLevel = :level";
if (persona != null) jpql += "AND m.selectedPersona = :persona";  // 앞 공백 누락
```

두 필터를 **동시에** 걸 때만 깨진다. 단독으로 걸면 멀쩡하므로 기동도 QA도 통과한다.

### ③ 정렬 컬럼은 파라미터 바인딩이 불가능하다 — 보안

값은 `:param`으로 바인딩되어 인젝션이 막히지만 **`ORDER BY`의 컬럼명은 바인딩할 수 없다.**

```java
jpql += " ORDER BY m." + sortKey;   // sortKey = 클라이언트 입력
```

화이트리스트가 없으면 그대로 주입 지점이 된다. "동적 정렬"은 필터가 1개뿐인 화면에서도 나오는 평범한 요구라 노출 빈도가 높다.

**QueryDSL이 이걸 자동으로 막아주지는 않는다** — 초기 서술("`OrderSpecifier`가 타입 객체라 임의 문자열을 넣을 경로 자체가 없다")은 부정확했다(외부 리뷰로 정정). `PathBuilder`로 요청값에서 경로를 만들면 동적 정렬이 그대로 가능하다:

```java
// QueryDSL을 써도 이렇게 짜면 같은 문제가 남는다
new PathBuilder<>(Member.class, "m").getComparable(sortKey, String.class).asc();
```

QueryDSL이 주는 것은 **차단이 아니라 안전한 기본형**이다. Q타입(`member.createdAt.desc()`)을 쓰면 정렬 키가 컴파일 타임에 고정되므로, 화이트리스트가 자연스러운 작성 방식이 된다. 문자열 조립은 `PathBuilder`를 일부러 꺼내 써야 한다.

> **구현 규칙(적용 시 준수)**: 요청의 정렬 키는 **enum 또는 명시적 Q타입 매핑**으로 변환하고, 매핑에 없는 값은 400으로 거부한다. `PathBuilder`에 요청값을 그대로 넘기지 않는다.

### ④ 관측·역추적이 어려워진다

슬로우 쿼리 로그의 SQL을 코드에서 문자열 검색으로 찾을 수 없다. 조립된 쿼리는 그 형태로 코드에 존재하지 않기 때문. [`observability-correlation-id.md`](./observability-correlation-id.md)로 추적성을 챙겨둔 프로젝트라 이 축만 뒤처지게 된다.

### ⑤ 조합마다 실행 계획이 다르다 — **QueryDSL로 해결되지 않음** ⚠️

32가지 조합은 32개의 서로 다른 SQL이고 각각 다른 실행 계획을 탄다. 어떤 조합은 인덱스를 타고 어떤 조합은 풀스캔이 되어, **"느린 게 아니라 특정 필터 조합에서만 느린"** 상황이 생긴다. 재현이 어렵다.

여기에 [`admin-page-scope.md`](./admin-page-scope.md) §4가 곱해진다 — 관리자 조회는 기존 인덱스(전부 `member_id` 선두)를 하나도 타지 못하므로, **32가지 조합 전부가 인덱스 없이 도는 상태에서 시작**한다.

### ⑥ `(:p is null OR col = :p)` 패턴의 옵티마이저 방해

JPQL로 동적 쿼리를 흉내 낼 때 흔히 쓰는 트릭인데, 옵티마이저가 조건을 미리 걷어내지 못해 인덱스 선택이 나빠질 수 있다. QueryDSL은 `null`이면 조건을 **생성하지 않는다.**

> 🔶 **여기서 확실한 것과 아닌 것을 구분할 것** — "SQL에 그 조건 형태가 안 나온다"는 확실하다(QueryDSL의 동작이므로). 그러나 **그것이 실행계획·인덱스 선택을 개선한다는 건 증명된 바 없다.** `is null OR` 패턴의 인덱스 영향은 일반적으로 알려진 것일 뿐 이 프로젝트에서 측정한 적이 없다. 완결하려면 관리자 쿼리 구현 후 **필터 조합별 `EXPLAIN` 측정**이 필요하다.

### 정리 — 조립 단계는 고치고, 실행 단계는 못 고친다

| 위험 | QueryDSL로 해결되나 |
|---|:--:|
| ① 조합 폭발 — 테스트 미달 | ❌ 조합 수는 그대로 (문법 사고만 사라짐) |
| ② 기동 검증 상실 | ✅ 컴파일 타임으로 앞당겨짐 |
| ③ `ORDER BY` 인젝션 | 🔶 **부분적** — 안전한 기본형을 주지만 `PathBuilder`로 우회 가능. 화이트리스트는 여전히 구현 책임 |
| ④ 관측·역추적 | 🔶 부분적 |
| ⑤ 조합별 실행 계획 편차 | ❌ **그대로 남음** |
| ⑥ `is null OR` 옵티마이저 방해 | 🔶 **조건 형태는 확실히 회피, 실행계획 개선은 미검증** |

**결론: QueryDSL은 조립 단계의 위험을 고치고 실행 단계의 위험은 손대지 않는다.** 따라서 관리자 페이지의 실제 난이도는 QueryDSL 학습이 아니라 ⑤를 어떻게 다룰지([`admin-page-scope.md`](./admin-page-scope.md) §4의 인덱스 대응 3안)에 있다.

---

## 5. Criteria API 직접 사용 — 배제 ❌ (확정)

§3~§4의 두 시나리오를 `CriteriaBuilder`로도 구현할 수 있지만 권하지 않는다.

| 축 | Criteria API 직접 | QueryDSL |
|---|---|---|
| 코드 길이 | 같은 쿼리에 3~4배 | 짧음 |
| 타입 안정성 | JPA static metamodel processor를 **또** 걸어야 함. 안 걸면 `"logDate"` 문자열이라 오타를 컴파일러가 못 잡음 | Q타입으로 확보 |
| 신규 도입 사례 | 실무에서 드묾 | 표준적 |
| 채용 키워드 가치 | 낮음 | 높음 ([`25-portfolio-strategy.md`](../tasks/25-portfolio-strategy.md) 관점) |

**Criteria가 실제로 등장하는 통로는 하나뿐이다: Spring Data의 `Specification`.** 내부가 Criteria 래퍼이며, 이미 있는 `spring-boot-starter-data-jpa`만으로 동작해 **의존성·빌드 설정 추가가 0**이다. 즉 "Criteria를 쓴다"는 선택지는 실질적으로 "Specification을 쓴다"로 치환된다.

---

## 6. 3안 비교

| 안 | 내용 | 적합 조건 | 비용 | 리스크 |
|---|---|---|---|---|
| **A. 현행 유지** (파생 쿼리 + JPQL) | 아무것도 안 함 | 관리자 페이지를 안 만들 경우 | 0 | 없음. 남은 7개는 이걸로 전부 구현 가능 |
| **B. `JpaSpecificationExecutor`** | 리포지토리에 인터페이스 하나 추가 | 조건 2개 이하, 정렬 고정 | **의존성 0, 빌드 설정 0** | 조건이 늘면 가독성 급락 |
| **C. QueryDSL** | Q타입 생성 + `JPAQueryFactory` | 동적 조건이 있는 모든 조회 | §7 (의존성 4줄) | 쓸 데 없이 넣으면 "이력서용" 이 드러남 |

**판단 기준선 (2026-07-31 개정):**

> WHERE 절이 요청마다 늘었다 줄었다 하는가?
> **예 → C (조건 개수 무관)** · **아니오 → A**

### 6-1. B안(Specification) 배제 결정 (2026-07-31)

당초 이 문서는 "조건 2개 이하면 B, 3개 이상이면 C"라는 개수 기준선을 뒀고, 운동/영상 관리 목록(필터 2개, [`admin-page-scope.md`](./admin-page-scope.md) §3-C)을 B의 후보로 남겨뒀다. **이를 폐기하고 동적 조회는 전부 C로 통일한다.**

**전제 2개가 무너졌다:**

1. **"의존성 0"이 이점이 아니게 됐다.** 그 장점은 QueryDSL이 없을 때만 성립한다. 회원·세션 목록(§3의 A·B) 때문에 QueryDSL은 어차피 들어오므로, 이미 트리에 있는 라이브러리를 안 쓴다고 절약되는 건 없다.
2. **대상 테이블이 3행이다.** 운동/영상 화면이 거는 `exercises`는 `mysql/data.sql`에 스쿼트·런지·플랭크 3개만 시드돼 있고 squat-first 방침상 당분간 늘지 않는다. **어떤 방식을 쓰든 성능 차이가 0**이므로 성능·표현력 논쟁 자체가 성립하지 않는다.

→ 남는 판단축은 **일관성 하나뿐이다.**

**결정 근거 — 조회 방식 종류 수:**

이 프로젝트는 이미 파생 쿼리 / JPQL / native / `JdbcTemplate` 4종을 쓰고 각각 이유가 명확하다(배치는 JdbcTemplate, upsert는 native 등). QueryDSL이 5번째로 들어오는 건 "동적 조건"이라는 **새 이유**가 생겨서라 정당하다. 그러나 Specification을 6번째로 추가하면 그 이유는 **"동적 조건인데 조건이 적어서"** 가 되어, 같은 문제에 도구가 둘이 된다.

"회원 목록은 QueryDSL인데 운동 목록은 왜 Specification인가"의 답이 "조건이 2개라서"인데, 이 규칙은 **코드에 드러나지 않고 문서에만 있다.** 문서에만 있는 규칙은 다음 사람이 지키지 않는다.

**이 결정이 뒤집히는 조건**: QueryDSL 도입 자체가 취소될 때(= 회원·세션 목록을 안 만들기로 할 때). 그 경우 전제 1이 되살아나 B가 정답이 된다.

**주의 — 용어**: `Specification`을 배제한다는 것이 "Criteria API를 아예 안 쓴다"는 뜻은 아니다. Specification은 Criteria 래퍼이므로, 배제 대상은 **그 래퍼를 우리 코드에서 쓰는 것**이다. `CriteriaBuilder` 직접 작성 배제(§5)와는 별개의 결정이다.

---

## 7. 도입 비용 (C안)

**✅ 실측 완료 (2026-07-31)** — 아래 내용은 로컬에서 실제로 적용해 빌드까지 돌려본 결과다. 검증 후 `build.gradle`은 원복했다(§8-1 (a) 방침: 커밋은 기능 PR에서).

### 7-1. 필요한 의존성 — 버전 명시 불필요

**Spring Boot 3.5.16이 QueryDSL 5.1.0을 이미 관리한다**(`./gradlew dependencyManagement`로 확인: `com.querydsl:querydsl-jpa 5.1.0` 등). 따라서 버전을 직접 박을 필요가 없고, 빈 버전 + 분류자 표기로 BOM 관리 버전을 그대로 쓴다.

```gradle
implementation 'com.querydsl:querydsl-jpa::jakarta'
annotationProcessor 'com.querydsl:querydsl-apt::jakarta'
annotationProcessor 'jakarta.annotation:jakarta.annotation-api'
annotationProcessor 'jakarta.persistence:jakarta.persistence-api'
```

`jakarta` 분류자는 여전히 필수다 — 빠뜨리면 `javax` 기반 아티팩트가 딸려온다.

### 7-2. protobuf ↔ Q타입 경로 충돌 — **기우였음**

원래 이 문서는 "생성 소스 경로가 3종으로 늘어 clean/Docker 빌드가 꼬일 수 있다"고 적었으나, **실측 결과 틀렸다.**

- Q타입은 `build/generated/sources/annotationProcessor/java/main` 에 생성된다. 이 경로는 **Gradle java 플러그인이 자동으로 소스셋에 포함**하므로 `sourceSets` 블록(`build.gradle:108-117`)에 **아무것도 추가할 필요가 없다.**
- protobuf가 쓰는 `build/generated/source/**`(단수 `source`)와 **디렉토리 자체가 다르다.** 겹치지 않는다.
- 즉 §6 C안의 실제 변경 면적은 **`dependencies` 블록 4줄뿐**이다.

### 7-3. 검증 내역

| 실행 | 결과 |
|---|---|
| `./gradlew clean compileJava` | BUILD SUCCESSFUL |
| Q타입 생성 확인 | **13개** (`QSession`, `QMember`, `QReport`, `QPoseData`, `QOutboxEvent` 등 전 엔티티) |
| `./gradlew clean bootJar -x test --no-daemon` | BUILD SUCCESSFUL — **로컬 검증** |
| `./gradlew test` (전체) | BUILD SUCCESSFUL — Lombok 애노테이션 프로세서와의 충돌 없음 |

#### ✅ Docker 이미지 빌드 — 완결 조건 해소 (2026-08-04)

초판이 **"남은 미검증"** 으로 남겼던 항목이다. 위 3행은 전부 로컬 Gradle 실행이라 `gradle:jdk21` 이미지 안에서 도는 `backend/Dockerfile` 과 동일한 실행이 아니었고(초기 서술 *"Dockerfile과 동일한 커맨드"* 는 부정확해 외부 리뷰로 정정된 이력이 있다), 이미지의 Gradle 버전 차이·컨테이너 내 네트워크·캐시 조건을 배제하지 못했다.

**도입 시점(2026-08-04)에 실제로 확인했다:**

| 실행 | 결과 |
|---|---|
| `docker build -f backend/Dockerfile backend` | ✅ **성공** — 이미지 656MB |
| `./gradlew clean compileJava` (도입 후 재확인) | ✅ BUILD SUCCESSFUL |
| Q타입 생성 (도입 후 재확인) | ✅ **13개** — §7-3 초판 실측과 동일 |
| `./gradlew test` 전체 (도입 후 재확인) | ✅ BUILD SUCCESSFUL |

**§7-2 의 "기우였음"도 재확인됐다** — `sourceSets` 블록에 아무것도 추가하지 않았고 protobuf 생성 경로와 충돌하지 않았다. 실제 변경 면적은 **`dependencies` 4줄 + 설정 클래스 1개**였다.

> 이로써 [`../tasks/28-remaining-work-plan.md`](../tasks/28-remaining-work-plan.md) §3 에 달려 있던 **"Docker 이미지 빌드 미검증이라 막히면 +1~3h"** 리스크는 소멸했다.

**결론: 도입 비용은 당초 예상보다 낮다.** 버전 확정 불필요, 빌드 스크립트 구조 변경 불필요, 의존성 4줄.

---

## 8. 착수 트리거 (결정으로 대부분 소진됨)

원래 트리거 3개 중 T1이 발동해 §1-1 결정에 이르렀다. 기록용으로 남긴다.

- **T1. 관리자 페이지 착수 결정** — ✅ **발동 (2026-07-31)**
- **T2. 리포트 히스토리 목록 신설** — 🔶 미발동 ([`report-read-path.md`](./report-read-path.md) §0-C ⑬ 보류 중). 발동 시 이미 들어온 QueryDSL을 그대로 재사용하면 됨
- **T3. 스코프 B 루틴 추천 착수** — 🔶 미발동, 2학기 운동 확장 이후 (§3-1)

### 8-1. 남은 선택 — 착수 순서 (열림)

의존성을 언제 넣을지가 아직 안 정해졌다. 두 방식의 트레이드오프:

| 방식 | 장점 | 단점 |
|---|---|---|
| **(a) 의존성 선행** — 지금 `build.gradle`에 넣고 Q타입 생성 + 로컬/Docker 빌드만 검증, 사용처는 나중 | §7의 protobuf ↔ Q타입 생성 경로 충돌(미검증 예측)을 **기능 작업과 분리해서** 확인 가능. 나중에 기능 만들다 빌드 문제로 막히지 않음 | 한동안 **사용처 없는 의존성**이 트리에 남음. 이 상태로 커밋 히스토리가 남으면 "먼저 넣고 나중에 쓸 데 찾은" 모양이 됨 |
| **(b) 기능과 동시** — 필터 항목 정의 → 관리자 목록 API와 같은 PR에서 의존성 추가 | 커밋 히스토리가 "필요해서 넣었다"로 남음. §9 선결 과제를 먼저 처리하게 강제됨 | 빌드 문제가 터지면 기능 작업 도중에 부딪힘 |

> (b)가 이력 관점에서 유리하나, §7 리스크가 미검증이라 (a)의 실익도 있다. **미결.**

---

## 9. 정직 단서 / 미확정

- **관리자 페이지의 필터 항목이 아직 어디에도 정의돼 있지 않다.** §4의 "조건 3~4개"는 일반적인 관리자 화면을 가정한 추정이며, 실제로 필터가 1~2개로 끝나면 **B안으로 충분하고 C안 근거는 사라진다.** 관리자 화면 요구사항 정의가 이 문서의 선결 조건이다.
- §7의 빌드 경로 충돌은 미검증 예측(위 표시 참조).
- 현재 관리자 기능은 `AdminExerciseController:24` 임계값 변경 `PATCH` 1개뿐이며, 목록 조회 API는 존재하지 않는다.
- 운동 영상 관리는 등록(`ExercisesController:33` `POST /exercises/{id}/reference`)만 있고 목록·수정·삭제가 없어, "관리"라 부르려면 목록 조회부터 신설해야 한다 → 이때 §4의 판단이 그대로 적용된다.

---

## 10. 부수 발견 — 세트 표기 불일치 (이 문서 스코프 밖)

§3의 "운동 세트 자동 구분"을 확인하다 발견. 세트 기능이 없어 하드코딩해둔 자리가 두 곳인데 값이 다르다:

- `ReportService.java:96` → `"1세트 x %d회"`
- `SessionService.java:408` → `"0세트 x %d회"`

같은 세션인데 화면에 따라 "0세트"와 "1세트"로 다르게 표시됐다. 동적 쿼리와 무관한 별개 결함이라 분리 등록 → **[#69](https://github.com/Shadowfit/init/issues/69)**.

**해소(2026-07-31)**: `"1세트"`로 통일 — 이는 새 선택이 아니라 [`report-aggregation.md`](./report-aggregation.md) 결정 5(`setInfo = "1세트 x {totalReps}회" 고정`)로의 복귀였고, `SessionService`의 `"0세트"`가 그 결정에서 이탈한 드리프트였다. 포맷은 `global/util/SetSummaryFormatter`로 단일화해 BE-09 착수 시 한 곳만 고치면 되게 했고, `total_reps`가 nullable인 점(`schema.sql:71`)에 대한 null 방어도 포매터 안으로 흡수했다.

---

## 11. 결정 후 남은 작업

1. ~~관리자 화면 필터 항목 정의~~ — 🟢 **초안 제출: [`admin-page-scope.md`](./admin-page-scope.md)** (2026-07-31). 회원 목록 5개·세션 목록 4개로 §1-1 재검토 조건을 벗어나 **QueryDSL 결정 유효**. 단 초안 자체는 미확정이며, 같은 문서 §4에서 **관리자 조회에 기존 인덱스가 하나도 안 듣는다**는 더 큰 문제가 드러났다(QueryDSL로 해결되지 않음).
2. ~~착수 순서 결정~~ — ✅ **(a) 로컬 검증 선행, 커밋은 기능 PR에서**로 확정(2026-07-31 confirm).
3. ~~QueryDSL 버전 확인~~ — ✅ 해소. Boot 3.5.16이 5.1.0 관리, 버전 명시 불필요 (§7-1).
4. ~~빌드 경로 공존 검증~~ — ✅ 해소. 충돌 없음, `sourceSets` 변경 불필요 (§7-2·§7-3).
5. 적용 시 **§4-1 범위 구분 준수** — 목록/검색만 QueryDSL, 통계 위젯·단건 조회는 기존 방식 유지.
6. **Criteria API 직접 사용은 하지 않는다** (§5, 확정).
7. **`Specification` 병용도 하지 않는다** — 동적 조회는 조건 개수와 무관하게 QueryDSL로 통일 (§6-1, 2026-07-31 확정).
