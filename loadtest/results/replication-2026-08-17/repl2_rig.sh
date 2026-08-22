#!/usr/bin/env bash
# 복제 — EC2 2대 무대 공통부 (Q1·Q2 전용)
#
# 설계: docs/decisions/replication-lag-and-semisync.md
# 게이트: repl2_probe.sh · 본 측정: repl2_sweep.sh · 사용법: REPL2-RIG.md
#
# ─────────────────────────────────────────────────────────────────────────
# 이 rig 이 재는 것 / 안 재는 것
#
#   재는 것    Q1 쓰기 부하에서 리플리카가 몇 초 뒤처지는가
#              Q2 반동기가 쓰기에 무는 값 (처리량 · 커밋 지연)
#   안 재는 것 Q3·Q4·Q5 — 2026-08-17 로컬 라운드가 이미 답했다(같은 디렉터리 README).
#              Q3 은 「이 계측으로는 못 잰다」로 닫혔고, 이 rig 은 그 재도전이 아니다.
#
# 🔴 러너의 자리는 **소스 박스**다. P6(동거)처럼 부하기가 따로 있는 구성이 아니다.
#    이유는 설계 §7 의 시계 함정 하나다 — 하트비트를 «소스 시각 vs 리플리카 시각» 으로
#    재면 두 인스턴스의 시계 차이가 그대로 지연으로 찍힌다. 그래서 **쓰는 것도 소스,
#    읽는 것도 소스 박스의 시계**로 묶는다. 리플리카는 원격 3306 으로만 만진다.
#
# 🔴 계측 바닥을 같이 잰다 (2026-08-17 로컬 라운드가 남긴 교훈).
#    그 라운드는 `SOURCE_DELAY=5` 로 만든 정확한 5초를 7~11.5초로 찍었다 — 질의 왕복이
#    현상보다 굵었기 때문이다. 그래서 이 rig 은 **리플리카에 던지는 같은 질의를 소스에도
#    던져** 그 값을 같은 표에 세운다. 정본 지표는 두 값의 차(`lag_net_us`)다:
#      lag_rep = (지금 - 리플리카가 든 beat)   ← 하트비트 주기 + 왕복 + 진짜 복제 지연
#      lag_src = (지금 - 소스가 든 beat)       ← 하트비트 주기 + 왕복
#      lag_net = lag_rep - lag_src             ← **진짜 복제 지연만 남는다**
#    「지연이 작다」와 「계측이 굵다」가 표에서 갈리는 자리다.
#
# 🔴 부하는 rig 내장 SQL writer 다 — ghz→Spring 앱 경로가 **아니다**.
#    설계 §3 의 무대 표는 `gen_batch_multi.py` 를 적어뒀지만, 그 경로를 쓰면 커밋 지연에
#    Spring 커넥션 풀·GC 가 교란 변수로 같이 들어온다. Q2 가 묻는 것은 «반동기가 커밋
#    경로에 무는 값» 이라 DB 계층에서 직접 잰다. **대가**: 4차 라운드의 649 RPS(다세션
#    천장)와 **같은 단위로 비교할 수 없다.** 결과 문서에 이 문장을 그대로 승계할 것.
#    페이로드의 «모양» 은 앱 경로에서 따온다:
#      한 트랜잭션 = pose_data_scale 25행 INSERT + 세션행 1건 UPDATE + COMMIT
#      25 = gen_batch_multi.py 의 기본 --reps (한 요청의 프레임 수)
#      동시 15 = backend/src/main/resources/application.yml:50 maximum-pool-size
#    둘 다 «정한 값» 이 아니라 **실 코드에서 따온 값**이라 근거가 조건 칸에 같이 간다.
#
# 🔴 §9-1 의 추천안을 기본값으로 배선했을 뿐, **결정된 것은 아니다.**
#    설계 §9 의 미결정 체크박스는 그대로 열려 있다. 손잡이를 돌리면 다른 조건이 된다.
# ─────────────────────────────────────────────────────────────────────────

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT=${OUT:-$HERE/_out2}
mkdir -p "$OUT" || { echo "🔴 $OUT 를 못 만든다" >&2; exit 1; }
WORK=${WORK:-$OUT/_work}
mkdir -p "$WORK"

# mysql 클라이언트 stderr 는 버리지 않고 파일로 뺀다 (online-ddl rig 과 같은 규약).
MYSQL_ERR=${MYSQL_ERR:-$OUT/mysql_stderr.log}

# ── 무대 ─────────────────────────────────────────────────────────────────
CONTAINER=${CONTAINER:-shadowfit-mysql}
PW=${PW:-1234}
DB_NAME=${DB_NAME:-shadowfit}
MYSQL_IMAGE=${MYSQL_IMAGE:-mysql:8.0}
XB_IMAGE=${XB_IMAGE:-percona/percona-xtrabackup:8.0}

# 리플리카 — 원격 박스. 이 둘이 없으면 이 rig 은 아무것도 못 한다.
REPLICA_HOST=${REPLICA_HOST:-}
REPLICA_PORT=${REPLICA_PORT:-3306}
# 예: REPLICA_SSH="ssh -i /root/.ssh/measure.pem -o StrictHostKeyChecking=no root@10.0.0.6"
REPLICA_SSH=${REPLICA_SSH:-${REPLICA_HOST:+ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 root@$REPLICA_HOST}}
REPLICA_CONTAINER=${REPLICA_CONTAINER:-shadowfit-mysql}
REPLICA_REPO_DIR=${REPLICA_REPO_DIR:-/root/init}

# AZ 구성 — rig 이 정하는 것이 아니라 **인스턴스를 어디 띄웠는지**다. 라벨만 받아
# 조건 칸에 박는다. 설계 §9-1 ② 가 「이 문서에서 제일 중요한 미결정」이라 부른 항목이다.
REPL_AZ_MODE=${REPL_AZ_MODE:-}

# 🔴 server_id 가 겹치면 복제가 안 붙는다 (설계 §7 의 «붙지 않는 첫 번째 이유»).
#    compose 는 이 값을 안 준다 — 둘 다 기본 1 이다. 그래서 rig 이 부여한다.
SRC_SERVER_ID=${SRC_SERVER_ID:-1}
REP_SERVER_ID=${REP_SERVER_ID:-2}

REPL_USER=${REPL_USER:-repl}
REPL_PW=${REPL_PW:-replpw}

# GTID — §9-1 ④ 추천(무대에서만 켠다). 0 이면 포지션 기반으로 붙는다.
# 🔴 켜는 것은 **이 무대뿐**이다. 실 docker-compose.yml 은 건드리지 않는다 —
#    백업 문서가 PITR 절차를 포지션 기반으로 확정해 뒀고, 그 절차와 어긋난다.
GTID=${GTID:-1}

