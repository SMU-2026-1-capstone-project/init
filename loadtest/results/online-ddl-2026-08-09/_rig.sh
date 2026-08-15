#!/bin/bash
# 무중단 DDL 실측 — 공통부. probe.sh 와 ddl_sweep.sh 가 같이 쓴다.
#
# 설계 문서: docs/decisions/online-ddl-vs-blocking-alter.md
#
# 4차 실측(commit-count-2026-08-09/_rig.sh)에서 공통부를 분리한 이유를 그대로 승계한다:
#   스윕 스크립트가 각자 복사본을 갖고 있으면 «같은 규약» 이 한쪽만 고쳐진다(#141·#146).
#   규약은 여기 한 군데, 스윕은 «무엇을 재는가» 만.
#
# 이 rig 는 로컬 docker 다 — SSH 가 없다. 그래도 되는 근거와 그 대가는 설계 §5.
#   요약: 「차단되나」는 이진 사실이라 하드웨어 독립이고, 비교 대상인 96분 baseline
#   자체가 로컬이다. 대신 **절대 소요 시간을 「운영에서 N분」으로 인용 금지.**

set -uo pipefail

CONTAINER=${CONTAINER:-shadowfit-mysql}
PW=${PW:-1234}
DB_NAME=${DB_NAME:-shadowfit}
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
OUT=${OUT:-$HERE}

# 축소 규모 — 설계 §9 결정: 1,000만 행.
#   13,333 세션 × 750행 + 끝세션 250행 = 정확히 10,000,000
#
# 🔴 SESSIONS 를 덮으면 아래 EXPECTED_ROWS·청크 수가 **같이** 따라와야 한다(#197).
#    예전엔 SESSIONS 만 변수고 기대 행수(10000000)와 청크 수(seq 0 13)가 상수였다 —
#    손잡이처럼 보이는데 돌리면 가드에서 죽는, 이 rig 두 번째 «손잡이인 줄 알았다» 다(#183).
SESSIONS=${SESSIONS:-13334}
ROWS_PER_SESSION=750
TAIL_ROWS=250          # 끝세션. 750 의 배수로는 딱 떨어지지 않는 나머지를 여기서 맞춘다
SEED_CHUNK=1000        # 한 INSERT 문이 삼키는 세션 수

# 기본값에서 13333×750+250 = 10,000,000 — 정판 조건은 그대로다.
EXPECTED_ROWS=$(( (SESSIONS - 1) * ROWS_PER_SESSION + TAIL_ROWS ))
# 세션 0 ~ SESSIONS-2 를 SEED_CHUNK 씩 끊는다. 마지막 청크는 seed_scale 이 잘라 쓴다.
SEED_CHUNKS=$(( (SESSIONS - 2) / SEED_CHUNK ))

# _seq 범위는 세션 번호를 다 담아야 한다. 그 위는 시딩이 **조용히 모자라고**, 가드에서야
# 죽는다. 그래서 필요한 자릿수를 여기서 정하고, 담을 수 없는 값은 먼저 끊는다.
#
# 🔴 무조건 6자리로 만들지 않는 이유: `_seq` 는 시딩에서 **자기 자신과 CROSS JOIN** 된다.
#    기존 판(1,000만 행 = 13,334 세션)의 플랜을 건드리지 않으려면 넓힐 때만 넓힌다.
if [ "$SESSIONS" -le 100000 ]; then SEQ_DIGITS=5; else SEQ_DIGITS=6; fi
[ "$SESSIONS" -ge 2 ] && [ "$SESSIONS" -le 1000000 ] \
  || { echo "🔴 SESSIONS 는 2~1000000 이어야 한다 (받은 값 '$SESSIONS') — _seq 범위 제약" >&2; exit 1; }

SEQ_EXPR="d0.n+d1.n*10+d2.n*100+d3.n*1000+d4.n*10000"
SEQ_FROM="d d0,d d1,d d2,d d3,d d4"
if [ "$SEQ_DIGITS" -eq 6 ]; then
  SEQ_EXPR="$SEQ_EXPR+d5.n*100000"
  SEQ_FROM="$SEQ_FROM,d d5"
