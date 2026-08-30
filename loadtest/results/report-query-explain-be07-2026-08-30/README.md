# BE-07(패턴 분석) 3 endpoint EXPLAIN — 로컬 (2026-08-30)

설계: [`pattern-analysis-implementation.md`](../../../docs/decisions/pattern-analysis-implementation.md) §3 (세션5)
쿼리: [`explain.sql`](./explain.sql) · MySQL 8.0(도커 로컬), DB `shadowfit`, `member_id=1`(`mysql/dev-seed.sql`이 심은 로드테스트용 합성 계정 — 세션 1,680, 2026-05-28~2026-08-29, COMPLETED 1,226 / FAILED 454)

---

## 0. 한 줄

**걱정했던 "회원 전체 이력 스캔"은 실제로 일어나지 않는다.** `findStartTimesByMemberAndRange`·`findIntensitySamplesByMemberAndRange` 둘 다 `status` 등치 조건이 없어 `idx_session_member_status_start(member_id, status, start_time)`로 `start_time`을 seek 못 할 거라 예상했는데, 실측(`Handler_read_next`)은 세 쿼리 다 **읽은 행 수 = 실제 창 안의 행 수**였다.

🔴 이 판은 로컬 도커의 **핸들러 카운터 실측**이지 EC2·부하 아래 실측이 아니다. 시간(ms)은 인용하지 않는다.

---

## 1. 배경 — 왜 걱정했나

세션 1~4에서 추가된 두 쿼리는 이전 세션들의 `findDistinctActiveDates` 관례([#541](https://github.com/Shadowfit/init/issues/541), [`report-query-explain-r14-2026-08-24`](../report-query-explain-r14-2026-08-24/README.md))와 다르게 **`status` 조건이 없다**:

- `findStartTimesByMemberAndRange`(periodicity) — `member_id = ? AND start_time BETWEEN ?`
- `findIntensitySamplesByMemberAndRange`(intensity-trend) — `member_id = ? AND avg_sync_rate IS NOT NULL AND start_time BETWEEN ?`

r14 판이 확정한 규칙은 "`idx_session_member_status_start`는 `status` 등치가 있어야 `start_time`을 seek 할 수 있고, 없으면 회원 전체 이력을 읽는다"였다. 그 규칙대로면 이 두 쿼리도 `member_id=1`의 전체 1,680행을 읽어야 정상이다 — 이 문서는 그 예상을 실측으로 검증한다.

---

## 2. 결과 — 실제 스캔 행수 (Handler_read_next, `FLUSH STATUS` 리셋 후 단독 실행)

| 쿼리 | 창 | 실제 matching rows | 실제 examined rows(`Handler_read_next`) |
|---|---|--:|--:|
| periodicity (최근 4주 tail) | 2026-08-02~08-30 | 680 | **680** |
| intensity-trend (최근 4주 tail) | 2026-08-03~08-30 | 680 | **680** |
| consistency(status=COMPLETED만) | 2026-08-03~08-30 | 226 | **226** |
| periodicity 재현 — 대량 배치 앞머리 | 2026-05-28~06-04 | 1,000 | **1,000** |
| intensity-trend 재현 — 대량 배치 앞머리 | 2026-05-28~06-04 | 1,000 | **1,000** |

다섯 경우 모두 `examined == actual` — 회원 전체 이력(1,680)을 읽은 사례가 없다.

정적 `EXPLAIN`(ANALYZE 아닌 쪽) FORMAT=JSON은 intensity-trend 쿼리의 `rows_examined_per_scan`을 **1,680**(=회원 전체)으로 추정했다 — 이건 옵티마이저의 비관적 추정치일 뿐, 실제 핸들러 카운터와 다르다.

`EXPLAIN ANALYZE`의 access 방식 표기도 둘이 다르다:
- periodicity → `Covering index skip scan` (라벨이 명시적으로 붙음)
- intensity-trend → `Index lookup ... with index condition`(ICP) — "skip scan" 라벨은 안 붙지만 실측 결과는 skip scan과 동일

---

## 3. 왜 예상이 틀렸나 (추정)

`status` 컬럼은 이 회원 기준 값이 2종류(COMPLETED/FAILED)뿐이고, enum 전체로도 4종류다(`IN_PROGRESS,COMPLETED,CANCELLED,FAILED`). MySQL 8.0 옵티마이저는 이렇게 **선두 컬럼이 몇 안 되는 값**일 때, 등치 조건이 없어도 값별로 range를 쪼개 각각 `start_time`을 seek하는 최적화를 적용한다 — 이미 `countStartedBetween`(admin-page-scope.md §4-5)에서 확인된 것과 같은 메커니즘이다. 여기서는 그게 명시적 "skip scan" 라벨 없이도(intensity-trend 케이스) 실질적으로 같은 효과를 냈다.

r14 판의 규칙("등치 없으면 회원 전체를 읽는다")이 틀렸다는 뜻은 아니다 — 그 판의 `findDistinctActiveDates`는 애초에 `status` 조건 자체가 SQL에 없었고(현재는 #541 픽스로 `IN` 절이 붙어 있음), 이번 두 쿼리는 처음부터 `status` 조건이 없는 채로 설계된 것이 다르다. 두 상황이 옵티마이저에게 다르게 보였을 가능성, 또는 MySQL 버전/통계 차이일 가능성 둘 다 배제 못 한다 — **이 판은 "왜"까지 확정하지 않는다, "그 결과가 실제로 안전한지"만 확정한다.**

---

## 4. 이 판이 확정한 것과 안 한 것

- ✅ **이 데이터 모양에서는 두 쿼리 다 창 밖의 행을 읽지 않는다** — 재현 시도(대량 배치 앞머리, 1,000행) 포함 5/5 일치
- ✅ **세션5가 우려했던 "새 인덱스 필요" 가설은 이 실측으로는 기각** — 현재 `idx_session_member_status_start`로 충분
- 🔴 **`member_id=1`은 로드테스트용 합성 계정**([[project_synthetic_data_distribution_limit]]) — status 분포가 회원당 2종류뿐이고, 5~8월 중 6~7월이 통째로 비어 있다(두 배치만 존재). 실제 회원 분포(4개 status가 고르게 섞이는 경우 등)에서도 같은 결과가 나오는지는 **안 쟀다**
- 🔴 **부하 아래 실측이 아니다** — 로컬 도커, 캐시 덥혀진 상태의 핸들러 카운터일 뿐
- 🔴 **`EXPLAIN ANALYZE`의 access 라벨과 실제 효율이 항상 일치하지 않는다는 것도 확인됐다** — intensity-trend는 "skip scan" 라벨이 없는데도 skip scan과 동일한 실측 결과를 냈다. 라벨만 보고 판단하면 오판할 수 있다는 사례로 남긴다

---

## 5. 다음 수

세션5의 EXPLAIN 검증은 이 결과로 종료 — 별도 인덱스·마이그레이션 불필요. §4의 🔴 두 항목(합성 데이터 분포 한계, 라벨-실효율 불일치)은 세션9(EXPLAIN 결과 + 응답시간 실측 문서화) 때 정식 포폴 카드에 그대로 옮겨 적을 것.
