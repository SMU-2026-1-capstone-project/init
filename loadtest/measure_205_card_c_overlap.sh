#!/usr/bin/env bash
# #205 카드 C — 동시 세션 수: 순진한 self-join(O(N²)) ↔ 스윕라인 윈도우 함수
#
# 이슈: https://github.com/Shadowfit/init/issues/205
# 카드가 되는 이유는 «알고리즘 선택이 SQL 문장 모양으로 드러나기» 때문이고,
#   이슈 체크박스가 요구하는 것은 **행 수를 늘리며 대조**하는 것이다
#   ("그래야 O(N²) 주장이 근거를 얻는다").
#
# 팔 둘:
#   naive  — self-join. s2.start <= s1.start < s2.end 인 쌍을 세어 시각별 동시 세션 수
#   sweep  — 시작 +1 / 종료 -1 이벤트로 펼친 뒤 SUM() OVER (ORDER BY t)
#
# 지표 둘:
#   · 시간 — 이 박스에서는 잡음이 크다(2물리코어 동거). 배수만 본다
#   · Handler_read_* — **판마다 완전히 동일**하다(실측). 캐시·경합과 무관한 결정적 지표다
#
# ⚠️ **2026-08-20 정정**: 여기 원래 «O(N²) 주장의 본 근거는 핸들러» 라고 적혀 있었다. **틀렸다.**
#   실측에서 핸들러는 배가할 때마다 4.33 → 2.48 → 2.20 → 2.09 배로 **선형에 수렴**하고,
#   2차식으로 자라는 것은 **시간**이다. 계획이 이유를 준다 — naive 는
#   `Inner hash join (no condition)`, 즉 **조인 조건 없는 데카르트 곱**을 만든 뒤 `Filter` 로
#   매 쌍을 검사한다. 테이블 «읽기» 는 O(N)(핸들러가 그것을 센다)이고 **N² 은 필터 평가 횟수**다.
#   → **O(N²) 의 근거는 시간이고, 핸들러는 «읽기는 선형인데도 느리다» 를 보여 기제를 좁히는 쪽이다.**
#
# 🔴 격리: es_c205 라는 별도 테이블에 표본을 넣고 잰다. exercise_sessions 는 안 건드린다.
#
# ⚠️ 한계:
#   · 합성 데이터라 **결과값(동시 세션 수) 자체는 의미 없다.** 이슈가 "방법이 선다까지가
#     이번 범위" 라고 못박은 그대로다. 여기서 재는 것은 **비용의 증가 곡선**이다
#   · 두 팔의 답이 같은지 매 판 대조한다 — 다르면 «빠른 쪽» 이 틀린 것일 수 있다
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a

LEVELS=(${LEVELS:-500 1000 2000 4000})
BLOCKS=${BLOCKS:-3}        # 첫 블록 버림 → 레벨·팔당 유효 2판
OUT=${OUT:-loadtest/results/card-c-overlap-2026-08-20}
SC=$(mktemp -d)
mkdir -p "$OUT"

DB(){ docker exec -i shadowfit-mysql mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit "$@" 2>/dev/null; }

echo "## [0] 표본 테이블 준비 (exercise_sessions 는 안 건드린다)"
DB -e "
DROP TABLE IF EXISTS es_c205;
CREATE TABLE es_c205 (
  id BIGINT PRIMARY KEY,
  start_time DATETIME NOT NULL,
  end_time   DATETIME NOT NULL,
  KEY idx_start (start_time),
  KEY idx_end (end_time)
) ENGINE=InnoDB;"
total=$(DB -e "SELECT COUNT(*) FROM exercise_sessions WHERE end_time IS NOT NULL AND end_time > start_time;")
echo "  모집단(끝난 세션) = $total 행"

fill(){ # $1=N — 최신 쪽에서 N 행. 무작위 표본이 아니라 «시간축에서 연속» 이어야 한다
  # 🔴 ORDER BY start_time ASC 로 뽑으면 안 된다 — 오래된 구간은 525분 간격 × 15분 지속이라
  #   겹침이 0 이고(#204 §5 실측), 그러면 self-join 이 O(N²) 로 안 자라 대조가 통째로 무너진다.
  #   최신 구간은 하루 ~1,006세션 × ~30분이라 겹침이 두껍다.
  DB -e "TRUNCATE TABLE es_c205;
    INSERT INTO es_c205 (id,start_time,end_time)
    SELECT id,start_time,end_time FROM (
      SELECT id,start_time,end_time FROM exercise_sessions
       WHERE end_time IS NOT NULL AND end_time > start_time
       ORDER BY start_time DESC LIMIT $1) t;
    ANALYZE TABLE es_c205;" > /dev/null
}

