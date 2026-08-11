# 이 프로젝트를 백엔드 DB 포폴로 만들기 — 로드맵

작성일: 2026-06-05
상태: **분석/추천 (결정 전)** — 기능 선택·착수는 사용자 confirm 후 박제
대상 진로: 백엔드(Spring) 신입. DB 역량 증명이 목표.
연관: [`youtube-coordinate-harvest.md`](./youtube-coordinate-harvest.md), [`report-aggregation.md`](./report-aggregation.md), [`redis-introduction.md`](./redis-introduction.md), [`ai-load-budget.md`](./ai-load-budget.md), `loadtest/ghz/`

---

## 0. 이 문서의 목적

여러 차례 논의(유튜브 좌표 → 대용량 기대 → CRUD와의 차이 → 추천 기능 → 쓰기 축 → 교수님 비전)가 결국 **"이 프로젝트로 DB 포폴을 어떻게 세울까"** 로 수렴했다. 그 결론을 자산/갭/기능 우선순위/측정법으로 정리한다. **결정(어느 기능부터)은 사용자 몫이며, 본 문서는 비교·근거만 제공한다.**

---

## 1. 핵심 원칙 — CRUD가 아니라 DB 엔지니어링

| | CRUD | DB 엔지니어링 |
|---|---|---|
| 본질 | `save()`/`findById()` — 결정 없음 | 순진한 방식이 볼륨/동시성에서 **깨지는 지점을 측정해 고침** |
| 증거 | (없음) | EXPLAIN, p99, 부하 숫자의 **before/after** |

> **기준선**: "순진한 CRUD가 숫자로 깨진 곳 + 고쳐서 좋아진 증거"를 한 곳도 못 가리키면 그건 CRUD고, DB 포폴 주장은 공허하다.

**따라서 모든 기능의 0번 전제 = 합성 볼륨.** 수백 행에선 전부 종이 설계다. (§6)

---

## 2. 영역 구분 — 어디서 Spring이 빛나나

```
폰 카메라 →(프레임마다 base64 POST)→ FastAPI(MediaPipe 분석) →(rep 완성 시 배치 gRPC)→ Spring → MySQL
```

| 단계 | 작업 | 영역 | Spring DB 축 |
|---|---|---|---|
| 운동 중 — 분석 계산 | pose→angle→DTW→sync | **FastAPI** | ✗ (ML/연산, Spring 안 빛남) |
| 운동 중 — 수집/적재 | pose_data 적재, rep 콜백, 세션 갱신 | **Spring** | ✓ **쓰기 축** |
| 운동 후 — 리포트/통계/소셜 | 집계·추이·랭킹·피드 | **Spring** | ✓ **읽기·집계 축** |

- 안 빛나는 건 딱 하나: **분석 계산(FastAPI)**. "운동 중" 전체가 아니다.
- `pose_data` 한 테이블이 **쓰기(운동 중)·읽기(리포트)·운영(보존정책)** 세 축을 관통하는 다리.
- 추천·소셜·코칭은 전부 **Spring 영역** (데이터 중력 + SQL 집계로 충분, FastAPI/Python 불필요).

---

## 3. 현 자산 (이미 갖춘 것)

