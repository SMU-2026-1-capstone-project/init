# 대용량 실험용 합성 시딩 절차

RealMySQL 실험([`../../docs/portfolio/realmysql-experiments.md`](../../docs/portfolio/realmysql-experiments.md))용 시딩. **목적이 다른 두 rig**를 분리한다 — 행수와 payload는 독립 변수이므로(§0.3).

| rig | 테이블 | payload | 규모 | 용도 |
|---|---|---|---|---|
| **A. 실데이터** | `pose_data` | 실제 33-랜드마크 JSON(~2.3KB) | ~375만(디스크 제약) | ②b projection 등 **payload 실험** |
| **B. 행수 스케일** | `pose_data_scale` | 더미 `{}` | **정확히 1억** | ②c 페이지네이션·②d 파티션 등 **행수 실험** |

> 핵심: 1억 행을 실제 JSON으로 채우면 255GB라 로컬 불가. 행수 실험은 payload가 무관하므로 더미로 디커플링(rig B). payload 실험은 행수가 작아도 되므로 실데이터 일부(rig A).

---

## rig A — 실데이터 (pose_data, 실제 JSON)

②b projection처럼 **JSON off-page 페치 비용**이 변수인 실험용. 템플릿 세션(601, ~750행)을 세션·날짜 분산해 복제.

> ⚠️ **리포트 경로 실험에는 그대로 못 쓴다.** 템플릿 601은 `rep_number`·`smoothed_knee_angle`이 생기기 전에 만들어져 두 컬럼이 전부 **0(미상)**이다. 계산기는 `rep_number <= 0`을 제외하므로 이 데이터로는 worst 구간·회차별 추이가 `null`로 나오고, 대표 프레임 선택도 "고를 근거 없음" 경로로 떨어진다. 조회 비용(JSON 페치·스캔) 실험에는 영향이 없다 — 그게 이 rig의 목적이다.
> 리포트 경로까지 부하로 돌리려면 템플릿에 rep 경계와 무릎각을 실제 값으로 채워야 하는데, 아직 하지 않았다.

### 1. 세션 시딩 (5,000개, 2026년 12개월 분산)
```bash
docker exec -i shadowfit-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit' < seed_sessions.sql
```

### 2. pose_data 시딩 (601 템플릿 × 세션 cross join, 청크)
```bash
docker exec shadowfit-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit -e "
DROP TABLE IF EXISTS _pose_template;
CREATE TABLE _pose_template AS
  SELECT timestamp_sec, joint_coordinates, sync_rate, rep_number, smoothed_knee_angle, feedback_message
  FROM pose_data WHERE session_id=601;"'

for i in $(seq 0 9); do
  docker exec shadowfit-mysql sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" shadowfit -e \"
    INSERT INTO pose_data (session_id, timestamp_sec, joint_coordinates, sync_rate, rep_number, smoothed_knee_angle, feedback_message, created_at)
    SELECT s.id, t.timestamp_sec, t.joint_coordinates, t.sync_rate, t.rep_number, t.smoothed_knee_angle, t.feedback_message, s.start_time
    FROM _pose_template t
    CROSS JOIN (SELECT id, start_time FROM exercise_sessions WHERE reference_source='seed' AND id % 10 = $i) s;\""
  echo "청크 $i/9"
done
docker exec shadowfit-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit -e "ANALYZE TABLE pose_data;"'
```

### 정리
```sql
DELETE FROM pose_data WHERE session_id IN (SELECT id FROM exercise_sessions WHERE reference_source='seed');
DELETE FROM exercise_sessions WHERE reference_source='seed';
DROP TABLE IF EXISTS _pose_template;
```

---

## rig B — 행수 스케일 (pose_data_scale, 더미 1억)

②c/②d처럼 **행수가 변수**인 실험용. 한 방에 재현:
```bash
bash seed_pose_scale.sh    # 133,334세션×750행=정확히 1억, ~11GB, ~50분(병렬시 ~16분)
```

**전제**: 버퍼풀 2GB·sort_buffer 64M (`docker-compose.yml` command). 기본 128MB로는 롤백/풀스캔이 디스크 random I/O로 붕괴.

**가속 교훈**(스크립트에 반영):
1. **버퍼풀 128MB→2GB** — 롤백 64행/s→1만행/s (150배)
2. **인덱스는 시딩 후 일괄 빌드** — random insert 페이지 분할 회피 (청크당 3.4분→2분)
3. **청크 병렬** — 세션범위 3분할 동시 INSERT (48분→16분)
4. **sort_buffer 1M→64M** — 인덱스 merge sort 디스크 thrashing 회피

### 정리
```sql
DROP TABLE IF EXISTS pose_data_scale;
```
