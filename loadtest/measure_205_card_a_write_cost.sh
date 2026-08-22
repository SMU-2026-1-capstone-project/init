#!/usr/bin/env bash
# #205 카드 A 의 남은 반쪽 — 커버링 인덱스의 «쓰기 대가»
#
# 이슈: https://github.com/Shadowfit/init/issues/205 (카드 A)
# 읽기 이득은 이제 둘 다 있다 — 논리 페이지 4.85배(#204 §2-ㄴ) · 곡선 쿼리 6.7배(카드 B).
#   그런데 이 프로젝트의 핫패스는 «쓰기» 다. #204 §4-3 이 "채택 판단은 여전히 열려 있다" 로
#   남긴 자리가 여기고, 이슈 체크박스도 "커버링 인덱스 추가 전후 쓰기 처리량 대조" 다.
#
# 팔 = idx_report_cover 존재 하나. 그 외는 전부 동일.
#
# 🔴 R8 이 남긴 함정을 피한다. uk_pose_event 판은 Innodb_buffer_pool_reads 가 «양쪽 다 0» 이라
#   메모리 안의 비용만 쟀다. 그래서 여기서는:
#     · 실 pose_data(데이터 4,995MB)에 대고 잰다 — 버퍼풀 2,048MB 를 2.4배 넘는다
#     · buffer_pool_reads 를 **찍어서 0 이면 그 사실을 표에 남긴다**(숨기지 않는다)
#
# 🔶 기제 가설 하나를 미리 박는다: idx_report_cover 는 **non-unique** 다.
#   uk_pose_event(unique)와 달리 **change buffer 를 쓸 수 있다**(innodb_change_buffering=all).
#   그러면 세컨더리 인덱스 삽입이 지연 병합돼 **대가가 예상보다 쌀 수 있다.**
#   판정: Innodb_ibuf_merges / ibuf 삽입이 with_cover 팔에서만 늘면 그 경로가 실제로 쓰인 것이다.
#
# ⚠️ 한계:
#   · 인덱스 자체는 작을 것이다(5컬럼 × 150만 행). **버퍼풀에 들어가면 대가는 «메모리 안» 이고**,
#     인덱스가 풀을 넘는 규모는 이 판이 못 본다 — R8 과 같은 경계다. 인덱스 크기를 찍어서 남긴다
#   · 단일 라이터다. 동시성 하 대가는 다른 판이다
#   · 로컬 2물리코어 동거
#
# 🔴 이 rig 은 **실 pose_data 에 행을 넣었다 지운다**(session_id 99xxxx 대역).
#   판마다 지우고, 끝나면 대역이 비었는지 단언한다.
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a

STMTS=${STMTS:-800}        # 판당 INSERT 문 수
ROWS=${ROWS:-25}           # 문당 행 수 (앱 배치 모사)
# 🔴 문당 커밋(=1)이 앱과 같은 조건이다. 그런데 이 박스는 sync_binlog=1·flush=1 이라
#   문마다 fsync 가 걸리고(스모크에서 문당 ~125ms), 그러면 **인덱스 대가가 fsync 밑에 숨는다**
#   — 이 repo 가 전에 「3.47배가 1.03배로 무너진」 그 자리다. 손잡이로 빼둔다:
#   TXN_STMTS>1 이면 그만큼 묶어 커밋해 fsync 비중을 줄이고 대가를 드러낸다.
TXN_STMTS=${TXN_STMTS:-1}
ORDER=${ORDER:-"A B B A B A A B"}   # 첫 판 버림 · 위치 균형
OUT=${OUT:-loadtest/results/card-a-write-cost-2026-08-20}
SC=$(mktemp -d)
mkdir -p "$OUT"

DB(){ docker exec -i shadowfit-mysql mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit "$@" 2>/dev/null; }
DBT(){ docker exec -i shadowfit-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit "$@" 2>/dev/null; }

