# Decision: 운동 추천 알고리즘 (BE-08)

상태: **✅ 구현 완료 (2026-08-30) — PR #625.** §9 전 항목 결정·구현 완료. §10 실측대로 신규 인덱스·캐싱 없이 순수 함수로 구현.
작성: 2026-05-31
배경: 리포트 기능이 거의 순수 GET(읽기)이라 "데이터를 소비하는" 기능이 없다는 문제 제기 (2026-05-31 사용자). 과거 운동 기록을 읽어 다음 운동을 추천하면 리포트 데이터가 소비되어 백엔드 깊이가 올라감. 이것이 [`../tasks/22-backend-tasks-detail.md`](../tasks/22-backend-tasks-detail.md) 의 BE-08. 단 BE-08 설계("3개 운동 루틴")가 squat-first 제약과 충돌 → 스코프부터 정리 필요.
연관: [`../tasks/22-backend-tasks-detail.md`](../tasks/22-backend-tasks-detail.md) BE-07·08, [`../12-persona-difficulty.md`](../12-persona-difficulty.md), [`./load-test-strategy.md`](./load-test-strategy.md), [`../tasks/25-portfolio-strategy.md`](../tasks/25-portfolio-strategy.md)

> 결정 ✅ 는 사용자 confirm 후 박제. 본 문서는 분석·권고.

---

## 1. 배경 / 문제

- 리포트(`GET /reports/sessions/{id}`)는 읽기 전용 집계 → "CRUD 인상" 약점.
- **과거 기록 → 다음 운동 추천**은 데이터를 *소비*하는 compute/write 경로를 추가 → CRUD 이상의 깊이.
- BE-08 기존 설계: `GET /recommendations/today`(3개 운동 루틴) + `GET /recommendations/weekly`(1주 플랜), 근거 한 줄.
- **알고리즘 미설계** — BE-08 자체가 본 문서 작성을 선결 조건으로 명시.

---

## 2. 핵심 제약 — squat-first 와 정면충돌 ⚠️

| 사실 | 출처 |
|------|------|
| 운동 3개 시드됨: 스쿼트(1)·런지(2)·플랭크(3) | [`mysql/data.sql`](../../mysql/data.sql) L112-119 |
| **실제 분석 가능한 건 스쿼트뿐** (AI analyzer·기준좌표 스쿼트 전용) | `project-squat-first` 메모리 |

→ BE-08 의 "**3개 운동 루틴 추천**"은 지금 만들면 **할 수 없는 운동(런지·플랭크)을 추천** → 시연 시 빈 껍데기. 면접에서 "스쿼트밖에 안 되잖아요?" 에 막힘.

→ **결론: 운동 *종목* 추천은 squat-first 에서 성립 불가.** 종목 추천은 2학기 운동 확장 후로 미뤄야 함.

---

## 3. 스코프 분리 (권고)

| 스코프 | 내용 | 가능 시점 | squat-first 충돌 |
|--------|------|----------|:--:|
| **A. 강도·볼륨 추천** (권고: now) | 다음 세션의 난이도·반복수·세트·휴식 추천 (progressive overload) | **지금** (스쿼트 단일로 성립) | 없음 |
| B. 운동 종목 루틴 추천 | 오늘 할 운동 3개 + 1주 플랜 | 2학기 운동 확장 후 | 지금은 불가 |

→ **A 를 now 트랙으로, B 를 BE-08 원안(2학기)으로 분리.** 본 문서는 A 중심으로 설계.

---

## 4. 입력 데이터 매핑 (A — 이미 존재하는 것)

강도 추천에 필요한 데이터는 신규 수집 없이 기존 필드로 충족:

| 입력 | 위치 | 비고 |
|------|------|------|
| 최근 N 세션 난이도 | [`Session.difficultyLevel`](../../backend/src/main/java/com/shadowfit/model/exercise/Session.java) | |
| 평균 싱크로율 | `Session.avgSyncRate` | 수행 품질 신호 |
| 총 반복수 | `Session.totalReps` | 볼륨 신호 |
| 세션 상태 | `Session.status` (COMPLETED/FAILED) | 완수 여부 |
| 직전 세션 | `SessionRepository.findFirstByMemberIdAndExerciseIdAndStatusOrderByStartTimeDesc` | **이미 존재** (ReportService 사용 중) |
| 이전 대비 비교 | ReportService `buildComparisonWithPrevious` (syncRate/분/칼로리 diff) | **이미 계산 중** |
| 콜드스타트(이력 0) | `Member.workoutLevel`, `Member.selectedPersona` | 첫 추천 기본값 |
| 난이도 파라미터(휴식 등) | [`../12-persona-difficulty.md`](../12-persona-difficulty.md) `restTimeSec` 등 | 페르소나×난이도 |

