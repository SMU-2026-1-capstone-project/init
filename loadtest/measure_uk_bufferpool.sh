#!/usr/bin/env bash
# 從 R8 후속 — 유니크 키의 대가를 **버퍼풀을 넘긴 규모**에서 잰다 (재고표 2번)
#
# ─────────────────────────────────────────────────────────────────────────
# 무엇이 열려 있었나
#
# R8(2026-08-18)이 `uk_pose_event` 의 쓰기 대가를 **−2.9% · p99 +98% · 버퍼풀 읽기 +17.5%**
# 로 냈다. 그런데 그 판은 `Innodb_buffer_pool_reads` 가 **양쪽 다 0** 이었다 —
# 인덱스가 메모리에 다 들어가 **디스크를 한 번도 안 쳤다.**
#
# 정작 V6 마이그레이션이 걱정한 근거는 그 반대 자리다:
#
#   "⚠️ 비용을 알고 건다: 유니크 secondary index 는 InnoDB change buffer 를 쓸 수 없다.
#    삽입마다 인덱스 페이지를 **읽어** 유일성을 확인해야 하므로 쓰기 경로에 랜덤 읽기가 붙는다."
#      — V6__add_pose_data_idempotency_key.sql
#
# 랜덤 읽기가 메모리에서 해결되면 그 비용은 거의 안 보인다. 즉 **지금 인용되는 −2.9% 는
# 「change buffer 를 못 쓰는 대가」를 사실상 안 잰 값**이다. 이 rig 이 그 자리를 잰다.
# ─────────────────────────────────────────────────────────────────────────
#
# ## 팔 셋 — R8 의 둘로는 «인덱스 값» 과 «유니크 값» 이 안 갈린다
#
#   none        인덱스 없음                    기준선
#   nonunique   비유니크 인덱스 (같은 4열)      change buffer 를 **쓸 수 있다**
#   unique      유니크 인덱스 (지금 스키마)     change buffer 를 **못 쓴다**  ← 우리가 내는 값
#
# nonunique ↔ unique 의 차이가 **«유니크라서» 내는 비용**이다. R8 은 none ↔ unique 만 봐서
# 그 둘이 섞여 있었다.
#
# ## 체제를 만드는 법 — 데이터를 키우지 않고 버퍼풀을 줄인다
#
# 2GB 버퍼풀을 인덱스만으로 넘기려면 엔트리 ~33B 기준 6,500만 행이 필요하다(시딩 몇 시간).
# 우리가 원하는 것은 절대값이 아니라 **체제**이고, 두 팔이 같은 데이터를 쓰므로 버퍼풀을
# 줄여도 델타 귀속은 그대로 성립한다. 그래서 버퍼풀을 낮춘다.
#
# 🔴 **`SET GLOBAL` 로는 못 줄인다** (2026-08-23 실측). MySQL 8 은 버퍼풀을
#    `chunk_size × instances` 단위로만 바꾸는데 기본이 128MB × 8 = **1GB** 라, 128MB 를
#    요청해도 **조용히 무시되고 2GB 그대로**였다(그 판은 게이트가 «성립 안 함» 으로 잡았다 —
#    그래서 이게 판정이 아니라 게이트다). chunk·instances 는 **시작 인자**라 동적으로 못 바꾼다.
#    그래서 이 rig 은 **전용 컨테이너를 작은 풀로 띄운다**(sweep/race 컨테이너 선례와 같은 방식).
#    부수 이득: GLOBAL STATUS 가 서버 전역이라 다른 작업과 지표가 안 섞인다.
#
# 🔴 **게이트**: 판마다 `Innodb_buffer_pool_reads` 증분이 0 이면 그 판은 «성립 안 함» 이다.
#    R8 이 정확히 그 자리에서 «메모리 안» 만 재고 끝났다 — 그러니 이건 판정이 아니라 게이트다.
#    («재봤더니 0» 과 «재지 못했다» 는 다르다 — _rig.sh 의 규약)
#
# ⚠️ 한계 (결과 문서에 그대로 옮길 것):
#   · 버퍼풀을 줄여 만든 체제다. «데이터가 커져서» 넘긴 것과 같은지는 **안 쟀다**
#   · 직접 SQL 이다(앱 경로 아님). 묻는 것이 InnoDB 의 인덱스 유지 비용이라 그게 맞지만,
#     Spring 경로의 수치로 인용하면 안 된다
#   · 절대 처리량은 이 박스의 값이다. 신뢰할 것은 **팔 간 상대**뿐이다
#   · 🔴 **무대가 판마다 커진다** — 판이 INSERT_ROWS 만큼 행을 남기므로 뒤쪽 블록은 더 큰
#     테이블에서 잰다. 라틴 방격이 팔 사이에서 그 표류를 **균형**시키지만 없애지는 않는다.
#     그래서 원시 표에 판마다 **시작 행 수**를 같이 찍는다 — 표류가 눈에 보이게 두는 것이
#     «없는 척» 보다 낫다. (판마다 되돌리는 방법도 있으나 대량 DELETE 가 파편화를 만든다)
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a

