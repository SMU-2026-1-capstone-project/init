#!/usr/bin/env bash
# D 대시보드 집계 5종 — 고친 시딩 위에서 재측정
# (docs/decisions/admin-page-scope.md §4-5, 재측정 2026-08-06)
#
# ── 왜 다시 재는가 ────────────────────────────────────────────────────────────
#
#   §4-5 의 수치는 시딩 결함 3건이 전부 살아 있던 데이터 위에서 나왔다(§4-2 #5·#6):
#     · member_id ↔ status 종속        → 상태별 분포 b 의 값이 인공적
#     · member_id ↔ start_time 종속    → **집계 e(기간 내 COUNT DISTINCT member_id) 직격**
#     · 모든 행이 2벌 + distinct start_time 이 절반 → **집계 a 의 skip scan 카디널리티 직격**
#
#   그래서 §4-5 의 두 헤드라인이 재확인 대상이다:
#     ① a 의 "rows 추정이 38배 부풀려져 있었다" (110,527 추정 vs 2,880 실제)
#     ② e 의 "(start_time, member_id) 를 넣으면 10배" (1,002ms → 99.5ms)
#
# ── 이 장치가 하는 것 ─────────────────────────────────────────────────────────
#
#   [1] AdminStatsExplainCaptureTest 로 **앱이 실제로 보내는** 집계 SQL 5종을 캡처
#   [2] 각각 EXPLAIN ANALYZE — 추정 rows vs 실제 rows, 시간 최소/중앙값
#   [3] e 인덱스 가설 재검증 — (start_time, member_id) 를 임시로 붙였다 뗀다
#
# ── 대답하지 못하는 것 ────────────────────────────────────────────────────────
#
#   ⚠️ 절대 시간은 이 장비(2코어 동거)의 것이다. §4-5 ②-1 에서 확인된 대로 동거 노이즈는
#      시간을 늘리기만 하므로 **최소값을 신호로 읽는다.**
#   ⚠️ 시딩은 여전히 하루·요일 주기가 없는 균등 분포다. 기간 필터의 선택도가 실제와 다르다.
#   ⚠️ b 의 결과값(상태별 건수)은 시딩이 정한 50/25/25 라 무의미하다. 비용만 읽을 것.
#
# ── 얼마나 걸리나 (2026-08-09 실측) ───────────────────────────────────────────
#
#   156초 (2분 36초) — 집계 SQL 캡처(gradle 테스트) + EXPLAIN ANALYZE 7회 × 5종 + [3].
#   ⚠️ **스크래치 DB 가 이미 시딩돼 있을 때**의 값이다. 없으면
#      measure_admin_filter_explain.sh 를 먼저 돌려야 하고 그쪽이 682초 더 든다.
#   ⚠️ 전제: 2물리코어, MySQL 컨테이너 단독. 이웃을 켜둔 채면 더 걸리고 ms 도 부풀려진다.
#
# ── 🔴 [3]단계가 낡았다 (2026-08-09) ──────────────────────────────────────────
#
#   [3] 은 "(start_time, member_id) 를 가설로 임시 추가해 이득을 본다" 는 장치인데,
#   그 가설은 **2026-08-07 에 채택돼 이미 스키마에 있다**(idx_session_starttime_member,
#   V1__baseline.sql:175). 그래서 지금 이 단계는 **같은 컬럼의 중복 인덱스**를 얹는다:
#
#     현행(5개)    최소 13.1ms  스캔 19,019행
#     가설(+6번째) 최소 12.8ms  스캔 19,019행     ← 스캔 행이 같다 = 같은 접근 경로
#
#   비교로서 의미가 없어졌고 라벨이 읽는 사람을 오도한다. 결과는 admin-page-scope.md
#   §4-5-2 ⑤. measure_r.py 가 시그니처 변경을 못 따라와 죽어 있던 것(#145)과 같은 계열인데,
#   그쪽은 터져서 드러났고 이쪽은 **조용히 무의미한 값을 낸다** — 후자가 더 위험하다.
#   (정리 자체는 정상이다: 측정 후 인덱스 6종 = 보조 5 + PRIMARY 로 임시분 제거 확인)
#
set -euo pipefail
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