---

## 5. 알고리즘 후보

| 후보 | 적합성 | 비고 |
|------|------|------|
| **규칙 기반** ⭐ (권고) | 🔴 강 | 설명 가능("근거 한 줄"), 데이터 적어도 동작, 방어 쉬움 |
| 협업 필터링 (CF) | ❌ | 사용자 ~0명에 CF = **인위적**. 역효과 |
| LLM 호출 | 🟡 | 종합 리포트(BE-03)와 별개. 강도 추천에 LLM 은 과함·비결정적 |

→ **규칙 기반 권장** (BE-08 자체 권장과 일치). 과공학 함정: 사용자 없는 앱에 ML/CF 는 "수치 외우기"와 같은 인위성 위험 (`25-portfolio-strategy.md` §1 측정 함정 정신).

---

## 6. 강도 추천 규칙 초안 (A, 잠정)

입력: 회원의 최근 N(예: 3)개 **COMPLETED** 스쿼트 세션.

```
IF 이력 없음:
    persona/workoutLevel 기반 시작 난이도·반복수 (콜드스타트 기본값)
ELSE:
    avg = 최근 N세션 평균 싱크로율
    IF avg ≥ 85% AND 모두 현재 난이도 완수:
        → 난이도 +1 (또는 반복수 +X)   # progressive overload
        근거: "최근 N세션 평균 {avg}%로 안정적 → 강도 상향"
    ELSE IF avg < 60% OR 하락 추세:
        → 난이도 유지(또는 -1), 자세 교정 메시지
        근거: "싱크로율 {avg}%로 폼 안정이 우선 → 강도 유지"
    ELSE:
        → 유지 + 소폭 반복수 증가
        근거: "꾸준히 수행 중 → 현재 강도 유지하며 볼륨 소폭 증가"
가드레일:
    - 난이도 점프는 ±1 로 제한 (급변 방지)
    - persona 상한 초과 금지
```

- 임계값(85%/60%, N)은 **잠정** — 측정·튜닝 대상 (RealMySQL 실험과 동일하게 "AI 추천값 → 실측 → 조정" 패턴).
- 출력: 다음 난이도, 목표 반복수·세트, 휴식초, **근거 한국어 한 줄** (`project-korean-only` 메모리).

---

## 7. 백엔드 깊이 포인트 (CRUD 이상으로)

1. **세션 이력 집계 쿼리** ⭐ — "회원의 최근 N개 스쿼트 세션" 신규 조회 필요. `findFirst...`(1건)와 달리 **최근 N건**이라 신규 repo 메서드 + **`exercise_sessions(member_id, exercise_id, status, start_time)` 복합 인덱스** 설계 여지.
   - → pose_data 의 perf 스토리는 죽었지만([`./load-test-strategy.md`](./load-test-strategy.md) §4.3), **sessions 이력 조회는 인덱스 설계가 살아있음** (DAU 1,000 이면 연 ~55만 세션). 추천 기능이 perf 스토리를 작게 부활시킴.
2. **설명 가능한 규칙** — 블랙박스 아님. "근거 한 줄"이 알고리즘의 투명성.
3. **캐싱** — 추천은 세션 종료 시에만 갱신 → cache-aside 적중률 높음. precompute-on-write 와 같은 정신.

---

## 8. API 설계 재검토

BE-08 원안 endpoint 는 *종목* 전제라 A 스코프엔 부적합:

| 원안(B, 2학기) | A 스코프(now) 후보 |
|------|------|
| `GET /recommendations/today` (3개 운동) | `GET /recommendations/next-session` (다음 스쿼트 강도/볼륨 + 근거) |
| `GET /recommendations/weekly` (1주 플랜) | (2학기 — 종목 확장 후) |

→ endpoint 이름·응답 스키마는 미결. A 는 단일 운동이라 "오늘의 루틴"보다 "다음 세션 처방"이 정확.

---

## 9. 미결 항목 (decision 대기)

