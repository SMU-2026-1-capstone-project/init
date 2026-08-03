# Decision: 세션 생존 판정 — 경과 시간인가 마지막 활동인가

상태: **🔵 OPEN — 결정 대기**
작성: 2026-08-03
연관: [#92](https://github.com/Shadowfit/init/issues/92) (휴식 중 프레임 전송), [#98](https://github.com/Shadowfit/init/issues/98) · [PR #100](https://github.com/Shadowfit/init/pull/100) (타임아웃 세션의 AI 통보), [#77](https://github.com/Shadowfit/init/issues/77) (재부착 창)
관련 문서: [`./session-lifecycle-checklist.md`](./session-lifecycle-checklist.md), [`./session-end-trigger.md`](./session-end-trigger.md), [`./ai-load-budget.md`](./ai-load-budget.md)

> 이 문서는 **결정을 요청**한다. 아래 §5 의 네 안 중 무엇을 택할지(또는 조합할지) 정해지면
> 그때 이 박스에 박고 구현한다. 현재 코드는 §2 그대로다.

---

## 1. 한 줄 요약

세션이 살아있는지를 **활동이 아니라 시작 이후 경과 시간**으로 판단한다. 그래서 서버는 "지금 프레임이 오고 있다"는 사실을 실시간으로 받으면서도 그것을 생존 판정에 쓰지 않는다.

증상 세 가지가 전부 여기서 나온다 — 통계 부풀림, 방치, 운동 중 조기 종료.

---

## 2. 현재 코드 (2026-08-03, `cf5b843` 기준)

### 2-1. 타임아웃 식은 `start_time` 앵커다

```java
// Session.isTimedOutAt / timeoutThreshold
타임아웃 = start_time + expectedDurationMinutes + defaultBufferMinutes
```

스쿼트(`expectedDurationMinutes=15`) + 버퍼 30분 = **시작 후 45분**. `SessionTimeoutScheduler` 가 1분마다 훑어 이 기준을 지난 `IN_PROGRESS` 를 `FAILED` 로 바꾼다.

**이 식에 활동은 들어오지 않는다.** 프레임이 지금 이 순간 들어오고 있어도, 안 들어온 지 40분이 지났어도 결과가 같다.

### 2-2. 세션 행에는 "마지막으로 살아있던 시각"이 없다

`exercise_sessions` 가 가진 시각은 둘뿐이다.

| 컬럼 | 채우는 주체 | 뜻 |
|---|---|---|
| `start_time` | `createSession` | 시작 시각 |
| `end_time` | `endSession` | **운동이 끝난 시각** |
| `end_time` | `markAsFailedIfStillInProgress` | **세션이 죽은 시각** |

`end_time` 한 컬럼이 두 가지 뜻을 진다 — [#98](https://github.com/Shadowfit/init/issues/98) 과 **같은 병**이다. 거기서는 `endSession` 의 멱등 가드가 두 뜻을 구분하지 못해 AI 통보를 통째로 빠뜨렸다.

### 2-3. 활동 신호는 존재하지만 세션이 안 본다

클라는 `intervalMs = 330`(`frontend/app/(tabs)/exercise.tsx:149`) 으로 **초당 ~3회** 프레임을 보낸다. 앱이 닫히면 그 흐름이 끊긴다. 즉 **"멈췄다"는 신호는 서버에 실시간으로 도착한다.**

그 흔적은 `pose_data.created_at` 에 남는다(DB `DEFAULT CURRENT_TIMESTAMP`, `PoseDataService`). 다만 **아무도 그것을 세션 쪽으로 되돌려 쓰지 않는다.**

---

## 3. 증상 — 셋 다 같은 뿌리다

### 3-1. 주간 통계 부풀림

`getWeeklyActivity` 는 status 를 가리지 않는다.

```java
// SessionRepository.findWeeklySessionsWithExercise — status 조건 없음
"SELECT s FROM Session s JOIN FETCH s.exercise " +
"WHERE s.member.id = :memberId AND s.startTime BETWEEN :start AND :end"

// SessionService.getWeeklyActivity
Duration.between(s.getStartTime(), s.getEndTime()).toMinutes()
```

FAILED 세션이 그대로 합계에 들어가고, 그 `end_time` 은 **운동이 끝난 시각이 아니라 타이머가 터진 시각**이다.

**변형 A — 종료를 눌렀는데 덮어써지는 경우**

```
09:00  운동 시작                    status=IN_PROGRESS, end_time=null
09:10  사용자가 "종료" 누름          end_time=09:10   ← 실제 10분 운동
       (status 는 IN_PROGRESS 유지 — 전환은 applyComplete 몫)
       ...AI 결과가 끝내 오지 않음...
09:46  스케줄러 → FAILED, end_time=09:46   ← 덮어쓴다
```
→ 실제 **10분**이 통계엔 **46분**.

**변형 B — 애초에 안 누른 경우 (더 흔하다)**

```
09:00  운동 시작
09:10  앱을 그냥 닫음 / 배터리 나감    end_time 계속 null
09:46  스케줄러 → FAILED, end_time=09:46
```
→ 덮어쓸 값조차 없지만 결과는 똑같이 **46분**. 조건이 "앱을 안 끄고 나감"이라 변형 A 보다 자주 일어난다.

> **⚠️ 실측하지 않았다.** 위는 코드에서 유도한 시나리오다. 운영 데이터로 FAILED 세션의 `end_time - start_time` 분포를 본 적이 없다.

### 3-2. 방치 — 그만뒀는데 45분간 IN_PROGRESS

09:03 에 앱을 닫아도 09:46 까지 `IN_PROGRESS` 다. 그동안:

- `createSession` 이 `SESSION_ALREADY_IN_PROGRESS`(409) 로 막는다 — 새 운동을 시작할 수 없다
- AI 메모리의 `SessionState` 도 그대로 남는다

[#59](https://github.com/Shadowfit/init/issues/59) 1·2단계(활성 세션 조회 + 재부착)가 "갇힘"을 **우회**할 길을 열었지만, **갇히는 원인 자체**는 여기 남아 있다.

### 3-3. 운동 중인데 걷어감

반대 방향이다. 45분 넘게 운동하면 **프레임이 지금 들어오고 있는데도** 스케줄러가 `FAILED` 로 바꾼다. 서버는 그 세션이 활성이라는 증거를 받고 있으면서 경과 시간만 보고 죽인다.

[PR #100](https://github.com/Shadowfit/init/pull/100) 이후 그 순간 AI 에 `StopAnalysis` 가 나가므로, **운동 중에 분석이 끊긴다.** #100 이전에는 AI 가 모르고 계속 돌았다 — 고친 쪽이 옳지만, 이 경로에서는 증상이 더 눈에 띄게 됐다.

> **⚠️ 미확인.** 45분 이상 운동이 실제로 얼마나 되는지 모른다. `expectedDurationMinutes` 가 종목별로 다르고 버퍼가 30분이라 여유가 있어 보이지만, 세트 사이 휴식이 길면 도달할 수 있다.

---

## 4. 왜 "빼면 되지 않나"가 안 되는가

`end_time` 에서 45분을 빼서 복원하자는 접근은 **성립하지 않는다.** 45분은 지연이 아니라 **상한**이라, 스케줄러 발동 시각이 사용자가 멈춘 시각과 무관하게 `start_time` 에 매달려 있기 때문이다.

| 실제로 멈춘 시각 | 스케줄러 발동 | 45분 뺀 값 | 맞나 |
|---|---|---|---|
| 09:10 (10분 운동) | 09:46 | 09:01 | ❌ |
| 09:40 (40분 운동) | 09:46 | 09:01 | ❌ |
| 09:03 (3분 운동) | 09:46 | 09:01 | ❌ |

언제 멈췄든 `start_time + 1분` 쯤이 나온다. **"항상 과대"가 "항상 ~0분"으로 바뀔 뿐**이고, 40분 운동한 사용자를 1분으로 깎는다. 게다가 45분은 상수도 아니다 — `expectedDurationMinutes` 가 종목마다 다르다.

**없는 정보는 계산으로 복원되지 않는다.** 세션 행에 "언제 멈췄나"가 없다는 것이 문제의 핵심이다.

---

## 5. 선택지

| 안 | 무엇을 바꾸나 | 3-1 | 3-2 | 3-3 | 면적 |
|---|---|---|---|---|---|
| **ㄱ** | 통계에서 FAILED 제외 | ✅ | ❌ | ❌ | 최소 |
| **ㄴ** | `pose_data.MAX(created_at)` 를 종료 시각으로 | ✅ | ❌ | ❌ | 작음 |
| **ㄷ** | `last_active_at` 컬럼 신설 + 타임아웃 기준 교체 | ✅ | ✅ | ✅ | 큼 |
| **ㄹ** | 현행 유지 | — | — | — | 0 |

### ㄱ. 통계에서 FAILED 제외

`findWeeklySessionsWithExercise` 에 status 조건을 넣는다.

- **장점** — 한 줄. 부풀림이 즉시 사라진다
- **단점** — **실제로 운동한 세션이 통째로 사라진다.** [PR #100](https://github.com/Shadowfit/init/pull/100) 이후 대부분의 타임아웃 세션은 AI 콜백으로 COMPLETED 가 되므로 해당 범위가 줄긴 했다. 다만 AI 가 세션을 잃은 경우(`success=false` → `TERMINAL_FAILED`)는 FAILED 로 남고, 그 세션의 `pose_data` 에는 rep 이 있다
- 3-2·3-3 은 손대지 않는다 — **증상 하나만 덮는다**

### ㄴ. `pose_data` 에서 실제 종료 시각을 읽는다

```sql
SELECT MAX(created_at) FROM pose_data WHERE session_id = ?
```

- **장점** — 추정이 아니라 **실제로 일어난 일의 기록**이다. 쓰기 경로를 전혀 건드리지 않아 아래 ㄷ 의 락 문제가 없다
- **단점**
  - 프레임이 0개인 세션(시작하자마자 이탈)은 여전히 답이 없다
  - 다운샘플(R=5, [`./pose-ingest-downsampling.md`](./pose-ingest-downsampling.md))로 마지막 프레임이 실제 종료보다 **앞설 수 있다** — 최대 몇 초 수준으로 보이나 **미측정**
  - 조회 비용 미측정. `pose_data` 는 파티셔닝돼 있고 세션당 ~750행 규모
- 3-2·3-3 은 그대로다 — 타임아웃 판정을 안 바꾸기 때문

### ㄷ. `last_active_at` 신설 + 타임아웃 기준 교체

세 증상을 다 잡는 유일한 안이지만 **함정이 하나 있다.**

**`Session` 에 `@Version` 이 걸려 있다**(`Session.java:69`). 이 프로젝트는 그 낙관적 락으로 **AI 콜백 ↔ 타임아웃 스케줄러 경쟁**을 조율한다 — `completeSession` 은 3회 재시도하고(`optimisticLockConflict("ai-callback", …)`), 스케줄러는 양보한다(`"timeout-scheduler", "yield"`).

프레임마다 세션 행을 JPA 로 UPDATE 하면 **version 이 초당 3회 올라간다.** 지금은 드물어서 지표로 관측하던 경쟁이 **상시화**되고, 세션 종료가 3회 재시도로도 못 이겨 예외가 날 수 있다. **즉 진짜 비용은 쓰기 부하가 아니라 락 경쟁이다.**

쓰기 지점 후보:

| 지점 | 빈도 | 문제 |
|---|---|---|
| 프레임마다 JPA UPDATE | 초당 3회/세션 | ❌ version 폭주 |
| 프레임마다 `JdbcTemplate` 로 `last_active_at` 만 | 초당 3회/세션 | version 은 피하지만 **핫 로우** |
| rep 배치마다 (`savePoseDataBatch` 안) | rep 당 1회 | 쌈. 다만 **휴식 중엔 rep 이 안 나와** 갱신이 멈춘다 → "쉬는 중"과 "나감"이 구분 안 됨 |

마지막 항목이 [#92](https://github.com/Shadowfit/init/issues/92) 와 얽힌다. #92 는 "휴식 중에도 프레임을 계속 보내 MediaPipe 추론이 낭비된다"를 줄이자는 이슈인데, **그 프레임이 곧 "아직 여기 있다"는 신호**다. 줄이면 이쪽 판단 근거가 함께 사라진다. **두 이슈는 같이 결정해야 한다.**

타임아웃 기준을 `last_active_at + N분` 으로 바꾸면 3-2(방치)와 3-3(조기 종료)이 동시에 풀리지만, **N 을 얼마로 잡을지가 새 결정**이 된다. 세트 사이 휴식이 90초까지 관측됐으므로([#91](https://github.com/Shadowfit/init/issues/91) 재현) 그보다는 넉넉해야 한다.

### ㄹ. 현행 유지

- 3-2·3-3 은 사용자에게 드러나는 빈도가 아직 측정되지 않았다
- 3-1 은 드러나지만 **얼마나 부풀었는지 실측이 없다**
- 유지한다면 그 근거(빈도 미측정)를 남기고, 측정 결과가 나오면 재개하는 조건부 결정이어야 한다

---

## 6. 권고 (결정 아님)

**단기로는 ㄴ, 중기로는 ㄷ 을 권한다. 단 ㄷ 은 [#92](https://github.com/Shadowfit/init/issues/92) 와 함께 결정한다.**

- **ㄴ 을 먼저** — 유일하게 "실제로 언제 멈췄나"에 답할 수 있는 데이터가 이미 저장돼 있다. 쓰기 경로를 안 건드려 `@Version` 문제도 핫 로우 문제도 없다. 3-1 을 **덮는 게 아니라 고친다**(ㄱ 은 덮는다)
- **ㄱ 은 ㄴ 의 대체가 아니다** — FAILED 를 빼면 부풀림은 사라지지만 실제로 한 운동도 같이 사라진다. 다만 ㄴ 을 하기 전 임시 조치로는 가능
- **ㄷ 은 세 증상을 다 잡는 유일한 안**이지만, `@Version` 경쟁과 #92 를 함께 풀어야 해서 단독으로 착수하면 두 번 손대게 된다

---

## 7. 결정 전에 재야 할 것

이 문서의 근거는 **전부 코드 유도**다. 아래는 재보지 않았다.

1. **FAILED 세션의 `end_time - start_time` 분포** — 3-1 의 실제 크기. 부풀림이 크지 않다면 ㄹ 이 정당해진다
2. **45분 이상 운동의 빈도** — 3-3 이 실재하는가
3. **`MAX(created_at)` 조회 비용** — ㄴ 의 유일한 비용
4. **다운샘플로 인한 마지막 프레임 오차** — ㄴ 의 정확도 상한
5. **프레임 단위 `last_active_at` 쓰기가 실제로 낙관적 락 충돌을 얼마나 늘리는지** — ㄷ 의 핵심 위험. 위 서술은 "초당 3회 version 증가"에서 유도한 것이고 부하 테스트로 확인하지 않았다

1·2 는 운영 데이터가 필요하고, 3·4·5 는 로컬에서 잴 수 있다. 다만 부하 관련 수치는 [`./load-test-glossary.md`](./load-test-glossary.md) 의 환경 한계(i3-6100 2물리코어에 MySQL+백엔드 동거)가 그대로 적용되므로 **절대값이 아니라 상대·델타로만** 읽어야 한다.
