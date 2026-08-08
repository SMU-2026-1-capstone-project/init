# AI 서버 변경 기록 — 페르소나별 싱크로율 임계값 연결

작성: 2026-07-22 · ✅ **재확인: 2026-08-08 — 코드와 일치, 정정 없음**

> 이 디렉터리 감사(2026-08-08) 중 대조한 결과 **이 문서의 서술이 현재 코드와 그대로 맞다.** `SYNC_THRESHOLDS` 는 `ai-server/app/utils/constants.py:44` 에 100 스케일로 있고, 판정은 `squat_analyzer.py:367` 에서 `SYNC_THRESHOLDS.get(state.persona, SYNC_THRESHOLDS["BEGINNER"])` 로 페르소나를 실제로 탄다. 낙관적으로 적힌 곳도 없었다.
>
> 📌 **같은 디렉터리 8개 중 정정이 필요 없던 유일한 문서다.** 다른 것들은 완료를 안 적었거나(요청이 끝났는데 대기처럼 보임) 착수를 안 적었다(2.5개월 방치). 차이는 **이 문서가 "요청서"가 아니라 "변경 기록"이라는 점** — 작업을 한 사람이 한 일을 적었기 때문에 뒤처질 구조가 아니었다.
대상: **AI(FastAPI) 담당자** — 본인(Backend) + Claude가 [`feedback_minimize_python_changes`] 완화 방침(2026-07-22)에 따라 AI 서버까지 직접 수정. 팀원은 이 문서로 변경 내용만 확인.
배경: 페르소나별 싱크로율 기준(헬린이 60%/헬창 85%/다이어트 70%/재활 50%)이 `12-persona-difficulty.md`에는 있었지만, 실제로는 **Spring DB 컬럼 2개(beginner/advanced만), AI서버 `constants.py`의 죽은 dict(4개, 0~1 스케일)로 따로 놀고 있었고, 세션 판정에 쓰이는 실제 로직은 페르소나 무관 고정값(`sync_rate >= 70/40`)** 이었음. 이번 작업으로 실제로 연결.

---

## 0. 요약

| 항목 | 내용 |
|---|---|
| 무엇을 연결했나 | Member의 `selectedPersona` → gRPC `AnalyzeRequest.persona` → AI서버 `SessionState.persona` → rep 판정 시 페르소나별 임계값 적용 |
| Spring 변경 | 스키마 컬럼 2개 추가, 임계값 admin API 4종 지원, gRPC 세션 시작 요청에 persona 필드 추가 |
| AI서버 변경 | proto 필드 추가, pb2 재생성, 세션 상태에 persona 저장, 하드코딩 70/40 제거 → 페르소나 기반 계산 |
| AI서버 코드 변경량 | 약 20줄 (proto 1줄, session_state 2곳, servicer 3줄, constants 스케일 수정, squat_analyzer 판정 로직) |
| 테스트 | Spring: `compileJava`/`compileTestJava` clean. AI서버: 관련 모듈 import 확인 + 기존 `tests/test_squat_analyzer.py` 영향 없음(해당 테스트가 이 경로를 안 건드림, pytest 미설치라 직접 실행은 못 함) |

---

## 1. AI 서버(`ai-server/`) 변경 파일

### `app/proto/exercise.proto` (텍스트 소스)
`AnalyzeRequest`에 필드 추가:
```proto
message AnalyzeRequest {
  int64 exercise_id = 1;
  int64 session_id = 3;
  string reference_source = 2;
  repeated PoseDataRequest reference_poses = 4;
  string persona = 5;   // 추가. 빈 문자열이면 AI측에서 BEGINNER로 취급
}
```
`backend/src/main/proto/exercise.proto`(Spring 쪽 사본)에도 동일하게 반영 — 두 `.proto` 텍스트는 지금도 수동 동기화 상태(분기 B, 단일 소스화 미결)라 양쪽 다 고침.

### `exercise_pb2.py` / `exercise_pb2_grpc.py` (ai-server **루트**, 재생성)
`app/grpc/exercise_servicer.py`가 `import exercise_pb2`로 **루트의 pb2를 참조**하고 있어(아래 "발견했지만 안 고친 것" 참고) 이 위치의 pb2만 재생성함. `grpc_tools.protoc -I app/proto --python_out=. --grpc_python_out=. app/proto/exercise.proto`로 생성. `exercise_pb2_grpc.py`는 diff 없음(메시지 필드만 추가, RPC는 안 바뀜).

