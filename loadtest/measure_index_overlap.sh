#!/usr/bin/env bash
# member_id 선두 인덱스 3종이 겹치는가 — 팬아웃을 변수로 놓고 잰다
# (issue #110, docs/decisions/admin-page-scope.md §4-6)
#
# ── 왜 기존 스크래치 DB 로는 못 재는가 ────────────────────────────────────────
#
#   #110 의 가설은 "회원 한 명의 세션은 많아야 수백 건이라, member_id 로 좁힌 뒤 나머지는
#   필터로 거르면 충분하다" 였다. 그런데 measure_admin_filter_explain.sh 가 만든 데이터는
#   member_id = 1 + (n % 200000) 이라 **회원 20만 명이 전원 정확히 5세션**이다(2026-08-07 확인).
#
#   팬아웃이 5 면 member_id 로 좁히는 순간 5행이고, 5행에서 status 를 인덱스로 거르든 필터로
#   거르든 차이가 날 수 없다. 즉 **가설이 참인 쪽으로 데이터가 이미 고정돼 있다** — 이 위에서
#   "차이가 없다"가 나와도 그것은 인덱스의 성질이 아니라 시딩 파라미터의 성질이다.
#   (실제로 IGNORE INDEX 로 흉내내 보면 idx_session_member_exercise_status_start 로 폴백해
#    5행을 읽고 필터하며, 여전히 covering 이라 테이블 조회조차 없다.)
#
#   그래서 질문을 답할 수 있는 형태로 바꾼다:
#     ❌ "(member_id, status) 는 제 몫을 하는가"      ← 이 데이터로는 원리적으로 답이 없다
#     ✅ "회원당 세션 몇 건부터 제 몫을 하기 시작하는가"  ← 팬아웃을 변수로 놓으면 답이 나온다
#
# ── 재는 것 ──────────────────────────────────────────────────────────────────
#
#   [측정 1] 팬아웃 × 인덱스 구성별 **읽기** — 실제 읽은 행(Handler)과 시간
#   [측정 2] 인덱스 구성별 **쓰기 비용**
#   [측정 3] 인덱스 구성별 **공간 점유**
#
# ── 변수와 고정 ──────────────────────────────────────────────────────────────
#
#   팬아웃 F ∈ {5, 50, 500, 2000} 로 테이블 4벌. **총 행수는 100만으로 고정**하고 회원 수를
#   1,000,000/F 로 줄인다 — 그래야 변수가 "테이블 크기"가 아니라 팬아웃 하나가 된다.
#
#   인덱스 구성 4안 (보조 인덱스만, FK 자동 인덱스는 공통):
#     base : (m,st) · (m,status) · (m,e,status,st) · (status,st)   ← 현행 4종
#     ㄱ(ga)  : base − (m,status)                                   ← 3종. "떼도 되나"
#     ㄴ(na)  : base 에서 (m,st)·(m,status) → (m,status,st) 통합    ← 3종. "합칠 수 있나"
#     ㄴ'(nb) : ㄴ 의 컬럼 순서를 뒤집은 (m,st,status)              ← 3종. "그 순서가 맞나"
#
#   ㄴ' 를 넣은 이유 — 1차 측정에서 ㄴ 이 이겼는데, **컬럼 순서의 근거가 없었다.**
#   (m,status,st) 는 #110 본문이 제안한 순서를 그대로 구현한 것이지 비교해서 고른 것이
#   아니다. 등치(member_id·status) 먼저 정렬(start_time) 나중이라는 원리적 근거는 있으나,
#   ㄴ 의 유일한 약점이 Q4(member_id + start_time 범위)였고 그 손해는 정확히 start_time 이
#   뒤에 있어서 생긴 것이다. 뒤집으면 Q4 는 회복되고 Q3 가 나빠질 것이므로, 재기 전에는
#   **어느 쪽이 비싼지 모른다** — 재야 순서가 비로소 선택이 된다.
#
# ── 이 장치가 대답하지 못하는 것 ─────────────────────────────────────────────
#
#   ⚠️ 팬아웃은 **전 회원이 동일**하다. 실제 서비스는 헤비 유저와 1회성 유저가 섞인 롱테일인데
#      여기는 계단이다. 그래서 읽어야 할 것은 "F=500 에서 몇 ms" 가 아니라 **어느 구간에서
#      곡선이 꺾이는가**다.
#   ⚠️ 절대 시간은 이 장비의 것이다(2코어 동거). 동거 노이즈는 시간을 늘리기만 하므로
#      REPS 회 중 **최소값을 신호로** 읽는다(§4-5 ②-1 에서 확인된 성질).
#   ⚠️ [측정 2] 는 INSERT..SELECT 벌크다. 앱의 실제 쓰기는 단건이라 행당 고정비가 다르다 —
#      읽어야 할 것은 절대치가 아니라 **구성 간 비율**이다.
#   ⚠️ status 주변분포는 COMPLETED 50% / FAILED 25% / IN_PROGRESS 25% 로 고정이다.
#      선택도가 다른 분포에서는 꺾이는 지점도 다르다.
set -euo pipefail
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

