# Decision: 부하 테스트 전략 — 3-tier(프론트·AI·백엔드)에서 어디를 어떻게 측정하는가

상태: **IN PROGRESS — 전략 분석 완료, 실행 결정 확정(2026-05-31 결정 1: DAU 1,000 / ②→⑤ 순차 / ghz+Locust). 스크립트·측정 착수 단계. R 값·SLO 수치는 baseline 실측 후 확정**
작성: 2026-05-31
배경: 포폴 깊이 트랙으로 "한 API 성능 최적화(A)" + "gRPC 결합(C)" 을 잠정 선택한 뒤, "사용자 수 → 트래픽 예측 → 용량 설계" 를 어떻게 할지 논의. 3-tier 구조에서 부하가 어디에 걸리는지, 백엔드 지원자가 무엇을 측정해야 하는지 정리.
연관: [`./ai-load-budget.md`](./ai-load-budget.md), [`./latency-perception.md`](./latency-perception.md), [`./ai-backend-coupling.md`](./ai-backend-coupling.md) 분기 D, [`../tasks/25-portfolio-strategy.md`](../tasks/25-portfolio-strategy.md)

> 이 문서는 **전략·방법론 분석**. 실제 측정값은 미실시 — §7 에 결과 누적 예정. 결정 ✅ 는 사용자 confirm 후 박제.

---

## 1. 핵심 전제 — 부하는 AI 에 걸린다

3-tier 구조와 부하 흐름:

```
프론트 ──실시간 프레임(base64, 10fps)──> AI서버(FastAPI, MediaPipe)
   │                                          │ CPU bound, 단일 인스턴스, in-memory session_state
   └──REST(로그인·세션·리포트)──> 백엔드(Spring+MySQL) <──gRPC 콜백──┘
```

[`./ai-load-budget.md`](./ai-load-budget.md) §3 측정 분해상 **MediaPipe 추론이 CPU 시간의 95%+** 점유. 프레임당 20~50ms CPU, 4코어 ≈ 동시 6~8세션이 한계. 단일 인스턴스 + `session_state` in-memory 라 수평 확장도 막혀 있음([`./ai-backend-coupling.md`](./ai-backend-coupling.md) 분기 D).

→ **시스템 전체 병목은 AI 추론.** 백엔드(Spring+MySQL)는 이 구조에서 가장 안 터지는 tier.
→ 백엔드의 "부하" 는 throughput(QPS)이 아니라 **누적 데이터량에서의 쿼리 저하** 에서 나옴 (§4).

---

## 2. 원칙 — 지배적 tier 가 나머지를 가리지 못하게 격리한다

한 tier 가 압도적으로 느리면, E2E 통째 측정 시 그 tier 가 먼저 터져 나머지가 부하를 구경도 못 함. 그래서 병목을 안 순간 **각 tier 를 따로 떼서** 측정한다. 이것이 컴포넌트 부하 테스트의 존재 이유.

**Top-down(E2E 먼저) vs Bottom-up(컴포넌트 격리 먼저)**:

| 접근 | 장점 | 단점 |
|------|------|------|
| Top-down (E2E) | 현실적, 진짜 병목 발견 | 셋업 어려움, 깨져도 어느 tier 범인인지 재추적 |
| Bottom-up (격리) | 깔끔, 책임 귀속 명확, tier별 천장 측정 | 상호작용 효과 누락 |

→ **현업 표준 = 격리로 각 tier 천장 측정 → 핵심 경로만 1회 E2E 로 상호작용 검증.**
→ 원칙 2개: (a) 깨질 것 같은 곳부터, (b) 항상 단일 사용자 baseline → ramp-up.

---

## 3. 세 갈래 테스트 설계

### 3.1 ① AI tier 격리 — "1 인스턴스 = 몇 세션?"

- 녹화한 실제 스쿼트 프레임 시퀀스를 리플레이(아무 바이트나 쏘면 MediaPipe 가 자세를 못 찾아 downstream 부하가 안 흐름 — §6 함정).
- 동시 세션 ramp → CPU 포화 지점 측정. 산출물: **인스턴스당 천장** = 수평 확장 단위.
- AI 팀 영역 ([`feedback-minimize-python-changes`] — Python 최소 변경). 백엔드 본인 어필 아님.

### 3.2 ② 백엔드 격리 — MediaPipe 를 건너뛴다 ⭐ (본인 핵심)

- **트릭**: AI 추론을 stub 처리하고, AI 가 쏠 gRPC 콜백을 **합성으로 직접 주입**. MediaPipe 없이 백엔드 throughput·지연만 측정.
- 비싼 의존성(AI)을 mock 하고 나머지를 풀스로틀로 때리는 **표준 격리 기법**.
- 주입 지점·인증·전제: §5.
- 산출물: 백엔드 throughput 천장(훨씬 높음) + 콜백 응답 p99 + 리포트 조회 지연.

### 3.3 ③ 작은 E2E — 합성·상호작용 검증 (1회)

- 전체 천장 = min(각 tier). AI 콜백이 **동기 blocking** 이라([`./ai-load-budget.md`](./ai-load-budget.md) §4.4) 백엔드가 느리면 AI worker 를 막아 AI 를 *더* 느리게 하는 상호작용 효과 확인.
- 용도: "측정으로 병목이 AI 임을 입증 + 백엔드 헤드룸 입증" 의 **시스템 시야 스토리**. 메인 아님, 1회 실험.

---

## 4. 용량 산정 — 트래픽 예측 (정직한 버전)

