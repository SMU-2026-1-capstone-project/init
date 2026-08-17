# 부하 페이로드를 어떻게 «매번 다르게» 만들 것인가 — 멱등 키가 rig 의 쓰기를 삼킨다

작성일: 2026-08-17
상태: **분석/추천 — 미결정** (결정 ✅ 는 사용자 confirm 후 §6)
대상: [#271](https://github.com/Shadowfit/init/issues/271) 을 고치는 **세 갈래**와 각각의 대가
연관: [`./session-spread-sweep.md`](./session-spread-sweep.md)(P5) ·
[`./pose-batch-idempotency-implementation.md`](./pose-batch-idempotency-implementation.md)(멱등 설계) ·
[`../../loadtest/AWS-RIDE-ALONG.md`](../../loadtest/AWS-RIDE-ALONG.md) ·
[#272](https://github.com/Shadowfit/init/issues/272) · [#273](https://github.com/Shadowfit/init/issues/273)

---

## 0. 무엇이 깨졌나

`uk_pose_event (session_id, rep_number, timestamp_sec, created_at)` 가 들어오면서
([`V6__add_pose_data_idempotency_key.sql:66`](../../backend/src/main/resources/db/migration/V6__add_pose_data_idempotency_key.sql)),
**부하 rig 이 보내는 두 번째 요청부터는 행을 만들지 않는다.** 네 열이 전부 상수이기 때문이다:

| 열 | 왜 상수인가 |
|---|---|
| `session_id` | 레벨이 정한다 |
| `rep_number` | 생성기가 이 필드를 **안 보낸다** → proto3 기본값 **0** ([`gen_batch_multi.py`](../../loadtest/ghz/gen_batch_multi.py)) |
| `timestamp_sec` | `f*0.1` 고정. `downsample()` 이 결정적이라 살아남는 5프레임도 같다 ([`PoseDataService.java:191`](../../backend/src/main/java/com/shadowfit/service/Exercise/PoseDataService.java)) |
| `created_at` | **세션 시작 시각**. 세션당 값 하나 — 그게 멱등의 근거다 (`PoseDataService.java:87`) |

`ON DUPLICATE KEY UPDATE` 는 에러가 아니라 **성공**이다. `fail=0` 에 RPS 도 정상으로 찍히므로
표를 봐서는 안 보인다. 상세는 [#271](https://github.com/Shadowfit/init/issues/271).

🔴 **이 문서는 「멱등을 되돌리자」를 다루지 않는다.** 멱등은 [#188](https://github.com/Shadowfit/init/issues/188) 로
채택된 결정이고 파티셔닝의 대가로 `created_at` 이 키에 낀 것도 이미 닫힌 이야기다.
여는 것은 **rig 쪽을 어떻게 고치나** 하나다.

---

## 1. 제약 — 왜 「그냥 다르게 만들면 되잖아」가 안 되나

**실측 (2026-08-17, `gen_batch_multi.py` 로 직접 생성):**

| 무엇 | 크기 |
|---|---:|
| 메시지 **1개**(세션 1개 · 25프레임) | **54,086 B** |
| 현행 배열(세션 100개) | **5,408,502 B** |

그리고 ghz 의 성질 둘 (`runner/data.go`):

1. 배열은 **`RequestNumber % len(배열)`** 로 순환한다 — 그래서 레벨이 곧 순환 주기다
2. 🔴 **데이터에 템플릿이 있으면 캐시가 꺼진다.** `ExecuteData()` 가 JSON 파싱 **앞에서**
   돌기 때문에, **요청마다 데이터 전체**를 템플릿 실행하고 다시 파싱한다

②가 이 문제의 실질적인 벽이다. 목표 처리량을 4차 baseline **649.4 RPS** 로 잡으면:

| 데이터 크기 | 초당 템플릿·파싱량 |
|---|---:|
| 5.4MB (현행 배열) | **3.5 GB/s** ← 성립하지 않는다 |
| 54KB (메시지 1개) | **35 MB/s** |

**중복을 배열 크기로 없애는 길도 막혀 있다.** 중복 0 이 되려면 배열 원소 수 ≥ 요청 수인데,
`N_REQ=30,000` 이면 **1.6GB** 다.

---

## 2. 선택지

### ㄱ. 현행 배열 + 템플릿 — ❌ 성립하지 않는다

배열은 그대로 두고 `repNumber` 만 `{{.RequestNumber}}` 로 바꾼다. 가장 작은 수정이지만
**요청마다 5.4MB 를 다시 파싱**한다(§1-②). 부하기가 대상보다 먼저 죽는다.

### ㄴ. 메시지 1개 + 템플릿 라우팅 — 🟢 추천

배열을 없애고 **메시지 하나**만 두되, 세션 라우팅을 배열 순환에서 **템플릿 산술**로 옮긴다.
ghz 는 [sprig](http://masterminds.github.io/sprig/) 함수를 지원하므로 `add`·`mod` 를 쓸 수 있다.

```
"sessionId": {{ add 901 (mod .RequestNumber <레벨>) }}
"repNumber": {{ .RequestNumber }}
```

- **라우팅이 그대로 재현된다** — `mod` 가 하는 일이 배열 순환이 하던 일과 같다.
  레벨 1 은 `mod 1` = 항상 901 이라 «단일 핫세션» 조건이 자연스럽게 표현된다
- 데이터가 **54KB** 라 템플릿 비용이 100배 작다(§1)
- `rep_number` 가 요청마다 달라지므로 유니크 키 4열 중 하나가 **확실히 움직인다**

🔴 **공짜가 아니다.** 초당 35MB 템플릿·파싱이 **새로 생긴다.** 2026-08-17 §T 가
「부하기는 천장이 아니다」를 실증했지만 그건 **템플릿이 없던 조건**이다. 그 판정을 이 조건으로
그대로 물려받으면 안 된다 — **리허설에서 부하기 CPU 와 달성 rate 를 먼저 본다.**

### ㄷ. 배열 확대 + `N_REQ` 축소 — 🟡 가능하지만 다른 것을 깎는다

배열 원소를 「세션 × 반복」으로 늘리고 요청 수를 그만큼 줄인다. 템플릿을 안 쓰므로 ghz 가
데이터를 **한 번만** 파싱한다(캐시 유지). 대신 `N_REQ` 가 줄어든다 —
`N_REQ=30,000` 은 [`session-spread-sweep.md` §8](./session-spread-sweep.md) 이
**스크레이프 격자에 못 미치는 판을 피하려고** 고른 값이다. 3,000 으로 줄이면 162MB 짜리
페이로드를 매 판 올려야 하고, 판이 10분의 1로 짧아진다.

### ㄹ. 유니크 키를 떼고 잰다 — ❌ 반대

프로덕션에 있는 것을 없앤 채로 재면 **다른 시스템을 재는 것**이다. 게다가 그 키의 대가는
그 자체로 미측정 항목이다([#272](https://github.com/Shadowfit/init/issues/272)).

---

## 3. 추천 — ㄴ, 단 리허설을 게이트로 건다

ㄴ 이 유일하게 **판 설계를 안 깎는** 안이다(레벨·반복·`N_REQ` 를 그대로 둔다). 대신 새 위험을
하나 들여오므로 **본 라운드 전에 축소 리허설에서 다음 둘을 본다**:

- 부하기 CPU — 템플릿·파싱이 코어를 먹는지
- **달성 rate vs 목표** — ghz 가 요청을 제때 못 내보내면 처리량이 아니라 **부하기를 재게 된다**

둘 중 하나라도 걸리면 ㄷ 으로 간다. 그때는 `N_REQ` 축소가 **판 길이를 깎는다**는 것을 알고
고르는 것이지, 몰라서 밀리는 것이 아니다.

---

## 4. 어느 쪽을 골라도 남는 것

🔴 **정본 baseline 649.4 RPS 와는 나란히 못 놓는다.** 그 값은 `uk_pose_event` 가 **없던**
스키마에서 나왔고, 유니크 secondary index 는 change buffer 를 못 쓴다 — 쓰기 경로에 랜덤
읽기가 붙었는데 **그 크기가 미측정**이다([#272](https://github.com/Shadowfit/init/issues/272)).

즉 P5 가 그리는 곡선은 **그 자체로는 유효**하지만(레벨 간 비교는 같은 스키마 안에서 한다),
「4차보다 낮다/높다」는 말은 이 라운드로 못 한다.

---

## 5. 손대야 하는 곳

| 파일 | 무엇 |
|---|---|
| [`loadtest/ghz/gen_batch_multi.py`](../../loadtest/ghz/gen_batch_multi.py) | 템플릿 자리를 남기는 출력 모드. `json.dumps` 가 `{{ }}` 를 문자열로 감싸므로 **후처리 치환**이 필요하다 |
| [`sessions_sweep.sh`](../../loadtest/results/session-spread-2026-08-13/sessions_sweep.sh) | 레벨을 배열 크기가 아니라 **템플릿 인자**로 넘긴다 |
| [`conn_ridealong.sh`](../../loadtest/results/session-spread-2026-08-13/conn_ridealong.sh) | 같은 페이로드를 쓴다 — 같이 고친다 |
| [`gen_batch.py`](../../loadtest/ghz/gen_batch.py) | 단일 핫세션 판도 같은 성질이다(P2 가 쓴다) |

[#273](https://github.com/Shadowfit/init/issues/273) 잔결함 4건이 **같은 파일들**이라 한 번에 손대는 것이 싸다.

---

## 6. 결정 (2026-08-17 사용자 confirm)

- [x] **ㄴ 채택** — 메시지 1개 + sprig 라우팅. 구현 `d2d32fe`
- [x] **리허설 게이트를 건다** — 부하기 CPU · 달성 rate. 통과해야 [#271](https://github.com/Shadowfit/init/issues/271) 을 닫는다
- [x] **P2 생성기도 같이 고친다** — `gen_batch.py` 도 같은 결함이다. 라운드는 다르지만 같은 수법을 지금 적용한다
- [x] **[#273](https://github.com/Shadowfit/init/issues/273) 은 같이 손대되 커밋 분리** — `b0c4383`

---

## 7. 미검증

- **sprig `mod` 가 ghz 의 `RequestNumber`(int64)를 받는지** — 문서에 형이 안 적혀 있다.
  안 받으면 `printf`·`until` 같은 우회가 필요하다
- **템플릿 실행 비용의 실제 크기** — 35MB/s 는 «데이터 크기 × 목표 RPS» 산술이지 실측이 아니다.
  ghz 가 워커별로 병렬 실행하는지, 파싱 결과를 요청 간에 얼마나 재활용하는지는 안 봤다
- **EC2 배포분에 V6(멱등)이 적용됐는지** — 스윕은 로컬 빌드 jar 를 올린다. 안 올라가 있으면
  중복이 «안 삼켜지는» 상태로 돌 수도 있는데, 그건 그것대로 프로덕션과 다른 조건이다

---

## 결정 로그

- **2026-08-17: ㄴ 채택 후 리허설 박스를 띄우려다 하나 더 나왔다.** `bootstrap.sh` 는
  `REF=main` 으로 빌드하는데 **main 에 멱등이 없다** — 그대로 띄웠으면 «멱등 없는 코드» 에
  부하를 걸고 「고쳐졌다」는 틀린 확신을 얻었을 것이다. 그러다 **V5 번호가 두 갈래로 갈린 것**을
  봤다([#274](https://github.com/Shadowfit/init/issues/274)): main 은 `V5__feedback_log_rep_key`,
  이 브랜치는 `V5__add_pose_data_idempotency_key`. 각자는 멀쩡하고 **합치는 순간** Flyway 가
  기동을 거부한다. 로컬 `flyway_schema_history` 를 확인해 **V4 까지만 적용된 것**을 보고
  이 브랜치 쪽을 **V6 으로 올렸다**(적용 이력이 없어야 안전한 조작이다).

- **2026-08-17: 문서 신설.** P5 탑승 전 「설계에서 확정된 것이 rig 에 실제로 있는지」 대조에서
  나왔다. 08-13 라운드의 교훈은 «설계엔 있는데 rig 엔 없다» 였는데, 이번은 **정반대 방향**이다
  — rig 은 그대로인데 **대상이 움직였다**(같은 날 들어온 [#188](https://github.com/Shadowfit/init/issues/188) 멱등 키).
  ⚠️ 방어가 왜 안 걸렸는지도 같이 적어둔다: rig 의 사전 확인 다섯 개는 전부 **환경**을 본다
  (시드·내구성·풀·페이로드 전송·프로시저). **「보낸 요청이 실제로 행을 만드는가」를 보는 확인은
  하나도 없었다.**