fi

# ── pt-osc 전용 계정 ─────────────────────────────────────────────────────
#
# 🔴 root 로는 pt-osc 가 아예 못 붙는다. percona-toolkit 의 Perl DBD::mysql 이
#    caching_sha2_password(8.0 기본값)를 비-SSL 연결에서 못 읽는다:
#      Authentication plugin 'caching_sha2_password' reported error:
#      Authentication requires secure connection.   → rc=2, 1초 만에 즉사
#    DSN 에 mysql_ssl=1 을 넣는 우회도 안 된다 — pt-osc 는 그 키를 모르고
#    «Unknown MySQL server host 'mysql_ssl=1'» 로 호스트명 취급한다.
#    그래서 mysql_native_password 로 인증하는 전용 계정을 따로 둔다.
#    로컬 실험 컨테이너 한정이다. 이 계정 정의를 다른 환경으로 옮기지 말 것.
PTOSC_USER=${PTOSC_USER:-ptosc}
PTOSC_PW=${PTOSC_PW:-ptosc1234}

FAILED=()

# mysql 클라이언트 stderr 는 버리지 않고 파일로 뺀다.
#   버리던 이유(stdout 오염 방지)는 맞았지만, 그 대가로 «왜 실패했는가» 가 통째로 사라져
#   1차 실행의 진단이 길어졌다. 파일로 빼면 stdout 은 깨끗하고 사유도 남는다.
MYSQL_ERR=${MYSQL_ERR:-$OUT/mysql_stderr.log}

die() {
  echo "" >&2
  echo "🔴 중단 — $*" >&2
  echo "   이 지점 이후는 측정되지 않았다. 남은 행만 보고 판정하지 말 것." >&2
  echo "   서버 응답: $MYSQL_ERR (경고 줄 제외하고 볼 것)" >&2
  exit 1
}

DB()  { docker exec -i "$CONTAINER" mysql -uroot -p"$PW" "$DB_NAME" "$@" 2>>"$MYSQL_ERR"; }
DBQ() { DB -N -e "$1" | tr -d '\r'; }

require_container() {
  docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null | grep -q true \
    || die "$CONTAINER 가 안 돌고 있다 — docker compose up -d mysql"
}

# 팔 B 가 붙을 수 있게 만든다. 위 PTOSC_USER 주석의 인증 함정 참고.
ensure_ptosc_user() {
  DB -e "
    CREATE USER IF NOT EXISTS '$PTOSC_USER'@'%' IDENTIFIED WITH mysql_native_password BY '$PTOSC_PW';
    ALTER USER '$PTOSC_USER'@'%' IDENTIFIED WITH mysql_native_password BY '$PTOSC_PW';
    GRANT ALL PRIVILEGES ON *.* TO '$PTOSC_USER'@'%' WITH GRANT OPTION;
    FLUSH PRIVILEGES;" || die "pt-osc 계정을 못 만들었다"
  local p
  p=$(DBQ "SELECT plugin FROM mysql.user WHERE user='$PTOSC_USER' AND host='%';" | tr -d '[:space:]')
  [ "$p" = "mysql_native_password" ] \
    || die "pt-osc 계정 인증 플러그인이 '$p' 다 — caching_sha2_password 면 팔 B 가 rc=2 로 즉사한다"
}

