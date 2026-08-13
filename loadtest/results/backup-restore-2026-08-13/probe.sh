#!/bin/bash
# 사전 확인 — 「전제가 서는가」만 본다. 여기서 나온 값은 측정치가 아니다.
#
# 설계: docs/decisions/backup-restore-rto-rpo.md §2(사전 확인)·§9-1 ④(게이트 G1~G4)
#
# 🔴 **이 넷이 전부 통과해야 EC2 로 올린다.** 하나라도 실패하면 안 올린다.
#    §5 가 「로컬에서 절차가 안 서면 EC2 에 올릴 이유가 없다」고 한 것을 실행 가능하게 편 것이다.
#    특히 G3(PITR)은 **이진 사실**이라 로컬에서 안 되면 EC2 에서도 안 된다 — 비싼 기계에서
#    「안 되네」를 확인하는 것이 08-08 에 한 번 낸 종류의 손해다.
#
# 🔴 **실 DB(shadowfit)를 건드리지 않는다.** scratch DB `backup_lab` 에서만 논다
#    (③ lock_lab · ④ mvcc_lab 관례). 이 repo 에는 세션이 둘 이상 붙고 백엔드가 떠 있을 수
#    있어서, 「내 실험이 남의 스택을 깼다」가 실재하는 위험이다.
#
# 사용:
#   bash probe.sh                    # 전부
#   GATES=G1,G2 bash probe.sh        # 일부만

set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

CONTAINER=${CONTAINER:-shadowfit-mysql}
PW=${PW:-1234}
LAB=${LAB:-backup_lab}                       # scratch DB. 실 DB 와 이름이 겹치면 안 된다
LAB_ROWS=${LAB_ROWS:-20000}                  # 게이트는 「되는가」라 작아도 된다
GATES=${GATES:-G1,G2,G3,G4}

# 복구용 별도 컨테이너 — §3 의 안전 규칙("복구는 반드시 별도 데이터 디렉터리")
RESTORE_CONTAINER=${RESTORE_CONTAINER:-shadowfit-mysql-restore}
RESTORE_VOLUME=${RESTORE_VOLUME:-backup_lab_restore_data}
MYSQL_IMAGE=${MYSQL_IMAGE:-mysql:8.0}

# 🔴 /tmp 는 Docker Desktop VM 안이라 좁다 — G2 첫 시도가 여기서 `No space left on device`
#    로 죽었다. 기본값을 repo 옆(호스트 디스크)으로 둔다.
WORK=${WORK:-$HERE/_work}

OUT=$HERE
FAILED=()
PASSED=()

say()  { echo; echo "──── $* ────"; }
note() { echo "  $*"; }
pass() { echo "  ✅ $1"; PASSED+=("$1"); }
fail() { echo "  🔴 $1" >&2; FAILED+=("$1"); }
want() { [[ ",$GATES," == *",$1,"* ]]; }

DB()  { docker exec -i "$CONTAINER" mysql -uroot -p"$PW" "$@" 2>/dev/null; }
DBQ() { DB -N -e "$1" | tr -d '\r'; }

docker exec "$CONTAINER" mysqladmin ping -h localhost --silent >/dev/null 2>&1 \
  || { echo "🔴 $CONTAINER 가 응답하지 않는다"; exit 1; }

mkdir -p "$WORK"

