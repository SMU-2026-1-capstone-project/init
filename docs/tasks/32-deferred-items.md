# 미룬 것들 — 지금 «안 한다» 를 박제한다

작성일: 2026-08-11
상태: **보류 목록** — 넷 다 착수하지 않는다. 각 항목의 «열 조건» 이 이 문서의 본체다
대상: 2026-08-11 대화(엔진 선택 · 병목 지도 · MySQL 튜닝)에서 나온 후속 후보 4건
연관: [`../decisions/project-destination-and-exit-criteria.md`](../decisions/project-destination-and-exit-criteria.md) §0·§4,
[`../decisions/mysql-vs-postgresql.md`](../decisions/mysql-vs-postgresql.md),
[`../../loadtest/results/ai-concurrency-2026-08-09/README.md`](../../loadtest/results/ai-concurrency-2026-08-09/README.md)

---

## 0. 이 문서가 하는 일

**할 일 목록이 아니다.** [`project-destination-and-exit-criteria.md §0`](../decisions/project-destination-and-exit-criteria.md)
이 경고한 순환 — *"실험 하나가 끝나면 그 실험이 다음 실험을 낳는다"* — 이 오늘 또 네 번 일어났다.
넷 다 그럴듯하고, 넷 다 지금 하면 목적지에서 멀어진다.

그래서 **«안 한다» 쪽을 기록한다.** 나중에 같은 생각이 다시 떠올랐을 때
«이미 검토했고 이 조건에서 열기로 했다» 로 만나기 위해서다.

> 🔴 **여기 항목을 추가할 때는 «열 조건» 을 반드시 같이 적을 것.**
> 조건 없는 항목은 보류 목록이 아니라 그냥 **불안 목록**이 된다.

---

## P1. `measure_ai_concurrency.py` 호스트 적응화

**무엇** — 지금 스크립트는 i3-6100 전용이다. 다른 장비에 그대로 올리면 결과가 틀린다.

| 위치 | 문제 |
|---|---|
| 헤더 출력(`:199-200`) | `물리 2코어(i3-6100)+HT` 가 **문자열로 하드코딩** — 다른 장비에서 돌리면 거짓 사양이 결과에 찍힌다 |
| `[C]` 스윕(`:248`) | `(1, 2, 4, 8)` 고정 — 물리 8코어 박스면 포화점이 8 «밖» 이라 곡선이 안 닫힌다 |
| 유도(`:282`) | `cores in (2, 4, 8)` 하드코딩 + 측정된 포화가 아니라 프레임당 비용에서 유도 |
| 전반 | `os.cpu_count()` 는 **호스트 논리 CPU 를 보지 cgroup 상한을 안 본다.** `docker --cpus` 캡이 걸리면 조용히 틀린 코어 수로 나눈다 |

## ✅ P1 완료 (2026-08-11) — 결과는 [`../../loadtest/results/ai-recalibrate-2026-08-11/`](../../loadtest/results/ai-recalibrate-2026-08-11/)

스크립트를 호스트 적응형으로 고치고(사양 자동 검출 · cgroup 상한 인식 · 스윕 자동 확장 ·
3판+버림판+순서반전 · 코어당 세션 출력), `c7i.2xlarge`(Xeon 8488C, 물리 4코어)에서 재측정했다.

| | i3-6100 | Xeon 8488C | 배수 |
|---|---|---|---|
| 프레임당 추론 | 103.4ms | **17.6ms** | 5.9x |
| 포화 처리량 | 18.3 fps | **196.6 fps** | 10.7x |
| **물리 코어당 세션**(3fps) | 3.0 | **16.4** | **5.5x** |

**닫힌 것**: «AI 가 목표의 1/10» 판정은 **하드웨어 탓**이었다. 스케일아웃의 **용량 근거는 사라졌고**
남는 것은 **가용성 근거**(1대 죽으면 전원 끊김)뿐이다. GPU 는 논할 자리가 없어졌다.

⚠️ **다만 «한 대로 충분» 은 아니다.** 물리 4코어의 **상한**이 65.5세션인데 가정 피크는 **67** 이다 —
**상한조차 목표에 못 미친다.** 그리고 이 값은 «코어를 100% 추론에» 가정이라 HTTP·디코딩·GIL·#164 손실을
빼면 더 내려간다. 실제 배치는 **물리 8코어(`c7i.4xlarge`)급**을 봐야 한다.

**열린 것**: [B] 검출률이 **11/20 으로 정확히 재현**됐다 → **#164 는 CPU 성능과 무관한 구조 문제**이며
«장비 올리면 되지 않나» 로 미룰 수 없다.

