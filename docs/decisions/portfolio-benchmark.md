# 유사 포폴 벤치마크 — 채울 키워드 vs 밀 차별점

상태: **리서치 기반 분석 완료 — 무엇을 빌드할지 결정은 사용자 confirm 후**
작성: 2026-06-14
대상: 백엔드(Spring) 신입 포지션. "DB 어필을 강화하려면 뭘 추가하나"에 대해, 유사 포폴이 실제로 뭘 어필하는지 웹 리서치로 정량 확인 → **서류 legibility용으로 채울 단골 키워드**와 **면접 차별용으로 밀 깊이**를 분리.
근거: deep-research(검색 5각도 → 소스 fetch → claim별 3표 적대 검증). 중단 시점 = 검증 부분완료(생존 claim ~60, high-confidence 84). **구조적 결론은 신뢰, 단일 소스 정량치(46배·23초 등)는 참고치.**
연관: [`../portfolio/portfolio-narrative.md`](../portfolio/portfolio-narrative.md), [`../portfolio/db-deep-dive.md`](../portfolio/db-deep-dive.md) §0.3, [`./db-portfolio-roadmap.md`](./db-portfolio-roadmap.md), [`./report-read-path.md`](./report-read-path.md), [`../portfolio/realmysql-experiments.md`](../portfolio/realmysql-experiments.md) §②b, [`./reference-style-and-caching.md`](./reference-style-and-caching.md)

> ✅=리서치로 확증, 🔶=정정/주의, 🟢=차별점 희소 확인, ⬜=계획. **빌드 결정 마크는 사용자 confirm 후에만.**

---

## 0. 한 줄 요약

유사 백엔드 신입 포폴은 **거의 같은 캔에 든 시그널 세트**(선착순 쿠폰 동시성·Redis·인덱스·N+1·부하테스트·keyset)를 어필한다. 내가 **빠진 단골 키워드는 Redis 캐싱·keyset 페이지네이션 둘**(서류 패턴매칭에 "있어야 정상")이고, **이미 더 깊게 가진 차별점은 projection(over-fetch 측정)·정직한 실재/잠재 분류**(면접 차별). **단 두 가지 정정**: ① MVCC/동시성은 *토픽 자체가 단골*이라 "프레임+정직"으로만 차별, ② 시계열 깊이는 도메인 내 희소하나 *스케일 천장*을 오버셀 금지.

핵심 분리: **"포폴 단골" ≠ "면접 필수 질문".** 키워드 채우기는 *서류 legibility*, 깊이+정직은 *면접 차별* — 목적이 다른 두 레버.

---

## 1. (a) 채워야 할 단골 키워드 — 둘 다 "있어야 정상" 확정

| 키워드 | 리서치 근거 | 판정 |
|---|---|---|
| **keyset/no-offset 페이지네이션** | 신입 포폴 표준 토픽 반복 등장. OFFSET 3,900만 구간 ≈23초 vs keyset ≈0.5ms(≈46배), 100만 행 page 5000 OFFSET ≈3.2초 vs 커서 ≈0.003초 일정. 데이터 변경 시 OFFSET 중복/누락 버그를 keyset가 회피 | ✅ **should-have. 없으면 감점** |
| **Redis 캐싱** | "국밥" 단골(TTL·cache stampede·캐시-DB 정합성·분산락). cache-aside가 표준 패턴, prod 목표 hit rate 95%+ | ✅ **베이스라인** |

**주의(얕음 경계)**: 다수 Redis 글이 **메커니즘만 쓰고 부하검증을 TODO로 남김**. plain TTL 캐시는 얕게 읽힘 → 차별 구간은 **cache stampede(PER 등)·세션 완료 시 캐시 무효화 정합성**. ([`./reference-style-and-caching.md`](./reference-style-and-caching.md)의 "로컬 Caffeine 충분, 수평확장 시 Redis" 추론과 연결)

---

## 2. (b) 밀 차별점 — 희소성 확인 + 정정 2건

| 내 강점 | 리서치 판정 |
|---|---|
| **projection / fat 컬럼 over-fetch −98.7%** | 🟢 **진짜 희소.** 피트니스/신입 포폴 DB는 basic CRUD에서 멈춤(복잡 쿼리 NativeQuery로 때움), fat JSON over-fetch 측정 사례 거의 없음<br>⚠️ 말할 때는 조건과 같이 — **정상 조회는 이 쿼리를 안 탄다**(precompute·폴백 전용), 3컬럼 시절 값 ([조건표](../portfolio/realmysql-experiments.md)) |
| **정직한 실재/잠재 분류 + 절대TPS 아닌 델타** | 🟢 **희소.** 고급 Redis 글조차 부하검증 없이 메커니즘에서 멈춤(TODO) → "정직하게 측정·분류"가 실제로 드뭄 |
| **시계열 적재 깊이(도메인 내)** | 🟢 도메인 내 희소 ⚠️ **단 스케일 천장 주의**(§2.2) |
| **MVCC/lost-update 동시성** | 🔶 **정정**(§2.1) — 토픽 자체가 단골이라 토픽으론 차별 안 됨 |

### 2.1 정정 ① — 동시성은 "토픽"이 아니라 "프레임+정직"으로 차별

