# #276 재현 — 생성 표 (판정은 [README.md](./README.md) 에)

격리 테이블 `pose_data_r276`(`CREATE TABLE ... LIKE pose_data` — 파티션·PK·`uk_pose_event` 복제).
워커 **8** · 워커당 INSERT 문 **40** · 문당 행 **25** · 팔당 **4판**(첫 판 버림).

팔: `same_partition new_keys_only` · 판 순서: **latin**

| 팔 | 판 | 데드락 | 시도 | 비율 | 그 외 에러 | 최종 행수 |
|---|---|---|---|---|---|---|
| same_partition | 0 | 135 | 320 | 42.2% | 0 | 200 | ← 버림
| new_keys_only | 0 | 0 | 320 | 0.0% | 0 | 8000 | ← 버림
| new_keys_only | 1 | 0 | 320 | 0.0% | 0 | 8000 |
| same_partition | 1 | 137 | 320 | 42.8% | 0 | 200 |
| same_partition | 2 | 135 | 320 | 42.2% | 0 | 200 |
| new_keys_only | 2 | 0 | 320 | 0.0% | 0 | 8000 |
| new_keys_only | 3 | 0 | 320 | 0.0% | 0 | 8000 |
| same_partition | 3 | 140 | 320 | 43.8% | 0 | 200 |

**팔별 중앙값(첫 판 제외)**

| 팔 | 데드락 비율 중앙값 | 시도당 확률 p |
|---|---|---|
| same_partition | 42.8% | 0.4281 |
| new_keys_only | 0.0% | 0.0000 |
