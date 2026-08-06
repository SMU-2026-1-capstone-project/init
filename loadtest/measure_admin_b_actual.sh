#!/usr/bin/env bash
# B 세션 목록 — 견적이 아닌 **실측**, 그리고 조건부 조인이 값을 하는가
# (docs/decisions/admin-page-scope.md §4-4 가 남긴 미측정 3건, 2026-08-06)
#
# ── 왜 다시 재는가 ────────────────────────────────────────────────────────────
#
#   §4-4 의 결과표(433,236 · 126,658 …)는 전부 EXPLAIN 의 `rows` 다. 그런데 바로 다음
#   측정인 §4-5 에서 그 `rows` 가 **38배 부풀려진** 사례가 나왔다(110,527 추정 / 2,880 실제).
#   §4-3 ② 는 반대 방향의 같은 사례였다(rows=20 인데 20만 행을 읽음).
#
#   같은 문서 안에서 "rows 는 견적이지 측정이 아니다" 를 두 번 확인해놓고 B 절만 견적 위에
#   서 있다. A 는 핸들러 카운터로 실제 행을 셌지만 B 는 그것도 없다.
#
# ── 재는 것 셋 ────────────────────────────────────────────────────────────────
#
#   [측정 1] B 5개 조합 × (목록·총건수) 의 **실제 읽은 행 수와 시간** — EXPLAIN ANALYZE
#   [측정 2] **조건부 조인이 값을 하는가** — countOf 가 조인을 빼는 것 vs 안 뺀 반사실
#   [측정 3] (start_time, member_id) **6번째 인덱스의 쓰기 비용** — §4-5 가 미측정으로 남김
#
# ── 이 장치가 대답하지 못하는 것 ──────────────────────────────────────────────
#
#   ⚠️ 절대 시간은 이 장비의 것이다(2코어 동거). §4-5 ②-1 에서 확인된 대로 **동거 노이즈는
#      시간을 늘리기만 하므로 최소값을 신호로 읽는다.** 중앙값도 같이 낸다.
#   ⚠️ 값 분포는 여전히 균일 합성이다(§4-3 한계 2번). 실제 읽은 행 수는 이 분포에서의 값이다.
#   ⚠️ [측정 3] 은 INSERT..SELECT 벌크다. 앱의 실제 쓰기는 단건이라 행당 고정비가 다르다 —
#      읽어야 할 것은 절대치가 아니라 5-인덱스 대비 6-인덱스의 **비율**이다.
set -euo pipefail
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

PW=1234
DB_NAME=shadowfit_explain
CONTAINER=shadowfit-mysql
LOGFILE=/tmp/admin_b_actual_capture.log
REPS=7            # §4-5 ②-1 과 같다. 7회 중 최소·중앙값을 본다
WRITE_ROWS=100000 # [측정 3] 배치 크기
WRITE_REPS=5

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ⚠️ -i 를 쓰지 않는다. 이 스크립트는 mysql 에 stdin 을 물려줄 일이 없는데, -i 를 붙이면
#    `while read ... done < file` 루프 안에서 docker exec 가 **그 파일의 나머지를 통째로 먹는다.**
#    1차 실행(2026-08-06)에서 12개 쿼리 중 1개만 측정된 원인이 이것이었다.
DB(){ docker exec "$CONTAINER" mysql -uroot -p$PW "$@" 2>/dev/null; }
Q(){ DB "$DB_NAME" "$@"; }

# ── [0] 전제 확인 ─────────────────────────────────────────────────────────────
# measure_admin_filter_explain.sh 가 만든 스크래치 DB 를 재사용한다. 다시 시딩하지 않는
# 이유는 시간이 아니라 **비교 가능성**이다 — §4-4 와 같은 데이터 위에서 재야 "견적 vs 실측"
# 이 같은 대상을 가리킨다. 그래서 규모가 어긋나면 여기서 멈춘다.
echo "############ B 실측 · 조건부 조인 · 쓰기 비용 ############"
echo
echo "## [0/4] 전제 — 스크래치 DB 규모 확인"
if ! DB -e "USE ${DB_NAME};" 2>/dev/null; then
  echo "!! ${DB_NAME} 이 없다. 먼저 measure_admin_filter_explain.sh 를 돌릴 것." >&2
  exit 1