# ── 시딩 ─────────────────────────────────────────────────────────────────
#
# ⚠️ 이 테이블 정의는 loadtest/seed/seed_pose_scale.sh 와 같고, 그 파일 헤더의 경고를
#    그대로 승계한다(이슈 #153): 실 pose_data 와 컬럼이 어긋나 있다
#    (is_correct 여기만 있음 / rep_number·smoothed_knee_angle 여기 없음).
#    이 실험은 DDL 비용을 재는 것이라 결론은 안 바뀌지만, **행 크기가 다르므로
#    여기서 나온 수를 「현재 pose_data 의 값」으로 인용하면 안 된다.**
#
# 🔴 created_at 간격이 원본(4분)이 아니라 40분인 이유:
#    원본은 133,334 세션 × 4분 ≈ 370일을 덮어 14개 파티션에 고르게 퍼졌다. 세션 수만
#    1/10 로 줄이고 간격을 그대로 두면 37일치가 되어 **행이 파티션 1~2개에 몰린다.**
#    그러면 파티션 전환(=행 재배치) 비용의 성격 자체가 달라져 축소 규모가 원본의
#    축소가 아니게 된다. 간격을 10배로 늘려 «같은 날짜 폭, 1/10 밀도» 를 유지한다.
#
# 🔴 완화(flush=2)에 들어간 뒤로는 die 가 아니라 seed_die 를 쓴다. 그냥 die 로 빠지면
#    innodb_flush_log_at_trx_commit=2 가 그대로 남아 **다음 판이 다른 내구성으로 측정된다.**
seed_die() {
  DB -e "SET GLOBAL innodb_flush_log_at_trx_commit=1;" >/dev/null 2>&1
  die "$*"
}