### 4.1 시장 출시 시 현실 (마케팅비 0 가정)

| 단계 | 비율(피트니스 앱 일반) | 보수적 | 잘 됐을 때 |
|------|------|------|------|
| 누적 다운로드(1년) | — | 500~2,000 | 1만~5만 |
| → MAU | 다운로드의 5~15% | ~150 | ~3,000 |
| → DAU | MAU 의 10~20% | **~30~50** | **~500** |

→ **냉정한 기본값: DAU 30~50.** 운동 앱은 매일 안 하고 이탈률 높아 DAU/MAU 낮음.

### 4.2 DAU 1,000 의 두 얼굴

- **제품 관점**: 작음. 단 마케팅 없는 졸작이 찍으면 실질적 성공 → "성공 시나리오" 목표로 타당.
- **백엔드 부하 관점**: 거의 아무것도 아님.
  ```
  DAU 1,000 × 1.5세션/일 = 1,500세션/일 → 피크 동시 ~67세션
  쓰기 ~3.3 batch/초 (R=25면 ~80 row insert/초), 리포트 읽기 초당 몇 건
  → MySQL 한 대가 콧방귀도 안 뀌는 수준. QPS·동시성 병목 스토리 안 나옴.
  ```

→ **결론: DAU 1,000 으로는 throughput 스토리 못 만듦. "대용량 누적 데이터에서의 쿼리 최적화" 로 포지셔닝해야 방어됨.** throughput 으로 우기면 "DAU 1,000인데 무슨 부하?" 한 방에 털림.

### 4.3 진짜 팔리는 축 = 누적 데이터량 — **단, GET /reports 는 아님 (검증 완료)**

| | QPS 부하 | 누적 데이터량 |
|---|---|---|
| DAU 1,000 에서 | 시시함 | R≈25 추정(§4.5) → 1년 ~8억 행/~800GB |

**⚠️ 2026-05-31 스키마 검증으로 "인덱스 추가 → 850ms→12ms" 가설 폐기.** §4.6 참조.

- 당초 가설: `GET /reports` 가 `pose_data` 에 `session_id` 인덱스 없어 full scan → 850ms.
- 실제: `mysql/schema.sql:86` 에 **`INDEX idx_session_timestamp (session_id, timestamp_sec)` 이미 존재**. 리포트 쿼리(`findBySessionIdOrderByTimestampSecAsc` = `WHERE session_id=? ORDER BY timestamp_sec`)에 **정확히 최적** — lookup + 정렬을 한 인덱스가 커버, full scan·filesort 없음.
- 게다가 쿼리가 **세션 단위**(`WHERE session_id=?`)라 한 세션 행 수(~1,500행)에만 묶임 → **테이블이 8억 행으로 커져도 이 조회는 안 느려짐.** worst 구간도 자바 메모리 슬라이딩 윈도우 O(N).
- → **"누적 데이터량 → GET /reports 저하" 스토리는 성립 안 함.** 살아남는 정직한 스토리는 §4.6.

### 4.4 권고 (잠정, 미confirm)

- **현실 baseline 명시**: DAU ~50 (마케팅 없는 출시 기대값) — 정직함
- **설계 목표**: DAU 1,000 (성공 시 = 다운로드 1.5만 내외) — 헤드룸
- 스토리 축: **QPS 도 아니고, GET /reports 쿼리 속도도 아님** (§4.6)

### 4.5 R — 구조는 코드로 확정, 수치는 추정 (2026-05-31)

[`ai-server/app/api/endpoints/pose.py`](../../ai-server/app/api/endpoints/pose.py) L96·107-116·129 확인:
```python
state.current_rep_frames.append(frame)               # 분석 프레임마다 누적 (가시성 부족분은 L79-86 skip)
pose_data_list = [PoseDataRequest(...) for f in state.current_rep_frames]  # rep 완성 시 전 프레임 전송
...
state.current_rep_frames.clear()                     # 전송 후 버퍼 비움 → batch 1개 = rep 1회 프레임들
```

- **확정 (코드 구조)**: PoseData 1행 = 분석 프레임 1개, rep 단위 batch. 즉 **R ≥ 2, rep당 다중 행 — R=1(rep 요약 1행) 배제.**
- **추정 (수치)**: R = (프론트 POST fps) × (rep 길이) ≈ 10fps × 2~3초 ≈ **20~30행/rep**. 세션당 ~1,500행.
  - rep 길이 ~2~3초: [`./latency-perception.md`](./latency-perception.md) §2 (도메인 상식)
  - POST fps ~10: [`config.py:18`](../../ai-server/app/config.py) `VIDEO_PROCESS_FPS=10` — **단, 이는 전처리 설정이고 실시간 POST 빈도는 코드로 강제 안 됨** ([`./ai-load-budget.md`](./ai-load-budget.md) §4.1). 카메라 최대 30fps(`config.py:17`)로 쏘면 R 이 60~90까지 열려 있음.
- **정확값 확정 방법 = 실측**: 세션 1회 후 `spring_client.py:55-59` 의 `count=%d` 또는 Spring [`PoseDataService`](../../backend/src/main/java/com/shadowfit/service/Exercise/PoseDataService.java) 의 `"포즈 데이터 {}개 저장"` 로그 관찰.
- 각 행 `joint_coordinates` = MediaPipe 33 랜드마크 JSON ~1~3KB.

### 4.6 살아남는 정직한 최적화 스토리 (인덱스 추가 대신)

인덱스가 이미 최적이라, "인덱스 추가" 보다 **더 고급** 스토리로 대체:

1. **Projection — JSON blob 그만 끌어오기** ⭐ (가장 깨끗, R≈25 로 효과 확정)
   - worst 구간 계산은 `syncRate`·`feedbackMessage`·`timestampSec` 3개만 쓰는데, [`ReportService`](../../backend/src/main/java/com/shadowfit/service/Report/ReportService.java) 가 엔티티 전체 로드 → `joint_coordinates`(JSON ~1~3KB)까지 끌어옴.
   - 세션당 ~1,500행 × ~2KB = **~3MB 를 매 조회마다 헛되이 전송·하이드레이션** (3개 스칼라만 필요한데).
   - → projection DTO(3컬럼만 select). "페이로드 3MB → 0.05MB, 응답 X→Y ms" 수치. **측정값이 헤드라인을 정한다** (사전 고정 금지).
2. **Precompute-on-write — worst 구간을 세션 종료 시 1회 계산해 `Report` 에 저장**
   - GET 때마다 pose_data 스캔 대신 단일 행 읽기. denormalization 스토리.
3. **불변 리포트 캐싱 (Redis cache-aside)** — 세션 종료 후 리포트 불변 → 높은 적중률.

→ B(파티셔닝)는 **쿼리 속도가 아니라 스토리지·운영**(~800GB 테이블 백업·ALTER·아카이빙)으로만 정당화. 축이 다름.

### 4.7 보존정책 (TTL) — 무한 성장 시계열의 정답

연 ~8억 행(§4.3) 시계열을 무한 적재하면 안 됨. TTL/보존정책이 정답이며, **왜 안전하게 가능한지**가 핵심.

**(1) pose_data 는 중간 산출물 → TTL 가능**
- pose_data(프레임 단위 관절 좌표)의 유일한 쓰임새 = 리포트 worst 구간 계산. 사용자 직접 노출 데이터 아님.
- **§4.6-2 precompute 를 하면**(worst 구간을 세션 종료 시 계산해 `Report` 에 저장) 원본 pose_data 는 리포트 생성 직후부터 **cold data** → 며칠 후 아무도 안 봄 → TTL 로 제거해도 UX 손실 0.
- **결합 포인트**: precompute(§4.6-2)가 TTL 을 *안전하게* 만든다. precompute 없으면 옛 리포트 재조회 시 원본 필요 → TTL 불가. 둘이 서로 강화.

**(2) 구현은 DELETE 가 아니라 DROP PARTITION**

| 방식 | 문제 |
|------|------|
| `DELETE WHERE created_at < ...` | 8억 행 대량 DELETE = 락·undo log 폭발, 느림 |
| **월 단위 Range 파티셔닝 + `DROP PARTITION`** | 가장 오래된 파티션을 **O(1) 메타데이터 연산**으로 제거. 락 거의 없음 |

→ **파티셔닝(B)의 진짜 가치 = 쿼리 pruning 이 아니라 "값싼 TTL"**. 쿼리는 이미 인덱스로 빠름(§4.3). 이것이 B 를 정당화하는 핵심 reframe.
→ 흐름: `월 단위 파티션 → (선택) 오래된 파티션 S3 아카이빙 → DROP PARTITION`. FK `session → pose_data` ON DELETE CASCADE 라 pose_data 만 제거해도 session·report 보존 (안전).

**(3) 발동 트리거 (지금 구현 아님)**
- DAU 50(현실)에선 불필요 — ~800GB 는 DAU 1,000 설계 목표 시나리오.
- → "**성장 목표 대비 설계 + 트리거 명시**" 가 맞음(다른 항목과 동일 톤). 트리거: pose_data 가 수천만 행 도달 / 단일 테이블 백업·ALTER 가 운영 부담이 될 때.

**(4) 포폴 가치**: 보존정책 + partition-drop 은 신입이 거의 안 하는 주제([`../tasks/25-portfolio-strategy.md`](../tasks/25-portfolio-strategy.md) §2 에서 🔴 어필강도, ⭐⭐⭐ 시너지).

---

## 5. 주입 지점 / 인증 / 전제 (백엔드 격리)

운동 중 데이터 적재 경로:

```
[부하 스크립트] --gRPC SavePoseDataBatch--> ExerciseGrpcService --> PoseDataService --> MySQL
                 (metadata: Authorization: Bearer <internal.api.token>)
```

- **RPC**: `ExerciseService.SavePoseDataBatch(PoseDataBatchRequest)` ([`backend/src/main/proto/exercise.proto`](../../backend/src/main/proto/exercise.proto) L20).
- **인증**: [`InternalAuthInterceptor`](../../backend/src/main/java/com/shadowfit/global/config/InternalAuthInterceptor.java) 가 `Authorization: Bearer <internal.api.token>` 메타데이터 검사. 스크립트가 이 헤더만 붙이면 통과.
- **전제조건**: [`PoseDataService.savePoseDataBatch`](../../backend/src/main/java/com/shadowfit/service/Exercise/PoseDataService.java) 가 `sessionRepository.findById(sessionId)` 로 세션을 먼저 조회 → **세션 row 가 미리 있어야 함** (REST `POST /sessions` 또는 DB 직접 seed).
- **관련 콜백**: `ReportFeedbackBatch`(BT-SET), `CompleteAnalysis`, `StopAnalysis` — 현실적 세션 lifecycle 재현 시 함께.

### 5.1 왜 좋은 테스트 경계인가

