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
#   그래서 §4-5 의 두 헤드라인이 재확인 대상이었다:
#     ① a 의 "rows 추정이 38배 부풀려져 있었다" (110,527 추정 vs 2,880 실제)  ← 이 스크립트가 본다
#     ② e 의 "(start_time, member_id) 를 넣으면 10배" (1,002ms → 99.5ms)
#        ← **이 스크립트는 더 이상 안 본다.** 가설이 채택돼 스키마에 들어갔다(아래 [3] 제거 기록)
#
# ── 이 장치가 하는 것 ─────────────────────────────────────────────────────────
#
#   [1] AdminStatsExplainCaptureTest 로 **앱이 실제로 보내는** 집계 SQL 5종을 캡처
#   [2] 각각 EXPLAIN ANALYZE — 추정 rows vs 실제 rows, 시간 최소/중앙값
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
#   156초 (2분 36초) — 집계 SQL 캡처(gradle 테스트) + EXPLAIN ANALYZE 7회 × 5종 + 옛 [3].
#   ⚠️ 이 값은 [3] 을 떼기 전의 것이다. 지금은 그만큼 짧아지지만 재측정하지 않았다.
#   ⚠️ **스크래치 DB 가 이미 시딩돼 있을 때**의 값이다. 없으면
#      measure_admin_filter_explain.sh 를 먼저 돌려야 하고 그쪽이 682초 더 든다.
#   ⚠️ 전제: 2물리코어, MySQL 컨테이너 단독. 이웃을 켜둔 채면 더 걸리고 ms 도 부풀려진다.
#
# ── [3]단계를 뗐다 (2026-08-12, 이슈 #153) ────────────────────────────────────
#
#   있던 것: "(start_time, member_id) 를 가설로 임시 추가해 이득을 본다".
#   그 가설은 **2026-08-07 에 채택돼 이미 스키마에 있다**(idx_session_starttime_member,
#   V1__baseline.sql:175). 그래서 이 단계는 **같은 컬럼의 중복 인덱스**를 얹고 있었다:
#
#     현행(5개)    최소 13.1ms  스캔 19,019행
#     가설(+6번째) 최소 12.8ms  스캔 19,019행     ← 스캔 행이 같다 = 같은 접근 경로
#
#   터지지 않고 **조용히 무의미한 값을 냈다.** 라벨만 보면 "6번째 인덱스는 효과가 없다" 는
#   정반대 결론으로 읽힌다(실제로는 이미 들어가 있어서 이득이 «현행» 쪽에 포함된 것).
#   measure_r.py 가 시그니처 드리프트로 죽어 있던 것(#145)과 같은 계열인데, 그쪽은 터져서
#   드러났고 이쪽은 안 터진다 — 후자가 더 위험하다. 결과 원본은 admin-page-scope.md §4-5-2 ⑤.
#
#   🔴 **«역방향»(채택된 인덱스를 떼고 대조) 으로 고쳐 쓰지 않았다.** 그렇게 하면 이 데이터
#      위에서 나온 ms 를 인용하게 되는데, 시딩이 균등 분포라 선택도가 가짜다. 같은 이유로
#      measure_admin_index.sh 도 «읽기 이득은 EXPLAIN 계획 변화까지, 시간 수치는 안 낸다» 로
#      선을 그어 두었다. 그 선을 여기서만 넘을 근거가 없다.
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

# ⚠️ 실패해도 서버 상태를 되돌린다. general_log 가 켜진 채 남으면 디스크가 차고 다음 측정이
#    느려진다.
#    임시 인덱스 제거는 **이제 이 스크립트가 만들지 않는 것을 지운다** — 옛 [3] 이 죽으면서
#    스크래치 DB 에 idx_tmp_start_member 를 남겼을 수 있어서 청소만 남겨 둔다. 남아 있으면
#    이후 측정이 **조용히 다른 스키마 위에서** 돌고, 그건 §4-2 가 정리한 "틀렸다는 신호조차
#    없는" 종류의 오염이다.
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
echo "## [0/2] 전제"
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
echo "## [1/2] 캡처 — AdminStatsExplainCaptureTest"
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
echo "## [2/2] EXPLAIN ANALYZE — 추정 rows vs 실제 rows, 시간 ${REPS}회"
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

if [[ -s "$FAILLOG" ]]; then
  echo "🔴🔴 실행 실패한 쿼리가 있다:"; sort "$FAILLOG" | uniq -c | sed 's/^/     /'; echo
fi

echo "############ 읽는 법 ############"
echo "[2] '배수' 는 추정rows/실제rows — 1.0 에서 멀수록 옵티마이저 견적이 빗나간 것이다."
echo "    §4-5 는 a 에서 38배(비관), §4-3 은 (b) 에서 낙관 방향으로 빗나간 사례를 이미 남겼다."
echo "    시간은 최소값을 읽을 것. 동거 노이즈는 늘리기만 한다(§4-5 ②-1)."
