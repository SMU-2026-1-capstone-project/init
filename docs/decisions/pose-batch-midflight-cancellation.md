# SavePoseDataBatch mid-flight 취소(#206 B-2) — 할 가치가 있는가

작성일: 2026-08-26
상태: **분석/추천 — 결정 미확정** (결정 ✅ 는 사용자 confirm 후, [[feedback_user_decides_not_claude]])
범위: `ExerciseGrpcService.SavePoseDataBatch` → `PoseDataService.savePoseDataBatch` 경로 하나. 다른
gRPC 핸들러(`CompleteAnalysis` 등)는 §6에서 짧게만 언급하고 별도 판단 대상으로 남긴다.
연관: [이슈 #206](https://github.com/Shadowfit/init/issues/206)(CLOSED — 이 문서의 전제),
[`../../loadtest/results/tcpdump-repro-2026-08-26/`](../../loadtest/results/tcpdump-repro-2026-08-26/)(계기),
[`./pose-batch-idempotency-implementation.md`](./pose-batch-idempotency-implementation.md)(`uk_pose_event`),
[`./load-test-strategy.md`](./load-test-strategy.md) §7.6(batchUpdate 채택 근거),
[[feedback_no_arbitrary_threshold_values]], [[feedback_state_assumption_design_to_it]]

---

## 0. 먼저 — 이건 새 버그가 아니다

이 문서는 **새로 발견한 결함을 다루지 않는다.** #206은 이미 CLOSED고, 그 이슈 자체가 결함 B를
B-1·B-2·B-3 셋으로 쪼갠 뒤 **B-1만 구현하고 B-2·B-3은 명시적으로 미룬 상태**다(#206 §6:
"B-2·B-3은 멱등키·배치 트랜잭션 경계와 상호작용한다. 여기서 즉답하지 말고 별도 판단이 필요하다").

- **B-1(구현 완료)**: 호출이 시작되기 전 이미 포기된 요청이면 아예 시작하지 않는다 —
  `CallCancellation.abortIfAbandoned`, `PoseDataService.java:137`. 되돌릴 수 없는 쓰기(batchUpdate)
  **직전**에 걸려 있다.
- **B-2(미결)**: 이미 시작된 batchUpdate **도중**의 취소는 못 본다 — 이게 이 문서의 대상이다.
- **B-3(미결)**: 남은 gRPC deadline을 DB 쪽(statement timeout 등)에 물리는 것 — 범위가 더 커서
  이 문서에서는 다루지 않는다(§6에서 한 줄만 비교).

이번에 다시 보게 된 계기는 tcpdump 재현(2026-08-26)이다 — ghz가 `-z` 만료로 먼저 TCP FIN을 보내는
것을 패킷으로 확인하면서, "그 순간 서버가 뭘 하고 있었는가"를 코드로 다시 짚다가 #206 B-2가 여전히
미결로 남아 있는 것을 재확인했다. **재현 자체가 B-2를 실측한 것은 아니다** — 그 판에서는 이미
`CallCancellation`(B-1)이 있어 요청 시작 전에 걸러졌을 가능성이 높고, batchUpdate 도중 취소가 실제로
일어났는지는 이번 판에서 별도로 확인하지 않았다.

## 1. B-2의 기술적 제약 — batchUpdate는 이미 원자적이다

`PoseDataService.savePoseDataBatch`의 쓰기는 `JdbcTemplate.batchUpdate`로 만든 **multi-row INSERT
한 문장**이다(§7.6, JPA `saveAll`의 개별 INSERT N방을 이것으로 바꿔 throughput +99%를 얻었다). 이건
JDBC 드라이버가 하나의 SQL 문으로 보내는 것이라 **Java 스레드 인터럽트로 중간에 끊을 수 있는 대상이
아니다** — 끊으려면 커넥션 자체를 강제로 닫아야 하고, 그러면 트랜잭션 전체가 롤백된다(부분 저장이
아니라 전체 취소가 된다는 뜻).

즉 "배치 루프 중간에 취소 확인"(#206이 원래 적어둔 B-2 문구)을 **글자 그대로** 하려면 이 multi-row
INSERT를 다시 행 단위 루프로 쪼개야 한다 — 그건 §7.6이 없앤 바로 그 구조로 되돌아가는 것이다.
**B-2는 캔슬레이션 세분화와 배치 성능이 정면으로 부딪히는 자리**라는 것이 이 문서의 핵심 관찰이다.

## 2. 실제로 낭비되는 시간이 얼마나 되나

B-1이 이미 "쓰기 시작 직전"을 막고 있으므로, B-2가 추가로 절약할 수 있는 것은 **"이미 시작된 한 번의
batchUpdate 호출이 끝날 때까지의 시간"** 하나뿐이다(데드락 재시도 루프는 매 시도마다 `savePoseDataBatch`가
처음부터 다시 불려 B-1 체크를 다시 타므로, 재시도 *사이*의 취소는 이미 잡힌다 —
`ExerciseGrpcService.savePoseDataBatchWithDeadlockRetry:250-276`).

- 이번 세션 tcpdump 재현 중 같은 호출(`SavePoseDataBatch`, 격리 환경, 로컬 단일 박스)의 실측 지연:
  **26ms~300ms, 평균 103ms(n=5, smoke 판)** — [`../../loadtest/results/tcpdump-repro-2026-08-26/`](../../loadtest/results/tcpdump-repro-2026-08-26/).
  🔴 표본이 5건뿐이고 목적이 경로 확인이라 이 수치를 "SavePoseDataBatch의 지연 분포"로 인용하면 안
  된다 — **자릿수 감(수십~수백 ms)만 참고할 것.**
- 데드락 재시도 상한(`deadlockMaxRetries=5`, 기본 백오프 0ms)까지 다 쓴 최악의 경우를 위 자릿수로
  거칠게 얹으면 **수백 ms~낮은 초 단위**다. 이것도 실측이 아니라 위 자릿수 감의 단순 곱이라
  [[feedback_no_arbitrary_threshold_values]]에 따라 **약속(threshold)이 아니라 참고 상한**으로만 쓴다.

**결론: B-2가 절약하는 것은 "이미 B-1이 걸러내고 남은, 낮은 초 단위 이하의 잔여 창"이다.** #206 A가
다뤘던 CompleteAnalysis 쪽(재시도 3회 × 5초 데드라인, 최대 19초)과는 자릿수가 다르다 — 그쪽은 이미
결정됐고 이 문서의 대상도 아니다.

## 3. 안전망은 이미 있다 — 낭비지 유실이 아니다

`uk_pose_event` 유니크 키([`pose-batch-idempotency-implementation.md`](./pose-batch-idempotency-implementation.md))가
재전송을 멱등하게 흡수하므로, 지금 상태(B-2 없음)에서 batchUpdate가 "쓸모없이 완주"해도 **데이터
정합성 문제는 없다** — 응답을 못 받은 AI가 같은 배치를 재전송하면 `ON DUPLICATE KEY UPDATE`가
중복을 흡수한다(§56). 즉 B-2가 막는 것은 **자원 낭비(DB 커넥션·CPU를 아무도 안 받을 응답에 씀)**지
**데이터 유실이나 오염이 아니다.** 이게 B-1/B-2를 "결함"이 아니라 "최적화 여지"로 놓아야 하는 이유다.

## 4. 옵션

| 옵션 | 내용 | 비용 | 절약 |
|---|---|---|---|
| **현행 (B-1만)** | 시작 직전 1회 확인 | 0(이미 있음) | 이미 대부분 확보 |
| **B-2 순수형** | multi-row INSERT를 행 단위로 쪼개 각 행 전에 취소 확인 | §7.6 batch 이득(+99% throughput) 역행 — **비추천** | 잔여 창 대부분(수십~수백 ms) |
| **B-2′ 청크형** | 배치를 N행씩 sub-batch로 나눠 청크 사이에서만 취소 확인 | 청크 수만큼 왕복 증가 — 청크 크기가 클수록(예: 전체를 1청크=현행과 동일) 비용↓이득↓ | 청크 크기에 따라 조절 가능 |
| **B-3 (별도 이슈)** | 남은 gRPC deadline을 DB 쪽에 전파 | 범위가 이 핸들러 하나를 넘음 | 데드락 재시도 루프 전체의 상한을 구조적으로 بound |

**B-2′(청크형)은 지금 이 배치 크기(다운샘플 후 R≈5 적용 시 rep당 수 행 수준, `pose-ingest-downsampling.md`)에서는
사실상 무의미하다** — 청크로 쪼갤 만큼 행 수가 많지 않다. 청크형이 의미를 가지려면 한 번의
`SavePoseDataBatch` 호출이 지금보다 훨씬 큰 배치(예: 오프라인 일괄 재처리, 세션 전체 재적재 같은
다른 사용처)를 다루게 될 때다 — **지금 이 호출 경로에는 해당하지 않는다.**

## 5. 추천 (결정 아님)

**지금은 손대지 않는 쪽을 추천한다.** 근거:
1. B-1이 이미 지배적인 낭비(시작 전 포기)를 잡는다.
2. 남은 창이 자릿수로 수십~수백 ms — §7.6이 지킨 batch 성능(+99%)을 깨면서까지 좁힐 값이 아니다.
3. 안전망(멱등키)이 있어 이건 정합성이 아니라 순수 자원 최적화 문제다 — 우선순위가 낮다.
4. 청크형(B-2′)은 이 호출의 실제 배치 크기에서 이득이 없다.

**단, 포폴 서사로는 가치가 있다** — "B-2를 문자 그대로 구현하지 않고, batchUpdate 원자성과
캔슬레이션 세분화가 상충한다는 것을 분석해 안 하기로 판단한 근거를 남긴다"는 이 문서 자체가
[[feedback_industry_level_standard]]가 요구하는 수준의 트레이드오프 사고를 보여준다 — "구현했다"보다
"왜 안 했는지 안다"가 더 강한 신호인 경우다.

## 6. 범위 밖 — CompleteAnalysis·B-3

- `CompleteAnalysis`는 이미 #206 코멘트(2026-08-22)가 별도로 다뤘다 — B-1 가드가 이 핸들러에서는
  "리포트를 버리는" 트레이드를 만든다는 게 지적됐고, 그 판단은 A(AI→Spring deadline)의 실측과 묶여
  있어 이 문서 범위 밖이다.
- B-3(deadline을 DB에 전파)는 이 핸들러 하나가 아니라 gRPC 계층 전체의 설계 문제라 별도 문서가
  필요하면 그때 연다.

## 7. 열린 질문 / 미검증

- [ ] 데드락 재시도 루프가 실제로 잔여 창을 얼마나 만드는지 — 이 문서의 §2 수치는 재시도가 안 걸린
      smoke 판(n=5)에서 역산한 자릿수 감이지, 재시도 루프 자체를 재현·실측한 값이 아니다.
- [ ] `Context.current().isCancelled()`가 batchUpdate 실행 중(블로킹 JDBC 호출 안)에도 폴링 가능한지 —
      "B-2 순수형"의 실제 구현 난이도에 영향을 준다. 이 문서는 §7.6 트레이드오프만으로 비추천 결론을
      냈고 이 세부는 확인하지 않았다.