# 무대 규모 — §9-1 ① 추천 1,000만 행. 시더는 online-ddl rig 의 것을 그대로 쓴다.
SESSIONS=${SESSIONS:-13334}
DDL_RIG=${DDL_RIG:-$(cd "$HERE/../online-ddl-2026-08-09" 2>/dev/null && pwd)}

# 부하 — 위 헤더의 근거 참고
CONNS=${CONNS:-15}
ROWS_PER_TX=${ROWS_PER_TX:-25}
DUR=${DUR:-180}
MULTI_SESSION_BASE=${MULTI_SESSION_BASE:-100}   # 다세션: 101..(100+CONNS)
HOT_SESSION=${HOT_SESSION:-1}                   # 단일 핫세션 대조(H3)

# 반동기 — 기본값을 조건으로 고정한다. 안 건드리는 것도 «정한 것» 이라 기록한다.
SEMISYNC_TIMEOUT_MS=${SEMISYNC_TIMEOUT_MS:-10000}
SEMISYNC_WAIT_POINT=${SEMISYNC_WAIT_POINT:-AFTER_SYNC}

# 리플리카 초기화 — §9-1 ③ 추천 XtraBackup. 실패하면 논리 덤프로 되돌린다(무인 라운드
# 에서 여기서 죽으면 밤을 통째로 잃는다). **어느 경로로 섰는지 산출물에 남는다.**
REPLICA_INIT=${REPLICA_INIT:-xtrabackup}
REPLICA_INIT_USED=""

CATCHUP_TIMEOUT=${CATCHUP_TIMEOUT:-1800}

# 반동기 상태변수 접두어 — 이미지에 신·구 이름이 둘 다 있다. 판정 뒤에 채운다(§7).
SRC_PREFIX=""
REP_PREFIX=""

log()   { printf '[repl2] %s\n' "$*"; }
head_() { printf '\n===== %s =====\n' "$*"; }
die()   { echo >&2; echo "🔴 중단 — $*" >&2; echo "   서버 응답: $MYSQL_ERR" >&2; exit 1; }
now_us(){ date +%s%6N; }
now_s() { date +%s; }

# ── 접속 ─────────────────────────────────────────────────────────────────
# 소스는 로컬 컨테이너, 리플리카는 **소스 박스에서 원격으로** 친다(위 헤더 §7 시계).
# --get-server-public-key: MySQL 8 기본 caching_sha2_password 를 비 TLS 로 쓸 때 필요하다.
# 이게 없으면 「접속이 그냥 안 된다」는 얼굴로 시간을 버린다(설계 §7 체크리스트).
SDB()  { docker exec -i "$CONTAINER" mysql -uroot -p"$PW" "$@" 2>>"$MYSQL_ERR"; }
SDBQ() { SDB -N -B -e "$1" | tr -d '\r'; }
RDB()  { docker exec -i "$CONTAINER" mysql -h "$REPLICA_HOST" -P "$REPLICA_PORT" \
           --get-server-public-key -uroot -p"$PW" "$@" 2>>"$MYSQL_ERR"; }
RDBQ() { RDB -N -B -e "$1" | tr -d '\r'; }
RSSH() { $REPLICA_SSH "$@"; }

# SHOW REPLICA STATUS 의 한 필드.
# ⚠️ `\G` 를 `-N -B` 와 같이 쓰면 파싱이 어긋나 빈 값이 나온다(로컬 rig 이 당한 자리).
#    반드시 정렬 출력에서 뽑는다.
rstat() { RDB -e "SHOW REPLICA STATUS\G" 2>/dev/null \
            | awk -F': *' -v k="$1" '$0 ~ "^[ ]*"k":" {print $2; exit}' | tr -d '\r'; }