AI(MediaPipe)를 안 건드리고 **본인이 소유한 Spring+MySQL 경로만** 정확히 측정. MediaPipe 는 백엔드 병목도 아니고 남의 코드라, 여기서 빼는 게 **책임 경계가 명확한 설계**. 면접: "왜 AI 빼고 측정?" → "병목은 DB 집계지 추론이 아니라, gRPC 콜백 계약 지점에서 부하 격리."

---

## 6. 함정 — E2E 시 MediaPipe 는 진짜 이미지가 있어야 부하가 흐른다

E2E(③)에서 아무 바이트나 쏘면 MediaPipe 가 자세를 못 찾아 → rep 감지 안 됨 → sync_rate·콜백 미발생 → **백엔드로 부하 0**. 빈 껍데기 테스트가 됨.

→ 해결: **실제 스쿼트 세션 프레임 시퀀스 1회 녹화 → base64 저장 → VU 마다 10fps 리플레이.** MediaPipe 가 실제 추론하고 downstream 이 진짜로 흐름.

---

## 7. 핵심 미지수 R — 구조 확정·수치 추정 (§4.5)

PoseData 1행 = 분석 프레임 1개(rep 단위 batch) — **R≥2 코드로 확정, R=1 배제**. 수치는 R≈20~30 추정(프론트 POST fps 미강제라 위로 열림), 세션당 ~1,500행. 정확값은 실측 필요.
→ 또 다른 미지수는 **실제 GET /reports 응답 시간**(projection 전/후) — 측정으로 헤드라인 숫자 확정.

### 7.5 ② 백엔드 격리 실측 결과 (2026-05-31, 로컬)

`ExerciseService.SavePoseDataBatch` gRPC, `batch.json`(session 801, **R=25 합성 데이터**), ghz, target `localhost:6565`. 결과 원본은 `loadtest/ghz/results/`(gitignore). 용어 정의는 [`./load-test-glossary.md`](./load-test-glossary.md).

| 모드 | 동시성 | 표본 | RPS | p50 | p90 | p95 | p99 | avg | slowest | 에러 |
|------|--------|------|-----|-----|-----|-----|-----|-----|---------|------|
| baseline | 1 | 200 | 12.9 | 59.5ms | 98.5ms | 121.6ms | 446.6ms | 77.0ms | 838ms | 0 (OK 200/200) |
| ramp | 5→100 step (10s/step, 210s) | 10,166 | **48.4** | 837ms | 2,426ms | 3,099ms | **4,198ms** | 1,130ms | 8,242ms | **100건 (OK 10,066 / 99.0%)** |

관찰:
- **에러 100건 = 측정 종료 시 in-flight 강제 종료 아티팩트 (원인 규명 완료, 부하 한계 아님)** — ramp 표본 중 `Unavailable`(use of closed network connection) 100건. **원인 추적 결과**(details rawData 의 timestamp 분석, §7.7): 에러 100건이 **측정 종료 직전 ~1~2ms 에 전부 발생**하고 그 전 ~210초간 0건. 즉 동시성/부하 한계가 아니라 ghz 가 `-z 210s` 도달로 종료할 때 그 순간 진행 중이던 동시성 100개 요청이 ghz 커넥션 종료로 잘린 것. before/jdbc 코드 무관·`--concurrency-end=100` 과 정확히 일치(종료 순간 in-flight 수)가 이를 뒷받침. **서버 결함 아님**(앞서 추정한 max-connections/OS 소켓/GC 는 데이터로 기각).
- **지연이 동시성 따라 급증** — baseline(c=1) p99 446ms → ramp(c≤100 집계) p99 **4,198ms**, slowest 8.2s. 부하가 늘면 큐잉으로 꼬리 지연이 수 초대로 폭증. baseline avg 77ms 대비 ramp avg 1,130ms (약 15배).
- **throughput 은 오히려 낮음 (RPS 12.9 → 48.4)** — 동시성을 1→100 으로 100배 올렸는데 처리량은 ~3.7배만 증가. batch 1건이 무겁고(R=25, 33관절×25프레임 JSON 파싱+대량 INSERT) 동시성 증가분이 대부분 **큐 대기(지연)로 흡수**됨. 이미 천장 부근이거나 그 너머에서 포화. → 백엔드 batch 적재 경로가 "안 터지는 tier"라는 §1 전제는 (에러가 부하 아닌 시작 아티팩트로 밝혀졌으므로) **대체로 성립**: 동시성 100 지속 부하에서 에러 0, 단 느려짐(지연 폭증).

### 7.7 에러 100건 원인 규명 (2026-05-31)

§7.5/7.6 에서 모든 ramp 에 고정 재현된 `Unavailable` 100건을 `results/ramp-*.json` 의 `details`(요청별 timestamp·latency·status) 로 추적.

| 측정 | 측정 구간 (210s) | 에러 100건 발생 구간 |
|------|-----------------|---------------------|
| before-fair | 18:47:47.531 ~ 18:51:17.371 | 18:51:17.**3699~3709** (종료 직전 ~1ms 내 전부) |
| jdbc-fair | 18:55:38.890 ~ 18:59:08.797 | 18:59:08.**7957~7969** (종료 직전 ~1.2ms 내 전부) |

