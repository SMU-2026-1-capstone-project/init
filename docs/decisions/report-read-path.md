# 리포트 기능 읽기 경로 감사 — 병목·갭·해결 로드맵

상태: **① projection 적용 완료(2026-07-15)**. 나머지(②precompute·④~⑥)는 착수 미결 — 결정은 사용자 confirm 후
작성: 2026-06-14
대상: 백엔드(Spring) 포폴. 리포트 기능(`GET /reports/sessions/{id}`)의 **읽기 경로**를 코드로 감사 — 실제 병목/갭이 뭔지, 인덱스로 풀리는 것/안 풀리는 것, 일반(CRUD) 프로젝트와의 차이, 정직한 현재 상태 분류.
연관: [`../portfolio/db-deep-dive.md`](../portfolio/db-deep-dive.md) §B, [`../portfolio/realmysql-experiments.md`](../portfolio/realmysql-experiments.md) §②b, [`./load-test-strategy.md`](./load-test-strategy.md) §4.6, [`./report-aggregation.md`](./report-aggregation.md), [`../portfolio/portfolio-narrative.md`](../portfolio/portfolio-narrative.md)

> ✅=코드/측정으로 확인, 🔶=실재하나 미발동/미구현, ⬜=계획. **결정 마크는 사용자 confirm 후에만.**

**스코프 명확화(2026-07-15)**: `docs/tasks/20-feature-roadmap.md` §1-3 "운동 리포트"는 9개 항목(메인 캘린더, 주간 요약, 실시간 피드백, 이전 기록 비교, **worst 구간 선정**, AI 리포트 자동생성, 개인화 루틴 추천, 목표 달성현황 등)을 묶은 PPT 기획 카테고리다. 이 문서(`report-read-path.md`)가 DB 엔지니어링(인덱스·projection·precompute)으로 다루는 건 그중 **"worst 구간 선정" 하나뿐**이며, 나머지 8개는 담당 축이 다르다(프론트 연동만 남은 것 / AI 서비스 신설 필요 / 스키마 자체가 없는 것 등) — "리포트 기능"이라는 이름이 같아서 전부 같은 작업으로 착각하지 않도록 명시.

---

## 0. 한 줄 요약

리포트는 **읽기 축(read-heavy, GET)** 이라 쓰기 최적화(락·트랜잭션)가 아니라 **쿼리·payload·캐싱**에 의존한다. 현재 읽기 경로엔 코드로 실재하는 갭이 4개(over-fetch·재계산·REPORT_NOT_FOUND·인덱스 갭), 규모 미발동 잠재 2개(버퍼풀 오염·무제한 프레임)가 있다. **인덱스는 "찾기"만 풀고**, 리포트의 진짜 병목(fat 컬럼·재계산·오염)은 인덱스 바깥(projection·precompute)에 있다.

---

## 0-A. 리포트 조회 DB 설계 체크리스트 (8단계, 2026-07-15)

읽기 경로 하나(세션 리포트 조회)를 DB 관점에서 설계·점검할 때 이 순서로 따진다. 뒤 단계일수록 앞 단계의 답을 전제로 하고(예: ⑤동시성은 ④규모를 모르면 판단 불가), 앞쪽일수록 코드만 읽으면 답이 나오는 저비용·확정적 질문, 뒤쪽일수록 가정·트레이드오프 판단이 필요한 고비용 질문이다.

| # | 단계 | 이 프로젝트 결론 | 상태 |
|---|---|---|---|
| ① | 저장 구조 파악 | `exercise_sessions`→`pose_data`(fat, append-only)→`reports`(집계) | ✅ |
| ② | 저장 vs 조회 컬럼 대조 (over-fetch) | 8개 저장 컬럼 중 조회는 3개(timestamp_sec·sync_rate·feedback_message)만 사용 → projection 적용 | ✅ 적용 완료(§7) |
| ③ | 접근 패턴별 인덱스 (등호 먼저·범위/정렬 나중) | `(session_id, timestamp_sec)`, `(member_id, start_time)`, `(member_id, exercise_id, status, start_time)` | ✅ 실측 완료(§4) |
| ④ | 규모 역산 (가정 기반 미래 볼륨 예측) | DAU 1,000 가정 → 월 ~1억 행 | ✅ 가정 명시 |
| ⑤ | 동시성 (④의 트래픽 규모로 충돌 빈도 판단) | 세션 status 낙관락(실장착), DailyLog 원자 upsert(실장착) | ✅ 코드 적용 완료 |
| ⑥ | precompute 여부 (읽기 비용을 쓰기 시점으로 이전) | 세부 설계 4가지(§9) 정리, 착수는 미결 | 🔶 설계만 |
| ⑦ | 보존 정책 (raw를 언제까지 들고 있을지) | 6가지 축(§9-B) 정리, 착수는 미결 | 🔶 설계만 |
| ⑧ | N+1 체크 | `SessionRepository.findSessionWithExerciseById`가 `JOIN FETCH s.exercise` 사용 — 이미 방지됨 | ✅ 확인 완료 |