**남은 것**: Docker·Python 3.12 조건에서의 값(측정은 베어메탈·3.11), 실제 카메라 프레임에서의 트래킹 유지율.

**열 때 지킬 것**
- 🔴 **T계열(t3/t4g) 금지** — CPU 크레딧 스로틀링이 측정을 통째로 오염시킨다. C·M 계열 고정 성능으로
- 🔴 `m6i.xlarge`(4vCPU=**물리 2**)는 지금과 같은 조건이다. 최소 `.2xlarge` 이상
- 🔴 `docker run --cpus` 캡 걸지 말 것
- 🔴 **원시 출력을 `loadtest/results/ai-recalibrate-<날짜>/` 에 커밋한 «뒤» 인스턴스를 내릴 것** —
  [`pool-cliff-2026-08-08/README.md`](../../loadtest/results/pool-cliff-2026-08-08/README.md) 맨 위 경고가 정확히 이 실패다

**얻는 값** — «물리 코어당 동시 세션». 장비를 옮겨도 옮겨 쓸 수 있는 유일한 수다.

---

## ~~P2. 🔴 `exercises` 시드 누락 (버그)~~ → ❌ **오탐이었다 (2026-08-12 정정)**

**시드는 있다.** [`V2__seed_master_data.sql:26,30,33`](../../backend/src/main/resources/db/migration/V2__seed_master_data.sql)
이 스쿼트(id=1, `analysis_supported=TRUE`) · 런지(2) · 플랭크(3) 3종을 넣는다.
버그가 아니므로 이슈를 올리지 않았다.

### 왜 «없다» 고 판정했나 — grep 패턴이 좁았다

초판은 `INSERT INTO exercises` 로 전수 확인했는데, **V2 는 `REPLACE INTO exercises` 를 쓴다.**
같은 파일에서 피드백 템플릿만 `INSERT INTO` 라 «템플릿은 있고 종목은 없다» 로 보였다.
(마이그레이션이 재적용돼도 안전하도록 종목만 `REPLACE` 를 쓴 것이고, 의도된 차이다.)

### 그리고 인용이 반쪽이었다

초판은 [`mysql/dev-seed.sql:11`](../../mysql/dev-seed.sql) 을 *"운영에도 필요한 마스터 데이터(exercises,
피드백 템플릿)는 여기 없다"* 까지만 인용했는데, 그 문장은 다음 줄에서 **어디에 있는지를 직접 가리킨다**:

```
-- 운영에도 필요한 마스터 데이터(exercises, 피드백 템플릿)는 여기 없다 →
--   backend/src/main/resources/db/migration/V2__seed_master_data.sql
```

따라서 *"`dev-seed.sql:11` 의 문장은 고쳐야 한다"* 도 철회한다 — **그 서술은 처음부터 맞았다.**

⚠️ **여전히 미검증** — SQL 파일이 있다는 것까지만 확인했다. 새 환경에서 Flyway 가 V2 를
끝까지 적용하는지는 **돌려보지 않았다.** E1(시연 한 바퀴)이 이걸 겸해서 덮는다.

📌 **남는 교훈** — «전수 확인» 이라고 적었지만 실제로 전수인 것은 **grep 패턴 하나**였다.
DML 을 셀 때 `INSERT` 만 보면 `REPLACE`·`INSERT ... ON DUPLICATE KEY`·`LOAD DATA` 가 통째로 샌다.