**결론: 부하 한계가 아니라 측정 종료 시 in-flight 강제 종료 아티팩트.**
- 에러가 **측정 마지막 ~1~2ms 에 전부** 몰림 → 그 전 ~210초 지속 부하(동시성 100)는 에러 0. 서버 용량 무관.
- **정확히 100건 = ghz `--concurrency-end=100`** = 종료 순간 in-flight(처리 중) 요청 수. ghz 가 `-z 210s` 도달로 종료하며 커넥션을 닫자, 진행 중이던 100개가 `use of closed network connection` 으로 잘림. (에러 latency 86ms~2.8s 로 다양 = 각자 처리 도중 중단된 요청들)
- **코드(JPA/JdbcTemplate) 무관 동일** → batch 개선과 독립.
- 앞서 추정한 원인(서버 gRPC max-connections/keepalive, OS 소켓 한도, GC stall)은 **데이터로 전부 기각**.

함의:
- **포폴 수치에서 이 에러는 측정 노이즈로 제외 가능** — "지속 부하 210초간 에러 0, 종료 시 끊긴 in-flight 100건"으로 설명. 단 보고 시 그대로 노출하면 오해 → 위 분석을 같이 제시.
- 서버는 동시성 100 지속 부하를 **에러 0 으로 버팀**(§1 "백엔드는 가장 안 터지는 tier" 실측 확인). 진짜 약점은 에러가 아니라 **지연**(p99 수 초).
- (측정 개선) ghz `-z` 종료 대신 `-n`(고정 요청수) 사용하거나, 종료 직전 ramp-down 두면 이 100건 사라짐 — 후속 측정 시 적용. 천장 특정용 고정 동시성 측정에도 동일 적용.

⚠️ 미해결 / 주의:
- **이 ramp 수치는 천장 측정으로 미흡** — step 집계라 천장을 못 봄. → §7.8 에서 고정 동시성 스윕 수행했으나 **측정 환경 교란(단일 머신 동거)으로 절대 천장 미확정**(정점 ~64 RPS @ c≈5 잠정).
- **R=25 는 합성 가정값** — 실제 스쿼트 세션 로그로 미확정(§7 본문, §11). 실측 R 로 `batch.json` 재생성 후 재측정해야 시딩량·payload·SLO 수치 확정.
- ramp 이 session 801 에 **~25만 행(OK 10,066 batch × R25) 적재**(side effect). ⑤ projection 시드로 재활용 또는 `DELETE FROM pose_data WHERE session_id=801` 정리 — 사용자 결정 대기(§11).
- SLO("콜백 p99<20ms", §10-1)는 작업량 정의(콜백 1건 vs R=25 batch) 불일치로 직접 비교 불가 → **재정의 필요**. 합격/불합격 판정은 R 실측·SLO 재정의·천장 특정 후.

### 7.6 batch insert 개선 — before/after (2026-05-31)

원인 분석(§7.5 코드 추적): `saveAll` 이 batch 가 아닌 **개별 INSERT N방**. ① JPA `@GeneratedValue(IDENTITY)` 가 Hibernate batch 를 원천 차단, ② batch 설정 부재. 개선:
- **jdbc**: `PoseDataService.savePoseDataBatch` 를 `JdbcTemplate.batchUpdate` multi-row INSERT 로 교체 (`FeedbackLogService` 패턴). IDENTITY 우회, INSERT 25방→1방.
- (참고) **config**: `application.yml` 에 `hibernate.jdbc.batch_size=25` + `rewriteBatchedStatements=true` 만 — IDENTITY 라 Hibernate batch 미발동, 효과 없음(아래 1차 표). JdbcTemplate 이 정석.

#### 공정 측정 (✅ 유효)

두 측정 모두 **동일 절차로 교란변수 통제**: 각 빌드마다 재빌드 → cold 컨테이너 기동 → **warmup 60s(c=20, 결과 폐기)** → 801 리셋 → ramp(5→100 step, 210s). JVM JIT·커넥션풀 워밍업 상태를 맞춤.

| 측정 | RPS | p50 | p95 | p99 | avg | slowest | 에러 |
|------|-----|-----|-----|-----|-----|---------|------|
| before-fair (JPA saveAll) | 23.5 | 2,120ms | 6,195ms | 7,549ms | 2,333ms | 11,545ms | 100 |
| **jdbc-fair (batchUpdate)** | **46.7** | **767ms** | **3,477ms** | **4,784ms** | **1,173ms** | **8,799ms** | 100 |
| 개선 | **+99%** | **−64%** | −44% | **−37%** | −50% | −24% |

→ **JdbcTemplate batch 로 throughput 약 2배, p50 −64%, p99 −37%.** 가설(INSERT 25방→1방으로 throughput↑·꼬리지연↓) 실증. batch INSERT 작동 확인(jdbc-fair 가 801 에 대량 적재).

#### ⚠️ 1차 측정은 무효 (워밍업 미통제 — 방법론 교훈)

| 측정 | RPS | p99 | 비고 |
|------|-----|-----|------|
| before (warm 58분) | 50.0 | 4,608ms | JIT·풀 워밍업 완료 상태 |
| config (cold 직후) | 26.8 | 5,498ms | 재빌드 직후 |
| jdbc (cold 직후) | 32.4 | 8,238ms | 재빌드 직후 |

1차는 before 만 warm(58분 가동), 개선안은 cold(기동 ~20초 후)에서 재 **"개선안이 더 느림"으로 잘못 나옴**. cold JVM 은 초반 인터프리터 모드로 2~5배 느려 ramp 저동시성 구간을 오염시킴 → 측정한 게 "batch 효과"가 아니라 "워밍업 차이". **교훈: JVM 서비스 부하 측정은 워밍업 통제가 필수.**

