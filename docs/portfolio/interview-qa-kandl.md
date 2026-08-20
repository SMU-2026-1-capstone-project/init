# 케이엔엘소프트 면접 Q&A 카드

2026 ICT 인턴십 하반기 국내과정 · 면접일 2026-07-21

DB 엔지니어링 서사(Shadowfit)를 코드 레벨로 방어하기 위한 예상질문·모범답변·근거 정리. `근거` 블록은 실제 파일·라인·수치.

연관: [`portfolio-narrative.md`](./portfolio-narrative.md), [`realmysql-experiments.md`](./realmysql-experiments.md), [`../decisions/report-read-path.md`](../decisions/report-read-path.md)(②번 축), [`../decisions/grpc-integration-checklist.md`](../decisions/grpc-integration-checklist.md)(①번 축), [`../decisions/session-lifecycle-checklist.md`](../decisions/session-lifecycle-checklist.md)(③번 축)

---

## 30초 엘리베이터 피치

> "운동 세션이 Spring과 FastAPI 두 서비스에 걸쳐 있어서, 세션 종료 시 타임아웃 스케줄러와 AI 비동기 콜백이 같은 레코드를 두고 경쟁합니다. 이걸 @Version 낙관락 + 멱등 수신(first-write-wins) + afterCommit 외부 호출로 정합성 있게 풀었고, lost-update·MVCC를 직접 재현·관찰해 근거를 만들었습니다. 그 아래 데이터 계층은 412만 행 시계열에서 인덱스·JSON projection을 실측으로 엔지니어링했고, read-ahead가 hit율 공식을 속이는 함정까지 잡았습니다."

---

## 1. 세션 생명주기 동시성 (③번 축, 1순위, 자소서엔 없음)

포폴 전체의 실제 헤드라인(`portfolio-narrative.md` §1). 자소서는 이 얘기를 안 썼지만, 깃헙을 보는 면접관은 이걸 먼저 물어볼 가능성이 높음. **정정(2026-07-15)**: 아래 두 문항은 `Session.status` 상태 전이(③번 축, `session-lifecycle-checklist.md`) 얘기고, MVCC 자체는 별개 축이라 1-B로 분리함.

### Q. 두 서비스가 같은 세션을 동시에 건드리는 문제, 어떻게 푸셨어요?

운동 종료 시점에 **타임아웃 스케줄러**("너무 오래 안 끝남 → FAILED")와 **FastAPI 완료 콜백**("분석 끝남 → COMPLETED")이 같은 세션 레코드를 동시에 갱신할 수 있습니다. 저경합이고 서로 다른 세션 위주라 비관락의 상시 블로킹 비용이 아깝다고 판단해서, `@Version` 낙관락으로 충돌만 감지하고, 충돌 시 콜백 쪽이 최대 3회 재시도하며 **FastAPI 결과를 우선**시키는 정책을 택했습니다.

```
근거:
Session.java:66  @Version
SessionService.java:89~102  completeSession() — ObjectOptimisticLockingFailureException catch, maxAttempts=3
SessionService.java:104~122  applyComplete() — status==COMPLETED면 즉시 return (멱등, first-write-wins)
```

### Q. 왜 비관락(SELECT FOR UPDATE)이 아니라 낙관락이었나요?

직접 lost-update를 재현해서 비교했습니다. 같은 로우를 두 트랜잭션이 동시에 read-modify-write 하는 시나리오(`total += 10`, `total += 20`, 기댓값 30)에서 naive RMW는 매번 유실(10 또는 20)됐고, 세 가지 방지책 — 원자 UPDATE, `SELECT FOR UPDATE`, `@Version` CAS — 는 전부 30을 복구했습니다. 차이는 락 비용이었습니다: FOR UPDATE는 `performance_schema.data_locks`에서 다른 트랜잭션이 **WAITING** 상태로 블로킹되는 게 직접 보였고, 낙관락은 블로킹 0에 충돌 시에만 재시도합니다. 저희 세션 충돌은 저경합이라 상시 블로킹보다 가끔의 재시도가 쌌습니다.

**근거 — lost-update 재현 결과 (2026-06-05, measure_lock.sh)**

| 전략 | 최종값 | 손실 | 락 비용 |
|---|---|---|---|
| naive RMW (RC) | 10 또는 20 | 유실 | 없음(둘 다 옛값 읽음) |
| 원자 UPDATE | 30 | 없음 | UPDATE 1문장 직렬화 |
| 비관락 FOR UPDATE | 30 | 없음 | 트랜잭션 내내 X락 (블로킹) |
| 낙관락 CAS (@Version) | 30 | 없음 | 블로킹 0, 충돌 시 재시도 1회 |