fi
U=$(Q -sN -e "SELECT COUNT(*) FROM users;")
S=$(Q -sN -e "SELECT COUNT(*) FROM exercise_sessions;")
echo "   회원 ${U} / 세션 ${S}"
if [[ "$U" != "200000" || "$S" != "1000000" ]]; then
  echo "!! §4-4 는 회원 20만 / 세션 100만에서 쟀다. 규모가 달라 비교가 성립하지 않는다." >&2
  exit 1
fi
echo

# ── [1] 실제 SQL 캡처 ─────────────────────────────────────────────────────────
# §4-3 이 세운 원칙 그대로 — SQL 을 손으로 쓰지 않고 general log 에서 받는다.
echo "## [1/4] 캡처 — 실제 QueryDSL 코드 경로 (반사실 2건 포함)"
DB -e "
SET GLOBAL general_log = 'OFF';
SET GLOBAL log_output = 'FILE';
SET GLOBAL general_log_file = '${LOGFILE}';"
docker exec "$CONTAINER" sh -c "rm -f ${LOGFILE}"
DB -e "SET GLOBAL general_log = 'ON';"

(
  cd "$REPO_ROOT"
  ./gradlew --quiet :backend:test --tests '*AdminSessionExplainCaptureTest*' \
            -Dexplain.capture=true --rerun
)
DB -e "SET GLOBAL general_log = 'OFF';"

docker exec "$CONTAINER" cat "$LOGFILE" > "$WORK/general.log"
awk '
  /\/\*EXPLAIN_MARK\*\/[a-z0-9_]+_BEGIN/ {
    match($0, /\/\*EXPLAIN_MARK\*\/[a-z0-9_]+_BEGIN/)
    lbl = substr($0, RSTART + 16, RLENGTH - 16 - 6); next
  }
  /\/\*EXPLAIN_MARK\*\/[a-z0-9_]+_END/ { lbl = ""; next }
  lbl != "" {
    line = $0
    sub(/^.*[ \t]Query[ \t]+/, "", line)
    if (line ~ /^[Ss][Ee][Ll][Ee][Cc][Tt] / && line ~ /[Ff][Rr][Oo][Mm] (users|exercise_sessions)/)
      printf "%s\t%s\n", lbl, line
  }
' "$WORK/general.log" > "$WORK/captured.tsv"

CAPTURED=$(wc -l < "$WORK/captured.tsv")
[[ "$CAPTURED" -eq 0 ]] && { echo "!! 캡처된 SQL 이 없다." >&2; exit 1; }
if grep -q '?' "$WORK/captured.tsv"; then
  echo "!! SQL 에 '?' 가 남아 있다 — 서버 측 prepared statement. 이 장치의 전제가 깨졌다." >&2
  exit 1
fi
# 반사실 2건이 실제로 잡혔는지 확인한다. 없으면 [측정 2] 가 조용히 비어버린다.
for need in s_x_alwaysjoin_status s_y_alwaysjoin_status_keyword; do
  grep -q "^${need}" "$WORK/captured.tsv" || { echo "!! 반사실 ${need} 미캡처." >&2; exit 1; }
done
echo "   ${CAPTURED}건 캡처 (조합 5 × 2 + 반사실 2)"
echo
# 캡처된 SQL 을 그대로 찍는다. 1차 실행에서 반사실의 조인 단수가 0 으로 나와 "표시 버그인가
# Hibernate 가 조인을 지운 것인가"를 가릴 수 없었다 — 어느 쪽이든 SQL 을 봐야 알 수 있다.
echo "## [1-1/4] 캡처된 SQL 원문"
nl -ba "$WORK/captured.tsv" | sed 's/\t/\n      /' | sed 's/^/   /'
echo