seed_scale() {
  local t0 t1
  # 🔴 이전 판의 writer 가 살아 있으면 시딩 중에도 계속 INSERT 해서 아래 «행수 1,000만»
  #    검사가 반드시 깨진다. 1차 실행이 정확히 이걸로 죽었다 — stop_writer 가 못 죽인 writer 가
  #    다음 판 테이블에 2,497행을 흘려 넣었다. 여기서 먼저 막는다.
  assert_no_writer
  printf "  [시딩] %s 세션 → %'d 행 (인덱스 없이)\n" "$SESSIONS" "$EXPECTED_ROWS"
  t0=$(date +%s)
  DB -e "
    DROP TABLE IF EXISTS pose_data_scale;
    CREATE TABLE pose_data_scale (
      id bigint NOT NULL AUTO_INCREMENT,
      session_id bigint NOT NULL,
      timestamp_sec double NOT NULL,
      joint_coordinates text COLLATE utf8mb4_unicode_ci NOT NULL,
      sync_rate double DEFAULT NULL,
      is_correct tinyint(1) DEFAULT 1,
      feedback_message varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
      created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
      PRIMARY KEY (id, created_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    DROP TABLE IF EXISTS _seq;
    CREATE TABLE _seq (n INT PRIMARY KEY);
    INSERT INTO _seq
    WITH d AS (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
               UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9)
    SELECT $SEQ_EXPR
    FROM $SEQ_FROM;
  " || die "시딩 준비 실패"

  # 시딩 한정 완화 — 끝에서 되돌린다. 되돌리기가 빠지면 **다음 판이 다른 내구성으로 측정된다.**
  # 여기부터 seed_die 를 쓴다(위 주석).
  DB -e "SET GLOBAL innodb_flush_log_at_trx_commit=2;" || die "flush 완화 실패"

  local i s e
  for i in $(seq 0 "$SEED_CHUNKS"); do
    s=$((i*SEED_CHUNK)); e=$(((i+1)*SEED_CHUNK))
    [ $e -gt $((SESSIONS-1)) ] && e=$((SESSIONS-1))
    [ $s -ge $((SESSIONS-1)) ] && break
    DB -e "INSERT INTO pose_data_scale
             (session_id, timestamp_sec, joint_coordinates, sync_rate, is_correct, feedback_message, created_at)
           SELECT s.n+1, r.n*1.2, '{}', 75.0, 1, 'ok',
                  TIMESTAMP('2026-01-01 06:00:00') + INTERVAL (s.n*40) MINUTE + INTERVAL FLOOR(r.n*1.2) SECOND
           FROM _seq s CROSS JOIN _seq r
           WHERE s.n >= $s AND s.n < $e AND r.n < $ROWS_PER_SESSION;" \
      || seed_die "청크 시딩 실패 (세션 $s~$e)"
  done
  DB -e "INSERT INTO pose_data_scale
           (session_id, timestamp_sec, joint_coordinates, sync_rate, is_correct, feedback_message, created_at)
         SELECT $SESSIONS, r.n*1.2, '{}', 75.0, 1, 'ok',
                TIMESTAMP('2026-01-01 06:00:00') + INTERVAL (($SESSIONS-1)*40) MINUTE + INTERVAL FLOOR(r.n*1.2) SECOND
         FROM _seq r WHERE r.n < $TAIL_ROWS;" || seed_die "끝세션 시딩 실패"

  DB -e "CREATE INDEX idx_session_timestamp ON pose_data_scale (session_id, timestamp_sec);
         SET GLOBAL innodb_flush_log_at_trx_commit=1;
         ANALYZE TABLE pose_data_scale;
         DROP TABLE IF EXISTS _seq;" || seed_die "인덱스 빌드/복구 실패"
  t1=$(date +%s)

  # 🔴 «시딩했다» 와 «시딩됐다» 는 다르다. 행 수는 이 실험이 고정해야 하는 조건이라,
  #    확인 없이 지나가면 다른 크기의 테이블을 잰 판이 표에 들어간다.
  local n flush
  n=$(DBQ "SELECT COUNT(*) FROM pose_data_scale;")
  [ "$n" = "$EXPECTED_ROWS" ] || die "행 수가 $EXPECTED_ROWS 이 아니다 — 실제 '$n'"
  flush=$(DBQ "SELECT @@innodb_flush_log_at_trx_commit;")
  [ "$flush" = "1" ] || die "내구성 복구가 안 됐다 — flush='$flush' (다음 판이 오염된다)"
  printf "  [시딩] 완료 %ss — %'d 행 확인, flush=1 복구 확인\n" "$((t1-t0))" "$EXPECTED_ROWS"
}

# ── 대상 DDL ─────────────────────────────────────────────────────────────
# 96분 baseline 과 **같은 문장이어야** 비교가 성립한다.
# 원본: loadtest/measure_partition.sh:16-33
PARTITION_SPEC="PARTITION BY RANGE (UNIX_TIMESTAMP(created_at)) (
  PARTITION p2026_01 VALUES LESS THAN (UNIX_TIMESTAMP('2026-02-01 00:00:00')),
  PARTITION p2026_02 VALUES LESS THAN (UNIX_TIMESTAMP('2026-03-01 00:00:00')),
  PARTITION p2026_03 VALUES LESS THAN (UNIX_TIMESTAMP('2026-04-01 00:00:00')),
  PARTITION p2026_04 VALUES LESS THAN (UNIX_TIMESTAMP('2026-05-01 00:00:00')),
  PARTITION p2026_05 VALUES LESS THAN (UNIX_TIMESTAMP('2026-06-01 00:00:00')),
  PARTITION p2026_06 VALUES LESS THAN (UNIX_TIMESTAMP('2026-07-01 00:00:00')),
  PARTITION p2026_07 VALUES LESS THAN (UNIX_TIMESTAMP('2026-08-01 00:00:00')),
  PARTITION p2026_08 VALUES LESS THAN (UNIX_TIMESTAMP('2026-09-01 00:00:00')),
  PARTITION p2026_09 VALUES LESS THAN (UNIX_TIMESTAMP('2026-10-01 00:00:00')),
  PARTITION p2026_10 VALUES LESS THAN (UNIX_TIMESTAMP('2026-11-01 00:00:00')),
  PARTITION p2026_11 VALUES LESS THAN (UNIX_TIMESTAMP('2026-12-01 00:00:00')),
  PARTITION p2026_12 VALUES LESS THAN (UNIX_TIMESTAMP('2027-01-01 00:00:00')),
  PARTITION p2027_01 VALUES LESS THAN (UNIX_TIMESTAMP('2027-02-01 00:00:00')),
  PARTITION pfuture  VALUES LESS THAN MAXVALUE
)"

# ── writer ───────────────────────────────────────────────────────────────
install_writer() {
  docker exec -i "$CONTAINER" mysql -uroot -p"$PW" "$DB_NAME" < "$HERE/writer.sql" 2>/dev/null \
    || die "writer.sql 적재 실패"
  DBQ "SELECT COUNT(*) FROM information_schema.routines
       WHERE routine_schema='$DB_NAME' AND routine_name='ddl_writer';" | grep -q '^1$' \
    || die "ddl_writer 프로시저가 안 만들어졌다"
}

start_writer() {  # $1=arm 이름 $2=최대 지속 초 $3=시도 간격 ms
  DB -e "TRUNCATE TABLE ddl_writer_log; DELETE FROM ddl_writer_ctl;" || die "writer 로그/제어 초기화 실패"
  # -d 로 떼어놓고 DDL 이 끝나면 stop_writer 가 멈춘다.
  docker exec -d "$CONTAINER" mysql -uroot -p"$PW" "$DB_NAME" \
    -e "CALL ddl_writer('$1', $2, $3);"
  sleep 3   # DDL 전 정상 구간(=평상시 latency 기준선)을 몇 건 확보한다
  local n
  n=$(DBQ "SELECT COUNT(*) FROM ddl_writer_log;")
  [ "${n:-0}" -gt 0 ] || die "writer 가 3초 안에 한 건도 못 썼다 — 안 떴다고 본다"

  # 🔴 «떴다» 만으로는 부족하다. conn_id 를 안 남겼으면 stop_writer 가 또 못 죽인다.
  #    그건 DDL 을 24분 돌린 **뒤에** 드러나므로, 여기서 미리 끊는다.
  WRITER_CONN=$(DBQ "SELECT conn_id FROM ddl_writer_ctl WHERE id=1;" | tr -d '[:space:]')
  [ -n "${WRITER_CONN:-}" ] \
    || die "writer 가 conn_id 를 안 남겼다 — writer.sql 이 옛 버전이다(제어 테이블 없음). stop_writer 가 못 죽인다"
}

# conn_id 가 아직 살아 있으면 rc=0
#
# 🔴 «못 읽었다» 를 «죽었다» 로 세지 않는다. DBQ 는 docker exec 라 컨테이너가 바쁘면
#    빈 문자열을 낼 수 있는데, 그걸 0 으로 접으면 **살아 있는 writer 를 죽었다고 통과시킨다.**
#    이 rig 는 같은 계열 착각으로 두 번 깨졌다(rc≠0 을 «거절됨 ✅» 으로, 없는 테이블을
#    «트리거 0개 ✅» 로). 답이 0/1 이 아니면 판정을 미루고 **살아 있는 쪽으로 센다** —
#    틀렸을 때 손해가 작은 방향이다(한 판 더 기다릴 뿐, 오염된 판이 표에 안 들어간다).
writer_alive() {  # $1 = conn_id. 살아있음/판정불가 → rc=0, 확실히 죽음 → rc=1
  [ -n "${1:-}" ] || return 1
  local n
  n=$(DBQ "SELECT COUNT(*) FROM performance_schema.processlist WHERE id=$1;" | tr -d '[:space:]')
  case "$n" in
    0) return 1 ;;                 # 확실히 없다
    ''|*[!0-9]*)                   # 빈 답·숫자 아님 = 판정 불가
      echo "  ⚠️ processlist 를 못 읽었다(응답 '$n') — 살아 있는 쪽으로 세고 계속 기다린다" >&2
      return 0 ;;
    *) return 0 ;;                 # 1 이상 = 살아 있다
  esac
}