**부수(이건 실재)** — ~~`mysql/schema.sql` 과 `mysql/data.sql` 이 **빈 디렉터리**로 남아 있다.~~
→ ✅ **삭제 완료(2026-08-12).** Flyway 전환(#115) 때 바인드마운트가 만든 잔재였다.

---

## P3. 입장 제한 (admission control)

**무엇** — AI 가 포화됐을 때 Spring 이 세션 시작을 거절하는 장치가 없다.
지금은 7번째 사용자가 들어와도 그냥 받고, **다 같이 느려지고 프레임이 버려진다.**
용량을 못 늘리더라도 **깨지는 방식**은 고를 수 있는데, 그걸 안 고르고 있다.

**왜 Spring 일인가** — 프레임은 폰→FastAPI 로 직행하지만, **세션 시작은 Spring 이 소유한다.**
활성 세션 수를 셀 재료도 이미 있다([`SessionRepository.java:149`](../../backend/src/main/java/com/shadowfit/repository/exercise/SessionRepository.java) —
`SELECT s.status, COUNT(s) ... GROUP BY s.status`).

**✅ 2026-08-11 — 막고 있던 것이 풀렸다.** 상한값의 근거가 없어서 미뤄뒀는데(«N» 을 근거 없이 박으면
[[feedback_no_arbitrary_threshold_values]] 위반), **P1 이 «물리 코어당 16.4세션(상한)» 을 냈다.**

**열 조건** — **없다. 이제 열 수 있다.** 다만 N 은 «16.4 × 물리코어» 를 **그대로 쓰면 안 된다** —
그건 상한이다. HTTP·디코딩·GIL·#164 손실을 감안한 안전계수를 어떻게 잡을지가 설계 대상이고,
그 계수 자체도 근거가 필요하다(실측 부하에서 «몇 %에서 프레임이 버려지기 시작하나»).

**열 때 정할 것** — 거절의 «모양» 도 설계 대상이다: 즉시 거절 / 대기열 / 저품질 모드(fps 하향).
셋은 사용자 경험이 전혀 다르다.

---

## P4. `innodb_redo_log_capacity` — «후보로 지목했으나 안 열었다»

**무엇** — 다세션 **649 RPS 천장의 정체가 미규명**이다([#166](https://github.com/Shadowfit/init/issues/166)).
fsync 레버는 안 먹었고(다세션에서 3.47배 → **1.03배**), 커밋 횟수 레버는 **음수**였다.
**redo 체크포인트 스로틀이면 그 두 결과와 모순되지 않는다** — 현재 가장 그럴듯한 후보다.

관련해 현재 설정 실태:

- `mysql/my.cnf` → **문자셋만** 한다
- `docker-compose.yml:41-42` → `--innodb-buffer-pool-size=2G`, `--innodb-sort-buffer-size=64M` **두 줄**
- 나머지는 전부 MySQL 8 기본값. `innodb_redo_log_capacity` · `innodb_io_capacity` ·
  `innodb_flush_method` 전부 기본이다

**왜 안 여나** — **이건 쓰기 축 5차 실험이다.**
[`project-destination-and-exit-criteria.md §4`](../decisions/project-destination-and-exit-criteria.md) 가
*"실험 차수 늘리기 — 쓰기 축은 이미 4차다. 5차는 수확체감이고 읽는 사람이 안 따라온다"* 로 이미 잘라 둔 자리다.

**열 조건** — **목적지 재검토가 선행되어야 한다.** 즉 사실상 안 연다.

**그래서 이 항목의 산출물은 측정이 아니라 이 문단 자체다.** 면접에서 «천장의 정체가 뭐죠?» 가 오면:

> "미규명입니다. 다만 후보까지는 좁혔습니다 — fsync 는 다세션에서 효과가 1.03배로 사라졌고
> 커밋 횟수 레버는 음수였습니다. redo 체크포인트 스로틀이 두 결과와 모순되지 않아서 유력 후보로 봅니다.
> 5차 실험이라 열지 않기로 했습니다."

**«모른다» 가 아니라 «여기까지 좁혔고 왜 더 안 갔는지» 가 된다 — E4 가 요구하는 모양이다.**

---

## P5. 🔴 `joint_coordinates` 가 write-only 다 — 저장한 좌표를 아무도 안 읽는다

**무엇** — 프레임마다 쓰는 2.3KB JSON 을 **읽는 경로가 없다.**

| | |
|---|---|
| 쓰기 | [`PoseDataService.java:42`](../../backend/src/main/java/com/shadowfit/service/Exercise/PoseDataService.java) INSERT · `:221` gRPC 수신 — 프레임마다 |
| 읽기 | [`PoseFrameProjection.java:18`](../../backend/src/main/java/com/shadowfit/dto/report/PoseFrameProjection.java) — *"`jointCoordinates` 를 싣지 않는 방침은 유지된다"* |
| API 노출 | **없음.** report DTO 어디에도 좌표 필드가 없다 |

그런데 [`SessionAnalysisCalculator.java:156`](../../backend/src/main/java/com/shadowfit/service/Report/SessionAnalysisCalculator.java) 은 용도를 명시한다:

> *"이 선택은 두 가지를 결정한다: 리포트의 `timeStamp`, 그리고 그 프레임의 **`jointCoordinates`(= 앱이 그릴 자세)**"*

**용도가 설계돼 있는데 꺼내는 쿼리도 응답 필드도 없다.** 즉 1억 행 규모로 쓰이고 **한 번도 안 읽힌 채 `DROP PARTITION` 으로 지워진다.**

⚠️ Java 전수 grep 기준(2026-08-11). 네이티브 쿼리 등 다른 경로가 있으면 정정할 것.

### 기능은 세 층으로 갈린다

| 층 | 내용 | JSON 질의 필요? |
|---|---|:--:|
| **Tier 0** ⭐ | **worst rep 의 자세를 앱에 그려주기.** 대표 프레임 고르는 로직(`pickRepresentative`)과 그 근거는 **이미 있다.** 없는 건 «고른 1프레임의 JSON 을 꺼내는 쿼리 하나 + 응답 필드 하나» | ❌ PK 로 한 행 |
| **Tier 1** | rep 재생(애니메이션) · 기준 자세와 겹쳐 보기 · **과거 세션 재채점(backfill)** | ❌ 통째 읽기 |
| **Tier 2** | 교차 세션 집계(«무릎이 무너진 프레임 비율», 데이터 품질) · 코호트 분석 | ✅ |

- **Tier 0 은 projection 방침을 안 깬다** — 스캔은 여전히 좌표 없이 하고, 고른 1프레임만 따로 읽는다.
- **Tier 2 는 열지 않는다.** [`realmysql-experiments.md §217`](../../docs/portfolio/realmysql-experiments.md) 이
  이미 *"프로젝트 실수요 0(JSON 내부값으로 검색 안 함)"* 으로 미수행 결정을 박아뒀고, 그 판단은 유효하다.
  하더라도 MySQL 에서 generated column + 인덱스로 되므로 **엔진 논쟁과 무관**하다
  ([`../decisions/mysql-vs-postgresql.md §3`](../decisions/mysql-vs-postgresql.md)).

### 왜 지금 안 하나

Tier 0 은 **싸다** — 쿼리 하나와 필드 하나다. 그래서 «비용이 커서» 미루는 게 아니다.

미루는 이유는 **지금 국면이 «측정이 아니라 압축»** 이고, 새 기능은
[`project-destination-and-exit-criteria.md §4`](../decisions/project-destination-and-exit-criteria.md) 가 목적지가 아니라고 잘라둔 항목이기 때문이다.

🔶 **다만 Tier 0 은 «기능 목록 채우기» 와 성격이 다르다** — 새 기능이 아니라 **이미 설계된 것의 미완성**이고,
§2 의 «도는 앱»·E1 에 기여하며, **이 프로젝트에서 화면에 보이는 거의 유일한 차별점 후보**다.
(§2 가 지적한 문제가 정확히 «앱 화면으로는 아무것도 안 보인다» 였다.)
그래서 §4 를 근거로 자동 기각하지 않고 **판단을 사용자에게 남긴다**([[feedback_user_decides_not_claude]]).

**열 조건** — 둘 중 하나:
1. E1(시연 한 바퀴)을 통과시킨 뒤, «화면에 보이는 것» 이 필요하다고 판단할 때
2. *"이 좌표를 왜 저장하세요?"* 에 대한 답이 필요해질 때 — 지금은 답이 약하다

**부수 이득** — Tier 0 을 만들면 **raw 좌표의 보존 정책에 근거가 생긴다.** 지금은 저장이 먼저고 용도가 없어서,
TTL 을 며칠로 잡을지에 근거가 없다.

---

## 열리는 순서

```
P2 (시드 누락)          ── ❌ 오탐. 열 것이 없다
P5 (Tier 0 자세 그리기) ── 독립. 싸다. 판단만 남았다
P1 (재측정) ──▶ P3 (입장 제한)      ← 상한값의 근거가 P1 의 산출물
P4 (redo)  ── 안 연다. 문단으로 닫힘
P5-Tier2   ── 안 연다. 기존 미수행 결정 유지
```

**P4 와 P5-Tier2 는 이미 닫혔다**(문서에 박제되는 것이 산출물). **P2 는 오탐으로 닫혔다.**
실질적으로 열려 있는 건 **P5-Tier0 · P1→P3 사슬** 둘이다.

---

## 결정 로그

- 2026-08-11: 4건 보류로 기록. **P4 는 «안 여는 것» 으로 사실상 종결**(산출물 = 위 문단).
  P2 는 버그라 보류 대상이 아니며 이슈 등록 필요. P1→P3 는 순서 종속.
- 2026-08-11: **P5 추가** — `joint_coordinates` write-only 발견. Tier 0/1/2 로 분리하고
  **Tier 2 는 기존 미수행 결정(realmysql §217) 유지로 종결**. Tier 0 은 비용이 아니라
  §4(목적지) 때문에 보류이며 **판단은 사용자 몫**으로 남긴다.
- 2026-08-12: **P2 를 오탐으로 정정·종결.** 시드는 `V2__seed_master_data.sql` 에 `REPLACE INTO`
  로 있었고, 초판의 «전수 확인» 은 `INSERT INTO` 한 패턴이었다. `dev-seed.sql:11` 문장을
  고쳐야 한다는 지적도 철회(그 줄은 V2 경로를 직접 가리킨다). 이슈는 올리지 않았다.
  부수 항목이던 빈 디렉터리 `mysql/schema.sql`·`mysql/data.sql` 은 삭제했다.
