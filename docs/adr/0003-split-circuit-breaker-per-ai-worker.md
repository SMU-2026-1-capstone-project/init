# ADR-0003: AI 워커(채널)별로 서킷브레이커를 분리한다

작성일: 2026-08-27
상태: Accepted
근거 문서: [`../decisions/circuit-breaker-worker-aggregation.md`](../decisions/circuit-breaker-worker-aggregation.md)

## Context

`session_id % 3` 라우팅으로 AI 워커 3개가 사실상 독립 프로세스인데, Resilience4j `CircuitBreaker`(`aiServer`) 하나가 셋의 실패율을 합산하고 있었다(이슈 #556). 처음엔 "워커 크래시(프로세스 종료) 시 3개가 항상 같이 죽고 같이 살아난다"(`entrypoint.sh`의 `wait -n`, 실측된 재현)를 근거로 "합산이 오히려 정확한 모델"이라 판단했다. 그러나 "워커가 죽지 않고 생존한 채 느려지는(hang)" 시나리오를 실제 JUnit 테스트로 재현하자 결과가 반대로 나빴다 — 트래픽이 균등 분산되면 실패율이 임계값(50%) 아래로 항상 희석돼 서킷이 절대 안 열려 hang 워커가 무기한 무방비 상태가 되고, 트래픽이 한 워커로 쏠리면 #556이 원래 걱정한 대로 정상 워커까지 같이 차단됐다.

## Decision

**AI 워커(채널)별로 서킷브레이커를 분리한다.** `aiCircuitBreaker()`가 라우팅 키(exerciseId/sessionId)를 받아 `"aiServer-" + Math.floorMod(routingKey, aiChannelPoolSize)`로 브레이커를 나누고, 채널 선택(`asyncStubFor`/`blockingStubFor`)과 같은 라우팅 키를 그대로 재사용한다. 호출부 4곳(추출·시작·재접속·중단) 전부 이 방식으로 갱신했다. `@PostConstruct`에서 워커별 서킷을 미리 생성해 `/actuator/health`에 `aiServer-0`~`aiServer-{N-1}`이 첫 호출 전부터 노출되게 했다(안 하면 hang 워커가 "한 번도 호출 안 됨"과 구분이 안 된다). `application.yml`의 `resilience4j.circuitbreaker.instances.aiServer` 설정은 `configs.default`로 바꿔, 워커 수(`AI_WORKER_COUNT`)가 바뀌어도 설정 파일을 늘리지 않아도 되게 했다.

## Consequences

- 얻는 것: 크래시 모드(3개가 항상 같이 죽고 같이 산다)에서는 손해가 없다 — 분리해도 3개가 동시에 열리고 동시에 닫히는 관측 결과는 그대로다. hang 모드의 두 하위 시나리오(균등 분산·트래픽 쏠림) 모두 워커별 실패율이 독립적으로 집계되어 개선된다.
- 감수하는 것: 이 분리는 "실패율 임계값 기반 판단"을 워커 단위로 좁힌 것일 뿐, **hang 자체를 직접 감지하는 수단은 아니다** — 여전히 윈도우(10)·임계값(50%) 기준으로만 서킷이 열린다. 워커별 마지막 성공 응답 시각 등으로 hang을 더 빠르게 감지하는 것은 범위가 더 큰 별도 카드로 남겨뒀다. 실제 운영 트래픽이 균등 분산에 가까운지 쏠림에 가까운지는 배포 전이라 여전히 미확정이다.
- 다음에 영향받는 결정: `aiCircuitBreaker()`가 RPC 종류(추출·시작·중단) 셋을 하나로 묶는 것은 이 결정과는 다른 축의 "합산"이라 범위 밖으로 남겨뒀다 — 필요해지면 별도로 열어야 한다.

## Alternatives considered

- 워커 hang을 별도 헬스체크로 직접 감지(예: 워커별 마지막 성공 응답 시각을 반영해 "그 워커로 새 세션을 안 보낸다"는 판단을 서킷의 실패율 임계값보다 빠르게 내림) — 서킷 분리와 병행 가능하지만 범위가 이 결정보다 크다. 별도 카드로 분리.
- 현행 유지(합산 서킷) — 크래시 모드 실측만으로는 "손대지 않는다"는 근거로 충분했으나, hang 모드 실측(균등 분산 시 서킷이 절대 안 열림, 쏠림 시 정상 워커 차단)이 더 나쁜 결과로 나와 기각했다.