MVCC·격리수준·lost-update는 그 자체가 **선착순 쿠폰과 같은 카탈로그된 국밥 토픽**. 차별은 토픽 보유가 아니라:
- **(1) 선착순 쿠폰을 bolt-on 안 하고 도메인(cross-user 세션)에서 유기적으로 나왔다는 점**
- **(2) 발동/미발동(실재/잠재)을 갈라 측정**

→ 면접 멘트: "MVCC 했어요" ❌ / "**남들 선착순 쿠폰 붙일 때 우리는 세션 동시성이 도메인에서 터졌고, 발동/미발동을 갈라 측정했다**" ✅
근거: [`./report-read-path.md`](./report-read-path.md) §5, 메모리 `feedback_state_assumption_design_to_it`(DAU 1,000 가정 교차사용자 동시성).

### 2.2 정정 ② — 시계열은 도메인 내 희소하나 스케일 천장 정직히

리서치가 못 박은 TSDB(TimescaleDB/InfluxDB) 정당화 기준:
- 수십억 행 / 디바이스 1,000개×10초 = **일 8,640만 행 → MySQL 2~3개월 내 붕괴**
- TSDB 네이티브 압축 **90~95% 절감**, 시간범위 쿼리가 범용 RDB의 약점

내 **~750행/세션은 그 임계 한참 아래**, 시계열 담론은 "MySQL+JSON" 진영과 별개. → **"시계열 대용량 처리"로 오버셀 금지.**
정직한 프레임: *"TSDB가 자동화하는 패턴(다운샘플·retention·working-set/버퍼풀)을 임계 이하 규모에서 MySQL로 직접 구현·측정"*.
근거 연결: 메모리 `project_loadtest_env_constraint`(절대 RPS 무의미·델타만), `project_synthetic_data_distribution_limit`.

> 보너스: 시계열 표준 패턴 = **continuous aggregate + 티어드 retention(raw→압축→삭제)** — 이게 정확히 내 **precompute + raw TTL**([`./report-read-path.md`](./report-read-path.md) §6) 아이디어. 면접: "TimescaleDB의 continuous aggregate를 MySQL에서 손으로 한 셈."

---

## 3. 두 레버는 목적이 다름 (핵심 함정)

리서치 추가 발견: **"포폴 단골" ≠ "면접 필수 질문".** 한 면접질문 카탈로그엔 부하테스트·EXPLAIN·keyset·TPS·fetch join이 **질문으로는 안 나옴**(스택/포지션 의존). N+1·Redis·MSA는 설명형 단골 질문으로 등장.

| 레버 | 목적 | 항목 |
|---|---|---|
| **키워드 채우기** | 서류 패턴매칭 통과(legibility) | Redis 캐싱, keyset 페이지네이션 |
| **깊이+정직** | 면접 차별 | projection, 실재/잠재 분류, 유기적 동시성 프레임 |

→ [`./report-read-path.md`](./report-read-path.md) §8의 빌드 후보와 매핑: **precompute(차별·깊이) + Redis·keyset(legibility)** 가 두 레버를 각각 메움.

---

## 4. 미결정 (사용자 confirm 필요)

- [ ] **precompute 착수** — 차별·깊이 축. ②③⑥ 동시 해결 + 주/월 집계([`./report-read-path.md`](./report-read-path.md) §8)
- [ ] **Redis 캐싱 추가** — legibility 키워드. 단 plain TTL 넘어 stampede/무효화 정합성까지
- [ ] **keyset 페이지네이션** — legibility 키워드. 캘린더/히스토리 뷰(④ 복합 인덱스)에 자연스러운 자리
- [x] **시계열 프레임 정정 반영**(2026-07-16) — `portfolio-narrative.md` §4·§5에 "TSDB 자동화 패턴을 임계 이하 MySQL로 측정" + 스케일 천장 정직 박제 반영 완료
- [ ] **동시성 멘트 정정 반영** — "토픽 보유"가 아니라 "유기적+실재/잠재 분류" 서사로

---

## 5. 리서치 신뢰도 메모

- deep-research 중단 시점 = 적대 검증 부분완료. **구조적 결론**(키워드 단골성·도메인 희소성·TSDB 임계)은 다중 소스 교차 → 신뢰.
- **단일 소스 정량치**(OFFSET 46배·23초·압축 90~95%·8,640만 행/일)는 참고치 — 포폴/면접에서 내 수치로 인용 금지, 내 실측만 인용([`../portfolio/realmysql-experiments.md`](../portfolio/realmysql-experiments.md)).
- 풀 인용 리포트 필요 시 deep-research 재개 가능(resume).

---

## 6. 관련 문서
- [`./report-read-path.md`](./report-read-path.md) — 읽기 경로 감사(§6 precompute, §8 빌드 후보)
- [`../portfolio/portfolio-narrative.md`](../portfolio/portfolio-narrative.md) — 서사 구조(시계열·동시성 정정 반영 대상)
- [`../portfolio/db-deep-dive.md`](../portfolio/db-deep-dive.md) §0.3 — "차별점은 문제를 가진 게 아니라 측정한 것"
- [`./reference-style-and-caching.md`](./reference-style-and-caching.md) — 캐싱(로컬 vs Redis) 추론
- [`./db-portfolio-roadmap.md`](./db-portfolio-roadmap.md) — DB 포폴 로드맵