# 상태 변수 하나 (소스). performance_schema 로 읽는다 — SHOW STATUS LIKE 보다 파싱이 안전하다.
sstatus() { SDBQ "SELECT VARIABLE_VALUE FROM performance_schema.global_status
                  WHERE VARIABLE_NAME='$1';" 2>/dev/null; }

require_stage() {
  [ -n "$REPLICA_HOST" ] || die "REPLICA_HOST 가 비었다 — 2대 무대가 성립하지 않는다"
  docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null | grep -q true \
    || die "$CONTAINER 가 안 돌고 있다 — docker compose up -d mysql"
  [ "$(SDBQ 'SELECT 1;')" = "1" ] || die "소스 MySQL 에 접속이 안 된다"
  [ "$(RDBQ 'SELECT 1;')" = "1" ] \
    || die "리플리카($REPLICA_HOST:$REPLICA_PORT)에 접속이 안 된다 — 보안그룹 3306 인바운드부터 볼 것"
}

DATADIR_VOL_CACHE=""
datadir_vol() {
  [ -n "$DATADIR_VOL_CACHE" ] && { echo "$DATADIR_VOL_CACHE"; return; }
  DATADIR_VOL_CACHE=$(docker inspect "$CONTAINER" \
    --format '{{range .Mounts}}{{if eq .Destination "/var/lib/mysql"}}{{.Name}}{{end}}{{end}}' 2>/dev/null)
  [ -n "$DATADIR_VOL_CACHE" ] || die "소스 datadir 볼륨을 못 찾았다"
  echo "$DATADIR_VOL_CACHE"
}

# ── D0 사전 확인 (측정 아님) ─────────────────────────────────────────────
d0_preflight() {
  head_ "D0. 사전 확인 — 무엇이 켜져 있나"
  {
    echo "# D0 — 2대 무대 (설계 §2 사전확인)"
    echo "# 생성: $(date -u '+%Y-%m-%dT%H:%M:%SZ') UTC"
    echo "# 소스=로컬 $CONTAINER · 리플리카=$REPLICA_HOST:$REPLICA_PORT"
    echo
    for v in version log_bin binlog_format gtid_mode enforce_gtid_consistency server_id \
             log_replica_updates replica_parallel_workers replica_parallel_type \
             sync_binlog innodb_flush_log_at_trx_commit innodb_buffer_pool_size \
             binlog_row_image binlog_expire_logs_seconds; do
      printf 'source   %-34s %s\n' "$v" "$(SDBQ "SELECT @@$v;" 2>/dev/null || echo '-')"
    done
    echo
    for v in version log_bin binlog_format gtid_mode enforce_gtid_consistency server_id \
             log_replica_updates replica_parallel_workers replica_parallel_type \
             sync_binlog innodb_flush_log_at_trx_commit innodb_buffer_pool_size read_only; do
      printf 'replica  %-34s %s\n' "$v" "$(RDBQ "SELECT @@$v;" 2>/dev/null || echo '-')"
    done
    echo
    echo "# 반동기 플러그인 파일 (양쪽)"
    docker exec "$CONTAINER" sh -c "ls -1 \"\$(mysql -uroot -p$PW -N -B -e 'SELECT @@plugin_dir;' 2>/dev/null)\" 2>/dev/null | grep -i semi" \
      || echo "  (소스에서 semisync .so 를 못 찾았다)"
    echo
    echo "# 병렬 적용 스레드는 팔이 아니다(§9-1 ⑥) — 기본값을 조건으로 기록만 한다."
  } | tee "$OUT/D0_preflight.txt"
}

# 🔴 내구성은 **조건으로 고정**한다 (설계 §7). 4차 라운드에서 이 레버 하나가 결론을
#    통째로 뒤집었다. 기본(1/1)이 아니면 여기서 멈춘다 — 모르고 다른 내구성으로 잰
#    판이 표에 들어가는 것이 제일 나쁘다.
assert_durability() {
  local a b c d
  a=$(SDBQ "SELECT @@sync_binlog;"); b=$(SDBQ "SELECT @@innodb_flush_log_at_trx_commit;")
  c=$(RDBQ "SELECT @@sync_binlog;"); d=$(RDBQ "SELECT @@innodb_flush_log_at_trx_commit;")
  log "내구성 source=$a/$b replica=$c/$d"
  [ "$a" = "1" ] && [ "$b" = "1" ] || die "소스 내구성이 기본(1/1)이 아니다 — $a/$b"
  [ "$c" = "1" ] && [ "$d" = "1" ] || die "리플리카 내구성이 기본(1/1)이 아니다 — $c/$d"
}

set_server_ids() {
  # server_id 는 동적이다. PERSIST 로 두면 컨테이너가 재기동해도 살아남는다.
  SDB -e "SET PERSIST server_id = $SRC_SERVER_ID;" >/dev/null || die "소스 server_id 설정 실패"
  RDB -e "SET PERSIST server_id = $REP_SERVER_ID;" >/dev/null || die "리플리카 server_id 설정 실패"
  local a b
  a=$(SDBQ "SELECT @@server_id;"); b=$(RDBQ "SELECT @@server_id;")
  [ "$a" != "$b" ] || die "server_id 가 여전히 같다 ($a) — 이대로면 복제가 안 붙는다"
  log "server_id source=$a replica=$b"
}

# GTID 온라인 전환 — OFF → OFF_PERMISSIVE → ON_PERMISSIVE → ON.
# 익명 트랜잭션이 남아 있으면 ON 이 거절되므로 0 이 될 때까지 기다린다.
enable_gtid_on() {  # $1 = src|rep
  local q Q
  if [ "$1" = src ]; then q=SDB; Q=SDBQ; else q=RDB; Q=RDBQ; fi
  [ "$($Q 'SELECT @@gtid_mode;')" = "ON" ] && { log "GTID($1) 이미 ON"; return 0; }
  $q -e "SET GLOBAL enforce_gtid_consistency = ON;" >/dev/null || return 1
  $q -e "SET GLOBAL gtid_mode = OFF_PERMISSIVE;"    >/dev/null || return 1
  $q -e "SET GLOBAL gtid_mode = ON_PERMISSIVE;"     >/dev/null || return 1
  local i n
  for i in $(seq 1 60); do
    n=$($Q "SELECT VARIABLE_VALUE FROM performance_schema.global_status
            WHERE VARIABLE_NAME='ONGOING_ANONYMOUS_TRANSACTION_COUNT';" 2>/dev/null)
    [ "${n:-0}" = "0" ] && break
    sleep 1
  done
  $q -e "SET GLOBAL gtid_mode = ON;" >/dev/null || return 1
  [ "$($Q 'SELECT @@gtid_mode;')" = "ON" ] || return 1
  log "GTID($1) ON"
}

enable_gtid_both() {
  [ "$GTID" = "1" ] || { log "GTID=0 — 포지션 기반으로 붙인다"; return 0; }
  enable_gtid_on src || die "소스 GTID 전환 실패"
  enable_gtid_on rep || die "리플리카 GTID 전환 실패"
}

# ── 왕복 지연 (RTT) ──────────────────────────────────────────────────────
# 🔴 이 값이 Q2 의 답을 지배한다 (설계 §9-1 ②). 반동기는 커밋 경로에 네트워크 왕복을
#    하나 더 얹는 일이라, 같은 AZ 와 다른 AZ 는 자릿수가 다르다. 그래서 **매 라운드
#    측정해서 조건 칸에 같이 박는다.** AZ 구성 자체는 rig 밖(인스턴스 배치)이다.
rtt_probe() {  # $1 = 결과 파일
  {
    echo "# 리플리카까지의 왕복 — 조건 칸에 같이 가는 값"
    echo "# AZ 구성: ${REPL_AZ_MODE:-(미기입 — 사람이 채울 것)}"
    echo
    echo "## ICMP (막혀 있으면 빈칸이 정상이다 — 보안그룹이 ICMP 를 안 열었을 뿐)"
    ping -c 10 -q "$REPLICA_HOST" 2>/dev/null | tail -2 || echo "  (ping 불가)"
    echo
    echo "## SQL 왕복 — 커밋 경로가 실제로 무는 자"
    local i t0 t1
    : > "$WORK/_rtt.txt"
    for i in $(seq 1 30); do
      t0=$(now_us); RDBQ "SELECT 1;" >/dev/null 2>&1; t1=$(now_us)
      echo $(( t1 - t0 )) >> "$WORK/_rtt.txt"
    done
    sort -n "$WORK/_rtt.txt" | awk '{a[NR]=$1}
      END{ if(NR) printf "SELECT 1 왕복(us): p50=%d p95=%d max=%d (n=%d)\n",
                 a[int(NR*0.5)+1], a[int(NR*0.95)+1], a[NR], NR }'
    echo
    echo "# ⚠️ 이 왕복에는 docker exec 가 포함된다 — 순수 네트워크 RTT 가 아니라"
    echo "#    «이 rig 이 리플리카를 만질 때 무는 값» 이다. 계측 바닥으로 읽을 것."
  } | tee "$1"
}

# ── 부하 객체 ────────────────────────────────────────────────────────────
# 🔴 커밋 지연을 **MySQL 자기 시계로** 잰다. 트랜잭션마다 쉘에서 mysql 을 띄우면
#    프로세스 생성 비용이 재려는 값보다 굵어진다(로컬 라운드가 당한 그 함정).
#    그래서 커넥션당 프로시저를 한 번 호출하고, 프로시저가 트랜잭션마다 결과 행
#    하나를 돌려준다 — 왕복은 커넥션당 1회다.
# 🔴 지연을 **표에 INSERT 하지 않는다.** 그 INSERT 도 커밋이고 binlog 를 타서 반동기
#    대기를 한 번 더 문다 — 재려는 값을 계측이 부풀리는 자리가 된다. 결과 «행» 으로
#    돌려주면 그 오염이 없다.
create_load_objects() {
  SDB -e "
    CREATE DATABASE IF NOT EXISTS replprobe;
    CREATE TABLE IF NOT EXISTS replprobe._n (n INT PRIMARY KEY);
    CREATE TABLE IF NOT EXISTS replprobe.session_probe (
      id BIGINT PRIMARY KEY,
      version BIGINT NOT NULL DEFAULT 0,
      last_active_at DATETIME(6) NULL
    ) ENGINE=InnoDB;
    CREATE TABLE IF NOT EXISTS replprobe.heartbeat (id INT PRIMARY KEY, beat_us BIGINT NOT NULL);
    INSERT IGNORE INTO replprobe.heartbeat (id, beat_us) VALUES (1, 0);
  " >/dev/null || die "부하 객체 생성 실패"

  SDB -e "
    INSERT IGNORE INTO replprobe._n (n)
    WITH d AS (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
               UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9)
    SELECT d0.n + d1.n*10 + d2.n*100 FROM d d0, d d1, d d2;
  " >/dev/null || die "_n 채우기 실패"

  local i vals="($HOT_SESSION,0,NULL)"
  for i in $(seq 1 "$CONNS"); do vals="$vals,($(( MULTI_SESSION_BASE + i )),0,NULL)"; done
  SDB -e "INSERT IGNORE INTO replprobe.session_probe (id, version, last_active_at) VALUES $vals;" \
    >/dev/null || die "session_probe 채우기 실패"

  # DELIMITER 는 클라이언트 지시어라 -e 로는 안 먹는다. 파일로 먹인다.
  {
    printf 'DELIMITER //\n'
    printf 'DROP PROCEDURE IF EXISTS replprobe.load_run //\n'
    printf 'CREATE PROCEDURE replprobe.load_run(IN p_conn INT, IN p_session BIGINT, IN p_rows INT, IN p_secs INT)\n'
    printf 'BEGIN\n'
    printf '  DECLARE v_end DATETIME(6);\n'
    printf '  DECLARE v_t0  DATETIME(6);\n'
    # 🔴 NOW(6) 가 아니라 SYSDATE(6) 다. NOW() 는 «문장이 시작한 시각» 이고 저장 프로그램
    #    안에서 얼어붙는 해석의 여지가 있다 — 그러면 WHILE 이 영영 안 끝나고 커밋 지연은
    #    전부 0 으로 찍힌다. SYSDATE() 는 **실행되는 그 순간**을 돌려주므로 둘 다 안 생긴다.
    #    (binlog 는 ROW 라 SYSDATE 의 복제 비결정성 문제도 해당 없다 — D0 에 기록된다.)
    printf '  SET v_end = SYSDATE(6) + INTERVAL p_secs SECOND;\n'
    printf '  WHILE SYSDATE(6) < v_end DO\n'
    printf '    SET v_t0 = SYSDATE(6);\n'
    printf '    START TRANSACTION;\n'
    printf '      INSERT INTO %s.pose_data_scale\n' "$DB_NAME"
    printf '        (session_id, timestamp_sec, joint_coordinates, sync_rate, is_correct, feedback_message)\n'
    printf "        SELECT p_session, n * 1.2, '{}', 75.0, 1, 'ok' FROM replprobe._n WHERE n < p_rows;\n"
    printf '      UPDATE replprobe.session_probe\n'
    printf '         SET version = version + 1, last_active_at = SYSDATE(6)\n'
    printf '       WHERE id = p_session;\n'
    printf '    COMMIT;\n'
    printf '    SELECT p_conn, ROUND(UNIX_TIMESTAMP(v_t0) * 1000000), TIMESTAMPDIFF(MICROSECOND, v_t0, SYSDATE(6));\n'
    printf '  END WHILE;\n'
    printf 'END //\n'
    printf 'DELIMITER ;\n'
  } > "$WORK/_load_proc.sql"

  SDB < "$WORK/_load_proc.sql" >/dev/null || die "load_run 프로시저 생성 실패"
  log "부하 객체 준비 — 커넥션 $CONNS · 트랜잭션당 ${ROWS_PER_TX}행"
}

# 하트비트 — 소스가 자기 시계로 1초마다 찍는다. 이벤트 스케줄러로 돌려서 쉘 왕복이
# 하트비트 «생산» 쪽에 끼지 않게 한다(소비 쪽 왕복은 계측 바닥으로 따로 뺀다).
start_heartbeat() {
  SDB -e "
    SET GLOBAL event_scheduler = ON;
    DROP EVENT IF EXISTS replprobe.beat;
    CREATE EVENT replprobe.beat ON SCHEDULE EVERY 1 SECOND DO
      UPDATE replprobe.heartbeat SET beat_us = ROUND(UNIX_TIMESTAMP(SYSDATE(6)) * 1000000) WHERE id = 1;
  " >/dev/null || die "하트비트 이벤트 생성 실패"
  log "하트비트 시작 (EVERY 1 SECOND · 소스 시계)"
}
stop_heartbeat() { SDB -e "DROP EVENT IF EXISTS replprobe.beat;" >/dev/null 2>&1; }

# ── 복제 계정 · 연결 ─────────────────────────────────────────────────────
# §7 함정: MySQL 8 기본 caching_sha2_password 는 비 TLS 로 붙을 때 공개키가 필요하다.
# 표준 경로(GET_SOURCE_PUBLIC_KEY=1)를 먼저 쓰고, 그게 막히면 native 로 되돌린다.
# **어느 쪽으로 붙었는지 기록한다** — 「그냥 됐다」와 「우회해서 됐다」는 다른 사실이다.
REPL_AUTH_USED=""
ensure_repl_user() {
  SDB -e "
    CREATE USER IF NOT EXISTS '$REPL_USER'@'%' IDENTIFIED BY '$REPL_PW';
    GRANT REPLICATION SLAVE ON *.* TO '$REPL_USER'@'%';
    FLUSH PRIVILEGES;
  " >/dev/null || die "복제 계정 생성 실패"
  REPL_AUTH_USED="caching_sha2_password + GET_SOURCE_PUBLIC_KEY=1"
}
fallback_repl_user_native() {
  SDB -e "
    ALTER USER '$REPL_USER'@'%' IDENTIFIED WITH mysql_native_password BY '$REPL_PW';
    FLUSH PRIVILEGES;
  " >/dev/null || return 1
  REPL_AUTH_USED="mysql_native_password (caching_sha2 경로가 막혀 되돌림)"
  log "복제 계정을 native 로 되돌렸다 — 이 사실은 결과에 남는다"
}

# 소스 IP — 리플리카가 소스를 부를 주소. 지정이 없으면 사설 IP 를 추정한다.
SOURCE_HOST=${SOURCE_HOST:-}
source_host() {
  if [ -z "$SOURCE_HOST" ]; then
    SOURCE_HOST=$(hostname -I 2>/dev/null | awk '{print $1}')
    [ -n "$SOURCE_HOST" ] || die "소스 IP 를 못 구했다 — SOURCE_HOST 를 직접 줄 것"
  fi
  echo "$SOURCE_HOST"
}

# 붙은 뒤 리플리카를 «읽기 전용 + 스케줄러 정지» 로 만든다.
# 🔴 물리 사본은 하트비트 EVENT 를 **ENABLED 인 채로** 데려온다. 복제를 타고 온 이벤트라면
#    MySQL 이 SLAVESIDE_DISABLED 로 바꿔주지만, datadir 를 통째로 복사한 경우엔 그 표시가
#    없다. 리플리카에서 스케줄러가 켜지면 **양쪽이 같은 하트비트 행을 쓴다** — 그러면
#    지연이 항상 0 으로 보인다. 재려는 값이 계측 때문에 사라지는 자리다.
# 적용 스레드는 read_only 를 무시하므로 복제 자체는 그대로 돈다.
harden_replica() {
  RDB -e "SET GLOBAL event_scheduler = OFF;" >/dev/null 2>&1
  RDB -e "SET GLOBAL super_read_only = ON;"  >/dev/null 2>&1
  local sched ro
  sched=$(RDBQ "SELECT @@event_scheduler;" 2>/dev/null)
  ro=$(RDBQ "SELECT @@super_read_only;" 2>/dev/null)
  log "리플리카 잠금 — event_scheduler=$sched super_read_only=$ro"
}

attach_replica() {  # $1 = binlog file, $2 = pos, $3 = gtid set (GTID=1 일 때만 쓴다)
  local host; host=$(source_host)
  if [ "$GTID" = "1" ]; then
    # XtraBackup 사본의 GTID 좌표를 리플리카의 «이미 적용한 것» 으로 심는다.
    # 이걸 안 하면 리플리카가 처음부터 다시 받으려 하고, 행이 조용히 중복된다(§7).
    RDB -e "STOP REPLICA; RESET REPLICA ALL;" >/dev/null 2>&1
    RDB -e "RESET MASTER;" >/dev/null || die "리플리카 RESET MASTER 실패"
    if [ -n "${3:-}" ]; then
      RDB -e "SET GLOBAL gtid_purged = '$3';" >/dev/null || die "gtid_purged 심기 실패"
    fi
    RDB -e "
      CHANGE REPLICATION SOURCE TO
        SOURCE_HOST='$host', SOURCE_PORT=3306,
        SOURCE_USER='$REPL_USER', SOURCE_PASSWORD='$REPL_PW',
        SOURCE_AUTO_POSITION=1, GET_SOURCE_PUBLIC_KEY=1;
      START REPLICA;
    " >/dev/null || return 1
  else
    [ -n "${1:-}" ] && [ -n "${2:-}" ] || die "포지션 좌표가 비었다 — 복제를 붙일 수 없다"
    RDB -e "STOP REPLICA; RESET REPLICA ALL;" >/dev/null 2>&1
    RDB -e "
      CHANGE REPLICATION SOURCE TO
        SOURCE_HOST='$host', SOURCE_PORT=3306,
        SOURCE_USER='$REPL_USER', SOURCE_PASSWORD='$REPL_PW',
        SOURCE_LOG_FILE='$1', SOURCE_LOG_POS=$2, GET_SOURCE_PUBLIC_KEY=1;
      START REPLICA;
    " >/dev/null || return 1
  fi

  local i io sq tried_native=0
  for i in $(seq 1 30); do
    io=$(rstat Replica_IO_Running); sq=$(rstat Replica_SQL_Running)
    [ "$io" = "Yes" ] && [ "$sq" = "Yes" ] && { log "복제 연결됨 (IO=Yes SQL=Yes · 인증=$REPL_AUTH_USED)"; \
                                                harden_replica; return 0; }
    # 인증에서 막히는 것이 §7 이 경고한 흔한 자리다 — 한 번만 native 로 되돌려 재시도한다.
    if [ "$io" = "No" ] && [ "$tried_native" = "0" ] && [ "$i" -ge 3 ]; then
      tried_native=1
      if fallback_repl_user_native; then
        RDB -e "STOP REPLICA; START REPLICA;" >/dev/null 2>&1
      fi
    fi
    sleep 2
  done
  RDB -e "SHOW REPLICA STATUS\G" > "$OUT/_replica_status_fail.txt" 2>&1
  return 1
}

# ── 리플리카 세우기 ──────────────────────────────────────────────────────
# §9-1 ③ 추천: XtraBackup 사본. 08-13 백업 라운드가 G5 로 검증한 경로를 그대로 쓴다.
# 🔴 실패하면 논리 덤프로 되돌린다. 「물리가 빠르다」는 이미 그 라운드가 실측했으므로
#    여기서 다시 잴 것은 없다 — 여기서 필요한 것은 **리플리카가 서는 것** 하나다.
rebuild_replica() {
  local t0 t1
  t0=$(now_s)
  case "$REPLICA_INIT" in
    xtrabackup)
      if _rebuild_xtrabackup; then REPLICA_INIT_USED=xtrabackup
      else
        log "XtraBackup 경로 실패 — 논리 덤프로 되돌린다 (사유는 $OUT/_xb.log)"
        _rebuild_dump || return 1
        REPLICA_INIT_USED="dump(xtrabackup 실패 후)"
      fi ;;
    dump) _rebuild_dump || return 1; REPLICA_INIT_USED=dump ;;
    *) die "REPLICA_INIT 는 xtrabackup|dump 여야 한다 — 받은 값 '$REPLICA_INIT'" ;;
  esac
  t1=$(now_s)
  REPLICA_BUILD_S=$(( t1 - t0 ))
  log "리플리카 초기화 완료 — 경로=$REPLICA_INIT_USED ${REPLICA_BUILD_S}s"
}