# ─────────────────────────────────────────────────────────────────────────
# G1 — binlog 가 켜져 있고 PITR 에 쓸 수 있는 형태인가
#
# 🔴 `log_bin` 이 꺼져 있으면 Q4·Q5 가 통째로 성립하지 않는다. 그 경우 「PITR 불가」가
#    결과이고, 켜는 것은 별도 결정이다.
# ─────────────────────────────────────────────────────────────────────────
if want G1; then
  say "G1 — binlog 전제"
  {
    echo "# G1 — binlog 전제 ($(date -Is))"
    DB -N -e "
      SELECT 'log_bin', @@log_bin
      UNION ALL SELECT 'binlog_format', @@binlog_format
      UNION ALL SELECT 'binlog_row_image', @@binlog_row_image
      UNION ALL SELECT 'gtid_mode', @@gtid_mode
      UNION ALL SELECT 'binlog_expire_logs_seconds', @@binlog_expire_logs_seconds
      UNION ALL SELECT 'server_id', @@server_id
      UNION ALL SELECT 'version', @@version;"
  } | tee "$OUT/G1_binlog.txt" | sed 's/^/  /'

  LOG_BIN=$(DBQ "SELECT @@log_bin;")
  BF=$(DBQ "SELECT @@binlog_format;")
  GTID=$(DBQ "SELECT @@gtid_mode;")

  [ "$LOG_BIN" = "1" ] && pass "G1a log_bin 켜짐" || fail "G1a log_bin 이 꺼져 있다 — Q4·Q5 성립 불가"
  [ "$BF" = "ROW" ] && pass "G1b binlog_format=ROW" || fail "G1b binlog_format=$BF (ROW 여야 PITR 이 안전하다)"

  # GTID 는 «실패» 가 아니라 «절차가 갈리는» 항목이다. 꺼져 있으면 포지션 기반으로 간다.
  if [ "$GTID" = "ON" ]; then
    note "GTID 모드 — PITR 을 GTID 기반으로 짠다"
  else
    note "⚠️ gtid_mode=$GTID — **PITR 은 포지션 기반**(파일명+오프셋)이다. G3 가 그 절차를 밟는다"
  fi
  note "RPO 상한 = binlog 보존 $(DBQ 'SELECT @@binlog_expire_logs_seconds;')초"
fi

# ─────────────────────────────────────────────────────────────────────────
# G2 — XtraBackup 이 이 서버에 붙는가 (팔 B 의 존재 조건)
#
# 못 붙으면 그것이 결과다 — 「물리 백업 도구는 서버 버전에 묶인다」도 산출물이고,
# 그때 팔 B 는 MySQL Shell 로 교체하되 **Q2 를 «미답» 으로 박제**한다(§9-1 ①).
# ─────────────────────────────────────────────────────────────────────────
if want G2; then
  say "G2 — XtraBackup ↔ 서버 버전"
  XB_IMAGE=${XB_IMAGE:-percona/percona-xtrabackup:8.0}
  docker pull -q "$XB_IMAGE" >/dev/null 2>&1
  XB_VER=$(docker run --rm "$XB_IMAGE" xtrabackup --version 2>&1 | grep -o 'version [0-9.-]*' | head -1)
  SRV_VER=$(DBQ "SELECT @@version;")
  note "도구 $XB_VER ↔ 서버 $SRV_VER"

  DATADIR_VOL=$(docker inspect "$CONTAINER" \
    --format '{{range .Mounts}}{{if eq .Destination "/var/lib/mysql"}}{{.Name}}{{end}}{{end}}')
  [ -n "$DATADIR_VOL" ] || fail "G2 datadir 볼륨을 못 찾았다"

  rm -rf "$WORK/xb" && mkdir -p "$WORK/xb"
  # `--user 0` — percona 이미지의 uid 와 mysql:8.0 datadir 소유자가 다르다(errno 13).
  # `:ro` — 백업은 읽기다. 원본을 못 건드리게 마운트에서 막는다.
  MSYS_NO_PATHCONV=1 docker run --rm --user 0 --network "container:$CONTAINER" \
    -v "$DATADIR_VOL:/var/lib/mysql:ro" -v "$WORK/xb:/backup" \
    "$XB_IMAGE" xtrabackup --backup --target-dir=/backup --datadir=/var/lib/mysql \
    --databases="$LAB" --host=127.0.0.1 --user=root --password="$PW" \
    > "$OUT/G2_xtrabackup.log" 2>&1
  XB_RC=$?

  if grep -q "completed OK" "$OUT/G2_xtrabackup.log"; then
    pass "G2 XtraBackup 이 붙고 백업이 완료됐다"
  elif grep -qi "unsupported server version\|not supported\|version.*mismatch" "$OUT/G2_xtrabackup.log"; then
    fail "G2 버전 불일치로 거절됨 — 팔 B 를 MySQL Shell 로 교체하고 Q2 를 «미답» 으로 박제"
  else
    fail "G2 실패(rc=$XB_RC) — 로그를 볼 것: G2_xtrabackup.log"
  fi

  # 🔴 도구가 자기 검사를 건너뛰었는지 본다. 「붙는다」와 「검증됐다」는 다르다.
  if grep -q "Skipping the version check" "$OUT/G2_xtrabackup.log"; then
    note "⚠️ **도구가 버전 검사를 건너뛰었다**(perl 없음). 붙었다는 것이 호환을 뜻하지 않는다"
    note "   → 유효성의 진짜 판정은 **복구해서 행수·체크섬이 맞는가**다. 조건으로 남길 것"
  fi
