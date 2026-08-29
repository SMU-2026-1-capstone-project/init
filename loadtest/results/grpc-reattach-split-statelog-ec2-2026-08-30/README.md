# ReattachAnalysis — 채널 상태 계측으로 원인 확정: OOM-kill (#613 해결)

측정일: 2026-08-30 (UTC 2026-08-29 15:05~15:07, 실측 스윕 자체는 4초 안에 끝났다) · 대상:
`c7i.4xlarge`(`i-0b7d4e5e93166dd2f`, 16vCPU/30.8GB, `--serve-only --pool-size 5000`) · 부하기:
`c7i.xlarge`(`i-091fe4580e566969c`, `--target-host`) · 커밋:
[`713a07a`](https://github.com/Shadowfit/init/commit/713a07aa200487dc341625ae923ec954a409f2aa)
(이 판을 위해 채널 상태·개별 호출 로깅 추가) · 앞선 두 판 대조:
[`../grpc-threadpool-sizing-ec2-2026-08-29/`](../grpc-threadpool-sizing-ec2-2026-08-29/README.md)(동거) ·
[`../grpc-reattach-split-ec2-2026-08-29/`](../grpc-reattach-split-ec2-2026-08-29/README.md)(분리, 계측 전)
· 관련 이슈: [#613](https://github.com/Shadowfit/init/issues/613)

---

## 0. 한 줄

**미스터리가 풀렸다 — 채널 불안정도 서버 행(hang)도 아니고, 서버 프로세스가 OOM-kill
당했다.** 커널이 `python`(PID 30450)을 **15:06:55 UTC**에 강제 종료했고, 클라이언트
채널은 **1.3초 뒤(15:06:56.314)** `TRANSIENT_FAILURE`로 전환됐다 — 그 뒤 모든 RPC가
서버 없는 채널에 즉시 실패(`UNAVAILABLE`)한 것뿐이다. `success ≈ 그 동시성의 1배치만`
이었던 이전 두 판의 패턴도 이걸로 설명된다 — **크래시 이전 배치는 성공, 그 이후는
전부 실패.**

---

## 1. 결정적 증거 — 타임라인

| 시각(UTC) | 사건 | 출처 |
|---|---|---|
| 15:06:52.000 | 채널 READY(첫 연결) | [`grpc_channel_state_log.json`](./grpc_channel_state_log.json) |
| 15:06:55 | **커널이 OOM-killer로 PID 30450(python) 강제종료** — `anon-rss:31471508kB`(31.47GB) | 대상 박스 `dmesg`/`journalctl -k`(SSH로 직접 확인, 이 판엔 로그 파일로 안 남음 — §4 한계 참고) |
| 15:06:56.314 | 채널 `IDLE → CONNECTING → TRANSIENT_FAILURE` | [`grpc_channel_state_log.json`](./grpc_channel_state_log.json) |
| 이후 전 구간 | 거의 모든 RPC가 즉시 `rpcerror:UNAVAILABLE` | [`grpc_call_log.json`](./grpc_call_log.json) · [`grpc_threadpool_sizing_reattach_result.json`](./grpc_threadpool_sizing_reattach_result.json) |

**OOM-kill(15:06:55) → 채널 TRANSIENT_FAILURE(15:06:56.314)까지 1.3초.** 인과관계로 보기에
충분히 가깝다 — grpc 클라이언트가 서버와의 TCP 연결이 끊긴 것을 감지하는 데 걸리는
시간이다.

---

## 2. 왜 OOM이 났나 — 검출기 메모리를 1000배 낮게 잡았다

서버 로그(`target_ai.log`)에 크래시 전까지 **`ReattachAnalysis 수신` 301건**이 찍혔다.
`acquire()`는 매 새 `session_id`마다 `PoseDetector()`(MediaPipe 그래프)를 **하나씩 영구히**
만든다(반납 없음 — 이 프로브의 의도된 설계, §"검출기 풀 크기를 크게 잡은 이유" 참고).

```
31.47GB(OOM 시점 anon-rss) ÷ 301개 ≈ 104.5MB/개
```

`measure_grpc_threadpool_sizing_reattach.py`의 원래 docstring은 "메모리는 0.1MB뿐"이라고
적어뒀었다 — **1000배 틀렸다.** 반면 `loadtest/aws/bootstrap.sh`가 딴 자리(ai-venv 준비
단계)에서 이미 실측해 둔 값은 "검출기 98.7MB/개"였다 — 이번 실측(104.5MB)과 자릿수가
맞는다. **이 스크립트를 쓴 사람(나)이 이미 저장소에 있던 실측값을 안 보고 새로 잘못된
가정을 박아넣은 것**이 근본 원인이다. 스크립트 docstring은 이 판 이후 정정했다
(커밋 참고).

`POSE_DETECTOR_POOL_SIZE`를 이 판에서 5000(대상)/4805(이전 동거 판)으로 "넉넉히" 잡은
것도 이 틀린 가정 위에 있었다 — 실제로는 **누적 세션 수 × 100MB가 박스 RAM(30.8GB)을
넘는 순간(≈300개) 서버가 죽는다.** pool_size라는 상한 자체는 무의미했다 — 애초에
도달하기 훨씬 전에 OOM이 먼저 왔다.

---

## 3. 이전 두 판 재해석

| 판 | 관측 | 지금 보면 |
|---|---|---|
| 동거(2026-08-29, 로컬↔EC2 아님, 서버·클라 한 프로세스) | 동시성 15에서 21분(1,274,791ms) 지연, 서버 로그 294초 무로그 | **OOM-kill 후 클라이언트 프로세스 자체도 같은 박스에서 메모리 회수·스와핑 등으로 오래 멎었을 가능성** — 클라도 메모리 압박을 같이 받는 동거 조건이라 지연이 훨씬 길었을 수 있다(미검증) |
| 분리(2026-08-29, 채널 계측 전) | success≈1배치/레벨, 나머지 즉시 UNAVAILABLE, 최대 지연 1.2초 | **이 판과 똑같은 OOM-kill 패턴.** 클라이언트가 분리돼 있어 서버만 죽고 클라는 멀쩡히 즉시 실패를 관측했다 — 그래서 지연이 짧았다 |

즉 "동거가 원인이 아니다"(앞선 판의 결론)는 여전히 맞지만, 그 이유가 이제는 다르게
읽힌다 — **동거든 분리든 서버가 똑같이 OOM으로 죽었고, 동거 조건에서만 클라이언트도
덩달아 자원 압박을 받아 지연이 극단적으로 길어졌을 뿐**이다.

---

## 4. 이 판의 한계

- **OOM 이벤트 자체는 이 판의 산출물(JSON/로그)에 안 남았다** — `dmesg`/`journalctl`을
  SSH로 직접 읽어 확인했고 이 README에 인용했지만, 원본을 파일로 커밋해두지 않았다(인스턴스가
  이미 terminate됨 — 재수집 불가). 다음부턴 `target` user-data가 종료 전에 `dmesg`를
  S3로 같이 올리게 할 것.
- **왜 EC2(16vCPU)에서 104.5MB/개인지, 다른 박스(로컬 등)에서도 같은 값인지 미확인** —
  MediaPipe/TFLite 내부 스레드풀이 vCPU 수에 비례해 detector당 메모리를 더 쓰는지는
  가설일 뿐, 확인 안 했다.
- **`py-spy`로 크래시 순간의 스택을 잡으려 했으나 실패했다** — 설치·기동에 걸린 SSH
  왕복 시간이 스윕 자체 소요(4초)보다 길어서 놓쳤다. 굳이 필요 없다 — OOM 원인은
  이미 dmesg로 확정됐다.

## 5. #613 판정 — 닫는다

| 갈래 | 답 |
|---|---|
| 채널이 TRANSIENT_FAILURE로 플래핑하는가 | 🟢 **그렇다 — 딱 1번, 확정적 원인(OOM-kill)과 함께.** "플래핑"이 아니라 서버가 죽은 뒤 계속 그 상태였을 뿐 |
| 원인이 자원 경합/행(hang)인가 | 🔴 **아니다 — OOM-kill이다.** 스레드 경합·GIL 등 이전 가설은 기각 |
| 동거가 원인인가 | 🔴 **아니다(재확인)** — 동거·분리 둘 다 같은 OOM을 겪었다. 동거는 지연의 *크기*만 키웠다(가설) |
| 근본 원인이 뭔가 | 🟢 **이 프로브 스크립트의 메모리 가정이 1000배 틀렸다**(0.1MB vs 실측 104.5MB) — 정정 완료 |

**결론: 이건 실제 프로덕션 결함이 아니라 이 측정 스크립트 자신의 설계 결함이었다** —
반납 없이 세션마다 실제 검출기를 영구히 쌓는 프로브를, 검출기가 "거의 공짜"라는 틀린
가정으로 지나치게 오래(300+ 세션) 돌린 것. `DetectorPool` 자체나 `max_workers=10`의
적정성 질문(#593 원래 목적)은 **이 판으로도 여전히 답이 안 났다** — 그 질문에 답하려면
검출기를 반납하며 도는(풀 안에서 회전하는) 스윕이 필요하다.