_remote_volume() {
  local v
  v=$(RSSH "docker inspect $REPLICA_CONTAINER --format '{{range .Mounts}}{{if eq .Destination \"/var/lib/mysql\"}}{{.Name}}{{end}}{{end}}'" 2>/dev/null | tr -d '\r')
  [ -n "$v" ] || v=$(RSSH "docker volume ls -q --filter name=mysql_data | head -1" 2>/dev/null | tr -d '\r')
  echo "$v"
}

_rebuild_xtrabackup() {
  local d="$WORK/xb" vol rvol coord file pos gtid
  vol=$(datadir_vol)
  rm -rf "$d"; mkdir -p "$d"

  # ① 소스 백업 — 08-13 라운드가 쓴 것과 같은 호출(네트워크 네임스페이스 공유로 127.0.0.1 접속)
  docker run --rm --user 0 --network "container:$CONTAINER" \
    -v "$vol:/var/lib/mysql:ro" -v "$d:/backup" "$XB_IMAGE" \
    xtrabackup --backup --target-dir=/backup --datadir=/var/lib/mysql \
    --host=127.0.0.1 --user=root --password="$PW" > "$OUT/_xb.log" 2>&1
  grep -q "completed OK" "$OUT/_xb.log" || return 1

  # ② prepare — 일관 시점으로 만든다. 이게 없으면 못 올린다.
  docker run --rm --user 0 -v "$d:/backup" "$XB_IMAGE" \
    xtrabackup --prepare --target-dir=/backup >> "$OUT/_xb.log" 2>&1 || return 1

  # ③ 좌표 — 「대충 최신」으로 붙이면 행이 조용히 비거나 중복된다(§7)
  coord=$(cat "$d/xtrabackup_binlog_info" 2>/dev/null)
  file=$(printf '%s' "$coord" | awk '{print $1}')
  pos=$(printf  '%s' "$coord" | awk '{print $2}')
  gtid=$(printf '%s' "$coord" | awk '{ $1=""; $2=""; sub(/^[ \t]+/,""); print }' | tr -d '\n\r ')
  log "사본 좌표 file=$file pos=$pos gtid=${gtid:-(없음)}"
  { echo "# xtrabackup_binlog_info"; echo "$coord"; } > "$OUT/_replica_coord.txt"

  # ④ 리플리카 비우기 — 원격 compose 를 그대로 쓴다. 설정이 소스와 같아야 하기 때문이다(§3).
  rvol=$(_remote_volume)
  [ -n "$rvol" ] || { log "리플리카 datadir 볼륨을 못 찾았다"; return 1; }
  RSSH "cd $REPLICA_REPO_DIR && docker compose stop mysql >/dev/null 2>&1; docker compose rm -f mysql >/dev/null 2>&1; docker volume rm -f $rvol >/dev/null 2>&1; docker volume create $rvol >/dev/null; rm -rf /root/_xb_in && mkdir -p /root/_xb_in" \
    || { log "리플리카 정리 실패"; return 1; }

  # ⑤ 사본 전송
  tar -C "$d" -cf - . | RSSH "tar -C /root/_xb_in -xf -" || { log "사본 전송 실패"; return 1; }

  # ⑥ datadir 로 붓기 — `--reflink=never` 는 #210 이 남긴 교훈이다. 여기서는 시간을 재는
  #    것이 아니라 **실제로 복사돼야** 하므로 공유가 아니라 복사로 못박는다.
  #
  # 🔴 물리 사본은 datadir 안의 «신원» 까지 통째로 데려온다. 지우지 않으면:
  #      auto.cnf        → 리플리카가 **소스와 같은 server_uuid** 를 갖는다. MySQL 이
  #                        「소스와 리플리카의 UUID 가 같다」며 복제를 **거부한다.**
  #                        server_id 만 갈라놨다고 끝난 게 아니다 — 여기가 두 번째 문이다
  #      mysqld-auto.cnf → 소스가 SET PERSIST 로 박아둔 값(server_id=1 포함)이 따라온다
  #    둘 다 지우면 리플리카가 기동하며 자기 UUID 를 새로 만든다.
  RSSH "docker run --rm --user 0 -v /root/_xb_in:/backup:ro -v $rvol:/target $XB_IMAGE sh -c 'cp -a --reflink=never /backup/. /target/ && rm -f /target/xtrabackup_* /target/backup-my.cnf /target/auto.cnf /target/mysqld-auto.cnf && chown -R 999:999 /target'" \
    || { log "datadir 붓기 실패"; return 1; }

  # ⑦ 기동 — 소스와 같은 compose 정의로 올린다
  RSSH "cd $REPLICA_REPO_DIR && docker compose up -d mysql" >/dev/null 2>&1 \
    || { log "리플리카 기동 실패"; return 1; }
  _wait_replica_ready || return 1

  # ⑧ 복원된 datadir 는 소스의 설정이 아니다 — server_id·GTID 를 다시 세운다
  RDB -e "SET PERSIST server_id = $REP_SERVER_ID;" >/dev/null 2>&1
  [ "$GTID" = "1" ] && { enable_gtid_on rep || { log "리플리카 GTID 전환 실패"; return 1; }; }

  attach_replica "$file" "$pos" "$gtid" || { log "복제 연결 실패"; return 1; }
  return 0
}

