# HTTP 읽기 4엔드포인트 EXPLAIN — 로컬 (2026-08-24)

설계: [`http-read-p99-cause-attribution.md`](../../../docs/decisions/http-read-p99-cause-attribution.md) §2-2 (「B안: 로컬 EXPLAIN 먼저」)
선행: [로컬판](../http-read-p99-2026-08-23/README.md) — `calendar`가 p50은 둘째로 싼데 p99/p50 배수(5.1x)는 넷 중 제일 나빴던 역설을 못 가른 채 남겼다
쿼리: [`explain.sql`](./explain.sql) · MySQL 8.0.46(도커 로컬), DB `shadowfit`, `member_id=1024`(2026-08-23 로컬판이 시딩한 k6load 계정, 세션 1,000)

---

## 0. 한 줄

**넷 중 셋(daily·calendar 본체·weekly)은 가볍다. `calendar`가 화면에 안 보이는 2번째 쿼리를
하나 더 쏘는데, 그게 나머지 셋을 합친 것보다 무겁다** — 회원 전체 이력(1,040행)을 읽고서야
100일 창으로 거른다.

🔴 이 판은 **EC2·부하 아래 실측이 아니다.** 로컬 도커 EXPLAIN ANALYZE의 "examined rows"만
읽는다 — 시간(ms)은 캐시가 다 덥혀진 로컬 값이라 인용하지 않는다.

---

## 1. 결과 — 엔드포인트별 실제 스캔 행수

| 엔드포인트 | 쿼리(리포지토리 메서드) | 쓰인 인덱스 | 실제 examined rows | 결과 행 |
|---|---|---|--:|--:|
| `daily` | `findByMemberIdAndStartTimeBetween`(1일) | `idx_session_starttime_member` | **3** | 3 |
| `calendar` 본체 | 〃 (1달) | `idx_session_starttime_member` | **85** | 85 |
| `weekly-summary` | `findWeeklySessionsWithExercise`(1주 + exercise JOIN) | `idx_session_member_exercise_status_start`(exercises 3행을 드라이빙, nested loop) | **19**(6.33×3 loops) | 19 |
| `calendar` **2차 쿼리** | `findDistinctActiveDates`(100일, `calculateConsecutiveDays`가 호출) | `idx_session_member_status_start` — `member_id` 등치만 걸리고 `status`·`start_time`은 못 건다 | **1,040** | 101(dedup 후) |

`calendar`는 컨트롤러 한 번 호출에 **본체(85) + 2차(1,040) = 1,125행**을 읽는다 — 넷 중
가장 가벼워 보이는 엔드포인트가 실제로는 가장 무겁다.

---

## 2. 왜 2차 쿼리만 이렇게 비싼가

`findDistinctActiveDates`([`SessionRepository.java:47-51`](../../../backend/src/main/java/com/shadowfit/repository/exercise/SessionRepository.java))는
`member_id`·`start_time` 둘 다로 거르는데, 정확히 그 조합(`member_id, start_time`)을 겨눈
2컬럼 인덱스가 지금 스키마에 **없다**. 후보 둘 다 절반만 맞는다:

- `idx_session_member_status_start(member_id, status, start_time)` — `status` 조건이 없어서
  `start_time`을 seek 못 하고 **`member_id`의 전 이력을 훑은 뒤** `start_time`으로 거른다
  (`Covering index lookup ... rows=1040` → `Filter ... rows=277` → `dedup rows=101`)
- `idx_session_starttime_member(start_time, member_id)`로 강제하면(§`explain.sql` 3-b) 정반대로
  **날짜 범위 안의 전 회원 행(2,099)**을 훑은 뒤 `member_id`로 거른다 — 오히려 더 나쁘다

