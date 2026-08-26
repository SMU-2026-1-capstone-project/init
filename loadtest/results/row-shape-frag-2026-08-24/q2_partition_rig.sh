#!/bin/bash
# Q2 — 살아있는 파티션에 뚫린 구멍이 DROP PARTITION 소요를 바꾸는가
#   설계: docs/decisions/row-shape-partition-interaction.md §3 Q2 (설계가 「본체」라 부른 쪽)
#
# 무엇을 정하나: **보존정책(DROP PARTITION) 비용이 사용자 삭제량에 의존하는가.**
#   의존한다면 「DROP 은 1.8초 · DELETE 대비 625배」를 구멍 유무와 무관하게 인용할 수 없다.
#
# 🔴 실 테이블(pose_data)은 안 건드린다 — 자체 파티션 테이블 pq_t 를 쓴다.
#
# 팔:
#   clean  파티션에 N행을 넣고 그대로 둔다
#   holed  같은 파티션에 더 넣었다가 지워 **구멍을 만들고**, 최종 행 수는 clean 과 **같게** 맞춘다
#          → 논리 행 수는 같고 **점유 페이지만 다르다**. 그게 이 질문이 묻는 실제 상황이다
#
# ⚠️ 절대 시간은 이 박스에서 못 쓴다(2코어 동거). **팔 간 비** 만 읽는다.
set -uo pipefail
cd "$(dirname "$0")/../../.."
set -a; . ./.env; set +a

ROWS=${ROWS:-50000}      # 파티션당 최종 행 수 (≈157MB · DROP 약 2초 — 잡음에 안 묻힌다)
CHURN=${CHURN:-25000}    # holed 팔이 넣었다 지우는 양
KEEP_K=${KEEP_K:-8}      # 1/8 이 남아 구멍이 된다
TARGET=${TARGET:-pholed} # 이 판에서 DROP 할 파티션 하나 (순서 교락 제거)

# ── 잡음 줄이기 노브 (기본 끔 — 켜야만 동작이 바뀐다) ─────────────────────
#
# 🔴 2026-08-24 조용한 EC2 판(팔당 8)에서 **블록 안 «먼저 돈 판» 이 1.64배 느렸다.**
#    팔 효과(중앙값 비 1.43x)보다 큰 잡음이라, 라틴 방격으로 편향은 막아도 **분산이 남아**
#    n=8 에서 부호검정 p=0.29 로 못 갈랐다. 아래 둘이 그 잡음을 겨냥한다.
#
#   WARM_DROP=1  측정 전에 **빈 pmax 를 먼저 DROP** 한다. 그 판의 첫 DDL 이 무는 몫
#                (MDL 획득·딕셔너리·버퍼풀 정리)을 측정 대상에서 떼어낸다.
#   SETTLE_SEC=N 무대를 세운 뒤 N초 기다린다. 12.5만 행을 넣고 지운 직후라
#                퍼지·플러시가 아직 도는데, 그게 DROP 과 같은 디스크를 친다.
WARM_DROP=${WARM_DROP:-0}
SETTLE_SEC=${SETTLE_SEC:-0}

DB(){ docker exec -i shadowfit-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit -N -e "$1" 2>/dev/null; }
trap 'echo; echo "=== 정리 ==="; DB "DROP TABLE IF EXISTS pq_t; DROP TABLE IF EXISTS pq_nums;" >/dev/null; echo "  pq_t·pq_nums 제거"' EXIT

echo "=== Q2 — 구멍이 DROP PARTITION 을 바꾸는가 (파티션당 ${ROWS}행 · 대상 $TARGET) ==="

DB "DROP TABLE IF EXISTS pq_t;
CREATE TABLE pq_t (
  id BIGINT AUTO_INCREMENT, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  session_id BIGINT NOT NULL, rep_number INT NOT NULL DEFAULT 0, timestamp_sec DECIMAL(10,3) NOT NULL,
  joint_coordinates JSON NOT NULL, sync_rate DOUBLE NULL, keep_flag TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id, created_at), INDEX idx_session (session_id, timestamp_sec)
) ENGINE=InnoDB STATS_SAMPLE_PAGES=200
PARTITION BY RANGE (UNIX_TIMESTAMP(created_at)) (
  PARTITION pholed VALUES LESS THAN (UNIX_TIMESTAMP('2026-02-01')),
  PARTITION pclean VALUES LESS THAN (UNIX_TIMESTAMP('2026-03-01')),
  PARTITION pmax   VALUES LESS THAN MAXVALUE);"

DB "SET SESSION cte_max_recursion_depth=1000000;
    DROP TABLE IF EXISTS pq_nums; CREATE TABLE pq_nums (n INT PRIMARY KEY);
    INSERT INTO pq_nums WITH RECURSIVE s(n) AS (SELECT 0 UNION ALL SELECT n+1 FROM s WHERE n+1 < $(( ROWS > CHURN ? ROWS : CHURN ))) SELECT n FROM s;"