---

## 1-B. MVCC — ①(gRPC 좌표 적재) × ②(보고서 조회) 교차 지점

**주의**: 이건 ③(세션 status 낙관락)이랑 다른 메커니즘·다른 테이블이야. Session.status 경합이 "쓰기 두 개끼리"라면, 이 MVCC 얘기는 **"쓰기(①, pose_data 적재) 하나 vs 읽기(②, 리포트 조회) 하나"**가 안 막히는 얘기. 실코드 매핑도 "사용자 A 세션이 pose_data에 적재 중 vs 사용자 B의 주간요약 조회"로 ①×②지 ③이 아님(`realmysql-experiments.md` §④).

### Q. MVCC가 정확히 어떻게 이 프로젝트에 도움이 되나요?

긴 배치 INSERT 트랜잭션이 열려 있는 동안에도 다른 사용자의 리포트 조회(SELECT)가 **블로킹되지 않는다**는 걸 직접 관찰했습니다. Reader가 100행을 읽은 트랜잭션을 3초간 유지하는 동안, Writer가 50행을 INSERT+COMMIT 했는데 **461ms**만에 끝났고 `data_locks`는 0행이었습니다 — 일반 SELECT는 락 없이 undo로 과거 버전을 재구성하기 때문입니다. 같은 시나리오를 SERIALIZABLE로 돌리면 SELECT가 암묵적 락(넥스트키락)이 되어 Writer가 **1,998ms**(4배) 블로킹되는 것도 대조로 확인했습니다.

```
근거 (2026-06-07, measure_mvcc.sh, scratch mvcc_lab 100행):
RR:  Reader 첫 SELECT=100 → Writer 50행 INSERT+COMMIT(그 사이) → Reader 재SELECT 여전히 100(스냅샷 고정) → Reader COMMIT 후 150
RC:  같은 시나리오, 재SELECT 즉시 150 (매 문장 새 스냅샷)
RR 비블로킹: Writer INSERT+COMMIT 461ms, data_locks 0행
SERIALIZABLE: Writer INSERT 1,998ms 블로킹, data_locks에 RECORD S GRANTED(리더의 암묵적 락)
```

### Q. 버퍼풀 크기가 실무에서 왜 중요한가요?

작업셋(실제로 자주 건드리는 데이터 범위)이 버퍼풀보다 크면, "warm"이라고 부르는 재실행도 캐시가 전혀 안 먹힙니다. AWS에서 진짜 2.3KB JSON·1억 행으로 재검증했는데, 8.6GB 작업셋을 cold로 스캔하면 675초, 곧바로 다시 스캔(warm)해도 675.85초로 **거의 그대로**였고 실제 디스크 읽기량도 12.8GB로 동일했습니다. 반대로 19MB짜리 작은 작업셋은 cold 1.56초 → warm 0.46초, 디스크 읽기 0바이트로 완전히 캐시됐습니다. 그리고 이때 표준 hit율 공식(1-reads/read_requests)은 큰 작업셋에서도 95.36%로 그럴듯하게 나왔는데, 실제 물리 I/O는 하나도 안 줄어서 **공식만 보면 착각하기 딱 좋다**는 걸 확인했습니다.

```
근거 (2026-07-15, AWS, pose_data_real_scale):
큰 작업셋(8.6GB): cold 675.2초 → warm 675.85초 (이득 0), data_read 12.79GB→12.78GB (불변)
작은 작업셋(19MB): cold 1.557초 → warm 0.461초 (3.4배), data_read 27.2MB→0바이트 (완전 캐시)
naive hit율(큰 작업셋 warm) = 95.36% — 그럴듯하지만 실제 I/O는 안 줄어든 함정 사례
```

### Q. 이게 SQL 마이그레이션이랑 무슨 상관이죠?

DB마다 MVCC 구현과 기본 격리수준이 다릅니다 — 예를 들어 Oracle은 기본이 READ COMMITTED고 롤백세그먼트 기반 MVCC, MySQL InnoDB·PostgreSQL은 기본 REPEATABLE READ(또는 유사)에 튜플 버저닝 방식입니다. 애플리케이션의 동시성 로직(락 획득 시점, 재시도 정책)을 그대로 옮기면 원본 DB에서는 안전했던 코드가 대상 DB에서 lost-update나 팬텀리드에 노출될 수 있습니다. 저는 이 프로젝트에서 격리수준별 동작 차이를 실측으로 직접 확인해봤기 때문에, 이런 이관 리스크를 "개념"이 아니라 "실측 경험"으로 이해하고 있습니다.

