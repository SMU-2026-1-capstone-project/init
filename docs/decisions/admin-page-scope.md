# 관리자 페이지 범위 · 필터 항목 정의 (초안)

상태: **혼합 — 아래 두 축을 분리해서 읽을 것**
- **화면 범위·필터 항목**(§2, §3의 필터 목록) = 🔶 **제안(초안), 미확정.** confirm 전이며, 확정 시 필터 개수가 달라지면 [`querydsl-adoption.md`](./querydsl-adoption.md) §1-1 결정이 재검토 대상이 된다.
- **동적 조회에 QueryDSL을 쓴다는 적용 원칙**(§3-C, §7의 방식 열) = ✅ **확정(2026-07-31)**, 근거는 [`querydsl-adoption.md`](./querydsl-adoption.md) §6-1. 이 원칙은 필터 개수와 무관하다.

작성: 2026-07-31
목적: [`querydsl-adoption.md`](./querydsl-adoption.md) §9의 **유일한 선결 과제**("관리자 화면 필터 항목이 정의돼 있지 않다") 해소. 필터 개수가 확정돼야 QueryDSL 채택 결정(§1-1)이 유효한지 판정된다.
연관: [`querydsl-adoption.md`](./querydsl-adoption.md), [`report-read-path.md`](./report-read-path.md)(읽기 경로·인덱스), [`../tasks/27-implementation-gaps.md`](../tasks/27-implementation-gaps.md)

> 🟢=제안, 🔶=열림, ❌=스코프 밖. 결정 ✅ 는 사용자 confirm 후.

---

## 0. 한 줄 요약

관리자 화면 3개(회원·세션·운동/영상)를 제안하며, 그중 **회원 목록·세션 목록이 필터 4개 이상**이라 QueryDSL 채택 근거가 성립한다. 다만 이 작업의 진짜 난점은 쿼리 빌더가 아니라 **기존 인덱스가 전부 `member_id` 선두라 관리자 조회에 하나도 듣지 않는다는 것**(§4)이다.

---

## 1. 현재 관리자 기능 — 사실상 없음

| 있는 것 | 없는 것 |
|---|---|
| `AdminExerciseController:24` — `PATCH /admin/exercises/{id}/thresholds` (임계값 변경, `@PreAuthorize("hasRole('ADMIN')")`) | 목록 조회 API **전무** |
| `UserRole.ADMIN` 열거값, 시큐리티 배선 | 관리자 화면용 DTO·서비스 |
| `ExercisesController:33` — `POST /exercises/{id}/reference`(영상 등록) | 영상 목록·수정·삭제 |

즉 **관리자 "페이지"는 0에서 시작**한다. 권한 배선(`ROLE_ADMIN`)만 이미 있다.

---

## 2. 제안 범위 — 목록 3개 + 대시보드 1개

| # | 화면 | 우선순위 | 근거 |
|---|---|:--:|---|
| A | **회원 목록** | 🟢 1순위 | 필터·정렬 조합이 가장 많아 QueryDSL 근거가 가장 셈 |
| B | **세션 목록** | 🟢 2순위 | 운영 관점에서 실사용 가치(실패·타임아웃 세션 추적) |
| C | **운동/영상 관리** | 🟢 3순위 | 등록 API만 있음 — **목록·수정·삭제 3개를 추가해야** CRUD가 완성된다 |
| D | 대시보드 통계 위젯 | 🟢 마지막 | 조건 고정 집계 — **QueryDSL 대상 아님**([`querydsl-adoption.md`](./querydsl-adoption.md) §4-1) |

❌ **스코프 밖(제안)**: 관리자 계정 관리(가입·권한 부여), 감사 로그(audit log), 공지/QnA 관리, 통계 CSV 내보내기. 전부 "있으면 좋지만 없어도 화면이 성립"한다.

---

## 3. 화면별 필터·정렬 후보

필드는 전부 **기존 엔티티에 이미 있는 것**으로만 뽑았다(신규 컬럼 없음).

### A. 회원 목록 — 필터 5

| 필터 | 타입 | 근거 필드 |
|---|---|---|
| 검색어 (username / email) | 부분일치 | `Member.java:25,28` |
| 페르소나 | `SelectedPersona` enum | `Member.java:42` |
| 운동 레벨 | `WorkoutLevel` enum | `Member.java:71` |
| 온보딩 완료 여부 | boolean | `Member.java:75` |
| 가입일 범위 | 기간 | `Member.java:55` `createdAt` |

**정렬 후보**: 가입일(기본, 최신순) / username / (파생) 최근 운동일
**판정**: 필터 5개 → 부분집합 2⁵ = 32. **QueryDSL 근거 성립.**

### B. 세션 목록 — 필터 4

