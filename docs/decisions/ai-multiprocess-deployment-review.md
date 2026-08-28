# AI 다중 프로세스 배포 검토 — 진행 중인 작업에 대한 코드 리뷰 (2026-08-26)

작성일: 2026-08-26
상태: 🟡 **검토 진행 중** — 발견 사항은 init-77(동시 세션)에 실시간 전달, **코드는 안 건드렸다**
대상: 아래 세 파일의 **미커밋** 변경(다른 세션이 작업 중)
- `backend/src/main/java/com/shadowfit/service/Exercise/ExerciseAnalysisService.java`
- `ai-server/Dockerfile`
- (검토 시점 기준 `docker-compose.yml`은 안 바뀌었으나 §2-3이 그 파일의 **기존** 계산이 새 변경과 안 맞는다는 것을 짚는다)

연관: [`./ai-sticky-routing.md`](./ai-sticky-routing.md) §5-1 · [`./per-process-ceiling-cause.md`](./per-process-ceiling-cause.md) §9 ·
[`../../loadtest/results/grpc-reuseport-probe-2026-08-26/README.md`](../../loadtest/results/grpc-reuseport-probe-2026-08-26/README.md)

---

## 0. 배경

같은 날(2026-08-26) 두 세션이 같은 질문을 쫓고 있었다 — "GIL이 원인이면, N=3 프로세스로
쪼갠 처방(`per-process-ceiling-cause.md`)을 실제로 배포할 수 있는가?" 이 세션은 파이썬
`grpc.insecure_channel()`로 SO_REUSEPORT를 흉내내 확인했고([결과](../../loadtest/results/grpc-reuseport-probe-2026-08-26/README.md)),
다른 세션(init-77, 추정: `grpc-conn-reuse-test` EC2 인스턴스)은 **실제 Spring→AI 경로**로
같은 것을 확인하고 **채널 풀 기반 해결책까지 구현 중**이었다 — 이 문서를 쓰는 시점 기준
`ExerciseAnalysisService.java`에 `AI_CHANNEL_POOL_SIZE=3` + `Math.floorMod` 라우팅이,
`Dockerfile`에 `--workers 3`이 이미 (커밋 없이) 들어가 있었다.

**이 문서는 그 진행 중인 작업을 읽고 발견한 것을 정리한다.** 판단은 하지 않는다 — 그건
init-77과 사용자의 몫이다([[feedback_user_decides_not_claude]]).

---

## 1. 확인된 것 — 이 부분은 맞다

- ✅ **SO_REUSEPORT는 실제로 작동한다** — 이 세션(파이썬 흉내)과 init-77(실제 Spring)
  둘 다 독립적으로 확인. `ai-server/app/grpc/server.py:93`이 옵션을 안 줘도 grpc-core
  기본값이 켜져 있다
- ✅ **채널 풀 + `sessionId` 해시 라우팅이라는 설계 방향 자체는 타당하다** — 같은 세션은
  항상 같은 채널(=같은 프로세스)로 가고, 세션마다는 채널이 흩어진다. `ai-sticky-routing.md`
  §5-2가 검토했던 무거운 대안들(세션 테이블 컬럼·Redis)보다 훨씬 싸다
- ✅ **`net.devh`(grpc-python C-core)와 grpc-java의 서브채널 공유 방식이 다르다는 관찰**
  (Dockerfile 주석) — 그래서 grpc-java 쪽은 별도 옵션 없이 채널마다 독립적으로 분산됐다는
  것도 이 문서 작성 시점 기준 init-77의 실측(session 1901~1907 → pid 7/8)과 일치한다

---

## 2. 발견한 문제 — 심각도 순

### 2-1. 🔴 프론트→AI 프레임 경로(HTTP)는 안 고쳐졌다 — 가장 크다

`ai-server/Dockerfile`이 `--workers 3`이면 **HTTP 포트(8000)도 3개 워커가 공유**한다.
반면 `frontend/services/aiService.ts:25`는 고정 주소(`EXPO_PUBLIC_AI_BASE_URL`) 하나로
axios를 쓰고 **세션 인지 라우팅이 없다.**

Spring의 채널 풀이 gRPC 제어 호출(StartAnalysis 등)을 세션마다 워커 K로 정확히 고정해도,
프레임(`POST /api/v1/pose`)이 그 워커 K로 갈 확률은 **워커 수분의 1**(N=3이면 1/3)뿐이다.
나머지는 detector가 없는 워커에 가서 `NO_LEASE`로 거절된다 — `pose.py:131~137`.