> 🔴 **이 교훈을 2026-08-08 에 그대로 다시 어겼다.** 풀 사이징 격자 실험에서 스윕 내내 떠 있던 warm 백엔드의 `pool=20`(218.5 RPS)과 방금 재시작한 cold 백엔드의 `pool=10`(168.7)을 **풀 차이로 읽고 "절벽이 있다"고 판단**했다. 위 문단이 이미 적어둔 함정이다.
>
> 더 나쁜 것은 그 다음이다 — 그 잘못된 비교를 근거로 **멀쩡한 v1 스윕 10판 전부를 폐기 판정**했다(재실행 결론은 v1 과 동일했다). 📌 **"차이가 보였다"와 "차이가 있다"는 다르다 — 조건을 한 번에 하나만 바꿨는지 먼저 확인한다.** 상세: [`pool-cliff-vs-concurrency.md`](./pool-cliff-vs-concurrency.md), [결과 README §5](../../loadtest/results/pool-cliff-2026-08-08/README.md).

#### ⚠️ 부하기를 피시험 대상과 같은 박스에 두지 않는다 (2026-08-08 추가)

이 문서는 §2 에서 *"지배적 tier 가 나머지를 가리지 못하게 격리한다"* 를 원칙으로 세웠는데, **부하기(ghz) 자신은 그 원칙의 대상으로 적어두지 않았다.** 그래서 2026-07-25·08-08 두 실험 모두 ghz 를 백엔드와 같은 2 vCPU 인스턴스에서 돌렸다 — 부하기가 피시험 대상과 코어를 다투면 **처리량 천장이 부하기 몫만큼 낮게 찍힌다.**

08-08 에 부하기를 별 인스턴스로 옮겨 재실행한 결과 **결론은 같았다**(196~205 → 201~213 RPS, 양쪽 다 cliff 없음).

> 🔴 **그런데 «그래서 손해가 없었다» 고 넘길 수 없다** (2026-08-08 재검증). 초판은 여기에 *"병목이 마침 다른 곳(백엔드 CPU)이었기 때문"* 이라고 적었는데 **그 근거가 철회됐다** — MySQL 지표는 수집조차 안 됐고 스윕 중 백엔드 `system_cpu` 는 0.51~0.56 으로 포화가 아니었다.
>
> **양쪽 다 ~205 RPS 로 같았다는 사실 자체가 오히려 신호다.** 부하기를 옮겼는데도 천장이 안 움직였다면, 천장을 만든 것은 **박스 공유가 아닌 다른 것**이다. 🔴 초판은 **1순위 용의자를 부하기 자신**으로 적었다 — ghz 결과 14판이 전부 `"connections": 1` 이라, 동시성 c 를 100 으로 줘도 TCP 커넥션 하나에 다중화했을 수 있다고 봤다.
>
> ✅ **검증했고 반증됐다 (2026-08-08 저녁)** — `--connections` 1 → 4 → 16 에서 **230.2 → 208.0 → 210.9 RPS**, 안 움직인다. 부하기 CPU 도 13~20% 로 유휴였다. **진짜 천장은 커밋 `fsync`** 였다(내구성 설정만 바꿔 231.6 → 803.1 RPS). 전문: [`../../loadtest/results/ceiling-fsync-2026-08-08/`](../../loadtest/results/ceiling-fsync-2026-08-08/).
>
> 📌 **이 절에 남길 교훈은 방향이 바뀐다.** *"부하기를 의심하라"* 가 아니라 **"천장이 안 움직이면 부하기·서버·디스크를 하나씩 소거하라"** 다. 실제로 답을 준 것은 **아무것도 CPU 포화가 아니라는 관측**이었다 — 그때부터 «계산»이 아니라 «대기»를 찾기 시작했다.
>
> 📌 이 표는 그래서 **한 줄이 더 필요하다** — *"부하기가 자기 천장을 만들지 않는지 확인한다"*(ghz `--connections` 를 c 에 맞춰 올린 뒤 같은 판을 다시 잰다). 배치를 분리하는 것만으로는 부족했다.

| 역할 | 어디에 | 왜 |
|---|---|---|
| 백엔드 | 자기 인스턴스 | 피시험 대상 |
| DB | 자기 인스턴스 | 코어 공유하면 "박스 병목"과 구분 불가([`pose-ingest-downsampling.md §5-1(5)(7)`](./pose-ingest-downsampling.md)) |
| **부하기(ghz)** | **자기 인스턴스** | 천장을 자기가 깎는다 |
| **관측(Prometheus·Grafana)** | **자기 인스턴스** | 동거하면 "수치냐 그래프냐" 택1이 된다 — 분리하면 **같은 판에서 둘 다** 얻는다([`../../monitoring/README.md`](../../monitoring/README.md)) |

⚠️ 로컬(2물리코어)에서는 이 표를 만족시킬 수 없다. **로컬 결과를 절대 수치로 쓰지 않는 이유가 이것**이고, 그 경우엔 메커니즘·상대·델타만 신뢰한다.

#### 공통 관찰
- **에러 100건은 모든 측정에서 동일** — 코드·JVM 상태와 무관하게 `Unavailable` 100건 고정 재현. §7.7 에서 **측정 종료 시 in-flight 강제 종료 아티팩트로 규명 완료**(서버 결함 아님). batch 개선과 독립.
- 절대 RPS 가 1차 before(50) vs 공정 before-fair(23.5)로 다른 건 측정 시점 머신 부하 차이 — **그래서 같은 절차끼리(before-fair vs jdbc-fair)만 비교해야** 함.

