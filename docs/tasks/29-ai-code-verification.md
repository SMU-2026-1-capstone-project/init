# AI 작성 코드 전수 검증 로그

작성: 2026-08-01 · 갱신: 2026-08-01 (#79 등록, #78 원인 보강)
상태: **진행 중** — 어제(2026-07-31) 머지분 중 Spring 재부착 경로·ai-server 경로·#72·#73 완료, 테스트 3종 상세 미완
목적: [`26-vacation-semester2-portfolio-plan.md`](./26-vacation-semester2-portfolio-plan.md) §3 **8월 항목 ③ "내 AI 코드 전수 검증"** 의 실행 기록
연관: [`28-remaining-work-plan.md`](./28-remaining-work-plan.md) §6 · [`../decisions/session-resume-and-ai-state.md`](../decisions/session-resume-and-ai-state.md)

---

## 0. 왜 지금인가

[`26-vacation-semester2-portfolio-plan.md:122`](./26-vacation-semester2-portfolio-plan.md) 가 리스크로 적어둔 것:

> AI 코드 미이해 → 라이브 질문 붕괴 → ③에 전수 검증 시간 사수 (8월)

2026-07-31 하루에 PR 5개가 머지됐고 그중 #74 하나가 **21 파일 크로스 레포**였다. 검증 대상이 하루 만에 늘었고, 8월은 계획상 구현이 아니라 ③ 버킷(면접 준비)의 달이다.

**검증 방식**: PR 리뷰하듯 실제 diff 를 읽고, 결정마다 *"왜 이렇게 했나"* 에 답할 수 있는지 확인한다. 답이 안 되거나 **주석이 사실과 다른 지점**을 찾는 것이 목표다. 결함이 나오면 이슈로 등록한다([[feedback_troubleshooting_to_issues]]).

---

## 1. 진행 상황

| 대상 | 상태 |
|---|---|
| #74 Spring 재부착 경로 (서비스·컨트롤러·DTO·ErrorCode·마이그레이션) | ✅ |
| #74 ai-server 경로 (`exercise_servicer`·`session_state`·`pose.py`·`squat_analyzer`) | ✅ |
| #72 종목 가드 | ✅ |
| #73 진행 중 세션 조회 | ✅ |
| 테스트 3종 내용 상세 (552줄) | ⬜ 이름만 훑음 |
| ai-server 테스트 (`test_session_reattach.py` 144줄) | ⬜ |

---

## 2. 나온 결함 — 이슈 6건

| # | 내용 | 상태 |
|:--:|---|---|
| [#75](https://github.com/Shadowfit/init/issues/75) | 싱크 통계가 후반 구간만 반영 / max·min 미저장 / **rep 있는데 0.0 저장** | **수정 완료** (`ca17ec0`) |
| [#76](https://github.com/Shadowfit/init/issues/76) | 재부착이 트랜잭션 안에서 블로킹 gRPC → 커넥션 풀 고갈로 번짐 | **수정 완료** (`8bc417f`) |
| [#77](https://github.com/Shadowfit/init/issues/77) | 재부착 검증과 gRPC 사이 잠금 없음 (TOCTOU) | 열림 — **결과 확인이 먼저** |
| [#78](https://github.com/Shadowfit/init/issues/78) | worst 구간이 프레임 해상도를 가정하는데 `sync_rate` 는 rep 단위 상수 | 열림 — 2026-08-01 코멘트로 **원인 보강** (§2-3) |
| [#79](https://github.com/Shadowfit/init/issues/79) | 다운샘플의 "worst 프레임 대표추출"이 실행되지 않는다 — 항상 첫 프레임만 남는다 | 열림 (§2-3) |
| [#80](https://github.com/Shadowfit/init/issues/80) | worst `reason` 이 싱크로율의 동어반복 / `pickDominantFeedback` 죽은 코드 / `is_correct` 읽는 곳 없음 | 열림 — 처리 방향은 [`../decisions/worst-section-rep-resolution.md`](../decisions/worst-section-rep-resolution.md) §8-3 에 확정 |
| — | `Exercise` 캐시 TTL 1시간 vs "플래그만 TRUE 로 바꾸면 열린다" 주석 | 미등록 (영향 낮음) |

**#75·#78·#79·#80 은 뿌리가 하나다**: `sync_rate` 가 rep 단위로 채점돼 프레임마다 복제 저장된다는 것(§5). 같은 사실을 **네 곳**이 서로 다르게 오해했다 — 집계는 프레임 가중으로(#75), worst 는 프레임 해상도가 있다고(#78), 다운샘플은 프레임마다 값이 다르다고(#79), reason 은 최빈값이 의미 있다고(#80). **하나씩 발견됐다는 게 핵심이다** — 셋을 고칠 때까지 넷째를 못 봤다.

### 2-1. #75 가 특히 중요했던 이유 — 고친 버그가 다른 문으로 돌아왔다

커밋 `0914082` 가 *"평균 싱크로율에서 null 세션을 0점으로 집계하던 것"* 을 고치면서 `filter(Objects::nonNull)` 을 넣었다. 그런데 그 방어는 **null 만** 막는다.

재부착 후 rep 을 하나도 안 하고 끝내면 AI 가 `avg=0.0` 을 보내고 Spring 이 **실제 값으로 저장**한다 → `nonNull` 을 통과 → 월 평균이 내려간다. **같은 증상이 필터로 막을 수 없는 경로로 재현되고 있었다.**

교훈: *"방어를 넣었다"* 와 *"그 방어가 모든 유입 경로를 덮는다"* 는 다르다. 이건 면접 답변으로 쓸 만하다.

### 2-2. #78 은 사용자 질문에서 나왔다

검증 중 사용자가 **"rep 안의 각 회차마다 sync_rate 가 달라야 하는 거 아니냐"** 고 물었다. 그 질문이 `WorstSectionCalculator` 를 드러냈다 — 3프레임 슬라이딩 윈도우로 worst 구간을 잡는데, `sync_rate` 는 rep 안에서 상수라 **윈도우가 방어한다는 노이즈가 존재하지 않는다.** 게다가 다운샘플로 행이 3개 미만 남은 rep 은 자기 값만으로 윈도우를 못 채워 **실제로 가장 나쁜 rep 인데도 worst 로 안 뽑힌다.**

`session-resume-and-ai-state.md` §3-3 은 이걸 *"고정 프레임 윈도우를 쓰고 **그게 의도된 설계**"* 로 적어뒀는데, 의도는 그랬어도 데이터가 받쳐주지 않는다.

### 2-3. 같은 질문을 한 번 더 밀어서 나온 것 — #79 와 #78 원인 보강

사용자가 **"rep 구분 안 했지 않았니"** 라고 되물어 같은 뿌리를 두 곳 더 확인했다.

**#78 — 증상이 아니라 원인**: 원 이슈는 *"경계를 걸친 윈도우가 뽑히면 실재하지 않는 점수가 나간다"* 로 증상만 적었는데, 실제로는 **읽기 경로가 rep 을 알 수조차 없다.** `PoseFrameProjection`(`PoseFrameProjection.java:4`)이 `(timestampSec, syncRate, feedbackMessage)` 3개뿐이라 `rep_number` 를 안 싣는다. 컬럼은 #74 에서 추가됐지만(`2026-07-31-add-pose-data-rep-number.sql`) 이 프로젝션은 그 전에 만들어졌고 갱신되지 않았다. → **3안 어느 쪽을 골라도 프로젝션+쿼리 수정이 공통 선결**이고, 생성자 프로젝션이라 읽기(`ReportService`)·쓰기(`SessionService.precomputeReport`) 양쪽 호출부가 함께 깨진다.

**#79 — 죽은 비교**: `downsampleByWorstSync`(`PoseDataService.java:104-117`)가 5프레임마다 최저 `sync_rate` 를 고른다는데, 배치 1개가 rep 1개고 그 안의 값이 전부 같아 **엄격 부등호(`<`)가 참이 되지 않는다.** 실제 동작은 매 5프레임 중 첫 프레임만 남기는 균등 샘플링이다. `sync_rate` 값 자체는 상수라 집계에는 영향이 없지만, **`joint_coordinates` 는 프레임마다 다르므로 어느 좌표가 남는지가 달라진다.**

교훈: **하나의 잘못된 전제는 한 곳에서만 틀리지 않는다.** #75 를 고칠 때 "rep 단위 상수"라는 사실을 이미 알고 있었는데, 그 사실을 **다른 소비자들에게 되짚지 않았다.** 결함을 고칠 때 "이 사실을 오해한 곳이 또 어디인가"를 묻는 절차가 없었던 셈이다.

---

## 3. 확인했는데 문제 없던 것

검증은 "결함 목록"만 남기면 절반이다. **틀렸으면 컸을 것들이 맞게 돼 있다**는 것도 기록해 둔다.

| 확인한 것 | 결과 |
|---|---|
| **재부착을 반복하면 rep 이 되돌아가나?** | ✅ `state.rep_count += 1`(`squat_analyzer.py:294`)이 `rep_number=state.rep_count`(`:331`)보다 **먼저** 실행돼, 8 에서 재부착하면 다음 rep 이 9 로 저장된다. `MAX(rep_number)` 가 역행하지 않는다 |
| 스케줄러 N+1 | ✅ `findByStatus` 에 `JOIN FETCH s.exercise`(`SessionRepository.java:53`) |
| `analysis_supported` 마이그레이션 파일 누락? | ✅ 있다 (`mysql/migrations/2026-07-31-add-exercises-analysis-supported.sql`) |
| `create_if_absent` 의 "확인·생성이 한 Lock 안" 주장 | ✅ 주석대로 정확 |
| 테스트 촘촘함 | ✅ 남의 세션/없는 세션을 **둘 다 404 로 통일**한 것까지 회귀로 고정돼 있다 |

---

## 4. 주석이 사실과 달랐던 것 ★ 면접 대비

**이 절이 이 문서의 핵심이다.** 코드는 맞는데 *설명이 틀린* 지점은, 그대로 외우면 면접에서 반박당한다.

| 위치 | 적혀 있는 말 | 실제 | 정확한 답 |
|---|---|---|---|
| `SessionRepository.java:68` | "`@Query + JOIN FETCH` 로도 되지만 그러면 **LIMIT 을 SQL 에 못 실어** 전 행을 가져와야 한다" | **to-one 조인엔 해당 없다.** Hibernate 가 `maxResults` 를 인메모리로 처리하는 건 **컬렉션 fetch join 일 때만**(`HHH000104`). 바로 다음 문장이 스스로 반박한다 | "JPQL 에 `LIMIT` 문법이 없어 `@Query` 로 가면 `Pageable` 을 끼워야 하는데, 파생 쿼리(`findFirst`) + `@EntityGraph` 면 그냥 된다. **못 하는 게 아니라 이게 더 간단하다**" |
| `SessionService.java:78` | "`@RequiredArgsConstructor` 라 생성자 파라미터로는 **못 넣어** 필드 주입을 쓴다" | 생성자를 직접 쓰거나 `@ConfigurationProperties` 면 된다 | "**Lombok 이** `@Value` 붙은 파라미터를 못 만든다. 필드 주입은 그 대가다" |
| `WorstSectionCalculator.java:20-22` | "단일 프레임은 **노이즈 영향이 커서** 구간으로 본다" | `sync_rate` 가 rep 안에서 상수라 그 노이즈가 없다 | → [#78](https://github.com/Shadowfit/init/issues/78) |
| `PoseDataService.java:99-103` | "**평균이 아니라 극값을 남기는 이유**는 리포트가 '가장 안 좋았던 순간'을 필요로 하기 때문" | 비교가 참이 되지 않아 극값 선택이 **실행되지 않는다.** `pose-ingest-downsampling.md` §4 의 "평균 vs 대표추출" 비교도 같은 전제 위라 함께 무효 — 두 선택지가 실제로는 구분되지 않는다 | → [#79](https://github.com/Shadowfit/init/issues/79). 다만 **R≈5 라는 비율 자체는 유효**하다("몇 개를 남기나"의 실험이고 #79 는 "그중 어느 것"의 문제) |
| `#72` 커밋 메시지 | "분석기가 붙으면 **플래그만 TRUE 로 바꾸면 열린다**" | `Exercise` 캐시가 `expireAfterWrite=1h`(`application.yml:53`). 직접 SQL `UPDATE` 는 `@CacheEvict` 를 안 타서 **최대 1시간 지연 또는 재시작 필요** | "플래그를 바꾸고 캐시를 비우거나 TTL(1시간)을 기다려야 한다" |

### 4-1. 설명이 잘 준비된 것

| 결정 | 답변 재료 |
|---|---|
| `findFirst`(LIMIT 1) | "회원당 활성 세션 1개"는 **DB 제약이 아니라 애플리케이션 규약**이다(유니크 제약은 `member_id` FK 의 `ON DELETE CASCADE` 때문에 MySQL 이 막아 폐기, `SessionService.java:91-94`). 규약이 깨지면 단건 시그니처는 `NonUniqueResultException` 을 던지는데, 하필 **갇힘을 푸는 API 가 갇힘 때문에 터진다** |
| proto3 기본값 활용 | 구버전 AI 가 `rep_number` 를 안 보내도 0 이 들어오고 컬럼 DEFAULT 와 같아 **배포 순서 무관**(`PoseDataService.java:75-77`) |
| 타임아웃 식을 엔티티로 | 스케줄러와 재부착 판정이 **같은 식**을 쓰도록 강제(`Session.timeoutThreshold`) |
| `COALESCE(MAX(rep_number), 0)` | 프레임 0건 세션에서 호출부 null 분기를 없애고, `rep_number` 의 "미상"도 0 이라 의미가 일치 |
| DTW 가 rep 단위인 이유 | 사람마다 운동 속도가 달라 프레임 번호를 1:1 로 맞댈 수 없다. DTW 는 **시간축을 늘였다 줄여** 정렬하므로 입력이 시퀀스 한 쌍·출력이 숫자 하나다 — 그래서 점수의 자연스러운 단위가 rep 이 된다 |

---

## 5. 도메인 사실 정리 (헷갈렸던 것)

| 개념 | 현재 |
|---|---|
| 세트 | **스키마에 없다.** `SetSummaryFormatter` 가 1 로 고정해 `"1세트 x N회"` 표기. BE-09 에서 `Session.setCount` 로 교체 예정 |
| 회차(rep) | `exercise_sessions.total_reps`. **회차마다 sync_rate 가 다르다**(rep 마다 DTW 재계산) |
| `pose_data.sync_rate` | rep 단위 값인데 **프레임 행마다 복제** 저장 — `(session_id, rep_number)` 에 종속. #75 가 `GROUP BY rep_number` 로 되접는 이유이자 #78 의 뿌리 |

⚠️ **세트 도입(BE-09) 시 확인할 것**: `rep_number` 는 지금 **세션 전체 기준 연번**이고, 재부착이 `MAX(rep_number)` 로 이어붙이는 것도 그 전제에 기댄다. 세트별로 1 부터 다시 세면 `MAX` 가 깨진다.

---

## 6. 코드 변경 (미푸시)

| 브랜치 | 커밋 | 내용 |
|---|---|---|
| `fix/session-stats-and-tx-boundary` | `8bc417f` | #76 — DB 작업을 `loadReattachRequest` 로 분리해 gRPC 를 트랜잭션 밖으로. 애노테이션 배치를 고정하는 회귀 테스트 추가 |
| | `ca17ec0` | #75 — `pose_data` rep 단위 집계, max/min 저장, 측정없음=null, 구버전 AI 폴백, 잠복 NPE 3개 |
| `docs/plan-sync-and-integration-candidates` | `adee12b` | 계획 동기화 + [`../decisions/external-integration-candidates.md`](../decisions/external-integration-candidates.md) 신설 |

**전체 테스트 223개 통과**(신규 6개: 싱크 통계 5 + 트랜잭션 경계 1).

---

## 7. 다음에 이어갈 것

- [ ] 브랜치 2개 **푸시 + PR** (문서 / 수정 分리)
- [ ] 테스트 3종 상세 검증 (552줄) + ai-server 테스트
- [ ] [#77](https://github.com/Shadowfit/init/issues/77) — 고치기 전에 **"그때 실제로 무슨 일이 나는지"** 부터 확인
- [ ] [#78](https://github.com/Shadowfit/init/issues/78) — 고치는 방법 3안 결정. **비교 문서 완료** → [`../decisions/worst-section-rep-resolution.md`](../decisions/worst-section-rep-resolution.md)(ㄱ안 추천, ㄴ 배제 권고, ㄷ 별도 트랙). 조사 중 확인한 것: `dtaidistance` 가 워핑 경로 API(`dtw.warping_path`)를 제공하는데 지금은 `dtw.distance()` 로 **거리만 받고 경로를 버린다.** ㄷ안(프레임별 점수)이 새 알고리즘이 아니라 **버리는 중간 산출물을 살리는 일**일 수 있다 — ⚠️ 코드를 읽고 판단한 것이며 돌려보지 않았다
- [ ] [#79](https://github.com/Shadowfit/init/issues/79) — **#78 과 같이 결정하는 게 싸다.** #78 ㄷ안(프레임별 점수)이 채택되면 #79 는 코드를 고치지 않아도 의도대로 동작한다. 그 전엔 `pose-ingest-downsampling.md` §4 에 최소한 정정 표시가 필요하다(이번 커밋 범위 밖)
- [ ] `applyCompleteFromApp`(앱 보고 경로)은 여전히 dto 값을 그대로 쓴다 — #75 를 이 경로에도 적용할지
- [ ] §4 의 부정확한 주석 5건 정정
- [ ] **같은 뿌리를 오해한 곳이 더 없는지** — `sync_rate` 를 읽는 소비자를 전수로 훑은 적이 없다(§2-3 교훈)

---

## 8. 정직 단서

- **전수가 아니다.** 어제 머지분(#72·#73·#74)만 봤고, 그 이전 코드(아웃박스·파티셔닝·관측성 등)는 이 문서 범위 밖이다
- #77·#78·#79 는 **코드 경로 추적으로 판단했고 재현하지 않았다.** 각 이슈에 미검증으로 명시. 특히 #79 의 "배치 1개 = rep 1개"는 `ai-server/app/api/endpoints/pose.py` 경로만 확인한 것이다
- §3 "문제 없던 것"은 **내가 확인한 항목에 한한다** — 확인 안 한 것이 없다는 뜻이 아니다
