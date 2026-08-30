# 구현 계획: 패턴 분석 API (BE-07)

상태: **완료 — 세션1~9 전부 종료** (2026-08-30)
배경: 2학기 콘텐츠 우선순위 논의에서 백엔드 소재 후보([`trainer-live-monitoring.md`](./trainer-live-monitoring.md), [`multiuser-realtime-sync.md`](./multiuser-realtime-sync.md))와 비교한 결과 **1순위로 추천된 항목** — 기존 DB 쿼리·인덱스 실측 강점([[project_db_portfolio_direction]])과 직접 이어지고, 새 인프라·리스크 없이 착수 가능. 이 문서는 "할지 말지"가 아니라 **"어떻게 할지"**를 다룬다.
연관: `docs/tasks/22-backend-tasks-detail.md` §BE-07(원 스코프 정의), `docs/tasks/24-semester2-plan.md`(Week 7 배치), [[project_synthetic_data_distribution_limit]](이 기능의 핵심 리스크)

---

## 1. 기존에 확정된 스코프 (22-backend-tasks-detail.md 기준)

| Endpoint | 내용 |
|---|---|
| `GET /patterns/periodicity` | 사용자가 주로 운동하는 요일/시간대 |
| `GET /patterns/intensity-trend` | 4주간 평균 syncRate·총 분 추세 |
| `GET /patterns/consistency` | 연속 운동일 수, 빠진 날 수 |

- 알고리즘: **단순 통계**(요일별 평균, 시간대별 분포) — 클러스터링 아님, 이미 결정됨
- 완료 기준: 3 endpoint 응답 + 최소 4주치 데이터가 있는 사용자에서 의미 있는 결과
- 원 추정: 8h+ (뭉뚱그린 단일 값 — 아래에서 세분화)

---

## 2. 🔴 핵심 리스크 — 합성 데이터로는 이 기능을 제대로 검증할 수 없다

[[project_synthetic_data_distribution_limit]]: 이 프로젝트의 기존 부하테스트 rig(`gen_frames.py` 계열)는 **단일 템플릿을 반복 복제**하는 구조라 값 분포가 균일하다. 그런데 패턴 분석의 존재 이유가 정확히 **분포**다 — "특정 요일에 몰려서 운동한다", "최근 4주간 강도가 늘고 있다" 같은 결과는 세션 데이터가 **불균일하게 분포**돼 있어야만 나온다.

→ 기존 부하테스트용 시드 데이터를 그대로 재사용하면 세 endpoint 모두 "고르게 분포함"이라는 재미없는(그리고 검증도 안 되는) 결과만 나온다. **별도의 "패턴이 있는" 합성 시드**가 필요하다 — 예: 특정 요일(화·목·토)에 세션이 몰리고, 최근 2주는 syncRate가 상승 추세인 시드를 의도적으로 만든다. 이건 부하테스트 rig와 목적이 다른 **별도 스크립트**이고, §3의 작업 1개로 반영한다.

---

## 3. 세션별 구현 계획

| 세션 | 작업 | 단독 | Claude 병행 | 마일스톤 |
|---|---|:--:|:--:|---|
| 1 | 엔드포인트·DTO 설계, `PatternAnalysisController` 골격(3 endpoint) | 2h | 1.5h | 3개 endpoint가 빈 응답이라도 200 반환 |
| 2 | `PatternAnalysisService` — periodicity(요일·시간대 그룹핑 집계) | 3h | 2h | 실데이터 없어도 로직 단위 테스트로 검증 |
| 3 | `PatternAnalysisService` — intensity-trend(4주 윈도우, 주 단위 syncRate·시간 집계) | 2.5~3h | 1.5~2h | |
| 4 | `PatternAnalysisService` — consistency(연속일수·결측일 계산, SQL보다 애플리케이션 레벨이 나을 수 있음 — 날짜 갭 계산은 윈도우 함수보다 자바 로직이 더 명확) | 3h | 2h | |
| 5 | `SessionRepository` 시계열 집계 메서드 추가 + **EXPLAIN 검증**(이 프로젝트 관례 — 기존 세션 인덱스로 커버되는지, 새 인덱스 필요한지 판단) | 2.5~3h | 1.5~2h | 쿼리 계획 확인, 풀스캔 여부 판정 |
| 6 | **"패턴이 있는" 합성 시드 스크립트**(§2) — 요일 편중·추세가 있는 4주+ 세션 데이터 생성 | 2~3h | 1.5~2h | 세 endpoint가 "고르게 분포"가 아닌 실제 패턴을 응답으로 냄 |
| 7 | 데이터 부족 시(4주 미만) mock/stub 응답 정책 구현 — 완료 기준에 이미 명시된 요구사항 | 1.5~2h | 1h | 신규 사용자도 500 대신 "데이터 부족" 안내 응답 |
| 8 | 테스트(단위 3종 + 경계 케이스: 0건·4주 미만·정확히 4주) | 3h | 2h | 회귀 고정 |
| 9 | EXPLAIN 결과 + 응답시간 실측 문서화(이 프로젝트의 다른 DB 기능들과 같은 형식, `report-query-explain-2026-08-19` 참고) | 2h | 1.5h | 정량적 근거를 가진 포폴 카드 완성 |
| **합계** | | **≈21.5~24h** | **≈14.5~16.5h** | |