PW=1234
DB_NAME=shadowfit_explain
CONTAINER=shadowfit-mysql
LOGFILE=/tmp/admin_stats_actual.log
REPS=7

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"

# ⚠️ 실패해도 서버 상태를 되돌린다. 여기서 특히 중요한 것은 **임시 인덱스**다 —
#    [3] 이 (start_time, member_id) 를 붙였다 떼는데, 그 사이에 죽으면 스크래치 DB 에
#    6번째 인덱스가 남는다. 그러면 이후 측정이 **조용히 다른 스키마 위에서** 돌게 되고,
#    그건 §4-2 가 정리한 "틀렸다는 신호조차 없는" 종류의 오염이다.
#    general_log 도 켜진 채 남으면 디스크가 차고 다음 측정이 느려진다.
cleanup(){
  local rc=$?
  docker exec "$CONTAINER" mysql -uroot -p$PW -e "SET GLOBAL general_log='OFF';" 2>/dev/null || true
  docker exec "$CONTAINER" mysql -uroot -p$PW "$DB_NAME" \
    -e "ALTER TABLE exercise_sessions DROP INDEX idx_tmp_start_member;" 2>/dev/null || true
  rm -rf "$WORK"
  [[ $rc -ne 0 ]] && echo "!! 비정상 종료(exit $rc) — general log 를 끄고 임시 인덱스를 제거했다." >&2
  return 0
}
trap cleanup EXIT

DB(){ docker exec "$CONTAINER" mysql -uroot -p$PW "$@" 2>/dev/null; }
Q(){ DB "$DB_NAME" "$@"; }

FAILLOG="$WORK/failed.txt"; : > "$FAILLOG"
ea(){ local out
  if out=$(Q -sN -e "EXPLAIN ANALYZE ${1%;}" 2>/dev/null) && [[ -n "$out" ]]; then printf '%s' "$out"
  else echo "${2:-?}" >> "$FAILLOG"; fi
}
top_time(){ { printf '%s' "$1" | grep -o 'actual time=[0-9.]*\.\.[0-9.]*' || true; } | head -1 | sed 's/.*\.\.//'; }
# ⚠️ EXPLAIN ANALYZE 트리에는 추정과 실측이 **둘 다** 들어 있다:
#      (cost=377043 rows=497472)  (actual time=1415..2513 rows=249554 loops=1)
#    그래서 `rows=` 만 잡으면 둘이 섞여 큰 쪽(대개 추정)이 나온다 — 2026-08-06 1차 실행의
#    'actual rows' 열이 통째로 추정치였던 원인이다. 반드시 앞의 문맥까지 물어서 갈라야 한다.
max_rows(){ { printf '%s' "$1" | grep -o 'actual time=[0-9.]*\.\.[0-9.]* rows=[0-9.e+]*' || true; } \
              | sed 's/.*rows=//' | sort -g | tail -1; }
est_rows(){ { printf '%s' "$1" | grep -o 'cost=[0-9.e+]* rows=[0-9.e+]*' || true; } | sed 's/.*rows=//' | sort -g | tail -1; }

echo "############ D 대시보드 집계 — 재측정 ############"
echo
echo "## [0/3] 전제"
U=$(Q -sN -e "SELECT COUNT(*) FROM users;")
S=$(Q -sN -e "SELECT COUNT(*) FROM exercise_sessions;")
echo "   회원 ${U} / 세션 ${S}"
if [[ "$U" != "200000" || "$S" != "1000000" ]]; then
  echo "!! §4-5 는 회원 20만 / 세션 100만에서 쟀다. 비교가 성립하지 않는다." >&2; exit 1
