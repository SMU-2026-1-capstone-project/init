# 스타일 식별자 — 「고르는 UI 는 있는데 고른 게 채점에 안 닿는다」

작성일: 2026-08-16
상태: **분석/추천 (결정 전)** — 채택은 사용자 confirm 후 박제 ([[feedback_user_decides_not_claude]])
대상: `reference-style-and-caching.md` §2 «필요» 를 실제 스키마·경로 변경안으로 바꾸는 것
연관: [`reference-style-and-caching.md`](./reference-style-and-caching.md)(제품 모델·§2 필요) ·
[`reference-freeze.md`](./reference-freeze.md)(고정 정책 — §4 구현 형태가 여기에 걸린다) ·
[#220](https://github.com/Shadowfit/init/issues/220) · [#192](https://github.com/Shadowfit/init/issues/192)

---

## 0. 이 문서가 여는 것

제품 모델은 **«운동 1 : N 스타일, 사용자가 고른다»** 로 결정돼 있다
([`reference-style-and-caching.md`](./reference-style-and-caching.md) §1). 그런데 지금
스키마에 스타일 식별자가 없어서 **N 을 담을 수가 없다.**

**그리고 그것보다 나쁜 상태가 이미 있다 — 아래 §1.**

---

## 1. 🔴 지금 상태 — 고르는 절반은 있고, 닿는 절반이 없다

«스타일» 의 원형이 **이미 URL 로 존재한다.** 코드를 따라가면:

```
users.preferred_url            ← 사용자가 온보딩에서 고른다 (없으면 세션 생성 400)
  → ExerciseAnalysisService:190   finalUrl = member.getPreferredUrl()
  → :196                          createSession(..., finalUrl)      → exercise_sessions.reference_source 에 기록
  → :238                          .setReferenceSource(finalUrl)     → AI 로 전달
```

**여기까지는 고른 값이 흐른다.** 그런데 좌표는:

```
  → :233   referenceRepository.findByExerciseId(appDto.getExerciseId())
```

**`exercise_id` 로만 조회한다.** 즉 사용자가 어떤 URL 을 골랐든 **같은 좌표 한 벌**이 간다.

> 🔴 **선택이 채점에 반영되지 않는다.** 「고르는 UI 는 있는데 고른 게 안 닿는」 상태다.
> 그리고 `reference_source` 는 AI 가 **읽지도 않는다**(ai-server 전체에서 사용처 0곳 —
> proto 필드로만 존재). 기록만 되고 아무 일도 하지 않는다.

즉 이 작업은 **«없는 기능을 새로 만드는 것» 이 아니라 «끊긴 절반을 잇는 것»** 이다.

---

## 2. ⭐ 범위 — AI 는 한 줄도 안 바뀐다

이 설계의 가장 중요한 사실이다.

`AnalyzeRequest` 는 **좌표를 통째로 받는다**(`reference_poses`). AI 는 그것을 각도로 바꿔
쓸 뿐, «어느 스타일인지» 를 알 필요가 없다. `reference_source` 도 안 읽는다.

| 계층 | 변경 |
|---|---|
| **AI (ai-server)** | ✅ **없음.** Spring 이 «어느 스타일의 좌표를 보낼지» 만 정하면 된다 |
| proto | ✅ **없음.** 필드 추가 불필요 |
| Spring | 🔧 조회 키 · 세션 기록 · 관리 API |
| DB | 🔧 스키마 |
| 프론트 | 🔧 선택 화면 (별건) |

📌 [[feedback_minimize_python_changes]] 관점에서도 좋은 형태다 — **파이썬을 안 건드린다.**

---

## 3. 스키마 — 두 안

### ㄱ. `exercise_references` 에 `style_id` 컬럼 + 스타일 메타 테이블

```
exercise_reference_styles           exercise_references
  id           PK                     id                PK
  exercise_id  FK                     style_id          FK  ← 신설
  name         "김종국식"              exercise_id       (유지 — 조회 편의·검증용)
  thumbnail_url                       timestamp_sec
  difficulty                          joint_coordinates
  source_url                          created_at
  created_at
```

- 🟢 기존 테이블을 **버리지 않는다.** 37행 시드가 그대로 살아 있고 `style_id` 만 채우면 된다
- 🟢 조회가 `findByStyleId` 로 바뀔 뿐 구조가 같다
- 🔴 `exercise_id` 가 두 곳에 생겨 **불일치 가능**(스타일의 운동 ≠ 좌표의 운동). 제약이 필요

### ㄴ. 좌표를 스타일 테이블에 JSON 배열로 통째로

```
exercise_reference_styles
  id · exercise_id · name · thumbnail_url · difficulty · source_url
  sequence  JSON   ← 30프레임 × 33랜드마크를 한 칸에
```

- 🟢 «스타일 1개 = 좌표 1벌» 이 **스키마로 강제**된다. #220(누적) 같은 결함이 구조적으로 불가능
- 🟢 행이 스타일당 1개라 `@Cacheable` 이 자연스럽다
- 🔴 `timestamp_sec` 이 배열 안으로 들어가 **SQL 로 못 다룬다**
- 🔴 **기존 37행을 통째로 옮겨야** 한다. V4 시드도 다시 써야 한다
- 🔴 Spring 이 AI 로 보낼 때 다시 `PoseDataRequest` 리스트로 풀어야 한다(변환 코드 신설)

### 🟢 추천 — **ㄱ**

ㄴ 이 «1스타일 1벌» 을 구조로 강제하는 것은 매력적이지만, **지금 도는 경로를 전부 바꾼다.**
ㄱ 은 조회 키 하나가 바뀌고 나머지가 그대로다. #220 은 이미 코드로 막았고
(`deleteByExerciseId` → `deleteByStyleId` 로 범위만 좁히면 된다), 구조 강제까지는
**지금 필요한 값보다 비용이 크다.**

---

## 4. 사용자의 선택을 무엇으로 받나 — 세 갈래

지금은 `users.preferred_url`(URL 문자열) 하나뿐이다.

| | 안 | 내용 | 비고 |
|---|---|---|---|
| **A** | `users.preferred_style_id` 신설 | 운동과 무관한 «내 기본 스타일» 1개 | 🔴 운동이 여러 개면 안 맞는다 |
| **B** | `(user, exercise) → style` 매핑 테이블 | 운동마다 다른 스타일을 기억 | 제품 모델에 맞다. 테이블 1개 추가 |
| **C** | **세션 시작 요청에 실어 보낸다** | 매번 고른다. 서버는 기억 안 함 | 🟢 가장 단순. 「지난번 것」은 세션 이력에서 읽으면 된다 |

🟢 **추천 C + 세션 기록.** 이유는 §5.

⚠️ **`preferred_url` 을 어떻게 할지 정해야 한다.** 지금 그 값이 없으면 세션 생성이 400 이다.
스타일로 옮기면 그 검증도 같이 옮겨야 하고, 안 옮기면 두 개념이 병존한다.

---

## 5. 세션이 어떤 스타일을 썼는지 기록해야 한다

`exercise_sessions.reference_source`(URL)가 이미 그 자리인데 **URL 이라 스타일과 연결이 안 된다.**
`reference_style_id` 를 추가하면:

- 🟢 **리포트가 「무엇과 비교한 점수인지」를 갖는다.** 지금은 그 정보가 없다
- 🟢 **[`reference-freeze.md`](./reference-freeze.md) 의 핵심 문제가 완화된다** — 정답지가
  바뀌어도 «이 세션은 그때의 스타일로 매겨졌다» 가 남는다
- 🟢 §4-C(매번 고름)가 성립하는 근거다. 서버가 기억 안 해도 **이력에 남으므로** 「지난번 것」을 복원할 수 있다

---

## 6. 🔴 [`reference-freeze.md`](./reference-freeze.md) 와의 충돌을 여기서 푼다

그 문서 §4 가 열어둔 질문이다 — **마이그레이션 박제(A) ↔ 관리자가 런타임에 등록**.

스타일 식별자가 들어오면 답이 나온다:

| 스타일 출처 | 어디에 사는가 |
|---|---|
| **시드 스타일**(V4 로 들어온 것) | 마이그레이션. 코드와 같이 버전 관리된다 |
| **관리자가 등록한 스타일** | DB 에만 |

**두 경로가 갈리는 것은 문제가 아니라 정상이다** — 시드는 «이 저장소가 보증하는 기본 스타일»
이고, 관리자 등록분은 «운영 데이터» 다. 지금 충돌처럼 보이는 이유는 **둘을 구분할 식별자가
없어서** 하나의 `exercise_id` 아래 뒤섞이기 때문이다.

→ 스타일 테이블에 **출처 구분**(시드/관리자)을 두면 «고정» 정책도 갈라 적용할 수 있다:
시드 스타일은 마이그레이션이 정본(A), 관리자 스타일은 재추출 시 교체(D) + 경고.

---

## 7. 마이그레이션 — 기존 37행을 어디로

시드 스타일 1개를 만들고 그 아래로 넣는다. 이름이 필요한데 **근거 있는 이름이 없다** —
Pexels 무료 영상이고 «누구의 스타일» 이 아니다.

- 🔶 «기본» / «표준» 같은 중립적 이름? **그것도 판단이다.** §9 미결정
- ⚠️ 이름을 안 정하면 사용자에게 보일 화면이 안 만들어진다

---

## 8. 의식적으로 안 할 것

| 안 함 | 이유 |
|---|---|
| AI 에 스타일 개념 도입 | §2. 좌표를 통째로 받으므로 알 필요가 없다 |
| proto 변경 | 같은 이유 |
| 캐시 백엔드 교체(Redis) | 그 문서 §6 이 «다중 인스턴스가 트리거» 로 이미 정리. 지금은 1대 |
| 스타일별 임계값·페르소나 분화 | 스타일은 «기준 좌표» 지 «판정 규칙» 이 아니다. 섞으면 범위가 폭발 |

---

## 9. 미결정 (사용자 confirm 필요)

- [ ] **스키마 ㄱ vs ㄴ** (§3). 추천은 ㄱ
- [ ] **선택을 받는 방식 A/B/C** (§4). 추천은 C + 세션 기록
- [ ] **`users.preferred_url` 을 어떻게 하나** — 스타일로 대체 / 병존 / 유지.
      지금 없으면 **세션 생성이 400** 이라 건드리면 통주행이 깨진다
- [ ] **시드 스타일의 이름** (§7). 근거 있는 이름이 없다
- [ ] **관리자 화면 범위** — 목록·등록·삭제까지인가, 등록만인가
- [ ] **착수 여부·순서** — 이 문서는 분석이다. 구현은 별도 confirm

---

## 결정 로그

- 2026-08-16: 설계 초안. `reference-style-and-caching.md` §2 의 «필요» 를 스키마·경로
  변경안으로 바꿨다. **실행 미착수**, §9 미결정 6건.
  - 🔴 **초안을 쓰며 «없는 기능» 이 아니라 «끊긴 절반» 임을 확인했다**(§1) — 사용자가 고른
    `preferred_url` 이 세션·AI 까지 흐르는데 **좌표만 `exercise_id` 로 조회**돼서 선택이
    채점에 안 닿는다. 그리고 `reference_source` 는 AI 가 읽지도 않는다.
  - ⭐ **AI 는 한 줄도 안 바뀐다**(§2). `AnalyzeRequest` 가 좌표를 통째로 받기 때문이다.
    이 사실이 작업 범위를 크게 줄인다.
  - 🔴 [`reference-freeze.md`](./reference-freeze.md) §4 가 «충돌» 로 열어둔 것이 **충돌이
    아니었다**(§6) — 시드 스타일과 관리자 스타일은 출처가 다르므로 정책도 갈라 적용하면 된다.
    지금 충돌로 보이는 이유는 둘을 구분할 식별자가 없어서다.
