# 백엔드 포폴 서사 구조 (Spring)

작성일: 2026-06-08
상태: 서사 구조 확정(이번 세션) — 세부는 진행 중
관련: [`realmysql-experiments.md`](./realmysql-experiments.md), [`db-deep-dive.md`](./db-deep-dive.md), [`db-portfolio-roadmap.md`](../decisions/db-portfolio-roadmap.md), [`reference-style-and-caching.md`](../decisions/reference-style-and-caching.md), [`FINAL-REPORT.md`](../FINAL-REPORT.md)

> 포지셔닝: AI 아닌 **백엔드(Spring) 신입** 지원. MediaPipe·TTS·포즈는 **무대(substrate)** 일 뿐 셀링 포인트가 아니다.

## 0. 한 줄 포지셔닝

> **"시계열 쓰기-헤비 워크로드 위에서, 두 서비스에 걸친 운동 세션 상태를 동시성 정합성 있게 관리하고, 그 데이터 계층을 production 기준으로 깊게 엔지니어링한 백엔드."**

서사 우선순위: **① 세션 분산 정합성(핵심 기능) → ② DB 엔지니어링 깊이(실력 증명) → ③ 신뢰성·운영(보강)**.

---

## 1. 헤드라인 — 운동 세션 생명주기의 분산 정합성 ⭐

**왜 1번인가**: 앱의 도메인 심장(모든 게 "세션"을 중심으로 돎) + 이미 구현된 *푼 문제* + 두 서비스에 걸친 진짜 경쟁 상태. 면접에서 즉시 후속 깊이(격리·멱등·outbox)로 증명 가능.

**문제**: 운동 세션 상태가 Spring ↔ FastAPI 두 서비스에 걸쳐 있다. **종료 시점에 둘이 같은 세션을 동시에 건드린다.**
- 타임아웃 스케줄러: "너무 오래 안 끝남 → `FAILED`"
- FastAPI 콜백: "분석 끝남 → `COMPLETED`"

**해결**(실코드):
- **afterCommit 외부 호출**: DB 커밋 확정 후에만 AI에 gRPC 통보(`SessionService` endSession afterCommit → StopAnalysis).
- **`@Version` 낙관락**: 스케줄러↔콜백 충돌 감지(`Session.java`), 충돌 시 **재시도 3회**(`completeSession`), **콜백 결과 우선** 정책.
- **멱등 수신**: `applyCompleteFromApp`의 `if (status==COMPLETED) return` (first-write-wins) — 중복 콜백 안전.

**증명(실측)**: lost-update 재현·방지 카드(③), MVCC 격리수준 카드(④ — RR/RC/SERIALIZABLE + `data_locks` 관찰). → "동시성·정합성"을 말이 아니라 실험으로.

**보강 여지**(아래 §3): 송신 at-least-once(outbox) + 멱등 수신 = **exactly-once**, gRPC deadline, 관측성.

---

## 2. 실력 증명 — DB 엔지니어링 깊이 (RealMySQL) 🟢

**왜 2번인가**: "기능"이라기보다 *깊이 시연*. substrate(pose_data 시계열) 위에서 production 기준으로 파고든 결과. 헤드라인을 떠받치는 "이만큼 깊다".

**두 축** (DAU 1,000 가정, 1억 행 합성 시딩):
- **쓰기 축**: 배치 INSERT(처리량 +99%, p99 −37%), 파티션 **DROP PARTITION**(TTL, DELETE 대비 625x)
- **읽기 축**: 인덱스(부재 시 85초 대조), **keyset 페이지네이션**(offset 대비 최대 489,868x), JSON **projection**(payload −98.7%), **버퍼풀**(작업셋 vs 풀)
- **저장**: JSON **트림 33→13**(−60.9%)
- **동시성**: lost-update 방지(③), MVCC(④) — §1과 공유

**차별 포인트(정직한 방법론)**:
- "추가로 빨라짐"이 아니라 **"측정해서 이미 최적임을 발견"**(세션 리포트 인덱스).
- **read-ahead 함정**(표준 hit율 공식이 거짓 99%) 등 *함정을 잡아낸 디테일*.
- **합성 데이터 값 분포 한계**를 알고 분포 의존 실험(선택도·옵티마이저)은 **의도적으로 제외** — "아무거나 다 쟀다"보다 성숙.

---

## 3. 보강 기둥 — 신뢰성·운영·확장 🟠

헤드라인(정합성)을 production 폭으로 떠받치는 것들. "DB만 판 게 아니라 운영·신뢰성도 안다".