_rebuild_dump() {
  local rvol
  rvol=$(_remote_volume)
  [ -n "$rvol" ] || return 1
  # 논리 경로는 무대를 통째로 다시 만든다. --source-data=2 가 좌표를 덤프 헤더에 남기고,
  # GTID 를 켰으면 --set-gtid-purged=ON 이 «이미 적용한 것» 까지 같이 옮긴다.
  RSSH "cd $REPLICA_REPO_DIR && docker compose stop mysql >/dev/null 2>&1; docker compose rm -f mysql >/dev/null 2>&1; docker volume rm -f $rvol >/dev/null 2>&1; docker compose up -d mysql" >/dev/null 2>&1 || return 1
  _wait_replica_ready || return 1
  RDB -e "SET PERSIST server_id = $REP_SERVER_ID;" >/dev/null 2>&1
  [ "$GTID" = "1" ] && { enable_gtid_on rep || return 1; }

  local gtid_opt="--set-gtid-purged=OFF"
  [ "$GTID" = "1" ] && gtid_opt="--set-gtid-purged=ON"
  docker exec -i "$CONTAINER" mysqldump -uroot -p"$PW" --single-transaction --source-data=2 \
    $gtid_opt --databases "$DB_NAME" replprobe 2>>"$MYSQL_ERR" \
    | RDB >/dev/null || return 1

  local coord file pos gset=""
  coord=$(SDBQ "SHOW MASTER STATUS;")
  file=$(printf '%s' "$coord" | awk '{print $1}')
  pos=$(printf  '%s' "$coord" | awk '{print $2}')

  # 🔴 GTID 일 때 좌표를 **다시 읽어서 넘긴다.** 안 그러면 attach_replica 의 RESET MASTER 가
  #    방금 덤프(--set-gtid-purged=ON)가 심어준 gtid_purged 를 지우고, 세 번째 인자가 비어
  #    다시 심지도 않는다 — 그러면 SOURCE_AUTO_POSITION=1 인 리플리카가 «아무것도 적용 안 함»
  #    상태로 붙어 **소스 binlog 를 처음부터 다시 받는다.** 되돌림 경로가 조용히 무대를
  #    오염시키는 자리였다 (2026-08-22 리뷰 지적, PR #348).
  if [ "$GTID" = "1" ]; then
    gset=$(RDBQ "SELECT @@GLOBAL.gtid_executed;" | tr -d '\n\r ')
    if [ -z "$gset" ]; then
      log "🔴 덤프를 부었는데 리플리카의 gtid_executed 가 비었다 — 이대로 붙이면 처음부터 다시 받는다"
      return 1
    fi
    log "덤프 뒤 리플리카 좌표 gtid_executed=$(printf '%s' "$gset" | cut -c1-60)…"
  fi

  attach_replica "$file" "$pos" "$gset" || return 1
  return 0
}

