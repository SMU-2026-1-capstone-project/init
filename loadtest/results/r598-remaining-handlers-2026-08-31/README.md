# #598 — 나머지 3개 gRPC 핸들러 in-flight 패턴 (격리 스택, 2026-08-31)

연관: [`docs 없음, 이슈 코멘트로만 기록됨`](https://github.com/Shadowfit/init/issues/598) —
2026-08-28 코멘트가 `savePoseDataBatch` 하나만 재고 "나머지는 아직"으로 남겼던 것을 채운다.

## 0. 이번 라운드가 다른 이유 — 격리 스택

이전 시도(같은 날짜 이전 세션)는 **공유** `shadowfit-mysql`/`shadowfit-backend`가 다른
브랜치의 Flyway 마이그레이션(V11 `trainer_assignments`, V13 `goals`)을 이미 적용해놓은
상태라 크래시 루프 중이라 측정 전에 중단했다. 이번엔 `docker-compose.iso.yml`(신규,
프로젝트명 `shadowfit-iso`)로 별도 mysql(13306)+backend(18080/16565/19090)를 새 볼륨에
띄워 공유 상태를 건드리지 않고 측정했다.

시딩: `exercises` 1~3(V2 마스터 시드 그대로) · `exercise_sessions` 1~200(신규 INSERT,
**전부 `exercise_id=1`로 통일** — 아래 §3 참고).

## 1. 방법

`loadtest/measure_r598_inflight_ramp.sh`(신규) — ghz ramp(c=5·15·30·50·80, 레벨당 8초)를
때리면서 `shadowfit_grpc_server_inflight{method=...}` 게이지를 0.25초 간격으로 폴링해
피크를 기록한다. 페이로드는 `loadtest/ghz/gen_r598_calls.py`(신규)가 만드는 400개짜리
JSON 배열을 ghz가 라운드로빈으로 순환한다(멱등 키 문제가 없는 3개 RPC라 `gen_batch_multi.py`류
템플릿 트릭 불필요).

🔴 **이 라운드는 그 어느 로컬 라운드보다 자원 경합이 심하다** — 격리 스택 자체(mysql+backend)에
더해, 이 물리 2코어 박스에 **공유 `shadowfit-backend`가 Flyway 실패로 크래시 재시작 루프를
돌며 지속적으로 CPU 260%대를 태우는 중**이었고, 다른 워크트리 컨테이너도 여럿 동거 중이었다.
절대 in-flight 숫자·지연·에러율은 이 조건 고유의 값이라 일반화하지 않는다
([[project_loadtest_env_constraint]]). 신뢰하는 것은 **plateau가 붙는지 안 붙는지** 뿐이다.

## 2. 결과 — peak in-flight (offered concurrency c 대비)

| c | ExtractReferenceData | CompleteAnalysis | ReportFeedbackBatch |
|--:|--:|--:|--:|
| 5  | 5.0  | 5.0  | 5.0  |
| 15 | 16.0 | 15.0 | 15.0 |
| 30 | 30.0 | 30.0 | 29.0 |
| 50 | 49.0 | 65.0 | 50.0 |
| 80 | 80.0 | (폴링 실패 — §4) | 80.0 |

원시 폴링 시계열: `*_c{N}_poll.tsv`. 레벨별 상태코드 분포: `*.ghz.json`
(`statusCodeDistribution`).

## 3. 판정 — 세 핸들러 모두 `savePoseDataBatch`와 같은 패턴

**셋 다 plateau가 안 붙는다.** in-flight 피크가 offered concurrency를 거의 그대로 따라
올라간다(오차는 폴링 간격에 따른 샘플링 오차 수준) — c=80까지 봐도 "여기서 상한이 걸린다"는
지점이 안 보인다. `savePoseDataBatch`가 2026-08-28에 보인 것과 **동일한 구조적 패턴**이다:
Spring gRPC 서버 스레드풀이 실제로 상한 없이 오퍼드 동시성을 그대로 통과시킨다는 #598의
핵심 주장이 4개 핸들러 전부에서 재현됐다.

`CompleteAnalysis`의 c=50 피크(65.0)만 오퍼드 동시성을 넘는데, 이건 "이 핸들러가 특별히
더 많이 쌓인다"기보다 **직전 레벨의 잔여 호출과 폴링 타이밍이 겹친 측정 잡음**으로 보인다 —
c=80에서 다시 정상 범위로 안 돌아온 것(§4에서 아예 못 잼)과 묶어 보면 인과관계를 주장할
근거가 약하다. 재확인하지 않았다.

## 4. 예상 밖 발견 — c=80 근방에서 실제로 무너지기 시작한다

`CompleteAnalysis` c=80에서 actuator(`:19090`)가 폴링 내내 **한 번도 응답하지 않았다**
(`*_c80_poll.tsv` 0줄). 그리고 세 핸들러 전부 c=50·80에서 gRPC 응답 자체가
**`Unavailable`을 c와 거의 같은 건수만큼** 냈다(예: `ReportFeedbackBatch` c=80 →
`{"OK":145,"Internal":30,"Unavailable":80}`). 백엔드 컨테이너는 재시작되지 않았다
(`RestartCount=0`, 세션 끝까지 `running`) — 완전한 다운은 아니고, 연결 수립 실패가 일시적으로
몰린 것으로 보인다.

🔴 **원인을 못 좁혔다** — 두 가지 후보가 섞여 있다:
1. 이 핸들러/레벨 자체의 부하 (§0의 "이번 라운드가 다른 이유"가 이미 짚은 공유 CPU 경합)
2. **레벨 사이에 쿨다운 없이 바로 다음 ghz 프로세스를 띄우는 이 스크립트의 설계** — 이전
   레벨의 연결이 정리되기 전에 새 레벨이 시작해 초반 연결 수립이 몰려 실패했을 가능성

`Unavailable` 건수가 매 레벨 **거의 정확히 c와 같다**는 점(우연이라기엔 너무 규칙적)은 ②쪽
정황을 가리키지만 검증하지 않았다 — 다음에 이 rig을 쓰면 레벨 사이 대기를 넣고 재확인할 것.

## 5. 부수 발견 — `ExtractReferenceData`의 Internal 에러가 유독 높다

| c | OK | Internal | Unavailable |
|--:|--:|--:|--:|
| 50 | 19 | 51 | 50 |
| 80 | 25 | 94 | 80 |

다른 두 핸들러(`CompleteAnalysis`·`ReportFeedbackBatch`)는 같은 c에서 Internal이 훨씬
적다(0~30건). 원인 후보: 이 라운드의 합성 페이로드가 **`exercise_id` 3종류만 400개
요청에 순환**시키는데(`gen_r598_calls.py`), `extractReferenceData`의 실제 구현
(`poseDataService.saveReferencePoses`)이 delete-then-insert 패턴이라 **같은 exercise_id에
동시 도달하면 경합한다**(이전 조사가 이미 지적한 지점). c=80이면 exercise당 평균 ~27-way
동시 경합이라 실제 프로덕션(신규 운동 등록 시 admin이 트리거하는 저빈도 작업, 같은 운동에
동시 등록이 겹칠 일이 사실상 없음)보다 훨씬 가혹한 조건이다 — **이 Internal 비율을 프로덕션
위험으로 일반화하지 않는다.** 다만 delete+insert 경합 자체는 실재하는 코드 패턴이라, 별도로
들여다볼 가치는 있어 보인다(이슈화 여부는 미결정).

## 6. 한계

- 로컬 2코어, 공유 컨테이너와 극심한 자원 경합 — §0·§4
- 레벨당 8초·5레벨, 반복 없음 — "plateau 유무"라는 구조적 판정에는 충분하지만 c=80 근방의
  실패율 자체를 정밀하게 특성화하려면 반복이 필요하다([[feedback_measure_design_needs_repeats]])
- `pose_data`가 이 스택에서 비어 있어 `CompleteAnalysis`의 `resolveSyncStats` 집계가
  가벼운 조건이었다 — 실제 세션처럼 pose_data가 쌓인 상태에서 재면 다른 패턴이 나올 수 있다
- `ExtractReferenceData`의 exercise_id 3종 순환은 의도적으로 만든 고충돌 조건이지
  프로덕션 재현이 아니다(§5)

## 7. 결정 사항 없음

스레드풀 상한을 얼마로 할지, 착수 여부·우선순위는 여전히 사용자 몫이다(#598 본문). 이번
라운드가 답한 것은 "4개 핸들러 전부 상한이 안 걸린다는 게 맞다"와 "c=80 근방에서 뭔가
무너지기 시작한다(원인 미확정)" 둘뿐이다.