fi

# ─────────────────────────────────────────────────────────────────────────
# G3 — PITR 이 실제로 되는가  ⭐ 이 문서의 급소
#
# 「binlog 켜져 있으니 된다」는 지금 **권고문**이다. 사고 직전 시각으로 실제 복원해 봐야 안다.
# 시나리오: T0 백업 → T1 계속 쓰기 → T2 «사고»(대량 DELETE) → T3 복원 + binlog 재생 → 검증
#
# 🔴 복구는 **별도 컨테이너**에 한다(§3 안전 규칙). 원본에 덮어쓰는 복구는 실험이 아니라 사고다.
# ─────────────────────────────────────────────────────────────────────────
if want G3; then
  say "G3 — PITR (사고 직전으로 되돌리기)"

  note "무대 준비 — $LAB.t 에 $LAB_ROWS 행"
  DB -e "DROP DATABASE IF EXISTS $LAB; CREATE DATABASE $LAB;"
  DB "$LAB" -e "
    CREATE TABLE t (
      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
      payload VARCHAR(200) NOT NULL,
      created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ) ENGINE=InnoDB;
    INSERT INTO t (payload)
    SELECT CONCAT('seed-', n) FROM (
      SELECT a.N + b.N*10 + c.N*100 + d.N*1000 + e.N*10000 AS n
      FROM (SELECT 0 N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
            UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
           (SELECT 0 N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
            UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
           (SELECT 0 N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
            UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
           (SELECT 0 N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
            UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d,
           (SELECT 0 N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
            UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
    ) nums WHERE n < $LAB_ROWS;"
  N_SEED=$(DBQ "SELECT COUNT(*) FROM $LAB.t;")
  note "시드 $N_SEED 행"

  # ── T0. 백업. `--source-data=2` 가 덤프 머리에 binlog 파일·포지션을 주석으로 박는다.
  #        gtid_mode=OFF 라 **이 포지션이 재생 시작점**이다(G1 이 확인한 갈림).
  note "T0 — mysqldump (--single-transaction --source-data=2)"
  docker exec "$CONTAINER" mysqldump -uroot -p"$PW" \
    --single-transaction --source-data=2 --databases "$LAB" \
    > "$WORK/pitr.sql" 2>/dev/null
  START_LINE=$(grep -m1 "^-- CHANGE MASTER TO\|^-- CHANGE REPLICATION SOURCE TO" "$WORK/pitr.sql")
  BINFILE=$(echo "$START_LINE" | grep -o "SOURCE_LOG_FILE='[^']*'\|MASTER_LOG_FILE='[^']*'" | cut -d"'" -f2)
  BINPOS=$(echo "$START_LINE" | grep -o "SOURCE_LOG_POS=[0-9]*\|MASTER_LOG_POS=[0-9]*" | grep -o '[0-9]*')
  note "백업 시점: $BINFILE @ $BINPOS"
  [ -n "$BINFILE" ] || fail "G3 덤프에서 binlog 포지션을 못 읽었다"

  # ── T1. 계속 쓴다. 각 행에 시각이 남는다.
  note "T1 — 추가 쓰기 (사고 전 데이터)"
  DB "$LAB" -e "INSERT INTO t (payload) SELECT CONCAT('after-backup-', id) FROM t LIMIT 500;"
  N_BEFORE=$(DBQ "SELECT COUNT(*) FROM $LAB.t;")
  sleep 1.2                                   # 초 단위 stop-datetime 경계를 확실히 가르려고
  # 🔴 **UTC 로 주면 안 된다.** `mysqlbinlog --stop-datetime` 은 그 값을 **로컬 타임존으로
  #    해석**한다. 서버가 UTC 로 시각을 남긴다고 UTC 를 주면 KST 기준 9시간 과거로 읽혀
  #    **모든 이벤트가 잘리고, 에러 없이 빈 결과가 나온다.** 첫 시도가 정확히 여기서 죽었다
  #    (replay.sql 0바이트). 「조용히 틀리는」 함정이라 운영에서 제일 위험한 종류다.
  T2=$(docker exec "$CONTAINER" date '+%Y-%m-%d %H:%M:%S')
  T2_UTC=$(docker exec "$CONTAINER" date -u '+%Y-%m-%d %H:%M:%S')
  sleep 1.2

  # ── T2. 사고.
  note "T2 — «사고» 대량 DELETE ($T2 UTC 이후)"
  DB "$LAB" -e "DELETE FROM t;"
  N_AFTER=$(DBQ "SELECT COUNT(*) FROM $LAB.t;")
  note "사고 전 $N_BEFORE 행 → 사고 후 $N_AFTER 행"
  DB -e "FLUSH BINARY LOGS;"                  # 재생 대상 로그를 닫아 둔다

  # ── T3. 별도 컨테이너에 복원 + binlog 재생.
  note "T3 — 별도 컨테이너($RESTORE_CONTAINER)에 복원"
  docker rm -f "$RESTORE_CONTAINER" >/dev/null 2>&1
  docker volume rm -f "$RESTORE_VOLUME" >/dev/null 2>&1
  docker run -d --name "$RESTORE_CONTAINER" \
    -e MYSQL_ROOT_PASSWORD="$PW" -v "$RESTORE_VOLUME:/var/lib/mysql" \
    "$MYSQL_IMAGE" >/dev/null 2>&1 || fail "G3 복구 컨테이너를 못 띄웠다"

  # 🔴 `mysqladmin ping` 으로 기다리면 안 된다. MySQL 엔트리포인트는 **초기화 중 임시 서버**를
  #    띄우는데 ping 이 거기에 붙어 «떴다» 고 오판한다. 그 뒤 서버가 재시작하면서 이어지는
  #    명령이 전부 빈 값으로 돌아온다 — 첫 시도가 정확히 이렇게 죽었다.
  #    **실제 쿼리가 성공할 때까지** 기다린다.
  RESTORE_READY=0
  for _ in $(seq 1 90); do
    if docker exec "$RESTORE_CONTAINER" mysql -uroot -p"$PW" -N -e "SELECT 1;" >/dev/null 2>&1; then
      RESTORE_READY=1; break
    fi
    sleep 2
  done
  [ "$RESTORE_READY" = "1" ] || fail "G3 복구 컨테이너가 3분 안에 쿼리를 받지 않았다"

  docker exec -i "$RESTORE_CONTAINER" mysql -uroot -p"$PW" < "$WORK/pitr.sql" 2>/dev/null
  N_RESTORED=$(docker exec "$RESTORE_CONTAINER" mysql -uroot -p"$PW" -N -e \
    "SELECT COUNT(*) FROM $LAB.t;" 2>/dev/null | tr -d '\r')
  note "덤프만 복원: $N_RESTORED 행 (백업 시점이라 T1 분량이 빠져 있어야 정상)"

  # binlog 를 «사고 직전» 까지만 재생한다. 이 --stop-datetime 이 PITR 의 핵심 손잡이다.
  # 🔴 **`mysqlbinlog` 은 `mysql:8.0` 공식 이미지에 없다.** `mysql`·`mysqladmin`·`mysqldump`·
  #    `mysqlsh` 는 있는데 그것만 빠져 있다(2026-08-13 확인). 즉 **binlog 은 켜져 있어도
  #    그걸 읽을 도구가 없다** — 「binlog 켜져 있으니 PITR 된다」가 이 환경에서 거짓인 이유다.
  #    percona 이미지에는 있으므로 datadir 를 읽기 전용으로 물려 거기서 돌린다.
  #
  # 🔴 그리고 **타임존을 UTC 로 못박는다.** `--stop-datetime` 은 «mysqlbinlog 를 돌리는
  #    프로세스» 의 로컬 시각으로 해석된다 — 서버 시각도, 이벤트 저장 시각도 아니다.
  #    이 헬퍼는 UTC 이므로 UTC 경계($T2_UTC)를 준다. 어긋나면 **에러 없이 빈 결과**가 나온다.
  XB_IMAGE=${XB_IMAGE:-percona/percona-xtrabackup:8.0}
  DATADIR_VOL=${DATADIR_VOL:-$(docker inspect "$CONTAINER" \
    --format '{{range .Mounts}}{{if eq .Destination "/var/lib/mysql"}}{{.Name}}{{end}}{{end}}')}
  MSYS_NO_PATHCONV=1 docker run --rm --user 0 -e TZ=UTC \
    -v "$DATADIR_VOL:/var/lib/mysql:ro" "$XB_IMAGE" \
    mysqlbinlog --start-position="$BINPOS" --stop-datetime="$T2_UTC" \
    "/var/lib/mysql/$BINFILE" > "$WORK/replay.sql" 2>"$WORK/replay.err"
  REPLAY_BYTES=$(wc -c < "$WORK/replay.sql" | tr -d ' ')
  note "재생 SQL: ${REPLAY_BYTES}바이트 (경계 $T2_UTC UTC — 헬퍼 TZ 도 UTC 로 고정)"
  # 빈 재생본은 «사고 이전 상태가 맞다» 와 구분이 안 된다. 여기서 먼저 걸러야
  # 「PITR 이 안 된다」로 오진하지 않는다.
  [ "$REPLAY_BYTES" -gt 0 ] || fail "G3 재생 SQL 이 비었다 — 시각 경계가 타임존 때문에 어긋났을 수 있다"
  docker exec -i "$RESTORE_CONTAINER" mysql -uroot -p"$PW" < "$WORK/replay.sql" 2>"$WORK/replay_apply.err"
  N_PITR=$(docker exec "$RESTORE_CONTAINER" mysql -uroot -p"$PW" -N -e \
    "SELECT COUNT(*) FROM $LAB.t;" 2>/dev/null | tr -d '\r')

  {
    echo "# G3 — PITR ($(date -Is))"
    echo "백업 시점       : $BINFILE @ $BINPOS"
    echo "사고 경계(T2)   : $T2 (서버 로컬) = $T2_UTC (UTC, 재생에 쓴 값)"
    echo "시드            : $N_SEED"
    echo "사고 직전       : $N_BEFORE"
    echo "사고 후(원본)   : $N_AFTER"
    echo "덤프만 복원     : $N_RESTORED"
    echo "PITR 후         : $N_PITR"
  } | tee "$OUT/G3_pitr.txt" | sed 's/^/  /'

  # 🔴 **환경 실패와 PITR 실패를 가른다.** 이 구분이 없으면 「디스크가 찼다」가 「PITR 이
  #    안 된다」로 찍힌다 — 이 repo 가 08-08 부터 반복해서 경계하는 그 오류다.
  #    행수는 숫자여야 한다. 숫자가 아니면 복구 경로가 애초에 안 돌았다는 뜻이다.
  if ! [[ "$N_RESTORED" =~ ^[0-9]+$ ]] || ! [[ "$N_PITR" =~ ^[0-9]+$ ]]; then
    fail "G3 판정 불가 — **환경 실패**(복구 컨테이너가 못 돌았다). PITR 의 가부는 아직 모른다"
    note "복원=[$N_RESTORED] PITR=[$N_PITR] — 디스크·컨테이너를 먼저 볼 것"
  elif [ "$N_PITR" = "$N_BEFORE" ]; then
    pass "G3 PITR 성공 — 사고 직전 $N_BEFORE 행으로 정확히 되돌렸다"
  elif [ "$N_PITR" = "$N_RESTORED" ]; then
    fail "G3 binlog 재생이 아무것도 안 걸렸다 ($N_PITR = 덤프 복원분) — 포지션/시각 경계를 볼 것"
  else
    fail "G3 PITR 결과가 사고 직전과 다르다 ($N_PITR ≠ $N_BEFORE) — **결함이면 이슈**"
  fi

  docker rm -f "$RESTORE_CONTAINER" >/dev/null 2>&1
  docker volume rm -f "$RESTORE_VOLUME" >/dev/null 2>&1
fi

# ─────────────────────────────────────────────────────────────────────────
# G4 — 계측이 «정지» 를 실제로 잡는가 (팔 C = 양성 대조군)
#
# ⭐ H1 은 「`--single-transaction` 은 사실상 안 멈춘다」인데, **「안 멈춘다」는 관측은
#    「계측이 정지를 못 잡았다」와 구분되지 않는다.** 명백히 잠그는 팔이 같은 rig 에 있어야
#    그 구분이 선다. `FLUSH TABLES ... FOR EXPORT` 가 그 역할이다.
# ─────────────────────────────────────────────────────────────────────────
if want G4; then
  say "G4 — 잠금이 계측에 잡히는가 (팔 C 양성 대조)"
  DBQ "SELECT 1 FROM information_schema.tables
       WHERE table_schema='$LAB' AND table_name='t';" | grep -q 1 \
    || DB -e "CREATE DATABASE IF NOT EXISTS $LAB;
              CREATE TABLE IF NOT EXISTS $LAB.t (
                id BIGINT AUTO_INCREMENT PRIMARY KEY, payload VARCHAR(200),
                created_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3));
              INSERT INTO $LAB.t (payload) VALUES ('x');"

  HOLD=3
  # 잠금을 HOLD 초 잡는 세션
  docker exec -d "$CONTAINER" mysql -uroot -p"$PW" "$LAB" \
    -e "FLUSH TABLES t FOR EXPORT; SELECT SLEEP($HOLD); UNLOCK TABLES;" >/dev/null 2>&1
  sleep 0.7

  # 그동안 쓰기를 시도해 «걸리는 시간» 을 잰다
  W0=$(date +%s%3N)
  DB "$LAB" -e "INSERT INTO t (payload) VALUES ('probe-under-lock');" >/dev/null 2>&1
  W1=$(date +%s%3N)
  STALL=$(( W1 - W0 ))

  {
    echo "# G4 — 잠금 관측 ($(date -Is))"
    echo "잠금 유지(초)     : $HOLD"
    echo "쓰기 대기(ms)     : $STALL"
  } | tee "$OUT/G4_lock_observability.txt" | sed 's/^/  /'

  # 잠금이 3초인데 쓰기가 즉시 통과하면 계측이 못 잡은 것이다.
  if [ "$STALL" -ge 1000 ]; then
    pass "G4 계측이 정지를 잡는다 (${STALL}ms 대기) — 팔 A 의 «안 멈춤» 을 믿을 수 있다"
  else
    fail "G4 잠금 중인데 쓰기가 ${STALL}ms 에 통과했다 — 계측이 정지를 못 잡는다"
  fi
  sleep "$HOLD"
fi

# ── 요약 ─────────────────────────────────────────────────────────────────
say "게이트 요약"
for p in "${PASSED[@]:-}"; do [ -n "$p" ] && echo "  ✅ $p"; done
for f in "${FAILED[@]:-}"; do [ -n "$f" ] && echo "  🔴 $f"; done
echo
if [ "${#FAILED[@]}" -eq 0 ]; then
  echo "  전부 통과 — EC2 승격 조건이 섰다 (설계 §9-1 ④)"
  exit 0
else
  echo "  🔴 ${#FAILED[@]}건 실패 — **EC2 에 올리지 않는다.** 고치고 다시 돌린다"
  exit 1
fi