SRC_CONTAINER=${CONTAINER:-shadowfit-mysql}   # 스키마를 가져올 곳 (Flyway 가 돈 컨테이너)
OWN_CONTAINER=${OWN_CONTAINER:-1}            # 전용 컨테이너를 띄운다 (버퍼풀을 시작 인자로 주려고)
CONTAINER=${UKBP_CONTAINER:-shadowfit-ukbp-mysql}
CHUNK_MB=${CHUNK_MB:-32}                     # 풀 크기의 배수 단위 — 이걸 줄여야 작은 풀이 가능하다
INSTANCES=${INSTANCES:-1}
MYSQL_IMAGE=${MYSQL_IMAGE:-mysql:8.0}
DB_NAME=${DB_NAME:-shadowfit}
PW=${PW:-${MYSQL_ROOT_PASSWORD:-1234}}

ARMS=${ARMS:-"none nonunique unique"}
BLOCKS=${BLOCKS:-4}                 # 첫 블록은 버린다
POOL_MB=${POOL_MB:-128}             # 버퍼풀을 이만큼으로 줄인다
SEED_ROWS=${SEED_ROWS:-3000000}     # 무대 크기 (인덱스가 풀을 넘도록)
INSERT_ROWS=${INSERT_ROWS:-100000}  # 판마다 새로 넣는 행 수 (이게 측정 대상이다)
BATCH=${BATCH:-500}                 # INSERT 문당 행 수
TABLE=pose_data_ukbp
OUT=${OUT:-loadtest/results/uk-bufferpool-$(date +%Y-%m-%d)}
SC=$(mktemp -d)
mkdir -p "$OUT"

DB(){ docker exec -i -e MYSQL_PWD="$PW" "$CONTAINER" mysql -uroot -N "$DB_NAME" "$@" 2>/dev/null; }
die(){ echo "🔴 중단 — $*" >&2; exit 1; }

SRC(){ docker exec -i -e MYSQL_PWD="$PW" "$SRC_CONTAINER" mysql -uroot -N "$DB_NAME" "$@" 2>/dev/null; }

