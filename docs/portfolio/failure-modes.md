# 실패 모드 카탈로그 (Failure Mode Catalog)

작성일: 2026-06-08
상태: 초안 — 코드 확인분 + 점검/보강 대상 구분
관련: [`portfolio-narrative.md`](./portfolio-narrative.md)(헤드라인=세션 정합성), [`reference-style-and-caching.md`](../decisions/reference-style-and-caching.md), [`realmysql-experiments.md`](./realmysql-experiments.md)

> 목적: "happy-path 동작"이 아니라 **"이게 어떻게 깨지나 + 얼마나 번지나(blast radius) + 어떻게 알아채나 + 지금 막고 있나 / 갭은 뭔가"** 를 컴포넌트별로 박제한다. 이 카탈로그가 보강 우선순위(outbox·관측성·회복탄력성)의 근거다.

## 표기

- **Blast radius**: 🟢 세션/사용자 1개 · 🟡 다수 세션 · 🔴 전역(인스턴스/서비스)
- **상태**: ✅ 완화됨 · 🔶 부분 · 🔴 갭(미완화)
- **확인**: 코드 확인분은 `file` 표기, 미확인은 *(점검 필요)*

---

## 1. 세션 시작 — `startAnalysis` (Spring DB read → gRPC StartAnalysis, @Async)

| ID | 실패 모드 | 트리거 | Blast | 감지 | 현재 완화 | 갭/보강 |
|---|---|---|---|---|---|---|
| S1 | StartAnalysis gRPC 실패 | FastAPI down/네트워크 | 🟢 | `onError` 로그만 (`ExerciseAnalysisService:158`) | 🔴 없음 (fire-and-forget) | 사용자는 운동 시작했는데 분석 안 됨 → **실패 통지/재시도/세션 무효화** 필요 |
| S2 | reference 비어있음/조회 실패 | 스타일 미추출·DB 오류 | 🟢 | — | 🔴 *(점검 필요)* | 빈 기준 전송 → DTW 불가. **사전 검증(기준 없으면 시작 거부)** |
| S3 | deadline 부재로 콜백 무응답 | FastAPI hang | 🟢 | 🔴 없음 | 🔴 deadline 미설정 | **gRPC deadline** → `DEADLINE_EXCEEDED` 처리 |

## 2. 라이브 적재 — `SavePoseDataBatch` (FastAPI → Spring → batch INSERT)

| ID | 실패 모드 | 트리거 | Blast | 감지 | 현재 완화 | 갭/보강 |
|---|---|---|---|---|---|---|
| I1 | 배치 부분 실패 | 제약 위반·커넥션 끊김 | 🟢 | gRPC 에러 | 🔶 트랜잭션 단위 *(원자성 점검 필요)* | 부분 커밋 시 데이터 구멍 → **배치 원자성·재시도 정책** 명시 |
| I2 | 중복 배치 적재 | FastAPI 재전송 | 🟢 | — | 🔴 멱등키 없음 — `idx_session_timestamp`가 **non-unique 인덱스**(`schema.sql:86`) | 재전송 시 **중복 행** → `(session_id, timestamp_sec)` **UNIQUE + UPSERT/INSERT IGNORE** |
| I3 | 적재 폭주 → 풀/버퍼풀 압박 | 고빈도 프레임·동시 세션↑ | 🟡 *(하향)* | 🟢 **`hikaricp_*` 시계열**(2026-08-08) | 🟢 배치 INSERT + **다운샘플 R=5** | **백프레셔·rate limit**. ⚠️ 아래 I3-note |