fi
# 이 스크립트는 고친 시딩을 전제한다. 옛 데이터 위에서 돌면 §4-5 를 그대로 반복할 뿐이다.
SPREAD=$(Q -sN -e "SELECT ROUND(100*STDDEV_POP(member_id)/(200000/SQRT(12)))
  FROM exercise_sessions WHERE start_time>='2025-11-01' AND start_time<'2025-11-02';")
echo "   하루치 member_id 퍼짐 ${SPREAD}% (독립이면 ~100)"
if [[ "$SPREAD" -lt 80 ]]; then
  echo "!! start_time 이 member_id 와 아직 묶여 있다 — measure_admin_filter_explain.sh 를 먼저 돌릴 것." >&2
  exit 1
fi
echo

# ── [1] 집계 SQL 캡처 ─────────────────────────────────────────────────────────
echo "## [1/3] 캡처 — AdminStatsExplainCaptureTest"
DB -e "SET GLOBAL general_log='OFF'; SET GLOBAL log_output='FILE'; SET GLOBAL general_log_file='${LOGFILE}';"
docker exec "$CONTAINER" sh -c "rm -f ${LOGFILE}"
DB -e "SET GLOBAL general_log='ON';"
( cd "$REPO_ROOT"
  ./gradlew --quiet :backend:test --tests '*AdminStatsExplainCaptureTest*' \
            -Dexplain.capture=true --rerun )
DB -e "SET GLOBAL general_log='OFF';"

docker exec "$CONTAINER" cat "$LOGFILE" > "$WORK/general.log"
awk '
  /\/\*EXPLAIN_MARK\*\/[a-z0-9_]+_BEGIN/ {
    match($0, /\/\*EXPLAIN_MARK\*\/[a-z0-9_]+_BEGIN/)
    lbl = substr($0, RSTART + 16, RLENGTH - 16 - 6); next }
  /\/\*EXPLAIN_MARK\*\/[a-z0-9_]+_END/ { lbl = ""; next }
  lbl != "" {
    line = $0; sub(/^.*[ \t]Query[ \t]+/, "", line)
    if (line ~ /^[Ss][Ee][Ll][Ee][Cc][Tt] / && line ~ /[Ff][Rr][Oo][Mm] (users|exercise_sessions)/)
      printf "%s\t%s\n", lbl, line }
' "$WORK/general.log" > "$WORK/captured.tsv"
CAPTURED=$(wc -l < "$WORK/captured.tsv")
[[ "$CAPTURED" -eq 0 ]] && { echo "!! 캡처된 SQL 이 없다." >&2; exit 1; }
grep -q '?' "$WORK/captured.tsv" && { echo "!! SQL 에 '?' 가 남았다 — 서버 측 prepared statement." >&2; exit 1; }
echo "   ${CAPTURED}건 캡처"
nl -ba "$WORK/captured.tsv" | sed 's/\t/\n      /' | sed 's/^/   /'
echo

# ── [2] 추정 vs 실제 ──────────────────────────────────────────────────────────
# §4-5 의 성과가 "rows 는 견적이지 측정이 아니다"였으므로, 그 격차를 표의 1급 열로 둔다.
echo "## [2/3] EXPLAIN ANALYZE — 추정 rows vs 실제 rows, 시간 ${REPS}회"
printf "%-26s %12s %12s %8s %10s %10s\n" "집계" "추정rows" "실제rows" "배수" "최소(ms)" "중앙값"
while IFS=$'\t' read -r lbl sql; do
  Q -e "${sql%;}" >/dev/null 2>&1 || true
  vals=""; er=""; ar=""
  for _ in $(seq $REPS); do
    tree=$(ea "$sql" "$lbl")
    [[ -z "$tree" ]] && continue
    vals="${vals}$(top_time "$tree")\n"
    [[ -z "$er" ]] && er=$(est_rows "$tree") && ar=$(max_rows "$tree")
  done
  if [[ -z "$vals" ]]; then printf "%-26s %12s\n" "$lbl" "실패"; continue; fi
  sorted=$(printf "$vals" | grep -v '^$' | sort -n)
  nv=$(printf '%s\n' "$sorted" | wc -l | tr -d ' ')
  ratio=$(awk -v e="${er:-0}" -v a="${ar:-0}" 'BEGIN{ if(a>0) printf "%.1fx", e/a; else printf "-" }')
  printf "%-26s %12s %12s %8s %10s %10s\n" "$lbl" "${er:--}" "${ar:--}" "$ratio" \
    "$(printf '%s\n' "$sorted" | head -1)" \
    "$(printf '%s\n' "$sorted" | awk -v m=$(( (nv+1)/2 )) 'NR==m')"
done < "$WORK/captured.tsv"
echo

# ── [3] e 인덱스 가설 재검증 ──────────────────────────────────────────────────
# §4-5 는 (start_time, member_id) 로 1,002ms → 99.5ms (10배) 를 봤다. 그 측정은 start_time 이
# member_id 와 묶여 있던 데이터 위였다 — 기간을 자르면 회원이 한 구간에 몰려 있었으므로
# 중복 제거가 실제보다 쉬웠을 수 있다. 고친 데이터에서 다시 본다.
echo "## [3/3] e 활성 회원 — (start_time, member_id) 인덱스 가설 재검증"
E_SQL=$(awk -F'\t' '$1 ~ /e_active/ {print $2; exit}' "$WORK/captured.tsv")
if [[ -z "$E_SQL" ]]; then
  echo "   !! e 집계 SQL 을 못 찾았다 (라벨 e_active*)"
else
  bench(){ # $1=라벨
    local vals="" tree
    Q -e "${E_SQL%;}" >/dev/null 2>&1 || true
    for _ in $(seq $REPS); do
      tree=$(ea "$E_SQL" "e/$1")
      [[ -n "$tree" ]] && vals="${vals}$(top_time "$tree")\n"
    done
    local sorted nv; sorted=$(printf "$vals" | grep -v '^$' | sort -n)
    nv=$(printf '%s\n' "$sorted" | wc -l | tr -d ' ')
    [[ -z "$sorted" ]] && { printf "   %-14s 실패\n" "$1"; return; }
    printf "   %-14s 최소 %8s ms  중앙값 %8s ms  스캔 %s행\n" "$1" \
      "$(printf '%s\n' "$sorted" | head -1)" \
      "$(printf '%s\n' "$sorted" | awk -v m=$(( (nv+1)/2 )) 'NR==m')" \
      "$(max_rows "$(ea "$E_SQL" "e/$1/rows")")"
  }
  bench "현행(5개)"
  Q -e "ALTER TABLE exercise_sessions ADD INDEX idx_tmp_start_member (start_time, member_id);" >/dev/null
  Q -e "ANALYZE TABLE exercise_sessions;" >/dev/null
  bench "가설(+6번째)"
  # 임시 인덱스는 반드시 뗀다 — 남기면 이후 측정이 조용히 다른 스키마 위에서 돈다.
  Q -e "ALTER TABLE exercise_sessions DROP INDEX idx_tmp_start_member;" >/dev/null
  Q -e "ANALYZE TABLE exercise_sessions;" >/dev/null
  echo "   (임시 인덱스 제거 완료 — 현재 인덱스 $(Q -sN -e "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='${DB_NAME}' AND table_name='exercise_sessions';")종)"
fi
echo

if [[ -s "$FAILLOG" ]]; then
  echo "🔴🔴 실행 실패한 쿼리가 있다:"; sort "$FAILLOG" | uniq -c | sed 's/^/     /'; echo
fi

echo "############ 읽는 법 ############"
echo "[2] '배수' 는 추정rows/실제rows — 1.0 에서 멀수록 옵티마이저 견적이 빗나간 것이다."
echo "    §4-5 는 a 에서 38배(비관), §4-3 은 (b) 에서 낙관 방향으로 빗나간 사례를 이미 남겼다."
echo "[3] 시간은 최소값을 읽을 것. 동거 노이즈는 늘리기만 한다(§4-5 ②-1)."
