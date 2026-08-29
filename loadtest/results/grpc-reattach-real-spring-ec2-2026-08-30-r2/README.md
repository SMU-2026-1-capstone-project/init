# 실제 Spring으로 재현 — 확인됨: 콜백 스레드는 안 쌓인다 (#614 검증)

측정일: 2026-08-30 (UTC 2026-08-29 16:15~16:21) · 박스: `c7i.4xlarge`(`i-0e435552a3bbc7a1f`,
16vCPU/30.8GB) 단일 인스턴스 · 스택: docker `mysql` + `shadowfit-backend`(진짜 Spring, gradle
빌드) + 호스트 venv AI 프로브(`BACKEND_GRPC_ADDRESS=127.0.0.1:6565`로 실제 Spring을 바라봄) ·
커밋: `c83de66`(main) · 부하: `--cycle --concurrencies 100 --repeats 60`(동시성 100, 60판,
~90초) · 계측: `pidstat -t` + `py-spy dump`(5회) · 관련:
[`../grpc-reattach-thread-cpu-ec2-2026-08-30/`](../grpc-reattach-thread-cpu-ec2-2026-08-30/README.md)
(Spring 없는 판) · 이슈 [#614](https://github.com/Shadowfit/init/issues/614)

---

## 0. 한 줄

**확인됐다 — Spring이 살아있고 빠르게 응답하면 콜백 스레드가 안 쌓인다.** 동시성 100 ·
60판 · 6000건 전부 성공(에러 0, TRANSIENT_FAILURE 0). `_send_complete_analysis` 스레드
수는 1~61 사이에서 **오르내렸을 뿐 계속 늘지 않았다**(#614의 Spring-부재 판은
453→464→487로 꾸준히 늘었다). 다만 콜백 자체는 전부 **`NOT_FOUND`로 거절**됐다(우리
합성 session_id가 Spring에 없어서) — "성공"이 아니라 "빠른 거절"이 스레드를 안 쌓이게
한 것이다.

---

## 1. 결과

| 시도 | 박스 | 결과 |
|---|---|---|
| 1차 | `c7i.2xlarge`(15.7GB) | `docker compose build`가 buildx 0.17+ 요구로 실패 → 수정 |
| 2차(같은 2xlarge) | 15.7GB | 빌드·기동 성공, 부하 시작 ~15초 만에 **AI 프로세스 자체가 죽음**(py-spy: "process not found"). `dmesg`는 못 걷음(이 시도는 스크립트에 그 단계가 없었다) — MySQL+Spring+AI가 15.7GB 박스에서 동거하다 메모리 압박으로 죽은 것으로 추정, 미확정 |
| 3차(`c7i.4xlarge`, 30.8GB) | 30.8GB | **깨끗하게 완주** — 6000/6000 성공, dmesg에 OOM 없음 |

이 문서는 **3차(깨끗한 판)** 을 기준으로 서술한다. 1·2차 원본은
[`../grpc-reattach-real-spring-ec2-2026-08-30/`](../grpc-reattach-real-spring-ec2-2026-08-30/)에
남겨뒀다(🔴 이 시도는 실패해서 `grpc_*.json`·AI 서버 로그를 못 건졌다 — 스크립트가 그 복사
줄을 빠뜨렸다, 3차에서 고침).

### 3차 스윕 요약

```
동시성 100 · 6000건 · 평균 505.20ms · p50 504.43ms · p95 894.11ms · 최대 1344.15ms
success 6000/6000 · 에러 0 · 채널 상태 전환 2회(TRANSIENT_FAILURE 0회)
```

원본: [`grpc_threadpool_sizing_reattach_result.json`](./grpc_threadpool_sizing_reattach_result.json) ·
[`grpc_channel_state_log.json`](./grpc_channel_state_log.json) ·
[`grpc_call_log.json`](./grpc_call_log.json) · [`ai_server.log`](./ai_server.log)(9.1MB) ·
[`pidstat_threads.txt`](./pidstat_threads.txt)(15.3MB) · [`pyspy_dumps.txt`](./pyspy_dumps.txt)

### 콜백 스레드 수 — 시간에 따라

| py-spy 덤프 | 총 스레드 | `_send_complete_analysis` 스레드 |
|---|--:|--:|
| 1 (t=0s) | 14 | 1 |
| 2 (t=15s) | 68 | 55 |
| 3 (t=30s) | 39 | 26 |
| 4 (t=45s) | 74 | 61 |
| 5 (t=60s) | 65 | 52 |

**오르내린다 — 단조 증가가 아니다.** #614의 Spring-부재 판(453→464→487, 계속 증가)과
질적으로 다르다.

### 왜 콜백이 빨리 끝났나 — `NOT_FOUND` 즉시 거절

`ai_server.log`를 세어보면 세션마다(800000001~) **각 61회, 전부 동일 메시지**:

```
콜백 거절 (session=800000001, code=StatusCode.NOT_FOUND) — 재시도하지 않는다:
SESSION_NOT_FOUND: 진행 중인 운동 세션을 찾을 수 없습니다.
```

`report_complete_analysis`(`spring_client.py`)는 `NOT_FOUND`를 **재시도 불가로 판정하고
즉시 반환한다**(`_is_retryable` — 코드 주석: "영구 실패다 … 더 던져도 결과가 같고, 그동안
이 워커가 붙잡힌다"). 그래서 스레드가 **최대 15초(3회×5초)가 아니라 즉시** 끝난다.

🔴 **DB에는 아무것도 안 쌓였다** — `exercise_sessions` 0건, `reports` 0건
([`db_counts.txt`](./db_counts.txt)). 이건 버그가 아니라 이 프로브의 한계다: 합성
`ReattachAnalysis`가 Spring의 정상 `StartAnalysis` 흐름을 거치지 않아 Spring이 그
session_id를 모른다.

---

## 2. #614 판정 — 검증 완료

| 물음 | 답 |
|---|---|
| Spring이 정상이면 콜백 스레드가 안 쌓이는가 | 🟢 **그렇다 — 확인됨.** 1~61 사이 등락, 증가 추세 없음 |
| 그 이유가 "성공이 빨라서"인가 "실패가 빨라서"인가 | 🟡 **이 판에선 "실패(NOT_FOUND)가 빨라서"다.** 진짜 성공 경로(세션이 실재해서 커밋까지 가는 경우)의 지연은 이 판이 안 쟀다 — 다만 실패든 성공이든 **"Spring이 응답한다"는 것 자체가 핵심**이지 응답의 성패는 부차적으로 보인다 |
| #614의 위험(Spring 저하 시 스레드 폭증)이 여전히 유효한가 | 🟢 **유효하다.** 이 판은 "Spring이 정상일 때는 안전하다"를 확인했을 뿐, "Spring이 느려지면 위험하다"는 원래 판(#614 결과)이 이미 보여줬다. 둘을 합치면 위험 조건이 **"Spring 자체"가 아니라 "Spring의 응답 지연/불가용"으로 좁혀진다** |

## 3. 이 판의 한계

- **성공 경로(진짜 세션 존재 → DB 커밋)의 콜백 지연은 안 쟀다** — 전부 `NOT_FOUND`
  즉시 거절이었다. 성공 경로가 이보다 느릴 수 있다(DB 쓰기 비용).
- **1회 관측**(1·2차는 실패, 3차만 유효) — 재현성은 이 3차 하나로만 본다.
- **Spring을 "정상"으로만 봤다** — "약간 느려진 Spring"(예: GC 정지 수백ms) 같은 중간
  조건은 안 쟀다. #614가 본 "Spring 부재"(항상 타임아웃)와 이 판의 "Spring 정상"(항상
  즉답) 사이의 스펙트럼은 비어 있다.

## 4. 다음 (결정 안 함)

- 진짜 세션을 만들어(Spring의 StartAnalysis 흐름을 태워) CompleteAnalysis가 실제
  DB 커밋까지 가는 성공 경로의 지연을 재보기
- Spring에 인위적 지연(예: 사이드카로 응답 지연 주입)을 걸어 "약간 느려진 Spring"
  스펙트럼에서 스레드 수가 어떻게 변하는지 보기
