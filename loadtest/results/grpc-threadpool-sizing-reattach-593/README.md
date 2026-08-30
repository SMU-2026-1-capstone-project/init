# ReattachAnalysis 동시성 스윕 — 로컬 1판, 원인 하나를 찾음

측정일: 2026-08-28
박스: 로컬 개발 머신 — Intel i3-6100 (물리 2코어 / 논리 4), Windows
스택: `ai-server` venv python, uvicorn+gRPC 서버 1프로세스, 서버 코드 미변경
(`max_workers=10`, `DetectorPool` 락 구조 그대로)
스크립트: [`../../measure_grpc_threadpool_sizing_reattach.py`](../../measure_grpc_threadpool_sizing_reattach.py)
선행: [`../grpc-threadpool-sizing-593/README.md`](../grpc-threadpool-sizing-593/README.md)
(StopAnalysis 판 — 이 판이 그 갭을 메운다)
연관: 이슈 [#593](https://github.com/Shadowfit/init/issues/593), [#581](https://github.com/Shadowfit/init/issues/581)

---

## 0. 한 줄

**"max_workers=10"보다 더 앞에 있는 병목을 찾았다.** `DetectorPool.acquire()`
(`app/core/mediapipe_detector.py:227-234`)가 세션 전체가 공유하는 **단일 락을 쥔 채로**
`PoseDetector()`(MediaPipe 그래프 초기화, 가벼운 작업이 아님)를 생성한다. 그 결과 동시
세션 시작·재부착은 gRPC 스레드가 몇 개 남아돌든 상관없이 **이 락 하나에서 줄을 선다.**
`StartAnalysis`도 같은 코드 경로다(`exercise_servicer.py:163`) — 재부착뿐 아니라 **평상시
세션 시작 전체**가 이 락의 영향권 안에 있다.

---

## 1. 결과

`ReattachAnalysis`(매번 새 session_id, 성공 경로 — 33개 랜드마크 합성 기준좌표로
`extract_angles`·`DetectorPool.acquire`·`registry.create_if_absent`를 실제로 통과시킴)를
동시성별로 쏜 왕복 지연(ms). 버림판 1 + 3판, 판마다 순서를 뒤집음:

| 동시성 | n | 평균ms | p50 | p95 | 최대ms | success |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 3 | 229.80 | 201.29 | 383.98 | 383.98 | 3/3 |
| 5 | 15 | 355.28 | 449.25 | 494.06 | 494.06 | 15/15 |
| 8 | 24 | 295.16 | 345.61 | 435.75 | 435.85 | 24/24 |
| 10 | 30 | 475.33 | 547.38 | 709.04 | 709.05 | 30/30 |
| **12** | 36 | 402.34 | 364.72 | 703.54 | 703.54 | 36/36 |
| 15 | 45 | 555.60 | 575.07 | 722.47 | 722.96 | 45/45 |
| 20 | 60 | 534.35 | 502.91 | 913.22 | 913.24 | 60/60 |
| 30 | 90 | 991.59 | 916.24 | 1921.96 | 1922.25 | 90/90 |
| 50 | 150 | 1320.16 | 1401.53 | 2274.42 | 2364.04 | 150/150 |

원본: [`result.json`](./result.json)

---

## 2. 읽는 법

- **성공률 100% — 전 구간.** StopAnalysis 판과 마찬가지로 타임아웃(10s)·드롭은 없었다.
  `POSE_DETECTOR_POOL_SIZE`를 넉넉히(4503) 잡아서 풀 고갈도 안 섞였다.
- **절대값이 StopAnalysis 판과 자릿수가 다르다.** 동시성 1의 기준선부터 **201ms**
  (StopAnalysis 판은 1.81ms) — `PoseDetector()` 생성이 dict 연산과 비교가 안 될 만큼
  비싸다는 뜻. 이게 재부착이 "무거운 RPC"인 이유다.
- **동시성 8·12·20에서 값이 앞뒤 동시성보다 낮게 나오는 비단조 구간이 있다** — 판 3개짜리
  단일 로컬 관측이라 잡음 폭이 크다(p95 대비 최대값 배율이 StopAnalysis 판보다 훨씬
  들쭉날쭉함). **곡선의 정확한 모양(선형이냐 계단이냐)을 이 데이터로 못 박지는 않는다** —
  다만 방향(늘수록 확실히 느려진다, 10 근처에서 유독 꺾이는 증거는 없다)은 StopAnalysis
  판과 같은 그림이다.
- **동시성 50에서 p50이 기준의 약 7배(201→1401ms)** — 완전 직렬(50배)도 완전 병렬(1배 근처
  유지)도 아닌 중간. 락 경합 + GIL + 실제 model init 비용이 섞인 결과로 읽는다.

## 3. 왜 이렇게 느린가 — 코드로 확인한 메커니즘

```python
# app/core/mediapipe_detector.py:227-234 (DetectorPool.acquire)
def acquire(self, session_id: int) -> bool:
    with self._guard:                      # 🔴 세션 전체가 공유하는 단일 threading.Lock
        if session_id in self._detectors:
            return True
        if len(self._detectors) >= self._capacity:
            return False
        self._detectors[session_id] = PoseDetector()   # 🔴 이게 락 "안"에서 돈다
        self._locks[session_id] = threading.Lock()
        return True
```

`PoseDetector()`(`mp_pose.Pose(...)` 생성)는 MediaPipe 그래프를 초기화하는 작업이다 —
dict 연산과 달리 비파이썬(C++) 초기화 비용이 실제로 있다. 이게 **`self._guard` 락 안에서**
실행되므로, 세션 A의 검출기를 만드는 동안 세션 B·C·...는 (10개든 몇 개든) gRPC 스레드가
비어 있어도 이 락 앞에서 대기한다. **스레드풀 크기(`max_workers`)는 이 병목과 무관하다** —
스레드가 100개여도 락은 하나뿐이다.

`StartAnalysis`도 같은 `get_pool().acquire()`를 부른다(`exercise_servicer.py:163`) — 즉 이
직렬화는 재부착 특수 시나리오(#581)에만 있는 게 아니라 **모든 세션 시작의 공통 경로**다.

---

## 4. 판정 — #593이 원래 물은 것에 대한 답

| 물음 | 답 |
|---|---|
| `max_workers=10`이 재부착 폭주의 병목인가 | 🔴 **아마 아니다 — 더 앞단의 병목을 찾았다.** `DetectorPool._guard`가 스레드풀보다 먼저 걸린다 |
| #581(서킷브레이커 자동 재부착) 시 타임아웃 나는가 | 이 판(로컬, 최대 50 동시)에서는 안 났다. 다만 지연이 7배까지 늘어나므로, Spring 쪽 gRPC 데드라인이 더 짧으면(이 판은 10s로 넉넉히 줬다) 거기서 먼저 끊길 수 있다 — **Spring이 실제로 쓰는 데드라인 값과 비교가 안 됐다** |
| 이건 새 문제인가, #593의 답인가 | **둘 다 아니고 셋째다.** `max_workers=10`의 적정성 질문(#593)은 여전히 안 닫혔다 — 이 락이 먼저 걸리는 바람에 스레드풀 자체가 시험대에 오르지도 못했을 가능성이 있다(락 대기 중에는 스레드를 안 쓴다는 보장이 없다 — 스레드는 잡은 채로 락을 기다린다). 별도 이슈로 등록한다 |

---

## 5. 이 판의 한계

- 로컬 1대, 판 3개 — StopAnalysis 판과 같은 한계.
- **원인을 코드 읽기로 확인했지만, 락 대기 시간 자체를 별도로 계측하진 않았다** — "느려진
  전체 시간 중 락 대기가 몇 %"는 모른다. 스레드별 CPU 분해(`per-process-ceiling-cause.md`
  축 1)나 락에 타임스탬프를 심는 계측이 다음 단계다.
- Spring이 실제로 쓰는 gRPC 데드라인 값과 비교 안 됨(§4 표에 적어둠).
- `PoseDetector()` 생성 비용 자체(락 없이 순수 생성만 쟀을 때 몇 ms인지)를 따로 안 쟀다 —
  "느려진 게 락 경합 때문"이라는 것과 "얼마나 락 경합 때문"이라는 것은 다른 질문이다.

## 6. 다음

- 새 이슈로 등록(락이 max_workers=10보다 앞선 병목이라는 것) — `docs/decisions/`에도
  분석 문서를 올릴지는 사용자 판단
- 락 대기 시간 자체를 계측(예: `_guard.acquire()` 전후 타임스탬프를 임시로 찍어 락 대기
  vs `PoseDetector()` 순수 생성 시간을 분리)
- Spring의 실제 gRPC 데드라인 값 확인 후 이 표와 대조
