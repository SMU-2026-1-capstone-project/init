#!/usr/bin/env bash
# #276 후속 — 재시도를 «유도» 에서 «실측» 으로
#
# 이슈: https://github.com/Shadowfit/init/issues/276
# 지금까지 이슈에 올라간 재시도 표는 전부 계산이다 — p 하나를 상수로 놓고 p^(n+1).
#   그 유도가 낙관 쪽으로 틀릴 이유를 이미 하나 찾았다(재시도가 동시성을 올린다).
#   여기서 실제로 재시도를 붙여 돌리고, 유도와 대조한다.
#
# 팔 = 최대 재시도 횟수 0 · 1 · 2 · 3. 그 외는 워커 스윕의 w=8 조건과 동일.
#   판정선 (미리 박는다):
#     · 실측 잔여 실패율이 유도값과 «비슷» 하면 → 되먹임이 작다. 유도를 써도 된다
#     · 실측이 유도보다 «높으면» → 되먹임이 이긴다. p^(n+1) 은 낙관이고 쓰면 안 된다
#     · 실측이 «낮으면» → 중복으로 접히는 경로가 이긴다(재시도 2회차는 대개 중복이 된다)
#
# 🔴 mysql CLI 는 문당 재시도를 못 한다(--force 는 그냥 다음 문으로 넘어간다).
#   그래서 SQLSTATE '40001'(데드락) 핸들러를 가진 저장 프로시저를 쓴다.
#   각 INSERT 는 autocommit 이라 데드락이 나면 그 문만 롤백된다 — 재시도가 안전한 단위다.
#
# ⚠️ 한계:
#   · 재시도 간격 0(즉시 재시도). 백오프를 넣으면 결과가 달라진다 — 그건 별도 팔이다
#   · 프로시저 호출 오버헤드가 붙는다. 팔 «간» 에는 공통이라 상쇄되지만,
#     앞 라운드의 생 INSERT 값(p=0.4219)과 직접 비교하면 안 된다
#   · 로컬 2물리코어 동거. 절대 비율은 이 박스 값이다
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a

ARMS=(${ARMS:-0 1 2 3})    # 최대 재시도 횟수
WORKERS=${WORKERS:-8}
ITER=${ITER:-40}           # 워커당 «논리 요청» 수 (재시도는 이 안에서 일어난다)
ROWS=${ROWS:-25}
BLOCKS=${BLOCKS:-4}        # 첫 블록 버림 → 팔당 유효 3판
OUT=${OUT:-loadtest/results/r276-retry-2026-08-20}
SC=$(mktemp -d)
mkdir -p "$OUT"

DB(){ docker exec -i shadowfit-mysql mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit "$@" 2>/dev/null; }

echo "## [0] 상태 단언"
have=$(DB -e "SELECT COUNT(*) FROM information_schema.statistics
        WHERE table_schema='shadowfit' AND table_name='pose_data_r276' AND index_name='uk_pose_event';" | tr -d '[:space:]')
echo "  uk_pose_event 컬럼 수 = ${have:-없음} (4 여야 정상)"
[ "$have" = "4" ] || { echo "🔴 4 가 아니다 — measure_r276_deadlock.sh 를 먼저 돌릴 것"; exit 1; }

echo
echo "## [0-b] 재시도 프로시저와 기록 테이블"
DB -e "
DROP TABLE IF EXISTS r276_retry_log;
CREATE TABLE r276_retry_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  worker INT NOT NULL,
  tries  INT NOT NULL,   -- 실제 시도 횟수 (1 = 첫 판에 성공)
  failed TINYINT NOT NULL -- 재시도를 다 쓰고도 실패했나
) ENGINE=InnoDB;"
docker exec -i shadowfit-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit 2>/dev/null <<'SQL'
DROP PROCEDURE IF EXISTS r276_ins;
DELIMITER $$
CREATE PROCEDURE r276_ins(IN p_sid INT, IN p_maxr INT, IN p_worker INT, IN p_vals TEXT)
BEGIN
  DECLARE v_try INT DEFAULT 0;
  DECLARE v_dead INT DEFAULT 0;
  DECLARE v_done INT DEFAULT 0;
  -- 40001 = serialization failure. 데드락이 여기로 온다.
  DECLARE CONTINUE HANDLER FOR SQLSTATE '40001' SET v_dead = 1;

  retry_loop: LOOP
    SET v_dead = 0;
    SET v_try = v_try + 1;
    SET @s = CONCAT('INSERT INTO pose_data_r276 (session_id,rep_number,timestamp_sec,',
                    'joint_coordinates,sync_rate,smoothed_knee_angle,feedback_message,created_at) VALUES ',
                    p_vals, ' ON DUPLICATE KEY UPDATE session_id = session_id');
    PREPARE st FROM @s;
    EXECUTE st;
    DEALLOCATE PREPARE st;
    IF v_dead = 0 THEN
      SET v_done = 1;
      LEAVE retry_loop;
    END IF;
    -- 재시도를 다 썼으면 실패로 끝낸다 (간격 0 — 즉시 재시도)
    IF v_try > p_maxr THEN
      LEAVE retry_loop;
    END IF;
  END LOOP;

  INSERT INTO r276_retry_log (worker, tries, failed) VALUES (p_worker, v_try, 1 - v_done);