---

## 2. 인덱스 · 실행계획 · Projection (2순위, 자소서 원문)

자소서 "직무 관련 경험" 문단 그대로. 숫자는 정확하니 자신 있게 말해도 됨 — 단 스케일(412만)은 3번 섹션에서 정정.

### Q. 조회 성능 문제, 어떻게 찾고 고치셨어요?

운동 세션마다 관절 좌표가 쌓이는 구조라 데이터가 늘수록 조회가 느려질 거라 예상하고, 인덱스 문제로 가정했습니다. 그런데 `EXPLAIN`으로 실측해보니 `idx_session_timestamp(session_id, timestamp_sec)`가 이미 `type=ref, Extra=NULL`(filesort 없음)로 최적이었습니다. `IGNORE INDEX`로 강제 풀스캔 시켜봤더니 412만 행 스캔+filesort로 85초가 걸려서, 인덱스가 없었다면 어떻게 됐을지 대조로 확인했습니다. 진짜 원인은 쿼리가 안 쓰는 `joint_coordinates`(2.3KB JSON, InnoDB off-page 저장)까지 매번 로드하는 거였고, 3컬럼 projection DTO로 바꿔서 해결했습니다(아래 수치는 이 3컬럼 시점 기준이고, 이후 rep 단위 계산이 들어오면서 지금은 4컬럼입니다 — `feedbackMessage` 를 빼고 `repNumber`·`smoothedKneeAngle` 을 넣었습니다. 둘 다 숫자 하나씩이라 `joint_coordinates` 를 안 싣는다는 핵심은 그대로입니다).

```
근거:
payload:      1,716.8 KB → 22.4 KB   (-98.7%)
warm 쿼리:   12.1 ms → 1.5 ms         (8x, -87%)
풀스캔 대조: IGNORE INDEX 시 412만 행 스캔 + filesort = 85초 (AWS 1억 행 재검증: 2,120.9초=35분20초, 85×24.27배 선형추정 2,063초와 거의 일치)
코드: PoseDataRepository.findFramesBySessionId (JPQL projection)
     ReportService.selectWorstSection / buildWorstReason (PoseFrameProjection 사용)
```

### Q. payload는 98.7% 줄었는데 속도는 왜 87%(8배)밖에 안 줄었어요?

바이트 감소량과 시간 감소량이 선형이 아니기 때문입니다. 750행 lookup 같은 고정비용은 그대로 남고, projection이 없애는 건 `joint_coordinates`가 InnoDB off-page(오버플로우 페이지)에 저장돼서 발생하는 **추가 random I/O**뿐입니다. 그 I/O 비용만 사라진 거라 바이트 감소율과 시간 감소율이 다르게 나옵니다.

### Q. 412만 행이면 작은 스케일 아닌가요? 더 큰 규모에서도 되나요?

면접 준비하면서 로컬 하드웨어 제약(2코어, 디스크 223GB) 때문에 412만 행이 상한선이었다는 걸 인지하고, AWS EC2로 실제 1억 행·진짜 2.3KB JSON 환경을 만들어 같은 실험을 재검증했습니다. payload는 −98.7%로 거의 동일(세션당 바이트 비율이라 행수와 무관하게 예상대로), **속도는 오히려 29~41배로 412만 행 때(8배)보다 더 크게 개선**됐습니다. 버퍼풀(2GB)은 그대로인데 테이블이 25배 커지면서 작업셋 대비 버퍼풀 비율이 나빠져, 풀엔티티 로드의 off-page 랜덤 I/O가 캐시에 덜 걸리고 실제 디스크 I/O를 더 타게 된 게 원인입니다. 결론이 바뀐 게 아니라 스케일이 커질수록 오히려 강화되는 방향이라는 걸 직접 확인했습니다.

```
근거 (2026-07-15, AWS m6i.xlarge, pose_data_real_scale 1억 행):
payload: 1,740.1 KB → 22.6 KB (-98.7%)
warm 쿼리: 40.6ms → 1.4ms (약 29배, 반복 측정 시 최대 41배)
```

---

## 3. 약점 질문 대응 (찔러도 안 막히게)

방어가 아니라 "인지하고 있다"를 보여주는 게 목표.

### Q. 자소서에 "1억 행"이라고 쓰셨는데? — 숫자 정정