# 팔 하나를 재고 "답 시간ms 핸들러합" 을 찍는다
run_one(){ # $1=arm → "answer ms handlers"
  local arm="$1" sql
  case "$arm" in
    naive) sql="SELECT MAX(c) FROM (
                  SELECT s1.id, COUNT(*) AS c
                    FROM es_c205 s1 JOIN es_c205 s2
                      ON s2.start_time <= s1.start_time AND s2.end_time > s1.start_time
                   GROUP BY s1.id) x" ;;
    sweep) sql="SELECT MAX(run) FROM (
                  SELECT SUM(d) OVER (ORDER BY t, d ROWS UNBOUNDED PRECEDING) AS run
                    FROM ( SELECT start_time AS t,  1 AS d FROM es_c205
                           UNION ALL
                           SELECT end_time   AS t, -1 AS d FROM es_c205 ) ev) y" ;;
  esac
  # 핸들러 카운터는 세션 단위다 — 같은 커넥션 안에서 리셋→쿼리→읽기를 해야 한다
  local out
  out=$(docker exec -i shadowfit-mysql mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit 2>/dev/null <<SQL
FLUSH STATUS;
SET @t0 = NOW(6);
-- 마커는 «별도 컬럼» 으로 낸다. CONCAT 안에 탭을 넣으면 mysql 배치 출력이
-- 그것을 \t 로 이스케이프해 파싱이 통째로 깨진다 (첫 스모크가 NA 로 나온 원인)
SELECT 'ANS', COALESCE(($sql),0);
SELECT 'MS', ROUND(TIMESTAMPDIFF(MICROSECOND, @t0, NOW(6))/1000, 1);
SELECT 'H', SUM(VARIABLE_VALUE)
  FROM performance_schema.session_status
 WHERE VARIABLE_NAME IN ('Handler_read_next','Handler_read_key','Handler_read_rnd_next','Handler_read_first');
SQL
)
  local ans ms h
  ans=$(echo "$out" | awk -F'\t' '$1=="ANS"{print $2}')
  ms=$(echo  "$out" | awk -F'\t' '$1=="MS"{print $2}')
  h=$(echo   "$out" | awk -F'\t' '$1=="H"{print $2}')
  # 🔴 셋 중 하나라도 못 읽으면 즉시 죽는다 — NA 가 표에 들어가면 «쟀는데 0» 처럼 보인다
  if [ -z "${ans:-}" ] || [ -z "${ms:-}" ] || [ -z "${h:-}" ]; then
    echo "🔴 팔 $arm 의 지표를 못 읽었다 (ans='${ans:-}' ms='${ms:-}' h='${h:-}') — 중단" >&2
    echo "$out" | sed 's/^/    /' >&2
    exit 1
  fi
  echo "$ans $ms $h"
}

echo
echo "## [1] 레벨 ${LEVELS[*]} × 팔 둘 × ${BLOCKS}블록 (첫 블록 버림) — 라틴 방격"
echo "n arm block answer ms handlers" > "$SC/raw.txt"
ARMS=(naive sweep)
for ((b=0;b<BLOCKS;b++)); do
  for lv in "${LEVELS[@]}"; do
    fill "$lv"
    # 팔 순서를 블록마다 뒤집는다 — «먼저 돈 팔이 캐시를 데운다» 를 상쇄
    if (( b % 2 == 0 )); then order="naive sweep"; else order="sweep naive"; fi
    declare -A ansmap=()
    for a in $order; do
      read -r ans ms h <<<"$(run_one "$a")"
      ansmap[$a]=$ans
      echo "$lv $a $b $ans $ms $h" >> "$SC/raw.txt"
      echo "  n=$lv $a block=$b → 답 $ans · ${ms}ms · 핸들러 $h$([ "$b" = 0 ] && echo '   ← 버림')"
    done
    # 🔴 두 팔의 답이 같아야 한다. 다르면 빠른 쪽이 틀린 것일 수 있다
    if [ "${ansmap[naive]}" != "${ansmap[sweep]}" ]; then
      echo "  🔴 답 불일치: naive=${ansmap[naive]} sweep=${ansmap[sweep]} (n=$lv, block=$b)" | tee -a "$SC/mismatch.txt"
    fi
    # 🔴 겹침이 없으면(최대 동시 = 1) self-join 이 O(N²) 로 안 자란다 — 재는 것이 없다
    if [ "${ansmap[sweep]}" -le 1 ] 2>/dev/null; then
      echo "🔴 n=$lv 에서 최대 동시 세션이 ${ansmap[sweep]} 이다 — 겹침이 없어 대조가 성립하지 않는다. 중단" >&2
      exit 1
    fi
  done
done

echo
echo "## [2] 집계"
{
echo "# #205 카드 C — 동시 세션 수 · 생성 표 (로컬, 2026-08-20)"
echo
echo "표본 테이블 \`es_c205\` · 레벨 ${LEVELS[*]} · **${BLOCKS}블록**(첫 블록 버림) · 팔 순서 블록마다 교대."
echo
echo "| N | 팔 | 블록 | 답 | ms | 핸들러 |"
echo "|---|---|---|---|---|---|"
awk 'NR>1 {printf "| %s | %s | %s | %s | %s | %s |%s\n", $1,$2,$3,$4,$5,$6, ($3==0?" ← 버림":"")}' "$SC/raw.txt"
echo
echo "**레벨·팔별 중앙값(첫 블록 제외)**"
echo
echo "| N | 팔 | ms 중앙값 | 핸들러 중앙값 |"
echo "|---|---|---|---|"
for lv in "${LEVELS[@]}"; do
  for a in naive sweep; do
    awk -v n="$lv" -v x="$a" 'NR>1 && $1==n && $2==x && $3>0 {print $5, $6}' "$SC/raw.txt" | sort -n | awk -v n="$lv" -v x="$a" '
      {m[NR]=$1; h[NR]=$2} END{
        if (NR==0) { printf "| %s | %s | — (유효 판 0) | — |\n", n, x; exit }
        a1=(NR%2)? m[(NR+1)/2] : (m[NR/2]+m[NR/2+1])/2;
        b1=(NR%2)? h[(NR+1)/2] : (h[NR/2]+h[NR/2+1])/2;
        printf "| %s | %s | %.1f | %.0f |\n", n, x, a1, b1 }'
  done
done
if [ -s "$SC/mismatch.txt" ]; then echo; echo "🔴 **답 불일치가 있었다**"; sed 's/^/- /' "$SC/mismatch.txt"; fi
} | tee "$OUT/summary.md"

DB -e "DROP TABLE IF EXISTS es_c205;"
cp "$SC/raw.txt" "$OUT/raw.tsv"
echo
echo "→ $OUT/summary.md (판정은 손으로 쓴 $OUT/README.md 에)"
