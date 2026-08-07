# AI 트랙 남은 작업 — 무엇이 비어 있나

작성: 2026-08-07
범위: `ai-server/` (FastAPI + gRPC + MediaPipe). **Spring 백엔드는 [`28-remaining-work-plan.md`](./28-remaining-work-plan.md)** 가 다룬다 — 그 문서는 범위를 백엔드로 못박고 있어서 AI 쪽 잔여가 어디에도 모여 있지 않았다. 이 문서가 그 자리를 채운다.

> ⚠️ **시간 추정을 넣지 않았다.** 이 저장소의 추정 이력은 전부 백엔드 작업 기준이고([`28-remaining-work-plan.md`](./28-remaining-work-plan.md) §6), AI 작업의 실측 데이터가 한 건도 없다. 근거 없는 숫자를 넣는 것보다 **"추정 없음"이라고 적는 편이 정직하다.** 착수할 항목이 정해지면 그때 추정한다.

> 📌 이 문서는 **현황 정리이고 계획이 아니다.** 우선순위는 §6 에 추천으로만 적었고 확정은 사용자 몫이다.

---

## 0. 한 줄 요약

AI 쪽 가장 큰 빈칸은 **"결함을 분류해서 백엔드로 보내는 경로가 통째로 없다"**(§1)는 것이다. proto 도 Spring 수신부도 이미 서 있는데 **AI 가 그 RPC 를 한 번도 부르지 않는다.** 나머지(부하 측정·수평확장·종목 확장)는 그다음이다.

---

## 1. 🔴 결함 분류 → `ReportFeedbackBatch` 송신 — 통째로 미구현

**양쪽 끝은 다 있는데 가운데가 없다.**

| 구간 | 상태 | 근거 |
|---|:--:|---|
| proto 계약 | ✅ 있음 | [`exercise.proto:34`](../../ai-server/app/proto/exercise.proto) — `rpc ReportFeedbackBatch (FeedbackBatchRequest) returns (FeedbackBatchResponse)` |
| Spring 수신·저장 | ✅ 있음 | `session_feedback_logs` 테이블 + 시연용 시드 |
| **AI 분류 로직** | ❌ **없음** | `ai-server/app/` 어디에도 `feedback_type`·`KNEE_OUT` 문자열이 없다 |
| **AI 송신 호출** | ❌ **없음** | [`spring_client.py`](../../ai-server/app/grpc/spring_client.py) 가 구현한 건 `report_pose_data_batch`·`report_complete_analysis` **둘뿐**이다 |

`mysql/dev-seed.sql`(구 `data.sql`)의 세션 801 주석이 이 상태를 그대로 적어놨다 — *"AI 측 분류·송신 로직 완료 전 L1 백엔드 단독 시연용."* **그 "완료 전"이 지금도 유효하다.**

### 딸려 있는 것