# ── [2] EXPLAIN ANALYZE — 견적이 아니라 실측 ──────────────────────────────────
# 계획(EXPLAIN)은 §4-4 에 이미 있다. 여기서 새로 얻는 것은 두 가지다:
#   ① actual rows — 옵티마이저 견적과 얼마나 벌어지는가
#   ② actual time — 그 스캔이 실제로 몇 ms 인가
# 쿼리 하나가 실패해도 측정을 통째로 잃지 않는다. 1차·2차 실행이 각각 [3]·[2] 에서
# set -e 로 즉사해 앞의 결과까지 못 건졌다. 측정 장치는 **실패를 표시하고 계속 가야** 한다
# (§4-2 결함 #3 이 남긴 "틀렸다는 신호가 있어야 한다"의 다른 면).
# ⚠️ 실패 기록은 **파일**에 남긴다. ea() 는 대부분 명령 치환 `$(ea ...)` 안에서 불리는데
#    그건 서브셸이라 변수에 적으면 부모로 안 올라온다.
FAILLOG="$WORK/failed.txt"; : > "$FAILLOG"
ea(){ # EXPLAIN ANALYZE 실행. 실패하면 기록하고 빈 문자열 반환, 스크립트는 계속.
  local out
  if out=$(Q -sN -e "EXPLAIN ANALYZE ${1%;}" 2>/dev/null) && [[ -n "$out" ]]; then
    printf '%s' "$out"
  else
    echo "${2:-?}" >> "$FAILLOG"
  fi
}
# 트리 문자열에서 값 하나 뽑기. grep 이 못 찾아도 죽지 않는다(pipefail 대비).
top_time(){ { printf '%s' "$1" | grep -o 'actual time=[0-9.]*\.\.[0-9.]*' || true; } | head -1 | sed 's/.*\.\.//'; }
# ⚠️ EXPLAIN ANALYZE 트리에는 추정과 실측이 **둘 다** 들어 있다:
#      (cost=377043 rows=497472)  (actual time=1415..2513 rows=249554 loops=1)
#    `rows=` 만 잡으면 둘이 섞여 큰 쪽(대개 추정)이 나온다 — 2026-08-06 실행에서
#    'actual rows' 열이 통째로 추정치였던 원인이다. 앞 문맥까지 물어서 갈라야 한다.
max_rows(){ { printf '%s' "$1" | grep -o 'actual time=[0-9.]*\.\.[0-9.]* rows=[0-9.e+]*' || true; } \
              | sed 's/.*rows=//' | sort -g | tail -1; }

echo "## [2/4] EXPLAIN ANALYZE — 실행 트리 (조합별 1회)"
while IFS=$'\t' read -r lbl sql; do
  echo "── ${lbl} ──────────────────────────────────────────────"
  out=$(ea "$sql" "$lbl")
  if [[ -n "$out" ]]; then printf '%s\n' "$out" | sed 's/^/   /'
  else echo "   🔴 실행 실패 — 이 조합은 결과 없음"; fi
  echo
done < "$WORK/captured.tsv"

echo "## [2-1/4] 시간 — ${REPS}회 중 최소 / 중앙값 (ms)"
printf "%-32s %10s %10s %12s\n" "조합" "최소" "중앙값" "actual rows"
while IFS=$'\t' read -r lbl sql; do
  Q -e "${sql%;}" >/dev/null 2>&1 || true   # 워밍업 (실패해도 진행)
  vals=""; rows=""
  for _ in $(seq $REPS); do
    tree=$(ea "$sql" "$lbl@time")
    [[ -z "$tree" ]] && continue
    vals="${vals}$(top_time "$tree")\n"
    # actual rows 는 트리 **전체**의 최대값을 쓴다. 최상위 노드를 보면 `Limit: 20` 이나
    # count 결과 1 이 찍혀 아무 정보가 없다(1차 실행이 그랬다). 알고 싶은 것은
    # "몇 행을 실제로 만졌나"이므로 최대 스캔 폭이 맞다.
    [[ -z "$rows" ]] && rows=$(max_rows "$tree")
  done
  if [[ -z "$vals" ]]; then
    printf "%-32s %10s %10s %12s\n" "$lbl" "실패" "-" "-"
    continue
  fi
  sorted=$(printf "$vals" | grep -v '^$' | sort -n)
  nvals=$(printf '%s\n' "$sorted" | wc -l | tr -d ' ')
  printf "%-32s %10s %10s %12s\n" "$lbl" \
    "$(printf '%s\n' "$sorted" | head -1)" \
    "$(printf '%s\n' "$sorted" | awk -v m=$(( (nvals + 1) / 2 )) 'NR==m')" "$rows"
