# 방학~2학기 실행 plan — 시간 배분 + 포폴 깊이

작성: 2026-06-02
범위: 프로젝트 기간 2026-03~10 중 **남은 5개월(6~10월)**의 시간 배분과 **포폴 깊이 작업**을 월별로 배치. AI(Claude) 사용 전제.
관계: 이 문서 = **시간배분 + 포폴 깊이 오버레이**. [`24-semester2-plan.md`](./24-semester2-plan.md) = **기능 딜리버리**(BE-05~09·Goal·추천·발표). **둘은 동시 진행, 중복 아님.**
연관: [`../portfolio/db-deep-dive.md`](../portfolio/db-deep-dive.md), [`../portfolio/problem-solving-log.md`](../portfolio/problem-solving-log.md), [`25-portfolio-strategy.md`](./25-portfolio-strategy.md), [`../decisions/load-test-strategy.md`](../decisions/load-test-strategy.md)

> 계획·추천 문서. 결정 마크는 사용자 confirm 후에만. 진척에 따라 조정.

---

## 0. 핵심 결론 (먼저)

**2학기는 이미 꽉 찼다 → 포폴 깊이는 방학(6~8월)에 몰아야 한다.**

- [`24-semester2-plan.md §5`](./24-semester2-plan.md): 2학기 기능·운영 작업만 **~101h**, 주당 8h×10주=80h를 **20h 초과**. 사용자 테스트·핫픽스·발표까지 포함.
- 여기에 포폴 깊이(projection·파티셔닝·동시성·부하·CS매핑)를 2학기에 얹으면 **불가능**.
- → **방학 3개월 = 포폴 깊이의 본편.** 2학기엔 기능 딜리버리 + (운영성=포폴 겸용) 작업만.

---

## 1. 시간 배분 원칙 (AI 전제)

AI가 코드를 빨리 짜주면 **순수 구현은 싸지고**, 차별화는 **측정→최적화→판단→글로 증명**으로 이동([`25-doc §36`](./25-portfolio-strategy.md)). "구현 vs 포폴" 이분법 대신 3버킷:

| 버킷 | 정체 | AI 효과 |
|---|---|---|
| ① 기능 구현 | 앱 동작 CRUD·API | 🔽 싸짐. 단 **한 줄씩 이해 필수**(라이브 시그널) |
| ② 깊이 작업 | 측정→최적화→수치 (부하·projection·동시성·파티셔닝) | = **포폴 알맹이** (구현이자 포폴) |
| ③ 포폴 패키징 | case study·CS매핑·면접답변·문서 + **내 AI코드 PR리뷰식 검증** | 🔼 가치↑ |

**배분**:

| | 8개월 전체 | **남은 5개월(6~10월)** — ①거의 끝남 |
|---|---|---|
| ① 기능 구현 | ~35% | **~10%** (잔여·시연·폴리시) |
| ② 깊이 작업 | ~40% | **~55%** |
| ③ 포폴 패키징·면접준비 | ~25% | **~35%** |

> ⚠️ AI 코드를 이해 못 하면 ① 시간 아낀 게 아니라 ③ 빚. 포화 시장 승부처가 라이브 시그널(자기 코드 100% 이해·디버깅·"왜 이렇게?")이라([`25-doc §37`](./25-portfolio-strategy.md)), ③에 **"내 AI 코드 한 줄씩 검증"**이 반드시 포함.

---

## 2. 전체 8개월 조망

