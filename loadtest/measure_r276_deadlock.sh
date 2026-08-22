#!/usr/bin/env bash
# #276 재현 rig — 멱등 INSERT 가 동시 재전송에서 데드락이 되는 조건과 비율
#
# 이슈: https://github.com/Shadowfit/init/issues/276
# 기제는 이미 잡혀 있다(R276 코멘트) — 다투는 자리는 uk_pose_event 가 아니라
#   index PRIMARY 의 supremum(인덱스 끝) 이고, 둘 다 X 락을 쥔 채 서로의
#   insert intention 을 기다린다. PK 가 AUTO_INCREMENT 라 삽입이 항상 끝에 몰려서다.
#
# 이 rig 이 답하는 것:
#   ㄱ. 「서로 다른 세션의 중복이 동시에」가 정말 조건인가 (단일 세션 대조군)
#   ㄴ. 🔴 **파티션이 갈리면 줄어드는가** — AWS 라운드의 34.8%·50% 는 rig 페이로드가
#       created_at 을 전부 같은 날짜로 줘서 **한 파티션에 몰린** 값이다.
#       ⚠️ **2026-08-20 정정**: 여기 원래 «실사용은 created_at = 세션 시작 시각이라
#       날짜별로 흩어진다» 고 적혀 있었고 그것이 완화 근거로 깔려 있었다. **틀렸다** —
#       흩어지는 것은 «날짜» 이고 파티션은 **월 단위**다. 동시에 운동 중인 사람은
#       정의상 같은 순간에 있으니 같은 달, 따라서 **같은 파티션**이다.
#       즉 **실사용 조건은 diff_partition 이 아니라 same_partition 팔이다.**
#       이 팔이 답하는 것은 «기제가 파티션 지역성이다» 이지 «실사용은 안전하다» 가 아니다
#   ㄷ. 시도당 데드락 확률 p — **재시도 횟수를 여기서 유도한다**(임의값을 넣지 않기 위해)
#   ㄹ. 🔴 **멱등 키가 원인인가** — `uk_pose_event` 를 뺀 대조군(no_uk). 08-20 1라운드가
#       「다투는 자리는 삽입 지점」이라는 기제를 지지했는데, 그 라운드는 **네 팔 전부 키가
#       있는 채로** 돌아 키의 유무를 못 갈랐다. 여기서 가른다.
#       ⚠️ 교락이 하나 딸려 온다 — 키가 없으면 중복이 안 걸려 **행이 실제로 쌓인다**
#       (200행 → 8,000행). 즉 no_uk 는 삽입 일이 **더 많다**. 그래서 판정은 한 방향으로만
#       강하다: no_uk 에서도 데드락이 나면 «키는 필요조건이 아니다» 가 확실하고,
#       0 이 나오면 «키가 관여한다» 쪽이지만 «일이 적어서» 는 아니다(더 많다).
#
# 🔴 격리: 실 테이블을 안 건드린다. `pose_data_r276`(CREATE TABLE ... LIKE = 파티션·PK·
#   유니크키 모두 복제)에 대고 잰다. #204 rig 의 1,590,434행이 그대로 남는다.
#
# ⚠️ 한계:
#   · 로컬 2물리코어 동거 — 절대 비율은 이 박스의 값이다. **팔 간 상대비교만** 신뢰할 것
#   · payload 를 작은 JSON 으로 줄였다(실제는 ~2KB). 데드락은 잠금 자리의 문제라
#     payload 크기와 무관하지만, **비율까지 같다고 말하면 안 된다**
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a

WORKERS=${WORKERS:-8}      # 동시 커넥션
ITER=${ITER:-40}           # 워커당 INSERT 문 수
ROWS=${ROWS:-25}           # INSERT 문당 행 수 (배치 R=25 모사)
ROUNDS=${ROUNDS:-4}        # 팔당 판 수 (첫 판은 버림)
OUT=${OUT:-loadtest/results/r276-deadlock-2026-08-20}
SC=$(mktemp -d)
mkdir -p "$OUT"

DB(){ docker exec -i shadowfit-mysql mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit "$@" 2>/dev/null; }

echo "## [0] 격리 테이블 준비 (pose_data 는 안 건드린다)"
DB -e "DROP TABLE IF EXISTS pose_data_r276; CREATE TABLE pose_data_r276 LIKE pose_data;"
DB -e "SELECT COUNT(*) FROM information_schema.statistics
        WHERE table_schema='shadowfit' AND table_name='pose_data_r276' AND index_name='uk_pose_event';" \
  | xargs -I{} echo "  uk_pose_event 컬럼 수: {} (4 여야 정상)"