**어느 인덱스를 골라도 100일 창의 실제 해당 행(277)보다 많이 읽는다.** 진짜 해법은
`(member_id, start_time)` 2컬럼 인덱스인데, 그건 [#110](https://github.com/Shadowfit/init/issues/110)/
[`session-index-composition.md`](../../../docs/decisions/session-index-composition.md)가
2026-08-07에 **`GET /sessions/active` 를 위해 이미 뗀 것**이다(그 문서 §1-2 대가 분석 참고).

🔴 **그 문서는 `findDistinctActiveDates`를 다루지 않는다** — 검색해도 "연속"·"calendar"·
`ConsecutiveDays`·`findDistinctActiveDates` 언급이 0건이다. 대가 분석이 "주간 리포트가
14→20행으로 는다(팬아웃 500 기준, 절대 0.03ms)"로 계산한 건 `findWeeklySessionsWithExercise`
하나였고, **이 2차 쿼리는 그 계산에 들어간 적이 없다.**

---

## 3. 스케일 — 이 대가는 회원의 "100일 안" 세션 수가 아니라 "전체" 세션 수를 따라간다

`idx_session_member_status_start`로는 `member_id` 등치 이후 `status`·`start_time` 둘 다 못 거르므로,
읽는 행수는 **그 회원이 지금까지 쌓은 전체 세션 수**다. 100일 창을 아무리 좁혀도 안 줄어든다.

🔴 `member_id=1024`는 이 로컬판이 만든 **합성 계정**(1,000세션, 1년 균등 분포)이다 — 실제
회원 분포에서 이 정도로 누적된 회원이 흔한지는 **안 쟀다**. [`session-index-composition.md`](../../../docs/decisions/session-index-composition.md)가
쓴 가정(DAU 1,000 → 1년차 팬아웃 156~365)을 그대로 빌리면 활성 회원 상당수가 이미 이 스캔
크기(수백 행) 근처에 있다는 뜻이지만, **이건 그 문서의 가정을 재인용한 것이지 이 판이 새로 잰
분포가 아니다.**

---

## 4. 이 판이 확정한 것과 안 한 것

- ✅ **넷 중 어느 것이 구조적으로 제일 무거운지는 확정** — `calendar`의 2차 쿼리, 다른 셋과
  자릿수가 다르다(1,040 vs 3~85)
- ✅ **원인 후보 「쿼리 모양」이 이 엔드포인트에서 실체를 얻었다** — #110 대가 분석의 사각지대
- 🔴 **로컬판의 역설(calendar p99/p50 5.1x)을 이 판이 "설명했다"고 말하면 안 된다** — 이건
  구조적 사실(examined rows)이지, 그게 실제로 부하 아래 p99에 얼마나 기여하는지는 **미측정**이다.
  캐시가 덥혀진 로컬 EXPLAIN 시간은 0.1~1ms대라 그 자체로는 "느리다"의 증거가 아니다
- 🔴 **다른 두 원인 후보(커넥션 풀 대기 · 이웃 프로세스)는 이 판이 안 건드린다** — 여전히
  [`http-read-p99-cause-attribution.md`](../../../docs/decisions/http-read-p99-cause-attribution.md) §2-1·§2-3 몫
- 🔴 **실제 분포(합성 계정 1,000세션이 대표적인가)는 미검증**

---

## 5. 다음 수 — 이슈로 분리, 그리고 고쳤다

`findDistinctActiveDates`가 100일 창을 요청해놓고 회원 전체 이력을 읽는 건 실험과 무관하게
남는 제품 코드 결함이라 별도 이슈([#541](https://github.com/Shadowfit/init/issues/541))로 냈다.
이 판은 그 이슈의 재현 근거다.

### 5-1. 고침 — 스키마 변경 없이, `status IN (전체값)` 하나로

[`SessionRepository.java`](../../../backend/src/main/java/com/shadowfit/repository/exercise/SessionRepository.java)의
JPQL에 `AND s.status IN :statuses`를 추가하고, 호출부
([`SessionService.java:calculateConsecutiveDays`](../../../backend/src/main/java/com/shadowfit/service/Exercise/SessionService.java))가
`List.of(Status.values())`(항상 전체값)를 넘긴다. **결과 집합은 안 바뀐다** — 필터링 의미가
아니라, `idx_session_member_status_start(member_id, status, start_time)`가 `status` 등치
없이는 `start_time`을 seek 못 하던 것을 우회하는 용도다.

§2의 「인덱스 둘 다 100일 창보다 많이 읽는다」 문제 자체가 없어진다 — `status IN` 이 있으면
MySQL이 상태값별 range scan 4개로 쪼개 각각 `start_time`을 seek한다([`explain.sql`](./explain.sql) #5,
결과 [`fix_verify.txt`](./fix_verify.txt)):

```
고치기 전: Covering index lookup (member_id=1024)                    rows=1,040
고친 후:   Covering index range scan × 4(status별) + start_time seek  rows=277   ← 실제 해당 행과 같다
```

**새 인덱스도 마이그레이션도 없다** — #110이 5개→4개로 줄인 인덱스 수를 그대로 유지한다.
`./gradlew compileJava`·`./gradlew test --tests SessionServiceTest` 통과 확인.

🔴 **이것도 로컬 EXPLAIN이다** — 부하 아래 p99에 실제로 얼마나 반영되는지는 여전히 미측정.
`Status`에 새 값이 추가되면 `Status.values()`를 그대로 쓰므로 자동 반영된다(하드코딩 아님).