| 결정 | 후보 | 비고 |
|------|------|------|
| 스코프 | ✅ **A(강도·볼륨 추천)**, 2026-08-30 사용자 confirm. B(종목 루틴)는 2학기 운동 확장 후로 보류 | §3 |
| 알고리즘 | ✅ **규칙 기반**, 2026-08-30 사용자 confirm | §5 |
| 규칙 임계값 | ✅ **avg 85%/60%, N=3 그대로 채택**(2026-08-30 사용자 confirm) — 🔴 미검증 잠정치, 실사용자 데이터 없어 근거로 정당화 불가. 코드·문서에 그렇게 명시할 것. 실사용자 데이터 확보 후 재조정 | §6 |
| 콜드스타트 기본값 | ✅ **workoutLevel → 시작 level 5단계**(STARTER=1, BEGINNER=3, INTERMEDIATE=5, ADVANCED=7, EXPERT=9), 2026-08-30 사용자 confirm. ⚠️ 근거 없는 잠정치 — 5단계를 1~10에 고르게 편 것뿐. persona는 `getDifficultyConfig`의 baseReps·baseSyncRate에 그대로 사용 | §4 |
| API 형태 | ✅ **`GET /recommendations/next-session`**, 2026-08-30 사용자 confirm | §8 |
| 세션 이력 인덱스 | ✅ **불필요**(2026-08-30 실측) — 기존 `idx_session_member_status_start`가 역순 스캔으로 이미 상수 비용, 아래 §10 | §7 |
| 캐싱 도입 시점 | ✅ **보류**(2026-08-30 사용자 confirm) — 실측 결과 캐싱 이득 근거 없음, 아래 §10 | §7 |

---

## 10. 실측 — "최근 N개 세션" 쿼리는 이미 상수 비용, 캐싱·신규 인덱스 둘 다 불필요 (2026-08-30)

§7이 "세션 이력 인덱스 설계 여지가 있다"고 예상했고, §9는 "캐싱 도입은 원본이 비싸고 적중률도 높을 때 정당화된다"는 전제를 깔고 있었다. 둘 다 실측으로 확인해보니 전제가 틀렸다.

**쿼리**: `WHERE member_id=? AND exercise_id=1 AND status='COMPLETED' ORDER BY start_time DESC LIMIT 3` — §6 규칙(N=3)이 그대로 필요로 하는 형태.

**대상**: `member_id=1`(dev-seed, 1,680세션 — BE-07 세션5·9가 만든 계정, 이 프로젝트에서 구할 수 있는 가장 큰 계정).

```
EXPLAIN ANALYZE 결과:
-> Limit: 3 row(s)  (actual time=0.368..0.411 rows=3 loops=1)
    -> Filter: (exercise_id = 1)  (actual time=0.366..0.409 rows=3 loops=1)
        -> Index lookup on exercise_sessions using idx_session_member_status_start
           (member_id=1, status='COMPLETED') (reverse)  (actual time=0.342..0.383 rows=3 loops=1)

Handler_read_key=1, Handler_read_prev=2, Handler_read_next=0
```

**왜 이렇게 싼가**: `idx_session_member_status_start(member_id, status, start_time)`가 이미 `(member_id, status)` 등치 + `start_time` 정렬을 지원하는 인덱스라, `ORDER BY start_time DESC`를 만나면 옵티마이저가 그 구간의 **끝(가장 큰 start_time)으로 바로 점프해 뒤로(`reverse`) 걸으며** `LIMIT 3`에서 멈춘다 — B+Tree 리프 페이지의 양방향 연결 리스트 덕분에 정렬(`filesort`) 없이 그대로 DESC 순서가 나온다. 읽은 행은 정확히 3개(`Handler_read_key` 1 + `Handler_read_prev` 2) — **계정에 세션이 몇 개든 상수 비용**, 1,680세션 계정에서도 100세션 계정에서도 동일하게 3행만 읽는다.

**결론**:
- 🔴 **세션 이력 인덱스 신설 불필요** — 기존 인덱스가 이미 최적. §7의 "인덱스 설계 여지" 기대는 기각.
- 🔴 **캐싱 근거 없음** — "적중률 × 원본 비용"에서 원본 비용이 0.4ms대(사실상 무시 가능)라, 적중률이 높아도(§7-3 관찰은 유효) 아낄 게 거의 없다. cache-aside를 넣으면 `@CacheEvict` 누락 시 stale 버그 위험 등 비용만 추가되고 이득은 없음 — 보류 확정(§9).
- 🔶 이 실측은 로컬 도커, dev-seed 합성 계정 1개 기준이다([[project_loadtest_env_constraint]]) — 부하(동시 요청) 아래 실측은 아님. 다만 "상수 비용 vs 계정 크기 무관"이라는 구조적 사실은 인덱스 구성 자체에서 나오는 것이라 부하와 무관하게 성립한다(BE-07 세션5·9와 같은 패턴).

---

## 결정 로그

