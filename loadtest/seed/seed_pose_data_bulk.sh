#!/usr/bin/env bash
# #205 카드 A 선결 — 실 pose_data 를 버퍼풀보다 큰 규모로 채운다
#
# measure_205_card_a_write_cost.sh 는 실 pose_data 가 버퍼풀(기본 2,048MB)을 넘겨야
# 「커버링 인덱스의 쓰기 대가가 디스크 I/O 밑에서 드러난다」는 전제가 선다(2026-08-20
# 로컬 판은 dev DB 가 이미 4,995MB 로 쌓여 있어서 이 단계 없이 됐다 — 새 박스엔 그 무대가 없다).
#
# 세션 대역은 1~900000 만 쓴다 — 990000~999999 는 card_a rig 의 작업 대역이라 겹치면 안 된다.
# 날짜는 p2026_01~p2026_07(과거 달)에만 분산한다 — pfuture·최근 달을 이 합성 데이터로
# 채우면 다른 rig(파티션 관련) 가 「사용자 데이터」와 「이 시더의 더미」를 못 가른다.
#
# ⚠️ joint_coordinates 는 고정 리터럴(~2.3KB, docs/portfolio 가 인용하는 실제 평균과
#    같은 자릿수)이다 — 내용 다양성이 아니라 행 크기가 목적이라 다양화하지 않는다.
#    [[project_synthetic_data_distribution_limit]] 와 같은 한계: 분포 의존 실험엔 못 쓴다.
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a

TARGET_MB=${TARGET_MB:-5120}     # 목표 테이블 규모(data+index). 버퍼풀 2,048MB 의 2.5배
BATCH_ROWS=${BATCH_ROWS:-50000}
DB(){ docker exec -i shadowfit-mysql mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit -e "$1" 2>/dev/null; }

echo "=== pose_data 벌크 시딩 — 목표 ${TARGET_MB}MB ==="

existing_mb=$(DB "SELECT ROUND((SUM(data_length)+SUM(index_length))/1048576) FROM information_schema.PARTITIONS WHERE table_schema='shadowfit' AND table_name='pose_data';")
existing_mb=${existing_mb:-0}
echo "  현재 pose_data(data+index) ≈ ${existing_mb}MB"
if [ "$existing_mb" -ge "$TARGET_MB" ]; then
  echo "  이미 목표를 넘었다 — 시딩 생략"
  exit 0
fi

# 행당 예상 바이트(JSON ~2.3KB + 고정열 + idx_session_timestamp 오버헤드) — 필요 행수를
# 이걸로 «추정»만 하고, 최종 판정은 항상 information_schema 실측으로 한다(추정이 못 맞아도
# 안 멈추도록 아래 while 로 재확인 루프를 돈다).
BYTES_PER_ROW=2450
need_mb=$(( TARGET_MB - existing_mb ))
need_rows=$(( need_mb * 1048576 / BYTES_PER_ROW ))
echo "  부족분 ≈ ${need_mb}MB → 예상 필요 행수 ≈ ${need_rows} (실측으로 보정)"

seq_max=$(( need_rows + BATCH_ROWS ))
echo "[1/2] 번호 테이블 준비 (0~${seq_max})"
DB "SET SESSION cte_max_recursion_depth=$(( seq_max + 10 ));
    DROP TABLE IF EXISTS _pose_seed_seq;
    CREATE TABLE _pose_seed_seq (n INT PRIMARY KEY);
    INSERT INTO _pose_seed_seq
    WITH RECURSIVE s(n) AS (SELECT 0 UNION ALL SELECT n+1 FROM s WHERE n+1 < $seq_max)
    SELECT n FROM s;" >/dev/null

# 고정 JSON — MediaPipe 33 랜드마크 형태, 자릿수만 맞춘다(내용은 시더의 목적이 아니다).
# mysql 이미지엔 python3 가 없어 bash 로 만든다 — 검증: 1,000행 삽입 스모크(로컬)에서 통과.
pair='{"x":0.123456,"y":0.654321,"z":-0.045678,"visibility":0.987654},'
body=$(printf "$pair%.0s" $(seq 1 33)); body=${body%,}
JSON_LIT="{\"landmarks\":[$body],\"note\":\"synthetic-bulk-seed\"}"
json_len=${#JSON_LIT}
echo "  joint_coordinates 리터럴 길이 = ${json_len} bytes"

echo "[2/2] 배치 삽입 (세션 1~900000, 날짜 2026-01~07 분산)"
DB "SET GLOBAL innodb_flush_log_at_trx_commit=2;" >/dev/null   # 시딩 한정 완화 — 끝에 복구

offset=0
rounds=0
while :; do
  cur_mb=$(DB "SELECT ROUND((SUM(data_length)+SUM(index_length))/1048576) FROM information_schema.PARTITIONS WHERE table_schema='shadowfit' AND table_name='pose_data';")
  cur_mb=${cur_mb:-0}
  if [ "$cur_mb" -ge "$TARGET_MB" ]; then
    echo "  ${cur_mb}MB 도달 — 종료"
    break
  fi
  if [ "$offset" -ge "$seq_max" ]; then
    echo "🔴 번호 테이블을 다 썼는데(${seq_max}행) 아직 ${cur_mb}MB 다 — BYTES_PER_ROW 추정이 크게 틀렸다. TARGET_MB 를 낮추거나 seq_max 를 키워 재실행할 것" >&2
    exit 1
  fi
  DB "INSERT INTO pose_data (session_id, rep_number, timestamp_sec, joint_coordinates, sync_rate, smoothed_knee_angle, feedback_message, created_at)
      SELECT 1 + (n % 900000), n % 30, ROUND((n % 750)/10, 3),
             '$JSON_LIT', 75.00, 30.00, NULL,
             TIMESTAMP('2026-01-01 00:00:00') + INTERVAL (n % 212) DAY + INTERVAL (n % 86400) SECOND
        FROM _pose_seed_seq WHERE n >= $offset AND n < $(( offset + BATCH_ROWS ));" >/dev/null
  offset=$(( offset + BATCH_ROWS ))
  rounds=$(( rounds + 1 ))
  echo "  배치 ${rounds} 완료 (누적 오프셋 ${offset}) — 현재 ${cur_mb}MB"
done

DB "SET GLOBAL innodb_flush_log_at_trx_commit=1;" >/dev/null
DB "ANALYZE TABLE pose_data; DROP TABLE IF EXISTS _pose_seed_seq;" >/dev/null

final_mb=$(DB "SELECT ROUND((SUM(data_length)+SUM(index_length))/1048576) FROM information_schema.PARTITIONS WHERE table_schema='shadowfit' AND table_name='pose_data';")
total_rows=$(DB "SELECT COUNT(*) FROM pose_data WHERE session_id BETWEEN 1 AND 900000;")
echo "DONE — pose_data ≈ ${final_mb}MB (목표 ${TARGET_MB}MB) · 시딩 행수(1~900000 대역) ≈ ${total_rows}"
