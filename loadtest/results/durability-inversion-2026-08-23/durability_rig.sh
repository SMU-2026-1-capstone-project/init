#!/usr/bin/env bash
# 내구성 완화가 «더 느린» 이유 — 2×2 rig (설계: docs/decisions/durability-relaxation-inversion.md)
#
# ── 이 rig 이 답하려는 것 ───────────────────────────────────────────────────
# 從 R4 가 관측한 역전(fsync 10분의 1인데 처리량 −15% · p99 3.5배 · log_bytes +23%)의
# **원인**이다. R4 는 노브를 둘 한꺼번에 바꿔서 원인을 못 가른다 — 여기서 2×2 로 쪼갠다.
#
#     A = flush 1 / sync_binlog 1   (기본, 대조군)
#     B = flush 2 / sync_binlog 0   (R4 의 «완화»)
#     C = flush 1 / sync_binlog 0   (binlog 만 완화)
#     D = flush 2 / sync_binlog 1   (redo 만 완화)
#
# ── 🔴 이 rig 이 R4 와 다른 것 ─────────────────────────────────────────────
# R4 는 **gRPC 배치 부하**(앱 경유)였고 이것은 **MySQL 안의 저장 프로시저**다. 앱·네트워크·
# 부하기를 통째로 뺐다 — 커밋 경로의 카운터만 보려는 판이라 그게 맞다. 대신 **«R4 재현» 이라고
# 부를 수 없다.** 설계 §6-ㄹ 의 판정은 「같은 방향이 나오나」로 약해진다. 결과에 그대로 적을 것.
#
# ── 🔴 절대값 인용 금지 ────────────────────────────────────────────────────
# 이 박스는 2코어에 MySQL·백엔드·AI·다른 세션이 산다([[project_loadtest_env_constraint]]).
# rps·ms 는 **참고값**이고, 판정에 쓰는 것은 **카운터 비율**이다 — 카드 A 의 교훈이 그것이다
# («답을 낸 것은 무대가 아니라 지표다»).
#
# ── 쓰는 법 ────────────────────────────────────────────────────────────────
#   bash loadtest/results/durability-inversion-2026-08-23/durability_rig.sh
#   OUT=<디렉터리> CONNS=4 DUR=30 REPEATS=4 bash ... (기본값은 아래)
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
OUT=${OUT:-$HERE}
CTN=${CTN:-shadowfit-mysql}
MYSQL_USER=${MYSQL_USER:-root}
MYSQL_PW=${MYSQL_PW:-1234}
DB=${DB:-durprobe}

CONNS=${CONNS:-4}            # 동시 쓰기 커넥션
DUR=${DUR:-30}               # 판당 부하 시간(초)
ROWS_PER_TX=${ROWS_PER_TX:-1}  # 트랜잭션당 행. 1 이면 커밋 경로가 지배한다 = 이 판의 관심사
REPEATS=${REPEATS:-4}        # 팔당 반복 (4×4 라틴 방격이면 4)
SETTLE=${SETTLE:-3}          # 판 사이 대기

LOG=$OUT/durability.tsv
SUM=$OUT/summary.md

die() { echo; echo "🔴 중단 — $*" >&2; exit 1; }
note() { echo "  $*"; }
q() { docker exec "$CTN" mysql -u"$MYSQL_USER" -p"$MYSQL_PW" -N -e "$1" 2>/dev/null; }
qv() { docker exec "$CTN" mysql -u"$MYSQL_USER" -p"$MYSQL_PW" -N -e "$1" 2>&1 | grep -vi 'warning'; }

# ── 원복 보장 ──────────────────────────────────────────────────────────────
# 🔴 이 rig 은 개발용 MySQL 의 **전역 설정을 바꾼다.** 중간에 죽으면 완화된 채로 남고,
#    그 뒤의 모든 작업이 «내가 모르는 조건» 에서 돈다. 그래서 어떤 경로로 끝나든 되돌린다.
restore_durability() {
  docker exec "$CTN" mysql -u"$MYSQL_USER" -p"$MYSQL_PW" \
    -e "SET GLOBAL innodb_flush_log_at_trx_commit=1; SET GLOBAL sync_binlog=1;" >/dev/null 2>&1 \
    && echo "  🟢 내구성 원복 (flush=1 / sync_binlog=1)" \
    || echo "  🔴 내구성 원복 실패 — 손으로 확인할 것" >&2
}
trap restore_durability EXIT INT TERM