_wait_replica_ready() {
  local i
  for i in $(seq 1 90); do
    [ "$(RDBQ 'SELECT 1;' 2>/dev/null)" = "1" ] && return 0
    sleep 2
  done
  log "리플리카가 안 뜬다"
  return 1
}

# ── 따라잡기 ─────────────────────────────────────────────────────────────
# 🔴 임계값을 쓰지 않는다. 「지연이 N초 이하면 따라잡은 것」은 근거 없는 기준이라,
#    **좌표가 같아졌는가** 라는 이진 사실로 판정한다.
#    GTID 면 WAIT_FOR_EXECUTED_GTID_SET, 아니면 파일·오프셋 일치를 본다.
wait_caught_up() {  # stdout: 걸린 초
  local t0 t1 rc
  t0=$(now_s)
  if [ "$GTID" = "1" ]; then
    local set_
    set_=$(SDBQ "SELECT @@GLOBAL.gtid_executed;" | tr -d '\n\r ')
    if [ -n "$set_" ]; then
      rc=$(RDBQ "SELECT WAIT_FOR_EXECUTED_GTID_SET('$set_', $CATCHUP_TIMEOUT);")
      [ "${rc:-1}" = "0" ] || { echo "-1"; return 1; }
    fi
  else
    local coord file pos i ef ep ok=0
    coord=$(SDBQ "SHOW MASTER STATUS;")
    file=$(printf '%s' "$coord" | awk '{print $1}')
    pos=$(printf  '%s' "$coord" | awk '{print $2}')
    for i in $(seq 1 "$CATCHUP_TIMEOUT"); do
      ef=$(rstat Relay_Source_Log_File); ep=$(rstat Exec_Source_Log_Pos)
      if [ "$ef" = "$file" ] && [ "${ep:-0}" -ge "${pos:-0}" ]; then ok=1; break; fi
      sleep 1
    done
    [ "$ok" = "1" ] || { echo "-1"; return 1; }
  fi
  t1=$(now_s)
  echo $(( t1 - t0 ))
}