if [ "$OWN_CONTAINER" = "1" ]; then
  echo "## [0-a] 전용 MySQL — 버퍼풀 ${POOL_MB}MB (chunk ${CHUNK_MB}MB × instances $INSTANCES)"
  docker rm -f "$CONTAINER" >/dev/null 2>&1
  docker run -d --name "$CONTAINER" -e MYSQL_ROOT_PASSWORD="$PW" -e MYSQL_DATABASE="$DB_NAME"     "$MYSQL_IMAGE"     --innodb-buffer-pool-size=$(( POOL_MB * 1024 * 1024 ))     --innodb-buffer-pool-chunk-size=$(( CHUNK_MB * 1024 * 1024 ))     --innodb-buffer-pool-instances=$INSTANCES >/dev/null || die "전용 컨테이너 기동 실패"

  # 🔴 «떴다» 는 ping 이 아니라 **인증된 질의**로 본다 — 초기화 임시 서버에 속지 않으려는 것이다
  #    (#275 ② 가 같은 자리에서 라운드를 둘 태웠다).
  hits=0
  for _ in $(seq 1 90); do
    if DB -e "SELECT 1" >/dev/null 2>&1; then hits=$(( hits + 1 )); [ "$hits" -ge 2 ] && break; else hits=0; fi
    sleep 5
  done
  [ "$hits" -ge 2 ] || die "전용 MySQL 이 7.5분 안에 준비되지 않았다"
  GOT=$(DB -e "SELECT @@innodb_buffer_pool_size;" | tr -d "[:space:]")
  echo "  버퍼풀 실제값: $GOT bytes (요청 $(( POOL_MB * 1024 * 1024 )))"
  [ "${GOT:-0}" -le $(( POOL_MB * 1024 * 1024 )) ]     || die "버퍼풀이 요청보다 크다 ($GOT) — chunk/instances 를 확인할 것"

  # 스키마는 원본 컨테이너에서 DDL 을 떠 온다 — 파티션·PK 가 그대로 와야 조건이 같다.
  echo "  스키마: $SRC_CONTAINER 의 pose_data DDL 을 그대로 옮긴다"
  # 🔴 --raw 가 필요하다. 기본 출력은 개행을 이스케이프해서 DDL 이 한 줄로 뭉치고,
  #    그대로 실행하면 파티션 절이 통째로 문자열이 되어 문법 오류가 난다.
  docker exec -i -e MYSQL_PWD="$PW" "$SRC_CONTAINER" mysql -uroot -N --raw "$DB_NAME" -e "SHOW CREATE TABLE pose_data" 2>/dev/null | cut -f2 > "$SC/ddl.sql"
  grep -q "CREATE TABLE" "$SC/ddl.sql" || die "원본 pose_data DDL 을 못 읽었다 — Flyway 가 돌았는지 볼 것"
  # 🔴 백틱은 큰따옴표 안에서 명령 치환이라 이스케이프한다 — 안 하면 셸이 pose_data 를 실행하려 든다.
  sed -i "s/CREATE TABLE \`pose_data\`/CREATE TABLE IF NOT EXISTS \`$TABLE\`/" "$SC/ddl.sql"
  docker exec -i -e MYSQL_PWD="$PW" "$CONTAINER" mysql -uroot "$DB_NAME" < "$SC/ddl.sql"     || die "테이블 생성 실패 (DDL 은 $SC/ddl.sql)"
fi

echo "## [0] 무대 — 격리 테이블 $TABLE"
DB -e "CREATE TABLE IF NOT EXISTS $TABLE LIKE pose_data;" >/dev/null 2>&1
# LIKE 는 인덱스까지 복제한다 — 팔이 정하도록 세컨더리를 먼저 없앤다.
DB -e "ALTER TABLE $TABLE DROP INDEX uk_pose_event;" >/dev/null 2>&1
have=$(DB -e "SELECT COUNT(*) FROM $TABLE;" | tr -d '[:space:]')
echo "  현재 행 수: ${have:-0} (목표 $SEED_ROWS)"