**스코프 밖(현재 규모에서 이득 작다고 판단)**: read replica 분리(현재 트래픽 규모에서 불필요). 캐싱은 §0-B ⑨ 참고 — "이득이 작다"와 별개로 무효화 설계 자체가 빠져있어 완전히 스코프 밖으로 볼 항목은 아님(외부 리뷰로 정정, 2026-07-15).

---

## 0-B. 리포트 조회 — 비성능 축 체크리스트 (⑨~⑭, 2026-07-15, 외부 리뷰 반영)

§0-A의 8단계는 "쿼리 성능·정합성" 축. 리포트 조회엔 성능 말고도 캐싱 무효화·시간 경계·결측 처리·권한·페이징·트랜잭션 경계라는 별개 축이 있음 — 외부 리뷰로 지적받아 코드 대조 후 정리.

| # | 축 | 확인 내용 | 상태 |
|---|---|---|---|
| ⑨ | 캐싱 & 무효화 | `exercises`·`feedbackTemplates`·`exerciseReferences`엔 `@Cacheable` 있으나 리포트/주간요약엔 캐시 자체가 없음 | ❌ **갭** — 캐싱 자체도, 무효화 트리거 설계도 없음 |
| ⑩ | 시간 경계 / 집계 기준 | `docker-compose.yml`에 `TZ: Asia/Seoul`(백엔드·DB 둘 다) → `LocalDate.now()`가 KST 기준 정확. 주 시작요일도 `DayOfWeek.MONDAY` 명시 | ✅ 처리됨 |
| ⑪ | 결측 데이터 / 빈 리포트 | `getWeeklyActivity`: 그 주 세션 0건이어도 NPE 없이 0값 응답(`totalMinutes=0` 등 방어적 처리) | ✅ 처리됨 |
| ⑫ | 권한 경계 (IDOR) | ~~조회 후 검증(fetch-then-check) 패턴~~ → **✅ 해결(2026-07-15)**: `SessionRepository.findSessionWithExerciseByIdAndMemberId`로 소유권을 WHERE절에 박음. 없으면(존재X/남의 것) 둘 다 동일하게 `SESSION_NOT_FOUND` — 존재 여부 비공개 | ✅ 해결 |
| ⑬ | 페이징 전략 | 리포트 히스토리 목록 엔드포인트 자체가 없음(단일 세션 조회만 존재) — **의도적으로 미룸**(§0-C) | ⬜ 미착수, 트리거 명시 |
| ⑭ | 읽기 트랜잭션 & OSIV | ~~기본값(켜짐) 방치~~ → **✅ 해결(2026-07-15)**: `application.yml`·테스트 yml 둘 다 `open-in-view: false` 명시. 전체 테스트 통과(숨은 lazy 접근 없음 확인, 단 테스트 파일 4~5개뿐이라 전체 엔드포인트 커버는 아님) | ✅ 해결 |

**우선순위 판단(외부 리뷰, 2026-07-15)**: ⑫·⑭는 "싸고 구조적 안전 상승" → 오늘 처리. ⑨·⑬은 "지금 하면 조기 최적화/조기 설계" → 의도적 미룸, 트리거 조건은 §0-C.

---

## 0-C. 의도적으로 미루는 것 — ⑨ 캐싱, ⑬ 페이징 (2026-07-15)

**⑨ 캐싱 — 지금 안 함.** DAU 1,000 가정에서 ③(인덱스)·⑧(N+1 차단)이 이미 돼 있으면 리포트 쿼리는 캐시 없이도 충분히 빠름. 캐싱은 "무효화"라는 새 버그 표면(세션 저장 시점에 어느 캐시를 깨야 하는지)을 여는 일이라, 실측 슬로우 쿼리가 나오기 전엔 순부채(premature optimization).
- **지금 대신 할 것**: 슬로우 쿼리 로깅/측정만 걸어둠 — "언제 캐싱이 필요한지"를 감이 아니라 데이터로 판단하기 위한 관측 준비.
- **착수 트리거**: 슬로우 쿼리 로그에 리포트/주간요약 쿼리가 실제로 잡히기 시작하면.