| 기둥 | 내용 | 상태 |
|---|---|---|
| **신뢰성(전달 의미론)** | 멱등 수신(있음) + **outbox로 at-least-once 송신** = exactly-once. 현재 gRPC 송신은 fire-and-forget(onError 로그만)이라 유실 가능 → 보강 | 🔶 보강 대상 |
| **회복탄력성** | gRPC **deadline** + Resilience4j **서킷브레이커** ("FastAPI 죽으면?") | 🔶 보강 대상 |
| **관측성** | **correlation id 전파**(@Async·gRPC 콜백·스케줄러·FastAPI 왕복) + Actuator + 커스텀 메트릭 3종 | 🟢 1차 완료(2026-07-28, [문서](../decisions/observability-correlation-id.md)) — JSON 구조화는 수집기 도입 시 |
| **캐싱** | 기준 좌표·TTS 템플릿 = 카탈로그 패턴(유한·불변·공유). 로컬 Caffeine → 다중 인스턴스 시 Redis | 🔶 설계됨 |
| **보안** | JWT + RefreshToken + blacklist + BCrypt + role | 🟢 있음 |

---

## 4. 의식적으로 안 하는 것 / 정직 포지셔닝

- **AI/포즈/TTS = substrate**, 셀링 포인트 아님. (백엔드 지원)
- **TTS는 단말 합성(MVP)** 이라 백엔드 깊이 얇음 — 메인 축 아님.
- **활동 피드 팬아웃**: 면접 친화 서사지만 **혼자 운동 도메인엔 억지** + 미착수 → 헤드라인에서 제외(이미 푼 세션 정합성이 더 정직·강함).
- **DAU 1,000 가정** 기준으로 설계·정당화. "작아서 드묾"으로 회피하지 않음. 단 **"N 동시부하 TPS 실측 자랑"과는 구분**(단일 클라 측정).
- **분포 의존 실험 미수행**(값 분포 균일 한계) 정직 명시.
- **"시계열 대용량 처리"로 오버셀 금지**: TSDB(TimescaleDB/InfluxDB)가 정당화되는 임계(수십억 행 / 디바이스 1,000개×10초=일 8,640만 행)에 비해 ~750행/세션은 한참 아래 — MySQL+JSON 진영과 별개 체급. 정직한 프레임은 **"TSDB가 자동화하는 패턴(다운샘플·retention·working-set/버퍼풀)을 임계 이하 규모에서 MySQL로 직접 구현·측정했다"**. 보너스: `daily_logs`(precompute)가 정확히 TSDB의 **continuous aggregate**에 대응 — "TimescaleDB의 continuous aggregate를 MySQL에서 손으로 한 셈."

---

## 5. 30초 엘리베이터 피치

> "운동 세션이 Spring과 FastAPI 두 서비스에 걸쳐 있어서, 세션 종료 시 **타임아웃 스케줄러와 AI 비동기 콜백이 같은 레코드를 두고 경쟁**합니다. 이걸 `@Version` 낙관락 + 멱등 수신(first-write-wins) + afterCommit 외부 호출로 정합성 있게 풀었고, lost-update·MVCC를 직접 재현·관찰해 근거를 만들었습니다. 그 아래 데이터 계층은 1억 행 시계열 substrate에서 **TSDB가 자동화하는 패턴(다운샘플·retention·working-set)을 MySQL로 직접 구현·측정**했습니다 — 배치 적재·파티션 TTL·keyset 페이지네이션을 실측으로 엔지니어링했고, read-ahead가 hit율 공식을 속이는 함정까지 잡았습니다."

---

## 6. 현재 상태 맵

| 구분 | 항목 |
|---|---|
| ✅ Built·측정 | 세션 정합성(낙관락·멱등·afterCommit), RealMySQL 카드(배치·인덱스·keyset·파티션·락·MVCC·JSON트림·버퍼풀), JWT/보안, **관측성(correlation id·커스텀 메트릭, 2026-07-28)** |
| 🔶 설계·보강 대상 | outbox(exactly-once), gRPC deadline·서킷브레이커, 캐싱(카탈로그), 선택형 스타일 기준 |
| 🔴 빈칸 | TestController 점검·N+1 점검, 구조화 로깅(JSON, 수집기 도입 시) |

## 7. 미결정 (사용자 confirm 필요)

- [ ] 보강 착수 순서: ~~관측성~~(2026-07-28 착수·완료) → 남은 건 **outbox(헤드라인 강화) vs 회복탄력성**
- [ ] 캐싱 백엔드: 로컬 Caffeine로 충분 vs Redis(시그널)
- [ ] §1 보강 실험화: "신뢰성 있는 비동기 메시징(outbox+멱등=exactly-once)" 카드를 만들지