# ── 시딩 — 배가로 채운다. 키가 겹치면 팔 unique 에서 «중복» 이 섞여 조건이 달라진다 ──
if [ "${have:-0}" -lt "$SEED_ROWS" ]; then
  echo "  시딩: 배가로 $SEED_ROWS 행까지 채운다 (키 충돌 없게 rep_number 를 오프셋한다)"
  DB -e "INSERT INTO $TABLE (session_id, rep_number, timestamp_sec, joint_coordinates,
                             sync_rate, smoothed_knee_angle, feedback_message, created_at)
         SELECT 901, seq, seq * 0.001, JSON_OBJECT('k', seq), 45.0, 90.0, '', '2026-05-28 10:00:00'
         FROM (SELECT 1 seq UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) t;" >/dev/null
  while :; do
    n=$(DB -e "SELECT COUNT(*) FROM $TABLE;" | tr -d '[:space:]')
    [ "${n:-0}" -ge "$SEED_ROWS" ] && break
    # rep_number 를 현재 행 수만큼 밀어 새 키를 만든다 — 같은 세션·같은 파티션에 쌓인다
    DB -e "INSERT INTO $TABLE (session_id, rep_number, timestamp_sec, joint_coordinates,
                               sync_rate, smoothed_knee_angle, feedback_message, created_at)
           SELECT session_id, rep_number + $n, timestamp_sec + $n * 0.001, joint_coordinates,
                  sync_rate, smoothed_knee_angle, feedback_message, created_at
           FROM $TABLE LIMIT $(( SEED_ROWS - n ));" >/dev/null || die "시딩 실패"
    echo "    ... $(DB -e "SELECT COUNT(*) FROM $TABLE;" | tr -d '[:space:]') 행"
  done
fi
SEEDED=$(DB -e "SELECT COUNT(*) FROM $TABLE;" | tr -d '[:space:]')
echo "  무대 완성: $SEEDED 행"

echo
echo "## [1] 버퍼풀 확인"
BEFORE_POOL=$(DB -e "SELECT @@innodb_buffer_pool_size;" | tr -d '[:space:]')
# 🔴 전용 컨테이너면 이미 시작 인자로 정해져 있다. 아니면 SET GLOBAL 을 시도하되 **믿지 않는다** —
#    chunk×instances 단위로만 듣기 때문에 조용히 무시될 수 있다(2026-08-23 실측).
[ "$OWN_CONTAINER" = "1" ] || DB -e "SET GLOBAL innodb_buffer_pool_size = $(( POOL_MB * 1024 * 1024 ));" >/dev/null 2>&1
for _ in $(seq 1 60); do
  st=$(DB -e "SHOW STATUS LIKE 'Innodb_buffer_pool_resize_status';" | cut -f2)
  case "$st" in *complete*|"") break ;; esac
  sleep 2
done
NOW_POOL=$(DB -e "SELECT @@innodb_buffer_pool_size;" | tr -d '[:space:]')
echo "  버퍼풀: $BEFORE_POOL → $NOW_POOL bytes"

# 인덱스가 실제로 풀보다 큰지 — 이 판의 전제다. 유니크 팔을 잠깐 걸어 크기를 잰다.
DB -e "ALTER TABLE $TABLE ADD UNIQUE KEY uk_pose_event (session_id, rep_number, timestamp_sec, created_at);" >/dev/null 2>&1
IDX=$(DB -e "SELECT IFNULL(index_length,0) FROM information_schema.tables
             WHERE table_schema='$DB_NAME' AND table_name='$TABLE';" | tr -d '[:space:]')
echo "  세컨더리 인덱스 크기: $IDX bytes (버퍼풀 $NOW_POOL)"
[ "${IDX:-0}" -gt "${NOW_POOL:-1}" ] \
  && echo "  ✅ 인덱스가 버퍼풀보다 크다 — «안 들어가는» 체제다" \
  || echo "  🔶 인덱스가 아직 버퍼풀보다 작다 — SEED_ROWS 를 올리거나 POOL_MB 를 내릴 것 (게이트가 판마다 다시 본다)"

# ── 팔 전환. «걸었다» 와 «걸렸다» 는 다르다 — 매번 단언한다 ──────────────
set_arm(){ # $1 = none|nonunique|unique
  DB -e "ALTER TABLE $TABLE DROP INDEX uk_pose_event;"  >/dev/null 2>&1
  DB -e "ALTER TABLE $TABLE DROP INDEX ix_pose_event;"  >/dev/null 2>&1
  case "$1" in
    unique)    DB -e "ALTER TABLE $TABLE ADD UNIQUE KEY uk_pose_event (session_id, rep_number, timestamp_sec, created_at);" >/dev/null ;;
    nonunique) DB -e "ALTER TABLE $TABLE ADD INDEX ix_pose_event (session_id, rep_number, timestamp_sec, created_at);" >/dev/null ;;
    none)      : ;;
  esac
  local want=0 uq nq
  uq=$(DB -e "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
              WHERE table_schema='$DB_NAME' AND table_name='$TABLE' AND index_name='uk_pose_event';" | tr -d '[:space:]')
  nq=$(DB -e "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
              WHERE table_schema='$DB_NAME' AND table_name='$TABLE' AND index_name='ix_pose_event';" | tr -d '[:space:]')
  case "$1" in
    unique)    [ "${uq:-0}" = "1" ] && [ "${nq:-0}" = "0" ] || die "팔 unique 인데 uk=$uq ix=$nq" ;;
    nonunique) [ "${nq:-0}" = "1" ] && [ "${uq:-0}" = "0" ] || die "팔 nonunique 인데 uk=$uq ix=$nq" ;;
    none)      [ "${uq:-0}" = "0" ] && [ "${nq:-0}" = "0" ] || die "팔 none 인데 uk=$uq ix=$nq" ;;
  esac
  echo "  [$1] 인덱스 상태 확인 (uk=$uq · ix=$nq)"
}

