# 리포트 문장 생성 — LLM 을 어디에 붙일 것인가

작성일: 2026-08-22
상태: **설계 · 미결정 ① 결정됨 (2026-08-22)** — **붙일 자리는 «주간» 으로 확정**(사용자 결정, §12).
      나머지 실행·채택은 여전히 사용자 confirm 후 박제 ([[feedback_user_decides_not_claude]]) — **§12 미결정 5건.**
      🔴 ① 이 주간으로 닫히면서 **⑤(스키마 변경)가 «선택» 에서 «필수» 가 됐다**(§6).
대상: `reports.summary` · `reports.improvement_tips` 가 **자리만 잡고 비어 있다.** 그 칸을 무엇으로,
어디서, 어떤 실패 모드로 채울 것인가. **LLM 을 쓸지 말지가 아니라 «어디에 쓸지» 가 질문이다**
연관: [`./outbox-reliable-messaging.md`](./outbox-reliable-messaging.md)(재사용할 무대) ·
[`./report-read-path.md`](./report-read-path.md) · [`./recommendation-algorithm.md`](./recommendation-algorithm.md)(BE-08) ·
[`./async-pool-backpressure-experiment.md`](./async-pool-backpressure-experiment.md) ·
[`../../loadtest/results/card-b-avg-2026-08-20/README.md`](../../loadtest/results/card-b-avg-2026-08-20/README.md)(§5 의 함정) ·
[[project_korean_only]] · [[feedback_state_assumption_design_to_it]]

---

## 0. 이 문서가 여는 것

「리포트에 AI 요약을 붙일까」에서 출발했는데, 코드를 열어보니 **질문이 바뀌었다.**

`SessionService.java:252` 가 세션 완료 시 **`precomputeReport(session)` 를 이미 부른다.**
`reports` 행은 만들어지고 있고 `detailed_analysis`(worst 구간 + rep 곡선)가 채워진다.

🔴 **`production-signal-checklist.md` 의 「`reports` 쓰기 경로 없음」은 2026-07-05 기준이라 낡았다.**
그 사이 구현됐다. 그 문서를 인용해 「BE-03 는 통째로 미착수」라고 말하면 안 된다.

| 컬럼 | 지금 |
|---|---|
| `detailed_analysis` | ✅ 채워진다 (worst 구간 · rep 곡선) |
| `summary` | ⬜ **빈 칸** — 엔티티 주석은 *"GPT가 요약한 전체 총평"* 인데 **쓰는 코드가 없다** |
| `improvement_tips` | ⬜ **빈 칸** |

즉 **파이프라인이 없는 게 아니라 두 칸이 비어 있다.**

---

## 1. 🔴 트랜잭션 경계 — 이 프로젝트가 이미 한 번 죽은 자리

`V1__baseline.sql:255` 가 사건을 기록해 뒀다:

> `precomputeReport` 가 세션 완료와 **같은 트랜잭션**이라 세션 `COMPLETED` 까지 롤백되면서
> **모든 세션이 `FAILED` 로 수렴했다** ([#66](https://github.com/Shadowfit/init/issues/66), 실제 재현)

원인은 컬럼 하나가 없던 것이었다. **여기에 외부 LLM 호출을 넣으면, Gemini 타임아웃 하나가
사용자의 운동 세션을 실패시킨다.**

「비동기로 뺀다」가 취향이 아니라 **이 프로젝트가 이미 낸 등록금**이다. 이 문서의 모든 안은
**LLM 호출이 세션 완료 트랜잭션 밖에 있어야 한다**를 전제로 한다.

---

## 2. 미결정 ① — **세션 리포트냐 주간 리포트냐**

LLM 이 템플릿을 이기려면 **① 입력이 많고 ② 패턴 해석이 필요하고 ③ 출력이 자연어**여야 한다.

| | 세션 1건 | 주간/월간 |
|---|---|---|
| 입력 | 숫자 **대여섯 개** | 세션 **20~30개**의 추세 |
| 구조 | 완전히 규칙적 | 「무엇이 달라졌나」는 **불규칙** |
| 템플릿으로 되나 | **된다** | 안 된다 (조합이 폭발) |
| LLM 이 더하는 것 | 문장 다듬기 + **그럴듯하게 틀릴 위험** | **해석** |
| 호출 빈도(DAU 1,000) | **일 1,000건** | **일 평균 ~140건** |
| 즉시성 | 운동 직후라 있다 | 없다 → **배치·저부하 시간대 가능** |

🔴 **`ReportType` 에 `WEEKLY`·`MONTHLY` 가 enum 과 DB ENUM 양쪽에 정의돼 있는데 쓰는 코드가
하나도 없다.** `precomputeReport` 는 `SESSION` 만 쓴다. **빈 자리가 이미 스키마에 있다.**

**추천: 주간.** 세션 1건은 템플릿이 이기고, 주간은 호출이 **7분의 1 이하**이며 즉시성이 없어
비용·신뢰성 양쪽에서 유리하다. 그리고 `recommendation-algorithm.md` 가 *"리포트가 거의 순수
GET 이라 데이터를 소비하는 기능이 없다"* 고 적어둔 구멍을 주간 집계가 메운다.

✅ **결정 (2026-08-22, 사용자) — 주간으로 간다.** 위 추천대로다. 이 결정이 닫는 것과 여는 것은
§12 에 적었다. **§8(세션 안을 택하면 달라지는 것)은 이 결정으로 비활성**이 되며, 되돌릴 때를 위한
기록으로만 남긴다.

⚠️ 원문 유지 — **결정은 사용자 몫이다.** 이 문서는 주간을 기준으로 나머지를 전개하되, 세션 안을 택하면
달라지는 자리를 §8 에 따로 적는다.

---

## 3. 경계 — 무엇을 SQL 이 하고 무엇을 LLM 이 하나

**원칙: 숫자는 전부 SQL, LLM 은 문장만. 그리고 그 경계를 코드로 강제한다.**

### SQL 이 내는 것

입력이 **`pose_data` 가 아니라 `reports` 다.** 세션 리포트가 이미 `detailed_analysis` 에
worst 구간·rep 곡선을 갖고 있으므로, 주간은 **그것들의 집계**다. 5GB 짜리 `pose_data` 를
다시 안 읽는다. (이 선택은 카드 A 가 잰 것과 직결된다 — `pose_data` 읽기는 비커버링이라
행당 4페이지를 친다.)

- 세션 수 · 총 rep · 주간 평균 sync_rate · **지난주 대비 델타**
- **rep 위치별 품질 곡선** — 주 전체 `rep_number` 별 평균 → 「몇 번째부터 무너지나」
- **worst 회차 분포** — 세션별 worst 회차를 세어 「어느 회차가 반복해서 약한가」
  🔴 «국면» 이 아니다 — `WorstSectionDto` 에 국면 이름표가 없다(`reason` 은 `"2회차 · 싱크로율 75%"`
  라는 문자열이고 문구도 잠정, [#80](https://github.com/Shadowfit/init/issues/80)). 국면 축은
  [#218](https://github.com/Shadowfit/init/issues/218) 이 선행이다. §13-2 참고
- 연속 운동일 · 요일/시간대 분포 · 이상치 세션

### LLM 이 하는 것 — 딱 셋

| | 내용 |
|---|---|
| ㄱ | **무엇이 달라졌나** — 한국어 2~3문장. **우리가 준 숫자만** 인용 |
| ㄴ | **반복 패턴 지목** — 「rep 4 이후 하락이 3주째」 |
| ㄷ | **다음 주 초점 1개** — 🔴 **규칙 카탈로그에서 «고른» 것을 문장화만.** 자유 생성 금지 |

### LLM 이 절대 안 하는 것

- **계산** (평균·델타·백분율) — SQL 이 미리 낸다
- **없는 사실** — 출력 JSON 스키마에 `cited_metrics` 를 강제하고, 워커가 **출력의 숫자를
  집계값과 대조**한다. 우리가 준 키에 없는 수가 나오면 **폐기하고 폴백.** 이게 환각 방지의
  실무적 형태다
- **의학적 조언**

---

## 4. 미결정 ② — 🔴 «이번 주 평균» 의 정의

카드 B 가 실측으로 세운 함정이 **한 층 위에서 다시 나온다.**

세션 안에서 이미 **프레임 가중 ↔ rep 가중**이 갈렸다(다운샘플 때문에 rep 마다 살아남은 행
수가 다르다 — 실측: 불균일 200/200 갈리고 균일 0/2,002). 주간에는 **세 번째 가중치**가 생긴다:

```
세션 평균의 평균   ≠   주간 전체 rep 평균
(세션마다 rep 수가 다르므로)
```

어느 쪽을 「이번 주 평균」이라 부를지 **정하지 않으면 LLM 이 문장으로 만들 때 그럴듯하게
틀린다.** 그리고 LLM 출력이라 **틀린 걸 알아채기가 더 어렵다.**

**추천: rep 가중**(AI 가 쓰는 것과 같은 기준). 단 **문서·화면·프롬프트에 정의를 같이 박는다.**

---

## 5. 파이프라인 — 아웃박스를 재사용한다, 다만 설정은 그대로 못 쓴다

`outbox_events` 는 **범용으로 지어져 있다** — `aggregate_type`/`event_type`/`payload JSON`,
리스 기반 선점(`PROCESSING` + `lock_expires_at`), 재시도 백오프, 독 메시지(`FAILED`),
`correlation_id`. 새 이벤트 타입 하나 + 발행기 분기 한 줄이면 붙는다.

```
매주 월요일 새벽 스케줄러  →  대상 사용자마다 outbox 행 INSERT
                            (aggregate_type='MEMBER', event_type='GENERATE_WEEKLY_REPORT')
발행기                     →  집계 SQL → 프롬프트 → LLM → 검증 → reports(WEEKLY) UPSERT
실패                       →  재시도, 한도 넘으면 템플릿 문장으로 채우고 FALLBACK 표시
멱등                       →  (member_id, report_type, period_start) 유니크
```

### 🔴 그런데 지금 설정값이 LLM 에 안 맞는다

`OutboxPublisher` 는 **내부 gRPC 호출(데드라인 5초)** 기준으로 잡혀 있다.

| 설정 | 지금 값 | LLM 에 쓰면 |
|---|---|---|
| `lock-timeout-seconds` | **60** | 🔴 주석이 *"batch-size × 응답시간이 tick 소요의 상한"* 이라 적어뒀다. LLM 이 건당 5~15초면 **20건 순차 = 최대 300초** → **리스(60초)를 넘겨 다른 발행기가 회수** → **중복 LLM 호출 = 중복 비용** |
| `batch-size` | **20** | 위와 같은 이유로 낮추거나, 이 이벤트 타입만 별도 발행기로 |
| `max-retry` | **10** | 🔴 **재시도는 비용을 곱한다.** 싼 내부 호출 기준의 10회를 유료 외부 호출에 그대로 쓰면 **최악 10배 청구**다 |
| `max-backoff-seconds` | 300 | 429(레이트리밋)에는 더 길어야 할 수 있다 |

**즉 「아웃박스가 있으니 그냥 얹으면 된다」가 아니다.** 이벤트 타입별 튜닝이 선행이고,
그게 이 설계의 실제 작업 중 하나다.

⚠️ `OutboxPublisher` 는 `@Lazy self` 자기주입을 쓴다 — [#175](https://github.com/Shadowfit/init/issues/175) 가 지목한 세 곳 중
하나다. 여기를 건드리면 그 이슈와 만난다.

### 5-1. 🔴 실측 — 지금 발행기는 타입을 구분하지 않는다 (2026-08-27)

코드 확인 결과다:

- `lockPendingBatch`/`lockStaleProcessingBatch`(`OutboxEventRepository.java:46-71`) **둘 다
  `event_type` 조건이 없다** — `PENDING` 이면 뭐든 같은 배치에 섞여 들어온다
- `batchSize`·`lockTimeoutSeconds`·`maxRetry`·`maxBackoffSeconds`(`OutboxPublisher.java:58-74`)는
  **인스턴스 필드 스칼라 하나씩** — 타입별로 나뉠 자리가 애초에 없다
- `idx_outbox_dispatch (status, next_retry_at)`는 **2026-07-29 실측으로 `status` 단일 선두
  컬럼에 맞춰 튜닝된 인덱스**다(`V1__baseline.sql:356-367`, key_len 7 · filtered 100% 를 얻으려고
  회수용 인덱스를 일부러 지운 자리). 여기에 `AND event_type = ?` 를 얹으면 인덱스가 그 컬럼을
  모르므로 필터링이 인덱스 밖에서 일어나 **그 실측을 무효화한다**
- 테이블 코멘트 자체가 `'...세션 종료 통보(STOP_ANALYSIS) 전달 보장'`(`V1__baseline.sql:368`) —
  스키마는 범용으로 지었다고 §5 에 적었지만 **실제로는 단일 목적으로 굳어 있다**

즉 지금 상태로 `GENERATE_WEEKLY_REPORT` 를 얹으면 이중으로 문제다:

1. **설정이 섞인다** — STOP_ANALYSIS 와 같은 lock-timeout(60초)·같은 max-retry(10)·같은
   backoff 상한(300초) 을 강제로 같이 쓴다. 표에서 이미 지적한 문제 그대로
2. **실행이 섞인다** — `ORDER BY id LIMIT 20` 이므로 STOP_ANALYSIS 가 밀리면 weekly 행이 뒤에서
   굶는다. 반대로 LLM 이 건당 5~15초면, **같은 순차 `dispatchBatch` 루프 안에서 세션 종료
   통보가 그만큼 지연된다** — §1 이 "LLM 호출은 세션 완료 **트랜잭션** 밖" 이라고 막아둔 것과
   같은 형태의 문제가 이번엔 **스레드/tick** 레벨에서 재현되는 것이다

### 5-2. 설계안

**안 A — 이벤트 타입별 발행기를 분리한다**

- 새 클래스(가칭 `ReportOutboxPublisher`) — 자기 `@Scheduled` tick, 자기 폴링 쿼리
  (`event_type = 'GENERATE_WEEKLY_REPORT'` 로 필터), 자기 설정 프리픽스(`outbox.weekly-report.*`)
- 타입 필터링을 인덱스로 받으려면 **추가 인덱스**가 필요하다 — 예:
  `idx_outbox_report_dispatch (event_type, status, next_retry_at)`. 기존
  `idx_outbox_dispatch` 는 그대로 둬 STOP_ANALYSIS 튜닝을 안 건드리는 **추가형(additive)
  마이그레이션**이 된다. 운영 중 DDL 이므로 §6 이 쓸 무중단 DDL 절차를 그대로 재사용할 수 있다
  (다른 테이블이라 §6 자체와는 별개)
- `claimPending`/`claimStale`/`dispatchBatch`/self-injection 골격은 `OutboxPublisher` 와
  거의 동일하므로, 그대로 복제하면 중복이 크다 — **공통 로직을 추상 클래스나 전략 객체로
  뽑는 리팩터가 선행**된다(claim 쿼리·설정값만 하위 클래스/구현체가 갈아끼우는 형태)
- 장점: 설정이 완전히 독립(STOP_ANALYSIS 값을 안 건드리고 LLM 쪽만 조정), tick 이 분리돼
  **한쪽이 느려도 다른 쪽 폴링 주기에 안 물린다**, 장애 격리(한 발행기가 죽어도 다른 하나는 돈다)
- 단점: 클래스 하나 + 인덱스 마이그레이션 하나가 늘어난다

**안 B — 발행기 하나, 설정만 타입별로 나눈다**

- `@Value` 스칼라들을 이벤트 타입별 맵(`Map<OutboxEventType, PublisherConfig>`)으로 바꾸고,
  `dispatchOne` 안에서 `configFor(event.getEventType())` 로 lock-timeout·max-retry·backoff 를 선택
- 그런데 **쿼리 분리 필요성은 안 A 랑 같다** — 타입별로 다른 lock-timeout 을 적용하려면
  `markProcessing` 시 타입별 만료 시각을 계산해야 하고, 배치를 타입별로 나눠 잡으려면 결국
  타입별 쿼리(그리고 그걸 받쳐줄 인덱스)가 필요하다. B 가 A 와 실제로 다른 지점은
  **tick 스케줄과 클래스를 공유한다는 것뿐**이다
- 장점: claim/dispatch/self-injection 골격을 그대로 재사용, 클래스가 하나
- 단점: 같은 스레드·같은 tick 안에서 순차 처리하므로 **한 타입이 오래 걸리면 다른 타입도
  지연된다** — §5-1 이 지적한 "실행이 섞인다" 문제가 격리되지 않고 남는다

**추천: 안 A.** 근거는 §1 이 이미 낸 등록금과 같은 형태다 — "LLM 호출이 세션 완료
**트랜잭션** 밖에 있어야 한다"는 이 프로젝트가 실패로 배운 원칙인데, 안 B 로 가면 그 원칙이
막던 것과 같은 사고가 **트랜잭션 대신 tick/스레드 레벨**에서 재현된다. 2026-08-26 결정
로그가 남긴 메모("이 이벤트 타입만 별도 발행기로")와도 방향이 같다.

✅ **결정 (2026-08-27, 사용자) — 안 A(별도 발행기) 로 간다.** 추천대로다. 남은 실행 항목은
`claim`/`dispatch`/self-injection 골격의 공통 로직 추상화, `event_type` 필터를 받는 새 인덱스
마이그레이션, `outbox.weekly-report.*` 설정 프리픽스 신설이다 — 튜닝 숫자는 여전히 ③(프로바이더)
확정 후로 미룬다.

⚠️ **튜닝 값은 여기서 정하지 않는다.** lock-timeout 은 "batch-size × 최대 응답시간 여유"
(주석이 이미 요구하는 규칙 그대로), max-retry·max-backoff 는 "유료 호출이라 재시도가 비용을
곱한다"(§5)는 방향만 맞고, **실제 숫자는 프로바이더 선정(③)과 실측 응답시간이 나온 뒤** 정한다
— 근거 없는 임계값을 지금 박지 않는다([[feedback_no_arbitrary_threshold_values]]).

⚠️ 원문 유지 — **안 A/안 B 중 최종 선택은 사용자 몫이다**([[feedback_user_decides_not_claude]]).
이 절은 분석과 추천까지만이고, §12 의 ⑥ 은 여전히 미결정으로 남긴다.

---

## 6. 🔴 스키마가 먼저 막는다

```sql
-- V1__baseline.sql:247
session_id BIGINT NOT NULL,
```

**주간 리포트에는 세션이 없다.** 지금 `reports` 는 세션당 1건 전제라 `WEEKLY` 행을 **넣을 수가
없다.** 필요한 변경:

⚠️ **단 이 벽은 «저장할 때» 만난다.** 1단계(템플릿 문장)는 조회 시 계산이라 **스키마를 안 건드린다**
— §13-0 을 먼저 볼 것. 🔴 **그래서 아래는 초안이지 지금 적용할 마이그레이션이 아니다.** §12-1 이
이미 적어뒀듯 지금 실행하면 **"저장이 필요해지는 시점"(=LLM 착수) 전에 재기 전에 고르는 것**이다.
③(프로바이더)·⑥(아웃박스 튜닝 구현) 이 끝나고 실제로 값을 저장해야 할 때 아래를 다듬어 진짜
Flyway 파일(`V11__...`)로 만든다.

### 6-1. 초안 — `V11__weekly_report_schema.sql` (가칭, 미적용)

```sql
-- 주간(WEEKLY) 리포트 저장 준비 — session_id 를 nullable 로 풀고 기간 컬럼 + 생성 출처를 더한다.
-- 근거: docs/decisions/report-generation-llm.md §6, §12-1

-- 1) 세션 없는 리포트(WEEKLY/MONTHLY)를 허용
--    ⚠️ NOT NULL → NULL 전환이 ALGORITHM=INPLACE 로 되는지 이 테이블 실제 행 수로 EXPLAIN
--    확인이 선행이다 — pose_data 파티셔닝(P1)이 겪은 "INPLACE 거절 → COPY/pt-osc 양자택일"
--    함정이 여기서도 재현될 수 있다(online-ddl-aws-2026-08-12/README.md §1). 다만 그 실험의
--    절대 소요시간(69초 등)은 다른 스키마·다른 행 수라 인용 금지 — 재현 여부만 이 표에서 배운다.
ALTER TABLE reports
    MODIFY COLUMN session_id BIGINT NULL COMMENT 'WEEKLY/MONTHLY 는 NULL — 세션 하나에 묶이지 않는다';

-- 2) 집계 기간 — DATE 로 둔다. WeeklySummaryService 가 이미 LocalDate 로 주 경계를 계산해
--    쓰고 있다(start = previousOrSame(MONDAY), end = start.plusWeeks(1), end 는 배타적 상한) —
--    그 규약을 그대로 물려받아 별도 변환 없이 매핑되게 한다.
ALTER TABLE reports
    ADD COLUMN period_start DATE NULL COMMENT '집계 시작일(해당 주 월요일). SESSION 리포트는 NULL',
    ADD COLUMN period_end   DATE NULL COMMENT '집계 종료일(배타적 상한, WeeklySummaryService.end 와 동일 규약)';

-- 3) 멱등성 — (member_id, report_type, period_start) 유니크.
--    기존 uk_report_session(session_id)은 그대로 둔다: WEEKLY 행은 session_id 가 NULL 이고
--    MySQL 유니크 인덱스는 NULL 을 서로 다른 값으로 취급하므로 충돌하지 않는다. 즉 두 유니크가
--    report_type 별로 각자의 멱등성만 담당하는 구조가 되고, 어느 쪽도 지울 필요가 없다.
ALTER TABLE reports
    ADD UNIQUE KEY uk_report_period (member_id, report_type, period_start);

-- 4) 생성 출처 — 모델이 바뀌면 출력이 바뀌고(§6 원 문장), LLM 실패 시 템플릿으로 채워지는
--    경로도 있다(§9). "누가 이 문장을 썼나"는 §10 폴백 비율 관측과 재현(같은 프롬프트로 다시
--    생성) 양쪽에 필요한 최소 정보다.
ALTER TABLE reports
    ADD COLUMN summary_source   ENUM('LLM','TEMPLATE_FALLBACK') NULL
        COMMENT '문장이 LLM 출력인지 실패 후 템플릿 대체인지 — §10 폴백 비율의 원천',
    ADD COLUMN generation_model VARCHAR(100) NULL COMMENT '예: gemini-1.5-flash-002. 프로바이더(③) 확정 전엔 전부 NULL',
    ADD COLUMN prompt_version   VARCHAR(50)  NULL COMMENT '프롬프트 카탈로그 버전 — 문장 재현·회귀 비교용';
```

### 6-2. 같이 바뀌어야 하는 것 — 스키마만으로 안 끝난다

- `Report.java:28-31` — `@JoinColumn(name = "session_id", nullable = false)` 를
  `nullable = true` 로. JPA 매핑이 DB 제약보다 먼저 막고 있어 **DDL 만 바꾸면 엔티티가 거짓말을
  하게 된다**
- `Report.java` — `periodStart`·`periodEnd`(`LocalDate`), `summarySource`(신규 enum,
  `ReportType` 과 같은 자리에 둔다), `generationModel`·`promptVersion`(`String`) 필드 추가
- `SessionService.java:375` 근방(`report.setReportType(ReportType.SESSION)`) 은 **안 바뀐다** —
  SESSION 리포트 생성 경로는 그대로다. 새 필드는 WEEKLY 생성 경로(§5-2 가 만들 발행기)에서만 채운다

### 6-3. 열린 채로 남기는 것

- ⚠️ **1)의 ALGORITHM 확인은 지금 안 한다.** `reports` 실제 운영 행 수가 있어야 EXPLAIN 이
  의미가 있고, 지금은 그 시점이 아니다(§12-1)
- `summary_source`/`generation_model`/`prompt_version` 세 컬럼을 **하나의 JSON 컬럼**(예:
  `generation_meta`)으로 합칠지, 지금처럼 개별 컬럼으로 둘지는 **미정** — 개별 컬럼은 SQL 로
  집계·필터(예: "폴백 비율")가 쉽고, JSON 은 필드를 늘려도 마이그레이션이 없다는 게 장점이다.
  이 프로젝트가 §3 에서 이미 "숫자는 SQL, LLM 은 문장만"이라는 경계를 강제해온 것과 같은
  이유로 **관측에 쓰는 필드(summary_source)는 개별 컬럼 쪽이 결이 맞다**는 게 잠정 의견이고,
  최종은 사용자 확인 대기

⚠️ 운영 중 DDL 이므로 **무중단 DDL 실측(P1)**과 바로 이어진다 —
[결과](../../loadtest/results/online-ddl-aws-2026-08-12/README.md). 「설계 → 스키마 변경 →
그 변경을 어떻게 무중단으로 넣나」가 한 줄로 꿰이는 자리다.

---

## 7. 미결정 ③ — 프로바이더와 무료 티어

**무료 티어의 진짜 비용은 돈이 아니다.**

- 🔴 **약관이 첫 관문이다.** 무료 등급은 보통 **입력이 모델 개선에 쓰일 수 있다**는 조건이
  붙는다. 우리가 보내는 것은 사용자의 **운동·신체 데이터**다. 「나중에 유료로 바꾸면 됨」으로
  미룰 수 있는 항목이 아니라 **착수 전에 확인해야 하는 자리**다.
  ⚠️ **현재 약관·한도는 이 문서를 쓰는 시점 지식으로 단정하지 않는다 — 직접 확인이 선행이다**
- **레이트리밋이 아키텍처를 정한다.** 한도에 걸리는 순간 큐가 필요해지는데, 우리는 이미 있다.
  즉 무료 티어를 고르면 **「큐를 왜 넣었나」에 실제 이유가 생긴다** — 이게 무료를 고를 진짜 근거다
- **프로바이더는 인터페이스 뒤로.** 한도·약관이 바뀌면 갈아타야 한다

### 가정을 못 박고 계산한다 ([[feedback_state_assumption_design_to_it]])

**DAU 1,000 · 사용자당 주 1회 주간 리포트** 기준:

| | 값 |
|---|---|
| 주간 호출 | 사용자당 1회 → **일 평균 ~140건** |
| 입력 토큰 | 집계 JSON 기준 **~1,200~1,500** (원본 프레임 없음) |
| 출력 토큰 | **~400** |
| 일 토큰 | **약 25만** |

⚠️ 세션마다 붙이는 안이면 **일 1,000건 · 약 190만 토큰**으로 **7배 이상**이다.
무료 티어 한도와 이 수를 직접 비교하는 것이 착수 전 첫 표다.

---

## 8. 세션 안을 택하면 달라지는 것 — 🔴 **비활성 (2026-08-22)**

> ①이 **주간**으로 결정됐다(§12). 아래는 «지금 할 일» 이 아니라 **되돌릴 때를 위한 기록**이다.


- 트리거가 **세션 완료 콜백**이 된다 → §1 의 트랜잭션 경계가 **더** 중요해진다(피크가 몰린다)
- 스키마 변경이 **필요 없다**(§6 이 통째로 빠진다) — 가장 큰 장점
- 호출이 **7배 이상**이고 즉시성이 있어 배치·저부하 시간대를 못 쓴다
- 사용자가 **리포트를 여는 시점에 문장이 아직 없을 수 있다** → 읽기 경로에 `PENDING` 상태 필요

---

## 9. 실패해도 제품이 서는가 — **이게 합격 조건이다**

**LLM 은 필수 경로에 없어야 한다.**

1. 숫자 부분(SQL 집계)은 **항상** 있다
2. LLM 이 죽거나 한도를 넘으면 **문장만 템플릿으로** 바뀐다
3. 사용자는 「리포트가 없음」을 겪지 않는다

이렇게 두면 포폴 문장이 **「LLM 붙였다」**(변별력 거의 0)가 아니라
**「외부 의존을 아웃박스로 감싸서, 남의 서비스가 죽어도 우리 화면은 안 깨진다」**가 된다.
그리고 그 문장은 [#206](https://github.com/Shadowfit/init/issues/206)(gRPC 예산 미전파)·[#208](https://github.com/Shadowfit/init/issues/208)(종료 정책 3갈래)과 **같은 자리**라,
**그 둘이 이 설계의 선행**이기도 하다.

---

## 10. 관측 — 기존 SLO 판정선에 없는 지표들

`slo-baseline.md` 에 없는 것이 새로 필요하다. 관측 스택 동거 비용은 이미 **−0.05%**(잡음 크기)로
실측돼 있으니 켜는 것 자체는 싸다.

- 건당 **입력·출력 토큰** → **건당 원가**
- **한도 초과율**(429) · 재시도율 · **재시도가 만든 추가 비용**
- **파싱/검증 실패율** (스키마 위반 · 없는 숫자 인용)
- **폴백 비율** — 「문장이 템플릿으로 나간 비율」이 곧 품질 지표다
- 큐 적체 · 리스 회수 횟수(= 중복 호출 후보)

**예산 상한을 코드에 건다.** 일·월 토큰 예산을 넘으면 **큐를 멈추고 템플릿 폴백**.
「고지서를 보고 아는 것」은 관리가 아니다.

---

## 11. 미결정 ④ — 조언을 지금 만들 것인가

**추천: 미룬다.** 정확도 축이 아직 안 서 있다.

| | 상태 |
|---|---|
| [#217](https://github.com/Shadowfit/init/issues/217) | 무릎 각도가 z 에 의존 — 같은 프레임이 **3D 108° ↔ 2D 178°**. **미검증** |
| [#256](https://github.com/Shadowfit/init/issues/256) | 정답지 min_knee 판별 변동 — **미측정** |
| [#234](https://github.com/Shadowfit/init/issues/234) | ✅ 완료 — 정답지 흔들림 폭 7.04 가 `sync_rate` **0.28점**(약 25분의 1) |

**입력 신호의 신뢰 구간이 아직 안 그어졌다.** 그 위에 조언을 얹으면 **틀린 근거 위의
그럴듯한 처방**이 된다. 요약은 그 문제가 **전달**만 되지만, 조언은 **증폭**한다.

⚠️ 그리고 조언 축의 진짜 선행은 LLM 이 아니라 **[#228](https://github.com/Shadowfit/init/issues/228) 자세 문제 유형 감지기**다 —
유형이 없으면 조언을 걸 자리가 없다. `ReportFeedbackBatch` 는 proto 에도 Spring 수신부에도
이미 있는데 **보낼 쪽이 없어서** [#193](https://github.com/Shadowfit/init/issues/193) 이 「한 번도 안 불린다」로 열려 있다.

---

## 12. 미결정 목록

| # | 갈래 | 이 문서의 추천 | 상태 |
|---|---|---|---|
| ① | **세션 ↔ 주간** 어디에 붙이나 | **주간** (§2) | ✅ **결정 — 주간** (2026-08-22, 사용자) |
| ② | **「이번 주 평균」의 정의** — 세션 가중 ↔ rep 가중 | **rep 가중 + 정의를 프롬프트·화면에 같이 박기** (§4) | 🔴 미결정 — 다만 **재료는 생겼다**(아래) |
| ③ | **프로바이더 · 무료 티어** | 무료로 시작하되 **약관 확인이 선행**, 인터페이스 뒤로 (§7) | 🔴 미결정 |
| ④ | **조언을 지금 만들 것인가** | **미룬다.** #217·#256 이 먼저 (§11) | 🔴 미결정 |
| ⑤ | ~~**스키마 변경 시점** — 주간을 택하면 필수~~ → **필수 확정** | 무중단 DDL 실측이 있으니 그 절차로 (§6). **초안 작성됨**(§6-1) — 미적용 | 🔴 미결정(시점) — **①로 «필수» 가 됐다**, 실행은 저장이 필요해질 때 |
| ⑥ | **아웃박스 튜닝** — 이벤트 타입별 리스·재시도 분리 | **안 A(별도 발행기)** — 근거는 §5-1·§5-2 | ✅ **결정 — 안 A** (2026-08-27, 사용자). 튜닝 숫자는 ③ 확정 후 |

### 12-1. ① 이 닫히면서 달라진 것 (2026-08-22)

**결정: 붙일 자리는 «주간» 이다.** 근거는 §2 그대로 — 세션 1건은 입력이 대여섯 개라 **템플릿이
이기고**, LLM 이 보태는 것은 문장 다듬기인데 **그럴듯하게 틀릴 위험만 새로 생긴다.** 주간은
「무엇이 달라졌나」가 불규칙해 규칙으로 안 접히고, 호출이 **7분의 1 이하**에 즉시성이 없다.

| | 달라진 것 |
|---|---|
| **닫힌 것** | §8 이 비활성이 됐다. 세션 완료 콜백 트리거 · 읽기 경로 `PENDING` 상태 · 「호출 7배」는 **이제 고려 대상이 아니다** |
| **필수가 된 것** | ⑤ **스키마 변경**(§6). `reports.session_id NOT NULL`(`V1__baseline.sql:247`) 이 주간 행을 막으므로 **저장하는 순간 반드시 만난다.** 운영 중 DDL 이라 **무중단 DDL 실측(P1)과 이어진다** |
| **안 달라진 것** | **1단계(§13)는 이 결정과 무관하게 이미 서 있다.** A층은 저장 없이 조회 시 계산이라 §6 의 벽을 안 만난다(§13-0). 즉 **오늘 당장 막히는 것은 없다** |
| **재료가 생긴 것** | ②(평균의 정의). A층 구현이 **rep 가중·세션 가중을 둘 다 낸다** — 「고르지 말고 재서 정한다」가 이제 가능하다. 차이가 잡음 크기면 논쟁이 사라지고, 크면 그 크기가 근거다 |

🔴 **다음에 막는 것은 ⑤가 아니라 «저장이 필요해지는 시점» 이다.** 1단계가 저장을 안 하므로
순서는 «LLM 붙이기 → 저장 필요 → 스키마 변경» 이고, ⑤ 를 지금 하면 **재기 전에 고르는 것**이다.

---

## 13. 1단계 — 템플릿 문장을 LLM 없이 먼저 만든다 (구체안)

**이 절이 실제 착수 단위다.** LLM 결정과 무관하게 어차피 필요하고(폴백), 만들고 나면
「LLM 이 값을 하나」를 **추측이 아니라 실물로** 판단할 수 있다.

### 13-0. 🔴 저장하지 않는다 — 그래서 스키마 변경도 없다

§6 에서 `reports.session_id NOT NULL` 이 주간을 막는다고 적었는데, **1단계는 그 벽을 안 만난다.**
**조회 시 계산**하면 되기 때문이다.

저장이 필요한 조건은 둘 중 하나다 — **비싸거나, 비결정적이거나.** 템플릿 문장은 **둘 다 아니다**
(세션 20~30행 집계 · 같은 입력이면 같은 문장). 저장이 필요해지는 순간은 **LLM 을 붙일 때**고,
그때 §6 을 하면 된다. 지금 하면 **재기 전에 고르는 것**이다.

⚠️ 단 **집계 비용은 재고 넘어간다** — 세션 수가 많은 사용자에서 지연이 문제되면 그때 캐시/저장을
판단한다. 그 판단의 근거를 §13-5 가 만든다.

### 13-1. 만들 것 넷

| | 무엇 |
|---|---|
| `WeeklySummaryQueryRepository` | 집계 쿼리 (아래 Q1~Q4) |
| `WeeklySummaryService` | 조립 + 규칙 적용 |
| `WeeklySentenceRules` | **규칙 카탈로그** — 조건 → 문장. 순수 함수라 테스트가 쉽다 |
| ~~`ExerciseReportController` 엔드포인트 1개~~ | ~~`GET /reports/weekly?start=YYYY-MM-DD`~~ → 🔵 **정정 (2026-08-23, #352)**: 그 경로는 **호출자가 0** 이었다(같은 base 에 `/reports/weekly-summary` 가 이미 있어 프론트에서 안 보였다). A층 요약은 그 기존 응답의 `summary` 필드로 **합쳤고**, 파라미터는 안 받는다 — 응답의 두 절반이 같은 주를 보게 하려는 것이다 |

### 13-2. 집계 쿼리 — 두 층으로 갈린다

**A층: 세션 헤더만으로 되는 것** (`exercise_sessions`, JSON 파싱 불필요)

```sql
-- Q1
SELECT COUNT(*)                                            AS sessions,
       SUM(total_reps)                                     AS reps,
       SUM(avg_sync_rate * total_reps)
         / NULLIF(SUM(total_reps), 0)                      AS rep_weighted,     -- 미결정 ②-ㄱ
       AVG(avg_sync_rate)                                  AS session_weighted, -- 미결정 ②-ㄴ
       COUNT(DISTINCT DATE(start_time))                    AS active_days
  FROM exercise_sessions
 WHERE member_id = ? AND status = 'COMPLETED'
   AND start_time >= ? AND start_time < ?
```

🔴 **두 평균을 «둘 다» 낸다.** §4 의 미결정 ②를 고르는 게 아니라 **재서** 정한다 — 카드 B 가
세션 안에서 했던 것과 같은 수법이다. 둘의 차이가 0.1점이면 논쟁이 사라지고, 크면 그 크기가
근거가 된다.

⚠️ **가중치가 정확하지 않다.** `avg_sync_rate` 는 **측정된 rep 들의 평균**인데 `total_reps` 는
**측정 안 된 rep 도 센다**. 정확한 가중치는 「측정된 rep 수」이고 그건 컬럼에 없다 —
`repTrend` 배열 길이로만 알 수 있다(B층). 1차는 근사로 가되 **이 사실을 결과에 적는다.**

**B층: `reports.detailed_analysis` JSON 을 펼쳐야 하는 것**

```sql
-- Q2  rep 위치별 곡선 — 「몇 번째부터 흔들리나」
SELECT jt.rep_number, AVG(jt.sync_rate) AS avg_sync, COUNT(*) AS n
  FROM reports r
  JOIN exercise_sessions s ON s.id = r.session_id
 CROSS JOIN JSON_TABLE(r.detailed_analysis, '$.repTrend[*]'
        COLUMNS (rep_number INT    PATH '$.repNumber',
                 sync_rate  DOUBLE PATH '$.syncRate')) jt
 WHERE r.member_id = ? AND s.start_time >= ? AND s.start_time < ?
 GROUP BY jt.rep_number
 ORDER BY jt.rep_number
```

```sql
-- Q3  worst 회차 분포
SELECT JSON_EXTRACT(r.detailed_analysis, '$.worstSection.repNumber') AS worst_rep, COUNT(*) AS n
  ...  GROUP BY worst_rep
```

🔴 **「worst 국면 빈도」는 못 만든다.** `WorstSectionDto` 에 국면 이름표가 없다 — `reason` 은
`"2회차 · 싱크로율 75%"` 라는 **문자열**이고 문구도 잠정이다([#80](https://github.com/Shadowfit/init/issues/80)).
만들 수 있는 것은 **worst «회차» 분포**뿐이다. 국면 축은 [#218](https://github.com/Shadowfit/init/issues/218)(국면 이름표와 rep 판정이
다른 자를 쓴다)이 먼저다.

**Q4**: Q1 을 지난주 기간으로 한 번 더 — 델타용.

⚠️ **`JSON_TABLE` 은 그 자체로 카드 후보다** — 「JSON 컬럼을 SQL 안에서 펼쳐 집계한다」는
읽기축에 없던 모양이다. 단 **계획을 재보기 전에는 카드라고 부르지 않는다**(EXPLAIN 선행).

### 13-3. 🔴 문장 규칙 — 임계값을 못 쓴다

규칙은 `조건 → 문장` 쌍이고 우선순위로 정렬해 상위 N개만 낸다. 그런데 여기서 이 프로젝트의
규칙 하나에 걸린다 — **근거 없는 임계값 금지**([[feedback_no_arbitrary_threshold_values]]).

「5점 이상 떨어지면 **무너진다**」의 5점에 근거가 없다. baseline 도 threshold 도 아직 없다.

**그래서 1차 규칙은 «순위 · 방향 · 사실» 로만 만든다.**

| 종류 | 예 | 임계값 필요? |
|---|---|---|
| **사실** | 「이번 주 {n}일, {m}세션 운동했어요」 | ❌ |
| **방향** | 「싱크로율이 지난주보다 **올랐어요/내렸어요**」 | ❌ |
| **순위** | 「가장 약했던 건 **{k}회차**였어요」 · 「곡선의 최댓값 대비 낙폭이 가장 큰 지점은 {k}회차」 | ❌ |
| **분포** | 「{n}세션 중 {m}번 {k}회차가 가장 약했어요」 | ❌ |
| ~~정도~~ | ~~「{k}회차부터 **무너집니다**」~~ | 🔴 **보류** — 임계값이 필요하고 근거가 없다 |

**「정도」를 말하는 문장은 데이터가 쌓인 뒤 분포에서 임계값을 뽑아 추가한다.** 그때까지는 없다.
⚠️ 데이터 부족(`sessions < 2`)일 때의 문장도 **사실**로만 쓴다 — 「추세를 보기엔 기록이 {n}건이에요」.

### 13-4. 테스트

- **집계 쿼리** — 고정 시드 → 기대값 단언. 특히 **두 평균이 다르다**는 것을 회귀로 박는다
- **규칙** — 순수 함수라 조건 조합 → 문장 집합 단위 테스트
- 🔴 **합성 데이터 한계**([[project_synthetic_data_distribution_limit]]) — 로컬 시드는 값 분포가
  균일해서 **규칙이 실제로 발화하는지**를 검증하기 어렵다. 「쿼리가 맞는가」까지가 테스트 범위이고,
  **「문장이 쓸모 있는가」는 실사용 데이터가 있어야 안다** — 그게 이 1단계를 만드는 이유이기도 하다

### 13-5. 관측

- 주간 조회 지연 · **사용자별 세션 수 분포** → 캐시/저장이 필요한지의 근거
- 규칙별 발화 횟수 → **한 번도 안 터지는 규칙**을 찾아낸다([#193](https://github.com/Shadowfit/init/issues/193) 이 준 교훈: 코드가 있어도 안 불릴 수 있다)

### 13-6. 범위 밖

LLM · 저장 · 스키마 변경 · 조언 · 국면 축. **1단계는 읽기 전용이다.**

---

## 결정 로그

- **2026-08-22: 설계 초안.** 착수 전 코드 확인에서 **질문 자체가 바뀌었다** — `precomputeReport`
  가 이미 있고 비어 있는 것은 `summary`·`improvement_tips` **두 칸**이다(`production-signal-checklist.md`
  의 「쓰기 경로 없음」은 07-05 기준이라 낡았다). 그리고 **`ReportType.WEEKLY`·`MONTHLY` 가
  스키마·enum 양쪽에 정의돼 있는데 쓰는 코드가 없다** — 빈 자리가 이미 있다.
  🔴 착수 전에 풀어야 할 것 셋이 드러났다: ① 세션 완료 트랜잭션 경계(#66 이 이미 낸 등록금)
  ② `reports.session_id NOT NULL` 이라 주간 행을 **넣을 수가 없다** ③ 아웃박스 설정값이
  **내부 gRPC(5초) 기준**이라 LLM(5~15초)에 그대로 쓰면 **리스 초과로 중복 호출**이 나고
  `max-retry:10` 이 **비용을 10배로** 만든다.
  **실행·채택 미착수**, §12 미결정 6건.

- **2026-08-22: 미결정 ① 결정 — 주간이다** (사용자).
  §2 의 추천대로 확정했다. 판단 근거는 «LLM 이 템플릿을 이기는 조건» 셋(입력이 많고 · 해석이
  필요하고 · 출력이 자연어)이고, **세션 1건은 그 셋 중 하나도 안 만족한다** — 입력이 숫자
  대여섯 개라 템플릿이 이기고, 이미 `comparisonWithPrevious`(직전 동일 운동 세션 대비)가
  구현돼 있어 «달라진 것» 도 규칙으로 나온다.
  - **§8 은 비활성**으로 표시했다. 지우지 않고 «되돌릴 때를 위한 기록» 으로 남긴다
  - **⑤(스키마 변경)가 «주간을 택하면 필수» → 필수로 확정**됐다. 다만 **지금 막히지는 않는다** —
    1단계가 저장을 안 하기 때문이고(§13-0), 순서는 «LLM → 저장 필요 → DDL» 이다
  - **②는 열려 있되 재료가 생겼다** — A층이 평균 둘을 다 내므로 재서 정할 수 있다
  - ⚠️ **착수 순서는 안 정했다.** 이 결정은 «어디에 붙이나» 하나만 닫는다. ③(프로바이더)·
    ⑥(아웃박스 튜닝)이 열려 있는 한 LLM 착수는 아직이다

- **2026-08-26: §7의 "발행기 20건 순차 → 락 리스(60초) 초과" 갭에 후보 도구 하나 추가.**
  사용자가 "WebFlux 도입할 부분이 더 없냐"고 물어 코드로 재확인하던 중 나온 부산물 — 새 결정
  아니다. §1의 "LLM 호출이 세션 완료 트랜잭션 밖에 있어야 한다"는 이미 이 발행기가 **서버
  요청 스레드가 아니라 백그라운드 잡**이라는 뜻이라, `production-signal-checklist.md` §2-7이
  판정한 "블로킹 JDBC 스택 위에 리액티브 서버는 안티패턴"이 이 자리엔 적용되지 않는다.
  `WebClient`(WebFlux **서버** 도입 없이 HTTP 클라이언트로만 추가)로 20건을 논블로킹 동시
  호출하면 "건당 5~15초 × 20건 순차 = 최대 300초"가 줄어 리스 초과·중복 호출 리스크가
  구조적으로 낮아진다는 것을 판단만 해뒀다 — 실측 없음, ⑥(아웃박스 튜닝)이 열려 있어 지금
  착수 대상이 아니라 **⑥ 착수 시점의 도구 후보**로만 남긴다. 상세는
  `production-signal-checklist.md` 결정 로그(2026-08-26)에 병기.
  ([[feedback_user_decides_not_claude]] — 착수 여부·순서는 사용자 확인 대기)

- **2026-08-27: ⑥(아웃박스 튜닝) 설계안 작성 — 결정 아님.** §12 미결정 중 사용자가
  이걸 먼저 진행하기로 골라, 코드(`OutboxPublisher`·`OutboxEventRepository`·
  `V1__baseline.sql`)를 다시 읽고 §5-1·§5-2 를 새로 썼다. 핵심 발견은 **지금 발행기가
  이벤트 타입을 아예 구분하지 않는다**는 것 — 폴링 쿼리에 `event_type` 조건이 없고,
  `idx_outbox_dispatch` 는 2026-07-29 실측으로 `status` 단일 컬럼에 맞춰 튜닝돼 있어 타입
  필터를 얹으면 그 튜닝이 깨진다. 두 안(별도 발행기 / 단일 발행기+타입별 config)을 비교해
  **안 A(별도 발행기)를 추천**했지만 최종 선택은 아직이다. 튜닝 값(lock-timeout·max-retry
  등 구체적 숫자)은 프로바이더(③) 선정 전이라 의도적으로 비워뒀다.

- **2026-08-27: ⑥ 결정 — 안 A(별도 발행기)** (사용자). §5-2 추천대로 확정했다. 아직
  구현은 시작 전이다 — 실행 순서(공통 로직 추상화 → 새 인덱스 마이그레이션 → 설정 프리픽스
  신설)와 튜닝 숫자(③ 확정 후)가 남아 있다. ⑤(스키마 변경)·③(프로바이더)이 여전히 열려
  있어 실제 착수 순서는 별도 확인이 필요하다.

- **2026-08-27: ⑤ 스키마 마이그레이션 초안 작성 — 미적용, 결정 아님.** `Report.java`·
  `WeeklySummaryService.java` 를 다시 읽고 §6-1(`V11__weekly_report_schema.sql` 가칭)·§6-2
  (엔티티 동반 변경)·§6-3(열린 항목)을 새로 썼다. 핵심 설계: `session_id` nullable 전환 +
  `period_start`/`period_end`(DATE, `WeeklySummaryService` 의 `LocalDate` 주 경계 규약을
  그대로 물려받음) + 신규 유니크 `uk_report_period` + 생성 출처 3컬럼
  (`summary_source`/`generation_model`/`prompt_version`). 기존 `uk_report_session` 은 NULL
  이 유니크에서 서로 다른 값으로 취급되는 성질을 이용해 **그대로 유지**하기로 했다(별도
  마이그레이션으로 지울 필요 없음). 열어둔 것: `session_id` NOT NULL→NULL 전환이
  ALGORITHM=INPLACE 로 되는지는 **운영 행 수가 있어야 EXPLAIN 이 의미가 있어 지금 안 잰다**,
  생성 출처를 개별 컬럼 vs JSON 하나로 합칠지는 미정(개별 컬럼 쪽이 관측·집계에 유리하다는
  잠정 의견만 적음). §12-1 의 경고대로 **이 마이그레이션은 지금 적용 대상이 아니다** — 저장이
  필요해지는 시점(LLM 착수)까지 초안으로만 둔다.