- **2026-05-31 (초안)**: BE-08 추천 알고리즘 분석 문서 신설. 핵심 발견 — BE-08 원안("3개 운동 루틴")이 squat-first 와 충돌(§2, 분석 가능 운동=스쿼트뿐). 스코프를 A(강도·볼륨 추천, now 성립) / B(종목 루틴, 2학기)로 분리 권고(§3). 알고리즘은 규칙 기반 권고(§5, CF·LLM 은 과공학). 강도 추천 규칙 초안(§6) + 기존 데이터 매핑(§4, 신규 수집 0). 백엔드 깊이는 세션 이력 집계 쿼리 + 인덱스(§7 — pose_data 와 달리 sessions 조회는 perf 스토리 살아있음). 임계값·API·인덱스·캐싱 미결(§9). confirm 후 박제.
- **2026-08-30**: 스코프 A(강도·볼륨 추천) 확정. B(종목 루틴, "오늘 할 운동 3개")는 squat-first 제약(§2)상 지금은 성립 불가라 2학기로 보류. 이후 결정 항목은 전부 A 스코프 기준.
- **2026-08-30**: 알고리즘 규칙 기반 확정 — CF는 사용자 ~0명이라 협업할 대상이 없어 인위적, LLM은 결정적이어야 할 강도 추천에 비결정적 호출이 과함(게다가 BE-03이 LLM 자리를 이미 맡음).
- **2026-08-30**: 규칙 임계값(avg 85%/60%, N=3) 잠정치 그대로 채택 확정 — 실사용자 데이터가 없어 다른 값으로 바꿀 근거도 없다는 점을 인정하고, "미검증 잠정치"로 명시한 채 코드에 반영. 실사용자 데이터 확보 후 재조정 대상.
- **2026-08-30**: API 형태 `GET /recommendations/next-session` 확정 — 원안(`/today`·`/weekly`, 종목 3개 전제)은 A스코프와 안 맞아 폐기, 단일 endpoint로 다음 스쿼트 세션 강도·볼륨 + 근거 한 줄만 반환.
- **2026-08-30**: 세션 이력 인덱스·캐싱 도입 시점 둘 다 실측으로 확정(§10) — `member_id=1`(1,680세션) 대상 `EXPLAIN ANALYZE`가 최근 N개 세션 조회를 0.4ms대(읽은 행 3개, 계정 크기 무관 상수 비용)로 확인. 기존 `idx_session_member_status_start`의 역순 스캔이 이미 최적이라 신규 인덱스 불필요, 원본 비용이 사실상 0이라 캐싱도 이득 근거 없음 — 둘 다 보류/불필요로 종결.
- **2026-08-30 (구현 완료)**: `GET /recommendations/next-session` 구현, PR #625. `Session.difficultyLevel`이 세션 생성 흐름에서 실제로 안 채워지는(항상 기본값 1) 것을 확인 — "직전 난이도에서 +1"의 "직전 난이도"를 DB에서 못 가져오므로, 상태를 어디에도 안 쌓는 순수 함수(프로필 기반 시작 level + 최근 3세션 평균으로 매 호출 새로 계산)로 구현했다. 세션 생성 흐름은 무변경("추천 API만" 스코프, 사용자 confirm). `docs/12-persona-difficulty.md`의 공식을 처음으로 백엔드에 반영. 테스트 12개, 전체 스위트 회귀 없음.
- **2026-08-30**: 콜드스타트 기본값 확정 — `Member.workoutLevel`(5단계) → `12-persona-difficulty.md`의 `getDifficultyConfig(level, persona)` 시작 `level`로 매핑(STARTER=1/BEGINNER=3/INTERMEDIATE=5/ADVANCED=7/EXPERT=9). 이 공식(`targetReps`·`targetSyncRate`·`restTimeSec`) 자체는 그 문서에 이미 있던 것을 그대로 가져다 쓰는 것 — 백엔드에 실제로 들어가는 건 이번이 처음이다(지금까지 `Session.difficultyLevel`은 계산 없이 기본값 1로만 남아있었음). §9의 나머지 항목(스코프·알고리즘·임계값·API·인덱스·캐싱)은 여전히 미결.

---

## 관련 문서

- [`../tasks/22-backend-tasks-detail.md`](../tasks/22-backend-tasks-detail.md) — BE-07(패턴 분석, 추천 입력)·BE-08(추천 API) 원안
- [`../12-persona-difficulty.md`](../12-persona-difficulty.md) — 페르소나×난이도 파라미터(휴식·반복 등)
- [`./load-test-strategy.md`](./load-test-strategy.md) — pose_data perf 스토리 폐기(§4.3), sessions 이력 조회는 인덱스 여지(본 문서 §7)
- [`../tasks/25-portfolio-strategy.md`](../tasks/25-portfolio-strategy.md) — 측정 함정·규칙 기반 권장, 깊이 트랙
- [`../../backend/src/main/java/com/shadowfit/model/exercise/Session.java`](../../backend/src/main/java/com/shadowfit/model/exercise/Session.java) — 추천 입력 필드(difficultyLevel·avgSyncRate·totalReps)
- [`../../backend/src/main/java/com/shadowfit/service/report/ReportService.java`](../../backend/src/main/java/com/shadowfit/service/report/ReportService.java) — `buildComparisonWithPrevious`·`findFirst...` 재사용 지점
