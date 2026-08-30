# 처리량 천장(~90~100/s) 원인 조사 — GIL이 아니라 "완료 콜백 스레드 폭증"이었다

측정일: 2026-08-30 (UTC 2026-08-29 15:47~15:50) · 박스: `c7i.4xlarge`(`i-045eb2ccf691a3dfc`,
16vCPU/30.8GB) 단일 인스턴스, 서버·클라이언트 동거(스레드 CPU 계측이 목적이라 분리 불필요) ·
커밋: `c83de66`(main, PR #601 머지 후) · 부하: `--cycle --concurrencies 100 --repeats 60`
(동시성 100 지속 60판, ~90초) · 계측: `pidstat -t`(스레드별 CPU, 1초 간격 90회) + `py-spy dump`
(5회, 15초 간격) · 관련: [`../grpc-max-workers-cmp-ec2-2026-08-30/`](../grpc-max-workers-cmp-ec2-2026-08-30/README.md)
§4("처리량 천장의 정체 — 미확정")의 후속

---

## 0. 한 줄

**GIL이 CPU 작업을 직렬화하는 그림이 아니었다.** py-spy로 잡아보니 **gRPC 워커 스레드
10개 중 대부분이 CPU 작업이 아니라 `logging` 모듈의 전역 락(lock) 대기에 걸려 있었다.**
그 락이 왜 붐비냐 — `StopAnalysis`가 호출될 때마다 **결과를 Spring에 보고하는 새
`threading.Thread`를 매번 만드는데**(`exercise_servicer.py:393`), 이 프로브 환경엔 Spring이
없어서 그 스레드들이 **각 최대 15초(3회 재시도 × 5초)씩 못 끝나고 쌓인다.** 90초 만에
살아있는 콜백 스레드가 450개를 넘었다 — 이 스레드 폭증이 로깅 락 경합을 만들고, 그게
gRPC 워커들을 묶어놓는다.

---

## 1. 증거 — `pidstat -t`가 보여준 것

프로세스 전체 CPU는 지속적으로 **800~950%**(8~9.5 vCPU, 16개 중)를 썼다. 그런데 그 CPU가
**어디서** 쓰였는지 스레드별로 뜯어보면:

- gRPC 워커 스레드(`ThreadPoolExecutor-0_0`~`_9`, max_workers=10) 각각은 **개별로는 CPU를
  거의 안 쓴다**(초당 5~9% 수준) — 바쁜 게 아니라 **락을 기다리며 앉아 있다.**
- 대신 **`mediapipe/<PID>`라는 이름의 순수 native 스레드가 수십~수백 개** 짧게 떴다 사라진다
  (예: 15:49:00 한 스냅샷에서 활성 30개 이상, PID가 계속 새로 발급됨) — `PoseDetector()`
  생성(MediaPipe 그래프 초기화)이 내부적으로 자기 스레드풀을 새로 띄운다는 뜻이다. 이
  스레드들이 CPU 800%대의 대부분을 설명한다 — **진짜 병렬 작업**이지 GIL에 안 걸린다.

원본: [`pidstat_threads.txt`](./pidstat_threads.txt)(8.3MB, 90초 전체) ·
[`pyspy_dumps.txt`](./pyspy_dumps.txt)(508KB, 5개 스냅샷)

---

## 2. 증거 — `py-spy dump`가 보여준 것 (더 결정적이다)

5번의 스냅샷(dump 2~4, 매 15초) 중 **gRPC 워커 10개 중 9개꼴로 이 스택에 멈춰 있었다**:

```
acquire (logging/__init__.py:973)
handle (logging/__init__.py:1026)
callHandlers (logging/__init__.py:1762)
handle (logging/__init__.py:1700)
_log (logging/__init__.py:1684)
info (logging/__init__.py:1539)
ReattachAnalysis (app/grpc/exercise_servicer.py:214)   # 또는 StopAnalysis:308
```

**`logging.Handler.acquire()`는 파이썬 표준 로깅의 전역 락이다** — 모든 스레드가 로그
한 줄을 쓸 때마다 이 락을 잡는다. 워커가 CPU 작업이 아니라 **이 락을 기다리며 멈춰
있었다**는 게 스택으로 직접 보인다.

그리고 같은 스냅샷에서 `_send_complete_analysis`라는 이름의 스레드가:

| 시각 | 살아있는 콜백 스레드 수 |
|---|---:|
| 15:49:07 | 453 |
| 15:49:22 | 464 |
| 15:49:37 | 487 |

이 스레드 453개 중 **447개(99%)가 `report_complete_analysis`(Spring으로의 gRPC 콜백)의
블로킹 호출 안에 멈춰 있었다** — `grpc/_channel.py`의 `_blocking`/`__call__` 프레임.

### 원인 코드

```python
# app/grpc/exercise_servicer.py:392-395 (StopAnalysis)
threading.Thread(
    target=correlation_wrap(_send_complete_analysis),
    args=(state,),
    daemon=True,
).start()
```

**`StopAnalysis`가 호출될 때마다 새 OS 스레드를 만든다** — 풀도 없고 상한도 없다.
`report_complete_analysis`(`spring_client.py:182`)는 실패 시 **최대 3회 재시도**
(`_COMPLETE_MAX_ATTEMPTS=3`), 매 시도 `BACKEND_GRPC_TIMEOUT_SECONDS=5.0`초 데드라인 —
**한 스레드가 최대 15초+백오프를 붙잡고 안 죽는다.**

---

## 3. 왜 지금 이렇게 심하게 보였나 — 이 판의 조건

🔴 **이 EC2 박스엔 Spring이 없다**(`BACKEND_GRPC_ADDRESS=shadowfit-backend:6565`가 안
뜬다). 그래서 모든 콜백이 **매번 재시도 끝까지 실패**하고, 스레드가 최대치(15초+)를
전부 채운다. 게다가 이 프로브는 `--cycle`로 **`StopAnalysis`를 초당 수십~백 번** 부르므로
(동시성 100 × 60판), **스레드가 죽는 속도보다 훨씬 빠르게 새로 생긴다.**

**실제 프로덕션에서는 다르게 보일 것이다** — Spring이 정상이면 콜백이 대개 빨리
끝나(p95 213ms, `r276-backoff-sweep-aws-2026-08-23` 인용) 스레드가 오래 안 산다. 이 판이
드러낸 건 **"평소엔 안 보이는 설계"**다 — Spring이 느려지거나(재배포·GC·과부하) 세션
완료가 몰리는 조합이 오면, 지금 이 판과 똑같은 그림(스레드 수백 개 → 로깅 락 경합 →
gRPC 워커 전체가 묶임)이 프로덕션에서도 날 수 있다.

---

## 4. #593(max_workers 적정성)과의 관계 — 재해석

`grpc-max-workers-cmp-ec2-2026-08-30`가 "max_workers 5~40이 처리량에 거의 차이가 없다"고
봤던 이유가 이제 설명된다 — **병목이 `max_workers`풀 안이 아니라 그 풀 밖(로깅 락)에
있었으니, 풀 크기를 아무리 키워도 안 바뀐 것이다.** 스레드를 10개 주든 40개 주든, 다들
같은 전역 락 앞에 줄을 서는 건 똑같다.

## 5. 이 판의 한계

- **1회 관측.** 재현성(매번 이 정도로 쌓이는지)은 확인 안 됨.
- **Spring이 없는 조건**이라 콜백 스레드 수명이 실제보다 훨씬 길다 — 실제 스레드
  누적 속도는 Spring 상태에 달려 있고 이 판은 그 축을 안 쟀다(Spring 정상 vs 저하
  둘 다 안 봄).
- **로깅 락이 처리량 천장의 "유일한" 원인인지, mediapipe native 스레드 경합과
  섞여 있는지 정량 분리 안 됨** — py-spy 스냅샷 5장은 강한 정황증거지 전수 조사가
  아니다.
- 이 스레드-폭증 설계 자체가 결함인지("StopAnalysis마다 새 스레드"가 의도인지)는
  코드 주석상 **의도된 설계**로 보인다(콜백이 응답을 안 막으려고) — 다만 **상한이 없다는
  것**이 이 판의 핵심 지적이다.

## 6. 다음 (결정 안 함 — 후보)

- 콜백 스레드에 풀(bounded `ThreadPoolExecutor`)이나 개수 상한을 두는 방안 검토
- Spring을 정상 상태로 둔 채 같은 부하로 재현 — 실제 조건에서도 스레드가 유의미하게
  쌓이는지 확인
- 로깅 락 경합 자체를 없애는 방법(예: `QueueHandler`로 로그를 비동기화)도 후보
