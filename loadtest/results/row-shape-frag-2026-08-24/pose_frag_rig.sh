#!/bin/bash
# Q1 — pose_data 행 모양에서도 파편화 계단이 같은가
#   설계: docs/decisions/row-shape-partition-interaction.md §3 Q1
#   대조: loadtest/results/delete-fragmentation-2026-08-09/ (outbox_events 행 모양)
#
# 🔴 실 테이블(pose_data)은 안 건드린다 — 자체 테이블 pose_frag 를 쓴다.
#
# 페이지 수를 08-09 판(1,348페이지)과 맞춘다. 행당 3,295 B(실측, #520)이므로
# 페이지당 4.97행 → 1,348페이지 ≈ 6,700행. 08-09 는 200,000행이었다.
# 🔑 **행 수가 아니라 페이지 수를 맞추는 것이 핵심이다** — 질문이 「페이지당 행 수가
#    다르면 계단이 다른가」라서, 무대 크기(페이지)를 같게 두고 행 모양만 바꾼다.
set -uo pipefail
cd "$(dirname "$0")/../../.."
set -a; . ./.env; set +a

STEADY=${STEADY:-6700}; CHURN=${CHURN:-840}; CYCLES=${CYCLES:-24}   # 840/6700 ≈ 12.5% (08-09 와 같은 비율)
ARM=${ARM:-fifo}                                                     # fifo | hole
FAILED_K=${FAILED_K:-8}                                              # hole 팔: 1/8 이 남아 구멍
DB(){ docker exec -i shadowfit-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit -N -e "$1" 2>/dev/null; }

trap 'echo; echo "=== 정리 ==="; DB "DROP TABLE IF EXISTS pose_frag; DROP TABLE IF EXISTS pf_nums;" >/dev/null; echo "  pose_frag·pf_nums 제거"' EXIT

echo "=== Q1 파편화 — 팔 $ARM (정상상태 ${STEADY}행 / 사이클당 ${CHURN}행 / ${CYCLES}사이클) ==="
echo "    전체 회전수: $(awk -v c=$CHURN -v y=$CYCLES -v s=$STEADY 'BEGIN{printf "%.1f", c*y/s}')회"

# pose_data 의 행 모양을 그대로 쓴다 (JSON 약 2,076 B 가 본체)
DB "DROP TABLE IF EXISTS pose_frag;
CREATE TABLE pose_frag (
    id                  BIGINT AUTO_INCREMENT,
    session_id          BIGINT        NOT NULL,
    rep_number          INT           NOT NULL DEFAULT 0,
    timestamp_sec       DECIMAL(10,3) NOT NULL,
    joint_coordinates   JSON          NOT NULL,
    sync_rate           DOUBLE        NULL,
    smoothed_knee_angle DOUBLE        NULL,
    feedback_message    TEXT          NULL,
    created_at          TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    keep_flag           TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    INDEX idx_session_timestamp (session_id, timestamp_sec)
) ENGINE=InnoDB STATS_SAMPLE_PAGES=200;"

DB "SET SESSION cte_max_recursion_depth=1000000;
    DROP TABLE IF EXISTS pf_nums; CREATE TABLE pf_nums (n INT PRIMARY KEY);
    INSERT INTO pf_nums WITH RECURSIVE s(n) AS (SELECT 0 UNION ALL SELECT n+1 FROM s WHERE n+1 < $CHURN) SELECT n FROM s;"

# pose_data 의 실제 JSON 한 건을 원본으로 쓴다 (행 모양을 흉내내지 않고 그대로 가져온다)
DB "SET @j := (SELECT joint_coordinates FROM pose_data LIMIT 1);"
JLEN=$(DB "SELECT LENGTH((SELECT joint_coordinates FROM pose_data LIMIT 1));")
echo "    원본 JSON 길이: ${JLEN} B"

