# #204 EXPLAIN 스윕 — 리포트 읽기 경로 (2026-08-19 밤 ~ 08-20 새벽, 로컬)

이슈 [#204](https://github.com/Shadowfit/init/issues/204) §4 가 *"`EXPLAIN` 미실행. filesort·프루닝·커버링
여부는 전부 스키마를 읽고 낸 예상"* 이라고 못박아둔 것을 실행한 판이다.

- 팔: [`arm-A.md`](./arm-A.md) · [`arm-B.md`](./arm-B.md) · [`arm-C.md`](./arm-C.md) · [`covering-cost.md`](./covering-cost.md)
- rig: [`../../seed/seed_report_rig.sh`](../../seed/seed_report_rig.sh) · [`../../seed/seed_read_path_rig.sh`](../../seed/seed_read_path_rig.sh)
- 스윕: [`../../measure_report_query_explain.sh`](../../measure_report_query_explain.sh) · [`../../measure_report_covering_cost.sh`](../../measure_report_covering_cost.sh)

---

## 0. 한 줄

**셋 중 둘은 확인됐고, 하나는 «닫을 열쇠가 이미 만들어져 있었다»** — [#188](https://github.com/Shadowfit/init/issues/188)
멱등 키가 리포트 정렬을 공짜로 가져간다. 🔴 **단 그 키는 아직 `origin/main` 에 없다**(§2-ㄷ).

---

## 1. 팔 셋

| 팔 | pose_data 인덱스 | 뜻 |
|---|---|---|
| **A** | `idx_session_timestamp (session_id, timestamp_sec)` | **이슈가 쓰인 시점**의 스키마 |
| **B** | A + `uk_pose_event (session_id, rep_number, timestamp_sec, created_at)` | V6 ([#188](https://github.com/Shadowfit/init/issues/188)) — 🔴 **origin/main 에 없다.** 아래 §2-ㄷ 주의 |
| **C** | B + `idx_report_cover (session_id, rep_number, timestamp_sec, sync_rate, smoothed_knee_angle)` | §2 「후보 수정」의 커버링 인덱스 |

데이터: `pose_data` **1,590,434행 / 2,203세션 / 5,090MB**(data 4,995 · index 95).
버퍼풀 2GB를 **확실히 초과**한다 — 행 본체 접근이 메모리 안에서만 끝나지 않는 규모다.
12개 월별 파티션에 89k~168k 로 분산.

---

## 2. §2 의 세 주장 — 판정

### ㄱ. 파티션 프루닝이 안 걸린다 → ✅ **확인. 그리고 인덱스로는 못 고친다**

```
ARM A/B/C 전부:
  partitions: p2026_01,...,p2026_12,p2027_01,pfuture   ← 14개 전탐색
```

세 팔 어디서도 안 줄었다. 인덱스를 바꿔도 `WHERE` 에 `created_at` 이 없으면 프루닝은 안 온다.
`created_at` 범위를 같이 넘긴 판(R1p)만 **`p2026_01` 하나**로 준다.

**값싼 증거 하나**: `Handler_read_key` 가 R1 은 **14**, R1p 는 **1** 이다 — 파티션마다 인덱스
다이브를 한 번씩 하고 있었다는 뜻이고, 프루닝이 걸리면 그게 1회로 준다.

> ⚠️ 대조군이 이슈 주장을 뒷받침한다 — `countSince`(R5)는 `created_at` 하한이 있어
> `p2026_12,p2027_01,pfuture` **3개**로 프루닝됐다. 같은 테이블에서 **한쪽만 받고 있다**는
> §2-ㄱ 의 서술 그대로다.

### ㄴ. 커버링이 아니다 → ✅ **확인. 크기는 논리 페이지 4.85배**

계획 텍스트로만 두지 않고 [`covering-cost.md`](./covering-cost.md) 에서 따로 쟀다.
같은 팔(C, 인덱스 둘 다 존재) 안에서 `FORCE INDEX` 로만 경로를 가르고 **교대**로 6판:

| 경로 | 논리 페이지 접근(중앙값) |
|---|---|
| `uk_pose_event` (비커버링 — 행 본체로 간다) | **3,777.5** |
| `idx_report_cover` (커버링) | **779** |

**4.85배**, 델타 **2,999 페이지**. 커버링 쪽은 6판 전부 **정확히 779** 로 흔들림이 없다.

델타를 행수로 나누면 **2,999 / 750 = 3.999** — 행 하나를 꺼내는 데 페이지를 **4개**씩 친다.
🔶 그 4가 **클러스터드 인덱스 트리 깊이**(뿌리부터 다시 탄다)라는 것은 **해석이지 실측이 아니다.**
실측된 것은 「행당 4페이지」까지다.

### ㄷ. 정렬이 인덱스와 어긋난다 → ✅ **A 에서 확인. 단 열쇠가 이미 만들어져 있다**

| 팔 | R1 `Extra` | `Sort_rows` |
|---|---|---|
| **A** (이슈 시점) | `Using filesort` | **750** |
| **B** (#188 적용 시) | `NULL` | **0** |
| **C** | `Using index` | **0** |

이슈의 예상은 **A 에서 맞았다.** 그런데 그 뒤 들어온 V6 의 멱등 키
`uk_pose_event (session_id, rep_number, timestamp_sec, created_at)` 가
`WHERE session_id=? ORDER BY rep_number, timestamp_sec` 와 **선두 세 컬럼이 정확히 같은 순서**라,
옵티마이저가 그 인덱스를 골라 정렬을 통째로 지운다.

**멱등을 위해 넣은 키가 리포트 정렬 비용을 공짜로 가져간 것**이고, 설계 의도가 아니었다.
[`pose-batch-idempotency-implementation.md`](../../../docs/decisions/pose-batch-idempotency-implementation.md)
는 이 이득을 적어두지 않았다 — 쓰기 대가(§3)만 논의했다.

> 🔴 **아직 안 닫혔다.** `uk_pose_event` 를 만드는 V6 은 `origin/main` 에 **없다** —
> #188 작업 브랜치에만 있는 미머지 상태다(`origin/main` 의 마이그레이션은 V5 까지).
> 즉 **오늘 배포돼 있는 코드에서는 ㄷ 가 여전히 살아 있고**, #188 이 머지되는 순간
> 따로 손대지 않아도 닫힌다. 「고칠 것」이 아니라 **「머지되면 닫히는 것」** 으로 관리할 항목이다.

---

## 3. 보너스 — §1 의 분류 두 개가 #188 적용 시 내려간다

| 쿼리 | 이슈 §1 | ARM A (= 지금 main) | ARM B·C (#188 적용 시) | 판정 |
|---|---|---|---|---|
| `findMaxRepNumberBySessionId` | 🟡 *"`rep_number` 가 인덱스에 없어 세션 전 행을 본다"* | `rows: 750` · `Handler_read_next 750` | **`Select tables optimized away`** · `Handler_read_next` **없음** | 🟡 → 🟢 |
| `findRepAverageSyncRates` | 🟡 *"`GROUP BY rep_number` + `AVG` 둘 다 인덱스 밖"* | `Using temporary; Using filesort` · tmp 1개 · `Handler_read_key 764` | `Using index condition` (B) / `Using where; Using index` (C) · tmp **0** | 🟡 유지, 그러나 **임시 테이블은 사라졌다** |

`MAX(rep_number)` 가 `optimized away` 로 접히는 이유도 ㄷ 과 같다 — `uk_pose_event` 의
2번째 컬럼이 `rep_number` 라 인덱스 역스캔 한 방으로 끝난다. **750행 → 0행.**

🟢 로 분류됐던 것들은 그대로 맞았다:
- `findMaxTimestampSecBySessionId` — 세 팔 전부 `Select tables optimized away`
- `countSince` — 프루닝 3파티션 + `Using index`(커버링). 주석의 근거가 실측으로 확인됐다

---

## 4. 🔴 미검증 · 이 판에서 말하면 안 되는 것

1. **시간은 못 믿는다.** A→B→C 를 **고정 순서**로 돌려 「팔」과 「캐시 상태·판 순서」가 분리되지
   않는다(A 가 가장 차가운 버퍼풀에서 돌았다). 그래서 arm-\*.md 의 `EXPLAIN ANALYZE` 시간은
   **참고값**이다. 계획 모양·핸들러 카운터는 순서와 무관하므로 그대로 유효하다.
   커버링 판만 **교대 설계**로 이 교락을 뺐다(§2-ㄴ).
2. **R1p 의 시간 4판은 무효다.** 스크립트가 세션 id 만 치환하고 `created_at` 범위는 안 바꿔서
   나머지 세션은 `rows=0` 으로 돌았다. **계획·핸들러(1판)는 유효, 시간은 버릴 것.**
3. **쓰기 대가는 안 쟀다.** 커버링 인덱스 채택 판단은 **여전히 열려 있다** — #204 §2 가
   *"지금 판단하지 않는다"* 라고 적은 그대로다. 참고로 이 박스에서 **인덱스 빌드 자체가 1분 36초**
   (150만 행)였다. [#205](https://github.com/Shadowfit/init/issues/205) 카드 A 의 남은 반쪽이다.
4. **합성 분포 균일.** 세션이 같은 템플릿의 복제라 옵티마이저 카디널리티 추정의 «정확도» 는
   여기서 결론 낼 수 없다([`project_synthetic_data_distribution_limit`]). 이번에 본 것은
   **계획 모양·접근 행수·페이지 수**뿐이다.
5. **admin 경로는 안 봤다** — 이미
   [`admin-page-scope.md`](../../../docs/decisions/admin-page-scope.md) §4 가 EXPLAIN 을 갖고 있다.
   중복으로 재지 않았다.

---

## 5. 덤 — #205 의 막힌 전제 둘을 같이 풀었다

rig 을 만들다 **#205 카드 두 개가 현재 데이터로는 구조적으로 못 돌아간다**는 것을 발견했다:

| 카드 | 막혀 있던 것 | 지금 |
|---|---|---|
| **B** rep 단위 집계 | 템플릿의 rep 당 프레임이 **30 고정**이라 「프레임 가중 ≠ rep 가중」이 **같은 값**으로 나온다 | rep 당 3~30 으로 흩은 세션 200개 추가 → **73.917431 ↔ 74.000000 으로 갈린다**(주석의 주장이 데이터에서 재현됐다) |
| **C** 동시 세션 수 | seed204 는 525분 간격 × 15분 지속이라 **겹치는 쌍 = 0**(실측) | 50일 × 하루 1,000세션 추가 → **1일 구간에서 겹치는 쌍 26,847** |

같이 채운 것: 회원 1,007명(DISTINCT 집계용) · `reports` 52,210 · `daily_logs` 52,065.

---

## 6. 다음

- [ ] 커버링 인덱스의 **쓰기 대가** 대조 — #205 카드 A 의 나머지 반쪽. 채택은 그 뒤
- [ ] #205 카드 B·C 실행 (전제는 이제 갖춰졌다)
- [ ] 「행당 4페이지」의 4가 트리 깊이인지 확인 (해석 → 실측)