# 🔴 2026-08-22: 이 rig 은 8판 내내 «ibuf_merges 0» 을 찍었는데 **아무것도 안 재고 있었다.**
#   MySQL 8.0 커뮤니티에 `Innodb_ibuf_merges` 라는 상태 변수가 없어서 빈 값이 왔고,
#   bash 산술이 그것을 0 으로 쳤다(0-0=0). #271 계열의 «조용한 0» 이다.
#   진짜 출처는 information_schema.INNODB_METRICS 이고, 없으면 여기서 죽는다.
metric(){ DB -e "SELECT count FROM information_schema.INNODB_METRICS WHERE name='$1';" | tr -d '[:space:]'; }
for _m in ibuf_merges ibuf_merges_insert; do
  _v=$(metric "$_m")
  case "$_v" in ''|*[!0-9]*) echo "🔴 계기 없음: INNODB_METRICS.$_m 를 못 읽었다 (값=[$_v]) — 중단"; exit 1;; esac
  _st=$(DB -e "SELECT status FROM information_schema.INNODB_METRICS WHERE name='$_m';" | tr -d '[:space:]')
  [ "$_st" = "enabled" ] || { echo "🔴 $_m 가 disabled — 중단"; exit 1; }
done
echo "  [계기 확인] INNODB_METRICS.ibuf_* 읽힘 · enabled"