- **BT-SET 전송 단위** — [`tts-design.md`](../decisions/tts-design.md) §2.A.BT 가 *"세트 경계 batch + 세션 종료 final"* 을 추천으로 확정해뒀다. 세트 카운터와 retry 가 AI 쪽 작업이다
- **`target_reps_per_set`** — 같은 문서 `:206` 이 세션 시작 시 받는 것으로 설계했으나 미구현. **[#92](https://github.com/Shadowfit/init/issues/92)(휴식 프레임)의 선행이기도 하다** — 세트 경계를 알아야 휴식 구간을 알 수 있다

> 이게 1순위인 이유: **이미 만들어둔 것이 놀고 있다.** proto·테이블·시드가 다 있는데 AI 가 안 불러서 TTS 피드백 기능 전체가 시연용 더미로만 존재한다.

---

## 2. 부하 측정 — 계획만 있고 한 번도 안 쟀다

[`ai-load-budget.md`](../decisions/ai-load-budget.md) 는 상태가 **`OPEN — 측정 미실시`** 이고, §7 결과표가 전부 `TBD / 미실시` 다:

| 측정 | 상태 |
|---|:--:|
| 단일 사용자 5분 베이스라인 | 미실시 |
| 동시 2 사용자 | 미실시 |
| 동시 5 사용자 | 미실시 |

§5 에 측정 계획이 이미 적혀 있으므로 **설계가 아니라 실행만 남았다.**

### 왜 이게 중요한가

이 문서가 세운 명제 — *"MediaPipe 추론이 AI CPU 의 95%+ 를 점유하고, `session_state` 가 in-memory 라 수평 확장이 막혀 있어, **단일 인스턴스 CPU 가 곧 동시 사용자 한계**"* — 가 **측정으로 뒷받침되지 않은 상태다.** [#92](https://github.com/Shadowfit/init/issues/92) 의 "절감 폭 수십 %" 도 같은 이유로 산정치일 뿐이다.

> 🔶 **미검증 표시 유지**: 위 95% 수치와 절감 추정은 근거 문서에 적힌 값이고, 이 문서를 쓰면서 재측정하지 않았다.

---

## 3. `session_state` in-memory — 수평 확장이 코드 레벨에서 막혀 있다

[`session_state.py:100`](../../ai-server/app/grpc/session_state.py) 이 `self._sessions: dict[int, SessionState]` 로 **프로세스 메모리에 들고 있다.** 따라서:

- AI 인스턴스를 2대로 늘리면 **같은 세션의 프레임이 다른 인스턴스로 가면 상태가 없다**
- 인스턴스가 죽으면 진행 중 세션 상태가 사라진다

`ai-load-budget.md` §6.2 가 이미 대응 옵션(우선순위 2)으로 다루고 있다.

> ⚠️ **다만 지금 이걸 고치는 게 맞는지는 별개다.** [[현 서버 AI 구조 유지]] 결정대로 온디바이스·갠플 마이그레이션은 미채택이고, 이 항목은 **면접 설계 서사로 쓰는 편**이 현재 방침에 맞는다. §2 측정으로 "단일 인스턴스로 어디까지 되는가"가 나온 뒤 판단할 일이다.

---

## 4. 종목 확장 — 분석기가 스쿼트뿐이다

`ai-server/app/core/` 에 `squat_analyzer.py` **하나만** 있다. 그래서 `exercises` 시드의 런지·플랭크는 `analysis_supported = FALSE` 이고 세션 생성이 `W007` 로 차단된다.

[`24-semester2-plan.md`](./24-semester2-plan.md) 가 이 의존을 명시적으로 적어놨다:

| 주차 | 백엔드가 막히는 지점 |
|---|---|
| Week 3~4 | **AI-02 런지·플랭크 분석기** — 카탈로그 시드를 등록해도 실제 분석이 안 된다. Week 5 까지 늦어지면 "운동 종류 확장" 발표 임팩트가 약해진다 |

플랭크는 **rep 이 아니라 hold_seconds** 라 카운팅 개념 자체가 다르다 — 분석기 신규 작성에 가깝다.

### proto 동기 변경이 걸려 있다

Week 2 의 BE-09(세트 도입)가 `PoseDataRequest.set_index` 등을 추가하는데, **proto 는 양쪽 사본이 같이 바뀌어야 한다.** 계획 문서가 리스크로 못박아뒀다 — *"proto 변경은 항상 양쪽 동시 PR."* `proto-sync-check.yml` 이 CI 에서 이 어긋남을 잡는다.

---

## 5. 그 밖

| 항목 | 상태 | 비고 |
|---|:--:|---|
| **[#92](https://github.com/Shadowfit/init/issues/92)** 휴식 중 프레임 낭비 | ❌ | **프론트 트랙 작업**이고 AI 는 변경 불필요(프레임이 안 오면 그만)이지만, 낭비되는 자원이 AI CPU 라 여기 적어둔다. §1 의 세트 경계 인지가 선행 |
| **선택형 스타일 기준** | ❌ 미구현 | [`reference-style-and-caching.md`](../decisions/reference-style-and-caching.md) — 상태가 *"분석/추천(일부 결정, 일부 열림)"*. 추출=백그라운드잡, 저장=리샘플+트림 방향까지 정리돼 있다 |
| **ai-server 테스트 잔여** | 🔶 | 현재 10개 파일. [`28-remaining-work-plan.md`](./28-remaining-work-plan.md) §2 의 "코드 검증 잔여" 버킷에 `ai-server 테스트` 가 들어 있다 |

### 이미 서 있는 것 (다시 안 해도 되는 것)

빈칸만 나열하면 그림이 왜곡되므로 적어둔다 — `InternalAuthMiddleware` 인증, correlation-id 전파, 세션 재부착(`test_session_reattach`), rep 카운팅·깊이 판정 스트리밍 테스트, pose 필터, DTW·각도 계산, YouTube 기준 좌표 추출. CI 도 붙어 있다([`ai-server-test.yml`](../../.github/workflows/ai-server-test.yml), 이슈 #96).

---

## 6. 추천 순서 (결정 아님)

> ⚠️ **추천이고 결정이 아니다.** 확정은 사용자 confirm 후 §7 에 박제한다([[결정은 사용자가, Claude 는 추천만]]).

1. **§1 결함 분류·송신** — 이미 만들어둔 proto·테이블·시드가 놀고 있다. 가장 큰 빈칸이고 완성 효과가 즉시 보인다
2. **§2 부하 측정** — 계획이 이미 있어 실행만 남았다. 그리고 이 결과가 §3·§4 의 판단 근거가 된다. **측정 전에 §3 을 손대는 것은 순서가 뒤집힌 것**이다
3. **§4 종목 확장** — 2학기 Week 3~4 에 걸려 있고 백엔드를 막는 유일한 AI 항목이다
4. **§3 수평 확장** — 측정 결과가 필요하다고 말한 뒤에 (지금 방침상 서사로만 쓸 수도 있다)

### 이 프로젝트의 포지션과의 관계

[[백엔드 포지션 지원]] 기준으로 보면 **AI 작업은 채용 시그널 기여가 낮다.** 다만 §1 은 예외에 가깝다 — gRPC 양방향 통신·배치 전송·retry 설계라 **백엔드 서사로 읽히는 부분**이 있다. §4(분석기 작성)는 순수 도메인 로직이라 백엔드 서류에 거의 안 잡힌다.

→ 백엔드 잔여([`28-remaining-work-plan.md`](./28-remaining-work-plan.md))와 경합할 때는 **§1 만 끌어오고 나머지는 뒤로 미는 것**이 포지션에 맞는다.

---

## 7. 결정 로그

- 2026-08-07: 문서 작성. AI 트랙 잔여를 처음으로 한자리에 모았다. **§6 은 추천이고 결정 전.**