# ── 반동기 ───────────────────────────────────────────────────────────────
# §7 함정 두 개를 한 자리에서 막는다:
#   ① 이미지에 신(source/replica)·구(master/slave) 이름이 **둘 다** 있다 → 파일로 판정
#   ② 반동기는 **양쪽** 다 켜야 한다. 한쪽만 켜면 «켜진 것처럼 보이는데 비동기» 다
detect_semisync_names() {
  if docker exec "$CONTAINER" sh -c "test -f \"\$(mysql -uroot -p$PW -N -B -e 'SELECT @@plugin_dir;' 2>/dev/null)/semisync_source.so\"" >/dev/null 2>&1; then
    SRC_PREFIX=rpl_semi_sync_source; REP_PREFIX=rpl_semi_sync_replica
  else
    SRC_PREFIX=rpl_semi_sync_master; REP_PREFIX=rpl_semi_sync_slave
  fi
  log "반동기 이름 계열: $SRC_PREFIX / $REP_PREFIX"
}

semisync_install() {
  [ -n "$SRC_PREFIX" ] || detect_semisync_names
  local so_src so_rep
  if [ "$SRC_PREFIX" = rpl_semi_sync_source ]; then so_src=semisync_source.so; so_rep=semisync_replica.so
  else so_src=semisync_master.so; so_rep=semisync_slave.so; fi
  SDB -e "INSTALL PLUGIN $SRC_PREFIX SONAME '$so_src';" >/dev/null 2>&1
  RDB -e "INSTALL PLUGIN $REP_PREFIX SONAME '$so_rep';" >/dev/null 2>&1
}

semisync_off() {
  [ -n "$SRC_PREFIX" ] || detect_semisync_names
  SDB -e "SET GLOBAL ${SRC_PREFIX}_enabled = 0;" >/dev/null 2>&1
  RDB -e "SET GLOBAL ${REP_PREFIX}_enabled = 0;" >/dev/null 2>&1
  RDB -e "STOP REPLICA IO_THREAD; START REPLICA IO_THREAD;" >/dev/null 2>&1
  sleep 2
}

# 켠 뒤 status 로 확인한다. 「켰다」와 「켜졌다」는 다르다.
semisync_on() {
  semisync_install
  SDB -e "SET GLOBAL ${SRC_PREFIX}_enabled = 1;
          SET GLOBAL ${SRC_PREFIX}_timeout = $SEMISYNC_TIMEOUT_MS;" >/dev/null || return 1
  # wait_point 는 신 이름 계열에만 있다(구 이름은 master_wait_point).
  SDB -e "SET GLOBAL ${SRC_PREFIX}_wait_point = '$SEMISYNC_WAIT_POINT';" >/dev/null 2>&1
  RDB -e "SET GLOBAL ${REP_PREFIX}_enabled = 1;" >/dev/null || return 1
  RDB -e "STOP REPLICA IO_THREAD; START REPLICA IO_THREAD;" >/dev/null || return 1
  sleep 3
  [ "$(sstatus "${SRC_PREFIX}_status")" = "ON" ] || return 1
  return 0
}

# G2 의 판정 규칙 그대로 — status=ON «그리고» yes_tx 가 실제로 는다.
# status 만 보면 「켜졌는데 아무 트랜잭션도 반동기로 안 나가는」 상태를 통과시킨다.
semisync_yes_tx_grows() {
  local a b
  a=$(sstatus "${SRC_PREFIX}_yes_tx")
  SDB -e "INSERT INTO replprobe.session_probe (id, version) VALUES (999999, 0)
          ON DUPLICATE KEY UPDATE version = version + 1;" >/dev/null 2>&1
  sleep 1
  b=$(sstatus "${SRC_PREFIX}_yes_tx")
  [ "${b:-0}" -gt "${a:-0}" ]
}

