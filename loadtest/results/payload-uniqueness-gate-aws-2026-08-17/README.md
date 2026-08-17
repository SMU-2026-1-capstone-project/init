# 페이로드 유일성 게이트 — 템플릿이 도는가, 행이 실제로 느는가, 부하기가 얼마를 내나 (2026-08-17)

측정일: 2026-08-17 (UTC 13:39~14:0x)
박스: AWS **`c7i.xlarge`** (4 vCPU · 7GiB) · ap-northeast-2 · `i-0446bb89b9482934f`
스택: 한 박스에 **MySQL + Spring + AI 동거** (`ROLE=p6-target`) · 커밋 **`629e7f0`**
목적: [#271](https://github.com/Shadowfit/init/issues/271) 수정(ㄴ안)이 **실제로 서는지**. P5 본 라운드 전 게이트
설계: [`../../../docs/decisions/loadtest-payload-uniqueness.md`](../../../docs/decisions/loadtest-payload-uniqueness.md)

> 🔴 **이 라운드는 «용량» 을 재지 않았다.** 부하기와 대상이 같은 박스에서 CPU 를 공유한다.
> 절대 RPS 는 이 문서 밖으로 나가면 안 되고, 읽어야 할 것은 **행수**와 **요청당 부하기 CPU** 뿐이다.

---

## 0. 한 줄

**수정본은 요청 100%를 행으로 만든다(중복 0 · 실패 0).** 그리고 수정 전 페이로드는 예상대로
**세션당 첫 요청만** 저장했다 — 다만 **조용히**는 아니었다. 동시성이 있으면 데드락으로 시끄럽다.

---

## 1. 전제 확인 — 박스가 «멱등 있는 코드» 인가

이걸 안 보고 쟀으면 라운드 전체가 무의미했다. `bootstrap.sh` 는 기본 `REF=main` 인데
**main 에는 멱등이 없다**(그래서 커밋을 명시해 띄웠다).

```
flyway_schema_history: V1 V2 V3 V4 V6__add_pose_data_idempotency_key.sql
information_schema.statistics: uk_pose_event → 4열
```

---

## 2. 본 판정 — 행이 실제로 느는가

`ExerciseService.SavePoseDataBatch` · `--reps 25` → 저장 5행/요청 · 세션 901~1000.

| 페이로드 | 동시성 | 요청 | OK | 실패 | **저장된 행** | 기대 |
|---|---:|---:|---:|---:|---:|---:|
| **템플릿(수정본)** | c=10 | 500 | 500 | **0** | **2,500** | 2,500 ✅ |
| **템플릿(수정본)** | c=20 | 3,000 | 3,000 | **0** | **15,000** | 15,000 ✅ |
| 옛 배열(수정 전) | c=10 | 500 | 326 | **174** | **500** | 2,500 ❌ |
| 옛 배열(수정 전) | **c=1** | 300 | 300 | **0** | **500** | 1,500 ❌ |

수정본의 저장 결과를 열별로 뜯으면:

| 무엇 | 값 | 뜻 |
|---|---|---|
| `COUNT(DISTINCT session_id)` | **100** | `mod` 라우팅이 배열 순환과 같은 일을 한다 |
| `COUNT(DISTINCT rep_number)` | **500** (0~499) | `{{ .RequestNumber }}` 가 요청마다 정확히 증가 |
| `COUNT(DISTINCT timestamp_sec)` | **5** | 다운샘플 창 5가 그대로 |

→ **sprig `mod` 가 int64 `RequestNumber` 를 받는다**(설계 §7 의 미검증 항목 **해소**).

---

## 3. 🔴 예상과 달랐던 것 — 「조용하다」는 동시성에 달렸다

[#271](https://github.com/Shadowfit/init/issues/271) 은 *"`fail=0` 에 RPS 도 정상으로 찍혀 표를
봐서는 안 보인다"* 라고 적었다. **절반만 맞았다.**

- **c=1**: 300요청 전부 OK 인데 행은 500. **완전히 조용하다** — 서술대로다
- **c=10**: 34.8%가 `[Internal]`. 원인은 중복이 삼켜지는 것이 아니라 **데드락**이다

```
저장 실패: PreparedStatementCallback; SQL [INSERT INTO pose_data (...)
ON DUPLICATE KEY UPDATE session_id = session_id];
Deadlock found when trying to get lock; try restarting transaction
```

P5 rig 은 **c=100** 이다. 그대로 돌렸다면 «조용한 오측정» 이 아니라 **데드락 폭풍**을 봤을 것이고,
판이 못 쓰게 되는 결론은 같아도 **원인을 잠금 쪽으로 잘못 쫓았을** 것이다.

⚠️ 이 데드락은 rig 만의 문제가 아니다 — 재전송([#188](https://github.com/Shadowfit/init/issues/188))을
붙이는 순간 실사용에서 열린다. **[#276](https://github.com/Shadowfit/init/issues/276)** 으로 분리했다.

---

## 4. 게이트 ③ — 부하기가 얼마를 내나

템플릿이 있으면 ghz 는 **요청마다 데이터 전체를 다시 파싱**한다. 그 대가를 **같은 동시성**에서 대조했다.

| 페이로드 | RPS | ghz 자신의 CPU | **요청당 CPU** |
|---|---:|---:|---:|
| 템플릿 53.7KB | 125.6 | 33% | **2.63 ms** |
| 정적 배열 5.2MB | 154.0 | 17% | **1.20 ms** |

**요청당 2.2배.** 절대값 환산:

| 목표 처리량 | 부하기 CPU | 2 vCPU 박스 | 4 vCPU 박스 |
|---:|---:|---:|---:|
| 584 RPS (실측) | **1.35 vCPU** | 68% | 34% |
| 649 RPS (정본 baseline) | ~1.7 vCPU (환산) | **85%** 🔴 | 43% |

→ **부하기는 4 vCPU 여야 한다.** 2 vCPU(`c7i.large`·`t4g.small`)면 부하기가 먼저 포화한다.
P5 설계 §8 의 «인스턴스 타입» 미결정에 **처음으로 근거가 생겼다**.

---

## 5. 이 라운드가 잡은 rig 결함 넷

게이트를 재려고 박스를 세우는 동안 rig 이 **네 번** 막혔다. 전부 「진짜 원인과 다른 것을 가리키는」 부류다.

| # | 무엇 | 어떻게 보였나 |
|---|---|---|
| [#275](https://github.com/Shadowfit/init/issues/275) ① | rig 은 `sf-mysql`, 부트스트랩은 `shadowfit-mysql` | 「세션 시드가 부족하다 — `''`/100」 |
| [#275](https://github.com/Shadowfit/init/issues/275) ② | MySQL 헬스 대기가 초기화 창에서 통과 후 실패 | 「떴다」 바로 다음 줄에 「안 떴다」 |
| — | rig 자격증명 `-pshadowfit`, 박스는 `1234` | `Access denied` |
| — | `_rig.sh` 의 `GHZ` 경로가 옛 자리(`~/go/bin/ghz`) | 「워밍업 ghz 실패 — 백엔드 미기동?」 |

그리고 **내가 그 방어로 넣은 확인 자체가 1차 리허설에서 틀렸다** — `mysql` 클라이언트가
성공해도 stderr 에 password 경고를 내는데, 그걸 안 걷어내고 비교해 «정상인데 실패» 로 읽었다.
리허설이 아니었으면 본 라운드에서 첫 판에 멈췄을 것이다.

---

## 6. 정직하게 비어 있는 것

- **부하기 CPU 는 동거 조건에서 쟀다.** 전용 부하기에서는 다를 수 있다 — 방향(2배)은 믿고 절대값은 재확인
- **판 길이가 짧다**(최대 3,000요청). 템플릿 파싱이 오래 돌 때 GC·메모리로 어떻게 되는지는 안 봤다
- **데드락 비율 34.8%** 는 이 부하·이 박스의 값이다. 일반화 금지
- **`SHOW ENGINE INNODB STATUS` 를 안 봤다** — 데드락의 정확한 잠금 기제는 미확인
- **P5 본 라운드는 4대 구성**이다. 이 라운드는 그 토폴로지를 전혀 검증하지 않았다

---

## 7. 이 결과가 움직이는 것

- [#271](https://github.com/Shadowfit/init/issues/271) — 게이트 ①②③ 전부 통과. **닫을 수 있다**
- [#276](https://github.com/Shadowfit/init/issues/276) — **새로 열렸다.** 재전송 구현의 선행이다
- [`session-spread-sweep.md` §8](../../../docs/decisions/session-spread-sweep.md) — 부하기 **4 vCPU** 근거
- [`loadtest-payload-uniqueness.md` §7](../../../docs/decisions/loadtest-payload-uniqueness.md) — 미검증 2건 해소