done < "$WORK/captured.tsv"
echo

# ── [3] 조건부 조인이 값을 하는가 ─────────────────────────────────────────────
# 짝: s_a(현행 총건수, 조인 0) vs s_x(반사실, 조인 2)
#     s_d(현행 총건수, 조인 1) vs s_y(반사실, 조인 2)
# 같은 조건·같은 답인데 조인 구성만 다르다. 시간이 같으면 옵티마이저가 어차피 조인을
# 지웠다는 뜻이고, 그러면 countOf 의 조건부 조인은 값을 하지 않는 코드가 된다.
echo "## [3/4] 조건부 조인 — 현행 vs 반사실(조인을 안 뺐다면)"
compare_pair() {   # $1=현행 라벨(총건수 쪽) $2=반사실 라벨 $3=설명
  local cur_sql alt_sql
  # 한 조합에서 목록·총건수 두 줄이 잡히는데, count 쿼리는 select count( 로 시작한다.
  cur_sql=$(awk -F'\t' -v l="$1" '$1==l && $2 ~ /^select count\(/ {print $2; exit}' "$WORK/captured.tsv")
  alt_sql=$(awk -F'\t' -v l="$2" '$1==l {print $2; exit}' "$WORK/captured.tsv")
  [[ -z "$cur_sql" || -z "$alt_sql" ]] && { echo "   !! $1 / $2 SQL 을 못 찾음"; return; }

  echo "── ${3}"
  local cn an
  cn=$(Q -sN -e "${cur_sql%;}" 2>/dev/null || echo "?")
  an=$(Q -sN -e "${alt_sql%;}" 2>/dev/null || echo "?")
  # 같은 답이 나와야 비교가 성립한다. 다르면 조인이 행을 거르고 있다는 뜻이라
  # "불필요한 조인"이라는 전제 자체가 틀린 것이므로 그 사실이 결과다.
  echo "   결과값  현행=${cn}  반사실=${an}$([[ "$cn" == "$an" ]] && echo '  ✅ 동일' || echo '  🔴 다르다 — 조인이 행을 거른다')"
  for tag in 현행 반사실; do
    local sql; [[ "$tag" == "현행" ]] && sql="$cur_sql" || sql="$alt_sql"
    Q -e "${sql%;}" >/dev/null 2>&1 || true
    local vals="" tree
    for _ in $(seq $REPS); do
      tree=$(ea "$sql" "$1/$tag")
      [[ -n "$tree" ]] && vals="${vals}$(top_time "$tree")\n"
    done
    local sorted nv; sorted=$(printf "$vals" | grep -v '^$' | sort -n)
    nv=$(printf '%s\n' "$sorted" | wc -l | tr -d ' ')
    # 조인 단수는 SQL 원문에서 센다. 1차 실행의 표시가 미덥지 않았으므로 근거가 되는
    # FROM~WHERE 구간도 같이 찍어, 표에 적힌 단수를 눈으로 검증할 수 있게 한다.
    # ⚠️ `|| true` 가 없으면 조인이 0개인 SQL 에서 grep 이 exit 1 을 내고, set -o pipefail 이
    #    그걸 파이프라인 실패로 올려 set -e 가 스크립트를 죽인다. 1차 실행에서 [3] 이 첫 짝의
    #    첫 줄만 찍고 종료한 원인이 이것이었다 — 조인 개수를 세려고 넣은 코드가 조인 0개를
    #    만나 죽었다.
    local njoin from_clause
    njoin=$( { printf '%s' "$sql" | grep -oi '[[:space:]]join[[:space:]]' || true; } | wc -l | tr -d ' ')
    from_clause=$(printf '%s' "$sql" | sed -n 's/.*\([Ff][Rr][Oo][Mm] .*\)/\1/p' | cut -c1-150)
    printf "   %-6s 최소 %8s ms  중앙값 %8s ms   조인 %s단\n" "$tag" \
      "$(printf '%s\n' "$sorted" | head -1)" \
      "$(printf '%s\n' "$sorted" | awk -v m=$(( (nv + 1) / 2 )) 'NR==m')" "$njoin"
    printf "          %s\n" "$from_clause"
  done
  echo
}
compare_pair s_a_status_only      s_x_alwaysjoin_status         "(a) 상태만 — 현행은 조인 0, 반사실은 2"
compare_pair s_d_status_keyword   s_y_alwaysjoin_status_keyword "(d) 상태+검색어 — 현행은 조인 1(필요), 반사실은 2"