# ── 샘플러 ───────────────────────────────────────────────────────────────
# 1초마다: 리플리카 beat · 소스 beat(계측 바닥) · SBS · 반동기 상태.
# 🔴 정본 지표는 lag_net = lag_rep - lag_src 다. 하트비트 주기(1s)와 질의 왕복이
#    두 값에 똑같이 얹혀 있으므로 차에서 상쇄된다.
SAMPLER_PID=""
start_sampler() {  # $1 = 결과 tsv
  printf 'ts_us\tlag_rep_us\tlag_src_us\tlag_net_us\tsbs\tsemi_status\tyes_tx\tno_tx\tsample_ms\n' > "$1"
  (
    while :; do
      local s0 rep src1 src2 t_rep t_s1 t_s2 sbs st yes no s1
      s0=$(now_us)
      # 🔴 소스 beat 를 리플리카 읽기 «앞뒤로» 두 번 읽는다.
      #    한 번만 읽으면 두 질의 사이에 하트비트가 한 칸 움직인 만큼이 그대로 lag_net 에
      #    편향으로 들어간다(읽는 순서에 따라 과대 또는 과소). 앞뒤 평균으로 그 편향을 절반씩
      #    나눠 없앤다 — 남는 것은 왕복의 절반이고, 그건 계측 바닥이라 두 값에 함께 얹힌다.
      src1=$(SDBQ "SELECT beat_us FROM replprobe.heartbeat WHERE id=1;" 2>/dev/null); t_s1=$(now_us)
      rep=$(RDBQ  "SELECT beat_us FROM replprobe.heartbeat WHERE id=1;" 2>/dev/null); t_rep=$(now_us)
      src2=$(SDBQ "SELECT beat_us FROM replprobe.heartbeat WHERE id=1;" 2>/dev/null); t_s2=$(now_us)
      sbs=$(rstat Seconds_Behind_Source)
      if [ -n "$SRC_PREFIX" ]; then
        st=$(sstatus "${SRC_PREFIX}_status"); yes=$(sstatus "${SRC_PREFIX}_yes_tx"); no=$(sstatus "${SRC_PREFIX}_no_tx")
      else st=NA; yes=NA; no=NA; fi
      s1=$(now_us)
      awk -v trep="$t_rep" -v ts1="$t_s1" -v ts2="$t_s2" \
          -v rep="${rep:-0}" -v s1v="${src1:-0}" -v s2v="${src2:-0}" -v sbs="${sbs:-NA}" \
          -v st="${st:-NA}" -v y="${yes:-NA}" -v n="${no:-NA}" -v ms="$(( (s1 - s0) / 1000 ))" '
        BEGIN{
          # 🔴 «못 읽었다» 는 NA 로 적는다. 예전엔 -1 을 썼는데, 그러면 **계측 잡음으로 생긴
          #    진짜 음수 표본**과 구분되지 않는다. 그리고 집계 필터가 음수를 통째로 버려서
          #    p50 이 위로 편향됐다 (2026-08-22 리뷰 지적, PR #348).
          #    lag_net 은 두 값의 «차» 라 음수가 정상적으로 나온다 — 실제 복제 지연이 계측
          #    잡음보다 작을 때다. 그건 버릴 값이 아니라 «지연이 계측 바닥 아래» 라는 관측이다.
          lr = (rep>0) ? trep-rep : "NA";
          l1 = (s1v>0) ? ts1-s1v  : "NA";
          l2 = (s2v>0) ? ts2-s2v  : "NA";
          if (l1 != "NA" && l2 != "NA")      ls = (l1+l2)/2;
          else if (l1 != "NA")               ls = l1;
          else if (l2 != "NA")               ls = l2;
          else                               ls = "NA";
          ln = (lr != "NA" && ls != "NA") ? lr-ls : "NA";
          printf "%d\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n", trep,
                 (lr=="NA")?"NA":sprintf("%d", lr),
                 (ls=="NA")?"NA":sprintf("%d", ls),
                 (ln=="NA")?"NA":sprintf("%d", ln),
                 sbs, st, y, n, ms;
        }' >> "$1"
      sleep 1
    done
  ) &
  SAMPLER_PID=$!
}
stop_sampler() {
  [ -n "$SAMPLER_PID" ] && kill "$SAMPLER_PID" >/dev/null 2>&1
  wait "$SAMPLER_PID" 2>/dev/null
  SAMPLER_PID=""
}

# ── 부하 실행 ────────────────────────────────────────────────────────────
run_writers() {  # $1 = multi|hot, $2 = 지속 초, $3 = 결과 디렉터리
  local i sid pids=()
  mkdir -p "$3"
  for i in $(seq 1 "$CONNS"); do
    if [ "$1" = hot ]; then sid=$HOT_SESSION; else sid=$(( MULTI_SESSION_BASE + i )); fi
    SDB -N -B -e "CALL replprobe.load_run($i, $sid, $ROWS_PER_TX, $2);" > "$3/conn_$i.tsv" &
    pids+=($!)
  done
  # 프로시저가 스스로 끝난다. 그래도 상한을 둔다 — 안 끝나면 판이 아니라 라운드가 죽는다.
  # 🔴 인자 없는 `wait` 를 쓰지 않는다. 샘플러도 백그라운드라 같이 기다리게 되고,
  #    샘플러는 스스로 끝나지 않으므로 그 자리에서 라운드가 멈춘다.
  local waited=0 alive p
  while :; do
    alive=0
    for p in "${pids[@]}"; do kill -0 "$p" 2>/dev/null && alive=1; done
    [ "$alive" = "0" ] && break
    if [ "$waited" -ge $(( $2 + 120 )) ]; then
      for p in "${pids[@]}"; do kill "$p" 2>/dev/null; done
      log "🔴 writer 가 상한을 넘겼다 — 강제 종료. 이 판의 처리량은 못 쓴다"
      break
    fi
    sleep 2; waited=$(( waited + 2 ))
  done
  for p in "${pids[@]}"; do wait "$p" 2>/dev/null; done
}

# ── 집계 ─────────────────────────────────────────────────────────────────
# 커밋 지연 분위수. 「재봤더니 0」과 「재지 못했다」를 가르려고 표본 수를 같이 낸다.
commit_stats() {  # $1 = conn_*.tsv 가 있는 디렉터리 → "n tps p50 p95 p99 max"
  local dur=$2
  cat "$1"/conn_*.tsv 2>/dev/null | awk -F'\t' 'NF>=3 && $3 ~ /^[0-9]+$/ {print $3}' | sort -n | awk -v dur="$dur" '
    {a[NR]=$1}
    END{
      if(NR==0){ print "0 - - - - -"; exit }
      printf "%d %.1f %d %d %d %d\n", NR, NR/dur, a[int(NR*0.5)+1], a[int(NR*0.95)+1], a[int(NR*0.99)+1], a[NR];
    }'
}

# 🔴 `asort` 를 쓰지 않는다 — gawk 전용이라 박스에 따라 조용히 «0건» 을 뱉는다.
#    정렬은 sort(1) 에 맡기고 awk 는 세기만 한다.
_pctl() {  # stdin = 숫자들 → "n p50 p95 max" (없으면 "0 - - -")
  sort -n | awk '{a[NR]=$1}
    END{ if(NR==0){ print "0 - - -"; exit }
         printf "%d %d %d %d\n", NR, a[int(NR*0.5)+1], a[int(NR*0.95)+1], a[NR] }'
}

lag_stats() {  # $1 = lag tsv → "n p50 p95 max sbs_p50 sbs_max"
  # 🔴 음수를 받는 정규식이다(`-?`). lag_net 은 «차» 라 음수가 정상적으로 나오고, 그것을
  #    버리면 지연이 작은 판일수록 p50 이 위로 밀린다. 버릴 것은 NA(못 읽은 표본)뿐이다.
  local net sbs
  net=$(awk -F'\t' 'NR>1 && $4 ~ /^-?[0-9]+$/ {print $4}' "$1" 2>/dev/null | _pctl)
  sbs=$(awk -F'\t' 'NR>1 && $5 ~ /^-?[0-9]+$/ {print $5}' "$1" 2>/dev/null | _pctl)
  echo "$(echo "$net" | awk '{print $1, $2, $3, $4}') $(echo "$sbs" | awk '{print $2, $4}')"
}
