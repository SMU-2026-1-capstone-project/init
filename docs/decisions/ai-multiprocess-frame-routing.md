# AI 프로세스 분리 후 프레임 라우팅 — 발견한 버그 셋과 해결

작성일: 2026-08-26
상태: 🟢 로컬 반영 완료, AWS 실측(P6 coresidency) 진행 중
대상: [`per-process-ceiling-cause.md`](./per-process-ceiling-cause.md) 가 N=3 로 닫은 뒤,
실제 배포에 옮기는 과정에서 발견된 문제들.

---

## 0. 배경

`proc-count-sweep-2026-08-24`가 GIL 병목을 프로세스 분리(N=3)로 풀 수 있다고 실측했지만,
**AI 단독 박스**에서 잰 결과였다. 이 문서는 그걸 실제 배포(Spring·프론트·nginx가 낀 전체
경로)에 옮기면서 EC2 격리 환경에서 발견한 버그 셋을 기록한다.

## 1. 버그 ① — 프레임 경로가 세션과 무관하게 흩어진다

**증상**: `--workers 3`(SO_REUSEPORT, 포트 공유)으로 띄우면, Spring→AI 제어 경로(gRPC:
StartAnalysis)는 채널 풀로 세션마다 같은 프로세스에 고정되는데, **프론트→AI 프레임 경로
(HTTP `/api/v1/pose`, Spring 안 거침)는 그 라우팅을 모른다.** 커널이 새 연결을 프로세스
간에 무작위로 분산시켜, 세션의 상당수가 다른 프로세스로 가서 `NO_LEASE`로 거절된다.

**실측**: 세션 6개, Start+프레임 조합 → NO_LEASE 4/6 (67%).

**원인**: `pose.py`의 `/pose` 핸들러가 `get_registry().get(session_id)`를 쓰는데, 이 레지스트리는
프로세스 로컬 메모리다. StartAnalysis가 프로세스 K에 세션을 만들어도, 프레임이 다른 프로세스로
가면 그 프로세스의 레지스트리에는 그 세션이 없다.

**해결**: AI를 SO_REUSEPORT(포트 공유)가 아니라 **워커마다 별도 포트**(8000/8001/8002 HTTP,
8585/8586/8587 gRPC)로 분리(`ai-server/entrypoint.sh`). 앞에 nginx를 두고, Spring이 세션
시작 응답에 `aiWorkerIndex`(0~2)를 실어 보내면, 프론트가 모든 `/pose` 요청에 `X-AI-Worker`
헤더로 그 값을 동봉 → nginx가 해당 포트로 고정 전달(`nginx-ai/default.conf`).

## 2. 버그 ② — Spring 채널 풀이 전부 같은 포트를 가리켰다

**증상**: 버그①을 고친 뒤에도 세션 6개가 **전부 같은 프로세스(pid=7)** 로 몰렸다.

**원인**: `ExerciseAnalysisService.initAiChannelPool()`이 채널 3개를 만들 때, 인덱스와
무관하게 **전부 같은 포트**(`fastApiAddress`에서 파싱한 8585)로 연결하고 있었다. AI가
SO_REUSEPORT(포트 하나 공유)였을 때는 커널이 그래도 분산시켜줘서 우연히 맞물렸는데, AI를
포트별로 분리한 뒤에는 채널 풀도 포트를 다르게 가리켜야 하는데 그러지 않았다.

**해결**: `port = basePort + i` — 채널 인덱스 i가 gRPC 포트 base+i와 짝을 맞추도록 수정.

## 3. 버그 ③ — 부하 rig가 session_nonce 검증 도입 이전 버전이었다

**증상**: 버그①·②를 다 고친 뒤에도 `coresidency-2026-08-15/load_ai.py` 부하 테스트가
"세션이 시작되지 않았습니다 (StartAnalysis 먼저 호출 필요)"로 프레임 전량 실패했다.

**원인**: `pose.py`가 `session_nonce`(#187, 세션 소유권 검증)를 요구하는데, 이 rig는 그
기능 도입 이전에 작성돼 `session_nonce`를 프레임 payload에 아예 안 보내고 있었다.
`classify()`가 "분석기가 없습니다"(nolease)와 문구가 달라 다른 카테고리로 잡았지만, 근본
원인은 버그①과 같은 종류(세션 소유권 확인 실패)다.

**해결**: `session_worker`가 세션 생성 응답의 `sessionNonce`를 저장해 모든 `/pose` 요청에
동봉하도록 수정.

**검증**: 9세션 스모크(15초) — 검출성공 100%, 전량 `ok`, 실패 0건.

## 4. 메모리 오버부킹

**증상**: `mediapipe_detector.py`의 `memory_ceiling()`이 컨테이너 메모리 한도를 "이 프로세스
혼자 쓰는 몫"으로 계산한다. 워커 3개가 각자 독립적으로 이 계산을 하면 오버부킹된다.

**실측**: `POSE_DETECTOR_POOL_SIZE=160`, `AI_MEM_LIMIT=20000m` 조건에서 워커 3개가 각자
160개씩 시도 → 약 47.4GB, 한도 20GB의 **2.4배**.

**해결**: `AI_WORKER_COUNT` 설정을 추가해 `memory_ceiling()`이 한도를 워커 수로 나누게
수정. 재검증: 워커당 자동 계산 66개, 3워커 합산 약 19.85GB — 한도 안에 들어옴.

## 5. 아직 안 고친 것 (다른 세션 리뷰, `docs/decisions/ai-multiprocess-deployment-review.md`
참고 — `docs/ai-multiprocess-deployment-review` 브랜치)

- **cid(correlation id) 전파 끊김**: `ManagedChannelBuilder`로 직접 만든 채널은
  `@GrpcGlobalClientInterceptor`(net.devh 전용)를 안 타서, 채널 풀로 나가는 gRPC 호출에
  correlation id가 안 붙는다. 장애 시 Spring↔AI 로그 추적 끊김.
- **서킷브레이커가 워커 3개를 하나로 묶는다**: 워커 1개만 죽어도 전체가 막히거나, 반대로
  희석돼 안 열릴 수 있다.
- **워커 수(3)가 여러 곳에 이원화**: `ai-server/entrypoint.sh`의 `WORKER_COUNT`,
  Spring의 `AI_CHANNEL_POOL_SIZE`, `nginx-ai/default.conf`의 `map` 블록 — 세 곳을 손으로
  맞춰야 하고 자동 동기화 장치가 없다.
- **라우팅 키 불일치**: `extractReferenceData`만 `exerciseId`로 라우팅하고 나머지는
  `sessionId`로 라우팅한다(의도적일 수 있으나 문서화 안 됨).

## 6. 아직 안 잰 것

- **N=3 + 동거(coresidency) 조합의 실제 최대 동접** — N=3(451.2 rps, AI 단독)과 동거 할인
  (89~105/156, N=1 기준)은 각각 실측됐지만 둘을 합친 실측은 없었다. `P6 coresidency` 라운드
  진행 중(위 상태 참고).