# 🔴 예전엔 (SELECT joint_coordinates FROM pose_data LIMIT 1) 로 실 pose_data 에서 JSON을
#   빌려 썼다 — pose_data 가 0행인 박스(로컬 dev DB 등)에서 그 서브쿼리가 NULL 을 반환해
#   NOT NULL 위반으로 INSERT 가 죽는데, DB() 가 stderr 를 삼켜 "무대가 안 섰다"는 엉뚱한
#   메시지만 남았다(#574). pq_t 는 실 pose_data 와 무관한 자체 테이블이라 빌릴 이유가
#   애초에 없었다 — 고정 리터럴로 바꾼다.
ins(){ # $1=날짜  $2=행수  $3=offset  $4=keep식
  DB "INSERT INTO pq_t (created_at, session_id, rep_number, timestamp_sec, joint_coordinates, sync_rate, keep_flag)
      SELECT TIMESTAMP('$1'), 800000 + (n+$3) DIV 750, (n+$3) % 30, ROUND(((n+$3) % 750)/10,3),
             JSON_OBJECT('note','q2-rig-fixed-payload'), 75.0, $4
        FROM pq_nums WHERE n < $2;"
}

echo "## [1] pclean — ${ROWS}행 그대로"
ins '2026-02-15 00:00:00' "$ROWS" 0 0

echo "## [2] pholed — 넣었다 지워 구멍을 만들고 최종 ${ROWS}행으로 맞춘다"
ins '2026-01-15 00:00:00' "$ROWS" 0 "IF(n % $KEEP_K = 0,1,0)"
for r in 1 2 3; do
  ins '2026-01-15 00:00:00' "$CHURN" $(( r * 100000 )) "IF(n % $KEEP_K = 0,1,0)"
  DB "DELETE FROM pq_t PARTITION (pholed) WHERE keep_flag=0 ORDER BY id LIMIT $CHURN;" >/dev/null
done
# 최종 행 수를 pclean 과 같게 맞춘다 (구멍은 남기고 개수만 맞춘다)
now=$(DB "SELECT COUNT(*) FROM pq_t PARTITION (pholed);")
[ "$now" -gt "$ROWS" ] && DB "DELETE FROM pq_t PARTITION (pholed) WHERE keep_flag=0 ORDER BY id DESC LIMIT $(( now - ROWS ));" >/dev/null
[ "$now" -lt "$ROWS" ] && ins '2026-01-15 00:00:00' $(( ROWS - now )) 900000 0

DB "ANALYZE TABLE pq_t;" >/dev/null
echo
printf "    %-9s %-9s %-10s %-10s\n" 파티션 행 페이지 MB
for p in pholed pclean; do
  read -r rc dl <<<"$(DB "SELECT CONCAT(table_rows,' ',data_length) FROM information_schema.PARTITIONS WHERE table_schema='shadowfit' AND table_name='pq_t' AND partition_name='$p';")"
  ac=$(DB "SELECT COUNT(*) FROM pq_t PARTITION ($p);")
  printf "    %-9s %-9s %-10s %-10s\n" "$p" "$ac" "$(( dl / 16384 ))" "$(( dl / 1048576 ))"
  #
  # 🔴 무대 게이트 — 2026-08-24 EC2 round1 이 **행 0 · 페이지 1** 인 파티션을 재고
  #    **17.8·19.4 ms** 를 냈다. 그 값이 진짜 판(17.7·21.1 ms)과 구별이 안 돼서,
  #    게이트가 없었으면 무효 판을 결과로 인용할 뻔했다.
  #    러너를 안 거치고 이 rig 을 직접 부르는 판을 위해 **여기서도** 막는다.
  if [ "$ac" != "$ROWS" ]; then
    echo "🔴 무대가 안 섰다 — $p 의 행이 $ac (기대 $ROWS). 측정하지 않고 멈춘다." >&2
    exit 2
  fi
done

echo
if [ "${SETTLE_SEC:-0}" != "0" ]; then
  echo "## [2-b] 안정화 ${SETTLE_SEC}초 — 방금 넣고 지운 것의 뒷정리가 DROP 과 같은 디스크를 친다"
  DB "SELECT SLEEP($SETTLE_SEC);" >/dev/null
fi
if [ "${WARM_DROP:-0}" != "0" ]; then
  wms=$(DB "SET @t0=NOW(6); ALTER TABLE pq_t DROP PARTITION pmax; SELECT ROUND(TIMESTAMPDIFF(MICROSECOND,@t0,NOW(6))/1000,1);")
  echo "## [2-c] 예열 DROP (빈 pmax) — ${wms} ms. 이 판의 «첫 DDL» 몫을 여기서 떼어낸다"
fi

echo "## [3] DROP PARTITION 소요 — **한 판에 하나만** 잰다"
#
# 🔴 초판은 한 판에서 둘을 연달아 DROP 했는데, 스모크에서 **순서가 부호를 뒤집었다**:
#      hc: pholed 1,888ms → pclean 1,023ms
#      ch: pclean   989ms → pholed   355ms
#    두 판 다 «먼저 한 쪽» 이 느리다 — 페이지 수(1,352 vs 867)와 무관하다. 첫 DROP 이
#    버퍼풀·메타데이터를 비우고 둘째가 그 덕을 보는 것으로 보인다(원인은 안 갈랐다).
#    그래서 **팔당 한 판, 판마다 첫 DROP** 으로 바꾼다 — 순서가 팔에 안 섞인다.
ms=$(DB "SET @t0=NOW(6); ALTER TABLE pq_t DROP PARTITION $TARGET; SELECT ROUND(TIMESTAMPDIFF(MICROSECOND,@t0,NOW(6))/1000,1);")
printf "    %-9s %10s ms   (이 판의 **첫** DROP)
" "$TARGET" "$ms"