# 🔴 2026-08-20 2차 시도가 중단됐을 때 드러난 구멍: 스크립트를 죽여도 docker exec 로 띄운
#   writer 가 컨테이너 «안에서» 계속 돌아 행을 넣는다. 바깥에서 지워봤자 뒤이어 다시 채워졌다.
#   그래서 종료 시 DB 안의 writer 스레드를 먼저 죽이고, 그다음에 정리한다.
cleanup_on_exit(){
  local ids
  ids=$(DB -e "SELECT id FROM information_schema.processlist
         WHERE info LIKE 'INSERT INTO pose_data%' AND command <> 'Sleep';" 2>/dev/null)
  for id in $ids; do DB -e "KILL $id;" >/dev/null 2>&1; done
  [ -n "$ids" ] && echo "  [정리] 살아 있던 writer 스레드 종료: $(echo $ids | tr '
' ' ')" >&2
  DB -e "DELETE FROM pose_data WHERE session_id BETWEEN 990000 AND 999999;" >/dev/null 2>&1
}
trap cleanup_on_exit EXIT INT TERM

COVER_COLS="(session_id, rep_number, timestamp_sec, sync_rate, smoothed_knee_angle)"
have_cover(){ DB -e "SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema='shadowfit' AND table_name='pose_data' AND index_name='idx_report_cover';" | tr -d '[:space:]'; }

set_cover(){ # $1=1|0
  local want="$1" have; have=$(have_cover)
  if [ "$want" = "1" ] && [ "$have" = "0" ]; then
    echo "  + idx_report_cover 생성 중 (150만 행 — 1~2분)" >&2
    DB -e "ALTER TABLE pose_data ADD INDEX idx_report_cover $COVER_COLS;"
  elif [ "$want" = "0" ] && [ "$have" != "0" ]; then
    echo "  - idx_report_cover 제거" >&2; DB -e "ALTER TABLE pose_data DROP INDEX idx_report_cover;"
  fi
  have=$(have_cover)
  if { [ "$want" = "1" ] && [ "$have" != "5" ]; } || { [ "$want" = "0" ] && [ "$have" != "0" ]; }; then
    echo "🔴 인덱스 상태 불일치: want=$want 실제컬럼수=$have — 중단" >&2; exit 1
  fi
  if [ "$want" = "1" ] && [ ! -s "$SC/idxsize.txt" ]; then
    # 🔴 파티션 테이블이라 innodb_index_stats 의 table_name 이 pose_data#p#p2026_01 로 쪼개진다.
    #   'pose_data' 로 등치 비교하면 **빈 결과**가 나온다(첫 스모크가 그랬다) — LIKE + SUM 이어야 한다
    DBT -e "SELECT index_name, ROUND(SUM(stat_value)*@@innodb_page_size/1024/1024,1) AS size_mb
       FROM mysql.innodb_index_stats
      WHERE database_name='shadowfit' AND table_name LIKE 'pose_data%' AND stat_name='size'
      GROUP BY index_name ORDER BY size_mb DESC;" > "$SC/idxsize.txt"
    echo "  [인덱스 크기]" >&2; sed 's/^/    /' "$SC/idxsize.txt" >&2
  fi
}

echo "## [0] 조건 확인"
DBT -e "SELECT @@innodb_buffer_pool_size/1024/1024 AS pool_mb, @@innodb_change_buffering AS change_buffering,
        @@innodb_flush_log_at_trx_commit AS flush_commit, @@sync_binlog AS sync_binlog;" | tee "$SC/cond.txt"
DBT -e "SELECT ROUND(data_length/1024/1024) AS data_mb, ROUND(index_length/1024/1024) AS index_mb
   FROM information_schema.tables WHERE table_schema='shadowfit' AND table_name='pose_data';" | tee -a "$SC/cond.txt"
echo "  (버퍼풀을 넘는가가 이 판의 전제다 — R8 은 그러지 못해 «메모리 안» 만 쟀다)"

# 커버링 인덱스 크기는 «처음 만들어질 때» set_cover 안에서 찍는다 — §0-b 를 없앴다.
# 스모크마다 1~2분짜리 인덱스 빌드를 강제하고 있었다.
: > "$SC/idxsize.txt"

# 판 하나: 문 STMTS 개를 한 커넥션으로 밀어넣고 시간·InnoDB 카운터 델타를 잰다
run_round(){ # $1=arm(A|B) $2=round $3=session_id
  local arm="$1" rd="$2" sid="$3" want=0
  [ "$arm" = "A" ] && want=1
  set_cover "$want"
  # 페이로드: 행마다 timestamp_sec 이 달라 uk_pose_event 에 안 걸린다
  : > "$SC/w.sql"
  local i r vals
  [ "$TXN_STMTS" -gt 1 ] && echo "START TRANSACTION;" >> "$SC/w.sql"
  for ((i=0;i<STMTS;i++)); do
    vals=""
    for ((r=0;r<ROWS;r++)); do
      [ -n "$vals" ] && vals+=","
      vals+="($sid,1,$((i*ROWS+r)).000,'{\"k\":$r}',45.0,0.0,'','2026-05-28 10:00:00')"
    done
    echo "INSERT INTO pose_data (session_id,rep_number,timestamp_sec,joint_coordinates,sync_rate,smoothed_knee_angle,feedback_message,created_at) VALUES $vals;" >> "$SC/w.sql"
    if [ "$TXN_STMTS" -gt 1 ] && [ $(( (i+1) % TXN_STMTS )) -eq 0 ]; then echo "COMMIT; START TRANSACTION;" >> "$SC/w.sql"; fi
  done
  [ "$TXN_STMTS" -gt 1 ] && echo "COMMIT;" >> "$SC/w.sql"
  local before after
  before=$(DB -e "SELECT CONCAT_WS(' ',
      VARIABLE_VALUE) FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_reads';")
  local pw0 im0 wr0 lw0
  pw0=$(DB -e "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_pages_written';")
  im0=$(metric ibuf_merges)
  # 🔴 2026-08-21: 이 박스에서 «시간» 은 못 쓴다 — 동일 조건 4판이 14.8·33.0·43.3·25.6초로
  #   2.9배 벌어졌다(드리프트 아니라 잡음). 카드 B·C 가 보여줬듯 카운터는 결정적이다.
  #   buffer_pool_write_requests = 페이지 «논리 수정» 횟수라 인덱스가 하나 늘면 그만큼 늘어야 하고,
  #   플러시 타이밍·CPU 경합과 무관하다. log_write_requests 도 같은 성격이다.
  wr0=$(DB -e "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_write_requests';")
  lw0=$(DB -e "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_log_write_requests';")
  local t0 t1
  t0=$(date +%s%3N)
  docker exec -i shadowfit-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit < "$SC/w.sql" > /dev/null 2>"$SC/err"
  t1=$(date +%s%3N)
  after=$(DB -e "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_reads';")
  local pw1 im1 wr1 lw1
  pw1=$(DB -e "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_pages_written';")
  im1=$(metric ibuf_merges)
  wr1=$(DB -e "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_write_requests';")
  lw1=$(DB -e "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_log_write_requests';")
  local errs; errs=$(grep -c 'ERROR' "$SC/err" 2>/dev/null); errs=${errs:-0}
  local ms=$((t1-t0)) rows=$((STMTS*ROWS))
  # 🔴 2026-08-20 1차 시도: 여기서 판마다 DELETE 했다. 그러자 시간이 판 순서를 따라 단조 증가해
  #   (19초 → 139초) **드리프트가 팔 효과를 덮었다** — 중앙값은 A 1.9배로 나왔지만 A 가 평균적으로
  #   뒤에 섰고, 인접 판끼리는 오히려 A 가 빨랐다. 원인은 반복 INSERT+DELETE 의 purge·파편화 누적.
  #   그래서 판마다 지우지 않는다. 판마다 session_id 가 다르므로 섞이지 않고,
  #   🔴 2026-08-22 정정: 이 줄이 «판당 7,500(총 6만) = 4%» 라고 적고 있었는데 **기본값과 안 맞는다**
  #   (STMTS=800·ROWS=25 면 판당 20,000 · 8판 총 16만 = 123만 대비 **13%**). 그 수로 정당화를 다시 쓴다:
  #   표가 판을 거치며 13% 커지므로 «순서 효과» 가 생길 수 있고, 그래서 ORDER 의 위치 균형이 필수다.
  #   판정 지표(bp_write_req)는 행당 논리 페이지 수정 수라 13% 증가로 B-tree 깊이가 바뀌지 않는 한 둔감하다.
  #   정리는 §3 에서 한 번에 한다.
  local line="$arm $rd $ms $rows $((after-before)) $((pw1-pw0)) $((im1-im0)) $errs $((wr1-wr0)) $((lw1-lw0))"
  # 🔴 이 함수의 stdout 은 «데이터 한 줄» 뿐이어야 한다. 안내 문구가 섞이면 필드가 밀려
  #   표가 멀쩡한 채로 엉뚱한 값이 들어간다 — 2026-08-20 스모크에서 실제로 그랬다
  #   (set_cover 의 echo 가 여기 잡혀 ms 자리에 pages_written 이 들어갔다). 필드 수로 막는다.
  if [ "$(echo "$line" | wc -w)" != "10" ]; then
    echo "🔴 run_round 출력 필드가 10개가 아니다: [$line] — 중단" >&2; exit 1
  fi
  echo "$line"
}

echo
echo "## [1] 판 순서: $ORDER (첫 판 버림) — 문 $STMTS · 행 $ROWS · 문당커밋 TXN_STMTS=$TXN_STMTS"
echo "arm round ms rows bp_reads pages_written ibuf_merges errors bp_write_req log_write_req" > "$SC/raw.txt"
rd=0
for a in $ORDER; do
  line=$(run_round "$a" "$rd" $((990000+rd)))
  echo "$line" >> "$SC/raw.txt"
  set -- $line
  echo "  [$1] 판 $2 → ${3}ms · ${4}행 · 🔑 bp_write_req ${9} · log_write_req ${10} · bp_reads $5 · pages_written $6 · ibuf $7 · err $8$([ "$rd" = 0 ] && echo '   ← 버림')"
  rd=$((rd+1))
done

echo
echo "## [2] 집계"
{
echo "# #205 카드 A — 커버링 인덱스의 쓰기 대가 · 생성 표 (로컬, 2026-08-20)"
echo
echo "실 \`pose_data\` 에 대고 잰다 · 문 **$STMTS** · 문당 행 **$ROWS** · 판 순서 \`$ORDER\`(첫 판 버림) · 트랜잭션당 문 **$TXN_STMTS**."
echo "**A = 커버링 인덱스 있음 · B = 없음**"
echo
echo "## 조건"; echo; echo '```'; cat "$SC/cond.txt"; echo '```'
echo
echo "## 인덱스 크기"; echo; echo '```'; cat "$SC/idxsize.txt"; echo '```'
echo
echo "| 팔 | 판 | 🔑 bp_write_req | 🔑 log_write_req | ms | 행 | rows/s | bp_reads | pages_written | ibuf_merges | err |"
echo "|---|---|---|---|---|---|---|---|---|---|---|"
awk 'NR>1 {rs=($3>0)? sprintf("%.0f", $4/($3/1000)) : "ms=0-실패"; printf "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |%s\n", $1,$2,$9,$10,$3,$4,rs,$5,$6,$7,$8, ($2==0?" ← 버림":"")}' "$SC/raw.txt"
echo
echo "⚠️ \`ibuf_merges\` 가 0 이어도 «change buffer 를 안 쓴다» 로 읽으면 안 된다 — 이 페이로드는 한 세션에"
echo "순차 삽입이라 인덱스 오른쪽 끝 한 자리만 치고, 그 페이지가 메모리에 머물러 조건 자체가 안 선다."
echo "삽입 지점을 흩뿌린 판은 따로 있다: \`measure_205_card_a_ibuf.sh\`"; echo
echo "**판 순서별** — 🔴 이 박스에서 **ms 는 못 쓴다**: 2026-08-21 동일 조건 4판이 14.8·33.0·43.3·25.6초로 2.9배 벌어졌다(드리프트 아니라 잡음). 팔 비교는 🔑 카운터로 하고 ms 는 참고다."; echo
echo "| 판 | " $(awk 'NR>1{printf "%s | ", $2}' "$SC/raw.txt")
echo "|---|" $(awk 'NR>1{printf "---|"}' "$SC/raw.txt")
echo "| 팔 | " $(awk 'NR>1{printf "%s | ", $1}' "$SC/raw.txt")
echo "| 🔑 bp_write_req | " $(awk 'NR>1{printf "%s | ", $9}' "$SC/raw.txt")
echo "| 🔑 log_write_req | " $(awk 'NR>1{printf "%s | ", $10}' "$SC/raw.txt")
echo "| ms (참고) | " $(awk 'NR>1{printf "%s | ", $3}' "$SC/raw.txt")
echo
echo "**팔별 중앙값(첫 판 제외)** — 🔑 판정 지표는 bp_write_req 다(ms 아님)"; echo
echo "| 팔 | 🔑 bp_write_req | 🔑 log_write_req | ms(참고) | rows/s | bp_reads | ibuf_merges |"
echo "|---|---|---|---|---|---|---|"
# 🔴 초판은 ms 로 정렬한 뒤 «그 행의» 다른 열 값을 중앙값이라 적었다 — 열마다 순위가 다르므로
#   그건 중앙값이 아니다. 열별로 따로 정렬해서 낸다.
med(){ awk -v x="$1" -v c="$2" 'NR>1 && $1==x && $2>0 {print $c}' "$SC/raw.txt" | sort -n \
     | awk '{v[NR]=$1} END{ if(NR==0){printf "-"; exit} printf "%.0f", (NR%2)? v[(NR+1)/2] : (v[NR/2]+v[NR/2+1])/2 }'; }
for a in A B; do
  n=$(awk -v x="$a" 'NR>1 && $1==x && $2>0' "$SC/raw.txt" | wc -l | tr -d '[:space:]')
  if [ "$n" = "0" ]; then echo "| $a | — (유효 판 0) | — | — | — | — | — |"; continue; fi
  wr=$(med "$a" 9); lw=$(med "$a" 10); msd=$(med "$a" 3); bpd=$(med "$a" 5); ibd=$(med "$a" 7)
  rws=$(awk -v x="$a" 'NR>1 && $1==x && $2>0 {print $4; exit}' "$SC/raw.txt")
  rs=$(awk -v m="$msd" -v r="$rws" 'BEGIN{ if(m>0) printf "%.0f", r/(m/1000); else printf "-" }')
  echo "| $a | $wr | $lw | $msd | $rs | $bpd | $ibd |"
  eval "WR_$a=\$wr; LW_$a=\$lw; MS_$a=\$msd"
done
echo
if [ "${WR_B:-0}" != "0" ] && [ -n "${WR_A:-}" ]; then
  echo "**팔 대비 (A÷B)** — 커버링 인덱스가 무는 값. 1.00 이면 «대가 없음»"; echo
  awk -v a="${WR_A}" -v b="${WR_B}" -v la="${LW_A:-0}" -v lb="${LW_B:-0}" -v ma="${MS_A:-0}" -v mb="${MS_B:-0}" 'BEGIN{
    printf "| 지표 | A(인덱스 있음) | B(없음) | A÷B |\n|---|---|---|---|\n";
    printf "| 🔑 bp_write_req | %d | %d | **%.3f** |\n", a, b, a/b;
    if (lb>0) printf "| 🔑 log_write_req | %d | %d | %.3f |\n", la, lb, la/lb;
    if (mb>0) printf "| ms ⚠️ 잡음 2.9배 — 인용 금지 | %d | %d | %.2f |\n", ma, mb, ma/mb; }'
fi
} | tee "$OUT/summary.md"

echo
echo "## [3] 뒷정리 — 마이그레이션이 정의하는 상태(인덱스 없음)로 되돌린다"
set_cover 0
DB -e "DELETE FROM pose_data WHERE session_id BETWEEN 990000 AND 999999;"
left=$(DB -e "SELECT COUNT(*) FROM pose_data WHERE session_id BETWEEN 990000 AND 999999;")
echo "  99xxxx 대역 잔여 행 = $left (0 이어야 정상)"
[ "$left" = "0" ] || { echo "🔴 잔여 행이 있다 — 손으로 지울 것"; exit 1; }
cp "$SC/raw.txt" "$OUT/raw.tsv"
echo
echo "→ $OUT/summary.md (판정은 손으로 쓴 $OUT/README.md 에)"