> **I3-note (2026-08-08 실측 반영)** — 이 행은 세 칸이 다 바뀌었고, 그 근거가 [풀 사이징 격자 실험](../../loadtest/results/pool-cliff-2026-08-08/)이다.
>
> - **감지 🔴→🟢**: `hikaricp_connections_pending`·`_active`·`_acquire_seconds_max`·`_timeout_total` 을 시계열로 본다([`monitoring/README.md`](../../monitoring/README.md)). 실험에서 실제로 이 네 개가 답을 줬다
> - **Blast 🔴→🟡**: c=100 부하에서 **풀은 포화됐지만(`pending` 96, 대기 7.28초) 아무도 죽지 않았다**(`timeout_total` 0). 커넥션당 작업이 짧아 30초 한도에 못 미치고 큐가 빠진다 — **포화 ≠ 붕괴**
> - **완화 🔶→🟢**: 다운샘플 R=5 가 배치당 25행 → 5행으로 줄이면서 **병목이 DB 쓰기에서 백엔드 CPU 로 옮겨갔다**(백엔드 p90 128% vs MySQL 53%)
>
> ⚠️ **그래서 이 행의 갭도 성격이 바뀐다.** "커넥션풀 한도"는 이제 레버가 아니다(pool 5~20 전 구간 동급). 폭주가 실제로 아플 자리는 **앱 CPU** 이고, 거기엔 아직 백프레셔도 rate limit 도 없다. 🔴 **버퍼풀 사이징(§④)은 이 경로에서 근거가 사라졌다** — MySQL 이 놀고 있었다.

## 3. 세션 종료 통보 — `stopAnalysis` (afterCommit gRPC) ⭐ 헤드라인

| ID | 실패 모드 | 트리거 | Blast | 감지 | 현재 완화 | 갭/보강 |
|---|---|---|---|---|---|---|
| **E1** | **통보 유실 → DB↔FastAPI 불일치** | afterCommit 후 gRPC 실패 | 🟢→🟡 | `onError` 로그만 (`:188`) | 🔴 없음 (at-most-once) | **DB는 COMPLETED인데 FastAPI는 orphan IN_PROGRESS.** → **outbox + 재발행(at-least-once)** |
| E2 | orphan 세션 상태 누적 | E1 반복 | 🟡 | 🔴 없음 | 🔴 없음 | FastAPI 측 **세션 상태 TTL/reconciliation 잡** |

## 4. 완료 콜백 — `completeSession` (FastAPI → Spring, @Version)

| ID | 실패 모드 | 트리거 | Blast | 감지 | 현재 완화 | 갭/보강 |
|---|---|---|---|---|---|---|
| C1 | 콜백 중복 | FastAPI 재시도 | 🟢 | — | ✅ 멱등 수신 `if COMPLETED return` (`:223`, first-write-wins) | — |
| C2 | 콜백↔스케줄러 동시 갱신 | 종료 시점 경합 | 🟢 | 낙관락 예외 | ✅ `@Version` + 재시도 3회·콜백 우선 (`:201-215`) | — |
| C3 | 낙관락 3회 모두 실패 | 극심한 경합 | 🟢 | 예외 throw | 🔴 그 후 처리 없음 | **DLQ/재큐 또는 reconciliation** |
| C4 | 콜백 영영 미수신 | 분석 결과·통보 유실 | 🟢 | — | 🔶 타임아웃 스케줄러가 FAILED 처리 | **분석은 됐는데 콜백만 유실 시 결과 손실** → E1과 동일(at-least-once 필요) |

## 5. 타임아웃 스케줄러 — `SessionTimeoutScheduler`

| ID | 실패 모드 | 트리거 | Blast | 감지 | 현재 완화 | 갭/보강 |
|---|---|---|---|---|---|---|
| T1 | 정상 세션 조기 FAILED | 콜백 직전 타임아웃 | 🟢 | — | ✅ 콜백이 낙관락으로 덮어씀(우선) | 경계 타이밍 *(정책 문서화 권장)* |
| T2 | 스케줄러 다운 | 인스턴스 장애·예외 | 🟡 | 🔴 없음 | 🔴 없음 | orphan IN_PROGRESS 영구 잔존 → **스케줄러 헬스·다중 인스턴스 시 중복 실행(락) 고려** |
| T3 | 다중 인스턴스 중복 실행 | 수평 확장 | 🟡 | — | 🔴 *(점검 필요)* | **분산 락/shedlock** 으로 단일 실행 보장 |

## 6. 피드백 배치 / 리포트 조회

