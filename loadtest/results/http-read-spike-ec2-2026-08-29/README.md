# HTTP 읽기 경로 스파이크 테스트 — 결과 (2026-08-29)

설계: [`../../../docs/decisions/read-path-spike-test.md`](../../../docs/decisions/read-path-spike-test.md) · [#587](https://github.com/Shadowfit/init/issues/587)
대상: EC2 **c7i.2xlarge**(8 vCPU) — MySQL·Spring·AI 동거, 커밋 [`a411d72`](https://github.com/Shadowfit/init/commit/a411d726cb03587e43fa442dc051f18e452ad593)
부하기: EC2 **c7i.xlarge**(4 vCPU) — 같은 VPC·서브넷, 대상과 분리, k6 v2.1.0
rig: [`read_p99_ec2.js`](../../k6/read_p99_ec2.js)(SPIKE_MULT 모드) · 러너 [`measure_http_read_spike.sh`](../../measure_http_read_spike.sh)

---

## 0. 한 줄 요약

**×3600(270req/s) 순간 스파이크에서도 p99가 두 자릿수 ms에 머문다 — 이 rig으로는 여전히 대상의 한계에 안 닿았다.** 從 R14(sustained, ×360까지)의 결론이 스파이크 조건에서도 그대로 유지된다. 오히려 스파이크 구간이 베이스라인보다 p99가 낮게 나오는 역설적 패턴이 4개 엔드포인트 전부에서 일관되게 나타났다.

---

## 1. 조건

| | |
|---|---|
| 단계 | 베이스라인×60(30s) → 급증(2s) → 스파이크×3600(20s) → 급락(2s) → 회복 관찰×60(90s) |
| 배수 근거 | 베이스라인=從 R14가 "안정" 확인한 수준. 스파이크=R14 상한(×360)의 10배 — "R14가 놔둔 미답 구간의 아래쪽 끝"을 처음 건드리는 시도(예측이 아니라 탐색) |
| 반복 | 4판(0 버림) — 판 0은 `dropped_iterations=193`으로 무효(콜드 스타트 아티팩트, 아래 §3) |
| 세션 | `k6read@shadowfit.local` 계정, 세션 50개(K6_SIDS) |

## 2. 결과 — 4개 엔드포인트 × 5단계, 유효 3판 중앙값

| 엔드포인트 | 단계 | p50 | p99 |
|---|---|--:|--:|
| session | baseline | 7.8ms | 9.9ms |
| session | spike(×3600) | 5.7ms | **7.6ms** |
| session | recovery | 7.9ms | 10.0ms |
| weekly | baseline | 4.2ms | 6.8ms |
| weekly | spike | 2.8ms | **3.7ms** |
| weekly | recovery | 4.2ms | 7.0ms |
| calendar | baseline | 3.1ms | 5.1ms |
| calendar | spike | 2.2ms | **2.8ms** |
| calendar | recovery | 3.0ms | 5.1ms |
| daily | baseline | 2.5ms | 4.4ms |
| daily | spike | 1.9ms | **2.4ms** |
| daily | recovery | 2.6ms | 4.6ms |

전문(rampup·rampdown 포함)은 [`table.md`](./table.md) · 원자료는 [`raw.tsv`](./raw.tsv).

**게이트**: 유효 3판 전부 `bad_status=0 · dropped_iterations=0`.

## 3. 판 0을 버린 이유 — `dropped_iterations=193`

첫 판만 스파이크 구간(×3600, 270req/s)에서 k6가 목표 도착률을 못 냈다(193회 미달성). 판 1~3은 전부 0 — 재현되지 않는다. 콜드 스타트(JVM JIT·커넥션 풀·k6 VU 초기화)가 첫 판에만 걸리는 전형적 워밍업 아티팩트로 판단해 버림판 설계(§3-3 관례) 그대로 처리했다. **이 판이 유효했다면 결론이 달라졌을 가능성은 없다** — 버려진 판도 `bad_status`는 0이었고(에러는 없었다), 단지 부하기가 목표 배수를 못 낸 것뿐이다.

## 4. 반증 조건 대면 — 설계 §3의 표

design 문서가 미리 적어둔 네 가지 중 정확히 하나가 나왔다:

> "스파이크 구간에서도 p99가 두 자릿수 ms에 머문다 → R14의 '이 rig으로는 대상의 한계에 안 닿았다'가 스파이크에서도 유지된다 — 상한을 더 올려야 함"

나머지 셋(느린 회복, 빠른 회복 후 큐잉 신호, dropped_iterations로 인한 rig 천장)은 안 나왔다 — 회복 구간 p99가 베이스라인과 같은 자릿수(오차 범위 안)로, 큐잉이 쌓였다는 신호가 없다.

## 5. 🔴 역설 — 스파이크가 베이스라인보다 빠르다

4개 엔드포인트 전부에서 스파이크 구간 p99가 베이스라인보다 **낮다**(session −23%, weekly −46%, calendar −45%, daily −45%). 우연이라기엔 방향이 너무 일관된다. 후보 설명(미검증):

- **JIT/캐시 워밍업**: 베이스라인 30초 동안 이미 웜업이 끝나 있어서 스파이크 자체의 효과라기보다, 베이스라인 구간이 "판이 막 시작된 직후"라 상대적으로 느릴 수 있다
- **커넥션 풀 재사용**: 낮은 도착률(4.5req/s)에서는 매 요청이 새 스레드/커넥션을 더 자주 왕복하고, 높은 도착률에서는 풀이 이미 데워져 있어 오히려 유리할 수 있다
- **k6 자체의 계측 오버헤드**: 낮은 rate에서 k6 VU가 유휴 상태로 있다가 깨어나는 비용이 매 요청에 실릴 수 있다(rig 쪽 아티팩트 가능성)

**어느 것도 확인하지 않았다** — 이 실험의 판정선(§3-4)이 요구하는 건 "스파이크를 버티는가"였지 "왜 이 방향인가"가 아니다. 후속 이슈로 남긴다.

## 6. 결론

- ✅ **×3600(27req/s의 10배, 270req/s) 순간 스파이크를 이 대상은 문제없이 흡수한다** — 에러 0, 지연 저하 0, 느린 회복 없음
- 🔴 **이 rig으로는 대상의 실제 한계를 여전히 못 찾았다** — 從 R14(sustained)에 이어 이 라운드도 "안 무너졌다"는 증거만 쌓았다. 다음 라운드가 있다면 배수를 더 올리거나(예: ×10000+), VU/커넥션 자체가 병목이 되는 지점을 찾아야 한다
- 🟡 **스파이크가 베이스라인보다 빠른 역설은 미해명** — §5, 후속 이슈 등록 후보

## 7. 인프라

`c7i.2xlarge`(대상) + `c7i.xlarge`(부하기), 무인 실행(부트스트랩 ROLE=p6-target/p6-loader → S3 핸드오프 → 자동 terminate). 소요: 런치~로더 완료 약 13분(17:22~17:35 UTC). 인스턴스 둘 다 종료·볼륨 삭제 확인.

## 8. 이번에 같이 고친 것 (부트스트랩 버그 2건)

從 R14 결과 문서가 남겨둔 미수정 버그 둘을 이번 커밋에 같이 고쳤다(`a411d72`):
- `seed_report_rig.sh`·`seed_k6_read_account.sh` git 실행권한 누락(100644) → 100755로 수정
- `bootstrap.sh`가 `python3→python` 심볼릭 링크를 p6-loader 역할에만 걸어서 대상(p6-target) 박스에서 시더가 "python: command not found"로 죽던 문제 → 역할 무관하게 패키지 설치 직후로 이동

덕분에 이번 라운드는 두 버그 다 안 밟았다 — 타겟 시딩까지 런치 후 **4분** 만에 끝남(R14는 같은 단계에서 3번 재시도했다).

## 9. 파일

| 파일 | 내용 |
|---|---|
| `table.md` | 단계별 p50/p99 전문(rampup·rampdown 포함) |
| `raw.tsv` | 판×엔드포인트×단계 원자료 |
| `logs/rep{0,1,2,3}.json` | k6 `--summary-export` 전체(모든 Trend 통계 포함) |
| `logs/rep{0,1,2,3}.log` | k6 stdout |
| `spike_run.log` | 오케스트레이터(`measure_http_read_spike.sh`) 전체 로그 |
| `user_data.log` | 부하기 EC2 부트스트랩·핸드오프 로그 |
