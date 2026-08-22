# #276 재현 — 생성 표 (판정은 [README.md](./README.md) 에)

격리 테이블 `pose_data_r276`(`CREATE TABLE ... LIKE pose_data` — 파티션·PK·`uk_pose_event` 복제).
워커 **8** · 워커당 INSERT 문 **40** · 문당 행 **25** · 팔당 **4판**(첫 판 버림).

| 팔 | 판 | 데드락 | 시도 | 비율 | 그 외 에러 | 최종 행수 |
|---|---|---|---|---|---|---|
| same_partition | 0 | 116 | 320 | 36.2% | 0 | 200 | ← 버림
| same_partition | 1 | 108 | 320 | 33.8% | 0 | 200 |
| same_partition | 2 | 140 | 320 | 43.8% | 0 | 200 |
| same_partition | 3 | 120 | 320 | 37.5% | 0 | 200 |
| diff_partition | 0 | 0 | 320 | 0.0% | 0 | 200 | ← 버림
| diff_partition | 1 | 0 | 320 | 0.0% | 0 | 200 |
| diff_partition | 2 | 0 | 320 | 0.0% | 0 | 200 |
| diff_partition | 3 | 0 | 320 | 0.0% | 0 | 200 |
| single_session | 0 | 0 | 320 | 0.0% | 0 | 25 | ← 버림
| single_session | 1 | 0 | 320 | 0.0% | 0 | 25 |
| single_session | 2 | 0 | 320 | 0.0% | 0 | 25 |
| single_session | 3 | 0 | 320 | 0.0% | 0 | 25 |
| no_uk | 0 | 0 | 320 | 0.0% | 0 | 8000 | ← 버림
| no_uk | 1 | 0 | 320 | 0.0% | 0 | 8000 |
| no_uk | 2 | 0 | 320 | 0.0% | 0 | 8000 |
| no_uk | 3 | 0 | 320 | 0.0% | 0 | 8000 |

**팔별 중앙값(첫 판 제외)**

| 팔 | 데드락 비율 중앙값 | 시도당 확률 p |
|---|---|---|
| same_partition | 37.5% | 0.3750 |
| diff_partition | 0.0% | 0.0000 |
| single_session | 0.0% | 0.0000 |
| no_uk | 0.0% | 0.0000 |