| ID | 실패 모드 | 트리거 | Blast | 감지 | 현재 완화 | 갭/보강 |
|---|---|---|---|---|---|---|
| F1 | 피드백 배치 중복 | 재전송 | 🟢 | — | ✅ INSERT IGNORE 멱등 (`FeedbackLogService`) | — |
| R1 | 리포트 조회 중 적재 동시 진행 | 교차 사용자 동시성 | 🟢 | — | ✅ RR 스냅샷 일관 읽기 + 단일 fetch (§5.8) | 다중 읽기로 리팩터 시 `@Transactional` 유지 필수 |

## 7. 유튜브 추출 (계획) — 백그라운드 잡

| ID | 실패 모드 | 트리거 | Blast | 감지 | 현재 완화 | 갭/보강 |
|---|---|---|---|---|---|---|
| Y1 | 추출 실패 | yt-dlp·영상 삭제·네트워크 | 🟢 | 잡 상태 | 🔶 (설계 단계) | **잡 상태 FAILED + 재시도·사용자 통지** |
| Y2 | 잡 워커 다운 중 유실 | 워커 크래시 | 🟢 | — | 🔴 | **at-least-once 큐(재처리)**, 멱등 잡 키 |
| Y3 | 추출 타임아웃(긴 영상) | 대용량·hang | 🟢 | — | 🔴 | **잡 타임아웃 + 리샘플로 비용 상한**(§4) |

## 8. DB / 인프라 레벨

| ID | 실패 모드 | 트리거 | Blast | 감지 | 현재 완화 | 갭/보강 |
|---|---|---|---|---|---|---|
| D1 | 커넥션풀 고갈 | 외부 호출이 트랜잭션 내 점유 | 🔴 | 🔴 메트릭 없음 | 🔶 startAnalysis는 async라 우연히 회피(§gRPC) | **외부 호출은 트랜잭션 밖** 원칙 명시, 풀 메트릭 |
| D2 | 버퍼풀 < 작업셋 → 디스크 바운드 | 대용량 스캔 | 🟡 | 🔴 | ✅ 측정·이해(§④), read-ahead 함정 인지 | 작업셋 축소(파티션 만료)·풀 증설 |
| D3 | future 파티션 누락 → INSERT 실패 | 파티션 범위 초과 | 🔴 | — | 🔶 `pfuture` 존재 *(자동 추가 점검 필요)* | **파티션 사전 생성 잡(pt-osc/이벤트)** |
| D4 | 락 경합·데드락 | 핫로우 동시 갱신 | 🟢 | INNODB STATUS | ✅ 낙관락/원자 UPDATE 선택(§③) | 데드락 재시도 정책 |

## 9. 인증

| ID | 실패 모드 | Blast | 현재 완화 | 갭 |
|---|---|---|---|---|
| A1 | 토큰 만료/탈취 | 🟢 | ✅ JWT+blacklist+refresh | refresh 토큰 **회전(rotation)** *(점검 필요)* |

---

## 10. 보강 우선순위 (갭 → 액션)

이 카탈로그가 가리키는 보강은 [`portfolio-narrative §3`](./portfolio-narrative.md)와 일치한다:

1. **신뢰성(outbox) 🔴** — E1·E2·C4·Y2의 공통 뿌리 = **at-most-once 송신**. outbox + 멱등 수신(C1 이미 있음) = **exactly-once**. → 헤드라인 직접 강화.
2. **관측성 🔴** — I3·T2·D1 전부 "감지 없음". 구조화 로깅·correlation id·메트릭·헬스체크.
3. **회복탄력성 🔶** — S1·S3 = gRPC **deadline + 서킷브레이커**, 재시도 정책.
4. **운영 안전** — D3 파티션 사전 생성, T3 분산 락(다중 인스턴스), 무중단 마이그레이션(pt-osc).

## 11. 정직 캐비엇

- *(점검 필요)* 표기는 **아직 코드로 확인 못 한 항목** — 단정하지 않는다.
- 다중 인스턴스(T3·D1) 이슈는 **수평 확장 가정** 하에서만 — 단일 인스턴스면 일부 무관(과대 포장 금지).
- 이 카탈로그는 "전부 막았다"가 아니라 **"무엇이 깨지는지 알고, 무엇을 아직 안 막았는지 정직히 안다"** 가 가치 — senior 사고의 핵심.