PW=1234
CONTAINER=shadowfit-mysql
# 규모는 env 로 낮출 수 있다 — 본 측정 전에 작은 규모로 장치 자체를 검증하기 위해서다.
# (SMOKE=1 로 돌린 뒤 결과 표의 모양이 맞는지 보고 본 측정에 들어간다.)
ROWS=${ROWS:-1000000}          # 총 세션 행수 — 팬아웃과 무관하게 고정
IFS=' ' read -r -a FANOUTS <<<"${FANOUTS:-5 50 500 2000}"
REPS=${REPS:-7}                # 7회 중 최소·중앙값. 다른 rig 와 같은 값
WRITE_ROWS=${WRITE_ROWS:-100000}  # [측정 2] 배치 크기
WRITE_REPS=${WRITE_REPS:-5}
DB_SUFFIX=${DB_SUFFIX:-}       # 스모크런이 본 측정 DB 를 덮어쓰지 않게 분리한다
DB_NAME=shadowfit_idx110${DB_SUFFIX}
# 구성 목록도 env 로 좁힐 수 있다 — 반사실 하나만 덧붙여 잴 때 12번의 인덱스 재생성을
# 다시 치르지 않기 위해서다. 단 서로 다른 실행의 시간을 비교하면 장비 상태가 변수로
# 섞이므로, 비교하려는 구성끼리는 **한 번의 실행 안에** 넣어야 한다.
IFS=" " read -r -a CONFIGS <<<"${CONFIGS:-base ga na nb}"

# ⚠️ 실패해도 general log 를 끄고 임시물을 지운다. 켜진 채로 남으면 디스크가 계속 차고
#    **이후의 모든 측정이 느려진다** — 측정 장치가 다음 측정을 오염시키는 것이라
#    §4-2 결함 #4 와 같은 계열이다. DB 자체는 남긴다(재실행 시 시딩을 건너뛰려고).
cleanup(){
  local rc=$?
  docker exec "$CONTAINER" mysql -uroot -p$PW -e "SET GLOBAL general_log='OFF';" 2>/dev/null || true
  docker exec "$CONTAINER" mysql -uroot -p$PW "$DB_NAME" \
    -e "DROP TABLE IF EXISTS es_w;" 2>/dev/null || true
  [[ $rc -ne 0 ]] && echo "!! 비정상 종료(exit $rc) — general log 를 끄고 임시 테이블을 정리했다." >&2
  return 0
}
trap cleanup EXIT

# ⚠️ -i 를 쓰지 않는다. `while read ... done < file` 루프 안에서 docker exec -i 는
#    **그 파일의 나머지를 통째로 먹는다**(measure_admin_b_actual.sh 1차 실행의 사인).
DB(){ docker exec "$CONTAINER" mysql -uroot -p$PW "$@" 2>/dev/null; }
Q(){ DB "$DB_NAME" "$@"; }
QN(){ DB -sN "$DB_NAME" "$@"; }

echo "############ #110 member_id 선두 인덱스 겹침 — 팬아웃별 실측 ############"
echo

# ── [1/5] 시딩 ───────────────────────────────────────────────────────────────
echo "## [1/5] 스크래치 DB 준비 (팬아웃 ${FANOUTS[*]}, 각 ${ROWS} 행)"

if QN -e "SELECT 1 FROM es_f${FANOUTS[-1]} LIMIT 1;" >/dev/null 2>&1; then
  echo "   기존 ${DB_NAME} 재사용 (다시 시딩하려면 DROP DATABASE ${DB_NAME} 후 재실행)"
