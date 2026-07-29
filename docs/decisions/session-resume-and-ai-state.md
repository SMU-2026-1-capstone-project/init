# 세션 재개(resume)와 AI 분석 상태의 내구성

작성일: 2026-07-29
상태: **분석/추천 (결정 전)** — 사용자 선호 표명(A+B) 기록됨, 확정은 §6 항목별 confirm 후 ([[feedback_user_decides_not_claude]], [[feedback_decision_doc]])
대상: 백엔드(Spring) 신입 포폴 — 기존 UX 기능의 정합성 결함
관련: [`outbox-reliable-messaging.md`](./outbox-reliable-messaging.md)(§3-2 AI 상태 한계), [`session-lifecycle-checklist.md`](./session-lifecycle-checklist.md), [`pose-ingest-downsampling.md`](./pose-ingest-downsampling.md), 이슈 [#59](https://github.com/Shadowfit/init/issues/59)

---

## 0. 한 줄 목적

> **"앱을 나갔다 돌아오면 하던 운동을 이어서 한다"는 의도한 기능이, AI 프로세스가 한 번 재시작하면 조용히 안 먹는 것**을 고친다.

---

## 1. 의도한 설계

사용자가 운동 중 앱을 이탈해도 세션을 이어갈 수 있어야 한다. 그래서:

- 이탈 시 `endSession`을 **부르지 않는다** → 세션은 `IN_PROGRESS`로 남는다
- `SessionTimeoutScheduler`의 버퍼(기본 30분, `SessionTimeoutScheduler:39`)가 그 사이 세션이 걷히지 않도록 지켜준다

이 버퍼는 "장애 세이프티넷"으로만 알려져 있었지만, **실제로는 재개 UX를 떠받치는 파라미터**다. 이 점이 문서에 없었다.

---

## 2. 문제 — 재개는 DB만 보고, 실제 상태는 AI 메모리에 있다

재개는 "DB에 세션이 `IN_PROGRESS`로 남아있다"에만 의존한다. 그런데 분석 상태는 **AI 프로세스 메모리**에 있고 `StartAnalysis`에서만 만들어진다(`exercise_servicer.py:84`, `session_state.py` — 무-TTL 무-영속).

재개 창 안에 AI가 재시작(배포·OOM·크래시)하면:

```text
사용자 복귀 → 포즈 프레임 전송 → pose.py:64-69
    → get_registry().get(session_id) 가 None
    → success=False "세션 N이 시작되지 않았습니다 (StartAnalysis 먼저 호출 필요)"
```

**DB는 멀쩡히 `IN_PROGRESS`라 클라는 "이어서 할 수 있다"고 믿고 들어오는데, AI는 프레임을 전부 거부한다.**

### 2-1. 복구 경로가 없다

백엔드 API에 재부착용 진입점이 없다 — `POST /exercises/sessions`(시작), `PATCH /sessions/{id}/end`(종료), `DELETE /sessions/{id}` 뿐이고, `POST /exercises/sessions`는 **새 세션 row를 만든다**(`ExercisesController:52-63`).

### 2-2. 설계상의 역설 (면접에서 쓸 만한 관찰)

재개 창을 길게 잡을수록 사용자 편의는 올라가지만, **AI 상태가 증발할 수 있는 노출 시간도 같이 길어진다.** 30분 버퍼는 재개를 위한 건데 그 30분이 곧 위험 구간이다. **같은 파라미터를 두 목적이 반대 방향으로 당긴다** — 버퍼를 줄이면 재개가 불편해지고, 늘리면 유실 노출이 커진다. 이 긴장은 파라미터 튜닝으로 못 풀고 **상태 내구성으로만 풀린다.**

### 2-3. ⚠️ 미검증

**클라이언트가 복귀 시 실제로 무엇을 호출하는지 확인하지 못했다.** 확인된 범위는 "백엔드·AI 쪽에 재부착 진입점이 없다"까지. 클라가 `POST /exercises/sessions`를 다시 부르는 식으로 우회 중이라면 증상이 달라진다(대신 세션 row 중복 생성). **클라 동작 확인이 선행돼야 한다.**

---

## 3. 확인된 사실 — 복구 재료는 이미 DB에 있다

### 3-1. 상태의 절반은 재생성이 공짜

| 재생성 가능 (DB에 있음) | 진짜 휘발 |
|---|---|
| `exercise_id`, `exercise_type`, `persona` | `completed_reps` |
| `reference_angles` — `exercise_references` 재조회 | `rep_count`, `rep_state`, `frame_index`, `previous_smoothed_knee` 등 분석기 진행 상태 |

왼쪽 칸은 애초에 `startAnalysis`가 **DB에서 읽어다 넣어준 것**이다(`ExerciseAnalysisService:190`). 언제든 다시 만들 수 있다.

### 3-2. rep 데이터는 세션 진행 중에 이미 Spring으로 온다 ★

rep이 하나 완성될 때마다 — 세션 종료 시가 아니라 **진행 중에** — AI가 그 rep의 프레임 묶음을 Spring에 보낸다:

```text
pose.py:106-116   rep 완성 → spring_client.report_pose_data_batch(session_id, 프레임들)
                  ↓ gRPC
PoseDataService.savePoseDataBatch:58  →  pose_data 배치 INSERT
```

**AI 메모리가 날아가도 그때까지의 rep 데이터는 Spring DB에 남아있다.** 재부착(B)이 성립하는 근거다.

### 3-3. 다만 rep 번호가 없다

proto `PoseDataRequest`(`exercise.proto:99-104`)에도 `pose_data` 스키마(`mysql/schema.sql:106-117`)에도 rep 번호 컬럼이 없다.

한 rep의 모든 프레임이 같은 `sync_rate`를 공유하므로(`pose.py:111`) "같은 `sync_rate` 연속 구간 = 1 rep"으로 셀 수는 있다. 그러나 **인접한 두 rep의 `sync_rate`가 우연히 같으면 병합돼 undercount 된다**(`DECIMAL(5,2)`). 진실의 출처로 쓰기엔 부족하다.

> 다운샘플(`PoseDataService:68`, window 5에서 sync 최저 프레임만 저장)이 걸려 있지만 rep당 최소 1행은 남으므로 **rep 개수 세기 자체엔 영향이 적다.** 대신 rep 안의 프레임은 대부분 버려져 프레임 단위 복원은 어차피 불가능하다.
>
> 정직 체크: 처음엔 "리포트의 worst 구간 분석도 rep 경계를 추측할 테니 `rep_number`가 그것도 고쳐준다"고 봤으나 **아니다.** `WorstSectionCalculator:20-21`은 rep이 아니라 **고정 프레임 윈도우**를 쓰고 그게 의도된 설계다("단일 프레임은 노이즈 영향이 커서"). `rep_number`는 기존 버그를 고치는 게 아니라 **순수하게 재부착을 위한 신규 투자**다.

---

## 4. 대안 비교

| # | 방식 | 재개 복원 수준 | 비용 | 평가 |
|---|---|---|---|---|
| A | **명시적 실패 안내** | 없음(포기를 알림) | 저 | **어차피 필요** — B·C를 해도 상태 없는 순간은 존재 |
| B | **재부착 엔드포인트** | 세션·완료 rep 수는 이어감. **분석기 내부 상태는 초기화**(§4-0) | 저~중 | **⭐ 추천(A와 함께)** |
| C | AI 상태 영속화(Redis 등) | 스냅샷에 담은 만큼만 — **무엇을 담느냐의 문제**(§4-0) | 중 | 2순위. 별도 카드 |
| D | Spring을 진실의 출처로 | 완전 | 고 | 프로토콜 변경 과대, 현 규모 과설계 |

### 4-0. ⚠️ 복원 범위를 정확히 — "rep 하나 분량"이 아니다

이전 판은 B 이후 남는 손실을 "rep 하나 분량 프레임", C를 "거의 완전"이라고 적었다. **둘 다 과장이다.** `SessionState`(`session_state.py:31-51`)의 휘발 항목을 실제로 나열하면:

| 항목 | `initial_rep_count` 주입(B)으로 복원? | `completed_reps` 저장(C)으로 복원? |
|---|---|---|
| `rep_count` | ✅ | ✅ |
| `completed_reps` (통계 집계용) | ❌ (Spring 이 대신 집계 가능) | ✅ |
| `rep_state` (`waiting_for_standing` 등) | ❌ **초기 상태로 리셋** | ❌ 별도로 담아야 함 |
| `frame_index` | ❌ 0 부터 | ❌ 별도 |
| `last_rep_frame_index` | ❌ | ❌ 별도 |
| `previous_smoothed_knee`, `recent_raw_knees` (스무딩 이력) | ❌ | ❌ 별도 |
| `current_rep_frames` (진행 중이던 rep) | ❌ 소실 | ❌ 소실(스냅샷 시점 이후분) |

**실질적 영향**: 재개 직후 분석기는 "방금 막 시작한" 상태로 동작한다 — `rep_state` 가 초기값이라 사용자가 이미 앉아 있는 자세여도 rep 감지가 한 박자 어긋날 수 있고, 스무딩 윈도우(`recent_raw_knees`)가 비어 있어 **처음 몇 프레임의 sync_rate 가 흔들린다.** 진행 중이던 rep 은 어느 방식으로도 못 살린다.

**그래서 정직한 서술은 이렇다:**
- **B**: "세션과 완료 rep 수를 이어붙인다. 분석기는 리셋되고 진행 중 rep 은 버린다." — 사용자 체감으로는 *"카운트는 이어지지만 그 순간의 폼 판정은 잠깐 부정확할 수 있다"*
- **C**: "무엇을 스냅샷에 담느냐에 정확히 비례한다." `completed_reps` 만 담으면 B 와 실질 차이가 작고, `rep_state`·스무딩 이력까지 담아야 진짜 이어짐이 된다. **C 를 채택할 때 '어디까지 담을지'가 실제 결정 항목**이며, 이건 §6 미결정에 추가한다.

이 손실을 **감수하고 넘어갈지, 아니면 "재개 후 첫 rep 은 집계에서 제외" 같은 보정을 둘지**도 결정이 필요하다.

### A. 명시적 실패 안내
지금 `pose.py:66-69`가 `success=False`를 주지만 **클라가 이걸 어떻게 다루는지 모른다**(§2-3). "세션 상태 유실 → 재개 불가, 새로 시작"을 사용자에게 보이게 하는 계약을 정한다. 결과 자체는 여전히 유실이지만, **조용한 실패를 보이는 실패로** 바꾼다. B·C를 채택해도 상태가 없는 순간은 존재하므로 A는 독립적으로 필요하다.

### B. 재부착 엔드포인트 (추천)
복귀 시 클라가 호출하면 Spring이 **같은 `sessionId`로** AI에 `StartAnalysis`를 재실행한다. §3-1의 왼쪽 칸은 DB에서 그대로 복원되고 새 세션 row는 만들지 않는다.

- ⚠️ **무조건 덮어쓰면 안 된다.** `SessionStateRegistry.create:69-78`이 `self._sessions[session_id] = state` 라 같은 id로 다시 부르면 **기존 상태를 그대로 날린다.** 이전 판은 이걸 "변경 없이 자연히 되는 장점"으로 적었으나 **반대다** — AI 상태가 멀쩡히 살아있는데 재부착 요청이 오면(중복 호출, 네트워크 재시도, 사용자가 빠르게 이탈·복귀) **진행 중이던 rep 과 스무딩 이력을 통째로 버리게 된다.** 정작 재부착이 필요 없는 경우에 피해를 주는 셈이다.
  → **멱등 가드가 필요하다**: 재부착 경로는 `get()` 으로 먼저 확인해 **상태가 이미 있으면 아무것도 하지 않고 성공을 반환**하고, 없을 때만 생성한다. `StartAnalysis` 를 그대로 재사용하려면 "기존 상태가 있으면 보존" 분기를 그쪽에 넣거나, 재부착 전용 RPC 를 따로 두어야 한다 — **어느 쪽으로 갈지는 §6 미결정.**
- rep 카운트까지 살리려면 `rep_number`가 필요하다(§3-3) → `SELECT MAX(rep_number) WHERE session_id = ?`로 정확히 복원.
- 비용: 엔드포인트 1개 + proto 2벌 동기화(`ai-server/`·`backend/`) + `pose.py` 한 줄 + `PoseDataService` INSERT 컬럼 1개 + `ALTER TABLE`.

> 파이썬 변경 면적: `pose.py`에 `rep_number=rep_event.rep_number` 한 줄. ([[feedback_minimize_python_changes]] — 최소지만 변경은 변경이므로 명시)

### C. AI 상태 영속화
프레임마다가 아니라 **rep 완료 시점마다** 스냅샷을 저장하면 쓰기 빈도가 감당된다(`completed_reps`만 저장, 진행 중 프레임은 잃어도 rep 하나 분량). Redis는 백엔드 채용 단골 시그널이라 포폴상 얻는 것도 있다([[user_career_target]], [`redis-introduction.md`](./redis-introduction.md)와 함께 볼 것). 다만 **B를 하면 남는 격차가 "rep 하나 분량 프레임"뿐**이라 우선순위가 내려간다.

### D. Spring을 진실의 출처로
AI가 rep마다 중간 보고하고 AI는 stateless를 지향. `pose_data`가 이미 Spring에 오고 있어 방향은 자연스럽지만, 분석기 진행 상태(`rep_state`, `previous_smoothed_knee` 등)까지 넘기려면 프로토콜 변경이 크다. 현 규모엔 과설계.

---

## 5. 추천 — A + B

사용자 선호 표명(2026-07-29): **A+B**. 근거는 위와 일치한다 —

- A는 어느 길을 가도 필요하다(상태 없는 순간은 항상 존재).
- B는 "재개가 아예 안 됨"을 "이어서 됨"으로 바꾸는 **가장 싼 지점**이고, §3-2 덕분에 rep 데이터가 이미 DB에 있어 복원 재료가 갖춰져 있다.
- C는 Redis 도입이라는 별도 결정이 필요해 지금 묶으면 카드가 무거워진다. B 이후 남는 격차가 작으므로 **확장 옵션**으로 둔다.

### 5-1. ⚠️ B 안의 갈림길 — rep 카운트를 누가 이어붙이나

| | AI에 주입 | Spring이 합산 |
|---|---|---|
| 방법 | `AnalyzeRequest`에 `initial_rep_count` 필드 추가 | AI는 0부터 다시 세고, 최종 집계 때 Spring이 재부착 이전 rep을 더함 |
| rep 번호 연속성 | ✅ 연속 | ❌ 세션 안에서 중복 → offset 처리 필요 |
| proto·AI 변경 | 필드 1개 추가 + 초기화 로직 | **거의 없음** ([[feedback_minimize_python_changes]]에 부합) |
| 집계 정확성 | AI가 그대로 보고 | Spring 합산 로직에 책임이 감 |

어느 쪽도 명백히 우세하지 않다. **`pose_data`에 `rep_number`를 저장하기로 하면 중복 번호가 실제 문제가 되므로 "AI에 주입"이 유리해진다.** 반대로 rep 번호를 저장하지 않고 카운트만 맞추면 "Spring 합산"이 싸다.

---

## 6. 미결정 (사용자 confirm 필요)

- [ ] **선행**: 클라이언트가 복귀 시 무엇을 호출하는지 확인(§2-3) — 이게 안 되면 A의 계약도 B의 트리거도 못 정한다
- [ ] A+B 채택 확정 여부 (현재 선호 표명 단계)
- [ ] `rep_number`를 proto+`pose_data`에 추가할지 — 추가하면 rep 복원이 정확해지지만 스키마·proto 변경 발생
- [ ] §5-1 갈림길: rep 카운트를 **AI에 주입** vs **Spring이 합산**
- [ ] 재부착 엔드포인트 형태: `POST /sessions/{id}/reattach` vs 기존 시작 API에 플래그
- [ ] **(신설)** 재부착의 멱등 처리 방식(§4-B): `StartAnalysis`에 "기존 상태 보존" 분기를 넣을지 vs **재부착 전용 RPC**를 둘지 — 살아있는 상태를 덮어쓰지 않는 게 요구사항
- [ ] **(신설)** 분석기 내부 상태 손실(§4-0)을 감수할지, "재개 후 첫 rep 집계 제외" 같은 보정을 둘지
- [ ] **(신설)** C를 가게 된다면 스냅샷에 **어디까지 담을지** — `completed_reps`만이면 B와 실질 차이가 작고, `rep_state`·스무딩 이력까지 담아야 진짜 이어짐이 된다
- [ ] 재부착 허용 조건: `IN_PROGRESS` + `end_time IS NULL` + 소유자 일치 외에 시간 상한을 둘지(타임아웃 버퍼와 같은 값으로?)
- [ ] C(AI 상태 영속화)를 언제 볼지 — 확장 옵션으로 둘지, 별도 카드로 올릴지
- [ ] §2-2의 역설을 근거로 **타임아웃 버퍼 30분 자체를 재검토**할지

---

## 결정 로그
- 2026-07-29: 문서 작성. [`outbox-reliable-messaging.md`](./outbox-reliable-messaging.md) §3-2를 파다가 **"AI 상태 in-memory"가 outbox의 한계가 아니라 의도한 재개 UX를 조용히 깨뜨리고 있다**는 것을 발견해 분리. 이슈 [#59](https://github.com/Shadowfit/init/issues/59) 등록. 확인: rep 데이터가 세션 진행 중 이미 `pose_data`에 쌓임(§3-2), 그러나 rep 번호 없음(§3-3). 대안 A~D 비교, **A+B 추천** — 사용자 선호 표명 있음. **확정 아님** — §6 8건 미결정, 특히 클라 동작 확인이 선행.
- 2026-07-29(리뷰 반영): CodeRabbit 지적으로 **과장 2건 정정** — ① B/C 복원 범위. `initial_rep_count`·`completed_reps` 만으로는 `rep_state`·`frame_index`·스무딩 이력이 복원되지 않아 "C는 거의 완전, B 이후 손실은 rep 하나 분량"은 틀렸다(§4-0 신설). ② 재부착이 `SessionStateRegistry.create` 로 **살아있는 상태를 덮어쓰는 것**을 장점으로 적었으나 반대로 위험이다 — 멱등 가드 필요(§4-B). 미결정 3건 신설. **결정 변경 없음 — 여전히 A+B 추천·확정 전.**