# 🔴 1차 실행(2026-08-09)이 죽은 자리다 — 자세한 진단은 writer.sql 의 제어 테이블 주석.
#    요약: `info LIKE 'CALL ddl_writer%'` 로는 프로시저를 못 찾는다. 살아남은 writer 가
#    다음 판 시딩에 2,497행을 흘려 넣어 «행수 1,000만» 검사를 깨뜨렸다.
#
#    이제 두 단이다. ① stop 플래그로 협조 종료를 기다린다 — 진행 중이던 INSERT 를 안 끊어서
#    writer 로그의 끝이 잘리지 않는다. ② 안 멈추면 KILL. 둘 다 실패하면 **die 한다.**
#    조용히 넘어가면 오염된 다음 판이 «측정됐다» 는 얼굴로 표에 들어간다.
stop_writer() {
  local cid=${WRITER_CONN:-} i
  [ -n "$cid" ] || cid=$(DBQ "SELECT conn_id FROM ddl_writer_ctl WHERE id=1;" | tr -d '[:space:]')
  DB -e "UPDATE ddl_writer_ctl SET stop=1 WHERE id=1;" >/dev/null 2>&1

  for i in $(seq 1 15); do
    writer_alive "$cid" || { WRITER_CONN=""; return 0; }
    sleep 1
  done
  echo "  ⚠️ writer 가 협조 종료를 안 받는다 — KILL 로 넘어간다(로그 끝이 한 건 잘릴 수 있다)" >&2
  [ -n "$cid" ] && DB -e "KILL $cid;" >/dev/null 2>&1
  for i in $(seq 1 15); do
    writer_alive "$cid" || { WRITER_CONN=""; return 0; }
    sleep 1
  done
  die "writer(conn ${cid:-?})가 30초를 줘도 안 죽는다 — 다음 판 시딩이 오염된다"
}

