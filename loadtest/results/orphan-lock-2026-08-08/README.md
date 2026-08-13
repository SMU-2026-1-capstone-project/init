# #87 수정안 ㄱ(비관적 락) 비용 측정 — 2026-08-08

대상: [이슈 #87](https://github.com/Shadowfit/init/issues/87) 의 수정안 ㄱ — `PoseDataService.savePoseDataBatch` 의
세션 존재 검증을 **잠그는 조회로 바꾸면** 고아 행 창이 닫힌다. 그 대가가 얼마인지 잰다.

관련 문서: [`docs/decisions/withdrawal-with-active-session.md`](../../../docs/decisions/withdrawal-with-active-session.md) ·
[`docs/decisions/load-test-strategy.md`](../../../docs/decisions/load-test-strategy.md) ·
[`docs/decisions/pose-data-partition-fk-tradeoff.md`](../../../docs/decisions/pose-data-partition-fk-tradeoff.md)

---

## 0. 한 줄 요약

**결론은 «ㄱ 이 비싸다» 도 «싸다» 도 아니다. 이 환경에서 단판 비교로는 이 크기의 효과를 잴 수 없다는 것이다.**

락을 **추가한** 판(`multi-after-scalar`, 37.5 RPS)이 아무것도 안 한 판(`multi-before`, 27.2 RPS)보다 **38% 빨랐다.**
`FOR UPDATE` 를 붙여서 처리량이 오를 수는 없으므로, 이건 효과가 아니라 **판 사이 변동**이다. 그리고 그 변동이
재려던 효과(−35.3%)보다 크다 — 즉 **앞의 −35.3% 도 신뢰할 수 없다.**

측정 5판을 다 남긴다. 숫자가 쓸모없어서가 아니라, **어떻게 어긋났는지가 이 실험의 산출물**이기 때문이다.

---

## 1. 판정선 (측정 전에 고정)

결과를 보고 정하면 판정이 아니라 사후 해석이라, 실행 전에 못박고 그대로 적용했다.

| 델타 (before → after) | 판정 |
|---|---|
| RPS −5% 이내 **그리고** p99 +10% 이내 | 🟢 비용 없음 |
| RPS −5~15% 또는 p99 +10~30% | 🟡 비용 있음 — 고아 위험과 저울질 |
| RPS −15% 초과 또는 p99 +30% 초과 | 🔴 비쌈 |

---

## 2. 무엇을 바꿨나

```java
// before — 잠그지 않는 존재 확인
if (!sessionRepository.existsById(sessionId)) throw new BusinessException(SESSION_NOT_FOUND);

// after — 세션 행을 커밋까지 잠근다
sessionRepository.findByIdForUpdate(sessionId).orElseThrow(() -> new BusinessException(SESSION_NOT_FOUND));
```

**락 «획득» 은 원래도 있었다.** 같은 메서드 끝의 `UPDATE exercise_sessions SET last_active_at` 이 같은 행을
X 락으로 잡고, `@Transactional` 이라 커밋까지 유지된다. 즉 ㄱ 이 늘리는 것은 획득 횟수가 아니라 **보유 시간**이고,
늘어나는 구간이 정확히 #87 의 창(실측 58~149ms)이다.

---

## 3. 절차

네 판 모두 동일하다 — **재빌드 → 컨테이너 교체 대기 → gRPC 기동 대기 → 리셋 → warmup 60s(c=20, 폐기) → 리셋 → ramp 210s(c 5→100 step)**.

warmup 을 매판 넣은 이유는 [`load-test-strategy.md` §7.6](../../../docs/decisions/load-test-strategy.md) 의 cold JVM 교훈이다.
재현 스크립트는 [`sweep-multi.ps1`](./sweep-multi.ps1)(다중 세션 판) · `loadtest/ghz/rebuild-and-measure.ps1`(단일 세션 판).

| | 단일 세션 판 | 다중 세션 판 |
|---|---|---|
| 데이터 | `batch.json` — 전 요청이 **세션 801** | `batch_multi.json` — **세션 901~1900 순회** |
| 잠그는 행 | 전부 같은 행 → 완전 직렬화 | 요청마다 다른 행 → 경합 거의 없음 |
| 성격 | 상한(실제로 발생하지 않는 상황) | 실운영 근사 |

환경: 로컬 단일 호스트(i3-6100, 2물리코어)에 MySQL·백엔드·ghz 동거. `dockin-*` 컨테이너 3개는 측정 전 중지.
세션 901~1900 은 이 측정을 위해 시딩했고 (`reference_source='loadtest-multi'`) **남겨뒀다** — [`seed-multi-sessions.sql`](../../seed/seed-multi-sessions.sql).

> 📌 이 시드는 여기서 만들어졌지만 **`loadtest/seed/` 로 옮겼다**(2026-08-12, #166). 다세션 페이로드가 rig 의 기본값이 되면서 1회성 산출물이 아니라 **상시 전제**가 됐기 때문이다.

---

## 4. 결과

| 판 | 데이터 | RPS | p50 | p90 | p99 | OK |
|---|---|---:|---:|---:|---:|---:|
| `lock-before` | 단일 | 10.9 | 4,135ms | 9,979ms | 17,165ms | 95.6% |
| `lock-after` | 단일 | 3.5 | 14,990ms | 18,510ms | 19,895ms | **49.0%** |
| `multi-before` | 다중 | **27.2** | 1,648ms | 4,049ms | 6,210ms | 98.3% |
| `multi-after` | 다중 | **17.6** | 2,653ms | 5,913ms | 8,413ms | 97.3% |
| `multi-after-scalar` | 다중 | **37.5** | 1,023ms | 2,839ms | 12,046ms | 98.7% |

세 다중 판의 코드 차이는 이렇다.

| 판 | 락 | 엔티티 하이드레이션 | RPS |
|---|:--:|:--:|---:|
| `multi-before` | ✗ | ✗ | 27.2 |
| `multi-after` | ✓ | ✓ | 17.6 |
| `multi-after-scalar` | ✓ | ✗ | **37.5** |

### 🔴 이 표는 인과로 읽을 수 없다

세 번째 판은 **락을 걸었는데 락이 없는 첫 판보다 38% 빠르다.** `FOR UPDATE` 는 일을 더 하지 덜 하지 않으므로
이 순서는 **어떤 인과로도 설명되지 않는다.** 남는 해석은 하나다 — **판 사이 변동이 효과보다 크다.**

그러면 앞서 🔴 로 판정했던 `before → after` 의 −35.3% 도 같은 변동 안에 들어간다. **판정선을 적용할 자격이
없는 데이터**였다는 뜻이고, 그래서 §0 의 결론을 "🔴" 가 아니라 "못 잰다"로 고쳤다.

변동의 원인으로 의심되는 것 (전부 **미검증**):

- **테이블 상태가 판마다 다르다.** 리셋은 `session_id BETWEEN 901 AND 1900` 만 지우는데, 판을 거듭할수록
  `pose_data` 전체는 커진다(측정 종료 시점 42,615행). 삭제·삽입이 반복된 페이지 상태도 같지 않다 —
  이건 이 프로젝트가 **미실험으로 보류해둔 소량 DELETE 파편화**와 같은 자리다
- **호스트 상태** — 2물리코어에 MySQL·백엔드·ghz 동거. 판 사이 40분~1시간 간격, 그 사이 다른 작업이 있었다
- **buffer pool 워밍 정도** — JVM warmup 은 통제했지만 **MySQL 쪽은 통제 항목에 없었다**

> `Unavailable` 100 건은 다섯 판 모두 동일하게 나오는 rig 아티팩트다 — 측정 종료 시 in-flight 강제 종료
> ([`load-test-strategy.md` §7.7](../../../docs/decisions/load-test-strategy.md) 에서 규명 완료). 서버 결함이 아니다.

> `Unavailable` 100 건은 네 판 모두 동일하게 나오는 rig 아티팩트다 — 측정 종료 시 in-flight 강제 종료
> ([`load-test-strategy.md` §7.7](../../../docs/decisions/load-test-strategy.md) 에서 규명 완료). 서버 결함이 아니다.

---

## 5. 🔴 이 측정의 결함 — 조건을 두 개 바꿨다

**`existsById` 와 `findByIdForUpdate` 는 «락 유무» 만 다른 게 아니다.**

| | 하는 일 |
|---|---|
| `existsById` | `SELECT count(*) … WHERE id=?` — 잠그지 않고, **엔티티를 만들지 않는다** |
| `findByIdForUpdate` | `SELECT s.* … FOR UPDATE` — 잠그고, **`Session` 엔티티 전체를 하이드레이션**해 영속성 컨텍스트에 올린다 |

즉 −35.3% 안에는 **락 비용과 엔티티 로드 비용이 섞여 있다.** 이 실험은 ㄱ 의 비용을 잰 게 아니라
**ㄱ 의 상한**을 잰 것이다.

📌 **이 프로젝트가 이미 세 번 적어둔 규칙을 또 어겼다** — *"차이가 보였다"와 "차이가 있다"는 다르다.
조건을 한 번에 하나만 바꿨는지 먼저 확인한다"* ([`load-test-strategy.md` §7.6](../../../docs/decisions/load-test-strategy.md)).
그 문장을 인용해가며 설계해놓고 같은 형태로 어겼다.

**분리해서 재려고** 세 번째 판(`multi-after-scalar`)을 돌렸다 — `SELECT s.id FROM Session s WHERE s.id = :id` +
`@Lock(PESSIMISTIC_WRITE)`. 락은 그대로 두고 하이드레이션만 뺀 형태이고, ㄱ 을 실제로 구현한다면 이 모양이다.

**그 판이 §4 의 모순을 만들었다.** 조건을 하나만 바꾼 것은 맞았는데, 결과가 인과로 설명이 안 되면서
**앞의 −35.3% 가 무엇이었는지도 같이 무너졌다.** 조건을 하나로 줄인 것이 문제를 푼 게 아니라
**측정 자체가 성립하지 않았다는 것을 드러냈다.**

### 5-0. 폐기한 판 1건 (기록)

`multi-after-scalar` 는 **두 번 시도했다.** 첫 시도는 ramp 도중 중단하고 버렸다 — 빌드가 시작된 뒤 워킹트리의
패치가 되돌려진 것을 발견했는데, 도커 빌드 컨텍스트가 전송된 시점과의 선후를 확정할 수 없어
**그 컨테이너가 어떤 코드로 빌드됐는지 말할 수 없었다.** 결과가 나와도 무엇을 잰 판인지 모르므로 버리는 게 맞다.

두 번째 시도는 패치 적용을 먼저 확인하고, `dockin-*` 정지 상태(앞선 네 판과 동일)를 맞춘 뒤 실행했다.

### 5-1. 단일 세션 판은 왜 결론을 못 냈나

`lock-after` 는 RPS 3.5 · OK 49% 로 붕괴했는데, **이건 락 비용이 아니라 rig 가 만든 인공 경합이다.**
요청 100개가 전부 세션 801 한 행을 잠그니 완전 직렬화된다(3.5 RPS ≈ 트랜잭션당 285ms, 산수가 맞는다).
같은 코드로 데이터 파일만 바꾼 `multi-after` 가 **RPS 5배(17.6)** 인 것이 직접 증거다.

실행 전에 *"단일 세션 rig 의 한계가 이 실험에서는 오히려 장점"* 이라고 적었는데 **틀렸다.**
상한 논증은 **🟢 가 나올 때만** 결론이 난다("최악에도 괜찮으니 실제로는 더 괜찮다"). 🔴 가 나오면 실운영에 대해
아무것도 말하지 못한다. 그 비대칭을 설계 시점에 안 따졌다.

### 5-2. 절차 흠결 1건

`lock-after` 는 warmup 후 리셋했는데 본측정 시작 시 **행이 30 남아 있었다** — warmup 의 in-flight 요청이
DELETE 뒤에 착지했다. 결과를 뒤집을 크기는 아니지만 기록한다. 다중 세션 판부터는
[`sweep-multi.ps1`](./sweep-multi.ps1) 이 리셋 전 5초 대기 + **0 이 아니면 실패**로 막았다.

---

## 6. 한계

- **절대값은 쓸 수 없다.** 2물리코어에 MySQL·백엔드·ghz 동거. 델타만 신뢰한다([`project_loadtest_env_constraint`])
- **비교는 같은 데이터 파일끼리만.** `multi-after`(17.6) 를 `lock-before`(10.9) 와 비교하면 안 된다 — 배치당 행수·세션 분포가 다르다
- **락 대기 자체는 이 판에서 거의 발생하지 않는다.** 세션마다 다른 행이라 정상이고, 그래서 −35.3% 의 대부분은 대기가 아니라 **쿼리·하이드레이션 비용**으로 추정된다. 추정이고 프로파일링으로 가르지 않았다
- **데드락은 미검증.** 코드상 순환이 없다고 판단했을 뿐이다 — 탈퇴는 `users → exercise_sessions` 순, 배치는 `exercise_sessions` 만 잠그고 `users` 를 요청하지 않는다. 이 판단은 `Session` 의 연관이 둘 다 `LAZY` 인 데 의존한다(EAGER 면 조인된 `users` 까지 FOR UPDATE 대상이 되어 순환이 생긴다). 재현 테스트 미작성

---

## 7. 남은 것

- [x] ~~**엔티티 로드를 뺀 락만의 비용**~~ → 측정했으나 **§4 의 모순으로 해석 불가**. 조건은 하나만 바꿨는데 결과가 인과를 안 따랐다
- [ ] 🔴 **반복 측정** — 이 환경에서 델타를 주장하려면 **판을 교차로 여러 번**(before/after/before/after…) 돌려 변동 폭부터 재야 한다. 그 폭보다 큰 차이만 효과로 인정할 수 있다. **지금까지의 모든 델타는 그 검증 없이 나온 값이다**
- [ ] **테이블 상태 통제** — 리셋 범위를 `901~1900` 이 아니라 판마다 동일한 시작 상태(예: 테이블 재생성)로. 지금은 판을 거듭할수록 `pose_data` 가 커진다
- [ ] **데드락 회귀 테스트** — 3307 MySQL 프로파일(`application-race.yml`) 필요. 미착수
- [ ] **ㄱ / ㄴ / ㄷ 결정** — 이 문서는 결정을 담지 않는다. **그리고 이 문서로는 결정할 수 없다**

### 7-2. 🔄 반복 측정 착수 시도 (2026-08-09) — 돌리지 않고 멈췄다

[`../../../docs/decisions/slo-baseline.md`](../../../docs/decisions/slo-baseline.md) §5-1 이 *"델타 인정 = before/after 분포가 겹치지 않을 것"* 을 규칙으로 제안하면서, 그 선결인 위 «반복 측정» 을 실제로 돌리려 했다. **착수 전 점검에서 멈췄고, 점검 결과 자체가 남길 값어치가 있다.**

#### ① 리그는 멀쩡하다 — 확인한 것

| 항목 | 상태 |
|---|---|
| [`sweep-multi.ps1`](./sweep-multi.ps1) | ✅ 그대로 쓸 수 있다 |
| gRPC 인증 (`INTERNAL_API_TOKEN`) | ✅ **안 바뀌었다.** [#134](https://github.com/Shadowfit/init/issues/134) 가 토큰을 갈랐지만 분리된 것은 AI 서버용 `AI_PUBLIC_TOKEN` 이고, 백엔드는 `docker-compose.yml:73` 에서 여전히 `INTERNAL_API_TOKEN` 을 받는다 |
| 세션 901~1900 시드 | ✅ **1,000행 존재** |
| `pose_data` 잔존 | 🔴 **39,390행** — 08-08 판의 찌꺼기가 그대로 남아 있다 |

> 📌 마지막 줄이 위 «테이블 상태 통제» 항목의 실물 증거다. 리셋은 `901~1900` 범위 `DELETE` 뿐이라 **판을 거듭할수록 테이블이 커지는 문제가 실제로 누적돼 있었다.**
>
> ⚠️ 인증을 확인한 이유는 [#145](https://github.com/Shadowfit/init/issues/145)·[#153](https://github.com/Shadowfit/init/issues/153) 때문이다. 같은 날 **측정 장치가 대상의 변경을 못 따라온 사례가 둘** 나왔으므로, 돌리기 전에 리그가 낡지 않았는지부터 봤다. 이번엔 안 낡아 있었다.

#### ② 🔴 멈춘 이유 — 재빌드가 워킹트리를 굽는다

`sweep-multi.ps1:39` 가 판마다 `docker compose up -d --build shadowfit-backend` 를 한다. 즉 **커밋이 아니라 워킹트리를 빌드**한다. 착수 시점의 워킹트리에는 **다른 작업의 미커밋 변경**이 있었다:

```
M backend/.../ExerciseAnalysisService.java
M ai-server/app/api/endpoints/pose.py
M ai-server/app/grpc/exercise_servicer.py
?? ai-server/app/core/analyzer_registry.py
```

그리고 그 작업은 **진행 중**이었다(같은 시간대에 HEAD 가 여러 번 움직였다). 40분짜리 판을 도는 동안 저쪽이 파일을 건드리면 **판마다 다른 코드를 잰 것**이 되고, 그러면 변동 폭 측정이 통째로 무효가 된다.

> 🔴 **이건 이 문서 §5 가 이미 기록한 폐기 사유와 같다** — *"빌드 시작 뒤 워킹트리 패치가 되돌려진 것을 발견, 컨테이너가 어떤 코드로 빌드됐는지 확정할 수 없어 ramp 도중 중단."* 그때는 실행 중에 발견했고, 이번엔 **실행 전에 걸렸다.**

#### ③ 그 점검에서 나온 설계 — 변동 폭을 두 성분으로 가른다

멈추는 대신 얻은 것이 이 구분이다.

| | 무엇을 재나 | 판당 | 코드 변경 위험 |
|---|---|--:|---|
| **(a) 측정 변동** | **빌드 1회** 후 측정만 반복 (리셋 → warmup 60s → 리셋 → ramp 210s) | ~4.8분 | **없음** |
| **(b) 판 변동** | 재빌드·재기동까지 포함 — §4 델타들이 나온 조건 | ~7분+ | 있음 |

**(a) 를 먼저 재는 것이 낫다. 이유는 비대칭이다** — (a) 는 (b) 의 **하한**이므로, **(a) 만으로도 −35.3% 가 변동 안에 들어가면 거기서 결론이 난다.** 그러면 (b) 를 돌릴 필요가 없다. 반대로 (a) 가 작게 나오면 그때 (b) 가 필요해진다.

> 📌 §5-1 이 *"상한 논증은 🟢 일 때만 결론이 난다"* 는 비대칭을 **안 따져서** 단일 세션 판을 헛돌린 기록을 남겼다. 이번 (a)/(b) 구분은 그 교훈을 **반대 방향으로** 적용한 것이다 — 싼 쪽이 결론을 낼 수 있는 방향인지 먼저 보고, 그쪽부터 돌린다.

#### ④ 그래서 돌리기 전에 필요한 것

- [ ] **워킹트리 고정** — 병행 작업이 정리되거나, 백엔드를 건드리지 않는 것이 확실할 때. 판을 돌리는 동안 빌드된 코드가 **하나로 확정**돼야 한다
- [ ] **판 수 결정** — (a) 기준 6판 ≈ 30분 / 4판 ≈ 20분. 4판은 *"최소한 이만큼은 흔들린다"* 이상은 말하기 어렵다
- [ ] **이웃 컨테이너 정지** — 관측 스택·`dockin-app-1`. 08-09 집계 측정 때와 같은 조건([`../../../docs/decisions/admin-page-scope.md`](../../../docs/decisions/admin-page-scope.md) §4-5-2 ④)
- [ ] **시작 상태 통제** — 위 «테이블 상태 통제» 와 같은 항목. 지금 39,390행이 남아 있으므로 첫 판 전에 반드시 처리해야 한다

### 7-1. 곁가지로 확인된 것 — GC 는 G1 이다

08-08 EC2 실험의 Prometheus 원본([`../pool-cliff-2026-08-08/metrics/m-jvm_memory_used_bytes.json`](../pool-cliff-2026-08-08/metrics/m-jvm_memory_used_bytes.json))
의 메모리 풀 이름이 `G1 Eden Space` · `G1 Survivor Space` · `G1 Old Gen` 이다.

확인할 값어치가 있었던 이유: JVM 은 "클라이언트급 머신"(CPU 2개 **미만** 또는 메모리 1792MB **미만**)으로 판정하면
조용히 **SerialGC** 로 떨어진다. 2 vCPU 인스턴스는 그 경계 바로 위라 갈릴 수 있는 자리였는데 안 걸렸다.

JVM 옵션은 **하나도 지정하지 않았고**(`Dockerfile`·compose 어디에도 `JAVA_OPTS`·`Xmx` 없음) 기본값이 적절했다.
→ **튜닝할 것 없음.** 다만 `MaxRAMPercentage` 기본값이 25% 라, prod 에 컨테이너 메모리 캡을 거는 시점에는
힙 상한이 그 캡의 1/4 로 잡힌다는 점을 캡과 **같이** 정해야 한다(호스트 RAM 만 올리고 캡을 안 푼 채 OOM 이
재발했던 전례가 있다).

---

## 파일

| | |
|---|---|
| [`ramp-lock-before.json`](./ramp-lock-before.json) · [`ramp-lock-after.json`](./ramp-lock-after.json) | 단일 세션 판 ghz 원본 |
| [`ramp-multi-before.json`](./ramp-multi-before.json) · [`ramp-multi-after.json`](./ramp-multi-after.json) · [`ramp-multi-after-scalar.json`](./ramp-multi-after-scalar.json) | 다중 세션 판 ghz 원본 |
| [`sweep-multi.ps1`](./sweep-multi.ps1) | 다중 세션 판 재현 스크립트 |
| [`seed-multi-sessions.sql`](../../seed/seed-multi-sessions.sql) | 세션 901~1900 시드 (**`loadtest/seed/` 로 이동**, #166) |
| [`lock-variant.patch`](./lock-variant.patch) | `after` 판에 쓴 코드 변경 |

> 📌 **코드는 되돌렸다.** `lock-variant.patch` 는 **적용돼 있지 않다** — `git apply` 로 얹어야 `after` 가 재현된다.
> 되돌린 이유는 §5 다. 이 변형은 **측정용**이고, 실제로 채택한다면 형태가 다르다(엔티티를 만들지 않는 `SELECT s.id … FOR UPDATE`).
> 측정에 쓴 코드를 그대로 두면 **«측정 변형»과 «구현안»이 구분되지 않는다.**

> ⚠️ ghz 원본의 `options.data` 는 제거했다 — 입력 페이로드가 통째로 들어가 판당 54MB 였다.
> 나머지 필드(`count`·`rps`·`latencyDistribution`·`histogram`·`details`)는 그대로다.