- 낙관적 락 `exercise_sessions.version` + 충돌 3회 재시도 — 동시성
- 멱등성: `session_feedback_logs` uniqueKey + `INSERT IGNORE`
- 복합 인덱스 `pose_data(session_id, timestamp_sec)`
- write 감축 의도: pose_data "1초 평균" 설계 ([§7 갭](#7-정직하게-짚을-갭))
- rep 단위 **배치 적재** (`SavePoseDataBatch`) — 단건 INSERT 회피
- 리포트 집계 로직 (worst 구간 sliding window, comparison)
- `loadtest/ghz/` — gRPC ceiling(c1~c100) + JDBC ramp/fair 결과 = **쓰기 축 부하 인프라 이미 존재**
- `redis-introduction.md` 의 "측정 전 캐싱은 premature" — production-grade 태도

---

## 4. 기능 후보 — 우선순위

각 후보는 "왜 CRUD 아님 + 측정 지표"를 가진다. **⭐ = 현재 추천 (결정 전)**.

| 순위 | 기능 | DB 기법 | 임팩트 | 차별화 | 비용 |
|---|---|---|---|---|---|
| 1 ⭐ | **활동 피드 팬아웃** (소셜) | fan-out-on-write vs on-read, 소셜그래프 M:N, 알림 fan-out | ★★★ | ★★★ | 중 |
| 2 ⭐ | **pose_data 파티셔닝 + 보존정책** | 시간 파티셔닝, 청크 삭제(락 회피), 아카이빙 | ★★★ | ★★ | 중 |
| 3 | 주간/월간 통계 사전집계 | 배치 집계테이블(materialized 흉내), `@Scheduled` | ★★ | ★★ | 중 |
| 4 | 리더보드 (그룹/전체) | 윈도우 함수, **Redis ZSET**, 배치 갱신 | ★★ | ★★ | 저 |
| 5 | 시계열 피로도·추세 (코칭) | 윈도우 `LAG`/이동평균, baseline 집계, Redis hot-state | ★★ | ★★ | 저 |
| 6 | 연속 운동일(스트릭/잔디) | gaps-and-islands SQL | ★★ | ★★ | 저 |
| 7 | 커서 페이징 (이력/달력) | 커서 vs OFFSET | ★★ | ★ | 저 |
| 8 | 운동 추천 (item 동시출현) | 사전집계 배치 + 캐시, 무거운 집계 쿼리 | ★★ | ★ | 중 |
| 9 | 전문가 연계 (리포트 전달) | RBAC/공유 권한, 상태머신, multi-tenant 집계 | ★★ | ★ | 중 |

### 4-1. 1순위 활동 피드 팬아웃 — 왜 톱픽인가
- "파트너가 하체 운동을 완료했습니다" = **fan-out 문제** (트위터 타임라인). 캡스톤이 거의 못 다룸 → 차별화 최고.
- 활동 1건 → 친구 N명 피드에 N행 = **write amplification**. 사용자가 "차별화 높다"고 한 **쓰기 축과 동일한 문제**.
- "인플루언서(팔로워 多) 활동 시 팬아웃 폭발 → on-write vs on-read 선택" 은 면접관이 즉시 알아보는 서사.
- 필요 신규 테이블(안): `friendships`(self M:N + 상태), `activity_feed`, `notifications`.

### 4-2. 2순위 pose_data 파티셔닝 — 가장 순수한 DB 스토리
- 대부분 캡스톤엔 천연 대용량 테이블이 없음. pose_data는 초당 수십 행 쏟아지는 시계열 → **누적 월 ~1억 행** (§5).
- "1초 평균 감축(이미 함) → 그래도 쌓이면 → 시간 파티셔닝 → 오래된 파티션 아카이브/드롭" = 운영까지 가는 완결 서사.
- ⚠️ **실스키마 반영 시 FK+파티션 비호환 블로커 발견** (MySQL/InnoDB 제약) + 회원 탈퇴 CASCADE 대체 설계 필요 — [`./pose-data-partition-fk-tradeoff.md`](./pose-data-partition-fk-tradeoff.md) (OPEN), [GitHub #41](https://github.com/Shadowfit/init/issues/41)

---

## 5. 볼륨 감각 (쓰기 축 실측 기반)

레이어별로 숫자가 다르다:

| 레이어 | 속도 | 주체 |
|---|---|---|
| 프레임 수집 (폰→FastAPI) | 초당 ~2~10프레임/세션 (base64 HTTP 한계) | FastAPI |
| 좌표 생성 | 초당 ~70~330 랜드마크값/세션 | FastAPI |
| **DB INSERT (Spring→MySQL)** | **rep당 배치 1회(~5~30행), 세션당 ~3~4초에 1배치** | **Spring** |

동시성으로 곱하면 (DB INSERT 기준): 100세션 ≈ 초당 150~800행, 1,000세션 ≈ 초당 1,500~8,000행.

**누적**: 1세션 ~300행 → 1만 사용자×1일 = ~300만 행/일 → **~1억 행/월**. ← 파티셔닝의 무대.

> 핵심: 쓰기 축의 강점은 "초당 QPS가 미쳤다"가 아니라 **"고빈도 이벤트를 배치·감축으로 길들이고, 누적 대용량을 파티셔닝으로 관리"** 다.

---

## 6. 0번 작업 — 합성 볼륨 시드 (모든 기능의 전제)

- 읽기 검증·소셜 팬아웃·추천 콜드스타트 전부 데이터 없으면 무의미.
- 필요: 더미 users / 친구관계 그래프 / exercise_sessions / pose_data(현실적 분포) / 활동.
- **유튜브 좌표는 여기서 기껏해야 "시드를 현실적으로 보이게 하는 샘플" 보조재.** 대용량 본체는 합성 생성기가 만든다 ([youtube-coordinate-harvest.md](./youtube-coordinate-harvest.md) 참조).

---

## 7. 측정 방법론

### 읽기 — 더미 데이터
- 미리 대량 적재 → 쿼리 p99 / EXPLAIN before-after. 상태 정적이라 반복 쉬움.

### 쓰기 — 더미 트래픽 (ghz, 이미 보유)
- `loadtest/ghz/` 로 `SavePoseDataBatch` gRPC에 동시 트래픽 발사.
- 레시피: ① FK 선행 시드(users/sessions) → ② 페이로드(rep 1개분) → ③ concurrency 램프(1→100), session_id 라운드로빈 → ④ 측정 → ⑤ JDBC batch/인덱스/파티션 토글 후 재측정.
- 쓰기 특유 함정: FK 선행, 멱등/유니크 충돌 회피, **매 run 초기화(TRUNCATE/스냅샷)**, 테이블 크기 자체가 변수.

### 합쳐지는 지점 (가장 강한 그림)
```
① bulk seed로 1억 행 적재 → ② 그 위 증분 INSERT p99 측정 → ③ 파티셔닝 전후 비교
```
"1억 행에서 INSERT p99 20ms→200ms 악화 → 파티셔닝하니 20ms 복구" = 읽기 더미데이터 + 쓰기 트래픽을 한 무대에서 합친 서사.

측정 지표: INSERT 처리량(행/초)·배치 p99·HikariCP active/pending·데드락/락 대기·인덱스 수별 쓰기비용.

---

## 8. 교수님 비전 ↔ DB 기능 매핑

제품 가치와 DB 포폴이 **충돌하지 않음** (win-win). 단 가치를 알고리즘/멘트가 아니라 **데이터 파이프라인에 의식적으로 못박을 것.**

> 🔴 **이 표의 1행은 현재 무효다 (2026-08-04 표기).** 아래 "활동 피드 팬아웃(1순위)"은 **§10 에서 폐기됐다.** 표가 갱신되지 않아 폐기된 항목을 가리키고 있다. 이 불일치와 재검토 여부는 [`professor-vision-backend-impact.md`](./professor-vision-backend-impact.md) §1·§7-2 에서 다룬다 — **정정 방향은 아직 미결정**이므로 표는 그대로 두고 표기만 단다.

| 교수님 비전 | DB로 빛나는 부분 | 비전의 어디가 DB 아님 |
|---|---|---|
| ~~파트너십(친구·기록 공유)~~ 🔴 무효 | ~~**활동 피드 팬아웃**(1순위), 그룹 랭킹~~ → §10 에서 폐기 | — |
| AI 코치(응원·피로도·휴식) | 시계열 피로도·추세 집계(5순위), 실시간 hot-state | 응원 **멘트 생성**(LLM), 자세분석(FastAPI) |
| 전문가 연계(위험 리포트 전달) | 권한 모델·상태머신·집계(9순위) | 푸시 전송(FCM 인프라) |

---

## 9. 의식적으로 안 할 것

| 안 함 | 이유 |
|---|---|
| 세 방향(소셜·코칭·전문가) 동시 추진 | 넓게 X 깊게 O. 1~2개만 |
| 추천을 "똑똑한 알고리즘"으로 | ML 변두리 함정. 사전집계 파이프라인일 때만 DB 의미 |
| 좌표 추출/소셜 로직을 FastAPI로 | 데이터 중력·진로상 Spring에 둬야 ([feedback-minimize-python-changes]) |
| read replica/CQRS 풀구현 | 캡스톤 규모 오버엔지니어링 (개념 언급은 OK) |
| 메모 풀텍스트 검색 | 데이터량 적어 약함 |
| 측정 전 캐싱 적용 | premature ([redis-introduction.md] 기조 유지) |

---

## 10. 미결정 항목 (2026-06-12 재조정)

> ⚠️ 이 절은 2026-06-05 초안. 그 뒤 실측·서사 작업으로 **5개 중 4개가 이미 해소**됐다. 아래는 현실 대조 결과.

**해소됨 (문서 동기화):**
- ~~착수 기능: 팬아웃 vs 파티셔닝~~ → **파티셔닝 채택·완료**([`realmysql-experiments.md §4-②d`](../portfolio/realmysql-experiments.md), 06-03: ALTER 96분 / DROP PARTITION 1.8초 / DELETE 대비 **625x** 실측). **팬아웃은 폐기**([`portfolio-narrative.md §4`](../portfolio/portfolio-narrative.md): "혼자 운동 도메인엔 억지 → 헤드라인 제외").
- ~~소셜 도입 여부~~ → **드롭**(팬아웃 폐기와 동반). friendships/activity_feed/notifications 신규 테이블 **안 만듦**.
- ~~볼륨 시드 규모~~ → **1억 행 완료**([`realmysql §3`](../portfolio/realmysql-experiments.md), 06-03: 133,334세션×750행·~11GB, `loadtest/seed/seed_pose_scale.sh`, 커밋 da69056).
- ~~유튜브 좌표를 시드로~~ → **안 씀**. 실제 시드는 더미 JSON `{}`/`_pose_template`(행수·payload 디커플링). 유튜브 추출은 별도 기능([`youtube-coordinate-harvest.md`](./youtube-coordinate-harvest.md))으로 분리, 시드와 무관.

**아직 진짜 미결정:**
- [ ] **"1초 평균 집계"를 AI(FastAPI)에서 할지 / Spring INSERT 직전에 할지** (쓰기 축 첫 갈림길, §7 갭). → 분석+측정 문서: [`pose-ingest-downsampling.md`](./pose-ingest-downsampling.md). **2026-06-12 측정 결과**: 쓰기 천장(~25 RPS)은 행수가 아니라 **HikariCP 풀=10 + 단일세션 rig 아티팩트**로 귀속(버퍼풀 가설 반증). R-sweep로 **배치 비용 고정비용 지배** 확인 → **다운샘플은 천장 해법 아님, 1순위는 풀 사이징**. 다운샘플은 저장·배치 효율 부수 카드로 강등.
  - ~~다음 미결정: 풀 10→20/30 재측정~~ → **2026-07-25 완료**([`pose-ingest-downsampling.md §5-1(7)(8)`](./pose-ingest-downsampling.md)). AWS EC2 임시 인스턴스 2대(DB 전용+백엔드/ghz 분리)로 실측 — **로컬 결론이 반전**: 분리 배포에서는 고부하(c≥50)에서 풀=30이 풀=10 대비 확실히 우세, c=100은 풀=10이 붕괴(47% 타임아웃). "풀 무용"은 로컬 동거 환경 종속 결론이었음. 이어서 pool=15·20을 c=100 기준 추가 실측해 cliff를 10~15 사이로 좁힘 — **15부터 20/30과 동급이라 실측 스위트스폿은 ~15**(이론 공식 ≈5보다 3배 큼). 인프라는 실측 후 삭제.
  - **다운샘플도 실제 착수 완료**(2026-07-25, PR #53): `PoseDataService.savePoseDataBatch`에 R≈5 대표추출(윈도우별 sync_rate 최저 프레임만 저장) 반영, HikariCP `maximum-pool-size`도 15로 변경. "1초 평균 집계" 위치(AI vs Spring) 자체는 위치 B(Spring)로 확정 반영된 상태 — 남은 건 pool=11~14 정밀 cliff 위치, 리포트 해상도 SLA 도메인 확인 정도.
  - 🔴 **2026-08-08 격자 재측정 — 위 두 줄의 관계가 뒤집혔다**([`pose-ingest-downsampling.md §5-1(9)`](./pose-ingest-downsampling.md), 원본 [`loadtest/results/pool-cliff-2026-08-08/`](../../loadtest/results/pool-cliff-2026-08-08/)). EC2 3대(obs 분리)로 c 10~100 × pool 5·20 을 재측정하니 **초당 ~205건 수준에서 절벽이 없다** — 풀을 4배 줄여도 ~205 RPS 고정, 실패 0. `pool=20` 은 20개 중 2~3개만 쓰고(대기 0) `pool=5` 는 포화인데(95 대기) RPS 가 같다 → **풀은 병목이 아니다.** 원인의 한 축은 **바로 위 줄의 다운샘플**이고, `DOWNSAMPLE_WINDOW` 를 1 로 되돌린 대조군이 RPS 1.7배·p99 4.9배 차이로 직접 증명한다. ⚠️ **초판이 여기에 적었던 *"병목을 백엔드 CPU 로 옮겼다(백엔드 p90 128% vs MySQL 53%)"* 는 같은 날 리뷰에서 근거 없음으로 철회**됐다(MySQL 지표 미수집, 백엔드 CPU 는 0.51~0.56). ✅ **~205 천장의 정체는 같은 날 저녁에 규명됐다 — 커밋 `fsync`** ([`loadtest/results/ceiling-fsync-2026-08-08/`](../../loadtest/results/ceiling-fsync-2026-08-08/)). 내구성 설정만 바꿔 **231.6 → 803.1 RPS (3.47배)**, binlog 동기화만 풀어도 +78%. 1순위 용의자로 적었던 «부하기 커넥션 1개» 는 반증됐다(`--connections` 1→16 에서 230→211). 🔴 **단 채택하지 않는다** — DAU 1,000 가정에서 231 RPS 는 한참 위라 안 아픈 것을 고치며 데이터 안전을 파는 셈이다.
    > 🔴 **2026-08-09 재정정 — 이 «절벽이 없다» 도 조건부였다** ([4차](../../loadtest/results/commit-count-2026-08-09/), [#166](https://github.com/Shadowfit/init/issues/166)). 위 재측정은 **모든 요청이 한 세션으로 가는** 페이로드였고, 그 조건에서는 fsync 가 천장이라 풀이 가려져 있었다. 다세션으로 재보니 **`pool=5` 가 plateau 의 69%** 로 **절벽이 다시 나타난다.** 즉 «질문 소멸» 이 아니라 **질문이 되살아났고**, 이번엔 답까지 있다 — 10 부터 plateau. 그리고 아래 *"살아남은 레버는 다운샘플 하나"* 도 좁혀야 한다: **풀은 여전히 레버다.**

    **즉 §5-1(9) 는 이 로드맵의 서사를 한 단계 바꾼다.** 2026-06-12 판정(*"다운샘플은 천장 해법 아님, 1순위는 풀 사이징"*)이 07-25 에 절반 뒤집혔고(분리 배포에서는 풀이 레버), 08-08 에 **완전히 역전됐다** — 지금 코드에서 살아남은 레버는 **다운샘플 하나**이고, 그것이 풀 사이징을 무의미하게 만든 당사자다. `pool=11~14 정밀 위치`는 해소가 아니라 **질문 소멸**(좁힐 절벽이 없다).

> 참고: 보강 축(outbox·관측성·회복탄력성)의 착수 순서는 별도 미결정 — [`portfolio-narrative.md §7`](../portfolio/portfolio-narrative.md), [`outbox-reliable-messaging.md`](./outbox-reliable-messaging.md).

---

## 11. 정직한 포지셔닝 메모

- 모든 데이터는 합성 → "**부하 테스트 기반 검증**"으로 포지셔닝. 실트래픽 척하지 말 것.
- JSON 컬럼(`joint_coordinates`) 방어 논리 준비: "조회가 통째 읽기 + 33관절 분해 시 행수 33배 → 합리적 반정규화". 면접 공격 대비.
- 신입 정직 포지셔닝 유지 ([feedback-industry-level-standard]).

### 11-1. MySQL vs PostgreSQL → **[`./mysql-vs-postgresql.md`](./mysql-vs-postgresql.md) 로 이관** (2026-08-11)

여기 있던 «엔진 우위 주장 3개 반증» 은 그대로 유효하고, 전문은 위 문서 §2 에 있다.

이관한 이유: 초판(2026-07-05)의 결론이 *"MySQL 을 유지하는 진짜 이유는 이미 만든 실측 자산 + 채용 시그널"* 이었는데,
그건 **"자산이 없었으면 뭘 골랐겠나" 에 답이 없다.** 새 문서는 자산을 빼고 다시 세운다 —
근거가 «이미 재놨으니까» 에서 **«전환 비용 + 운영 여력»** 으로 바뀐다. 결론(MySQL 유지)은 동일.

세 줄 요약:

- 기술적으로는 무승부. **엔진 자체의 기술적 필연성을 면접에서 주장하지 말 것.**
- greenfield 였다면 PostgreSQL. 지금 조건(운영자 1명·마감·도는 시연)에서는 MySQL.
- 진짜 갈림길은 «MySQL이냐 PG냐» 가 아니라 **«DB 를 박스 밖 매니지드로 뺄 거냐»** 다.

---

## 결정 로그
- 2026-06-05: 로드맵 초안 작성. 기능 9개 후보·우선순위·측정법 정리. **착수 기능 미결정** (§10).
- 2026-06-12: §10 재조정. 초안 미결정 5개 중 **4개 해소 반영**(파티셔닝 채택·완료, 팬아웃·소셜 폐기, 1억 시드 완료, 유튜브 좌표 미사용). 남은 진짜 미결정 = **"1초 평균 집계" 위치(AI vs Spring)** 1개. 새 결정 아닌 *기존 사실·서사 동기화*.
- 2026-07-05: §11-1 추가. "워크로드가 MySQL에 맞다"는 주장 3개(클러스터드 인덱스·파티션 DROP·off-page)를 자체 실측/RDBMS 일반 원리로 재검토 → 전부 과장 판명, 정정. MySQL 유지 근거를 기술 우위가 아닌 실측 자산+채용 시그널로 재정리. 새 결정 아닌 *기존 주장 정정*.
