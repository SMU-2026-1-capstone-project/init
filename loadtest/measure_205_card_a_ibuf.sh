#!/usr/bin/env bash
# #205 카드 A 후속 — change buffer 는 이 인덱스에서 «켜지기는 하나»
#
# ─────────────────────────────────────────────────────────────────────────
# 왜 이 판이 따로 필요한가
#
# 본 판(measure_205_card_a_write_cost.sh)이 «ibuf_merges 0» 을 8판 내내 찍었는데
# 그건 사실이 아니라 **계기 고장**이었다 — MySQL 8.0 커뮤니티에 `Innodb_ibuf_merges`
# 라는 상태 변수는 없다. 빈 값을 bash 산술이 0 으로 쳤다(#271 계열의 조용한 0).
#   진짜 출처: information_schema.INNODB_METRICS (ibuf_merges · ibuf_merges_insert)
#   교차 확인: SHOW ENGINE INNODB STATUS 의 "merged operations: insert N"
#
# 그리고 계기를 고쳐도 본 판의 페이로드로는 **답이 안 나온다.** change buffer 는
# «대상 페이지가 버퍼풀에 없을 때» 만 쓰이는데, 본 판은 판마다 session_id 하나에
# timestamp_sec 을 올려가며 넣는다 — idx_report_cover 의 선두 컬럼이 session_id 라
# 삽입 지점이 **인덱스 오른쪽 끝 한 자리**고, 그 페이지는 계속 메모리에 머문다.
# 즉 «안 탔다» 는 결론을 페이로드가 만들었을 수 있다.
#
# 그래서 팔을 하나 더 만든다: **삽입 지점을 흩뿌린다.**
#   팔 H(집중) = 본 판과 같은 모양. session_id 하나
#   팔 S(분산) = session_id 를 넓은 대역에 흩뿌린다 → 삽입 지점이 인덱스 전역으로 퍼진다
# 실사용은 **S 쪽에 가깝다**(동시에 운동 중인 사람이 여럿).
#
# 판정:
#   S 에서 ibuf_merges_insert 가 늘면 → 경로가 «켜진다». 본 판의 «안 탔다» 는
#     **이 페이로드 한정**이 되고, 실사용 대가는 본 판이 잰 것과 다를 수 있다
#   S 에서도 0 이면 → 이 인덱스·이 규모에서는 조건 자체가 안 선다
#
# ⚠️ 한계: 인덱스가 81.5MB 라 버퍼풀 2GB 에 통째로 들어간다. 표(5GB)가 풀을 밀어내
#   인덱스 페이지가 «쫓겨나야» 조건이 서므로, 이 판은 그 밀어내기에 기댄다.
# ⚠️ 이 판은 팔 사이 «대가» 를 비교하지 않는다 — 페이로드가 다르므로 비교 불가다.
#   묻는 것은 오직 «경로가 켜지는가» 하나다.
# ─────────────────────────────────────────────────────────────────────────
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a

STMTS=${STMTS:-320}        # 팔당 INSERT 문 수 (× 25행 = 8,000행)
ROWS=${ROWS:-25}
ORDER=${ORDER:-"H S S H S H H S"}
SPREAD=${SPREAD:-8000}     # 팔 S 가 흩뿌릴 session_id 대역 폭
OUT=${OUT:-loadtest/results/card-a-ibuf-2026-08-22}
SC=$(mktemp -d); mkdir -p "$OUT"

DB(){ docker exec -i shadowfit-mysql mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit "$@" 2>/dev/null; }
DBT(){ docker exec -i shadowfit-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit "$@" 2>/dev/null; }
metric(){ DB -e "SELECT count FROM information_schema.INNODB_METRICS WHERE name='$1';" | tr -d '[:space:]'; }

