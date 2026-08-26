# #276 ② 앱 경로 재시도 — 생성 표 (판정은 [README.md](./README.md) 에)

ghz → Spring gRPC `SavePoseDataBatch` → `savePoseDataBatchWithDeadlockRetry`(간격 0).
페이로드는 **중복 조건**(`--duplicate-keys`, rep_number 고정) · 세션 901-1000 · 배치당 25 프레임.
판당 **300 요청** · 레벨 `16` · 2블록(첫 블록 버림) · 판마다 대상 세션 행 삭제.

| 팔 | 동시성 | 블록 | OK | 실패(Internal+Aborted) | 그 외 | retried | recovered | **exhausted** | 저장된 행 |
|---|---|---|---|---|---|---|---|---|---|
| - | 16 | 0 | 290 | 10 | 0 | 258 | 94 | **10** | 500 | ← 버림
| - | 16 | 1 | 270 | 30 | 0 | 435 | 120 | **30** | 500 |

**레벨별 중앙값(첫 블록 제외)**

| 동시성 | Internal 중앙값 | 잔여 실패율 | exhausted 중앙값 | retried 중앙값 |
|---|---|---|---|---|
| 16 | 30.0 | 10.00% | 30.0 | 435.0 |