ins(){ # $1 = offset,  $2 = keep 규칙(0 이면 전부 지움 대상)
  local off=$1 keep=$2
  DB "INSERT INTO pose_frag (session_id, rep_number, timestamp_sec, joint_coordinates, sync_rate, smoothed_knee_angle, feedback_message, keep_flag)
      SELECT 900000 + ((n+$off) DIV 750), (n+$off) % 30, ROUND(((n+$off) % 750)/10,3),
             (SELECT joint_coordinates FROM pose_data LIMIT 1), 75.0, 95.0, 'frag', $keep
        FROM pf_nums;"
}
# 🔴 ANALYZE 를 먼저 한다. 08-09 rig 의 snapshot() 이 그렇게 돼 있고, 안 하면
#    data_length 가 갱신되지 않아 **행당 16 B 같은 말이 안 되는 값**이 나온다(스모크에서 잡았다).
# 🔴 purge 도 기다린다. 지운 행이 아직 안 치워졌으면 «점유 페이지» 가 과대로 잡힌다.
wait_purge(){
  local i=0 h
  while [ $i -lt 20 ]; do
    h=$(DB "SELECT COUNT(*) FROM information_schema.innodb_metrics WHERE name='trx_rseg_history_len' AND status='enabled';")
    h=$(DB "SELECT variable_value FROM performance_schema.global_status WHERE variable_name='Innodb_history_list_length';")
    [ "${h:-0}" -le 20 ] && return 0
    sleep 1; i=$((i+1))
  done
  echo "   ⚠️ purge 가 20초 안에 안 내려왔다 (현재 $h) — 이 사이클 수치는 과대일 수 있다" >&2
}
snap(){
  DB "ANALYZE TABLE pose_frag;" >/dev/null
  DB "SELECT CONCAT((SELECT COUNT(*) FROM pose_frag),' ',data_length,' ',data_free) FROM information_schema.tables WHERE table_schema='shadowfit' AND table_name='pose_frag';"
}

echo "## [1] 초기 적재 (${STEADY}행)"
off=0
while [ "$off" -lt "$STEADY" ]; do ins "$off" "$([ "$ARM" = hole ] && echo "IF(n % $FAILED_K = 0,1,0)" || echo 0)"; off=$((off+CHURN)); done
DB "DELETE FROM pose_frag ORDER BY id DESC LIMIT $(( off - STEADY ));" >/dev/null
wait_purge; read -r r d fr <<<"$(snap)"
BASE=$(( d / 16384 ))
printf "    행 %s · 페이지 %s · 행당 %.0f B\n" "$r" "$BASE" "$(awk -v d=$d -v r=$r 'BEGIN{print d/r}')"

echo "## [2] steady-state — 행 수는 일정, 점유 페이지가 자라는가"
printf "    %-8s %-9s %-9s %-9s\n" 사이클 행 페이지 행당B
for c in $(seq 1 $CYCLES); do
  if [ "$ARM" = hole ]; then
    ins "$off" "IF(n % $FAILED_K = 0,1,0)"
    DB "DELETE FROM pose_frag WHERE keep_flag=0 ORDER BY id LIMIT $CHURN;" >/dev/null
  else
    ins "$off" 0
    DB "DELETE FROM pose_frag ORDER BY id LIMIT $CHURN;" >/dev/null
  fi
  off=$((off+CHURN))
  if [ "$c" -le 3 ] || [ $(( c % 4 )) -eq 0 ]; then
    wait_purge; read -r r d fr <<<"$(snap)"
    printf "    %-8s %-9s %-9s %-9.0f\n" "$c" "$r" "$(( d / 16384 ))" "$(awk -v d=$d -v r=$r 'BEGIN{print d/r}')"
  fi
done

read -r r d fr <<<"$(snap)"; END=$(( d / 16384 ))
echo "## [3] 참고 — OPTIMIZE (같은 행을 새로 담으면)"
DB "OPTIMIZE TABLE pose_frag;" >/dev/null
read -r r2 d2 fr2 <<<"$(snap)"; OPT=$(( d2 / 16384 ))
echo
echo "=== 결과 (팔 $ARM) ==="
printf "  초기 %s페이지 → 종료 %s페이지 (%+.1f%%) · OPTIMIZE %s페이지 (종료 대비 %+.1f%%)\n" \
  "$BASE" "$END" "$(awk -v a=$BASE -v b=$END 'BEGIN{print (b-a)*100.0/a}')" "$OPT" \
  "$(awk -v a=$END -v b=$OPT 'BEGIN{print (b-a)*100.0/a}')"