| 필터 | 타입 | 근거 필드 |
|---|---|---|
| 상태 | `Status` enum (IN_PROGRESS/COMPLETED/CANCELLED/FAILED) | `Session.java:66` |
| 운동 종목 | FK | `Session.java:36` |
| 기간 (시작시각) | 기간 | `Session.java:42` |
| 회원 검색어 | 부분일치(조인) | `Member.java:25` |

🔶 **열림**: 싱크로율 구간 필터(`avgSyncRate`)를 넣을지. 운영상 "저품질 세션 추적"에 쓸모는 있으나 필수는 아님.
**정렬 후보**: 시작시각(기본, 최신순) / 싱크로율 / 반복수
**판정**: 필터 4개(+선택 1) → **QueryDSL 근거 성립.**

### C. 운동/영상 관리 — 필터 2

| 필터 | 타입 | 근거 필드 |
|---|---|---|
| 카테고리 | `ExerciseCategory` enum | `Exercise.java:27` |
| 검색어 (운동명) | 부분일치 | `Exercise.java:23` |

**판정**: 필터 2개지만 **QueryDSL로 통일한다** (✅ 2026-07-31 확정, 근거는 [`querydsl-adoption.md`](./querydsl-adoption.md) §6-1).

당초 이 자리를 `Specification` 후보로 뒀으나 폐기했다. 이 화면이 거는 `exercises` 테이블은 `mysql/data.sql`에 **3행(스쿼트·런지·플랭크)** 만 시드돼 있고 squat-first 방침상 당분간 늘지 않는다 → 어떤 방식을 쓰든 성능 차이가 0이라 판단축이 일관성만 남는다. 동적 조회 도구를 둘로 늘리지 않는다.

### D. 대시보드 통계 위젯 — 필터 0

오늘 세션 수 / 상태별 세션 분포 / 평균 싱크로율 / 신규 가입자 수 / 활성 회원 수. **전부 조건 고정 집계 → JPQL 또는 native.** QueryDSL로 감싸지 말 것.

---

## 4. ⚠️ 진짜 난점 — 기존 인덱스가 관리자 조회에 하나도 안 듣는다

`mysql/schema.sql`의 인덱스를 전부 확인한 결과:

| 인덱스 | 선두 컬럼 |
|---|---|
| `idx_session_member_starttime (member_id, start_time)` | `member_id` |
| `idx_session_member_exercise_status_start (member_id, exercise_id, status, start_time)` | `member_id` |
| `idx_session_member_status (member_id, status)` | `member_id` |
| `idx_session_timestamp (session_id, timestamp_sec)` | `session_id` |
| `users` 테이블 | **보조 인덱스 없음** (PK + username/email UNIQUE만) |

**모든 세션 인덱스가 `member_id` 선두다.** 이는 지금까지의 모든 조회가 "내 데이터"였기 때문에 올바른 설계였다. 그런데 관리자 목록은 **`member_id` 조건 없이 전체를 훑으며 상태·기간으로 거른다** → 위 복합 인덱스는 선두 컬럼이 안 맞아 **전혀 타지 않는다.** 회원 목록도 `users`에 보조 인덱스가 없어 정렬·필터가 전부 풀스캔 + filesort가 된다.

> 이건 QueryDSL로 해결되는 문제가 **아니다.** 쿼리 빌더는 SQL을 조립할 뿐 실행 계획을 바꾸지 않는다. ([`querydsl-adoption.md`](./querydsl-adoption.md) §4-2 ⑤ — 동적 조립 위험 중 QueryDSL이 못 고치는 항목)

**게다가 조합 수만큼 곱해진다.** 회원 목록 필터 5개면 가능한 쿼리가 32가지이고, 각각 실행 계획이 다르다. 인덱스가 없으면 32가지 전부가 인덱스 없이 돌고, 인덱스를 추가해도 **일부 조합만** 타게 된다 — "어떤 필터 조합에서만 느리다"는 형태의 문제가 나온다. 아래 대응안을 고를 때 "모든 조합을 커버하는 인덱스는 없다"를 전제로 판단할 것.

**대응 선택지 (🔶 열림)**

| 안 | 내용 | 트레이드오프 |
|---|---|---|
| ㄱ. 관리자 전용 인덱스 추가 | `(status, start_time)`, `users(created_at)` 등 | 쓰기 경로에 인덱스 유지 비용 추가. 세션 INSERT는 이 프로젝트의 **핵심 쓰기 축**이라 공짜가 아님 |
| ㄴ. 인덱스 없이 감수 | 관리자 트래픽은 극소(관리자 몇 명) | 데이터가 쌓이면 관리자 화면만 느려짐. 운영상 허용 가능 여부 판단 필요 |
| ㄷ. 목록을 기간 필수로 제한 | "최근 30일" 같은 기본 기간 강제 | **반환 행 수만 줄어들 뿐 스캔 범위는 그대로다** — `start_time` 선두 인덱스가 없으면 전체를 훑은 뒤 기간으로 거른다. 정렬·전송 비용은 줄지만 근본 해결이 아니라 ㄱ의 보조책 (초기 서술은 "스캔 범위를 구조적으로 제한"이었으나 오류 — 외부 리뷰로 정정) |