# ── [4] 6번째 인덱스의 쓰기 비용 ──────────────────────────────────────────────
# §4-5 는 (start_time, member_id) 가 e 집계를 10배 줄이는 것만 재고 쓰기는 "미측정"으로
# 남겼다. §4-1 도 쓰기는 "방향만 확정, 배수 미확정" 이다.
#
# ⚠️ 삽입 **순서**를 나눠 잰다. 시딩된 start_time 은 n%525600 분이라 사실상 무작위인데,
#    실제 서비스의 세션은 시간순으로 쌓인다(append). (start_time, ...) 인덱스에 무작위
#    삽입은 최악, 시간순은 최선이라 **둘을 안 나누면 실제보다 나쁜 수치가 나온다.**
#
# ⚠️ 두 원천 모두 **PK 순서로 읽어 넣는다.** 1차 판은 src_sorted 를 start_time 순으로
#    읽었는데, 그러면 클러스터 PK 쪽이 무작위 삽입이 되어 **재려던 보조 인덱스 효과가
#    PK 삽입 비용에 묻힌다.** 그래서 시간순 원천은 id 를 start_time 순으로 다시 매겨,
#    두 경우 모두 PK 는 append 이고 **차이가 보조 인덱스 하나뿐**이 되게 만든다.
#    (1차 판의 `ALTER TABLE ... DROP PRIMARY KEY` 는 AUTO_INCREMENT 컬럼이라 애초에 실패해
#     [4] 가 통째로 안 돌았다.)
echo "## [4/4] 쓰기 비용 — 5개 인덱스 vs 6개 (${WRITE_ROWS}행 × ${WRITE_REPS}회, 최소값)"