# 시딩 직전 가드. 이전 판의 writer 가 남아 있으면 «행수 1,000만» 이 반드시 깨진다.
assert_no_writer() {
  local has cid
  has=$(DBQ "SELECT COUNT(*) FROM information_schema.tables
             WHERE table_schema='$DB_NAME' AND table_name='ddl_writer_ctl';" | tr -d '[:space:]')
  [ "${has:-0}" = "1" ] || return 0   # writer 를 아직 안 깐 단계(probe 등) — 볼 게 없다
  cid=$(DBQ "SELECT conn_id FROM ddl_writer_ctl WHERE id=1;" | tr -d '[:space:]')
  writer_alive "$cid" \
    && die "이전 판의 writer(conn $cid)가 아직 살아 있다 — 시딩 중에도 INSERT 해서 행수 검사가 깨진다"
  return 0
}

# writer 로그 → 한 줄 요약. 이 실험의 1차 산출물이 여기서 나온다.
writer_summary() {  # stdout: "attempts errors max_elapsed_ms p50_elapsed_ms max_gap_ms"
  DBQ "SELECT
         COUNT(*),
         IFNULL(SUM(errno <> 0), 0),
         IFNULL(MAX(elapsed_ms), -1),
         IFNULL(MAX(CASE WHEN rn = mid THEN elapsed_ms END), -1),
         IFNULL(MAX(gap_ms), -1)
       FROM (
         SELECT elapsed_ms, errno,
                ROW_NUMBER() OVER (ORDER BY elapsed_ms) rn,
                COUNT(*) OVER () DIV 2 mid,
                TIMESTAMPDIFF(MICROSECOND,
                  LAG(started_at) OVER (ORDER BY seq), started_at) / 1000 gap_ms
         FROM ddl_writer_log
       ) t;" | tr '\t' ' '
}

dump_writer_log() {  # $1 = 파일명
  DB -e "SELECT seq, arm, started_at, elapsed_ms, errno FROM ddl_writer_log ORDER BY seq;" \
    > "$OUT/$1" 2>/dev/null || echo "  ⚠️ writer 로그 덤프 실패 ($1)" >&2
}

# ── 곁다리 지표 ──────────────────────────────────────────────────────────
#
# binlog 델타는 지금 판정에 안 쓴다. 그래도 걷는 이유: 청크 복사가 binlog 를 타므로
# 이 값이 곧 **복제 지연의 원인 크기**다 — 3순위 복제 실험이 그대로 물린다.
# 걷어두고 안 본 지표를 나중에 붙였더니 증명 하나·반증 하나가 나온 전례가 있다
# (commit-count-and-mysql-metrics.md).
#
# ⚠️ `SELECT ... FROM (SHOW BINARY LOGS)` 은 문법이 아니다 — SHOW 는 서브쿼리로 못 감싼다.
#    파일 목록을 직접 더한다.
binlog_bytes() {
  docker exec "$CONTAINER" bash -c \
    "ls -l /var/lib/mysql/binlog.[0-9]* 2>/dev/null | awk '{s+=\$5} END {print s+0}'" 2>/dev/null \
  || echo 0
}

# 원본 + 사본 + 임시테이블을 전부 합친 피크. 팔 B 의 «디스크 2배» 가 여기서 보인다.
disk_now() {
  docker exec "$CONTAINER" bash -c \
    "ls -l /var/lib/mysql/$DB_NAME/ 2>/dev/null \
     | awk '/pose_data_scale|#sql|_pose_data_scale_new/ {s+=\$5} END {printf \"%.0f\", s/1024/1024}'"
}

start_disk_sampler() {  # $1 = 결과 파일. 5초마다 찍어 최대치를 남긴다
  local f=$OUT/$1
  : > "$f"
  ( while :; do echo "$(date +%s) $(disk_now)" >> "$f"; sleep 5; done ) &
  DISK_PID=$!
}
stop_disk_sampler() {
  [ -n "${DISK_PID:-}" ] && kill "$DISK_PID" 2>/dev/null
  DISK_PID=""
}
disk_peak() {  # $1 = 샘플 파일 → MB 최대치
  awk '{if ($2+0 > m) m=$2+0} END {print m+0}' "$OUT/$1" 2>/dev/null || echo -1
}

# ── 판 검증 ──────────────────────────────────────────────────────────────
#
# 🔴 «DDL 이 끝났다» 와 «DDL 이 됐다» 는 다르다. 팔 B 는 도구가 조용히 포기해도
#    종료 코드가 0 일 수 있어서, 파티션이 실제로 걸렸는지 확인하지 않으면
#    **아무것도 안 한 판이 「빠르다」로 표에 들어간다.**
verify_partitioned() {  # $1 = 태그
  local parts rows
  parts=$(DBQ "SELECT COUNT(*) FROM information_schema.partitions
               WHERE table_schema='$DB_NAME' AND table_name='pose_data_scale'
                 AND partition_name IS NOT NULL;")
  [ "${parts:-0}" = "14" ] || { echo "  ✗ 파티션이 14개가 아니다 (실제 '${parts:-없음}') — $1" >&2; return 1; }
  rows=$(DBQ "SELECT COUNT(*) FROM pose_data_scale;")
  [ "${rows:-0}" -ge "$EXPECTED_ROWS" ] \
    || { echo "  ✗ 행이 유실됐다 (실제 '$rows' < $EXPECTED_ROWS) — $1" >&2; return 1; }
  echo "  검증: 파티션 14개 · 행 $rows (시드 $EXPECTED_ROWS + writer 분)"
  return 0
}

# ── 로그 ─────────────────────────────────────────────────────────────────
init_log() {
  [ -f "$LOG" ] || printf "round\tarm\tddl_s\tattempts\terrors\tmax_stall_ms\tp50_ms\tmax_gap_ms\tdisk_peak_mb\tbinlog_mb\n" > "$LOG"
}
# 실패를 숫자로 바꾸지 않는다. 0 은 «재봤더니 0», FAIL 은 «재지 못했다» 다.
fail_row() { printf "%s\t%s\tFAIL\t-\t-\t-\t-\t-\t-\t-\n" "$1" "$2" >> "$LOG"; }

finish() {  # $1 = 전체 판 수
  echo; echo "=== 결과 ($LOG) ==="; cat "$LOG"
  if [ ${#FAILED[@]} -gt 0 ]; then
    echo >&2
    echo "🔴 $1 판 중 ${#FAILED[@]}판이 유효 데이터를 내지 못했다: ${FAILED[*]}" >&2
    echo "   해당 판은 위 표에서 FAIL 행이다. 남은 $(( $1 - ${#FAILED[@]} ))판만으로" >&2
    echo "   결론을 쓰지 말 것 — 비교의 근거가 그만큼 줄었다." >&2
    exit 1
  fi
  echo; echo "✅ $1 판 전부 유효."
}