END$$
DELIMITER ;
SQL
DB -e "SELECT COUNT(*) FROM information_schema.routines
        WHERE routine_schema='shadowfit' AND routine_name='r276_ins';" \
  | xargs -I{} echo "  프로시저 r276_ins 생성 = {} (1 이어야 정상)"

# VALUES 목록 (모든 워커·팔 공통 모양, session_id 만 다르다)
mk_vals(){ # $1=sid → 따옴표 이스케이프된 VALUES 문자열
  local sid="$1" r out=""
  for ((r=0;r<ROWS;r++)); do
    [ -n "$out" ] && out+=","
    out+="($sid,0,$((r/2)).$(((r%2)*5))00,''{\"k\":$r}'',45.0,0.0,'''',''2026-05-28 10:00:00'')"
  done
  echo "$out"
}

run_arm(){ # $1=maxr  $2=block → "maxr block 논리요청 최종실패 총시도 그외에러 행수"
  local maxr="$1" blk="$2" w pids=()
  DB -e "TRUNCATE TABLE pose_data_r276; TRUNCATE TABLE r276_retry_log;"
  rm -f "$SC"/err.* "$SC"/w.*
  for ((w=0;w<WORKERS;w++)); do
    local vals; vals=$(mk_vals $((900+w)))
    : > "$SC/w.$w.sql"
    for ((i=0;i<ITER;i++)); do
      echo "CALL r276_ins($((900+w)), $maxr, $w, '$vals');" >> "$SC/w.$w.sql"
    done
  done
  for ((w=0;w<WORKERS;w++)); do
    ( docker exec -i shadowfit-mysql mysql --force -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit \
        < "$SC/w.$w.sql" > /dev/null 2> "$SC/err.$w" ) &
    pids+=($!)
  done
  for p in "${pids[@]}"; do wait "$p"; done
  local other=0 c
  for ((w=0;w<WORKERS;w++)); do c=$(grep -c 'ERROR' "$SC/err.$w" 2>/dev/null); other=$((other + ${c:-0})); done
  local agg; agg=$(DB -e "SELECT COUNT(*), SUM(failed), SUM(tries) FROM r276_retry_log;")
  local rows; rows=$(DB -e "SELECT COUNT(*) FROM pose_data_r276;")
  echo "$maxr $blk $(echo "$agg" | tr '\t' ' ') $other $rows"
}

echo
echo "## [1] 팔 ${ARMS[*]} × ${BLOCKS}블록 (첫 블록 버림) — 라틴 방격 · 워커 $WORKERS · 논리요청/워커 $ITER"
echo "maxr block requests failed tries other_err rows" > "$SC/raw.txt"
n=${#ARMS[@]}
for ((b=0;b<BLOCKS;b++)); do
  order=""
  for ((k=0;k<n;k++)); do order+="${ARMS[$(( (b+k) % n ))]} "; done
  echo "  — 블록 $b 순서: $order$([ "$b" = 0 ] && echo '  ← 버림')"
  for a in $order; do
    line=$(run_arm "$a" "$b")
    echo "$line" >> "$SC/raw.txt"
    echo "    maxr=$line"
  done
done

echo
echo "## [2] 집계"
{
echo "# #276 후속 — 재시도 실측 (로컬, 2026-08-20) · 생성 표"
echo
echo "워커 **$WORKERS** · 워커당 논리요청 **$ITER** · 문당 행 **$ROWS** · **${BLOCKS}블록**(첫 블록 버림) · 라틴 방격."
echo "\`requests\` = 논리요청 수 · \`failed\` = 재시도를 다 쓰고도 실패 · \`tries\` = 실제 INSERT 시도 총합."
echo
echo "| 최대 재시도 | 블록 | 논리요청 | 최종실패 | 잔여 실패율 | 총 시도 | 요청당 시도 | 행수 |"
echo "|---|---|---|---|---|---|---|---|"
awk 'NR>1 {printf "| %s | %s | %s | %s | %.1f%% | %s | %.2f | %s |%s\n", $1,$2,$3,$4,($4/$3)*100,$5,$5/$3,$6==""?"-":$7, ($2==0?" ← 버림":"")}' "$SC/raw.txt"
echo
echo "**팔별 중앙값(첫 블록 제외)**"
echo
echo "| 최대 재시도 | 잔여 실패율 중앙값 | 요청당 시도 중앙값 |"
echo "|---|---|---|"
for a in "${ARMS[@]}"; do
  awk -v x="$a" 'NR>1 && $1==x && $2>0 {printf "%.6f %.4f\n", $4/$3, $5/$3}' "$SC/raw.txt" | sort -n | awk -v x="$a" '
    {f[NR]=$1; t[NR]=$2} END{
      if (NR==0) { printf "| %s | — (유효 판 0) | — |\n", x; exit }
      m=(NR%2)? f[(NR+1)/2] : (f[NR/2]+f[NR/2+1])/2;
      u=(NR%2)? t[(NR+1)/2] : (t[NR/2]+t[NR/2+1])/2;
      printf "| %s | %.1f%% | %.2f |\n", x, m*100, u }'
done
} | tee "$OUT/summary.md"

DB -e "TRUNCATE TABLE pose_data_r276; DROP TABLE IF EXISTS r276_retry_log; DROP PROCEDURE IF EXISTS r276_ins;"
cp "$SC/raw.txt" "$OUT/raw.tsv"
echo
echo "→ $OUT/summary.md (판정은 손으로 쓴 $OUT/README.md 에)"