`ai-sticky-routing.md` §5-1의 표가 이미 *"두 방향(제어·프레임)이 같은 인스턴스로
모여야 한다"*고 적어뒀던 자리가 정확히 여기다.

**미검증**: 실제로 이 상태로 부하를 걸어 nolease율을 재본 적은 없다(추론).

### 2-2. 🔴 검출기 풀이 컨테이너 메모리 한도를 워커 수만큼 오버부킹한다

`ai-server/app/core/mediapipe_detector.py:143~179`의 `memory_ceiling()`이 **cgroup
메모리 한도**(컨테이너 전체 몫)를 읽어 "그 한도를 내가 다 쓸 수 있다"고 계산한다 —
**자기가 그 컨테이너의 유일한 프로세스라는 전제**다.

`--workers 3`이 되면 프로세스 3개가 **같은 cgroup을 공유**하는데, `POSE_DETECTOR_POOL_SIZE`가
0(자동)이면 각자 독립적으로 같은 한도를 자기 것으로 계산한다. `docker-compose.yml:123`의
`mem_limit: ${AI_MEM_LIMIT:-1536m}`을 예로 들면, 워커 하나가 `(1536-BASE_RSS)/98.7 ≈ 15`개를
잡을 수 있다고 계산하고, **3개가 각자 그렇게 계산해 최대 45개(≈4.4GB)까지 오버부킹**할 수
있다 — 실제 한도는 1536MB 그대로다. OOM killer가 뜰 자리다.

⚠️ **오늘의 실측 전부가 이 함정을 안 밟았다** — 이 세션도 init-77(추정)도 `ROLE=ai-venv`
(도커 없이 venv로 uvicorn 직접 기동)로 테스트했다. **cgroup 한도 자체가 그 경로엔 없거나
무의미하다.** 실제 `docker-compose` 배포 경로로는 아무도 아직 검증한 적이 없다 —
[[feedback_ec2_container_memory_cap]]과 같은 계열의, 워커 개수 버전이다.

### 2-3. 🔴 correlation ID(cid) 전파가 gRPC→AI 호출에서 끊긴다

`backend/src/main/java/com/shadowfit/global/observability/GrpcObservabilityConfig.java`가
`@GrpcGlobalClientInterceptor`로 correlation ID 인터셉터를 **전역** 등록해뒀다 — 목적은
*"서비스별로 나열 안 해도 자동 적용"*이다.

새 코드(`ExerciseAnalysisService.java:86`)는 `io.grpc.ManagedChannelBuilder.forAddress(...)
.usePlaintext().build()`로 채널을 **직접** 만든다 — `net.devh`(grpc-client-spring-boot-starter)의
`@GrpcClient` 관리 밖이라 그 전역 인터셉터가 안 걸린다.

인증(`Authorization` 헤더)은 `getAuthenticatedStub()`에서 수동으로 다시 붙여 살아있지만
(`:140-142`), **cid는 아무도 다시 안 붙였다.** 장애 시 Spring 로그와 AI 로그를 cid로
잇던 것이 이 호출들(StartAnalysis·StopAnalysis·ReattachAnalysis·ExtractReferenceData)
에서는 안 된다 — 기능은 되는데 관측이 조용히 빠지는, [[project_doc_drift_pattern]]과
같은 모양이다.

### 2-4. 🟡 서킷브레이커가 프로세스 3개를 하나로 합산한다

`ExerciseAnalysisService.java:107~111`의 기존 주석 — *"AI가 죽으면... 인스턴스 하나로
충분"* — 의 전제가 채널 풀 도입으로 깨졌다. 워커 3개가 사실상 독립 인스턴스인데
실패율은 여전히 하나로 합산된다. 워커 1개만 죽어도 멀쩡한 2개로 가는 호출까지 같이
막힐 수 있고, 반대로 1개가 죽어도 나머지 2개가 실패율을 희석해 서킷이 안 열릴 수도
있다.

### 2-5. 🟡 라우팅 키가 통일이 안 됐다

`extractReferenceData`(`:189`)만 `exerciseId`로 라우팅하고, `startAnalysis`·
`reattachAnalysis`·`stopAnalysis`(`:320`, `:407`, `:538`)는 `sessionId`로 라우팅한다.
참조 영상 추출이 세션 detector와 무관한 1회성 작업이라 의도적일 수 있으나, 확인된
적은 없다.

### 2-6. 🟢 워커 수(3)가 두 파일에 하드코딩 이원화