DB -e "SELECT COUNT(*) FROM information_schema.partitions
        WHERE table_schema='shadowfit' AND table_name='pose_data_r276';" \
  | xargs -I{} echo "  파티션 수: {}"

# 워커 w 의 INSERT 문 생성.
#   같은 (rep_number, timestamp_sec, created_at) 를 **반복** 넣으므로 2회차부터는 전부 중복이다
#   (= 재전송이 원본과 겹치는 상황 그대로).
gen_sql(){ # $1=session_id  $2=created_at  $3=파일
  # ⚠️ 값은 i 에 의존하지 않는다 — ITER 개 문이 **글자 그대로 같다**(그게 이 rig 의 취지:
  #   2회차부터 전부 중복). 그래서 VALUES 를 한 번만 만들고 같은 줄을 ITER 번 찍는다.
  #   (이전 판은 행마다 awk 를 띄워 판당 8,000 프로세스였다 — Windows 에선 이게 측정보다 오래 걸린다)
  local sid="$1" cat_="$2" out="$3" i r vals
  vals=""
  for ((r=0;r<ROWS;r++)); do
    [ -n "$vals" ] && vals+=","
    # r*0.5 를 정수 연산으로만 — awk "%.3f" 와 같은 문자열이 나온다 (0.000 · 0.500 · 1.000 …)
    vals+="($sid,0,$((r/2)).$(((r%2)*5))00,'{\"k\":$r}',45.0,0.0,'','$cat_')"
  done
  local stmt="INSERT INTO pose_data_r276 (session_id,rep_number,timestamp_sec,joint_coordinates,sync_rate,smoothed_knee_angle,feedback_message,created_at) VALUES $vals ON DUPLICATE KEY UPDATE session_id = session_id;"
  : > "$out"
  for ((i=0;i<ITER;i++)); do echo "$stmt" >> "$out"; done
}

uk_cols(){ DB -e "SELECT COUNT(*) FROM information_schema.statistics
        WHERE table_schema='shadowfit' AND table_name='pose_data_r276' AND index_name='uk_pose_event';" | tr -d '[:space:]'; }

# 팔에 맞춰 uk_pose_event 를 붙였다 뗀다. 🔴 «걸었다고 생각했는데 안 걸린» 판을 막으려고
#   매번 information_schema 로 단언하고, 어긋나면 즉시 죽는다.
set_index_for(){ # $1=arm
  local want=4 have
  [ "$1" = "no_uk" ] && want=0
  have=$(uk_cols)
  if [ "$have" != "$want" ]; then
    if [ "$want" = 0 ]; then DB -e "ALTER TABLE pose_data_r276 DROP INDEX uk_pose_event;"
    else DB -e "ALTER TABLE pose_data_r276 ADD UNIQUE KEY uk_pose_event (session_id, rep_number, timestamp_sec, created_at);"
    fi
    have=$(uk_cols)
  fi
  [ "$have" = "$want" ] || { echo "🔴 인덱스 상태 불일치: 팔=$1 기대=$want 실제=$have — 중단"; exit 1; }
  echo "  [$1] uk_pose_event 컬럼 수 = $have (기대 $want)"
}

# 팔 하나를 한 판 돌린다 → "데드락수 총시도수" 를 찍는다
run_arm(){ # $1=arm  $2=round
  local arm="$1" round="$2" w sid cat_ pids=()
  DB -e "TRUNCATE TABLE pose_data_r276;"
  rm -f "$SC"/err.* "$SC"/w.*
  for ((w=0;w<WORKERS;w++)); do
    case "$arm" in
      same_partition) sid=$((900+w)); cat_="2026-05-28 10:00:00" ;;
      diff_partition) sid=$((900+w)); cat_="2026-$(printf '%02d' $((w%12+1)))-15 10:00:00" ;;
      single_session) sid=900;        cat_="2026-05-28 10:00:00" ;;
      no_uk)          sid=$((900+w)); cat_="2026-05-28 10:00:00" ;;  # same_partition 과 페이로드 동일
    esac
    gen_sql "$sid" "$cat_" "$SC/w.$w.sql"
  done
  for ((w=0;w<WORKERS;w++)); do
    ( docker exec -i shadowfit-mysql mysql --force -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit \
        < "$SC/w.$w.sql" > /dev/null 2> "$SC/err.$w" ) &
    pids+=($!)
  done
  for p in "${pids[@]}"; do wait "$p"; done
  local dl=0 other=0
  for ((w=0;w<WORKERS;w++)); do
    # grep -c 는 0 건일 때도 0 을 찍고 exit 1 을 낸다 — `|| echo 0` 을 붙이면 0 이 두 번 나온다
    c=$(grep -c 'Deadlock found' "$SC/err.$w" 2>/dev/null); dl=$((dl + ${c:-0}))
    c=$(grep -c 'ERROR' "$SC/err.$w" 2>/dev/null);          other=$((other + ${c:-0}))
  done
  local total=$((WORKERS*ITER))
  local rows; rows=$(DB -e "SELECT COUNT(*) FROM pose_data_r276;")
  echo "$arm $round $dl $total $((other-dl)) $rows"
}

