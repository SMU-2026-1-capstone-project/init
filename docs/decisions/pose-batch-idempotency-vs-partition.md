# pose_data 적재의 멱등성 — 파티션이 순진한 유니크 키를 막는다 (#188)

작성일: 2026-08-12
상태: **결정됨 — ㄴ 채택 (2026-08-12)**. 근거와 번복 경위는 §4-0. 구현 미착수
연관: [GitHub #188](https://github.com/Shadowfit/init/issues/188), [`./pose-data-partition-fk-tradeoff.md`](./pose-data-partition-fk-tradeoff.md), [`./outbox-reliable-messaging.md`](./outbox-reliable-messaging.md), [`./pose-ingest-downsampling.md`](./pose-ingest-downsampling.md), [`./grpc-integration-checklist.md`](./grpc-integration-checklist.md) §2-2

---

## 0. 한 줄

`SavePoseDataBatch` 는 **재전송도 없고 수신측 멱등도 없다.** 그런데 멱등을 걸려고 유니크 키를 세우는
순간 **파티셔닝 제약과 정면으로 부딪친다** — 그래서 「다른 곳에서 쓴 방식(`INSERT IGNORE` +
유니크 키)을 그대로 복제」가 **이 테이블에서는 작동하지 않는다.**

---

## 1. 지금 무엇이 사실인가 (코드·DDL 대조, 2026-08-12)

### 1-1. 세 콜백 중 이 경로만 양쪽 다 비어 있다

| AI→Spring 경로 | 재전송(AI) | 수신측 멱등(Spring) |
|---|---|---|
| `CompleteAnalysis` | ✅ 지수 백오프 | ✅ `COMPLETED` 가드(`SessionService.java:229-232`) |
| **`SavePoseDataBatch`** | ❌ 없음 | ❌ 없음 |
| `ReportFeedbackBatch` | — | ✅ `INSERT IGNORE` + `uk_session_event` (단, 호출자 부재 → 안 돎, #193) |

`spring_client.py:51-68` 은 실패 시 `logger.error` 한 줄로 끝난다. **rep 하나 분량이 통째로 사라진다.**

> **지금 중복이 나지 않는 이유는 방어가 아니라 부재다.** 재전송이 없어서 중복이 없다. 즉 ①번을
> 고치면(재시도 추가) 그 순간 ②번(중복)이 새로 생긴다 — 둘은 따로 고칠 수 없다.

### 1-2. 실제 스키마 (DB 실측)

```sql
CREATE TABLE `pose_data` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` bigint NOT NULL,
  `rep_number` int NOT NULL DEFAULT '0',
  `timestamp_sec` decimal(10,3) NOT NULL,
  ...
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,   -- ← DB 가 채운다
  PRIMARY KEY (`id`,`created_at`),
  KEY `idx_session_timestamp` (`session_id`,`timestamp_sec`)    -- ← 비유니크
) PARTITION BY RANGE (unix_timestamp(`created_at`))             -- ← 월별
```

유니크 제약은 **하나도 없다**. `idx_session_timestamp` 는 조회용 비유니크 인덱스다.

---

## 2. 왜 「유니크 키 + INSERT IGNORE」를 그대로 못 쓰나

MySQL 은 **파티션 테이블의 모든 유니크 키(PK 포함)가 파티션 표현식의 컬럼을 전부 포함**할 것을
요구한다. PK 가 `(id)` 가 아니라 `(id, created_at)` 인 것이 이미 그 결과다.

따라서 어떤 자연키를 세우든 **`created_at` 이 강제로 끌려 들어온다**:

```sql
-- 하고 싶은 것
UNIQUE KEY uk_pose (session_id, rep_number, timestamp_sec)
-- 실제로 허용되는 것
UNIQUE KEY uk_pose (session_id, rep_number, timestamp_sec, created_at)
```

그런데 `created_at` 은 **DB 가 INSERT 시각으로 채운다**(`DEFAULT CURRENT_TIMESTAMP`). 재전송은
당연히 나중에 도착하므로 값이 달라지고, **유니크 키가 통째로 무력해진다.** `INSERT IGNORE` 를
붙여도 흡수되는 것이 없다.

> `session_feedback_logs` 에서 통했던 방식이 여기서 안 통하는 이유가 이것이다 — **그 테이블은
> 파티셔닝돼 있지 않다.** 즉 이것은 「빠뜨린 방어」가 아니라 **파티셔닝을 얻은 대가**다.

### 2-1. 다행인 사실 — 재전송 페이로드는 결정론적이다

`(session_id, rep_number, timestamp_sec)` 이 자연키 후보로 성립하는지부터 확인해야 한다. 성립한다:

- 다운샘플이 **같은 입력이면 항상 같은 프레임을 고른다** — 구간별 최소 `smoothedKneeAngle`,
  동률이면 먼저 나온 것(`PoseDataService.java:192-203`, 엄격 부등호 + 시간 오름차순 입력)
- 따라서 **같은 배치를 다시 보내면 저장 대상 행 집합이 동일**하다

즉 막는 것은 「키를 만들 수 없음」이 아니라 **「파티션 때문에 그 키를 유니크로 걸 수 없음」** 이다.
문제가 좁게 정의된다.

---

## 3. 선택지

### ㄱ. AI 재시도만 추가한다

`report_pose_data_batch` 에 `report_complete_analysis` 와 같은 백오프를 붙인다.

| | |
|---|---|
| 장점 | 면적 최소(파이썬 한 곳). 유실은 즉시 줄어든다 |
| 단점 | **중복을 새로 만든다.** 리포트 집계(평균 sync·worst 구간)가 중복 행만큼 왜곡된다 |
| 판정 | **단독 채택 부적합.** ㄴ~ㄷ 중 하나와 반드시 짝지어야 한다 |

### ㄴ. `created_at` 을 애플리케이션이 결정론적으로 채운다

AI 가 보낸 값에서 유도한 시각을 Spring 이 명시적으로 INSERT 한다. 그러면 재전송에도 같은 값이 들어가
`(session_id, rep_number, timestamp_sec, created_at)` 유니크 키가 실제로 작동한다.

| | |
|---|---|
| 장점 | pose_data 한 테이블 안에서 닫힌다. 추가 테이블·추가 조회 없음 |
| 단점 | **`created_at` 의 의미가 바뀐다** — 「적재 시각」에서 「이벤트 시각」으로 |
| 파급 | 파티션 배정 기준이 같이 바뀐다. 늦게 도착한 배치가 **지난 달 파티션**에 들어갈 수 있고, 그러면 보존정책·아카이빙이 「이미 드롭한 구간에 새 행이 도착」하는 경우를 다뤄야 한다 |
| 미확인 | proto 에 이벤트 시각 필드가 없다. `timestamp_sec` 는 세션 내 상대 초라 절대 시각이 아니다 → **계약 변경이 선행**될 수 있다 |

### ㄷ. inbox 테이블로 배치 단위 중복을 흡수한다

`pose_data` 는 손대지 않고, 「이 배치를 이미 처리했는가」만 별도 테이블로 판정한다.
outbox 의 반대편(inbox 패턴)이며 이 repo 에 outbox 선례가 이미 있다.

```sql
CREATE TABLE processed_pose_batch (
  session_id BIGINT NOT NULL,
  rep_number INT NOT NULL,
  processed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (session_id, rep_number)          -- 파티션 없음 → 제약 자유
);
```

| | |
|---|---|
| 장점 | **파티션 제약을 아예 우회**한다. `created_at` 의미도, pose_data 스키마도 그대로 |
| 장점 | 배치 단위라 행 수가 작다(rep 당 1행). 인덱스 비용 낮음 |
| 단점 | 테이블 1개 + INSERT 1회 추가. 세션 삭제 시 정리 경로가 하나 늘어난다(FK 없음 — pose_data 와 같은 이유) |
| 미확인 | `rep_number=0`(구버전 AI 미전송)이 섞이면 키가 겹친다. **0 을 어떻게 다룰지 결정 필요** |

### ㄹ. SELECT 후 INSERT (선검사)

**기각 후보.** 검사와 삽입 사이가 원자적이지 않아 동시 재전송이면 둘 다 통과한다. 같은 종류의 창을
이 코드베이스가 이미 한 번 밟았다(#87 고아 행). 기록해 두되 채택하지 않는 것을 권한다.

---

## 4-0. 결정 — **ㄱ + ㄴ 채택** (2026-08-12)

**`created_at` 을 「적재 시각」에서 「이벤트 시각」으로 바꾸고, 그 위에 유니크 키를 세운다.**
재시도(ㄱ)는 어느 안이든 필요하므로 함께 간다. inbox 테이블(ㄷ)은 **짓지 않는다.**

### 왜 추천(ㄱ+ㄷ)을 뒤집었나

이 문서는 처음에 **ㄱ+ㄷ 을 추천**했다(§4). 그 추천의 핵심 근거는
*"ㄴ 의 파급(늦게 도착한 배치가 이미 DROP 된 파티션을 노림)이 **미측정**"* 이었다.

**그 미측정 항목을 측정했고(§4-1), 우려한 실패가 일어나지 않았다.** 근거가 사라졌으므로 추천도 유지되지 않는다.

결정을 굳힌 판단 두 개가 더 있다:

1. **ㄷ 은 영구 구조물이 아니다.** 자연키로 멱등이 서면 장부는 필요 없어진다 — 즉 ㄷ 은 **나중에
   ㄴ 으로 갈 때 걷어낼 임시물**이다. 남은 일정(2학기)이 ㄴ 을 감당할 수 있다면 짓고 걷어내는 낭비를
   피하는 쪽이 낫다.
2. **현행 구조는 이미 미묘하게 틀려 있다.** 밤 11시 58분에 운동하고 자정 넘어 저장되면 그 행은
   **다음 달 파티션**에 들어간다. 보존정책이 「N개월 지난 **운동 기록** 폐기」를 의도한다면 기준은
   운동일이어야 하는데 적재일로 돌고 있다. ㄴ 은 멱등을 얻는 김에 **이 오차도 같이 고친다.**

### 이 결정이 되돌리는 것 / 되돌리지 않는 것

- 되돌리지 않는다: 파티셔닝 자체, 월별 RANGE 스킴, `DROP PARTITION` TTL. **키의 의미만 바꾼다.**
- 기존 파티션 실험(ALTER 96분 / DROP PARTITION vs DELETE 625배 / pruning)은 전부 **구조**에 대한
  측정이라 **무효화되지 않는다.** 오히려 `realmysql-experiments.md` 의 「유일한 정당화 = TTL」 서사에
  *"그 TTL 의 기준이 적재일이라 틀려 있었고 이벤트 시각으로 맞췄다"* 는 정정이 얹힌다.

---

## 4-1. 측정 — 만료된 파티션 범위로 INSERT 하면 무슨 일이 일어나나 (2026-08-12)

ㄴ 의 유일한 미지수였다. `pose_data` 는 건드리지 않고 **같은 파티션 스킴의 복제 테이블**로 쟀다
(경계 상수는 실제 DDL 에서 그대로 가져옴).

```sql
CREATE TABLE lab_late_arrival (
  id BIGINT NOT NULL AUTO_INCREMENT, label VARCHAR(40) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (unix_timestamp(created_at)) (
  PARTITION p2026_05 VALUES LESS THAN (1780239600),
  PARTITION p2026_06 VALUES LESS THAN (1782831600),
  PARTITION p2026_07 VALUES LESS THAN (1785510000),
  PARTITION p2026_08 VALUES LESS THAN (1788188400),
  PARTITION pfuture  VALUES LESS THAN MAXVALUE );

-- 월별 1행씩 적재 → 6월 파티션 DROP → 6월 시각으로 INSERT
ALTER TABLE lab_late_arrival DROP PARTITION p2026_06;
INSERT INTO lab_late_arrival (label, created_at) VALUES ('늦게도착_6월','2026-06-20 09:00:00');
SELECT label, created_at FROM lab_late_arrival PARTITION (p2026_07);
```

**결과:**

```
+-------------------+---------------------+
| 7월행             | 2026-07-15 12:00:00 |
| 늦게도착_6월      | 2026-06-20 09:00:00 |   ← 에러 없이 여기 들어갔다
+-------------------+---------------------+
```

| 관찰 | |
|---|---|
| INSERT 실패 | **없음** — 에러도 경고도 없다 |
| 데이터 유실 | **없음** |
| 착지 위치 | **인접(다음) 파티션에 조용히 흡수** — RANGE 파티션은 앞 칸이 사라지면 뒷 칸의 하한이 내려간다 |
| 조회 정확성 | **유지** — 6월 조건 질의는 확장된 p2026_07 을 스캔하므로 누락되지 않는다 |

**해석**: 실패 모드가 「거부」가 아니라 **「조용한 흡수」** 다. 대가는 그 행이 **예정보다 한 달 늦게
폐기**되는 것뿐이다. 그리고 이 상황이 성립하려면 늦게 도착한 데이터가 **이미 폐기된 달**을 가리켜야
하는데, 재전송은 초 단위이고 보존은 개월 단위라 **실질적으로 도달하지 않는다.**

⚠️ `information_schema.PARTITIONS.TABLE_ROWS` 는 추정치라 일부 칸을 0 으로 표시했다. 판정은 추정치가
아니라 `PARTITION (...)` 직접 조회로 했다.

⚠️ 반복 없이 1회 실행이다. 타이밍이 아니라 **결정론적 동작**을 보는 실험이라 반복이 결론을 바꾸지
않는다고 판단했다([[feedback_measure_design_needs_repeats]] 의 반복 요건은 성능 측정 대상).

---

## 4-3. 착수 블로커 — 유니크 키를 지금 그대로는 못 건다 (2026-08-12 실측)

`ALTER TABLE ... ADD UNIQUE KEY` 는 **기존 행에 위반이 있으면 실패**한다. 재봤더니 **있다.**

```
전체 3,225 행 중 중복 조합 900 건
session_id=801 / rep_number=0 / timestamp_sec=0.000 / created_at=2026-08-08 22:57:45  → 9 행
                              / timestamp_sec=0.500 / (같은 초)                        → 9 행
...
세션 1개 · rep_number 1종 · timestamp_sec 5종
```

**원인은 부하 rig 다.** 단일 템플릿을 복제해 주입하므로 **같은 행이 9벌씩** 들어간다. 실사용 데이터라면
프레임마다 `timestamp_sec` 가 달라 충돌하지 않는다 — 즉 **합성 데이터의 산물이지 설계 결함이 아니다.**

### 그런데 이게 rig 쪽에 파급된다 (⚠️ 다른 작업과 충돌 지점)

유니크 키가 서면 **현재 rig 페이로드는 9행 중 1행만 살아남는다.** `INSERT IGNORE` 면 나머지 8행이
조용히 흡수되므로, **rig 가 측정하는 «초당 저장 행수» 가 실제와 어긋난다.**

- 이미 나온 과거 측정치는 **그대로 유효**하다(그때는 유니크 키가 없었고, 잰 것을 잰 것이다)
- 그러나 **ㄴ 도입 이후의 rig 실행은 페이로드를 고쳐야 의미가 있다** — 배치 안에서
  `timestamp_sec`(또는 `rep_number`)가 행마다 달라져야 한다
- [#166](https://github.com/Shadowfit/init/issues/166) 이 지금 rig 페이로드를 손보는 중이므로
  **그 작업과 조율이 필요하다**

### 착수 순서에 미치는 영향

마이그레이션이 유니크 키를 걸기 전에 기존 위반 행을 처리해야 한다. 현재 DB 의 위반 행은 **전부
합성 데이터**(세션 801 하나)이므로 폐기 가능하나, **다른 세션이 그 데이터로 부하 테스트를 돌리는 중**이라
임의로 지우면 안 된다. **정리 시점을 조율할 것.**

---

## 4-2. (참고) 채택 전 추천이었던 안

> 아래는 §4-1 측정 **이전**의 판단이다. 기록으로 남긴다.

**ㄱ + ㄷ** 을 추천한다.

- ㄷ 이 **파티셔닝이라는 이미 내린 결정을 되돌리지 않는다.** ㄴ 은 `created_at` 의 의미를 바꿔
  보존정책까지 건드리는데, 그 파급은 아직 측정된 적이 없다(§6).
- ㄷ 은 **작동을 먼저 확보**하고, ㄴ 은 언제든 나중에 갈 수 있다. 반대 방향(ㄴ 먼저)은 되돌리기가 비싸다.
- ㄱ 은 어느 쪽을 고르든 필요하다 — 수신측만 고치면 **애초에 재전송이 없어 아무 일도 일어나지 않는다.**

다만 ㄴ 은 **서사 가치가 더 크다** — 「파티션 키의 의미를 바꿔서 멱등을 얻고, 대신 보존정책을 다시
설계했다」는 이야기는 ㄷ 보다 깊다. 포폴 관점을 우선한다면 ㄴ 이 후보로 남을 만하다. 이 저울질은
사용자 몫이다.

---

## 5. 무엇을 측정해서 증명하나

before/after 가 없으면 이 작업은 「고쳤다는 주장」에 그친다.

| 지표 | 어떻게 |
|---|---|
| 유실률 | AI→Spring 배치 전송 시도 대비 저장 성공 배치 수. **현재 계측이 없다**(#151 — AI 계측 0줄) |
| 중복률 | ㄱ 적용 후 `(session_id, rep_number)` 당 행 수 분포 |
| 적재 지연 | inbox 조회 1회 추가가 배치 처리 시간에 주는 델타. `loadtest/ghz/` 리그 재사용 |
| 리포트 왜곡 | 중복 상태에서의 평균 sync·worst 구간 vs 정상 상태 |

⚠️ **부하 환경 한계**: 이 박스는 2물리코어에 MySQL·백엔드·리그가 동거한다. 절대 RPS 는 의미 없고
**같은 조건의 상대 델타만** 신뢰한다.

---

## 6. 정직하게 비어 있는 것

- **유실이 실제로 얼마나 나는지 측정된 적이 없다.** 「rep 하나가 사라진다」는 코드 구조에서
  나온 결론이고, 빈도는 미상이다. 재시도 횟수·백오프 간격 같은 값은 **근거가 생긴 뒤에 정한다** —
  지금 숫자를 박으면 임의값이다.
- ~~ㄴ 의 파급(늦게 도착한 배치가 지난 파티션에 착지)은 **빈도·영향 모두 미측정**이다.~~
  → **§4-1 에서 측정 완료(2026-08-12)**. 영향은 «조용한 흡수». 빈도는 여전히 미측정이나, 재전송이
  초 단위이고 보존이 개월 단위라 상한이 구조적으로 낮다.
- ~~파티션 경계에 걸친 배치(자정 직전 시작 → 직후 저장)의 동작은 확인하지 않았다.~~
  → 확인했다. **현행(적재 시각) 기준에서는 그 행이 다음 달 칸에 들어간다** — ㄴ 이 고치려는 오차가
  바로 이것이다(§4-0).
- **`occurred_at` 미전송(proto3 기본값 0)** 시 1970년으로 해석돼 **최하위 파티션에 흡수**될 수 있다.
  `rep_number`·`smoothed_knee_angle` 이 쓰는 «0 = 미상» 관례대로 **서버 시각 폴백**이 필요하다 — 구현 시 필수.
- ~~기존 행에 **유니크 키 위반이 있는지 확인하지 않았다.**~~ → **확인했다. 위반이 있다(§4-3).**
- `rep_number=0` 이 실제로 유입되는지 — 구버전 AI 가 현재 운영에 있는지 — 확인하지 않았다.

---

## 7. 이 문서가 여는 다음 질문

`pose_data` 는 FK 도 없고(파티션 때문에 제거, `pose-data-partition-fk-tradeoff.md`) 유니크 키도
없다(같은 이유). **참조무결성과 멱등성 둘 다를 애플리케이션이 떠맡고 있다.**

지금은 세션 존재 검증 한 줄(`PoseDataService.java:68`)이 그 전부이고, 그 검증과 INSERT 사이의
창은 이미 알려진 결함이다(#87). 이 문서의 ㄷ 안은 그 위에 하나를 더 얹는 것이므로,
**「애플리케이션이 떠맡은 무결성 항목」의 목록을 한 곳에 모아 관리할 필요**가 생긴다.