`Dockerfile`의 `--workers 3`과 `AI_CHANNEL_POOL_SIZE = 3`(`ExerciseAnalysisService.java:72`)이
서로 다른 파일에서 독립적으로 관리된다. 워커 수를 바꾸면 둘 다 손대야 하는데 강제하는
장치가 없다.

### 2-7. 🟢 워커 재시작 시 채널의 물리적 대상이 바뀔 수 있다

`ManagedChannel`이 재연결하면(워커 크래시·재배포) SO_REUSEPORT가 그때 다시 워커를
고른다. "채널 인덱스 K = 항상 같은 물리 프로세스"라는 전제가 재시작 이후엔 흔들릴 수
있다. `keepalive`/`idleTimeout` 설정은 양쪽 다 없어서(§4 확인함) **주기적 강제 재연결은
아니고, 워커 크래시·재배포 시에만** 발동하는 문제로 좁혀진다.

---

## 3. 전달 이력

세 묶음으로 init-77에 `SendMessage`로 전달했다(2026-08-26, 이 세션):

| # | 내용 | 상태 |
|---|---|---|
| 1 | §2-1 프레임 경로 미해결 | 🟡 전달됨, 회신 대기 |
| 2 | §2-4·§2-5·§2-6·§2-7 (서킷브레이커·라우팅 키·하드코딩·재연결) | 🟡 전달됨, 회신 대기 |
| 3 | §2-3 correlation ID 유실 | 🟡 전달 예정(이 문서와 같이) |

§2-2(메모리 오버부킹)는 아직 전달 안 했다 — 이 문서 작성과 동시에 정리한다.

---

## 4. 정직하게 비어 있는 것

- **§2-1·§2-2 둘 다 실측으로 재현한 적이 없다** — 코드를 읽고 추론했다. 실제 부하로
  nolease율·OOM 여부를 확인해야 확정된다
- **Spring의 실제 `@GrpcClient` 대체 실측**(session 1901~1907 → pid 7/8)은 init-77의
  것이고, 이 문서를 쓰는 이 세션은 그 원본 로그·커밋에 접근하지 못했다 — Dockerfile
  주석에서 인용했을 뿐이다
- **이 검토가 완전하다는 보장이 없다** — 진행 중인 작업을 스냅샷으로 본 것이라, 이
  문서 작성 이후 init-77이 이미 일부를 고쳤을 수 있다

---

## 결정 로그

- 2026-08-26: 문서 작성. init-77의 진행 중인 미커밋 작업(N=3 워커 + gRPC 채널 풀)을
  읽고 발견한 7개를 정리했다. 코드 변경 없음 — 순수 리뷰.
- 2026-08-27: **사용자 요청으로 §2-2(메모리 오버부킹) 재확인 — 이미 닫혀 있었다.** 코드
  확인 결과 `mediapipe_detector.py:memory_ceiling()`이 `per_worker_limit = limit /
  max(1, settings.AI_WORKER_COUNT)`로 이미 워커 수만큼 나눠 계산한다(`config.py:26-30`
  주석: "실측 워커 3개 × POSE_DETECTOR_POOL_SIZE=160 = 약 47.4GB 시도, 한도 20GB의
  2.4배 오버부킹"을 막으려고 추가). 커밋 `d73e99d`(2026-08-26 17:51, main에 병합됨,
  이 문서 작성 당일 저녁)의 커밋 메시지가 스스로 "다른 세션이 같은 날 남긴 리뷰(이
  문서)의 발견 중 이미 해소된 것(프레임 경로 라우팅 없음, 메모리 오버부킹, 워커 수
  이원화)은 위 작업으로 닫혔다"고 적어뒀다 — 이 문서의 §4가 우려한 "init-77이 이미
  일부를 고쳤을 수 있다"가 정확히 그대로 실현된 경우다.
  - 같은 커밋으로 **§2-1(프레임 경로 라우팅)·§2-6(워커 수 하드코딩 이원화)도 닫혔다**
    — `Dockerfile`은 이제 `--workers 3` 없이 `CMD ["/entrypoint.sh"]`뿐이고,
    `AI_WORKER_COUNT` 하나가 Java·Python 양쪽의 공통 소스다(`docker-compose.yml`).
  - **§2-5(라우팅 키 불일치)도 사실상 닫혔다** — 같은 커밋이
    `ExerciseAnalysisService.extractReferencePoses`에 "라우팅 키로 sessionId 가 아니라
    exerciseId 를 쓴다 — 실수가 아니다" 주석을 달아, §2-5가 "확인된 적 없다"고 적었던
    의도성을 명시적으로 확정했다.
  - **§2-4(서킷브레이커 워커 3개 합산)는 별도로 오늘(2026-08-27) 닫혔다** — 이슈 #556,
    커밋 `e1f5415`, [`circuit-breaker-worker-aggregation.md`](./circuit-breaker-worker-aggregation.md).
  - **남은 것은 §2-3(gRPC 제어 호출 cid 우회)·§2-7(워커 재시작 시 채널 물리 대상 drift)
    둘뿐이다.** 이 문서 상단의 🔴·🟡 표시는 이 갱신 이전 상태라 낡았다 — §2-1·§2-2·
    §2-5·§2-6은 취소선 없이 그대로 두되(되돌릴 때를 위한 기록), 이 로그가 최신 상태다.
  - 코드 변경 없음 — 순수 확인.
