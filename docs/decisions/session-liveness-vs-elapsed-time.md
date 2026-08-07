# Decision: 세션 생존 판정 — 경과 시간인가 마지막 활동인가

상태: **✅ CLOSED — ㄷ 채택 (2026-08-03)**
작성: 2026-08-03
연관: [#92](https://github.com/Shadowfit/init/issues/92) (휴식 중 프레임 전송), [#98](https://github.com/Shadowfit/init/issues/98) · [PR #100](https://github.com/Shadowfit/init/pull/100) (타임아웃 세션의 AI 통보), [#77](https://github.com/Shadowfit/init/issues/77) (재부착 창)
관련 문서: [`./session-lifecycle-checklist.md`](./session-lifecycle-checklist.md), [`./session-end-trigger.md`](./session-end-trigger.md), [`./ai-load-budget.md`](./ai-load-budget.md)

> **✅ 결정 (2026-08-03): ㄷ 채택** — `last_active_at` 을 신설하고 타임아웃·재부착 판정의
> 기준을 "시작 이후 경과 시간"에서 "마지막 활동 이후 경과 시간"으로 바꾼다.
>
> 셋 중 하나만 덮는 ㄱ·ㄴ 과 달리 §3 의 세 증상을 모두 해소하는 유일한 안이고, 초안에서
> "면적 큼"으로 분류한 근거 두 가지가 **확인해보니 성립하지 않았다**(§5-ㄷ 정정 참고).
>
> **아직 안 정한 것**: 유휴 임계 `N`. §5-ㄷ 에 근거와 권고를 적었고 구현 착수 시 확정한다.
>
> **박힌 코드 (2026-08-03)**:
> - `mysql/schema.sql` — `exercise_sessions.last_active_at DATETIME` 신설
> - `Session.lastActiveAt` + `timeoutThreshold(idleMinutes, bufferMinutes)` — 활동이 있으면
>   `last_active_at + idleMinutes`, 없으면 **기존 식으로 폴백**(아래 정정 참고)
> - `PoseDataService.savePoseDataBatch` — 기존 `JdbcTemplate` 경로에 `last_active_at` 갱신 추가
> - `SessionTimeoutScheduler` · `SessionService.findReattachableSession` — 같은 식을 그대로 공유(현행 유지)
> - `application.yml` — `exercise.session.timeout.idle-minutes: 10` 신설,
>   `default-buffer-minutes` 는 **준비 구간 폴백 전용**으로 의미 축소
> - 통계는 별도로 손대지 않았다 — 앵커가 고쳐지면 `getWeeklyActivity` 의 부풀림이 함께 사라진다

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
| **ㄷ** ← 채택 | `last_active_at` 컬럼 신설 + 타임아웃 기준 교체 | ✅ | ✅ | ✅ | **중간** (초안 "큼" 에서 정정) |
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

### ㄷ. `last_active_at` 신설 + 타임아웃 기준 교체 ← **채택**

세 증상을 다 잡는 유일한 안이다. 초안에서 "면적 큼"으로 분류했으나 **그 근거 두 가지가 확인해보니 성립하지 않았다.**

#### 정정 1 — `@Version` 폭주는 일어날 수 없다

초안에는 *"프레임마다 세션 행을 UPDATE 하면 version 이 초당 3회 올라가 낙관적 락 경쟁이 상시화된다"* 고 적었다. **틀렸다. Spring 은 개별 프레임을 받지 않는다.**

`exercise.proto` 기준 AI → Spring 방향 RPC 는 셋뿐이다:

| RPC | 호출 시점 |
|---|---|
| `SavePoseDataBatch` | **rep 완성 시** |
| `ReportFeedbackBatch` | 피드백 묶음 |
| `CompleteAnalysis` | 세션 종료 시 |

프레임은 클라 → `ai-server` 로만 흐른다. Spring 의 활동 신호는 **처음부터 rep 단위**이고, 프레임 단위로 쓰려면 새 RPC(하트비트)부터 만들어야 한다 — 그럴 이유가 없다.

따라서 쓰기 지점은 하나로 정해진다:

```java
// PoseDataService.savePoseDataBatch — 이미 JdbcTemplate 배치가 도는 곳
jdbcTemplate.update("UPDATE exercise_sessions SET last_active_at = ? WHERE id = ?", now, sessionId);
```

- **rep 당 1회** — 수 초에 한 번. 핫 로우가 아니다
- **`JdbcTemplate` 이라 `@Version` 을 건드리지 않는다** — AI 콜백 ↔ 스케줄러 경쟁 조율은 그대로 유지된다
- 그 메서드가 이미 `JdbcTemplate` 배치를 쓰고 있어 자연스러운 자리다

#### 정정 2 — [#92](https://github.com/Shadowfit/init/issues/92) 와 차단 관계가 아니다

초안에는 *"#92 가 휴식 중 프레임 전송을 줄이면 생존 신호가 함께 사라지므로 두 이슈를 같이 결정해야 한다"* 고 적었다. **과장이었다.** 신호원이 프레임이 아니라 **rep 완성**이므로, #92 가 휴식 중 전송을 줄여도 rep 은 그대로 완성되고 갱신도 그대로 일어난다.

다만 완전히 독립은 아니다 — 아래 `N` 을 정할 때 "휴식 중 갱신이 멈추는 구간"의 길이가 #92 의 구현에 따라 달라질 수 있다. **함께 보되, 차단하지는 않는다.**

#### 남는 제약 — 신호가 rep 단위라 거칠다

휴식 동안에는 rep 이 안 나오므로 갱신이 멈춘다. 그래서 유휴 임계 `N` 을 **휴식보다 넉넉히** 잡아야 한다.

- 세트 사이 휴식은 90초까지 관측됐다([#91](https://github.com/Shadowfit/init/issues/91) 재현)
- **권고: `N = 10분`** — 90초 휴식에 여유가 크고, 이탈한 세션이 45분이 아니라 10분 만에 정리된다. `N` 이 무엇이든 현행 45분보다 낫다는 것이 이 결정의 핵심이라, 값 자체는 착수 시 확정한다
- rep 이 아직 하나도 없는 세션(준비·자세 잡는 중)은 `last_active_at` 이 null 이다 → **기존 식(`start_time + 예상 운동시간 + 버퍼`)으로 폴백**한다

> **📌 구현 중 정정 (2026-08-03)** — 이 문단은 처음에 *"`COALESCE(last_active_at, start_time)` 로
> 폴백하면 현행과 같은 동작이라 안전하다"* 고 적었다. **부정확했다.** `COALESCE` 로 앵커만 바꾸면
> 활동이 없는 세션은 `start_time + N`(=10분)이 되어, 자세를 잡거나 워밍업하느라 첫 rep 이 늦는
> 사용자를 **시작 10분 만에 걷어간다.** 종전(45분)보다 오히려 공격적이다.
>
> 실제로 "현행과 같은 동작"이 되려면 폴백이 **기존 식 전체**여야 한다. 그래서 구현은 두 구간으로
> 나뉜다 — 첫 rep 전에는 종전과 완전히 동일하고, 첫 rep 이 들어오는 순간 유휴 판정으로 넘어간다.
> `default-buffer-minutes` 를 지우지 않고 **준비 구간 전용으로 남긴** 이유가 이것이다.

#### 파급 — 재부착 판정도 함께 바뀐다 (의도한 것)

`SessionService.findReattachableSession` 은 스케줄러와 **같은 식**(`Session.isTimedOutAt`)을 일부러 공유한다. 두 기준이 어긋나면 "재부착은 됐는데 곧 걷혀가는" 세션이 생기기 때문이다(주석 `SessionService:78-80`).

그래서 ㄷ 를 하면 재부착 가능 여부도 **"45분 지났나" → "최근에 활동했나"** 로 바뀐다. 이쪽이 더 맞다 — 40분 쉬다 돌아온 사람은 어차피 이어할 게 아니고, 5분 전까지 운동하던 사람은 이어하는 게 맞다.

### ㄹ. 현행 유지

- 3-2·3-3 은 사용자에게 드러나는 빈도가 아직 측정되지 않았다
- 3-1 은 드러나지만 **얼마나 부풀었는지 실측이 없다**
- 유지한다면 그 근거(빈도 미측정)를 남기고, 측정 결과가 나오면 재개하는 조건부 결정이어야 한다

---

## 6. 왜 ㄷ 인가

**ㄷ 만 원인을 고친다. ㄱ·ㄴ 은 증상 하나씩을 덮는다.**

원인은 §1 그대로 "생존을 활동이 아니라 경과 시간으로 판단한다"이고, 그 앵커를 바꾸는 것은 ㄷ 뿐이다. ㄱ·ㄴ 은 통계(3-1)만 손대고 방치(3-2)·조기 종료(3-3)는 그대로 남긴다.

- **ㄱ 을 안 쓰는 이유** — FAILED 를 통계에서 빼면 부풀림은 사라지지만 **실제로 한 운동도 같이 사라진다.** 고치는 게 아니라 덮는 것이다
- **ㄴ 을 안 쓰는 이유** — `pose_data.MAX(created_at)` 는 근거가 실재하고 쓰기 경로도 안 건드려 매력적이지만, **읽는 시점에만 답한다.** 타임아웃 판정을 바꾸지 못하므로 3-2·3-3 이 남는다. ㄷ 를 하면 같은 정보를 세션 행이 이미 들고 있어 이 조회가 불필요해진다 — 즉 ㄴ 은 ㄷ 의 하위집합이고, 먼저 하면 두 번 손대게 된다
- **ㄷ 의 면적이 초안 평가보다 작다** — §5-ㄷ 정정 1·2 참고. 쓰기 지점이 `savePoseDataBatch` 한 곳으로 정해지고, `@Version` 경쟁도 #92 차단도 없다

ㄷ 는 부수적으로 **재부착 판정까지 옳은 기준으로** 바꾼다(§5-ㄷ 파급).

---

## 6-1. 이미 같은 문제를 국소적으로 우회한 곳이 있다 (구현 중 발견)

**회원 탈퇴 가드가 이 판정을 이미 자기 방식으로 하고 있다.** `MemberService:171` 이
`poseDataRepository.countSince(inProgressIds, since)` 로 "최근 `active-workout-idle-seconds`(180초)
동안 `pose_data` 유입이 있었나"를 보고, 없으면 죽은 세션으로 간주해 탈퇴를 허용한다([#87](https://github.com/Shadowfit/init/issues/87)).

설정 주석의 근거가 이 문서와 **같은 진단**이다:

> *"상태값만 보면 좀비 세션 때문에 운동 중이 아닌 사용자가 최대 ~45분간 탈퇴하지 못한다"*
> *"휴식 중에는 rep 이 완성되지 않아 AI 가 콜백을 안 보내므로 Spring 이 보기엔 죽은 세션과 구분되지 않는다"*

즉 **같은 뿌리(§1)를 한 번 국소적으로 우회한 전례**가 있고, 이번 ㄷ 는 그것을 전역 기준으로 올린 것이다.

**지금 통합하지 않는다.** 탈퇴 가드는 동작 중이고 임계값도 다르다(180초 vs 10분 — 각자 근거가 있다:
전자는 세트 휴식 90초의 2배, 후자는 유휴 판정 여유). `last_active_at` 으로 바꾸면 `countSince` 조회가
사라지지만, 그건 **후속 정리**이지 이 변경의 일부가 아니다. 별도 이슈로 남긴다.

---

## 7. 구현 전에 정하거나 재야 할 것

이 문서의 근거는 **전부 코드 유도**다. 결정(ㄷ)은 "어느 방향이 원인을 고치는가"로 내렸고, 아래는 그 방향 안에서 남은 값·수치다.

**정할 것**

1. **유휴 임계 `N`** — 권고 10분(§5-ㄷ). 세트 사이 휴식 90초 관측치보다 넉넉해야 한다는 것이 하한 근거다. 상한 근거(너무 길면 방치가 남는다)는 재보지 않았다

**잴 것**

2. **rep 간격의 실제 분포** — `N` 의 하한을 90초 하나가 아니라 분포로 잡을 수 있다. `pose_data.created_at` 으로 사후 계산 가능
3. **`savePoseDataBatch` 에 UPDATE 한 방 추가하는 비용** — rep 당 1회라 작을 것으로 보나 재지 않았다. 같은 트랜잭션 안 배치 INSERT 뒤에 붙는다
4. **`last_active_at` 인덱스 필요 여부** — 스케줄러가 `findByStatus(IN_PROGRESS)` 로 전체를 훑은 뒤 애플리케이션에서 거르는 현재 구조라 당장은 불필요해 보인다. 훑는 대상이 커지면 재검토

**여전히 미측정 (결정을 바꾸지는 않음)**

5. **FAILED 세션의 `end_time - start_time` 분포** — 3-1 의 실제 크기
6. **45분 이상 운동의 빈도** — 3-3 이 실제로 얼마나 일어나는가

5·6 은 운영 데이터가 필요하다. 값이 작더라도 ㄷ 가 원인을 고친다는 판단은 바뀌지 않으므로 **결정의 전제가 아니라 우선순위의 근거**다.

부하 관련 수치는 [`./load-test-glossary.md`](./load-test-glossary.md) 의 환경 한계(i3-6100 2물리코어에 MySQL+백엔드 동거)가 그대로 적용되므로 **절대값이 아니라 상대·델타로만** 읽어야 한다.
