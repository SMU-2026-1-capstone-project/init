# 운동 세션 생명주기 — 프론트 핸드오프

> 작성: 2026-07-29 · 대상: 프론트 담당
> 한 줄: **운동 화면이 "시작"과 "정상 종료"만 다루고, 그 사이에 사용자가 화면을 벗어나는 경우를 전혀 처리하지 않는다.** 세션이 서버에 방치되고, 화면 밖에서도 카메라 폴링이 계속 돌고, AI가 보내는 실패 응답을 읽지 않는다.
> 근거: 백엔드/AI 코드 대조 + `frontend/` 전수 확인(2026-07-29). 아래 file:line 은 전부 실제로 읽고 적은 것.

세션 생명주기 코드는 **전부 `app/(tabs)/exercise.tsx` 한 파일**에 있다. 별도 store 도 hook 도 없다.

---

## 0. 현재 동작 요약

| 사용자 행동 | 지금 일어나는 일 |
|---|---|
| 녹화 버튼 탭 | `POST /exercises/sessions` → 새 세션 생성, `sessionId` 를 `useState` 에 보관 (`exercise.tsx:89`) |
| 운동 중 (330ms 주기) | 카메라 프레임 → `POST /api/v1/pose` (`exercise.tsx:194`) |
| 녹화 버튼 다시 탭 | `PATCH /sessions/{id}/end` → 리포트로 이동 (`exercise.tsx:102-103`) |
| **탭 전환** | 세션 안 끝남. **화면이 언마운트되지 않아 폴링이 계속 돎** |
| **뒤로가기 버튼** | 세션 안 끝남. `router.back()` 만 호출(`exercise.tsx:288`) — **역시 언마운트가 아니라 폴링이 계속 돎** |
| **앱 백그라운드** | 세션 안 끝남. `AppState` 핸들러가 없어 **앱 코드 차원의 처리가 0**(런타임 동작은 아래 주석) |
| **앱 복귀** | **아무것도 안 함.** 재개 코드 없음 |

이탈 3종은 **"세션이 안 끝난다"는 점에서 같지만 폴링 거동이 다르니** 재현할 때 구분할 것:

- **탭 전환·뒤로가기는 화면이 살아있다.** `(tabs)/_layout.tsx:14-31` 의 `Tabs` 에 `unmountOnBlur`/`freezeOnBlur` 가 없어서 한 번 마운트된 탭 화면은 그대로 남는다. 뒤로가기도 같은 탭 네비게이터 안에서 이동하는 것이라 언마운트가 아니다. 그래서 폴러 cleanup(`exercise.tsx:195-198`)이 **안 돈다**
- 그 cleanup 이 실제로 도는 건 의존성(`[isRecording, sessionId, exerciseId]`, `:199`)이 바뀌거나 화면이 진짜 언마운트될 때뿐이다
- **앱 백그라운드는 코드가 아니라 OS 가 결정한다.** 앱 쪽엔 아무 처리가 없고, JS 타이머와 카메라가 백그라운드에서 어떻게 되는지는 플랫폼(iOS/Android)·빌드 종류에 따라 다르다. ⚠️ **이 부분은 실제 디바이스에서 확인하지 않았다** — 코드 확인으로 말할 수 있는 건 "`AppState` 리스너가 없다"까지다

`PATCH /sessions/{id}/end` 호출 지점은 **단 하나** — 녹화 버튼을 두 번째로 탭했을 때(`exercise.tsx:97-105`, 버튼은 `:414`)다.

---

## 1. 화면을 벗어나도 세션이 안 끝난다

### 확인한 것

세션을 끝낼 수 있는 진입점을 전부 뒤졌고, **하나도 없다**:

| 봤던 것 | 결과 |
|---|---|
| `AppState` 리스너 (백그라운드 감지) | `frontend/` 전체에 **0건** |
| `navigation.addListener('beforeRemove' / 'blur')` | **0건** |
| `BackHandler` (안드로이드 물리 뒤로가기) | **0건** |
| `useFocusEffect` | 3곳뿐, 전부 데이터 리페치 (`index.tsx:66`, `activity.tsx:22`, `mypage.tsx:58`) — 운동 화면엔 없음 |
| `exercise.tsx` 의 useEffect cleanup | 3개 다 세션과 무관 — 토스트 `clearTimeout`(`:135`), 폴러 정리(`:195-198`), 애니메이션 `pulse.stop()`(`:232`) |

화면 안 뒤로가기 버튼도 `router.back()` 만 한다 (`exercise.tsx:288`).

### 서버 쪽에서 벌어지는 일