정확히 말씀드리면 이 EXPLAIN·projection 실험은 **412만 행**에서 측정한 겁니다. 1억 행은 같은 프로젝트의 다른 실험(offset vs keyset 페이지네이션)에서 쓴 스케일이고, 자소서를 정리하는 과정에서 프로젝트 전체 대표 숫자(1억)와 개별 실험 숫자(412만)가 섞였습니다. 면접 준비하면서 이 부분을 다시 확인했고, 로컬 환경(2코어, 디스크 223GB)에서는 실제 2.3KB JSON을 1억 행 넣으면 약 230GB가 필요해 물리적으로 불가능하다는 것도 확인했습니다.

### Q. 합성 데이터로 실험하셨다는데, 실제 데이터랑 다르지 않나요? — 분포 한계

맞습니다, 명확한 한계입니다. 시딩 rig가 단일 템플릿(750행)을 복제하는 구조라 **행 수·payload 크기는 현실적이지만 값 분포(카디널리티)는 균일**합니다. 그래서 선택도·옵티마이저 카디널리티처럼 값 분포에 의존하는 실험은 의도적으로 제외했고, 인덱스·페이지네이션·파티션처럼 **구조(행 수·크기)에만 의존하는 실험만 골라서 수행**했습니다. 실사용자가 없는 캡스톤 프로젝트라 애초에 "진짜" 분포를 알 방법이 없는데, 억지로 분포를 가정해서 실험하면 오히려 근거 없는 숫자로 결론을 내리는 게 되어 신뢰도가 떨어진다고 판단했습니다.

### Q. 관측성(로깅·모니터링)이나 메시지 안정성은요? — 빈칸

아직 안 돼 있습니다. 구조화 로깅+correlation id 전파, Actuator는 빈칸이고, gRPC 통보는 현재 fire-and-forget(실패 시 로그만 남김)이라 at-least-once 전달이 보장되지 않습니다 — outbox 패턴으로 보강할 설계는 해뒀지만 착수 전입니다. 헤드라인(세션 정합성)과 데이터 계층(인덱스·projection)에 우선순위를 두고 진행하다 보니 아직 못 갔는데, 다음 착수 대상으로 명확히 인지하고 있습니다.

> 🔴 **이 답변은 2026-07-21 시점의 사실이고, 지금은 셋 다 채워졌다** (2026-08-12 확인).
> **면접 당시 답변으로서는 정확했으므로 본문을 고치지 않고 정정만 덧붙인다.**
>
> | 그때 «빈칸» 이라고 답한 것 | 지금 |
> |---|---|
> | correlation id 전파 | ✅ **5단계 전파**(HTTP·gRPC 진입점·MDC·`@Async` TaskDecorator·스케줄러) — 2026-07-28 |
> | Actuator·모니터링 | ✅ 관리 포트 9090 분리 + **커스텀 지표 9종**([`SessionMetrics.java`](../../backend/src/main/java/com/shadowfit/global/observability/SessionMetrics.java)) + Prometheus·Grafana(profile `obs`) — 2026-08-08 |
> | outbox «설계만, 착수 전» | ✅ **구현 완료** — 아웃박스 + 멱등 수신, 지표 3종(`pending`·`dispatch`·`lag`)까지 |
>
> 🔶 **다만 «다 됐다» 로 바꿔 답하면 안 되는 부분이 남는다** — AI→Spring **콜백 방향**의 장애 보호는
> 여전히 없다(서킷브레이커·재시도가 Spring→AI 방향에만 있다, 의도적 스코프 제외).
> 재전송 없이 유실되는 경로도 열려 있다([#188](https://github.com/Shadowfit/init/issues/188)).
> 지금 같은 질문을 받으면 **«전달 보장은 붙였고, 반대 방향 보호는 의도적으로 안 했습니다»** 가 정확한 답이다.

### Q. 부하테스트 수치, 얼마나 신뢰할 수 있나요? — 환경 한계

절대 수치(RPS, 처리량)는 신뢰 구간이 아니라고 명확히 구분합니다. 로컬이 i3-6100 물리 2코어인데 MySQL·백엔드·부하생성기(ghz)가 같은 머신에서 동거하기 때문입니다. 그래서 결론은 항상 **메커니즘(왜 그런 결과가 나오는지)**과 **상대적 델타(A 방식 대비 B 방식이 몇 배)**로만 제시했고, "TPS 몇 나왔다" 식의 절대 성능 자랑은 하지 않았습니다.