| 기간 | 단계 | 주력 버킷 |
|---|---|---|
| 3~5월 | 1학기 MVP (✅ 완료) | ① 기능 구현 + 문제해결 일부 (problem-solving-log #1~#9) |
| **6~8월 (방학)** | **포폴 깊이 본편** | ② 55% + ③ 35% |
| 9~10월 (2학기) | 기능 딜리버리(24) + 운영(겸 포폴) + 발표 | ① + ②(운영성) + ③(면접준비) |

---

## 3. 방학 월별 plan (6~8월) — 포폴 깊이 본편

### 6월 — 읽기 최적화(헤드라인) + 동시성 개발

| 작업 | 버킷 | 산출 |
|---|---|---|
| **projection** — `ReportService` JSON blob 헛로드 → 3컬럼 DTO. before/after 측정 | ② | 🔴 헤드라인: payload 3MB→0.05MB, 응답 X→Y ms |
| **일일 집계 lost-update** — `DailyLog.updateStats()` 배선 → 동시 종료 경합 → 원자 UPDATE/락 | ② | 🟠 동시성 카드 |
| **report 생성 멱등성** — session_id 유니크/upsert | ② | 🟠 정합성 카드 |
| 측정 방법론 준수 (워밍업 통제·동일환경) | ② | 신뢰성 |

### 7월 — 시계열 운영 + 부하 마무리 + 캐싱

| 작업 | 버킷 | 산출 |
|---|---|---|
| **파티셔닝 + TTL/DROP PARTITION** 설계+구현 (월 Range) | ② | 시계열 운영 스토리 |
| **Redis 캐싱** (불변 리포트 cache-aside + stampede 방지) | ② | 단골 시그널 |
| **precompute-on-write** — worst 구간 세션종료 시 1회 계산 | ② | denormalization |
| 부하 테스트 마무리 — R 실측(팀원 `squat.mp4` 확보 시) / 천장 재측정(머신 분리) | ② | 수치 확정 |
| case study 초안 (`docs/portfolio/`) | ③ | 진화기 골격 |

### 8월 — CS 매핑 + 면접 준비 + 2학기 선제 작업

| 작업 | 버킷 | 산출 |
|---|---|---|
| **CS 50토픽 × 코드 매핑** (MVCC·격리수준·낙관락 등 3단 답변) | ③ | CS 노트 |
| **면접 3단 답변 정련** (30초/2분/10분) — problem-solving 카드 전부 | ③ | 면접 준비 |
| **내 AI 코드 전수 검증** (PR리뷰식, "왜 이렇게?" 셀프질문) | ③ | 라이브 시그널 |
| 2학기 선제 — BE-05/06 머리 떼기 (2학기 용량 초과 완화) | ① | 2학기 숨통 |

---

## 4. 2학기 오버레이 (9~10월)

> 2학기 주력은 [`24-semester2-plan.md`](./24-semester2-plan.md) 기능 딜리버리. 여기선 **포폴 관점 오버레이만**.

| 기간 | 24의 기능 작업 | 포폴 오버레이 (겸용) |
|---|---|---|
| 9월 | BE-05~08, Goal·패턴·추천, 사용자 테스트 1차 | **SLO 정의 + Resilience4j** (운영성=포폴 겸용) / 실데이터로 측정 갱신 |
| 10월 | 2차 테스트·발표·심사 | **면접 준비 마감** + 트러블 **git blame 검증**(본인 작업 분리) + 발표에 깊이 스토리 반영 |

> 핵심: 2학기엔 **새 깊이 작업을 벌이지 말 것**. 9~10월 깊이는 "운영성(SLO·Resilience4j)"처럼 발표·기능과 겸용되는 것만. 순수 깊이(projection·파티셔닝)는 방학에 끝나 있어야 함.

---

## 5. 산출물 체크리스트

> 🔄 **2026-08-08 대조.** 방학이 2/3 지난 시점이라 계획과 실제를 나란히 적는다. **6월분은 다 됐고, 7월분은 절반이 다른 일로 교체됐고, 8월분(③ 버킷)이 밀렸다.**

| 산출물 | 계획 | **2026-08-08 실제** | 시점 |
|---|:--:|---|---|
| [`portfolio/db-deep-dive.md`](../portfolio/db-deep-dive.md) | ✅ | ✅ | — |
| [`portfolio/problem-solving-log.md`](../portfolio/problem-solving-log.md) | ✅ | ✅ | — |
| projection 수치 카드 (before/after) | ⬜ | ✅ **완료** — payload −98.7%([`portfolio-narrative.md`](../portfolio/portfolio-narrative.md) §2 읽기축) | 6월 |
| 동시성 카드 2개 (일일집계·report 멱등성) | ⬜ | ✅ **완료** — 낙관락·멱등 수신·MVCC 재현까지 | 6월 |
| 파티셔닝+TTL 설계 문서 | ⬜ | ✅ **완료** — DROP PARTITION, DELETE 대비 625x | 7월 |
| case study (GET /reports 진화기) | ⬜ | ⬜ **없다** — `docs/portfolio/` 에 해당 문서 없음. 재료는 흩어져 있다(projection·인덱스·keyset·버퍼풀) | 7~8월 |
| CS 50토픽 코드 매핑 | ⬜ | ⬜ **없다** — 계획 문서 2곳에서만 언급된다 | 8월 |
| 면접 3단 답변 세트 | ⬜ | 🟨 **부분** — [`portfolio/interview-qa-kandl.md`](../portfolio/interview-qa-kandl.md) 가 있으나 **특정 회사 면접(2026-07-21) 대비용**이다. 30초/2분/10분 3단 구조의 범용 세트는 아니다 | 8월~10월 |
| 트러블 git blame 검증 | ⬜ | ⬜ | 10월 |

### 5-1. 🔴 계획에 없던 일이 7~8월을 채웠다

7월 계획 5줄 중 **Redis 캐싱과 R 실측 2건이 안 됐고**, 그 자리에 계획에 없던 작업이 들어왔다.

| 계획했으나 안 된 것 | 상태 |
|---|---|
| **Redis 캐싱**(cache-aside + stampede 방지) | ❌ **미도입.** `build.gradle`·`application.yml` 에 Redis 없음. 2026-08-08 에 *"어디에 넣을지"* 를 비교하는 [결정 문서](../decisions/redis-adoption.md)가 **이제 막 작성됐고 결정 미확정** — 즉 7월에 「구현」으로 잡았던 것이 8월에 「분기점」으로 되돌아갔다 |
| **R 실측 / 천장 재측정** | 🟨 천장은 **두 번 실측**했다(EC2 2대·3대). ⚠️ 그런데 **R 실측은 여전히 없다** — 팀원 `squat.mp4` 미확보 조건이 안 풀렸고, 그래서 부하 결과를 *"사용자 몇 명"* 으로 번역할 수 없다([`../decisions/load-test-strategy.md`](../decisions/load-test-strategy.md) §7) |

| 계획에 없었는데 한 것 | 포폴 버킷 |
|---|---|
| **outbox at-least-once**(유실 0 실측) | ② — 신뢰성 카드. 계획의 «동시성»보다 상위 서사 |
| **관측성 2차** — correlation id + 지표 9종 + Prometheus·Grafana 시계열 | ② — 계획은 이걸 **9월(2학기 오버레이)** 로 잡았는데 8월에 끝났다 |
| gRPC deadline + 서킷브레이커 | ② — 같은 이유로 9월 항목이 앞당겨졌다 |
| **풀 사이징 실측 2회 + 병목 이동 증명** | ② — 계획엔 «천장 재측정» 한 줄뿐이었는데 **자기 처방이 자기 근거를 무효화한 서사**로 커졌다 |
| Flyway · CD 앞단 · 인덱스 통합 · 관리자 API 3종 | ①/② |

> 📌 **읽는 방식이 중요하다.** 이건 «계획 실패»가 아니라 **버킷 ②의 내용이 «DB 깊이» 에서 «신뢰성·운영» 으로 이동한 것**이다. 그리고 그 이동은 [`../decisions/portfolio-benchmark.md`](../decisions/portfolio-benchmark.md)·[`db-portfolio-roadmap.md`](../decisions/db-portfolio-roadmap.md) 의 방향과 맞다.
>
> 🔴 **다만 밀린 것이 전부 ③ 버킷(글·면접 준비)이라는 점은 그냥 밀린 것이다.** 이 문서 §2 가 *"AI 코드를 이해 못 하면 ① 시간 아낀 게 아니라 ③ 빚"* 이라고 적고, §6 리스크에 *"③에 전수 검증 시간 사수(8월)"* 를 넣어뒀다. 전수 검증은 [`29-ai-code-verification.md`](./29-ai-code-verification.md) 로 **착수했으나 상태가 «진행 중»** 이고, CS 매핑·case study 는 시작되지 않았다. **방학이 3주 남았다.**

---

## 6. 리스크 / 조정

| 리스크 | 영향 | 대응 |
|---|---|---|
| **R 실측 막힘** (대표 영상 부재, [`load-test §7`](../decisions/load-test-strategy.md)) | projection 시딩량 정밀도 | R≈25 추정으로 진행, 팀원 `squat.mp4` 확보 시 보정 |
| 방학 깊이 작업 지연 → 2학기로 밀림 | 2학기 용량(101h) 폭발 | 방학에 ②를 사수, 못 하면 깊이 우선순위(projection>동시성>파티셔닝) 컷 |
| throughput/QPS 스토리 억지 유혹 | 면접 역풍 | DAU 작음 인정([`§4.2`](../decisions/load-test-strategy.md)), 데이터량·정합성 축으로 |
| AI 코드 미이해 | 라이브 질문 붕괴 | ③에 전수 검증 시간 사수 (8월) |

---

## 7. 관련 문서
- [`24-semester2-plan.md`](./24-semester2-plan.md) — 2학기 기능 딜리버리 (주차별, ~101h)
- [`25-portfolio-strategy.md`](./25-portfolio-strategy.md) — 진로 전략 회고
- [`../portfolio/db-deep-dive.md`](../portfolio/db-deep-dive.md) — DB 깊이
- [`../portfolio/problem-solving-log.md`](../portfolio/problem-solving-log.md) — 문제해결 카드
- [`../decisions/load-test-strategy.md`](../decisions/load-test-strategy.md) — 부하 테스트 전략·측정