# ── 사전 확인 ──────────────────────────────────────────────────────────────
preflight() {
  echo "──── 사전 확인 ────"
  docker inspect "$CTN" >/dev/null 2>&1 || die "컨테이너 '$CTN' 이 없다 (CTN 으로 이름을 줄 것)"
  [ "$(qv 'SELECT 1;' | tr -d '[:space:]')" = "1" ] || die "MySQL 에 질의할 수 없다 — 자격증명(MYSQL_USER/MYSQL_PW)을 볼 것"

  # 🔴 log_bin 이 꺼져 있으면 sync_binlog 팔이 **아무것도 안 바꾼다.** 그러면 C·B 가
  #    A·D 와 같은 조건이 되고, 표는 「차이 없음」으로 멀쩡해 보인다.
  local lb; lb=$(q "SELECT @@log_bin;" | tr -d '[:space:]')
  [ "$lb" = "1" ] || die "log_bin=$lb — 바이너리 로그가 꺼져 있으면 sync_binlog 팔이 무의미하다"

  # SET GLOBAL 권한 — #275 가 두 번 죽은 자리다. 미리 한 번 왕복해 본다.
  q "SET GLOBAL sync_binlog=1;" >/dev/null 2>&1 || die "SET GLOBAL 권한이 없다 (#275 와 같은 자리)"

  note "MySQL $(q 'SELECT VERSION();' | tr -d '[:space:]') · log_bin=1 · SET GLOBAL 가능"
  note "박스 $(nproc 2>/dev/null || echo '?') vCPU — 🔴 rps·ms 는 참고값이다"
}

# ── 부하 객체 ──────────────────────────────────────────────────────────────
# 앱을 안 쓴다. MySQL 안에서 도는 프로시저가 «커밋을 만드는 일» 만 한다 — 네트워크·직렬화·
# 커넥션 풀이 빠지므로 카운터가 커밋 경로만 반영한다.
setup_objects() {
  echo "──── 부하 객체 ────"
  q "CREATE DATABASE IF NOT EXISTS $DB;" || die "스키마 생성 실패"
  q "DROP TABLE IF EXISTS $DB.w;
     CREATE TABLE $DB.w (
       id BIGINT AUTO_INCREMENT PRIMARY KEY,
       conn_id INT NOT NULL,
       payload VARCHAR(255) NOT NULL,
       created_at DATETIME(6) NOT NULL
     ) ENGINE=InnoDB;" || die "테이블 생성 실패"

  docker exec -i "$CTN" mysql -u"$MYSQL_USER" -p"$MYSQL_PW" "$DB" 2>/dev/null <<SQL || die "프로시저 생성 실패"
DROP PROCEDURE IF EXISTS load_run;
DELIMITER //
CREATE PROCEDURE load_run(IN p_conn INT, IN p_secs INT, IN p_rows INT)
BEGIN
  DECLARE v_end DATETIME(6);
  DECLARE i INT;
  -- SYSDATE(6) 다. NOW() 는 문장 시작 시각이라 루프 안에서 얼어붙을 수 있고,
  -- 그러면 WHILE 이 안 끝난다 (복제 rig 이 같은 자리에서 겪은 것).
  SET v_end = SYSDATE(6) + INTERVAL p_secs SECOND;
  WHILE SYSDATE(6) < v_end DO
    SET i = 0;
    START TRANSACTION;
      WHILE i < p_rows DO
        INSERT INTO w (conn_id, payload, created_at)
          VALUES (p_conn, REPEAT('x', 200), SYSDATE(6));
        SET i = i + 1;
      END WHILE;
    COMMIT;
  END WHILE;
END //
DELIMITER ;
SQL
  note "프로시저 load_run 준비됨 — 커넥션 $CONNS · 트랜잭션당 ${ROWS_PER_TX}행 · 판당 ${DUR}초"
}

# ── 내구성 ─────────────────────────────────────────────────────────────────
# «설정했다» 와 «설정됐다» 는 다르다. 이 둘이 조작 변수 그 자체라 확인 없이 지나가면
# «완화했다고 믿고 기본값을 잰» 판이 표에 들어간다 (commit-count rig 의 교훈).
set_durability() {  # $1=flush $2=sync_binlog
  q "SET GLOBAL innodb_flush_log_at_trx_commit=$1; SET GLOBAL sync_binlog=$2;" \
    || die "내구성 변경 실패 (flush=$1 sync_binlog=$2)"
  local got; got=$(q "SELECT CONCAT(@@innodb_flush_log_at_trx_commit,' ',@@sync_binlog);" | tr -d '\r')
  [ "$got" = "$1 $2" ] || die "내구성이 반영되지 않았다 — 원한 값 '$1 $2', 실제 '$got'"
}