# 컬럼 목록을 스키마에서 읽는다. 손으로 나열하면 스키마가 바뀔 때 조용히 어긋난다
# (1차 판이 9개만 나열해 실제 16개 컬럼과 맞지 않았다).
COLS=$(Q -sN -e "SELECT GROUP_CONCAT(column_name ORDER BY ordinal_position)
                 FROM information_schema.columns
                 WHERE table_schema='${DB_NAME}' AND table_name='exercise_sessions';")
COLS_NOID=$(Q -sN -e "SELECT GROUP_CONCAT(column_name ORDER BY ordinal_position)
                      FROM information_schema.columns
                      WHERE table_schema='${DB_NAME}' AND table_name='exercise_sessions'
                        AND column_name <> 'id';")
[[ -z "$COLS" || -z "$COLS_NOID" ]] && { echo "!! 컬럼 목록을 못 읽었다." >&2; exit 1; }

Q -e "
DROP TABLE IF EXISTS src_rand, src_seq;
CREATE TABLE src_rand LIKE exercise_sessions;
INSERT INTO src_rand SELECT * FROM exercise_sessions LIMIT ${WRITE_ROWS};
CREATE TABLE src_seq LIKE exercise_sessions;
INSERT INTO src_seq (id, ${COLS_NOID})
  SELECT ROW_NUMBER() OVER (ORDER BY start_time), ${COLS_NOID} FROM src_rand;" >/dev/null
# src_rand : id 오름차순, start_time 무작위  → PK append / 보조 인덱스 무작위
# src_seq  : id 를 start_time 순으로 재부여   → PK append / 보조 인덱스 append (실제에 가까움)

printf "%-12s %-14s %10s %10s %12s\n" "인덱스" "start_time" "최소(ms)" "중앙값(ms)" "인덱스KB"
for idx in 5 6; do
  for order in rand seq; do
    src="src_${order}"
    vals=""
    for _ in $(seq $WRITE_REPS); do
      Q -e "DROP TABLE IF EXISTS es_w; CREATE TABLE es_w LIKE exercise_sessions;" >/dev/null
      [[ "$idx" == "6" ]] && Q -e "ALTER TABLE es_w ADD INDEX idx_tmp_start_member (start_time, member_id);" >/dev/null
      t=$(Q -sN -e "
        SET @t0 = NOW(6);
        INSERT INTO es_w (${COLS}) SELECT ${COLS} FROM ${src};
        SELECT ROUND(TIMESTAMPDIFF(MICROSECOND, @t0, NOW(6))/1000, 1);" | tail -1)
      vals="${vals}${t}\n"
    done
    # 마지막 라운드의 es_w 인덱스 크기. ⚠️ information_schema.INDEX_LENGTH 는 InnoDB 가
    # 주기적으로 갱신하는 **추정치**라 ANALYZE 없이 읽으면 어긋난다 — 2026-08-06 실행에서
    # "인덱스를 하나 더 넣었는데 총합이 줄어드는" 값이 나왔다. 그래서 먼저 ANALYZE 한다.
    Q -e "ANALYZE TABLE es_w;" >/dev/null
    ilen=$(Q -sN -e "SELECT ROUND(INDEX_LENGTH/1024) FROM information_schema.TABLES
                     WHERE TABLE_SCHEMA='${DB_NAME}' AND TABLE_NAME='es_w';")
    sorted_v=$(printf "$vals" | sort -n)
    order_label=$([[ "$order" == "rand" ]] && echo "무작위(최악)" || echo "시간순(실제)")
    printf "%-12s %-14s %10s %10s %12s\n" "${idx}개" "$order_label" \
      "$(printf '%s\n' "$sorted_v" | head -1)" \
      "$(printf '%s\n' "$sorted_v" | awk -v r=$WRITE_REPS 'NR==int((r+1)/2)')" "$ilen"
  done
done
Q -e "DROP TABLE IF EXISTS es_w, src_rand, src_seq;" >/dev/null
echo
echo "   ※ 두 원천은 변수가 **둘** 다르다(2026-08-06 확인). src_rand 는 start_time 이 무작위인"
echo "     대신 member_id 가 오름차순이고, src_seq 는 그 반대다 — 시딩의 member_id = 1+(n%%200000)"
echo "     이 앞 10만 행에서 id 와 100%% 같이 가기 때문이다. 그래서 이 표는 start_time 순서만"
echo "     가려내지 못하고, '무작위 인덱스 1종 vs 3종' 의 비교가 된다. 깨끗한 분리는 2x2 가 필요하다."
echo "   ※ 인덱스KB 는 ANALYZE 후 값이다. 다만 삽입 순서가 인덱스 크기에 미치는 영향은 별도"
echo "     실험(단일 인덱스 20만 행)에서 **관측되지 않았다** — 순차·무작위 모두 289 페이지였고,"
echo "     change buffering 을 꺼도 같았다. 이유는 미규명이다."
echo

# 실패한 쿼리가 있었다면 마지막에 크게 알린다. 조용히 빠진 측정이 가장 위험하다.
if [[ -s "$FAILLOG" ]]; then
  echo "🔴🔴 실행에 실패한 쿼리가 있다 — 위 표에서 해당 항목은 비어 있거나 표본이 적다:"
  sort "$FAILLOG" | uniq -c | sed 's/^/     /'
  echo
fi

echo "############ 읽는 법 ############"
echo "[2] rows 열은 EXPLAIN ANALYZE 의 actual — §4-4 표의 견적과 벌어지는 만큼이 이 측정의 성과다."
echo "[3] 현행·반사실의 시간이 같으면 조건부 조인은 값을 하지 않는 코드다(옵티마이저가 이미 지움)."
echo "[4] 무작위 삽입은 최악, 시간순은 실제에 가깝다. 6개/5개의 비율만 읽을 것."