counter(){ DB -e "SHOW GLOBAL STATUS LIKE '$1';" | cut -f2 | tr -d '[:space:]'; }

run_one(){ # $1=arm $2=block → "arm block rows_before sec rows_per_sec bp_reads bp_read_req data_reads"
  local arm="$1" blk="$2" base i
  set_arm "$arm" >&2
  base=$(DB -e "SELECT IFNULL(MAX(rep_number),0) FROM $TABLE;" | tr -d '[:space:]')

  # 이 판이 넣을 SQL 을 미리 만든다 — 생성 비용이 측정에 섞이지 않게.
  : > "$SC/ins.sql"
  local n=0 vals
  while [ "$n" -lt "$INSERT_ROWS" ]; do
    vals=""
    for ((i=0;i<BATCH && n<INSERT_ROWS;i++)); do
      n=$(( n + 1 ))
      [ -n "$vals" ] && vals+=","
      vals+="(901,$(( base + n )),$(( base + n )).001,'{\"k\":$n}',45.0,90.0,'','2026-05-28 10:00:00')"
    done
    echo "INSERT INTO $TABLE (session_id,rep_number,timestamp_sec,joint_coordinates,sync_rate,smoothed_knee_angle,feedback_message,created_at) VALUES $vals;" >> "$SC/ins.sql"
  done

  local r0 q0 d0 r1 q1 d1 t0 t1
  r0=$(counter Innodb_buffer_pool_reads); q0=$(counter Innodb_buffer_pool_read_requests); d0=$(counter Innodb_data_reads)
  t0=$(date +%s%3N)
  docker exec -i -e MYSQL_PWD="$PW" "$CONTAINER" mysql -uroot "$DB_NAME" < "$SC/ins.sql" >/dev/null 2>"$SC/err.$arm.$blk"
  t1=$(date +%s%3N)
  r1=$(counter Innodb_buffer_pool_reads); q1=$(counter Innodb_buffer_pool_read_requests); d1=$(counter Innodb_data_reads)

  local sec rps
  sec=$(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.2f", (b-a)/1000}')
  rps=$(awk -v n="$INSERT_ROWS" -v s="$sec" 'BEGIN{printf "%.0f", (s>0? n/s : 0)}')
  echo "$arm $blk $base $sec $rps $(( r1 - r0 )) $(( q1 - q0 )) $(( d1 - d0 ))"
}

echo
echo "## [2] 스윕 — 팔 «$ARMS» × ${BLOCKS}블록(첫 블록 버림) · 판당 $INSERT_ROWS 행 · 라틴 방격"
echo "arm block rows_before sec rows_per_sec bp_reads bp_read_req data_reads" > "$SC/raw.txt"
av=($ARMS); an=${#av[@]}
for ((b=0;b<BLOCKS;b++)); do
  echo "  --- 블록 $b$([ "$b" = 0 ] && echo ' (버림)')"
  for ((k=0;k<an;k++)); do
    line=$(run_one "${av[$(((k+b)%an))]}" "$b")
    echo "$line" >> "$SC/raw.txt"
    echo "    $line"
  done
done

echo
echo "## [3] 🔴 게이트 — 디스크를 실제로 쳤는가"
GATE_OK=1
for arm in $ARMS; do
  z=$(awk -v a="$arm" 'NR>1 && $1==a && $2>0 && $6==0 {c++} END{print c+0}' "$SC/raw.txt")
  tot=$(awk -v a="$arm" 'NR>1 && $1==a && $2>0 {c++} END{print c+0}' "$SC/raw.txt")
  if [ "$z" -gt 0 ]; then
    echo "  🔴 팔 $arm — 유효 $tot 판 중 $z 판이 bp_reads=0 (버퍼풀 안에서 끝났다)"
    GATE_OK=0
  else
    echo "  ✅ 팔 $arm — 유효 $tot 판 전부 디스크를 쳤다"
  fi
done
[ "$GATE_OK" = 1 ] || echo "  🔴 이 라운드는 **R8 과 같은 체제**를 다시 잰 것이다 — 표를 «버퍼풀 초과» 로 인용하면 안 된다"

echo
echo "## [4] 집계"
{
echo "# 유니크 키 대가 @ 버퍼풀 초과 — 생성 표 (판정은 [README.md](./README.md) 에)"
echo
echo "격리 테이블 \`$TABLE\` · 무대 **$SEEDED 행** · 버퍼풀 **$NOW_POOL bytes** · 세컨더리 인덱스 **$IDX bytes**"
echo "판당 **$INSERT_ROWS 행**(문당 $BATCH) · 팔 \`$ARMS\` · ${BLOCKS}블록(첫 블록 버림) · 라틴 방격"
echo
echo "게이트(디스크를 쳤는가): $([ "$GATE_OK" = 1 ] && echo '✅ 통과' || echo '🔴 실패 — 아래 표를 «버퍼풀 초과» 로 인용 금지')"
echo
echo "| 팔 | 블록 | 시작 행 수 | 초 | 행/초 | bp_reads(디스크) | bp_read_req | data_reads |"
echo "|---|---|---|---|---|---|---|---|"
awk 'NR>1 {printf "| %s | %s | %s | %s | %s | %s | %s | %s |%s\n", $1,$2,$3,$4,$5,$6,$7,$8, ($2==0?" ← 버림":"")}' "$SC/raw.txt"
echo
echo "**팔별 중앙값(첫 블록 제외)**"
echo
echo "| 팔 | 행/초 | bp_reads | bp_read_req |"
echo "|---|---|---|---|"
for arm in $ARMS; do
  awk -v a="$arm" 'NR>1 && $1==a && $2>0 {print $5, $6, $7}' "$SC/raw.txt" | sort -n | awk -v a="$arm" '
    {r[NR]=$1; b[NR]=$2; q[NR]=$3}
    END{ if(NR==0){printf "| %s | — (유효 판 0) | — | — |\n", a; exit}
         m=(NR%2)?r[(NR+1)/2]:(r[NR/2]+r[NR/2+1])/2
         mb=(NR%2)?b[(NR+1)/2]:(b[NR/2]+b[NR/2+1])/2
         mq=(NR%2)?q[(NR+1)/2]:(q[NR/2]+q[NR/2+1])/2
         printf "| %s | %.0f | %.0f | %.0f |\n", a, m, mb, mq }'
done
} | tee "$OUT/summary.md"

cp "$SC/raw.txt" "$OUT/raw.tsv"
DB -e "SHOW ENGINE INNODB STATUS\G" > "$OUT/innodb-status.txt" 2>/dev/null

echo
echo "→ $OUT/summary.md (판정은 손으로 쓴 $OUT/README.md 에)"
echo "정리: 버퍼풀은 컨테이너 재시작으로 원복된다(SET GLOBAL 은 영속이 아니다). 테이블은 남긴다 — 재실행에 다시 쓴다"
