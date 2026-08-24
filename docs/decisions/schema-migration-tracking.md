# 스키마 마이그레이션 적용 이력 — 무엇으로 추적할 것인가

작성: 2026-08-07
상태: **✅ ㄱ(Flyway) 채택 확정 (2026-08-07 confirm)** — 결정 내역은 §8
발단: [이슈 #115](https://github.com/Shadowfit/init/issues/115) — dev DB 가 `schema.sql` 과 2건 벌어져 있었고, 그 결과 `UPDATE ... SET last_active_at` 이 `Unknown column` 으로 실패한다
연관: [`session-liveness-vs-elapsed-time.md`](./session-liveness-vs-elapsed-time.md)(문제의 컬럼을 도입한 결정) · [`../tasks/24-semester2-plan.md`](../tasks/24-semester2-plan.md) OP-04(이미 계획돼 있던 항목) · [`../tasks/28-remaining-work-plan.md`](../tasks/28-remaining-work-plan.md) §2-4(CD)

---

## 0. 한 줄 요약

이슈는 *"적용 이력을 추적하는 장치가 없다"* 로 제목을 달았는데, 실제로 관측된 피해(§2 의 깨지는 UPDATE)는 **이력이 없어서가 아니라 적용을 빠뜨려서** 났다. 이 둘은 다른 문제고 **다른 도구가 푼다** — Flyway 는 빠뜨림을 막지만 드리프트는 못 본다(§3).

그리고 지금 이 문제가 아픈 환경은 **사실상 1대뿐**이다(§4). 도입 근거는 "지금 아프다"가 아니라 **"[#4 CD](../tasks/28-remaining-work-plan.md) 가 붙는 순간 배포마다 아파진다"** 로 잡는 게 정직하다.

---

## 1. 스키마 소스가 둘이 아니라 셋이다

이슈는 `schema.sql` ↔ `migrations/` 의 두 소스로 정리했는데, **테스트 DB 라는 세 번째 소스가 있다.**

| # | 소스 | 무엇을 만드나 | 누가 검사하나 |
|:--:|---|---|---|
| ① | **Java 엔티티** (`ddl-auto: create-drop`, `src/test/resources/application.yml`) | 테스트 DB | (자기 자신) |
| ② | **`mysql/schema.sql`** (`ddl-auto: none`, initdb) | 신규 설치 DB | `SchemaEnumConsistencyTest` 가 ①↔② 의 **ENUM 만** |
| ③ | **`mysql/migrations/`** | 기존 인스턴스 | **아무도** |

`SchemaEnumConsistencyTest` 의 클래스 주석이 이 구조를 이미 정확히 짚고 있다 — *"테스트 DB 는 Java 엔티티에서 스키마를 생성한다. 즉 Java 와 `schema.sql` 이 어긋나도 테스트 쪽에는 언제나 Java 기준 스키마가 만들어져 초록불이 뜬다."* ([`SchemaEnumConsistencyTest.java:37-41`](../../backend/src/test/java/com/shadowfit/global/SchemaEnumConsistencyTest.java#L37-L41))

**#115 는 그 관찰의 확장판이다.** 그 테스트는 ①↔② 를 ENUM 한정으로 보지만, **실행 중인 DB 는 어느 소스와도 대조되지 않는다.** ③ 은 파일이 쌓이기만 하고 무엇이 돌았는지 아무 데도 안 남는다.

> 📌 그래서 `last_active_at` 이 **엔티티에도 있고**(`Session.java:93`) **`schema.sql` 에도 있는데**(`schema.sql:84`) 테스트는 전부 초록이었다. 두 소스가 서로 일치했고, 어긋난 것은 **셋 중 어디에도 안 잡히는 실제 DB** 였다.

---

## 2. 왜 하필 이 컬럼에서 터졌나 — 우연이 아니다

`last_active_at` 을 쓰는 코드가 **JPA 가 아니라 raw JDBC** 다:

```java
// PoseDataService.java:114
jdbcTemplate.update("UPDATE exercise_sessions SET last_active_at = ? WHERE id = ?", ...)
```

JPA 경로였다면 `ddl-auto: validate` 로 부팅 시 막을 수 있었다(현재는 `none`). raw JDBC 는 그 그물에도 안 걸린다 — **쿼리가 실제로 실행되는 순간까지 아무도 모른다.**

> 🔶 **미검증**: 이슈 §2 의 단서를 그대로 유지한다. 확인된 것은 *"이 DB 에서 위 UPDATE 문 자체가 실패한다"* 뿐이고, 앱을 띄워 요청을 흘려본 것은 아니다. 엔티티 매핑(`Session.java:93`)이 있으므로 더 이른 시점에 다른 방식으로 터질 가능성은 확인하지 않았다.

그리고 이 마이그레이션 파일 자체가 **소급 보완물**이다 — [`2026-08-03-add-sessions-last-active-at.sql:13-16`](../../mysql/migrations/2026-08-03-add-sessions-last-active-at.sql#L13-L16) 이 *"PR #102 는 `schema.sql` 만 고치고 이 파일을 빠뜨린 채 머지됐다"* 고 스스로 적어놨다. **같은 종류의 빠뜨림이 두 번 났고, 두 번째는 그 파일을 돌리는 것을 빠뜨린 것이다.**

---

## 3. 🔴 Flyway 는 드리프트를 탐지하지 않는다

선택지를 비교하기 전에 이것부터 못박아야 한다. 흔한 오해라 여기서 틀리면 §5 표를 잘못 읽는다.

| | 막는 것 | 못 막는 것 |
|---|---|---|
| **Flyway (ㄱ)** | *"파일이 있는데 안 돌았다"* — 부팅 시 미적용분을 감지해 적용하거나 멈춘다 | *"누가 손으로 DB 를 바꿨다"* — Flyway 는 **자기 파일의 실행 여부만** `flyway_schema_history` 에 기록한다. 실제 스키마를 읽지 않는다 |
| **드리프트 탐지 (ㄴ)** | *"실제 DB 가 기대와 다르다"* — 원인이 무엇이든 | **적용은 여전히 사람이** 한다. 탐지가 곧 수리가 아니다 |

**두 선택지는 경쟁 관계가 아니라 서로 다른 구멍을 막는다.** #115 에서 관측된 사고는 ㄱ 이 막았을 사고고, "EC2 가 어디까지 갔는지 모른다"(이슈 §4)는 ㄴ 이 답하는 질문이다.

### 3-1. ⚠️ baseline 함정 — 순서가 강제된다

지금 dev DB 에 Flyway 를 baseline 으로 얹으면, Flyway 는 **"기존 스키마는 이미 완성된 상태"** 로 도장을 찍고 baseline 이전 파일을 **영구히 건너뛴다.** 즉 미적용 2건이 **영원히 안 돌고, 대신 "적용됨"으로 기록된다** — 관측 가능하던 문제가 관측 불가능해진다.

> **그래서 ㄱ 을 고르든 말든 [이슈 §6 의 수동 적용이 먼저다.** 이건 선택이 아니라 선후 관계다.

---

## 4. 환경이 몇 대인가 — 이슈의 "각자 다른 지점" 재검토

이슈 §4 는 *"다른 개발 환경·CI·EC2 배포본이 각자 다른 지점에 가 있을 가능성이 높다"* 를 이 이슈의 핵심으로 뒀다. **현재 사실관계로는 그 위험이 아직 실현되지 않았다.**

| 환경 | 상태 |
|---|---|
| 로컬 dev DB | **1대.** 드리프트 관측된 그 DB |
| CI | `ddl-auto: create-drop` — 매번 엔티티에서 새로 만든다. 마이그레이션과 무관 |
| EC2 | **상시 인스턴스 없음.** 2026-07-25 풀 사이징 재검증 2대 · 2026-08-08 격자 재측정 3대(app·db·obs) 모두 **실측 후 삭제**([[풀 사이징 AWS 재검증]], [`pool-cliff-vs-concurrency.md`](./pool-cliff-vs-concurrency.md)) |
| 부하테스트 스크래치 DB | `schema.sql` 을 매번 통째로 적용해 만들고 버린다([`measure_admin_filter_explain.sh:72-88`](../../loadtest/measure_admin_filter_explain.sh#L72-L88)) |

→ **"누가 어디까지 돌렸는지 모른다"는 지금 1대짜리 문제다.** 다만 [#4 CD](../tasks/28-remaining-work-plan.md#4-cd--배포-문서-713h--59h) 가 남은 4덩어리 중 **착수 2순위**이고, CD 가 붙는 순간 "배포는 자동인데 스키마 적용은 수동"이 되어 **배포마다 재발하는 구조**가 된다.

> 이게 이 문서의 실질적 논거다: **지금 고치는 비용보다 CD 위에서 고치는 비용이 비싸다.** 순서상 CD 앞에 두는 것이 싸다.

---

## 5. 선택지 비교

이슈 §5 의 ㄱ~ㄹ 을 사실관계로 채운다. **시간은 전부 추정이다**([[모든 건 현업 수준으로]] 기준으로도 추정은 추정이다).

| | 내용 | 얻는 것 | 대가 | 추정 |
|:--:|---|---|---|:--:|
| **ㄱ** | Flyway 도입 + baseline | 빠뜨림이 **부팅 시** 잡힌다. CD 의 선결이 풀린다. 채용 시그널 | 의존성 2개. `schema.sql` 역할 재정리. **§3-1 선행 조건** | **4~7h** |
| **ㄴ** | 드리프트 탐지 스크립트 (`information_schema` ↔ 기대값) | 원인 불문 "모르는 상태"가 사라진다. EC2·CI 어디서든 1회 실행 | 적용은 수동 그대로. **비교 범위를 못박아야 함**(§5-2) | **2~4h** |
| **ㄷ** | README 에 적용 순서·기대 상태 문서화 | 0 에서 1 | 사람이 지켜야 함. **PR #102 가 정확히 여기서 실패했다** — 이미 파일 주석에 다 적혀 있었는데도 빠뜨렸다 | 0.5h |
| **ㄹ** | 볼륨 폐기 + `schema.sql` 재생성을 표준 절차로 | 절차가 단순해진다 | **이 프로젝트에서 특히 비싸다** — §5-1 | 0h |

### 5-1. ㄹ 이 이 프로젝트에서 유독 비싼 이유

일반론("dev 는 되지만 데이터 있는 환경엔 못 씀")보다 구체적인 문제가 있다. 이 프로젝트에서 **볼륨을 날린다는 것은 부하 실험 기반을 날린다는 뜻**이고, 그 재구축 비용이 크다 — 1억 행 시딩은 **로컬에서 16~48분**이 걸린 작업이다([`realmysql-experiments.md §3`](../portfolio/realmysql-experiments.md), `loadtest/seed/seed_pose_scale.sh`). 볼륨 폐기를 표준 절차로 삼으면 **스키마를 고칠 때마다 그 비용을 낸다.**

> 📌 **2026-08-07 실측 정정.** 이 문서 초판은 *"dev 볼륨에 1억 행 / ~11GB 가 들어 있다"* 고 현재형으로 썼는데 **사실이 아니다.** 지금 볼륨의 실제 상태는 이렇다:
>
> | 스키마 | 크기 | 내용 |
> |---|:--:|---|
> | `shadowfit` | **0.00 GB** | `pose_data` 0행. 애플리케이션 데이터만 소량(세션 7행) |
> | `shadowfit_idx110` | 0.88 GB | [#110](https://github.com/Shadowfit/init/issues/110) 팬아웃 rig (`es_f5`/`es_f500`/`es_f2000` 각 ~100만 행) |
>
> 1억 행은 `pose_data` 가 아니라 **`pose_data_scale` 이라는 별도 테이블**에 있었고(2026-06-03), 이미 삭제된 상태다. 즉 ㄹ 의 대가는 *"지금 들어 있는 것을 잃는다"*가 아니라 **"다시 만드는 데 드는 시간"**이다. 결론(ㄹ 비추천)은 그대로지만 근거의 성격이 다르다.

게다가 이 프로젝트의 실험은 대부분 "적재된 상태 위에서 재는 것"이라([`db-portfolio-roadmap.md §7`](./db-portfolio-roadmap.md)), 볼륨 폐기는 측정 기반 자체를 지우는 행위다. 위 `shadowfit_idx110` 이 그 예다 — **지금 실제로 볼륨에 살아 있는 실험 자산**이고, ㄹ 을 표준으로 삼으면 이것이 매번 날아간다.

### 5-2. ⚠️ ㄴ 을 고르면 비교 범위를 먼저 못박아야 한다

`information_schema` 를 `schema.sql` 기대값과 **통짜로 비교하면 매일 다른 답이 나온다.** `PoseDataPartitionScheduler` 가 매일 04:00 에 `pose_data` 의 파티션을 **추가·삭제**하기 때문이다([`PoseDataPartitionScheduler.java:55`](../../backend/src/main/java/com/shadowfit/service/exercise/PoseDataPartitionScheduler.java#L55)).

즉 **파티션 목록은 정당하게 드리프트한다** — 그건 결함이 아니라 설계된 동작이다. 비교 대상을 **컬럼과 인덱스로 한정**하고 파티션은 제외해야 신호가 된다. (이건 구현 난이도가 아니라 **범위 정의**의 문제고, 안 정하면 경보가 매일 울려 아무도 안 보게 된다.)

### 5-3. ㄱ 의 대가 — `schema.sql` 을 지워야 하나

**아니다. 지울 필요 없고, 지우면 세 곳이 깨진다.**

| `schema.sql` 을 읽는 곳 | 깨지면 |
|---|---|
| `docker-compose.yml:18-20` initdb | 신규 설치 경로 |
| `SchemaEnumConsistencyTest` | ①↔② ENUM 가드가 사라짐 |
| `measure_admin_filter_explain.sh:75` | 관리자 필터 실측 rig |

Flyway 는 이 파일을 그대로 **`V1__baseline.sql` 로 캡처**해 쓸 수 있다([`24-semester2-plan.md:212`](../tasks/24-semester2-plan.md) 가 이미 같은 방식을 리스크 대응으로 적어뒀다). 다만 그러면 **"신규 설치는 initdb, 기존은 Flyway"** 라는 이중 경로가 남으므로, 어느 쪽을 정본으로 삼을지는 ㄱ 을 고를 때 같이 정해야 한다 — 그게 위 4~7h 추정의 폭이다.

---

## 6. 이미 계획에 있던 항목이다

새 제안이 아니다. [`24-semester2-plan.md:42`](../tasks/24-semester2-plan.md) 가 **OP-04 (Flyway 도입, 베이스라인 잡기) 4h** 를 2학기 Week 1 에, 리스크 대응(`:212`)까지 함께 적어놨다. Week 1 마일스톤이 *"CI 동작, Flyway 베이스라인 확정"* 이다.

**#115 는 그 미이행 계획이 실제 결함으로 나타난 것이다.** 그래서 "할까 말까"보다 **"2학기 Week 1 로 미룰까, 지금 당길까"** 가 실제 질문에 가깝다.

- 당길 근거: #4 CD 가 착수 2순위고 순서상 앞에 두는 게 싸다(§4). dev DB 가 실제로 깨졌던 전력이 있다(§8-1 에서 해소 확인 — 다만 **적용된 것을 확인했을 뿐 누가 언제 했는지는 여전히 모른다**)
- 미룰 근거: 지금 아픈 환경이 1대뿐이고, 그 1대는 §6 수동 적용으로 즉시 해결된다. 4~7h 를 남은 4덩어리에서 빼야 한다

---

## 7. 추천 → ✅ 채택됨

> 아래는 작성 시점의 **추천**이었고, 2026-08-07 사용자 confirm 으로 **그대로 채택**됐다. 확정 내역과 그때 함께 못박은 조건들은 §8 참조.

**0단계 (선택과 무관하게 먼저) — ✅ 완료 확인 (2026-08-07)** — 이슈 §6 의 수동 적용 3건. §3-1 때문에 ㄱ 을 고르면 **반드시 선행**이다.

```
mysql/migrations/2026-08-03-add-admin-list-indexes.sql
mysql/migrations/2026-08-03-add-sessions-last-active-at.sql
mysql/migrations/2026-08-07-consolidate-session-member-indexes.sql   # 반드시 마지막
```

dev DB 를 실측한 결과 **3건 모두 이미 반영돼 있었다.** 검증 내역은 §8-1.

**추천: ㄱ (Flyway), 단 #4 CD 착수 직전에.** 근거 셋:

1. **순서 논거** (§4) — CD 가 붙으면 배포마다 재발한다. 지금 넣는 게 나중보다 싸다
2. **이미 계획된 항목** (§6) — OP-04 를 앞당기는 것이지 범위를 늘리는 게 아니다
3. **구현 %에 안 잡히는 빈칸을 메운다** — 백엔드 신입 채용에서 마이그레이션 도구는 단골 시그널이고([[백엔드 포지션 지원]]), 현재 이 자리는 비어 있다

**ㄴ 은 ㄱ 의 대안이 아니라 별건이다** (§3). ㄱ 을 넣어도 "손으로 바꾼 DB"는 여전히 안 보인다. 다만 그 구멍이 지금 아픈지는 근거가 없으므로 **지금 판단하지 말고 열어둔다.**

**ㄷ 단독은 추천하지 않는다.** PR #102 가 정확히 그 방식으로 실패했다 — 마이그레이션 파일마다 *"Flyway 가 없어 기존 인스턴스는 이 디렉터리가 담당한다"* 가 이미 적혀 있었는데도 빠뜨렸다. 문서가 없어서 난 사고가 아니다.

---

## 8. 결정 로그

- 2026-08-07: 문서 작성. [이슈 #115](https://github.com/Shadowfit/init/issues/115) 의 ㄱ~ㄹ 을 사실관계로 채우고 **ㄱ(#4 CD 직전) 추천**.
- 2026-08-07: **✅ ㄱ(Flyway 도입 + baseline) 채택 확정** — 사용자 confirm. §7 추천 그대로.
  - **착수 시점**: [#4 CD](../tasks/28-remaining-work-plan.md#4-cd--배포-문서-713h--59h) 직전. CD 가 붙으면 "배포는 자동, 스키마 적용은 수동"이 되어 배포마다 재발하는 구조가 되므로(§4) 순서상 앞에 둔다
  - **선행 조건**: §7 0단계 수동 적용 3건이 **반드시 먼저**. baseline 을 먼저 찍으면 미적용 2건이 영구히 건너뛰어지고 대신 "적용됨"으로 기록된다(§3-1)
  - **`schema.sql` 은 유지**한다(§5-3). 지우면 initdb·`SchemaEnumConsistencyTest`·관리자 필터 실측 rig 세 곳이 깨진다. `V1__baseline.sql` 로 캡처하는 방식을 쓰되, 신규 설치(initdb)와 기존(Flyway) 중 어느 쪽을 정본으로 삼을지는 **구현 시 결정**한다 — 4~7h 추정 폭이 여기서 나온다
  - **ㄴ(드리프트 탐지)은 채택하지 않는다.** 기각이 아니라 **별건으로 열어둔다**(§3) — ㄱ 을 넣어도 "손으로 바꾼 DB"는 여전히 안 보이지만, 그 구멍이 지금 아프다는 근거가 없다
  - **범위 밖**: OP-04 를 앞당기는 것이지 범위를 늘리는 게 아니다(§6). 이 결정에 CD 자체나 드리프트 탐지 구현은 포함되지 않는다
- 2026-08-07: **✅ 0단계(수동 적용 3건) 완료 확인** — 실측 결과 3건 모두 이미 반영. 상세는 §8-1. **Flyway baseline 의 선행 조건이 풀렸다.**

### 8-1. 0단계 검증 내역 (2026-08-07, 로컬 `shadowfit-mysql`)

`information_schema` 로 최종 상태를 대조했다. **DDL 을 새로 실행하지는 않았다** — 이 3건은 `IF NOT EXISTS` 를 못 써 멱등하지 않으므로(각 파일의 "멱등성" 주석) 상태 확인이 선행이었고, 확인 결과 실행할 것이 없었다.

| 마이그레이션 | 기대 | 실측 |
|---|---|:--:|
| `add-sessions-last-active-at` | `exercise_sessions.last_active_at` | ✅ |
| `add-admin-list-indexes` | `idx_session_status_starttime` | ✅ |
| ″ | `idx_users_created_at` | ✅ |
| `consolidate-session-member-indexes` | `idx_session_member_status_start` 추가 | ✅ |
| ″ | `idx_session_starttime_member` 추가 | ✅ |
| ″ | 옛 `idx_session_member_starttime` 제거 | ✅ 없음 |
| ″ | 옛 `idx_session_member_status` 제거 | ✅ 없음 |

`exercise_sessions` 보조 인덱스 최종 구성 = `exercise_id`(FK) · `member_exercise_status_start` · `member_status_start` · `starttime_member` · `status_starttime` — §5-3 의 목표 상태와 일치한다.

그리고 **§2 에서 깨지던 UPDATE 를 직접 실행해 확인했다:**

```sql
UPDATE exercise_sessions SET last_active_at = NOW() WHERE id = -1;   -- affected 0, 에러 없음
```

`Unknown column` 이 나지 않는다. #115 에서 **관측된 피해는 해소된 상태다.**

> 🔶 **여전히 미검증**: §2 의 단서는 그대로 남는다. 확인한 것은 *"이 문장이 파싱·실행된다"* 까지고, **앱을 띄워 실제 요청 경로로 흘려본 것은 아니다.**

> 📌 **누가 언제 적용했는지는 모른다.** `users` 의 `CREATE_TIME` 이 2026-08-07 15:41, `exercise_sessions` 가 16:32 로 마이그레이션 순서와 정합적이지만, `CREATE_TIME` 은 `ALTER` 로도 갱신되므로 근거로는 약하다. **이것이 정확히 §3 이 말한, `flyway_schema_history` 한 줄이면 끝났을 질문이다** — 결과적으로 이번 확인 자체가 ㄱ 의 도입 근거를 한 번 더 보여준 셈이다.