else
  DB -e "DROP DATABASE IF EXISTS ${DB_NAME}; CREATE DATABASE ${DB_NAME} CHARACTER SET utf8mb4;"

  # 0 ~ ROWS-1 의 수열. 재귀 CTE 는 cte_max_recursion_depth 에 걸리므로 자기조인으로 만든다.
  echo "   _seq ${ROWS} 행 생성"
  Q -e "
  CREATE TABLE _seq (n INT PRIMARY KEY);
  INSERT INTO _seq (n)
  SELECT a.d + b.d*10 + c.d*100 + d.d*1000 + e.d*10000 + f.d*100000
  FROM (SELECT 0 d UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a
  CROSS JOIN (SELECT 0 d UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b
  CROSS JOIN (SELECT 0 d UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c
  CROSS JOIN (SELECT 0 d UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d
  CROSS JOIN (SELECT 0 d UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
  CROSS JOIN (SELECT 0 d UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) f;"

  SEQN=$(QN -e "SELECT COUNT(*) FROM _seq;")
  [[ "$SEQN" -ge "$ROWS" ]] || { echo "!! _seq 가 ${SEQN} 행뿐 — ${ROWS} 미만이라 중단"; exit 1; }

  for F in "${FANOUTS[@]}"; do
    USERS=$(( ROWS / F ))
    echo "   es_f${F} — 회원 ${USERS} × ${F} 세션 = ${ROWS}"
    # 스키마는 V1__baseline.sql 의 exercise_sessions 에서 인덱스만 뺀 형태.
    # FK 는 만들지 않는다 — 여기서 재는 것은 보조 인덱스의 비용이고, FK 자동 인덱스는
    # 세 구성안에 공통이라 변수가 아니다. 대신 exercise_id 인덱스는 명시적으로 넣어
    # 현행과 인덱스 개수를 맞춘다.
    Q -e "
    CREATE TABLE es_f${F} (
      id BIGINT AUTO_INCREMENT PRIMARY KEY,
      member_id BIGINT NOT NULL,
      exercise_id BIGINT NOT NULL,
      start_time DATETIME NOT NULL,
      end_time DATETIME NULL,
      total_reps INT NULL,
      avg_sync_rate DECIMAL(5,2) NULL,
      status ENUM('IN_PROGRESS','COMPLETED','FAILED','CANCELLED') NOT NULL,
      created_at DATETIME NOT NULL,
      INDEX exercise_id (exercise_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;"
    # 시딩 식은 measure_admin_filter_explain.sh 와 같다 — start_time 과 status 를 각각
    # 독립한 MD5 해시에서 뽑는다. n 의 등차 구조가 남으면 member_id 와 종속돼
    # "재려던 성질을 안 갖는" 데이터가 된다(§4-2 결함 #5, CRC32 도 같은 이유로 탈락).
    Q -e "
    INSERT INTO es_f${F}
      (member_id, exercise_id, start_time, end_time, total_reps, avg_sync_rate, status, created_at)
    SELECT 1 + (n % ${USERS}), 1 + (n % 3),
           TIMESTAMP('2025-08-01 06:00:00') + INTERVAL mins MINUTE,
           TIMESTAMP('2025-08-01 06:15:00') + INTERVAL mins MINUTE,
           30, 75.00,
           ELT(1 + (st % 4), 'COMPLETED','COMPLETED','FAILED','IN_PROGRESS'),
           TIMESTAMP('2025-08-01 06:00:00') + INTERVAL mins MINUTE
    FROM (
      SELECT n,
             CONV(SUBSTRING(MD5(CONCAT('ts', n)), 1, 8), 16, 10) % 525600 AS mins,
             CONV(SUBSTRING(MD5(CONCAT('st', n)), 1, 8), 16, 10)          AS st
      FROM _seq WHERE n < ${ROWS}
    ) t;"
  done
fi

# ── [2/5] 시딩 자기검증 ──────────────────────────────────────────────────────
# measure_admin_filter_explain.sh 가 7종을 검사하게 된 이유와 같다 — 결함 3건이
# **스크립트가 성공하고 행 수도 맞은 채로** 났다. 여기서 검사할 것은 팬아웃 그 자체다.
echo
echo "## [2/5] 시딩 자기검증 — 팬아웃이 실제로 그 값인가"
FAIL=0
chk(){ # name actual expect_expr
  local ok; ok=$(QN -e "SELECT IF($2 $3, 'OK', 'FAIL');")
  printf "   %-38s %-14s %s\n" "$1" "$2" "$ok"
  [[ "$ok" == "OK" ]] || FAIL=1
}
for F in "${FANOUTS[@]}"; do
  N=$(QN -e "SELECT COUNT(*) FROM es_f${F};")
  MINMAX=$(QN -e "SELECT CONCAT(MIN(c),'~',MAX(c)) FROM (SELECT COUNT(*) c FROM es_f${F} GROUP BY member_id) t;")
  KINDS=$(QN -e "SELECT ROUND(100*SUM(k=1)/COUNT(*),2) FROM (SELECT COUNT(DISTINCT status) k FROM es_f${F} GROUP BY member_id) t;")
  printf "   es_f%-6s 행 %-9s 회원당 %-10s status 1종 %s%%\n" "$F" "$N" "$MINMAX" "$KINDS"
  [[ "$N" == "$ROWS" ]] || { echo "     !! 행 수 불일치"; FAIL=1; }
  [[ "$MINMAX" == "${F}~${F}" ]] || { echo "     !! 팬아웃이 균일하지 않다"; FAIL=1; }
done
# distinct start_time 이 적으면 시간축이 뭉쳐 Q4(주간 범위)가 무의미해진다 — 결함 #6 계열.
DST=$(QN -e "SELECT COUNT(DISTINCT start_time) FROM es_f${FANOUTS[0]};")
# 서로 다른 분(minute)은 최대 525,600 개다. ROWS 가 그보다 크면 생일 문제로 충돌이 늘어
# 상한이 525,600 에 눌린다 — 그래서 기대치를 min(ROWS, 525600) 에 대한 비율로 본다.
# 고정 상수로 박으면 규모를 낮춰 장치를 검증할 때 오탐이 난다(실제로 났다).
DST_CAP=$(( ROWS < 525600 ? ROWS : 525600 ))
DST_MIN=$(( DST_CAP * 60 / 100 ))
printf "   distinct start_time (es_f%-4s)          %-10s (상한 %s 의 %s%%)\n" \
  "${FANOUTS[0]}" "$DST" "$DST_CAP" "$(( DST * 100 / DST_CAP ))"
[[ "$DST" -gt "$DST_MIN" ]] || { echo "     !! 시간축이 뭉쳐 있다"; FAIL=1; }
[[ "$FAIL" == "0" ]] || { echo; echo "!! 자기검증 실패 — 측정을 시작하지 않는다."; exit 1; }

# ── 인덱스 구성 전환 ─────────────────────────────────────────────────────────
IDX_ALL="idx_m_st idx_m_status idx_m_e_status_st idx_status_st idx_m_status_st idx_m_st_status"
apply_config(){ # table config
  local t=$1 cfg=$2 have
  for i in $IDX_ALL; do
    have=$(QN -e "SELECT COUNT(*) FROM information_schema.STATISTICS
                  WHERE TABLE_SCHEMA='${DB_NAME}' AND TABLE_NAME='${t}' AND INDEX_NAME='${i}';")
    [[ "$have" != "0" ]] && Q -e "ALTER TABLE ${t} DROP INDEX ${i};"
  done
  case "$cfg" in
    base) Q -e "ALTER TABLE ${t}
            ADD INDEX idx_m_st (member_id, start_time),
            ADD INDEX idx_m_status (member_id, status),
            ADD INDEX idx_m_e_status_st (member_id, exercise_id, status, start_time),
            ADD INDEX idx_status_st (status, start_time);" ;;
    ga)   Q -e "ALTER TABLE ${t}
            ADD INDEX idx_m_st (member_id, start_time),
            ADD INDEX idx_m_e_status_st (member_id, exercise_id, status, start_time),
            ADD INDEX idx_status_st (status, start_time);" ;;
    na)   Q -e "ALTER TABLE ${t}
            ADD INDEX idx_m_status_st (member_id, status, start_time),
            ADD INDEX idx_m_e_status_st (member_id, exercise_id, status, start_time),
            ADD INDEX idx_status_st (status, start_time);" ;;
    # ㄴ' — ㄴ안의 컬럼 순서를 뒤집은 반사실. 이것을 재지 않으면 ㄴ안의 순서가
    # **근거 없이 고른 것**이 된다. 1차 측정(2026-08-07)에서 ㄴ안의 유일한 약점이
    # Q4(주간 리포트, member_id + start_time 범위)였는데, 그 손해는 정확히
    # "start_time 이 뒤에 있어서" 생긴 것이다. 뒤집으면 Q4 는 회복되고 Q3 가 나빠질
    # 것이므로, 둘 중 어느 쪽이 비싼지를 재야 순서가 선택이 된다.
    nb)   Q -e "ALTER TABLE ${t}
            ADD INDEX idx_m_st_status (member_id, start_time, status),
            ADD INDEX idx_m_e_status_st (member_id, exercise_id, status, start_time),
            ADD INDEX idx_status_st (status, start_time);" ;;
  esac
  Q -e "ANALYZE TABLE ${t};" >/dev/null
}

# ── 측정 도구 ────────────────────────────────────────────────────────────────
# 시간은 EXPLAIN ANALYZE 의 actual time, 실제 읽은 행은 Handler 카운터로 센다.
# EXPLAIN 의 `rows` 는 쓰지 않는다 — 같은 rig 에서 38배 부풀려진 사례(§4-5)와
# rows=20 인데 20만 행을 읽은 사례(§4-3 ②)가 이미 나왔다.
run_query(){ # table sql -> "minms|medms|handler_rows|access_path"
  local t=$1 sql=$2 i ms times=() path handler
  for ((i=0;i<REPS;i++)); do
    ms=$(Q -e "EXPLAIN ANALYZE ${sql}\G" | grep -o 'actual time=[0-9.]*\.\.[0-9.]*' | head -1 \
         | sed 's/.*\.\.//')
    times+=("$ms")
  done
  local sorted; sorted=$(printf '%s\n' "${times[@]}" | sort -g)
  local minms medms
  minms=$(echo "$sorted" | head -1)
  medms=$(echo "$sorted" | sed -n "$(( (REPS+1)/2 ))p")
  # Handler_read_* 합 — FLUSH STATUS 부터 같은 커넥션이어야 하므로 한 번의 -e 안에서 끝낸다.
  handler=$(QN -e "FLUSH STATUS; ${sql}; SHOW SESSION STATUS WHERE Variable_name IN
             ('Handler_read_key','Handler_read_next','Handler_read_rnd_next','Handler_read_prev');" \
            | awk 'NF==2 && $2 ~ /^[0-9]+$/ {s+=$2} END {print s+0}')
  path=$(Q -e "EXPLAIN ${sql}\G" | awk -F': ' '/^ *key:/{print $2; exit}')
  echo "${minms}|${medms}|${handler}|${path:-NULL}"
}

# 대상 회원 — 팬아웃별로 COMPLETED 와 IN_PROGRESS 를 둘 다 가진 회원을 고른다.
# 고정 상수로 박으면 팬아웃이 작을 때 해당 status 가 없어 0건을 재게 된다.
pick_member(){ # table
  QN -e "SELECT member_id FROM es_f$1 GROUP BY member_id
         HAVING SUM(status='COMPLETED')>0 AND SUM(status='IN_PROGRESS')>0
         ORDER BY member_id LIMIT 1;"
}

# ── [3/5] 읽기 ──────────────────────────────────────────────────────────────
echo
echo "## [3/5] 읽기 — 팬아웃 × 구성 (${REPS}회 중 최소값이 신호)"
echo
for F in "${FANOUTS[@]}"; do
  M=$(pick_member "$F")
  [[ -n "$M" ]] || { echo "   !! es_f${F} 에서 대상 회원을 못 찾았다"; exit 1; }
  echo "── 팬아웃 ${F} (회원 $(( ROWS / F ))명, 대상 member_id=${M}) ──"
  printf "   %-30s %-8s %10s %10s %10s  %s\n" "쿼리" "구성" "min(ms)" "med(ms)" "읽은행" "탄 인덱스"
  for CFG in "${CONFIGS[@]}"; do
    apply_config "es_f${F}" "$CFG"
    while IFS='|' read -r qname qsql; do
      [[ -z "$qname" ]] && continue
      IFS='|' read -r mn md hd pk <<<"$(run_query "es_f${F}" "${qsql//@M/$M}")"
      printf "   %-30s %-8s %10s %10s %10s  %s\n" "$qname" "$CFG" "$mn" "$md" "$hd" "$pk"
    done <<EOF
Q1 exists(m,status)|SELECT 1 FROM es_f${F} WHERE member_id=@M AND status='IN_PROGRESS' LIMIT 1
Q2 ids(m,status)|SELECT id FROM es_f${F} WHERE member_id=@M AND status='COMPLETED'
Q3 first(m,status)ORDER st|SELECT id FROM es_f${F} WHERE member_id=@M AND status='COMPLETED' ORDER BY start_time DESC LIMIT 1
Q4 weekly(m,st BETWEEN)|SELECT id FROM es_f${F} WHERE member_id=@M AND start_time BETWEEN '2025-10-01' AND '2025-10-08'
EOF
    echo
  done
done

# ── [4/5] 쓰기 ──────────────────────────────────────────────────────────────
# 팬아웃 하나(가장 작은 것)에서만 잰다 — 쓰기 비용은 인덱스 구성의 함수이지 팬아웃의
# 함수가 아니다. 단 member_id 의 무작위성은 팬아웃에 따라 달라지므로 그 사실은 남긴다.
echo "## [4/5] 쓰기 — 구성별 ${WRITE_ROWS} 행 삽입 × ${WRITE_REPS}회 (최소값이 신호)"
echo
TW=es_f${FANOUTS[0]}
USERS0=$(( ROWS / FANOUTS[0] ))
printf "   %-8s %10s %10s %10s\n" "구성" "min(s)" "med(s)" "base대비"
BASE_MIN=""
for CFG in "${CONFIGS[@]}"; do
  apply_config "$TW" "$CFG"
  Q -e "DROP TABLE IF EXISTS es_w; CREATE TABLE es_w LIKE ${TW};"
  times=()
  for ((r=0;r<WRITE_REPS;r++)); do
    Q -e "TRUNCATE TABLE es_w;"
    s=$(date +%s.%N)
    Q -e "INSERT INTO es_w (member_id, exercise_id, start_time, end_time, total_reps,
            avg_sync_rate, status, created_at)
          SELECT 1 + (n % ${USERS0}), 1 + (n % 3),
                 TIMESTAMP('2025-08-01 06:00:00') + INTERVAL mins MINUTE,
                 NULL, 30, 75.00,
                 ELT(1 + (st % 4), 'COMPLETED','COMPLETED','FAILED','IN_PROGRESS'),
                 NOW()
          FROM (SELECT n,
                  CONV(SUBSTRING(MD5(CONCAT('w', n)), 1, 8), 16, 10) % 525600 AS mins,
                  CONV(SUBSTRING(MD5(CONCAT('v', n)), 1, 8), 16, 10)          AS st
                FROM _seq WHERE n < ${WRITE_ROWS}) t;"
    e=$(date +%s.%N)
    times+=("$(awk -v a="$e" -v b="$s" 'BEGIN{printf "%.3f", a-b}')")
  done
  sorted=$(printf '%s\n' "${times[@]}" | sort -g)
  mn=$(echo "$sorted" | head -1); md=$(echo "$sorted" | sed -n "$(( (WRITE_REPS+1)/2 ))p")
  [[ -z "$BASE_MIN" ]] && BASE_MIN=$mn
  ratio=$(awk -v a="$mn" -v b="$BASE_MIN" 'BEGIN{printf "%.3f", a/b}')
  printf "   %-8s %10.2f %10.2f %10sx\n" "$CFG" "$mn" "$md" "$ratio"
done
Q -e "DROP TABLE IF EXISTS es_w;"

# ── [5/5] 공간 ──────────────────────────────────────────────────────────────
echo
echo "## [5/5] 공간 — 구성별 인덱스 크기 (es_f${FANOUTS[0]}, ${ROWS} 행)"
echo
printf "   %-8s %14s %14s %10s\n" "구성" "인덱스(MB)" "데이터(MB)" "base대비"
BASE_IDX=""
for CFG in "${CONFIGS[@]}"; do
  apply_config "$TW" "$CFG"
  read -r IDXMB DATMB <<<"$(QN -e "
    SELECT ROUND(INDEX_LENGTH/1024/1024,1), ROUND(DATA_LENGTH/1024/1024,1)
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA='${DB_NAME}' AND TABLE_NAME='${TW}';")"
  [[ -z "$BASE_IDX" ]] && BASE_IDX=$IDXMB
  ratio=$(awk -v a="$IDXMB" -v b="$BASE_IDX" 'BEGIN{printf "%.3f", a/b}')
  printf "   %-8s %14s %14s %10sx\n" "$CFG" "$IDXMB" "$DATMB" "$ratio"
done

# 원래 구성으로 되돌린다 — 다음 실행이 이전 잔여물 위에서 돌면 안 된다.
for F in "${FANOUTS[@]}"; do apply_config "es_f${F}" base; done

echo
echo "############ 완료 — 읽을 때 주의 ############"
echo "  · 절대 시간은 이 장비의 것이다(2코어 동거). 구성 간 **차이**만 읽는다."
echo "  · 팬아웃은 전 회원 동일한 계단이다. 실제는 롱테일이므로 '어디서 꺾이는가'만 읽는다."
echo "  · 읽은행은 Handler 카운터의 실측이고 EXPLAIN 의 rows 견적이 아니다."