**⑬ 페이징 — 히스토리 엔드포인트 자체가 없어서 지금 할 일이 없음.** 기능이 생길 때 같이 설계.
- **미리 못박아두는 것**: 만들 때 offset이 아니라 **cursor(created_at + id) 페이징으로 시작**. 시계열 데이터라 나중에 offset→cursor로 갈아엎는 게 처음부터 cursor로 시작하는 것보다 비쌈.
- **착수 트리거**: 리포트 히스토리 목록 기능(`20-feature-roadmap.md`) 착수 시점.

두 항목 다 "안 했다"가 아니라 "의도적으로 미뤘고, 이 조건이 되면 착수한다"로 박제 — 나중에 "왜 안 했지"가 아니라 "언제 할지 이미 정해뒀다"가 되도록.

---

## 1. 현재 읽기 경로 (코드 기준)

`ReportService.getSessionReport`([`ReportService.java:33`](../../backend/src/main/java/com/shadowfit/service/Report/ReportService.java))가 던지는 쿼리 4개:

```
GET /reports/sessions/{id}
 1. findSessionWithExerciseById         ← JOIN FETCH s.exercise (SessionRepository.java:16)
 2. reportRepository.findBySessionId     ← Report 1행, 없으면 REPORT_NOT_FOUND (ReportService.java:41)
 3. findFirstByMemberIdAndExerciseIdAndStatusOrderByStartTimeDesc  ← 이전 세션(비교용)
 4. findFramesBySessionId ← pose_data 3컬럼 projection (2026-07-15 적용, §7)
```

이후 `buildReportResponse` → `selectWorstSection`([`:74`](../../backend/src/main/java/com/shadowfit/service/Report/ReportService.java))에서 연속 3프레임(`WORST_WINDOW_SIZE=3`) 슬라이딩으로 최저 syncRate 구간을 **자바 메모리에서 O(N) 재계산**.

**데이터 모델**: pose_data 1행 = 분석 프레임 1개. `joint_coordinates`(@Lob TEXT, [`PoseData.java:26`](../../backend/src/main/java/com/shadowfit/model/exercise/PoseData.java))는 프레임당 33 랜드마크 JSON ≈ 2.3KB. 세션당 ~750행 → ~1.7MB.

---

## 2. 발견된 문제 (코드 기준 분류)