세션 row 가 `end_time = null`, `status = IN_PROGRESS` 로 남는다. AI 에도 중단 신호가 안 간다. 그러면 백엔드 타임아웃 스케줄러가 걷어가는데, 기준이 **`시작시간 + 예상 운동시간 + 30분`** 이다(`SessionTimeoutScheduler.java:81-83`). 종료 시점 기준이 아니라 **시작 시점 기준**이라 이렇게 된다:

| 예상 20분 운동에서 이탈한 시점 | 세션이 정리되기까지 |
|---|---|
| 시작 5분 만에 | **45분** |
| 시작 20분 만에 | 30분 |

그동안 그 세션은 계속 `IN_PROGRESS` 이고, 정리될 때는 `COMPLETED` 가 아니라 **`FAILED`** 로 기록된다. 사용자가 실제로 한 운동이 "실패"로 남는다.

### 필요한 것

`AppState` 가 `background` / `inactive` 로 갈 때, 그리고 화면을 실제로 떠날 때 최소한 **폴링은 멈춰야** 한다. 세션을 끝낼지(= `end` 호출) 이어갈지는 §4 의 재개 설계와 묶인 결정이라 서버와 같이 정해야 한다.

---

## 2. 화면을 떠나도 카메라 폴링이 계속 돈다 ⚠️

`(tabs)` 네비게이터에 `unmountOnBlur` / `freezeOnBlur` 설정이 없다 (`app/(tabs)/_layout.tsx:14-31`). 그래서 **다른 탭으로 이동하든 뒤로가기를 누르든** 운동 화면이 **마운트된 채로 남는다.** 뒤로가기(`router.back()`, `exercise.tsx:288`)도 같은 탭 네비게이터 안의 이동이라 언마운트가 아니다.

폴링 `useEffect` 의 의존성은 `[isRecording, sessionId, exerciseId]` (`exercise.tsx:199`) 라 **화면 이동으로는 재실행되지 않는다.** cleanup(`:195-198`)이 도는 건 저 의존성이 바뀌거나 화면이 진짜 언마운트될 때뿐이다. 결과적으로 **330ms 인터벌이 그대로 살아서** `takePictureAsync` 와 `POST /api/v1/pose` 를 계속 호출한다 (`exercise.tsx:194`).

즉 사용자가 다른 화면을 보고 있는 동안에도 **초당 3회 카메라 촬영 + 서버 요청**이 나간다. 배터리·데이터·서버 부하가 전부 영향을 받고, 화면에 보이지도 않는 프레임이 `pose_data` 에 쌓인다.

**이건 재개 기능과 무관하게 지금 새고 있는 부분이다.** 재개를 만들든 안 만들든 고쳐야 한다.

> 참고: 폴러는 `EXPO_PUBLIC_INTERNAL_API_TOKEN` 이 설정돼 있어야만 돈다(`exercise.tsx:144-145`). 이 값이 없는 환경에서는 위 증상이 안 보인다.

---

## 3. AI 가 보내는 실패 응답을 안 읽는다 ⚠️

`exercise.tsx:174-191` 이 pose 응답에서 읽는 필드는 셋뿐이다:

```ts
const r = res.data;
if (r.sync_rate != null) setSyncRate(Math.round(r.sync_rate));
if (r.feedback_type) setLastFeedback(r.feedback_type);
if (r.rep_count != null) setRepCount(r.rep_count);
```

`r.success` 와 `r.message` 는 **한 번도 읽지 않는다.** 타입에는 둘 다 선언돼 있다 (`types/pose.ts:19`, `types/pose.ts:25`).

### 왜 문제인가

AI 는 세션 상태를 못 찾아도 **HTTP 에러를 주지 않는다.** `200 OK` 에 이렇게 담아 보낸다 (`ai-server/app/api/endpoints/pose.py:64-69`):

```json
{ "success": false, "message": "세션 42가 시작되지 않았습니다 (StartAnalysis 먼저 호출 필요)" }
```

axios 는 `200` 이라 예외를 안 던지고, 클라는 그 응답을 그냥 버린다. 그래서:

- **UI 가 마지막 싱크로율·rep 수에 그대로 얼어붙는다** (덮어쓸 값이 안 오므로)
- 그 상태로 **330ms 마다 죽은 세션에 계속 요청을 보낸다** — 멈추는 조건이 없다
- 사용자는 아무 안내도 못 받는다

`catch` 블록(`exercise.tsx:186-191`)은 HTTP/네트워크 오류에만 걸리고, 내용은 `__DEV__` 조건부 `console.warn` 한 줄이 전부다. `aiService.ts:20-24` 에도 재시도 인터셉터는 없다(타임아웃 8000ms — 폴링 주기 330ms 보다 길다).