arm_flush()  { case $1 in A|C) echo 1 ;; B|D) echo 2 ;; esac; }
arm_binlog() { case $1 in A|D) echo 1 ;; B|C) echo 0 ;; esac; }

# ── 카운터 ─────────────────────────────────────────────────────────────────
# 🔴 Binlog_commits / Binlog_group_commits 는 **MariaDB 상태변수라 여기 없다**(설계 §5).
#    그래서 그룹 커밋은 InnoDB redo 쪽 비율로 추론한다.
counters() {  # stdout: commit lwr lw written fsync waits bcache bcdisk
  q "SELECT CONCAT_WS(' ',
       MAX(CASE WHEN VARIABLE_NAME='HANDLER_COMMIT'             THEN VARIABLE_VALUE END),
       MAX(CASE WHEN VARIABLE_NAME='INNODB_LOG_WRITE_REQUESTS'  THEN VARIABLE_VALUE END),
       MAX(CASE WHEN VARIABLE_NAME='INNODB_LOG_WRITES'          THEN VARIABLE_VALUE END),
       MAX(CASE WHEN VARIABLE_NAME='INNODB_OS_LOG_WRITTEN'      THEN VARIABLE_VALUE END),
       MAX(CASE WHEN VARIABLE_NAME='INNODB_OS_LOG_FSYNCS'       THEN VARIABLE_VALUE END),
       MAX(CASE WHEN VARIABLE_NAME='INNODB_LOG_WAITS'           THEN VARIABLE_VALUE END),
       MAX(CASE WHEN VARIABLE_NAME='BINLOG_CACHE_USE'           THEN VARIABLE_VALUE END),
       MAX(CASE WHEN VARIABLE_NAME='BINLOG_CACHE_DISK_USE'      THEN VARIABLE_VALUE END))
     FROM performance_schema.global_status;" | tr -d '\r'
}

# ── 한 판 ──────────────────────────────────────────────────────────────────
run_round() {  # $1=팔 $2=판번호 $3=discard(0/1)
  local arm=$1 n=$2 discard=$3
  local f b; f=$(arm_flush "$arm"); b=$(arm_binlog "$arm")

  set_durability "$f" "$b"
  # 🔴 리셋은 카운터를 읽기 **전에** 한다. TRUNCATE 자체가 로그를 쓰므로 판에 섞이면 안 된다.
  q "TRUNCATE TABLE $DB.w;" >/dev/null || die "리셋 실패"
  sleep 1

  local c0 c1 t0 t1
  c0=$(counters); t0=$(date +%s%3N)
  local pids=()
  for ((c=1; c<=CONNS; c++)); do
    docker exec "$CTN" mysql -u"$MYSQL_USER" -p"$MYSQL_PW" "$DB" \
      -e "CALL load_run($c, $DUR, $ROWS_PER_TX);" >/dev/null 2>&1 &
    pids+=($!)
  done
  for p in "${pids[@]}"; do wait "$p" || true; done
  t1=$(date +%s%3N); c1=$(counters)

  local rows; rows=$(q "SELECT COUNT(*) FROM $DB.w;" | tr -d '[:space:]')
  local ms=$((t1-t0))

  python - "$LOG" "$arm" "$n" "$discard" "$f" "$b" "$ms" "$rows" "$c0" "$c1" <<'PY'
import sys
log, arm, n, discard, f, b, ms, rows = sys.argv[1:9]
c0 = list(map(int, sys.argv[9].split()))
c1 = list(map(int, sys.argv[10].split()))
d = [y - x for x, y in zip(c0, c1)]
commit, lwr, lw, written, fsync, waits, bcache, bcdisk = d
ms, rows = int(ms), int(rows)
sec = ms / 1000.0
def r(a, b, nd=2):
    return round(a / b, nd) if b else None
row = [arm, n, discard, f, b, ms, rows,
       commit, lwr, lw, written, fsync, waits, bcache, bcdisk,
       r(commit, sec, 1),            # commits/s (참고값)
       r(lwr, lw),                   # 🔑 배칭 계수
       r(written, lw, 1),            # 🔑 쓰기당 바이트
       r(fsync, commit, 4)]          # fsync / commit
open(log, "a", encoding="utf-8").write("\t".join("" if v is None else str(v) for v in row) + "\n")
mark = " (버림)" if discard == "1" else ""
print(f"  {arm}{mark} f={f} sb={b} · 커밋 {commit:,} · 배칭 {r(lwr,lw)} · 쓰기당 {r(written,lw,1)}B "
      f"· fsync/commit {r(fsync,commit,4)} · {r(commit,sec,1)}/s")
PY
  sleep "$SETTLE"
}