- 2026-08-27(후속): **사용자 요청으로 남은 둘(§2-3·§2-7)도 확인 — 전부 닫혀 있었다.**
  - **§2-3(cid 우회)**: 이슈 [#555](https://github.com/Shadowfit/init/issues/555)(CLOSED),
    커밋 `eea84cc`(2026-08-26 21:18, main). `ExerciseAnalysisService.java`에
    `GrpcCorrelationClientInterceptor`(`CORRELATION_INTERCEPTOR`)를 만들어
    `getAuthenticatedStub`/`getAuthenticatedBlockingStub` 양쪽에 인증 헤더와 함께
    붙였다 — §2-3이 "아무도 다시 안 붙였다"고 적은 바로 그 자리다.
  - **§2-7(워커 재시작 시 채널 물리 대상 drift)**: 코드 변경으로 닫힌 게 아니라 **전제
    자체가 최종 설계에서 사라졌다.** §2-7은 워커들이 **SO_REUSEPORT로 포트를 공유**한다는
    전제였는데, `entrypoint.sh`(첫 줄 주석)가 스스로 적어뒀듯 그 공유 포트 방식은
    §2-1(NO_LEASE 67%)을 잡으려고 **버려졌다** — 지금은 HTTP·gRPC 둘 다
    "base+워커인덱스"로 **워커마다 전용 포트**(`entrypoint.sh:22-27`)를 쓴다. 포트를 공유하지
    않으니 커널이 재연결 시 다른 프로세스로 돌릴 여지 자체가 없다 — 컨테이너가 재기동돼도
    (§2-4 실측대로 워커 1개 죽으면 셋 다 재기동) 채널 인덱스 K는 여전히 포트 `base+K`
    하나에만 연결되고, 그 포트에 새로 뜨는 프로세스가 곧 "워커 K"가 된다. **재현으로 확인한
    것은 아니고(포트 구조를 코드로 읽고 내린 결론), §2-1·§2-2 커밋들처럼 명시적으로
    "§2-7을 닫는다"고 적은 커밋은 없다** — 이 문서가 그 연결을 처음 짓는 것이다.
  - **이로써 이 문서(7개 발견) 전부 해소됨.** 새로 남는 것 없음 — 코드 변경 없음, 순수 확인.
- 2026-08-28: §2-2(메모리 오버부킹) 관련 **새 최적화 여지 하나 박제, 착수 안 함.** 지금 해법은
  워커 수만큼 한도를 "나눠서" 오버부킹을 막은 것이지, 워커마다 모델·검출기 풀을 각자
  로드해 **실제 메모리 사용량 자체가 N배**인 건 그대로다. `fork` + copy-on-write로 부모가
  모델을 한 번만 로드한 뒤 자식 워커를 fork해 물리 메모리를 공유하는 방법을 검토했으나,
  ⚠️ 파이썬 refcount가 읽기 전용 접근에도 메모리 페이지를 건드려 COW가 이론만큼 안 먹힐
  가능성이 있고(파이썬의 잘 알려진 COW 약점), MediaPipe가 C++ 객체를 파이썬으로 감싸는
  구조라 실제 공유율은 재봐야 안다 — **검증된 적 없음.** 착수 안 하는 이유: ① 24시간 soak
  관찰([[project_ai_worker_soak_running]])에서 OOM·재기동 0회로 **실제 문제였던 적이
  없다** ② DAU 1,000 가정에서 N=3으로 이미 필요 용량 충족(`ai-sticky-routing.md` §1-1)이라
  메모리를 줄여야 할 압박이 없다 ③ 효과가 불확실해 검증 자체에 새 라운드가 필요한데, 그
  비용 대비 ROI가 outbox #573·`ai-sticky-routing.md` §8 결정 같은 남은 축보다 낮다.