echo "## [0] 계기 확인 — 이 판의 존재 이유가 «없는 카운터를 읽고 있었다» 이므로 여기부터 단언한다"
for m in ibuf_merges ibuf_merges_insert ibuf_size; do
  v=$(metric "$m")
  case "$v" in ''|*[!0-9]*) echo "🔴 계기 없음: INNODB_METRICS.$m 를 못 읽었다 (값=[$v]) — 중단"; exit 1;; esac
  st=$(DB -e "SELECT status FROM information_schema.INNODB_METRICS WHERE name='$m';" | tr -d '[:space:]')
  [ "$st" = "enabled" ] || { echo "🔴 $m 가 disabled — SET GLOBAL innodb_monitor_enable 필요. 중단"; exit 1; }
  echo "  $m = $v ($st)"
done

COVER_COLS="(session_id, rep_number, timestamp_sec, sync_rate, smoothed_knee_angle)"
have_cover(){ DB -e "SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema='shadowfit' AND table_name='pose_data' AND index_name='idx_report_cover';" | tr -d '[:space:]'; }

cleanup_on_exit(){
  local ids; ids=$(DB -e "SELECT id FROM information_schema.processlist
       WHERE info LIKE 'INSERT INTO pose_data%' AND command <> 'Sleep';" 2>/dev/null)
  for id in $ids; do DB -e "KILL $id;" >/dev/null 2>&1; done
  DB -e "DELETE FROM pose_data WHERE session_id BETWEEN 900000 AND 989999;" >/dev/null 2>&1
  DB -e "DELETE FROM pose_data WHERE timestamp_sec >= 990000;" >/dev/null 2>&1
  if [ "$(have_cover)" != "0" ]; then DB -e "ALTER TABLE pose_data DROP INDEX idx_report_cover;" >/dev/null 2>&1; fi
  return 0
}
trap cleanup_on_exit EXIT INT TERM

echo
echo "## [1] 무대 — 인덱스를 세운다 (양 팔 모두 «인덱스 있음». 팔은 삽입 지점 모양 하나다)"
if [ "$(have_cover)" = "0" ]; then
  echo "  + idx_report_cover 생성 중 (1~2분)"
  DB -e "ALTER TABLE pose_data ADD INDEX idx_report_cover $COVER_COLS;"
fi
[ "$(have_cover)" = "5" ] || { echo "🔴 인덱스가 5컬럼이 아니다 — 중단"; exit 1; }
DBT -e "SELECT @@innodb_buffer_pool_size/1024/1024 pool_mb, @@innodb_change_buffering cb;" | tee "$SC/cond.txt"
DBT -e "SELECT ROUND(SUM(stat_value)*@@innodb_page_size/1024/1024,1) cover_mb
          FROM mysql.innodb_index_stats WHERE database_name='shadowfit'
           AND table_name LIKE 'pose_data%' AND stat_name='size' AND index_name='idx_report_cover';" | tee -a "$SC/cond.txt"

# 🔴 2026-08-22 2차: 팔 S 로도 조건이 안 섰다. 흩뿌린 session_id 가 **전부 새 값**이라
#   인덱스 오른쪽 끝에 «새로 만들어지는» 페이지에 들어갔고, 새 페이지는 정의상 메모리에 있다.
#   change buffer 는 «디스크에 있고 메모리에 없는» 페이지에만 쓰인다.
#   그래서 팔 E: **이미 표에 있는 session_id** 들에 흩뿌린다(그 페이지들은 디스크에 있고,
#   5GB 표가 2GB 풀을 밀어내므로 상당수가 메모리 밖이다). timestamp_sec 은 990000+ 대역을
#   써서 uk_pose_event 와 안 부딪히고 뒷정리도 그 대역으로 정확히 한다.
EXIST_IDS=""
load_exist_ids(){
  [ -n "$EXIST_IDS" ] && return 0
  EXIST_IDS=$(DB -e "SELECT DISTINCT session_id FROM pose_data WHERE session_id < 900000 LIMIT 500;" | tr '
' ' ')
  read -ra EXIST_ARR <<< "$EXIST_IDS"
  local n=${#EXIST_ARR[@]}
  [ "$n" -ge 50 ] || { echo "🔴 기존 session_id 를 $n 개밖에 못 찾았다 — 팔 E 불가, 중단" >&2; exit 1; }
  echo "  [팔 E] 기존 session_id $n 개에 흩뿌린다" >&2
}

run_arm(){ # $1=arm(H|S|E) $2=round
  local arm="$1" rd="$2" base=$((900000+rd*10000))
  [ "$arm" = "E" ] && load_exist_ids
  : > "$SC/w.sql"
  local i r vals sid
  for ((i=0;i<STMTS;i++)); do
    vals=""
    for ((r=0;r<ROWS;r++)); do
      # 팔 H: 한 세션에 순차(본 판과 같은 모양) · 팔 S: 대역 안에 흩뿌린다(7919 는 소수)
      case "$arm" in
        H) sid=$base ;;
        S) sid=$(( base + ( (i*ROWS+r) * 7919 ) % SPREAD )) ;;
        E) sid=${EXIST_ARR[$(( ( (i*ROWS+r) * 7919 ) % ${#EXIST_ARR[@]} ))]} ;;
      esac
      [ -n "$vals" ] && vals+=","
      # 🔴 2026-08-22: 판마다 대역을 안 나눠서 판 1·2 가 판 0 과 같은 값을 넣다 uk_pose_event 로
      #   통째로 죽었다(err 1 · bp_write_req 12). 팔 E 는 session_id 가 판 간에 같으므로
      #   구분이 timestamp_sec 에만 있다 — 판 번호를 대역에 섞는다.
      local ts=$((i*ROWS+r)); [ "$arm" = "E" ] && ts=$((990000 + rd*100000 + ts))
      vals+="($sid,1,${ts}.000,'{\"k\":$r}',45.0,0.0,'','2026-05-28 10:00:00')"
    done
    echo "INSERT INTO pose_data (session_id,rep_number,timestamp_sec,joint_coordinates,sync_rate,smoothed_knee_angle,feedback_message,created_at) VALUES $vals;" >> "$SC/w.sql"
  done
  local m0 mi0 br0 wr0 m1 mi1 sz1 br1 wr1 t0 t1
  m0=$(metric ibuf_merges); mi0=$(metric ibuf_merges_insert)
  br0=$(DB -e "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_reads';")
  wr0=$(DB -e "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_write_requests';")
  t0=$(date +%s%3N)
  docker exec -i shadowfit-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit < "$SC/w.sql" >/dev/null 2>"$SC/err"
  t1=$(date +%s%3N)
  m1=$(metric ibuf_merges); mi1=$(metric ibuf_merges_insert); sz1=$(metric ibuf_size)
  br1=$(DB -e "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_reads';")
  wr1=$(DB -e "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_write_requests';")
  local errs; errs=$(grep -c 'ERROR' "$SC/err" 2>/dev/null); errs=${errs:-0}
  local line="$arm $rd $((t1-t0)) $((STMTS*ROWS)) $((m1-m0)) $((mi1-mi0)) $sz1 $((br1-br0)) $((wr1-wr0)) $errs"
  if [ "$(echo "$line" | wc -w)" != "10" ]; then
    echo "🔴 필드 수가 10이 아니다: [$line] — 중단" >&2; exit 1
  fi
  # 🔴 «보낸 요청이 실제로 행을 만들었는가» — 탑승 목록 §4 가 요구하는 확인을 판마다 한다.
  #   8,000행이면 bp_write_req 가 만 단위여야 한다. 세 자리면 삽입이 죽은 것이다.
  if [ "$((wr1-wr0))" -lt $((STMTS*ROWS/4)) ]; then
    echo "  🔴 판 $rd($arm): 삽입이 죽었다 — bp_write_req $((wr1-wr0)) (행 $((STMTS*ROWS)) 기대치의 1/4 미만) · err $errs" >&2
  fi
  echo "$line"
}

echo
echo "## [2] 판 순서: $ORDER (첫 판 버림) — 팔당 $((STMTS*ROWS))행 · 팔 S 대역폭 $SPREAD"
echo "arm round ms rows ibuf_merges ibuf_ins ibuf_size bp_reads bp_write_req errors" > "$SC/raw.txt"
rd=0
for a in $ORDER; do
  line=$(run_arm "$a" "$rd"); echo "$line" >> "$SC/raw.txt"; set -- $line
  echo "  [$1] 판 $2 → ${3}ms · 🔑 ibuf_merges $5 · ibuf_ins $6 · ibuf_size $7 · bp_reads $8 · bp_write_req $9 · err ${10}$([ "$rd" = 0 ] && echo '   ← 버림')"
  rd=$((rd+1))
done

echo
echo "## [3] 집계"
{
echo "# #205 카드 A 후속 — change buffer 가 켜지는가 (로컬, 2026-08-22)"
echo
echo "양 팔 모두 **커버링 인덱스 있음**. 팔은 **삽입 지점 모양** 하나다 — H=한 세션 집중, S=대역 $SPREAD 에 분산."
echo "팔당 $((STMTS*ROWS))행 · 순서 \`$ORDER\`(첫 판 버림)."
echo "🔴 팔 사이 **비용 비교는 하지 않는다**(페이로드가 다르다). 묻는 것은 «경로가 켜지는가» 하나다."
echo
echo "## 조건"; echo; echo '```'; cat "$SC/cond.txt"; echo '```'
echo
echo "| 팔 | 판 | ms | 행 | 🔑 ibuf_merges | 🔑 ibuf_ins | ibuf_size | bp_reads | bp_write_req | err |"
echo "|---|---|---|---|---|---|---|---|---|---|"
awk 'NR>1 {printf "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |%s\n",$1,$2,$3,$4,$5,$6,$7,$8,$9,$10,($2==0?" ← 버림":"")}' "$SC/raw.txt"
echo
echo "**팔별 합(첫 판 제외)** — merge 는 «몰아서» 나므로 판별은 중앙값이 아니라 **합**으로 본다"
echo
echo "| 팔 | ibuf_merges 합 | ibuf_ins 합 | bp_reads 합 | bp_write_req 중앙값 | 유효 판 | 🔴 버린 판(err) |"
echo "|---|---|---|---|---|---|---|"
for a in H S E; do
  # 🔴 err>0 판은 «넣다 죽은» 판이라 유효 판이 아니다. 세지 않고, 몇 판이 버려졌는지 남긴다.
  bad=$(awk -v x="$a" 'NR>1 && $1==x && $2>0 && $10>0' "$SC/raw.txt" | wc -l | tr -d '[:space:]')
  sums=$(awk -v x="$a" 'NR>1 && $1==x && $2>0 && $10==0 {m+=$5; i+=$6; b+=$8; n++} END{ if(n==0) printf "none"; else printf "%d %d %d %d", m, i, b, n }' "$SC/raw.txt")
  if [ "$sums" = "none" ]; then echo "| $a | 🔴 **못 쟀다 — 유효 판 0** | — | — | — | 0 | $bad |"; continue; fi
  wd=$(awk -v x="$a" 'NR>1 && $1==x && $2>0 && $10==0 {print $9}' "$SC/raw.txt" | sort -n \
       | awk '{v[NR]=$1} END{ printf "%.0f", (NR%2)? v[(NR+1)/2] : (v[NR/2]+v[NR/2+1])/2 }')
  set -- $sums
  echo "| $a | $1 | $2 | $3 | $wd | $4 | $bad |"
done
echo
echo "## 교차 확인 — SHOW ENGINE INNODB STATUS (누적, 서버 기동 이후)"
echo
echo '```'
docker exec -i shadowfit-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW ENGINE INNODB STATUS\G" 2>/dev/null \
  | sed -n '/INSERT BUFFER AND ADAPTIVE/,/^LOG$/p' | head -7
echo '```'
} | tee "$OUT/summary.md"

echo
echo "## [4] 뒷정리"
DB -e "DELETE FROM pose_data WHERE session_id BETWEEN 900000 AND 989999;"
DB -e "DELETE FROM pose_data WHERE timestamp_sec >= 990000;"
left=$(DB -e "SELECT COUNT(*) FROM pose_data WHERE session_id BETWEEN 900000 AND 989999 OR timestamp_sec >= 990000;")
echo "  900000~989999 대역 잔여 = $left (0 이어야 정상)"
if [ "$left" != "0" ]; then echo "🔴 잔여 행 — 손으로 지울 것"; exit 1; fi
cp "$SC/raw.txt" "$OUT/raw.tsv"
echo "→ $OUT/summary.md"