echo
echo "## [1] 팔 넷 × ${ROUNDS}판 (첫 판 버림) — 워커 $WORKERS · 문 $ITER · 행 $ROWS"
echo "arm round deadlocks attempts other_err rows" > "$SC/raw.txt"
for arm in same_partition diff_partition single_session no_uk; do
  set_index_for "$arm"
  for ((rd=0;rd<ROUNDS;rd++)); do
    line=$(run_arm "$arm" "$rd")
    echo "$line" >> "$SC/raw.txt"
    echo "  $line$([ "$rd" = 0 ] && echo '   ← 워밍업(버림)')"
  done
done

echo
echo "## [2] 집계"
{
echo "# #276 재현 — 생성 표 (판정은 [README.md](./README.md) 에)"
echo
echo "격리 테이블 \`pose_data_r276\`(\`CREATE TABLE ... LIKE pose_data\` — 파티션·PK·\`uk_pose_event\` 복제)."
echo "워커 **$WORKERS** · 워커당 INSERT 문 **$ITER** · 문당 행 **$ROWS** · 팔당 **$ROUNDS판**(첫 판 버림)."
echo
echo "| 팔 | 판 | 데드락 | 시도 | 비율 | 그 외 에러 | 최종 행수 |"
echo "|---|---|---|---|---|---|---|"
awk 'NR>1 {printf "| %s | %s | %s | %s | %.1f%% | %s | %s |%s\n", $1,$2,$3,$4,($3/$4)*100,$5,$6, ($2==0?" ← 버림":"")}' "$SC/raw.txt"
echo
echo "**팔별 중앙값(첫 판 제외)**"
echo
echo "| 팔 | 데드락 비율 중앙값 | 시도당 확률 p |"
echo "|---|---|---|"
for arm in same_partition diff_partition single_session no_uk; do
  awk -v a="$arm" 'NR>1 && $1==a && $2>0 {print ($3/$4)}' "$SC/raw.txt" | sort -n | awk -v a="$arm" '
    {v[NR]=$1} END{
      # 유효 판이 0 이면 awk 는 빈 값을 0.0% 로 찍는다 — 「0% 였다」와 「안 쟀다」가 같아 보인다
      if (NR==0) { printf "| %s | — (유효 판 0) | — |\n", a; exit }
      m=(NR%2)? v[(NR+1)/2] : (v[NR/2]+v[NR/2+1])/2; printf "| %s | %.1f%% | %.4f |\n", a, m*100, m }'
done
} | tee "$OUT/summary.md"

# 🔴 no_uk 팔이 마지막이라 그대로 두면 테이블이 «키 없는» 상태로 남는다 — 되돌린다.
#   ⚠️ 비우고 나서 붙여야 한다 — no_uk 판이 남긴 8,000행에는 중복이 있어 ADD UNIQUE 가 실패한다
#   (2026-08-20 1차 실행이 정확히 여기서 exit 1 났다. 표는 이미 다 찍힌 뒤였다)
DB -e "TRUNCATE TABLE pose_data_r276;"
set_index_for same_partition

cp "$SC/raw.txt" "$OUT/raw.tsv"
echo
echo "→ $OUT/summary.md (판정은 손으로 쓴 $OUT/README.md 에)"
echo "정리: DROP TABLE pose_data_r276; (지금은 남겨둔다 — 수정 후 대조에 다시 쓴다)"