#### 상태
- 코드/설정 변경(`PoseDataService` JdbcTemplate, `application.yml` batch설정)은 **작업트리에 존재, 커밋은 사용자 결정 대기**. jdbc 개선은 효과 입증됨 → 커밋 후보. config 설정은 단독 효과 없으나 JdbcTemplate 와 무해하게 공존(드라이버 레벨 batch 재작성).

---

## 8. 도구

| 대상 | 도구 | 비고 |
|------|------|------|
| ② gRPC 부하 | **ghz** | gRPC용 k6. proto + 데이터 템플릿 + 메타데이터(Bearer) + p50/95/99. Windows OK |
| ① 시딩(대량) | 작은 JVM 시더 / SQL `INSERT ... SELECT` 자가증식 / ghz 고RPS | 순수 bulk 는 SQL 이 최速 |
| ③ E2E(상태 여정) | **Locust** (또는 k6) | 로그인→세션→프레임 루프→리포트, 프레임 리플레이가 Python 이라 쉬움 |
| 관측 | correlation id / 분산 추적 + tier별 자원 모니터 | E2E 에서 tier 귀속 필수 |

---

## 9. 백엔드 엔지니어의 역할 (병목이 내 영역이 아닐 때)

1. **백엔드가 범인 아님 + 콜백 빠른 응답** — 동기 콜백이라 백엔드 느리면 AI worker 막아 AI throughput 깎음. "콜백 p99 < 20ms 보장" = 진짜 기여.
2. **AI 장애로부터 백엔드 보호** — Spring→AI 호출(StartAnalysis 등)에 Circuit Breaker(Resilience4j). AI 죽어도 백엔드 graceful.
3. **병목은 이동한다** — AI 수평 확장 후 DAU 1,000 받으면 *다음* 병목은 MySQL 커넥션·gRPC 콜백 경로 = 백엔드 차례. ②번 격리가 답하는 질문: **"AI 가 충분히 확장됐다 가정 시 백엔드는 버티나?"** → AI 확장 안 기다리고 지금 답 가능.

---

## 10. 권고 실행 순서 (잠정)

1. **SLO 정의** — "리포트 조회 p95 < Xms, 세션당 적재 누락 0, 콜백 p99 < 20ms"
2. **R 값 확인** (§7)
3. **백엔드 단일 baseline** — gRPC batch 1세션 + REST 리포트 1요청 지연 분해 ([`./latency-perception.md`](./latency-perception.md) §10 분해표 활용)
4. **② 백엔드 격리 ramp** — 동시 N세션 합성 콜백 → throughput 천장 + 콜백 p99
5. **시딩 → projection 재현** — pose_data 대량 시딩(DAU 1,000 기준, §4.5 R 로 산정) → `GET /reports` 가 `joint_coordinates` JSON blob 까지 끌어와 세션당 ~3MB 헛로드 → projection DTO(3컬럼 select) → 응답 **X→Y ms** 측정 (A 트랙 간판, §4.6-1).
   - ⚠️ 구 *"인덱스 추가 → 850ms→12ms"* 가설은 **정정 1에서 폐기** (`schema.sql:86` 인덱스 이미 최적, §4.3). 헤드라인은 인덱스가 아니라 **payload 축소**(3MB→0.05MB).
6. **(선택) ③ 작은 E2E** — 병목 귀속 1회 실험 (시스템 시야 스토리). 도구 Locust(프레임 리플레이).

→ 백엔드 포폴 깊이는 ②·⑤ 가 메인, ③ E2E 는 1회 실험. 풀 E2E 를 메인으로 시간 쏟는 건 백엔드 지원자에겐 가성비 낮음(병목이 본인 영역 아님).

---

## 11. 결정 현황 (2026-05-31 결정 1 반영)

| 결정 | 상태 | 영향 |
|------|------|------|
| 목표 DAU | ✅ **1,000 (설계 목표)** — baseline 50 은 §4.4 정직성 narrative 로 병기 유지 | 모든 산정의 출발점 |
| 시작 트랙 | ✅ **② 백엔드 격리 → ⑤ 시딩→projection 순차** | 첫 산출물 |
| 부하 도구 | ✅ **ghz(②) + Locust(③ E2E)** | 학습·셋업 비용 |
| A 트랙 최적화 대상 | ✅ **projection (§4.6-1)** — 트랙 ⑤ 로 확정. precompute/캐시는 후속 옵션 | 헤드라인 스토리 |
| R 값 | 🔵 OPEN — 구조 확정(R≥2)·추정 R≈20~30 (§4.5). ②측정은 R=25 합성값으로 진행(§7.5). **실제 세션 1회 로그로 확정** 필요 | 시딩량 산정 |
| SLO 수치 | 🟡 ② 실측+batch개선+천장스윕 (§7.5~7.8) — **batch 로 throughput +99%·p99 −37%**(공정). 천장은 정점 ~64 RPS(동시성~5) 잠정, 역스케일링·절대값은 측정환경 교란으로 미확정(§7.8). 에러 100건=측정종료 아티팩트 규명(§7.7). SLO "콜백 p99<20ms" 작업량 불일치로 **재정의 필요**. R 실측 미완 | 합격 기준 |
| Resilience4j 도입 시점 | 🔵 OPEN — §9.2, 별도 작업 | 별도 작업 |

---

## 결정 로그