**순서 규칙**: 1은 선행, 2·3·4는 서로 독립(병렬 가능), 5는 2~4가 쿼리 형태를 확정한 뒤, 6은 2~4의 집계 로직이 뭘 필요로 하는지 알아야 하므로 그 이후, 7·8은 6 이후, 9는 마지막.

**Claude 병행 기준 주당 8h 가정 시 약 2주** — 트레이너 모니터링(17~19h)보다 작고, 다중사용자 동기화(27~35h)보다 훨씬 작다.

---

## 4. 왜 원 추정(8h+)보다 커 보이는가

원 스코프 문서(`22-backend-tasks-detail.md`)의 "8h+"는 3 endpoint의 순수 구현만 잡은 값이다. 여기서 늘어난 부분은:
- **§2의 합성 데이터 문제**(+2~3h) — 원 문서에 없던, 이 문서가 새로 짚은 리스크
- **EXPLAIN 검증 + 실측 문서화**(+4~5h) — 이 프로젝트의 다른 모든 DB 기능이 거쳐온 절차를 동일하게 적용한 것

이 두 가지를 빼면 원래 추정(8h+)과 거의 맞아떨어진다 — 즉 **기능 자체가 무거워진 게 아니라, 이 프로젝트 수준의 엄밀함(분포 문제 인지 + 실측 문서화)을 붙였기 때문**이다.

---

## 5. 완료 요약 (세션1~9, 2026-08-30)

| 세션 | 결과 | 커밋/문서 |
|---|---|---|
| 1 | `PatternAnalysisController`·`Service` 골격, DTO 3종 | `100eee7` |
| 2 | periodicity(요일·시간대 집계) | `11590ef` |
| 3 | intensity-trend(주 단위 추세) | `fe990b2` |
| 4 | consistency(스트릭·결측일) | `08745cb` |
| 5 | EXPLAIN 검증 — status 등치 없는 쿼리도 계정 전체 이력을 안 읽음 확인, 새 인덱스 불필요 | [`report-query-explain-be07-2026-08-30`](../../loadtest/results/report-query-explain-be07-2026-08-30/README.md) |
| 6 | "패턴 있는" 합성 시드 스크립트, 실 API로 화·목·토 편중·상승 추세·스트릭 확인 | [`gen_pattern_seed.py`](../../loadtest/seed/gen_pattern_seed.py) |
| 7 | `sufficientData` 응답 필드(가입 4주 기준) | 서비스·DTO·컨트롤러·테스트 |
| 8 | 리포지토리 3쿼리 경계 케이스 테스트 13개 | `SessionRepositoryPatternAnalysisTest` |
| 9 | 응답시간 실측 — 16세션 vs 2,000세션 계정, 배수 1.1~1.2x(세션5 결론과 일치) | [`response-time-be07-2026-08-30`](../../loadtest/results/response-time-be07-2026-08-30/README.md) |

포폴 카드: [`db-deep-dive.md §2-F`](../portfolio/db-deep-dive.md).

§2의 핵심 리스크(합성 데이터 분포 한계)는 세션6의 전용 시드 스크립트로 해소했다 — 기존 부하테스트 rig의 균등분산 데이터가 아니라 화·목·토 편중·4주 상승 추세를 가진 별도 계정으로 세 endpoint를 검증했다.