### `app/grpc/session_state.py`
`SessionState` dataclass에 `persona: str = "BEGINNER"` 필드 추가, `SessionStateRegistry.create()`가 `persona` 파라미터를 받아 넘기도록 수정.

### `app/grpc/exercise_servicer.py`
`StartAnalysis`에서 `request.persona`(없으면 `"BEGINNER"`)를 읽어 `get_registry().create(...)`에 전달.

### `app/utils/constants.py`
```python
# 변경 전 (0~1 스케일 — 어디서도 참조 안 되던 죽은 dict)
SYNC_THRESHOLDS = {"BEGINNER": 0.60, "ADVANCED": 0.85, "DIET": 0.70, "REHAB": 0.50}
# 변경 후 (sync_rate와 동일한 0~100 스케일)
SYNC_THRESHOLDS = {"BEGINNER": 60.0, "ADVANCED": 85.0, "DIET": 70.0, "REHAB": 50.0}
```

### `app/core/squat_analyzer.py`
`_summarize_rep`의 하드코딩 판정을 페르소나 기반으로 교체:
```python
# 변경 전
if sync_rate >= 70:
    msg = "자세 양호"
elif sync_rate >= 40:
    msg = "자세 보정 필요"
else:
    msg = "즉시 자세 수정 필요"

# 변경 후
pass_threshold = SYNC_THRESHOLDS.get(state.persona, SYNC_THRESHOLDS["BEGINNER"])
low_threshold = pass_threshold * _LOW_CUT_RATIO   # = 40/70, 기존 두 컷의 비율 유지
if sync_rate >= pass_threshold:
    msg = "자세 양호"
elif sync_rate >= low_threshold:
    msg = "자세 보정 필요"
else:
    msg = "즉시 자세 수정 필요"
```
"즉시 수정" 컷을 페르소나 무관 고정값으로 둘지, 지금처럼 비례 스케일할지는 판단이 필요한 지점이었음 — 기존 비율(40/70 ≈ 0.57) 유지 쪽으로 확정(2026-07-22 대화에서 결정).

---

## 2. Spring 쪽 변경 (참고용 요약)

- `mysql/schema.sql`: `exercises`에 `sync_threshold_diet`, `sync_threshold_rehab` 컬럼 추가 (기존 beginner/advanced와 같은 패턴)
- `Exercise.java`, `ExerciseThresholdResponseDto`, `ThresholdUpdateDto`, `AdminExerciseService`: 4개 페르소나 임계값 admin API로 확장 (beginner < advanced 검증은 유지, diet/rehab은 축이 달라 순서 제약 없음)
- `ExerciseAnalysisService.sendAnalysisRequestToFastApi`: `member.getSelectedPersona().name()`을 gRPC `AnalyzeRequest.persona`로 전달

---

## 3. 발견했지만 이번에 안 고친 것

- **`ai-server/app/proto/exercise_pb2.py`·`exercise_pb2_grpc.py`가 stale.** `.proto` 텍스트는 최신인데 거기서 생성된 pb2는 예전 버전(`GetFinalPoseData`/`SessionRequest`/`PoseDataList` 등 지금 proto엔 없는 것들 포함, `StopAnalysis`/`ReportFeedbackBatch` 없음)이라 사실상 죽은 파일. 실제로 쓰이는 건 ai-server **루트**의 `exercise_pb2.py`(`import exercise_pb2`로 참조됨). 분기 B(proto 단일 소스화 미결)와 얽힌 별도 정리 작업 필요.
- **`POST /sync` REST 엔드포인트(`app/api/endpoints/sync.py` → `dtw_calculator.classify_sync_visual_cue`)는 여전히 고정 70/40.** 이건 세션 기반 gRPC 흐름과 무관한 별도 stateless 엔드포인트(persona/session 컨텍스트 자체가 없음)라 이번 스코프에 안 넣음. 온보딩 가이드용으로만 쓰이는 듯— 필요하면 별도 논의.
- `mysql/data.sql`이 `schema.sql`과 별도로 자체 `CREATE TABLE`을 갖고 있어 컨테이너 init 순서상 `schema.sql`을 덮어쓸 수 있는 문제 발견 — 이번 작업과 별개로 처리하기로 함(사용자 확인, 2026-07-22).

---

## 관련 문서
- [`PRD.md`](../PRD.md) §5-2 — 페르소나 임계값 요구사항 상태
- [`12-persona-difficulty.md`](../12-persona-difficulty.md) — 페르소나 정의·기준값 원본
- [`decisions/ai-backend-coupling.md`](../decisions/ai-backend-coupling.md) — Spring↔AI 결합 관련 기존 분기들