- **2026-05-31 (초안)**: 3-tier 부하 테스트 전략 신설. 부하는 AI 추론에 집중(§1) → 격리 측정 원칙(§2) → 3갈래 설계(§3, AI 격리/백엔드 격리/작은 E2E) 정리. 용량 산정은 정직한 트래픽 예측(§4 — 현실 DAU 50, 설계 목표 1,000, 축은 QPS 아닌 데이터량) 으로 박음. 주입 지점·인증(§5), E2E 프레임 리플레이 함정(§6), 핵심 미지수 R(§7), 도구(§8), 백엔드 역할(§9), 권고 순서(§10), 미결(§11). 실측 미실시 — confirm 후 트랙 선택 + 측정 착수.
- **2026-05-31 (정정 1)**: 스키마·코드 검증으로 두 가지 박제. (1) **"인덱스 추가 → GET /reports 850ms→12ms" 가설 폐기** — `mysql/schema.sql:86` 에 `idx_session_timestamp (session_id, timestamp_sec)` 이미 존재, 쿼리에 최적이며 세션 단위라 테이블 성장에 무관(§4.3). 살아남는 정직한 스토리를 projection / precompute / 캐시로 대체(§4.6). (2) **R** — AI `pose.py` 가 rep 의 전 프레임을 전송 → **구조 확정(R≥2, R=1 배제), 수치는 R≈20~30 추정**(프론트 POST fps 미강제, §4.5). projection 스토리는 R≈25 기준 세션당 ~3MB JSON 불필요 로드. B(파티셔닝)는 쿼리 속도 아닌 스토리지·운영으로 reframe.
- **2026-05-31 (결정 1)**: 사용자 confirm 으로 §11 실행 결정 4건 박제 → status OPEN→IN PROGRESS. (1) **목표 DAU = 1,000(설계 목표)**, baseline 50 narrative 병기 유지(§4.4). (2) **시작 트랙 = ② 백엔드 격리(ghz) → ⑤ 시딩→projection 순차**, 풀 E2E 는 메인 아님. (3) **도구 = ghz(② gRPC) + Locust(③ E2E 프레임 리플레이)**. (4) **A 트랙 최적화 = projection(§4.6-1)** 확정 — precompute/캐시는 후속 옵션. 잔여 OPEN: R 값·SLO 수치(둘 다 baseline 실측 gated), Resilience4j 시점. **§10 step 5 정합 수정** — 폐기된 "인덱스 850→12ms" 문구를 projection(payload 3MB→0.05MB)로 교체.
- **2026-08-08 (추가 2)**: §7.6 에 **부하기·관측 배치 원칙**을 신설하고, 같은 절에 **cold JVM 교훈을 다시 어긴 기록**을 붙였다. 계기는 풀 사이징 격자 재측정([`pool-cliff-vs-concurrency.md`](./pool-cliff-vs-concurrency.md)) — §2 가 *"tier 를 격리한다"* 를 원칙으로 세웠는데 **부하기 자신은 그 대상으로 적혀 있지 않아** 두 실험 연속 ghz 를 백엔드와 동거시켰다. 원칙에 빈칸이 있으면 그 빈칸대로 실행된다. 관측 스택도 같은 표에 넣었다 — 분리하면 로컬의 "수치냐 그래프냐" 택1이 사라진다. ⚠️ **§7 의 R 실측·rep 간격 미결은 여전히 열려 있다** — 그게 없으면 c 를 "동시 사용자 N명"으로 번역할 수 없고, 08-08 실험도 그 한계를 그대로 물려받았다.
- **2026-05-31 (추가 1)**: §4.7 보존정책(TTL) 신설. 연 ~8억 행 무한 성장 대응. pose_data 는 중간 산출물이라 precompute(§4.6-2) 후 TTL 안전 — 둘이 서로 강화. 구현은 대량 DELETE 가 아니라 **월 단위 파티셔닝 + DROP PARTITION**(O(1)) → 파티셔닝(B)의 진짜 가치는 쿼리 pruning 이 아니라 값싼 TTL 로 reframe. (선택)S3 아카이빙. DAU 50 엔 불필요, 성장 목표 대비 설계 + 발동 트리거 명시.

---

## 관련 문서

- [`./ai-load-budget.md`](./ai-load-budget.md) — AI 부하 분해(MediaPipe 95%+), 동시성 시나리오, 동기 콜백 blocking(§4.4)
- [`./latency-perception.md`](./latency-perception.md) — tier별 latency 분해표(§10), 측정 방법(§7)
- [`./load-test-glossary.md`](./load-test-glossary.md) — 용어집(baseline/ramp/smoke, percentile, throughput, SLO, ghz/reflection, R)
- [`./ai-backend-coupling.md`](./ai-backend-coupling.md) — 분기 D(session_state 외부화 = AI 수평 확장 전제), 분기 H2(프론트 직결)
- [`../tasks/25-portfolio-strategy.md`](../tasks/25-portfolio-strategy.md) — 깊이 트랙(A 간판 + C 무기), 수치 개선 패키지
- [`../../backend/src/main/proto/exercise.proto`](../../backend/src/main/proto/exercise.proto) — gRPC 계약(SavePoseDataBatch 등)
- [`../../backend/src/main/java/com/shadowfit/global/config/InternalAuthInterceptor.java`](../../backend/src/main/java/com/shadowfit/global/config/InternalAuthInterceptor.java) — 내부 서비스 인증(Bearer)
- [`../../backend/src/main/java/com/shadowfit/service/Exercise/PoseDataService.java`](../../backend/src/main/java/com/shadowfit/service/Exercise/PoseDataService.java) — 적재 경로, 세션 전제조건, R 측정 지점