> 같은 종류의 누락이 백엔드에도 있었고 [#58](https://github.com/Shadowfit/init/issues/58) 로 고쳤다. gRPC/HTTP 의 **전송 성공**과 응답 본문의 **업무 성공**은 다른 축인데 전송 층만 보고 있었던 것. 클라도 동일하다.

### 필요한 것

`r.success === false` 를 분기해서 (a) 폴링 중단, (b) 사용자에게 안내, (c) 재시작 경로 제공. 최소한 조용히 계속 쏘는 것만은 멈춰야 한다.

---

## 4. 재개(resume) — 지금은 프론트·백엔드 **양쪽 다 없다**

### 현재 상태

`sessionId` 는 **휘발성 `useState` 하나뿐**이다 (`exercise.tsx:72`).

- `stores/` 에는 `authStore.ts` 하나뿐이고 `persist` 미들웨어가 없다 (`authStore.ts:46`). 세션용 store 자체가 없다
- `SecureStore` 는 토큰·이메일에만 쓴다 (`authStore.ts:59-61` 외). `sessionId` 는 안 넣는다
- `@react-native-async-storage/async-storage` 는 `package.json:13` 에 있지만 **소스 어디서도 import 하지 않는다**

그래서 앱이 재시작되면 `sessionId` 가 `null` 로 돌아가고, 폴러 가드(`exercise.tsx:143`)에 걸려 멈춘다. 사용자가 할 수 있는 건 녹화를 다시 누르는 것뿐이고 그러면 `startSession`(`exercise.tsx:89`)이 **새 세션 row 를 만든다.** 이전 세션은 고아로 남는다.

### ~~백엔드도 마찬가지다~~ → ✅ **서버 쪽은 완료됐다 (2026-07-31)**

이 문서를 처음 쓸 때는 세션 API 가 셋뿐이고 재부착용 진입점이 없었다. 지금은 **둘이 추가됐고, 이제 공은 프론트에 있다.**

| API | 용도 | 상태 |
|---|---|---|
| `GET /sessions/active` | 진행 중인 내 세션 조회 — **`sessionId` 를 서버에서 되찾는다** | ✅ 신설 (#59 1단계) |
| `POST /sessions/{sessionId}/reattach` | 재부착 — AI 분석 상태를 되살린다 | ✅ 신설 (#59 2단계) |
| `POST /exercises/sessions` | 시작. 항상 새 row (`ExercisesController.java:52-63`) | 기존 |
| `PATCH /sessions/{sessionId}/end` | 종료 | 기존 |
| `DELETE /sessions/{sessionId}` | 삭제 (클라에서 호출하는 곳 없음) | 기존 |

**`sessionId` 를 로컬에 저장할 필요가 없어졌다.** `GET /sessions/active` 가 서버에서 돌려주므로 `async-storage` 도입은 불필요하다 — 재설치·기기 변경에도 동작하고, 로컬에 저장된 id 가 이미 `FAILED` 처리된 낡은 값일 위험도 없다.

### 프론트가 해야 할 일 (3단계)

**1. 운동 화면 진입 / 앱 복귀 시 `GET /sessions/active`**

- `204 No Content` → 진행 중 세션 없음. 평소대로 "시작" 버튼
- `200` + `endTime == null` → **이어할 수 있는 세션이 있다.** 2번으로
- `200` + `endTime != null` → 사용자가 이미 종료를 눌렀고 결과 처리 대기 중. 이어하기를 권하면 안 된다

**2. 프레임 전송 전에 `POST /sessions/{sessionId}/reattach`** — 이걸 안 부르면 AI 가 프레임을 전부 거부한다

| 응답 | 의미 | 클라 동작 |
|---|---|---|
| `200` | 재부착 성공 | 같은 `sessionId` 로 폴링 재개 |
| `404` | 남의 세션/없음/이미 끝남/종료 요청됨 | 이어하기 포기, 새 세션 |
| `410 W008` | 재개 가능 시간(운동시간+30분) 초과 | 안내 후 새 세션 |
| `503 W009` | AI 서버 연결 실패 | **세션은 살아있다.** 잠시 후 재시도 — 재요청은 멱등이라 안전 |

`200` 응답 본문:

```json
{
  "sessionId": 42,
  "restoredRepCount": 8,
  "alreadyActive": false,
  "analyzerStateReset": true,
  "message": "8회까지 이어서 진행합니다. 자세 판정이 잠시 흔들릴 수 있습니다."
}
```

- `restoredRepCount` — **UI 카운터를 이 값으로 맞춰야 한다.** 0 부터 그리면 서버 집계와 어긋난다
- `alreadyActive: true` — AI 상태가 이미 살아있어 아무것도 안 했다는 뜻. 손실 없음, 안내 문구도 다르다
- `analyzerStateReset: true` — 아래 경고에 해당하는 상태. `message` 를 그대로 노출해도 되게 써 두었다

**3. `AppState` 핸들링** — 이탈 시 폴링 정지, 복귀 시 1번부터

> ⚠️ **재부착으로도 복원되지 않는 것이 있다.** rep 카운트는 이어지지만 분석기 내부 상태(rep 진행 단계, 각도 스무딩 이력)는 초기화되고, 재부착 시점에 진행 중이던 rep 의 프레임은 버려진다. 사용자 체감으로는 **"횟수는 이어지는데 처음 몇 프레임 동안 자세 판정이 흔들릴 수 있다"** 이다. 감수하기로 확정된 사항이며(`session-resume-and-ai-state.md` §4-0), 그래서 `analyzerStateReset` 과 `message` 로 알려준다.
>
> ⚠️ 재부착이 일어난 세션은 **리포트의 평균/최대/최소 싱크로율이 재부착 이후 구간만 반영**한다(총 횟수는 정확). 같은 문서 §7-1 에 미해결로 박제돼 있다.

---

## 5. 우선순위 제안

| 순위 | 항목 | 왜 |
|---|---|---|
| 1 | §2 탭 전환 시 폴링 정지 | 재개 설계와 **무관**하게 지금 새고 있음. 수정 범위도 작음(`_layout.tsx` 옵션 또는 폴러 의존성) |
| 2 | §3 `success:false` 분기 | 역시 무관하게 필요. 죽은 세션에 무한 요청하는 것만은 막아야 함 |
| 3 | §1 이탈 시 처리 | §4 결정과 묶임 — "끝낼지 이어갈지"가 정해져야 구현 방향이 나옴 |
| 4 | §4 재개 | ~~서버 엔드포인트 선행. 백엔드 결정 대기~~ → **서버 완료(2026-07-31). 더 이상 대기 사유 없음** |

1·2 는 서버 변경 없이 프론트만으로 끝난다. **4 도 이제 프론트만 남았다** — 서버 API 두 개는 이미 있다.

### 🔄 2026-08-08 확인 — 네 항목 전부 아직 프론트에 없다

이 문서를 쓴 뒤 8일이 지났고, 그 사이 **프론트 쪽 진척은 확인되지 않는다.**

```
$ grep -rn "reattach|Reattach" frontend --include=*.ts --include=*.tsx    → 0건
```

즉 순위 4 의 `POST /sessions/{id}/reattach` 는 **여전히 아무도 부르지 않는다.** 서버는 2026-07-31 에 준비를 끝냈고(`084fac7`), 그 뒤로 백엔드 쪽에서 재부착 경로를 **더 손봤다** — 타임아웃으로 걷어가는 세션도 AI 에 통보하고(#98, `e28bc65`), 재부착↔타임아웃 레이스의 결과를 테스트로 확정했다(`d440cae`). **서버는 이 기능을 전제로 계속 움직였는데 클라이언트 진입점이 비어 있다.**

> 🔴 **그래서 지금 실제 동작은 이 문서가 서술한 "이탈 시 방치" 그대로다.** 재부착 API 가 «있다»는 것과 «쓰인다»는 것은 다르다 — [`../architecture/ai-backend-integration.md`](../architecture/ai-backend-integration.md) §3-1 이 같은 구분(**선언 ≠ 사용**)을 결합면 전체에 적용하기 시작했고, 이 항목이 그 목록에 들어간다.
>
> 📌 **데이터 계층 핸드오프는 그 사이 완료됐다**([`frontend-data-layer.md`](./frontend-data-layer.md) — 요청 7개 전부 존재). 즉 **프론트 트랙이 멈춘 게 아니라, 생명주기 항목만 안 집힌 것**이다. 순위 1·2(폴링 정지·`success:false` 분기)는 서버 의존이 없고 범위도 작은데 그것조차 안 됐다 — 우선순위 제안이 전달되지 않았을 가능성을 의심해야 한다.

---

## 관련 문서·이슈

- [`docs/decisions/session-resume-and-ai-state.md`](../decisions/session-resume-and-ai-state.md) — 재개와 AI 상태 내구성 (백엔드, 결정 전)
- [#59](https://github.com/Shadowfit/init/issues/59) — AI 재시작 시 재개 불가
- [#58](https://github.com/Shadowfit/init/issues/58) · [#60](https://github.com/Shadowfit/init/pull/60) — 백엔드에서 같은 종류(업무 층 응답 무시)를 고친 건
