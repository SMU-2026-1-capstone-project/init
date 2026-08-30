# BE-07(패턴 분석) 응답시간 실측 — 로컬 (2026-08-30)

설계: [`pattern-analysis-implementation.md`](../../../docs/decisions/pattern-analysis-implementation.md) §3 (세션9)
선행: [`report-query-explain-be07-2026-08-30`](../report-query-explain-be07-2026-08-30/README.md) — 세션5의 EXPLAIN/`Handler_read_next` 실측(행 수 관점)
방법: `seed_bulk_account.sql`로 만든 대량 계정 + `gen_pattern_seed.py`(세션6)의 소량 계정, `curl -w "%{time_total}"` 콜드 1회 버림 → 5회 측정, 중앙값 인용

---

## 0. 한 줄

**16세션 계정과 2,000세션 계정의 응답시간이 사실상 같다** — 세션5가 "이 두 쿼리는 계정 전체 이력이 아니라 창 안의 행만 읽는다"고 EXPLAIN으로 확인한 것을, 응답시간 쪽에서도 그대로 뒷받침한다.

🔴 이건 **로컬 도커 단일 요청 실측**이다. 부하 아래(동시 요청) p99가 아니고, HTTP+JWT 인증+Spring 오버헤드가 다 포함된 end-to-end 값이라 순수 DB 시간이 아니다. n=5, 워밍업 1회뿐 — 통계적으로 얇다.

---

## 1. 대상 계정

| 계정 | 세션 수 | 만든 방법 |
|---|--:|---|
| `pattern-seed@test.com` | 16 | [`gen_pattern_seed.py`](../../seed/gen_pattern_seed.py)(세션6) — 화·목·토 편중 |
| `bulk-timing@test.com` | 2,000 | [`seed_bulk_account.sql`](./seed_bulk_account.sql) — 최근 180일에 순수 볼륨 분산(패턴 없음), session5 EXPLAIN 판(`member_id=1`, 1,680세션)과 비슷한 자릿수로 맞춤 |

두 계정 다 `exercise_id=1`(스쿼트), `status='COMPLETED'`.

---

## 2. 결과 — 중앙값(ms), n=5, 콜드 1회 버림

| endpoint | pattern-seed(16세션) | bulk-timing(2,000세션) | 배수 |
|---|--:|--:|--:|
| `/patterns/periodicity` | 44ms | 53ms | 1.2x |
| `/patterns/intensity-trend` | 57ms | 61ms | 1.1x |
| `/patterns/consistency` | 30ms | 35ms | 1.2x |

세션 수가 **125배**(16→2,000) 늘었는데 응답시간은 **1.1~1.2배**밖에 안 늘었다 — 세션5가 실측한 "행 수 = 창 안의 행 수(계정 전체 이력과 무관)"가 응답시간에도 그대로 반영된 모양이다.

원자료(초 단위, 콜드 1회 버림 후 5회):

```
pattern-seed  periodicity:      0.041 0.056 0.044 0.098 0.025  (중앙값 0.044)
pattern-seed  intensity-trend:  0.018 0.069 0.057 0.102 0.036  (중앙값 0.057)
pattern-seed  consistency:      0.028 0.049 0.026 0.030 0.032  (중앙값 0.030)
bulk-timing   periodicity:      0.083 0.039 0.053 0.021 0.064  (중앙값 0.053)
bulk-timing   intensity-trend:  0.109 0.086 0.060 0.047 0.061  (중앙값 0.061)
bulk-timing   consistency:      0.058 0.029 0.061 0.035 0.032  (중앙값 0.035)
```

편차가 큰 것도 눈에 띈다(예: intensity-trend 0.018~0.109, 6배 폭) — 이 로컬 박스가 MySQL+백엔드+AI+obs 스택을 한 호스트(물리 2코어)에 다 띄운 채라([[project_loadtest_env_constraint]]) 단일 요청 타이밍이 이웃 컨테이너 스케줄링에 흔들린다. **절대 ms 값은 이 환경 밖으로 못 들고 나간다** — 여기서 확정하는 건 "16세션과 2,000세션의 배수가 거의 1"이라는 상대적 사실뿐이다.

---

## 3. 이 판이 확정한 것과 안 한 것

- ✅ **세션5(EXPLAIN, 행 수)와 세션9(응답시간)가 같은 결론을 가리킨다** — 계정 전체 이력이 아니라 창 안의 행만 읽는 쿼리 설계가 실제 응답시간에도 "계정이 커져도 안 느려짐"으로 나타남
- 🔴 **부하(동시 요청) 아래 p99는 미측정** — 이 판은 순차 단일 요청뿐이다. 다른 DB 기능들의 ghz/k6 부하판과 같은 급으로 인용하면 안 된다
- 🔴 **n=5는 통계적으로 얇다** — 이 프로젝트의 다른 실측([[feedback_measure_design_needs_repeats]])이 요구하는 반복·randomize 설계에 못 미친다. "배수가 1에 가깝다"는 방향성 확인이지, 정밀한 배수 확정이 아니다
- 🔴 **절대 ms는 이 로컬 박스([[project_loadtest_env_constraint]]) 밖에서 무의미** — 스쿼트 exercise_id 하나, 계정 2개, 로컬 도커 단일 요청이라는 조건에서만 성립

---

## 4. 다음 수

BE-07(패턴 분석) 세션1~9는 이 문서로 종료. 부하 아래 p99·동시 요청 실측은 범위 밖(원 계획에 없던 항목) — 필요해지면 별도 이슈로 분리.
