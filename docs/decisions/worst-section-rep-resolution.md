# Decision: worst 구간을 어느 해상도로 계산할 것인가 (이슈 #78)

상태: **✅ ㄱ안 채택 확정 + 구현 완료 (2026-08-01)** — [PR #82](https://github.com/Shadowfit/init/pull/82) 리뷰 대기. 남은 미결은 §9
작성: 2026-08-01 / 결정: 2026-08-01 / 구현: 2026-08-01
배경: 코드 검증([`../tasks/29-ai-code-verification.md`](../tasks/29-ai-code-verification.md) §2-2)에서 `WorstSectionCalculator` 가 **프레임마다 `sync_rate` 가 다르다**는 전제로 3프레임 슬라이딩 윈도우를 도는데, 실제 데이터는 **rep 안에서 상수**라는 것이 드러났다. 사용자가 *"rep 구분 안 했지 않았니"* 로 되물어 원인이 하나 더 나왔다 — 읽기 프로젝션에 `rep_number` 가 아예 없다.
연관: [#78](https://github.com/Shadowfit/init/issues/78) · [#79](https://github.com/Shadowfit/init/issues/79) · [#80](https://github.com/Shadowfit/init/issues/80) · [#75](https://github.com/Shadowfit/init/issues/75) · [`./report-read-path.md`](./report-read-path.md) §9(precompute-on-write) · [`./pose-ingest-downsampling.md`](./pose-ingest-downsampling.md) §4 · [`../tasks/28-remaining-work-plan.md`](../tasks/28-remaining-work-plan.md) §4

> 🟢=추천, 🔶=열림, ❌=배제 권고. **시간은 실측이 아니라 추정이다**(§9).

---

## 0. 한 줄 요약

**데이터에는 rep 경계가 있는데 코드가 그걸 안 본다.** 고치는 방법은 세 갈래인데, 갈림길의 본질은 *"버그를 고칠 것인가"* 가 아니라 **"싱크로율의 해상도를 rep 에 맞출 것인가, 프레임까지 올릴 것인가"** 다. 앞의 둘(ㄱ·ㄴ)은 Spring 단독·데이터 그대로, 뒤(ㄷ)는 크로스 레포·알고리즘 설계다.

추천은 **ㄱ안(rep 단위 계산) 1순위**다. ㄷ안은 좋은 과제지만 **버그 수정이 아니라 기능 개선**이라 같은 줄에 놓고 비교하면 판단이 흐려진다 — 별도 트랙으로 분리할 것을 권고한다.

---

## 1. 사실 관계 — 왜 이렇게 됐나

싱크로율은 **프레임이 아니라 rep 단위로 채점된다.**

| 단계 | 코드 | 하는 일 |
|---|---|---|
| 채점 | `ai-server/app/core/squat_analyzer.py:303-336` `_summarize_rep` | rep 하나의 각도 시퀀스 ↔ 기준 시퀀스를 DTW 로 비교해 **숫자 하나** 산출 |
| DTW | `ai-server/app/core/dtw_calculator.py:34-41` `compute_sync_rate` | `100 * exp(-distance / 30)`. `distance` 는 관절 수·시퀀스 길이로 정규화된 스칼라 |
| 복제 | `ai-server/app/api/endpoints/pose.py:111` | `sync_rate=rep_event.sync_rate` 를 **그 rep 의 모든 프레임에 복사** |
| 저장 | `backend/.../PoseDataService.java:80` | 프레임 행마다 같은 값이 들어간다 |

즉 `pose_data.sync_rate` 는 **`(session_id, rep_number)` 에 종속인 값**인데 프레임 행마다 중복 저장돼 있다.

**DTW 가 rep 단위인 건 우연이 아니다.** 사람마다 운동 속도가 달라 프레임 번호를 1:1 로 맞댈 수 없어서 시간축을 늘였다 줄여 정렬하는 것이고, 그 연산의 입력은 시퀀스 한 쌍·출력은 숫자 하나다. **점수의 자연스러운 단위가 rep 이 된다.** 지금 구조는 틀린 게 아니라 **의도대로 rep 해상도**이며, 틀린 건 그걸 프레임 해상도로 착각한 소비자 쪽이다.

---

## 2. 그래서 지금 무슨 일이 일어나는가

### 2-1. 읽기 경로가 rep 을 알 수조차 없다 ★ 이번에 새로 확인

`backend/src/main/java/com/shadowfit/dto/report/PoseFrameProjection.java:4`

```java
public record PoseFrameProjection(Double timestampSec, Double syncRate, String feedbackMessage) {}
```

`rep_number` 가 없다. 조회도 rep 무관하게 평평하다 — `PoseDataRepository.java:16-19` 의 `ORDER BY p.timestampSec ASC`.

컬럼 자체는 #74 에서 추가됐지만(`mysql/migrations/2026-07-31-add-pose-data-rep-number.sql`), **이 프로젝션은 그 전에 만들어졌고 갱신되지 않았다.** 현재 `rep_number` 를 읽는 곳은 재부착 시 `MAX(rep_number)` 하나뿐이다(`ExerciseAnalysisService.java:300`) — "몇 번까지 셌나"만 보고 rep 을 나누지는 않는다.

### 2-2. 그 결과 셋이 틀린다

| 결함 | 내용 |
|---|---|
| 주석의 근거가 없다 | `WorstSectionCalculator.java:20-22` 가 *"단일 프레임은 노이즈 영향이 커서 구간으로 본다"* 고 하는데, rep 안에서 상수라 **그 노이즈가 존재하지 않는다** |
| 짧은 rep 이 구조적으로 불리하다 | 다운샘플(R≈5)로 rep 당 남는 행이 rep 길이에 비례. 행이 3개 미만인 rep 은 자기 값만으로 윈도우를 못 채워 이웃의 높은 값이 섞인다 |
| 보고값이 어느 rep 의 점수도 아니다 | 경계를 걸친 윈도우가 뽑히면 평균이 나가는데 **실재하지 않는 점수**다 |

이슈 본문의 예시(#78):

```
rep1: 2행 @ 20   ← 실제로 가장 나쁜 rep
rep2: 3행 @ 25
rep3: 3행 @ 21
→ 리포트는 rep3 을 "가장 나빴던 구간"으로 보여준다
```

⚠️ 이 시나리오는 **손으로 만든 예시이며 실제 데이터로 재현하지 않았다.**

---

## 3. 세 안의 공통 선결 (ㄷ안 일부 제외)

"이 행이 몇 번째 rep 인가"를 알아야 하므로 **ㄱ·ㄴ 은 공통으로** 아래가 선행된다.

| 작업 | 파급 |
|---|---|
| `PoseFrameProjection` 에 `repNumber` 추가 | record 시그니처 변경 |
| `findFramesBySessionId` 의 `SELECT new` 인자·정렬 갱신 (`ORDER BY p.repNumber, p.timestampSec`) | JPQL 생성자 프로젝션이라 **컴파일 단위로 깨진다** |
| 호출부 2곳 | 읽기 `ReportService.java:87` · 쓰기 `SessionService.java:256` — 같은 컴포넌트를 공유하는 구조라(`WorstSectionCalculator.java:12-15`) 둘 다 영향 |
| 테스트 픽스처 | `WorstSectionCalculatorTest` 가 프로젝션을 직접 만든다 |

**ㄷ안만 이 선결이 필수가 아니다** — 프레임마다 값이 달라지면 rep 을 몰라도 윈도우가 의미를 갖기 때문이다. 다만 §4-ㄷ 의 하위호환 문제 때문에 결국 필요해질 수 있다.

---

## 4. 3안 비교

### 🟢 ㄱ안 — rep 단위 계산 (해상도를 데이터에 맞춘다)

윈도우를 버리고 **가장 낮은 rep 을 worst 로 뽑는다.** `sync_rate` 가 rep 안에서 상수이므로 rep 별 값을 그대로 비교하면 된다. 대표 timestamp 는 그 rep 프레임들의 중앙(또는 시작).

| | |
|---|---|
| **범위** | Spring 단독. ai-server·proto·DB 스키마 변경 없음 |
| **데이터** | 현재 저장분 그대로 사용 가능 |
| **#75 와의 시너지** | `findRepAverageSyncRates`(`AVG(syncRate) GROUP BY repNumber`)가 **이미 필요한 재료의 대부분**이다. rep 번호까지 같이 뽑도록 확장하면 재사용된다 — 단 그 쿼리는 아직 `fix/session-stats-and-tx-boundary` 브랜치에만 있다 |
| **계산기** | `WorstSectionCalculator` 가 크게 단순해진다(슬라이딩 윈도우·null 윈도우 배제 로직 제거) |
| **잃는 것** | "구간(section)"이라는 표현이 실제로는 "rep"이 된다 — DTO 이름·프론트 문구가 어긋날 수 있다(§9) |

**정직성 측면의 장점**: 데이터가 rep 해상도밖에 없는데 프레임 단위 정밀도를 가장하지 않게 된다. 지금은 `"03:42"` 같은 초 단위 timestamp 를 보여주는데 그 정밀도의 근거가 없다.

**추정**: 3~5h (프로젝션·쿼리 2h + 계산기 재작성 1~2h + 테스트 갱신 1h)

---

### ❌ ㄴ안 — 짧은 rep 보정 (윈도우를 rep 경계에서 자른다)

윈도우 구조는 유지하되 **rep 경계를 넘지 않게 자르고**, 행이 `WORST_WINDOW_SIZE` 미만인 rep 은 남은 행만으로 평균을 낸다.

| | |
|---|---|
| **범위** | Spring 단독 |
| **고쳐지는 것** | 결함 2·3 (짧은 rep 불리, 실재하지 않는 점수) |
| **안 고쳐지는 것** | **결함 1** — 윈도우가 방어한다는 노이즈는 여전히 존재하지 않는다 |

**배제 권고 사유**: rep 안에서 값이 상수라면, rep 경계로 자른 윈도우의 평균은 **그 rep 의 값 그 자체**다. 즉 ㄴ안은 계산 결과가 ㄱ안과 동일하면서 코드만 더 복잡하다. 윈도우를 남길 이유는 "나중에 ㄷ안이 오면 쓸모가 생긴다"뿐인데, 그건 **오지 않을 수도 있는 미래를 위해 지금 복잡도를 지불하는 것**이다.

⚠️ 단, "ㄴ안의 결과가 ㄱ안과 항상 같다"는 것은 **논증이며 테스트로 확인하지 않았다.** 다운샘플 경계나 null 섞임 같은 예외에서 갈릴 여지가 있는지는 검증이 필요하다.

**추정**: 4~6h (ㄱ안보다 비싸다)

---

### 🔶 ㄷ안 — 프레임별 점수 (해상도를 프레임까지 올린다)

ai-server 가 rep 하나에 점수 하나를 내는 대신, **DTW 워핑 경로를 이용해 프레임마다 점수를 낸다.**

지금은 `dtw_calculator.py:22` 가 `dtw.distance()` 로 **거리만 받고 경로를 버린다.** `dtaidistance==2.3.12` 는 `dtw.warping_path` / `dtw.warping_paths`(누적 비용 행렬)를 제공하므로, 원리상 "기준 시퀀스의 어느 프레임과 매칭됐고 그 지점의 비용이 얼마였나"를 꺼낼 수 있다.

| | |
|---|---|
| **범위** | **크로스 레포** — ai-server(알고리즘) + proto 영향 없음(`sync_rate` 필드 그대로) + Spring(하위호환) |
| **얻는 것** | worst 구간이 진짜 구간이 된다. 윈도우의 노이즈 방어도 근거가 생긴다. 다운샘플의 극값 선택(#79)도 **코드 수정 없이 의도대로 동작**한다 |
| **부수 효과** | 실시간 큐(`classify_sync_visual_cue`)가 rep 완성 시점이 아니라 **프레임마다** 나올 수 있다 — UX 개선이지만 이 문서 범위 밖 |
| **#75 와의 관계** | `AVG(syncRate) GROUP BY repNumber` 는 그대로 유효하고 **오히려 더 의미 있어진다**(rep 안 프레임들의 진짜 평균) |

**⚠️ "버리는 산출물을 살리는 일"이라는 표현은 과소평가일 수 있다.** 워핑 경로를 얻는 것과 **프레임별 점수를 정의하는 것은 다른 문제**다. 최소한 아래가 설계 결정으로 남는다:

- 다중 관절(`num_joints` 개)의 경로가 관절마다 다를 때 프레임 점수를 어떻게 합칠 것인가
- 한 user 프레임이 여러 ref 프레임에 매칭되는(워핑) 경우 비용을 어떻게 배분할 것인가
- 정규화 상수(`/30.0`, `max_len` 나눗셈)가 rep 전체 기준이라, 프레임 단위로 쓰려면 **스케일 재설계**가 필요하다 — 지금 척도를 그대로 쓰면 점수 분포가 달라져 `SYNC_THRESHOLDS`(40/70) 임계값이 의미를 잃는다

**하위호환**: 이미 저장된 행은 여전히 rep 단위 상수다. 읽기 경로가 **두 세대 데이터를 함께 다뤄야 한다** — 결국 §3 의 프로젝션 변경이 (구분 목적으로) 필요해질 가능성이 높다.

**추정**: 12~20h (알고리즘 설계·검증이 대부분, 임계값 재조정 포함). ⚠️ 이 추정은 **다른 항목들보다 신뢰도가 낮다** — 알고리즘 설계는 조사 전 추정이 방향까지 맞히지 못하는 대표적인 항목이다([`../tasks/28-remaining-work-plan.md`](../tasks/28-remaining-work-plan.md) §6).

---

## 5. 한눈에

| | ㄱ rep 단위 | ㄴ 짧은 rep 보정 | ㄷ 프레임별 점수 |
|---|:--:|:--:|:--:|
| 성격 | 버그 수정 | 버그 수정 | **기능 개선** |
| 레포 | Spring | Spring | **크로스 레포** |
| 결함 1(노이즈 근거) | 해소(윈도우 제거) | ❌ 미해소 | 해소 |
| 결함 2(짧은 rep) | 해소 | 해소 | 해소 |
| 결함 3(실재 않는 점수) | 해소 | 해소 | 해소 |
| [#79](https://github.com/Shadowfit/init/issues/79) 다운샘플 | 남음(문서 정정 필요) | 남음 | **자동 해소** |
| 데이터 마이그레이션 | 불필요 | 불필요 | 불필요(단 2세대 공존) |
| 추정 | **3~5h** | 4~6h | 12~20h |
| 추정 신뢰도 | 보통 | 보통 | **낮음** |

---

## 6. 과거 리포트는 안 고쳐진다 (모든 안 공통)

precompute-on-write 로 **이미 `reports.detailed_analysis` 에 저장된 worst 값들**이 있다(`SessionService.java:255-271`). 읽기 경로는 저장된 게 있으면 그대로 읽고 재계산하지 않는다(`ReportService.java:78-89`).

즉 **어느 안을 골라도 과거 리포트는 잘못된 채로 남는다.** 선택지:

| | 내용 | 비고 |
|---|---|---|
| 방치 | 새 세션부터 맞음 | 시연·포폴 관점에서 문제 없음. 실사용자가 없다 |
| 백필 | `detailed_analysis` 를 재계산해 덮어씀 | 배치 1회. `pose_data` 는 TTL 로 삭제되므로 **원본이 남아 있는 세션만 가능** |
| 무효화 | 해당 컬럼을 비워 즉석 재계산 경로로 흘림 | 위와 같은 TTL 제약 + 읽기 비용 증가 |

### 6-1. 실측 (2026-08-01) — **백필 대상 0건**

로컬 개발 DB(`shadowfit-mysql`, Docker)에서 직접 셌다.

| 항목 | 값 |
|---|:--:|
| `reports` 전체 | **7** |
| 그중 `detailed_analysis` 가 채워진 행 | **0** |
| `pose_data` 전체 행 | **0** |
| `exercise_sessions` | 7 (전부 `COMPLETED`) |

**틀린 worst 가 박제된 행이 하나도 없다.** `reports` 7건은 전부 시드다 — `mysql/data.sql:92` 의 `REPLACE INTO reports (id, session_id, member_id, report_type, summary, improvement_tips, created_at)` 가 `detailed_analysis` 컬럼을 아예 넣지 않는다. `pose_data` 시드도 없다(0건).

즉 이 DB 에는 **실제 운동 데이터가 없고**, precompute-on-write 가 한 번도 돈 적이 없다. 지금 이 7건을 조회하면 `detailed_analysis` 가 비어 즉석 재계산 경로로 흐르는데(`ReportService.java:87-88`), `pose_data` 가 0행이라 `WorstSectionCalculator` 가 `null` 을 돌려준다 — **틀린 값이 아니라 값이 없는 상태**다.

→ **§6 의 세 선택지(방치/백필/무효화)는 실행 대상이 없어 자동 해소된다.** 결정할 것이 남아 있지 않다.

⚠️ **로컬 DB 기준이다.** EC2 배포분([`../tasks/28-remaining-work-plan.md`](../tasks/28-remaining-work-plan.md) §2-4, 2026-07-25 풀 사이징 재검증)은 확인하지 않았다. 다만 상시 구동이 아니고 실사용자가 0 이라 규모가 다를 근거는 없다. 배포분을 다시 띄울 일이 있으면 같은 쿼리를 한 번 돌려보는 것으로 충분하다.

---

## 7. #79 와의 관계

[#79](https://github.com/Shadowfit/init/issues/79)(다운샘플의 극값 선택이 실행되지 않음)는 **뿌리가 같다**.

- **ㄱ·ㄴ 을 고르면** #79 는 남는다 → 별도로 처리해야 한다. 최소 조치는 `pose-ingest-downsampling.md` §4 의 "평균 vs 대표추출" 비교에 정정 표시를 다는 것(그 비교는 두 선택지가 실제로 구분되지 않으므로 무효다)
- **ㄷ 을 고르면** #79 는 **코드를 고치지 않아도 해소된다** — 프레임마다 값이 달라지므로 극값 선택이 진짜로 동작한다

→ **#78 을 결정하면 #79 의 처리 방향이 딸려 나온다.** 따로 결정할 필요가 없다.

---

## 8. 권고

### 🟢 ㄱ안(rep 단위 계산)을 지금 하고, ㄷ안은 별도 트랙으로 분리

근거:

1. **ㄷ안은 버그 수정이 아니다.** 지금 사용자에게 틀린 "가장 나빴던 구간"이 나가고 있는데, 그걸 멈추는 데 12~20h 짜리 알고리즘 설계를 선결로 걸 이유가 없다. ㄱ안이면 3~5h 로 **오늘 멈춘다.**
2. **ㄱ안은 ㄷ안을 막지 않는다.** 나중에 ㄷ안을 하면 그때 윈도우를 다시 넣으면 된다 — 그 시점엔 넣을 근거가 실제로 생긴다. 반대로 ㄴ안은 "미래를 위해 지금 복잡도를 지불"하는 형태라 순서가 거꾸로다.
3. **재료가 이미 있다.** #75 의 `findRepAverageSyncRates` 가 rep 단위 집계를 이미 한다.
4. **정직성이 올라간다.** 데이터에 없는 정밀도를 가장하지 않게 된다 — 이건 포폴에서 방어 가능한 서사다(§8-1).

### 8-1. 면접 서사 관점

ㄱ안을 고르면 답할 수 있는 게 이렇게 된다:

> "DTW 는 시퀀스 한 쌍을 받아 숫자 하나를 내놓기 때문에 점수의 자연스러운 단위가 rep 입니다. 그런데 리포트 쪽 코드가 프레임 단위 해상도를 가정하고 있었고, 슬라이딩 윈도우가 방어한다는 노이즈는 실제로 존재하지 않았습니다. **해상도를 데이터에 맞추는 쪽으로 정리**했고, 프레임 해상도가 필요하면 DTW 워핑 경로를 살리는 게 다음 단계인데 그건 임계값 재설계가 붙는 별도 과제로 뒀습니다."

ㄷ안을 어설프게 하면 이 서사가 **"정밀도를 올렸는데 임계값은 그대로"** 로 바뀌어 오히려 약해진다.

### 8-2. 반대 근거 (ㄷ안을 지금 할 만한 경우)

**AI 정확도 자체를 포폴 축으로 밀 생각이라면** 이야기가 다르다. 워핑 경로 활용은 DTW 를 "라이브러리 호출"이 아니라 "이해하고 쓴 것"으로 보이게 하는 소재다. 다만 [[project_keep_server_ai_architecture]] 방침상 AI 는 **면접 설계 서사로만 활용**하기로 했고, 지원 포지션도 백엔드(Spring)라 이 축의 우선순위는 낮다.

---

## 8-3. ㄱ안 채택 후 확정된 하위 결정 (2026-08-01)

### 대표 timestamp = **rep 중앙 프레임** ✅

기존 동작(윈도우 중앙)과 가장 가까워 변화가 작다. 검토했으나 배제한 것:

- *rep 시작 프레임* — 되감기 지점으로는 더 자연스럽지만, 지금 바꿀 이유가 약하다
- *가장 깊었던 지점* — ❌ **무릎각 컬럼이 없다.** `pose_data` 는 `(timestamp_sec, joint_coordinates, sync_rate, is_correct, feedback_message)` 뿐이라 `joint_coordinates`(2.3KB JSON) 를 파싱해야 하는데, 그건 `PoseFrameProjection` 이 애초에 피하려던 off-page I/O 를 되살리는 일이다

### `reason` = **동어반복 제거** ✅ (정확한 문구는 미정)

`reason` 이 지금 **싱크로율을 말만 바꿔 되풀이한다** — 사용자 질문 *"reason 을 어떻게 아는데"* 에서 나왔다. → [#80](https://github.com/Shadowfit/init/issues/80)

```
"싱크로율 21% · 즉시 자세 수정 필요"
       └───┬───┘   └────────┬────────┘
        원본 값        그 값을 임계값과 비교한 결과
```

`feedback_message` 는 문자열 3개가 전부이고 전부 `sync_rate` 에서 파생된다(`ai-server/app/core/squat_analyzer.py:320-328`). **관절별 진단이 스트리밍 경로에 없다** — 그런 문구는 영상 일괄 분석 경로(`analyze_squat`)에만 있고 영어이며 `pose_data` 로 흘러가지 않는다.

따라서 `pickDominantFeedback`(`WorstSectionCalculator.java:83-95`)의 최빈값 계산은 **#79 와 같은 형태의 죽은 코드**다(rep 안에서 상수라 3개를 세도 항상 같은 값 하나).

**결정**: 동어반복을 걷어내고 `pickDominantFeedback` 을 삭제한다. **최종 문구는 구현 시점에 정한다**(2026-08-01 사용자 판단 보류).

> 진짜 진단(무릎/상체 각도 기반 사유)을 만드는 안은 [#80](https://github.com/Shadowfit/init/issues/80) ㄷ안으로 열려 있다 — §4-ㄷ 와 같은 급의 작업이라 이 범위에 넣지 않는다.

### ~~DTO 구조는 그대로 둔다 (파생)~~ → ⚠️ **되돌렸다** (2026-08-01, 같은 날)

원문: *"문구만 바꾸므로 `WorstSectionDto` 의 필드 구조는 유지된다 → 프론트 영향 없음."*

**회차별 추이(§8-4)를 응답에 넣으면서 성립하지 않게 됐다.** 추이가 없을 때는 worst 와 이을 대상 자체가 없어 구조를 건드릴 이유가 없었는데, 추이가 생기자 **"추이의 어느 점이 worst 인가"** 를 답할 수단이 필요해졌다.

그 수단이 없으면 클라이언트가 쓸 방법이 둘뿐인데 둘 다 취약하다:

| 방법 | 문제 |
|---|---|
| `timeStamp` 문자열 비교 | `mm:ss` 라 같은 초에 걸친 rep 이 둘이면 모호. 포맷이 바뀌면 깨진다 |
| `reason` 에서 `"2회차"` 파싱 | **`reason` 문구가 잠정이다**(위 §8-3). 확정하는 순간 프론트가 깨진다 |

→ **`WorstSectionDto.repNumber` 추가로 확정**(2026-08-01 사용자 confirm). 검토했으나 배제한 대안은 `RepSyncRateDto.isWorst` 플래그다 — 클라 로직은 0 이 되지만 같은 사실이 두 곳에 저장돼 계산이 어긋나면 응답이 자기모순이 된다.

**교훈**: "프론트 영향 없음"은 그 시점의 응답 모양을 전제로 한 판단이었다. 응답에 필드를 하나 더하는 결정이 이전 결정의 전제를 무너뜨렸는데, 두 결정 사이의 간격은 몇 시간이었다.

### 8-4. 회차별 추이 (`repTrend`) — 범위 추가 (2026-08-01)

ㄱ안 구현 직후 *"rep 별로 구분이 되는 건가"* 라는 확인 과정에서 나왔다. worst 는 **가장 나빴던 한 회차**만 알려주므로 *"3회차부터 계속 떨어졌다"* 같은 흐름을 볼 수 없었고, 데이터는 `pose_data` 에 rep 별로 이미 있었다 — **노출 경로만 없었다.**

```json
"worstSection": { "repNumber": 2, "exerciseName": "스쿼트", "timeStamp": "01:15", "reason": "2회차 · 싱크로율 75%" },
"repTrend": [
  { "repNumber": 1, "syncRate": 80.0, "timeStamp": "00:12" },
  { "repNumber": 2, "syncRate": 75.0, "timeStamp": "01:15" }
]
```

**저장은 precompute-on-write 에 얹었다.** worst 와 재료(rep 그룹핑)가 같아 이미 읽어 온 프레임으로 바로 나온다. 조회 시점 계산으로 두면 **precompute 가 없애려던 `pose_data` 스캔이 추이 때문에 되살아난다**([`./report-read-path.md`](./report-read-path.md) §9 의 목적이 무효가 된다). `reports.detailed_analysis` 에 `{worstSection, repTrend}` 로 함께 넣는다.

그 대가로 그 컬럼의 JSON 모양이 바뀐다. 구버전 형식(`WorstSectionDto` 단독)으로 저장된 행은 재계산으로 흘리는데, **실측 0건**이라(§6-1) 마이그레이션이 필요 없다 — 동시에 그 하위호환 경로가 사실상 죽어 있다는 뜻이기도 하다.

> 🔶 **프론트 화면은 범위 밖.** `frontend/types/report.ts` 타입만 맞췄고 추이 그래프·표는 없다. 지금 상태로는 API 를 직접 호출해야 보인다.

### 곁가지 — `is_correct` 🔶 미결

`PoseDataService.java:81` 이 `sync_rate >= 40.0` 을 하드코딩해 저장하는데 **읽는 코드가 없다.** 게다가 AI 의 persona 별 임계값(BEGINNER 60 등)과 어긋나 한 행 안에서 두 컬럼이 반대로 말한다. 삭제/정합/유지 미결 → [#80](https://github.com/Shadowfit/init/issues/80).

---

---

## 8-5. 구현 결과 (2026-08-01)

[PR #82](https://github.com/Shadowfit/init/pull/82) — base 가 `main` 이 아니라 [PR #81](https://github.com/Shadowfit/init/pull/81)(`fix/session-stats-and-tx-boundary`)인 **스택 PR** 이다. ㄱ안이 #75 의 rep 단위 조회 위에 서기 때문이고, #81 이 먼저 머지돼야 한다.

| 커밋 | 내용 |
|---|---|
| `a86621f` | worst 를 rep 단위로. `PoseFrameProjection` 에 `repNumber` 추가·`feedbackMessage` 제거, 정렬 rep 우선, `WORST_WINDOW_SIZE` 삭제, `pickDominantFeedback` 삭제([#80](https://github.com/Shadowfit/init/issues/80) 일부) |
| `6d5d419` | 회차별 추이 + `WorstSectionDto.repNumber` + 프론트 타입 |

**추정 대비**: §4-ㄱ 에서 3~5h 로 추정했다. 실제 투입 시간을 기록하지 않아 **맞았는지 판단할 수 없다** — [`../tasks/28-remaining-work-plan.md`](../tasks/28-remaining-work-plan.md) §2 가 "#3 착수 시엔 시작·종료 시각을 남기자"고 적어둔 것을 여기서도 못 지켰다. 다만 범위가 추정 당시보다 늘었다(추이 + `repNumber` + 프론트 타입은 §4-ㄱ 에 없던 항목).

**구현하며 정한 것** (문서에 없던 판단):

- **rep 평균을 쓴다.** 지금은 rep 안이 상수라 어느 프레임을 봐도 같지만, ㄷ안이 오면 이 코드를 안 고쳐도 의미가 유지된다
- **`repNumber <= 0`(미상) 제외 → 미상만 있는 세션은 `null`.** 예전 코드는 뭔가를 내놓긴 했지만 그 값이 어느 rep 의 것인지 말할 수 없었다. 틀린 값을 내놓느니 없다고 하는 편이 낫다는 판단
- **미상 필터를 쿼리가 아니라 계산기에.** "프레임이 아예 없다"와 "rep 을 알 수 없는 프레임뿐이다"를 계산기가 구분해 다룰 수 있어야 해서
- **`WorstSectionCalculator` 는 이제 이름이 하는 일보다 좁다**(추이도 계산). 클래스명 정리는 후속 — 미머지 PR 위에 이름 변경 diff 를 겹치면 리뷰가 어려워진다

**검증**: 테스트 236개 통과(#81 기준 223 → +13). `WorstSectionCalculatorTest` 7 → 19 재작성. 통합테스트 픽스처가 마침 `rep1=80.0(3행)` · `rep2=75.0(2행)` 이라 **예전 윈도우로는 rep2 가 밀려 80 이 worst 로 뽑히던 정확히 그 케이스**여서, 실제 쿼리·실제 다운샘플을 거친 end-to-end 회귀로 고정했다.

⚠️ **실데이터 검증이 아니다.** 로컬 `pose_data` 0행(§6-1)이라 전부 테스트 픽스처 기준이다.

---

## 9. 열린 질문 (사용자 결정 필요)

- [x] ~~**ㄱ / ㄴ / ㄷ 중 무엇**~~ → **✅ ㄱ안 채택** (2026-08-01 사용자 confirm). ㄴ 배제, ㄷ 는 별도 트랙 여부가 아래에 열려 있음
- [x] ~~ㄱ안 채택 시 **DTO·용어 정리**~~ → ⚠️ **결정이 바뀌었다.** "구조 유지"로 정했다가 추이(§8-4)를 넣으면서 전제가 무너져 **`WorstSectionDto.repNumber` 추가**로 확정. 클래스명(`WorstSectionCalculator`)만 후속으로 남음. §8-3
- [x] ~~**대표 timestamp**~~ → **✅ rep 중앙 프레임**. §8-3
- [x] ~~`reason` 처리~~ → **✅ 동어반복 제거 + `pickDominantFeedback` 삭제**. 단 **최종 문구는 구현 시점으로 보류**. §8-3 · [#80](https://github.com/Shadowfit/init/issues/80)
- [x] ~~**과거 리포트**(§6) — 방치 / 백필 / 무효화~~ → **✅ 자동 해소.** 2026-08-01 실측 결과 `detailed_analysis` 가 채워진 행 **0건**, `pose_data` **0행**. 실행 대상이 없다(§6-1)
- [x] ~~**착수 브랜치**~~ → **✅ [#75](https://github.com/Shadowfit/init/issues/75) 먼저.** `fix/session-stats-and-tx-boundary` 푸시 + [PR #81](https://github.com/Shadowfit/init/pull/81) 생성 완료(2026-08-01). 머지 후 그 위에서 ㄱ안 착수
- [ ] `is_correct` 처리 — 삭제 / 임계값 정합 / 유지 ([#80](https://github.com/Shadowfit/init/issues/80))
- [ ] `reason` **최종 문구** — 구현은 잠정값(`"2회차 · 싱크로율 75%"`)으로 나갔다. 테스트도 전체 문자열을 고정하지 않았으니 바꿀 여지는 열려 있다
- [ ] `applyCompleteFromApp`(앱 보고 경로)에 rep 단위 집계를 적용할지 — 지금은 앱이 보낸 dto 값을 그대로 쓴다(`ExerciseAnalysisService.java:510-511`). AI 콜백 경로만 rep 단위다
- [ ] **프론트 화면** — 추이 그래프·표. 타입만 맞춰뒀고 표시가 없어 지금은 사용자 눈에 안 보인다(범위·추정 미정)
- [ ] `WorstSectionCalculator` 클래스명 정리 (후속, §8-5)
- [ ] **ㄷ안을 별도 트랙으로 남길지**, 아예 배제할지
- [ ] [#79](https://github.com/Shadowfit/init/issues/79) — ㄱ안 선택으로 남게 됐다. 문서 정정만 할지, 비교 기준을 다른 값으로 교체할지

---

## 10. 정직 단서

- **#78·#79 는 코드 경로 추적으로 판단했고 재현하지 않았다.** §2-2 의 rep1/rep2/rep3 예시는 손으로 만든 것이다
- **시간 추정은 실측이 아니다.** 특히 ㄷ안은 알고리즘 설계 비중이 커서 신뢰도가 낮다
- **"ㄴ안의 결과가 ㄱ안과 항상 같다"는 논증이며 테스트하지 않았다**(§4-ㄴ)
- ~~**§6 의 영향 규모를 세지 않았다**~~ → **2026-08-01 실측 완료**(§6-1). 단 **로컬 DB 한정**이며 EC2 배포분은 확인하지 않았다
- **로컬 DB 에 실제 운동 데이터가 없다**(`pose_data` 0행). 즉 #78·#79·#80 어느 것도 **실제 데이터로 재현해 본 적이 없고**, 구현 후 검증도 테스트 픽스처에 의존하게 된다
- `dtaidistance==2.3.12` 가 `warping_path` 를 제공한다는 것은 **버전 문자열과 라이브러리 문서 기준**이며, 이 프로젝트에서 호출해 보지 않았다
- ㄱ안이 #75 의 쿼리를 재사용한다는 것은 **그 브랜치가 머지된다는 전제**다 — 현재 `fix/session-stats-and-tx-boundary` 는 미푸시 상태다
