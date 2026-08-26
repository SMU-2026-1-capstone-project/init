# tcpdump 재현 — §7.7("에러 100건") 의 패킷 레벨 증거

작성: 2026-08-26
연관: [`../../../docs/decisions/load-test-strategy.md`](../../../docs/decisions/load-test-strategy.md) §7.7,
[`../../../docs/decisions/production-signal-checklist.md`](../../../docs/decisions/production-signal-checklist.md) §2-4

## 0. 왜 이 판인가

`production-signal-checklist.md` §2-4가 tcpdump 적용을 "§7.7 재현 시나리오에 한정해서만"으로 스코프해뒀다 —
새 장애를 지어내지 않고, 이미 애플리케이션 레벨(ghz 타임스탬프)로만 규명됐던 §7.7("측정 종료 시 in-flight
강제 종료, 서버 결함 아님")을 **TCP 패킷 레벨 증거로 보강**하는 것이 유일한 목적이다.

## 1. 격리

동시 세션이 `shadowfit-backend`를 쓰고 있을 가능성이 있어([[project_concurrent_sessions]]), 별도 compose
프로젝트(`tcpdump-repro`, 포트 13306/16565/18080/19090)로 mysql+backend만 격리 기동 — 기존 스택은
전혀 건드리지 않았다. 실험 종료 후 컨테이너·볼륨·네트워크 전부 제거.

## 2. 방법

- ghz `SavePoseDataBatch` 고정 동시성 부하: `-c 50 -z 6s`(§7.7의 ramp 대신 짧은 고정 동시성 — 재현에
  필요한 최소 조건만 사용)
- 캡처: `nicolaka/netshoot`를 `--network container:tcpdump-repro-backend`로 backend 컨테이너의
  네트워크 네임스페이스에 붙여 `tcpdump -i eth0 'tcp port 6565 and (tcp[tcpflags] & (tcp-syn|tcp-fin|tcp-rst) != 0)'`
  — SYN/FIN/RST만 필터링(전체 페이로드 캡처 불필요)

## 3. 결과

ghz JSON 리포트:
```
count: 202, statusCodeDistribution: {OK: 152, Unavailable: 50}
errorDistribution: {"rpc error: code = Unavailable desc = error reading from server:
  read tcp [::1]:54811->[::1]:16565: use of closed network connection": 50}
```
→ **에러 수(50) = 동시성(c=50)** — §7.7("정확히 100건 = `--concurrency-end=100`")과 같은 패턴.
50건 전부 23:52:58.827~828(KST) 1ms 창 안에 몰림 — §7.7의 "종료 직전 ~1~2ms 내 전부"와 동일.

tcpdump (UTC, KST-9h):
```
14:52:52.858032  SYN/SYN-ACK — 이 연결이 위 50건을 처리
14:52:58.807378  IP client(ghz) > backend:6565  Flags [F.]   ← 클라이언트가 먼저 FIN
14:52:59.101315  IP client(ghz) > backend:6565  Flags [R]    ← 294ms 후 클라이언트가 RST
```

**클라이언트(ghz) 발 FIN이 14:52:58.807(UTC) = 23:52:58.807(KST)** — ghz가 보고한 에러 클러스터
(23:52:58.827~828)보다 정확히 ~20ms *먼저* 찍힌다. 즉 순서는:

1. ghz의 `-z 6s` 타이머 만료 → **클라이언트가 먼저 TCP FIN을 보낸다**
2. 그 커넥션 위에 있던 in-flight 스트림들이 취소되고, ghz가 그 취소를 "Unavailable / use of closed
   network connection"으로 ~20ms 뒤 집계한다
3. 294ms 후 클라이언트가 RST로 마무리

**서버(backend:6565)는 이 종료 시퀀스에서 FIN/RST를 먼저 보내지 않는다** — 캡처 전체에서 서버발
FIN·RST가 한 건도 없다(SYN-ACK 이후 서버는 침묵). §7.7이 텍스트로만 결론 냈던 "부하 한계가 아니라
측정 종료 아티팩트, 서버 결함 아님"이 패킷 레벨에서도 그대로 — **종료를 시작한 쪽은 서버가 아니라
클라이언트(ghz)**라는 것이 이제 애플리케이션 로그가 아니라 TCP 플래그로 확인된다.

## 4. 한계 (정직하게)

- **로컬 단일 박스**([[project_loadtest_env_constraint]]) — 절대 수치(50건, 20ms 오프셋)는 이 환경
  고유. "종료를 시작하는 쪽이 클라이언트"라는 **메커니즘**만 인용 가능, 수치는 인용 금지.
  **§7.7의 값(100건)과 여기(50건)의 차이는 재측정이 아니라 동시성(c) 설정 차이일 뿐이다** — 같은
  메커니즘의 다른 동시성 재현.
- **표본 1판** — 재현 반복은 안 함(이 판의 목적은 "메커니즘이 실재하는가"의 예/아니오이지 분포 특성화가
  아니다). [[feedback_measure_design_needs_repeats]]가 요구하는 반복은 "성능 개선 주장"에 적용되는
  기준이라 이 판(반증/확인 목적)에는 해당 없음.
- HTTP/2(gRPC) 스트림 레벨 취소(GOAWAY/RST_STREAM) 프레임까지는 안 봤다 — TCP 플래그만으로 "클라이언트가
  먼저 닫았다"는 확인에 충분해서 페이로드 캡처(및 그로 인한 크기·복잡도 증가)는 안 했다.

## 5. 파생 발견 (별도 트랙)

이 재현 도중 `ExerciseGrpcService.SavePoseDataBatch`가 **호출 시작 시점의 취소만** 확인하고
(`abortIfClientGaveUp` → `CallCancellation.isAbandoned()`, #206-B) **처리 도중(mid-flight) 취소는
확인하지 않는다**는 것을 코드로 확인 — 클라이언트가 배치 INSERT 도중 연결을 끊어도 서버는 끝까지 쓰고
응답만 못 보낸다. 새 결정 아니고, 이 판의 목적(§7.7 보강)과 무관한 별도 관찰이라 여기 기록만 하고
`production-signal-checklist.md`·`async-pool-backpressure-experiment.md` 계열에 별도로 남긴다.