| # | 문제 | 상태 | 코드 근거 |
|---|------|------|-----------|
| ① | ~~**fat 컬럼 over-fetch** — 풀엔티티 로드, worst 계산은 `syncRate`·`timestampSec`·`feedbackMessage` 3개만 씀. `joint_coordinates`는 **한 번도 안 씀**~~ | **✅ 해결(2026-07-15)** | `findFramesBySessionId`(3컬럼 projection, §7)로 교체. 실측(§②b): payload −98.7%, warm 쿼리 **8x**(로컬 412만 행) → **29~41x**(AWS 1억 행, 2026-07-15 재검증) |
| ② | **재계산-on-read** — precompute 없이 매 GET마다 worst 구간 재계산 | ✅ 실재 | `selectWorstSection` 매 호출 |
| ③ | **REPORT_NOT_FOUND 갭** — `completeAnalysis`가 `session`만 UPDATE, `reports`엔 안 씀 → 실제 세션은 Report 행 없음 → 404 | ✅ 실재(기능 끊김) | `applyCompleteFromApp`([`ExerciseAnalysisService.java:217`](../../backend/src/main/java/com/shadowfit/service/Exercise/ExerciseAnalysisService.java)) session만 UPDATE / `reports`는 [`data.sql:168`](../../mysql/data.sql) 시드뿐 |
| ④ | ~~**exercise_sessions 인덱스 갭** — `(member_id, start_time)` 등 복합 인덱스 없음(FK 단일뿐) → 캘린더·이전세션 쿼리 filesort~~ | **✅ 해결(2026-07-11)** | `idx_session_member_starttime` 추가([`schema.sql:71`](../../mysql/schema.sql)), 실측: 월간 조회 1675행 Filter → 143행 Index range scan(cost 91.4→64.6), 연간 조회는 Covering index scan으로 전환. 커밋 `dbb0fec`<br>🔀 **2026-08-07**: 이 인덱스는 `idx_session_member_status_start (member_id, status, start_time)` 로 통합돼 이름이 사라졌다([#110](https://github.com/Shadowfit/init/issues/110), [`session-index-composition.md`](./session-index-composition.md)). 갭 자체는 여전히 해결 상태 — 다만 `status` 를 건너뛰어야 해 읽는 행이 14 → 20 으로 늘었다(절대 0.03ms) |
| ④-1 | ~~**"직전 동일 운동" 조회는 ④의 인덱스로 안 커버됨** — `findFirstByMemberIdAndExerciseIdAndStatusOrderByStartTimeDesc`(이전 기록 비교용)는 `member_id, exercise_id, status`로 필터하는데 `idx_session_member_starttime(member_id, start_time)`엔 `exercise_id`·`status`가 없어 member_id로 찾은 뒤 filter~~ | **✅ 해결(2026-07-15)** | 실측: 기존 인덱스만 있을 때 `rows=1675, filtered=5.19%, Using where`. `idx_session_member_exercise_status_start(member_id, exercise_id, status, start_time)` 추가 후 `filtered=100%`, filter 단계 제거(`schema.sql` `exercise_sessions`) |
| ⑤ | **버퍼풀 오염** — cold 거대 raw를 서빙 경로가 건드려 핫 데이터 evict | 🔶 메커니즘 실재, 규모 미발동 | DAU 작아 리포트 조회 드묾 |
| ⑥ | **무제한 프레임 극단** — R(프레임/세션)이 위로 열림(fps 미강제) → 극단 세션이 수십~수백 MB 로드·O(N) 루프·OOM 위험 | 🔶 가능성 실재, 미발동 | 정상 750행, [`load-test-strategy.md`](./load-test-strategy.md) §4.5 "R 위로 열림" |

---

## 3. 인덱스가 푸는 것 / 못 푸는 것 (핵심 reframe)

읽기를 단계로 쪼개면 인덱스는 **①찾기만** 담당:

| 시계열 읽기 하위문제 | 인덱스로 풀리나 |
|---|---|
| ① 어느 행/범위 찾고 정렬 | ✅ **풀린다** (단건 리포트는 이미 최적; 캘린더도 복합 인덱스 추가로 해결=문제④, 2026-07-11) |
| ② 찾은 fat 행 JSON 펼치기 | ❌ projection |
| ③ derived(worst 구간) 재계산 | ❌ precompute |
| ④ cold raw 버퍼풀 오염 | ❌ 분리/티어링 |

- 단건 리포트는 `idx_session_timestamp(session_id, timestamp_sec)`([`schema.sql:86`](../../mysql/schema.sql))로 **①이 이미 최적**(세션 바운드, filesort 0, [`db-deep-dive.md`](../portfolio/db-deep-dive.md) §4.3) → 남는 병목 ②③⑤가 전부 **인덱스 바깥**.
- **cross-session(캘린더·이전세션)도 ①이 인덱스 갭이었으나 해결됨**(문제④, `idx_session_member_starttime` 추가) — member 바운드+누적이라 데이터 쌓이면 인덱스 없이는 진짜 느려지는 구조였음(pose_data와 정반대).

**쓰기(적재)와도 대비**: 적재 경로엔 인덱스가 *비용*(쓰기 증폭·페이지 분할). 시딩 때 인덱스 빼고 일괄 빌드한 이유([`realmysql-experiments.md`](../portfolio/realmysql-experiments.md) §3). → 인덱스가 닿는 건 "읽기의 찾기" 한 칸뿐.

---

## 4. 일반(CRUD) vs 시계열 읽기 문제 클래스

| | 일반 (게시판·쇼핑몰·CRUD) | 너희류 (시계열 적재 + derived 리포트) |
|---|---|---|
| 읽기 핫패스 | 목록·검색(다행) | 단건 리포트(부모의 children) |
| 행 특성 | 작고 균일 | **행마다 fat JSON(off-page)** |
| 주 문제 | N+1, 인덱스 부재→풀스캔, 깊은 offset | **fat 헛로드·재계산·버퍼풀 오염·raw-in-OLTP** |
| 인덱스 추가 효과 | 극적(헤드라인) | 이미 최적(조연) |
| projection 효과 | 작음(행 작음) | **큼(−98.7%)** |
| 해결 성격 | textbook=평준화 | **판단 필요**(denormalization·티어링) |

→ 같은 "읽기"여도 **병목이 어느 단계냐**가 달라, 인덱스가 헤드라인(CRUD)이냐 조연(시계열)이냐가 갈린다. 유사 프로젝트도 *제대로 측정하면* 같은 문제를 만나지만 대부분 측정 안 해 모름 → **차별점은 "문제를 가진 것"이 아니라 "측정해 발견한 것"**([`db-deep-dive.md`](../portfolio/db-deep-dive.md) §0.3).

---

## 5. 정직한 현재 상태 분류 (오버셀 금지)

| 등급 | 항목 | 의미 |
|---|---|---|
| 🟢 **실재 + 측정** | ① over-fetch (projection −98.7% 측정) | 코드에 있고 합성 볼륨으로 측정 |
| 🟢 **실재 (코드)** | ② 재계산, ③ REPORT_NOT_FOUND | 코드/스키마에 실재 |
| ✅ **해결됨(2026-07-11)** | ④ 인덱스 갭 | `idx_session_member_starttime` 추가, 실측 확인 — 커밋 `dbb0fec` |
| 🟡 **잠재 (규모 미발동)** | ⑤ 버퍼풀 오염, ⑥ 무제한 프레임 | 메커니즘 실재하나 DAU 작아 안 터짐 |

**메타 포인트**: 이건 "프로덕션 화재"가 아니라 "**코드를 읽고 측정해 발견·판단한 것**". 실트래픽 작아 실제로 아픈 건 아니지만 코드엔 실재. → §0.5 정직한 천장이자 차별점("운영 위장"❌ / "median 신입보다 위"✅). 면접에선 **"실재+측정 / 잠재+미발동"을 갈라** 말해야 과장도 약함도 아님.

---

## 6. 해결 로드맵 — 무엇이 무엇을 푸나

| 문제 | projection | precompute | 복합 인덱스 | 다운샘플 |
|---|---|---|---|---|
| ① fat 헛로드 | ✅ | ✅ | | |
| ② 재계산 | | ✅ | | |
| ③ REPORT_NOT_FOUND | | ✅ | | |
| ④ 인덱스 갭(캘린더) | | | ✅ **완료(2026-07-11)** | |
| ⑤ 버퍼풀 오염 | 🔶 부분 | ✅ | | |
| ⑥ 무제한 프레임 | ❌ | ✅ | | ✅ |

→ **precompute가 6개 중 4개를 한 번에 푼다**(세션 종료 시 worst 구간·요약을 `reports`에 1회 저장 → 조회가 raw를 0개 읽음). projection은 그 전까지 완화책(per-row만), 복합 인덱스는 별개 축(세션 히스토리, 완료)이고, 다운샘플은 프레임 수 자체([`pose-ingest-downsampling.md`](./pose-ingest-downsampling.md)).

**추천 착수 순서(미confirm)**: ① projection(가볍다, 쿼리/DTO만) → ② precompute(`completeAnalysis`에서 Report 생성 + worst 저장, ③·⑥도 해결) → ~~③ 복합 인덱스(캘린더)~~ **완료** → ④ raw TTL.

**부수 발견(2026-07-11)**: 복합 인덱스 실측 중 `calculateConsecutiveDays`(연속일수 계산)가 실제 세션 데이터가 있으면 항상 500(`java.sql.Date` → `LocalDate` 컨버터 없음, `ConverterNotFoundException`)을 내는 걸 발견·수정. 이전까지 이 경로를 테스트할 때 결과가 항상 빈 배열이라 드러나지 않았던 버그 — `GET /reports/calendar`가 실사용자 데이터로는 사실상 항상 터지고 있었을 것. 인덱스와는 무관한 별개 버그, 커밋 `eed807e`.

---

## 7. projection 구현 (③단계, 코드 — **적용 완료 2026-07-15**)

3파일 변경(`PoseFrameProjection.java` 신규, `PoseDataRepository.java`, `ReportService.java`). 기존 `findBySessionIdOrderByTimestampSecAsc`는 제거(테스트는 `findAll`/`count`만 써서 영향 없음, 전체 테스트 통과 확인).

**① 신규 DTO** — `dto/report/PoseFrameProjection.java`
```java
package com.shadowfit.dto.report;

// worst 구간 계산에 필요한 3컬럼만. joint_coordinates(2.3KB) 제외 → off-page I/O 회피.
public record PoseFrameProjection(Double timestampSec, Double syncRate, String feedbackMessage) {}
```

**② Repository** — 명시적 JPQL로 컬럼 고정
```java
@Query("SELECT new com.shadowfit.dto.report.PoseFrameProjection(" +
       "p.timestampSec, p.syncRate, p.feedbackMessage) " +
       "FROM PoseData p WHERE p.session.id = :sessionId ORDER BY p.timestampSec ASC")
List<PoseFrameProjection> findFramesBySessionId(@Param("sessionId") Long sessionId);
```
생성 SQL: `SELECT timestamp_sec, sync_rate, feedback_message FROM pose_data WHERE session_id=? ORDER BY timestamp_sec` (joint_coordinates 없음). `p.session.id`는 FK 컬럼 직접 사용(session 조인 안 함).

**③ ReportService** — `selectWorstSection`/`buildWorstReason`/`pickDominantFeedback` 시그니처를 `List<PoseFrameProjection>`로, 접근자 `getSyncRate()→syncRate()` 등으로 교체. 호출부(`:51`)를 `findFramesBySessionId`로.

> 기존 `findBySessionIdOrderByTimestampSecAsc`는 교체 후 미사용(테스트는 `findAll`/`count`만 씀) → 제거 선택.

**실측**(이미 [`realmysql-experiments.md`](../portfolio/realmysql-experiments.md) §②b — 2026-06-02, warm, 750행/세션, **로컬 412만 행, 3컬럼 시절 프로젝션**): payload 1,716.8KB→22.4KB(−98.7%), warm 쿼리 12.1ms→1.5ms(8x). 바이트는 −98.7%인데 시간은 −87%인 이유 = 750 row lookup 등 고정비용은 남고 off-page JSON I/O만 사라지기 때문(바이트≠시간 선형).

**AWS 1억 행 재검증(2026-07-15)**: payload 1,740.1KB→22.6KB(−98.7%, 동일), warm 쿼리 **40.6ms→1.4ms(29~41x)**. 412만 행 때의 8x보다 배수가 커진 것은 버퍼풀(2GB) 대비 테이블이 25배(~230GB) 커지며 작업셋 비율이 나빠져 off-page 랜덤 I/O가 캐시에 덜 걸린 탓 — 결론이 강화된 방향이다. ⚠️ 이 쿼리는 precompute가 세션당 1회 도는 비동기 잡이라 **사용자 체감 지연이 아니라 잡의 자원 소모 절감**으로 읽어야 한다.

**⚠️ 향후 컬럼 추가 예정 (BE-09 세트 도입, 현재 보류)**: `pose_data`에 `set_index` 컬럼이 추가되면([`22-backend-tasks-detail.md#BE-09`](../tasks/22-backend-tasks-detail.md)) `PoseFrameProjection`도 `setIndex`를 포함하도록 확장 필요 — worst 구간 계산이 세션 전체가 아니라 세트 단위로 바뀔 수 있음. BE-09는 스쿼트 외 운동 추가 시점까지 보류라 지금 미리 넣지 않음(YAGNI) — 확장 시점에 이 3컬럼 DTO부터 손대야 함을 기록.

---

## 8. 미결정 (사용자 confirm 필요)

- [x] projection 코드 적용 여부 (위 §7) — **적용 완료 2026-07-15**
- [x] precompute 착수 — **완료(2026-07-24)**. `SessionService.applyComplete`가 세션 완료 시 `WorstSectionCalculator`로 계산해 `reports.detailed_analysis`에 저장(③·⑥ 동시 해결). 세부 결정 4가지는 §9
- [x] exercise_sessions 복합 인덱스 추가 — **완료(2026-07-11)**, `mysql/schema.sql:83` `idx_session_member_starttime(member_id, start_time)` (§2 ④ 참고). 체크박스 갱신 누락 발견(2026-07-15). 🔀 2026-08-07 `idx_session_member_status_start` 로 통합(#110)
- [x] 착수 순서 — **precompute 먼저 완료**(2026-07-24), projection은 이미 그 이전(2026-07-15)에 적용 완료라 사실상 둘 다 완료
- [x] pose_data 파티션 스키마 자체 반영 — **완료(2026-07-20, PR #43)**. FK 제거 대체(B5) 포함. [`pose-data-partition-fk-tradeoff.md`](./pose-data-partition-fk-tradeoff.md)
- [x] raw TTL(보존 정책) **자동화**(DROP PARTITION 스케줄러) — **완료(2026-07-24)**. `PoseDataPartitionScheduler` 신설. 설계 정리는 §9-B

---

## 9. precompute 설계 — 미결정 4가지 (2026-07-15)

precompute 자체("세션 완료 시 1회 계산해 `reports`에 저장, 조회는 읽기만")는 §6·§8에서 방향은 잡혔음. 아래 4개는 **그 방향 안에서 아직 안 정한 세부 설계** — 결정 마크는 사용자 confirm 후에만.

### 9-1. 계산 로직 위치 — 순환 의존 회피 — ✅ 결정(2026-07-24)

`selectWorstSection`/`buildWorstReason`/`pickDominantFeedback`(현재 `ReportService`, 읽기 경로 전용)를 쓰기 경로(`SessionService.applyComplete`)에서도 호출해야 함. `SessionService`가 `ReportService`를 직접 의존하면, 훗날 `ReportService`가 `SessionService`를 참조할 일이 생겼을 때 순환 의존이 됨.
- **결정: 후보 A 채택** — 계산 로직을 별도 컴포넌트 `WorstSectionCalculator`로 추출, `ReportService`(읽기)·`SessionService`(쓰기, precompute) 둘 다 이걸 의존. `SessionService.applyComplete`가 이미 `DailyLogService`를 같은 패턴(완료 시점 부가 서비스 호출)으로 의존하고 있어(`SessionService.java:133`) 일관성 있음. `ReportService`의 "읽기 경로 전용" 관례도 유지됨. (기각한 후보 B: `SessionService`가 `ReportService`를 직접 의존 — 클래스 하나 덜 늘지만 read-only 관례가 깨지고 향후 확장 시 순환 위험.)

### 9-2. 트랜잭션 경계 — 세션 완료와 리포트 생성의 원자성

세션이 `COMPLETED`인데 `reports` row가 없는 상태(현재 ③번 갭)가 precompute 도입 후에도 재발하면 안 됨.
- **후보**: `applyComplete`와 리포트 생성을 **같은 트랜잭션**에서 처리 — 하나라도 실패하면 세션 완료 자체가 롤백.

### 9-3. 계산 실패 시 정책

`findFramesBySessionId` 조회나 worst 구간 계산 자체가 실패하면(예: pose_data 0건인 극단 케이스) 어떻게 할지.
- **후보 A**: 세션 완료 자체를 실패시킴(9-2와 연결, 엄격하지만 AI 콜백 재시도 정책과 상호작용 고려 필요)
- **후보 B**: 세션 완료는 성공시키고 리포트는 null/기본값으로 저장 후 별도 배치로 나중에 채움(③번 갭이 다른 형태로 재발할 위험)

### 9-4. 과거(precompute 이전) 완료 세션의 백필

precompute 도입 이전에 이미 `COMPLETED`된 세션들은 `reports` row가 없거나 비어있을 수 있음.
- **후보**: 일회성 마이그레이션 스크립트로 과거 세션 전체를 훑어 `reports`를 소급 생성. 단 이 프로젝트는 실사용자가 없는 캡스톤이라 백필 대상 자체가 미미할 가능성 높음(과잉설계 주의).

---

## 9-B. raw TTL(보존 정책) 설계 — 6가지 축 — ✅ 전부 완료(2026-07-24)

> **2026-07-24 최종 갱신**: precompute-on-write(§9)가 먼저 구현되면서 ②(삭제 근거)가 풀렸고, 그 위에 TTL 자동화(⑤⑥) 및 나머지 안전장치(보존기간·아카이빙·안전마진·검증)까지 전부 결정·구현 완료. `PoseDataPartitionScheduler`(신규, `com.shadowfit.service.Exercise`) 참조.

`pose_data` TTL(§6 ④)은 개념·실측(`realmysql-experiments.md` §4②d, DROP PARTITION 625배)에 스키마 반영(PR #43)과 자동화까지 모두 끝났다. 6가지 축 — ①~④는 **정책 설계**, ⑤~⑥은 **운영 실행**.

| # | 축 | 내용 | 이 프로젝트 상태 |
|---|---|---|---|
| ① | 버퍼 vs 영속 구분 | `pose_data`(raw, 단기) vs `reports`(집계, 영속) | ✅ 판단 완료(`db-deep-dive.md` §2-0) |
| ② | 삭제 근거 | raw를 지워도 되는 건 precompute로 요약이 `reports`에 이미 박제됐을 때뿐 | ✅ precompute-on-write 완료(2026-07-24, §9) — 삭제 근거 확보 |
| ③ | 삭제 메커니즘 | DROP PARTITION vs DELETE — DROP 채택(625배, 디스크 즉시 회수) | ✅ 실측 완료 |
| ④ | 파티션 단위 | 월별 — 너무 잘게(일별) 관리부담, 너무 크게(연별) 만료 단위가 거칠어짐 | ✅ 월별 14파티션 + `pfuture`, 실스키마 반영(`mysql/schema.sql`, PR #43) |
| ⑤ | 실행 트리거 | 스케줄러가 주기적으로 DROP/ADD PARTITION 실행 | ✅ `PoseDataPartitionScheduler` 신설, 매일 새벽 4시(`@Scheduled(cron=...)`), `SessionTimeoutScheduler`와 동일 패턴 |
| ⑥ | 파티션 유지보수 | 미래 파티션(`pfuture`)이 계속 커지지 않도록, 다다음 달 파티션을 미리 만들어둬야 함 | ✅ 이번 달 기준 +2개월(`lookahead-months`)까지 항상 실명 파티션 유지, 부족하면 `REORGANIZE PARTITION pfuture INTO (...)`로 확장 |

**결정된 안전장치 (2026-07-24 확정)**:
- **보존 기간**: 이번 달(쓰기 중) + 지난 1개월(버퍼, `retention-buffer-months: 1`)만 남기고 그 이전 파티션 드롭. precompute가 이미 있어 원본을 오래 붙들 product 근거가 없고, 개인정보보호법 제21조 "지체없이 파기" 취지상 짧게 유지하는 쪽으로 판단.
- **아카이빙 여부**: **안 함 — 완전 폐기**. S3 이전은 "저장 위치 변경"일 뿐 파기가 아니고, precompute로 핵심 요약은 이미 `reports`에 남아 있어 원본을 따로 보관할 이유가 약함. 새 인프라(S3 버킷·수명주기)를 벌이는 것도 `db-portfolio-roadmap.md §9` "의식적으로 안 할 것" 기조와 안 맞음.
- **경계 안전마진 + 실행 전 검증**: 정보스키마(`information_schema.partitions`)에서 조회한 파티션 이름이 `pYYYY_MM` 패턴과 정확히 일치할 때만 드롭 후보로 인정(패턴 불일치 시 건드리지 않고 경고만), `pfuture`는 쿼리 단계에서부터 제외. 드롭 직전 예상 행수를 로그로 남겨 사후 감사 흔적 확보(실행을 막진 않음 — 운영 자동화라 사람 확인 없이 돎).
- **실행 주기**: 매일 1회(새벽 4시). 월 단위 하우스키핑이라 `SessionTimeoutScheduler`(1분 주기)만큼 자주 돌 필요 없고, 매번 실제 파티션 목록을 다시 조회해 판단하므로 어느 날 실행되든 멱등적으로 안전.

**결론**: TTL 보존 정책 6가지 축 + 안전장치까지 전부 결정·구현 완료. precompute-on-write가 먼저 풀리면서 자연스럽게 이어진 마지막 조각이었음 — [`27-implementation-gaps.md`](../tasks/27-implementation-gaps.md)에도 완료로 반영.

---

## 10. 관련 문서
- [`../portfolio/db-deep-dive.md`](../portfolio/db-deep-dive.md) §B(읽기 최적화)·§2-0(raw→buffer/serving 분리)
- [`../portfolio/realmysql-experiments.md`](../portfolio/realmysql-experiments.md) §②b(projection 측정)
- [`./load-test-strategy.md`](./load-test-strategy.md) §4.6(살아남는 최적화 스토리)
- [`./report-aggregation.md`](./report-aggregation.md) — 리포트 집계 로직 설계
- [`./pose-ingest-downsampling.md`](./pose-ingest-downsampling.md) — 적재 다운샘플(⑥ 완화)
- 코드: `ReportService.java`(읽기 경로), `PoseDataRepository.java`, `ExerciseAnalysisService.java:217`(완료 콜백), `SessionRepository.java`(세션 쿼리), `mysql/schema.sql:55,86`(인덱스)