# ── 본체 ───────────────────────────────────────────────────────────────────
mkdir -p "$OUT"
preflight
setup_objects

printf 'arm\tn\tdiscard\tflush\tsync_binlog\tms\trows\tcommit\tlog_write_req\tlog_writes\tos_log_written\tfsyncs\tlog_waits\tbinlog_cache\tbinlog_cache_disk\tcommits_s\tbatching\tbytes_per_write\tfsync_per_commit\n' > "$LOG"

# 4×4 라틴 방격 + 버림 1. 각 팔이 각 위치에 정확히 한 번 온다 —
# 판 순서가 팔에 얹히면 안 된다([[feedback_measure_design_needs_repeats]]).
SQUARE=("A B C D" "B C D A" "C D A B" "D A B C")

echo; echo "──── 버림판 ────"
run_round A 0 1

i=0
if [ -n "${PLAN:-}" ]; then
  # 🔴 PLAN 은 «본 라운드» 가 아니다. 방격을 우회하므로 위치 균형이 없다 —
  #    산포 측정(A 팔만 반복)처럼 **팔 간 비교를 안 하는** 용도로만 쓸 것.
  echo; echo "──── PLAN 모드 : $PLAN ────"
  echo "  🔴 라틴 방격을 안 쓴다 — 팔 간 비교에는 쓰지 말 것"
  for arm in ${PLAN//,/ }; do
    i=$((i+1))
    run_round "$arm" "$i" 0
  done
else
  for ((r=0; r<REPEATS && r<${#SQUARE[@]}; r++)); do
    echo; echo "──── 블록 $((r+1)) : ${SQUARE[$r]} ────"
    for arm in ${SQUARE[$r]}; do
      i=$((i+1))
      run_round "$arm" "$i" 0
    done
  done
fi

echo; echo "──── 요약 ────"
python - "$LOG" "$SUM" <<'PY'
import sys, statistics as st
from collections import defaultdict
log, out = sys.argv[1:3]
rows = [l.rstrip("\n").split("\t") for l in open(log, encoding="utf-8")][1:]
g = defaultdict(list)
for r in rows:
    if r[2] == "1":       # 버림판
        continue
    g[r[0]].append(r)
H = ["팔", "n", "커밋/s(참고)", "🔑 배칭 계수", "🔑 쓰기당 바이트", "fsync/commit", "총 바이트", "log_waits"]
lines = ["| " + " | ".join(H) + " |", "|" + "---|" * len(H)]
base = None
for arm in sorted(g):
    v = g[arm]
    def col(i, f=float): return [f(x[i]) for x in v if x[i] != ""]
    cps, bat, bpw, fpc = col(15), col(16), col(17), col(18)
    tot, waits = col(10, int), col(12, int)
    med = lambda a: round(st.median(a), 3) if a else None
    if arm == "A":
        base = med(cps)
    lines.append("| %s | %d | %s | **%s** | **%s** | %s | %s | %s |" % (
        arm, len(v), med(cps), med(bat), med(bpw), med(fpc),
        f"{int(st.median(tot)):,}", int(st.median(waits))))
# A 팔 판 간 산포 — 판정선의 기준선이다(임의의 % 를 안 쓴다)
a = [float(x[15]) for x in g.get("A", []) if x[15] != ""]
spread = (max(a) - min(a)) / st.mean(a) * 100 if len(a) > 1 else None
txt = "\n".join(lines)
if spread is None:
    # 🔴 판이 하나면 산포가 없다 — 그러면 판정선의 기준선 자체가 없다. 표는 내되 그 사실을 적는다.
    txt += "\n\n🔴 **A 팔 판이 1개라 판 간 폭을 못 낸다** — 기준선이 없으므로 이 표로는 판정 불가다(설계 §6).\n"
else:
    txt += f"\n\n**A 팔 판 간 폭 = {spread:.1f}%** — 판정선의 기준선이다(설계 §6).\n"
if base:
    txt += "\n| 팔 | A 대비 커밋/s |\n|---|---:|\n"
    for arm in sorted(g):
        v = [float(x[15]) for x in g[arm] if x[15] != ""]
        if v:
            txt += f"| {arm} | {(st.median(v)-base)/base*100:+.1f}% |\n"
txt += ("\n🔴 **커밋/s 는 참고값이다** — 이 박스는 2코어에 여럿이 산다. "
        "판정에 쓰는 것은 배칭 계수·쓰기당 바이트다.\n")
open(out, "w", encoding="utf-8").write(txt)
print(txt)
PY
echo "결과: $LOG · $SUM"