**포폴 관점 메모**: 이 항목은 "동일 테이블에 **읽기 주체가 둘**(사용자 축 vs 관리자 축)이면 인덱스 전략이 갈린다"는 서사가 된다. QueryDSL 도입보다 이쪽이 면접에서 훨씬 무겁다.

---

## 5. 페이징 방식 — 관리자 화면은 keyset이 정답이 아닐 수 있음

[`querydsl-adoption.md`](./querydsl-adoption.md) §4에서 keyset 페이징을 QueryDSL의 강점 근거로 들었으나, **관리자 화면에 한정하면 재고가 필요하다.**

| 방식 | 관리자 화면 적합성 |
|---|---|
| **offset 페이징** | 관리자는 "5페이지로 점프", "전체 1,234건 중" 같은 **총건수·임의 페이지 이동**을 기대한다. keyset은 이걸 구조적으로 못 한다 |
| **keyset 페이징** | 무한 스크롤·피드에 적합. 관리자 테이블 UI와는 궁합이 나쁨 |

🔶 **제안**: 관리자 목록은 **offset + `COUNT(*)`** 로 가고, keyset은 §T2(리포트 히스토리, 모바일 무한스크롤)에서 다룬다. "관리자 = keyset"을 억지로 붙이면 UI 요구와 싸우게 된다.

> 정직 단서: 이 판단은 관리자 UI가 **테이블 + 페이지네이션**이라는 전제 위에 있다. 무한 스크롤로 만들 거라면 뒤집힌다.

---

## 6. 보안·권한 (제안)

- 전 엔드포인트 `@PreAuthorize("hasRole('ADMIN')")` — `AdminExerciseController:18` 패턴 그대로.
- 경로는 `/admin/**` 로 통일.
- 🔶 **열림**: 회원 목록에 이메일 전체를 노출할지(마스킹 여부). 관리자라도 최소 노출 원칙을 적용할지는 판단 사항.
- 세션 목록에서 개별 회원의 `pose_data` 원본까지 열람 가능하게 할지 — ❌ 제안: 하지 말 것(용량·프라이버시 대비 실익 없음).

---

## 7. 판정 — QueryDSL 결정은 유효한가

[`querydsl-adoption.md`](./querydsl-adoption.md) §1-1은 "필터가 1~2개로 확정되면 결정 재검토"를 조건으로 달아뒀다.

**본 초안 기준: 회원 목록 5개, 세션 목록 4개 → 조건 충족. QueryDSL 채택 결정은 유효하다.**

적용 경계(✅ 2026-07-31 확정):

| 대상 | 방식 |
|---|---|
| A 회원 목록 · B 세션 목록 · **C 운동/영상 목록** | **QueryDSL** — 동적 조건이 있으면 개수 무관 통일 |
| A·B·C의 총건수 `COUNT` 쿼리 | **QueryDSL** — 목록과 필터 조건을 재사용해야 함(따로 짜면 건수와 목록이 어긋남) |
| D 대시보드 통계 위젯 | JPQL / native — 조건 고정 집계 |
| 관리자 단건 상세 조회 | 파생 쿼리 — 조건 1개 |
| `Specification` | ❌ 사용하지 않음 ([`querydsl-adoption.md`](./querydsl-adoption.md) §6-1) |

---

## 8. 남은 열린 질문

1. 화면 A·B·C를 **전부** 만들 것인가, A만 만들고 볼 것인가 (범위)
2. §4의 인덱스 대응 3안 중 무엇 (ㄱ 추가 / ㄴ 감수 / ㄷ 기간 강제) — **이 문서에서 가장 무거운 미결.** QueryDSL로 해결되지 않는 유일한 축이라([`querydsl-adoption.md`](./querydsl-adoption.md) §4-2 ⑤), 관리자 페이지 작업의 실제 난이도가 여기 걸려 있다
3. §5 페이징 — offset 확정해도 되는가 (관리자 UI 형태 전제)
4. §3-B 싱크로율 구간 필터 포함 여부
5. §6 이메일 마스킹 여부
6. 관리자 화면의 **프론트**를 만들 것인가, API만 만들고 Swagger로 시연할 것인가 — 2학기 일정([`../tasks/24-semester2-plan.md`](../tasks/24-semester2-plan.md))과 직결
