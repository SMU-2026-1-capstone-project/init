#!/bin/bash
# R4 재현 — 「내구성 완화가 왜 더 느린가」를 R4의 정확한 워크로드(gRPC 배치 · SavePoseDataBatch ·
# connections=1)로 4팔(A/B/C/D) 2×2 로 가른다.
#
# 설계: docs/decisions/durability-relaxation-inversion.md §3(2×2) · §5(카운터) · §6(판정선)
# 원본 워크로드: loadtest/results/session-spread-2026-08-13/conn_ridealong.sh
#   (R4 결과: loadtest/results/session-spread-aws-2026-08-17/R4-connection-sweep.md)
#
# 🔴 로컬 2×2(durability-inversion-2026-08-23)는 SQL 프로시저·conn4·1행/트랜잭션이었다 —
#    R4의 실제 조건(gRPC 배치 · conn=1 · rep당 5~30행/트랜잭션)과 다르다. 이 판이 그 간극을 메운다.
#
# 팔:
#   A  flush=1 sync_binlog=1   (기본, 대조군)
#   B  flush=2 sync_binlog=0   (R4의 "완화" — 역전이 관측된 그 조합)
#   C  flush=1 sync_binlog=0   (binlog만 완화)
#   D  flush=2 sync_binlog=1   (redo만 완화)
#
# 사용:
#   PEM=... DB_PUB=... APP_PUB=... LOADER_PUB=... DB_PRIV=... APP_PRIV=... \
#   TOKEN=<INTERNAL_API_TOKEN> OUT=... bash durability_r4_repro.sh
#   (DB_PUB == APP_PUB — target 1대에 MySQL+Spring 동거, 원본 R4/P5 라운드와 같은 조건)

set -uo pipefail
cd "$(dirname "$0")"

: "${PEM:?PEM 미설정}" "${DB_PUB:?}" "${APP_PUB:?}" "${LOADER_PUB:?}"
: "${DB_PRIV:?}" "${APP_PRIV:?}" "${TOKEN:?TOKEN(INTERNAL_API_TOKEN) 미설정}" "${OUT:?}"
mkdir -p "$OUT"

SESS_LO=${SESS_LO:-901}
LEVEL=${LEVEL:-50}
SESS_HI=$(( SESS_LO + LEVEL - 1 ))
C=${C:-100}
N_REQ=${N_REQ:-20000}
REPS=${REPS:-25}
DOWNSAMPLE_WINDOW=${DOWNSAMPLE_WINDOW:-5}
ROWS_PER_REQ=$(( (REPS + DOWNSAMPLE_WINDOW - 1) / DOWNSAMPLE_WINDOW ))
GHZ=${GHZ:-/usr/local/bin/ghz}
MYSQL_CTN=${MYSQL_CTN:-shadowfit-mysql}
MYSQL_USER=${MYSQL_USER:-shadowfit}
MYSQL_PW=${MYSQL_PW:-1234}
MYSQL_ROOT_PW=${MYSQL_ROOT_PW:-1234}
WARM_C=${CONN_WARM_C:-4}
WARM_SEC=${WARM_SEC:-15}

# 4팔 × 4반복, 위치 균형 (블록 4개, 블록마다 ABCD 순서를 순환)
ORDER=(A B C D  B C D A  C D A B  D A B C)

LOG="$OUT/r4repro.tsv"
KNOWN="$OUT/known_hosts"
touch "$KNOWN"; chmod 600 "$KNOWN" 2>/dev/null
SSH_OPTS=(-i "$PEM" -o "UserKnownHostsFile=$KNOWN" -o StrictHostKeyChecking=no -o ConnectTimeout=10 -o LogLevel=ERROR)
SCP_OPTS=(-i "$PEM" -o "UserKnownHostsFile=$KNOWN" -o StrictHostKeyChecking=no -o LogLevel=ERROR)

die() { echo; echo "🔴 중단 — $*" >&2; exit 1; }
rsh_db() { ssh "${SSH_OPTS[@]}" "ec2-user@$DB_PUB" "$@"; }
rsh_loader() { ssh "${SSH_OPTS[@]}" "ec2-user@$LOADER_PUB" "$@"; }

mysql_q() {  # $1 = SQL
  rsh_db "sudo docker exec $MYSQL_CTN mysql -u$MYSQL_USER -p$MYSQL_PW shadowfit -N -e \"$1\"" 2>/dev/null
}

assert_mysql_reachable() {
  local out
  out=$(rsh_db "sudo docker exec $MYSQL_CTN mysql -u$MYSQL_USER -p$MYSQL_PW shadowfit -N -e 'SELECT 1;'" 2>&1)
  [ "$(printf '%s\n' "$out" | grep -vi 'warning' | tr -d '[:space:]')" = "1" ] \
    || die "MySQL 에 질의할 수 없다 — 받은 것: '$out'"
  echo "  MySQL: 컨테이너 '$MYSQL_CTN' 응답 확인"
}

assert_sessions_exist() {
  local want=$(( SESS_HI - SESS_LO + 1 )) got
  got=$(mysql_q "SELECT COUNT(*) FROM exercise_sessions WHERE id BETWEEN $SESS_LO AND $SESS_HI;")
  [ "$got" = "$want" ] || die "세션 시드 부족 — $SESS_LO~$SESS_HI 중 '$got'/$want"
  echo "  세션 시드: $SESS_LO~$SESS_HI $want개 확인"
}

mysql_root_q() {  # $1 = SQL — SET GLOBAL 은 shadowfit 앱 유저에 SUPER 권한이 없다(#275 계열)
  rsh_db "sudo docker exec $MYSQL_CTN mysql -uroot -p$MYSQL_ROOT_PW shadowfit -N -e \"$1\"" 2>/dev/null
}

set_durability() {  # $1=flush $2=sync_binlog
  mysql_root_q "SET GLOBAL innodb_flush_log_at_trx_commit=$1; SET GLOBAL sync_binlog=$2;" \
    || die "내구성 설정 실패 (flush=$1 sync_binlog=$2)"
  local got
  got=$(mysql_q "SELECT @@innodb_flush_log_at_trx_commit, @@sync_binlog;" | tr '\t' ' ')
  [ "$got" = "$1 $2" ] || die "내구성 반영 안됨 — 원함 '$1 $2' 실제 '$got'"
  echo "  내구성: flush=$1 sync_binlog=$2 (확인됨)"
}

reset_rows() {
  mysql_q "DELETE FROM pose_data WHERE session_id BETWEEN $SESS_LO AND $SESS_HI;" \
    || die "pose_data 초기화 실패 ($1)"
}

restore_default_durability() { echo "=== 내구성 기본값 복원 ==="; set_durability 1 1; }
trap restore_default_durability EXIT

# 카운터 — 기본 셋(commits·fsyncs·log_written) + 설계 §5 확장분
#   순서: handler_commit fsyncs log_written  log_write_req log_writes  log_waits  binlog_use binlog_disk_use
counters() {
  mysql_q "SELECT
      MAX(CASE WHEN VARIABLE_NAME='HANDLER_COMMIT' THEN VARIABLE_VALUE END),
      MAX(CASE WHEN VARIABLE_NAME='INNODB_OS_LOG_FSYNCS' THEN VARIABLE_VALUE END),
      MAX(CASE WHEN VARIABLE_NAME='INNODB_OS_LOG_WRITTEN' THEN VARIABLE_VALUE END),
      MAX(CASE WHEN VARIABLE_NAME='INNODB_LOG_WRITE_REQUESTS' THEN VARIABLE_VALUE END),
      MAX(CASE WHEN VARIABLE_NAME='INNODB_LOG_WRITES' THEN VARIABLE_VALUE END),
      MAX(CASE WHEN VARIABLE_NAME='INNODB_LOG_WAITS' THEN VARIABLE_VALUE END),
      MAX(CASE WHEN VARIABLE_NAME='BINLOG_CACHE_USE' THEN VARIABLE_VALUE END),
      MAX(CASE WHEN VARIABLE_NAME='BINLOG_CACHE_DISK_USE' THEN VARIABLE_VALUE END)
    FROM performance_schema.global_status
    WHERE VARIABLE_NAME IN ('HANDLER_COMMIT','INNODB_OS_LOG_FSYNCS','INNODB_OS_LOG_WRITTEN',
      'INNODB_LOG_WRITE_REQUESTS','INNODB_LOG_WRITES','INNODB_LOG_WAITS',
      'BINLOG_CACHE_USE','BINLOG_CACHE_DISK_USE');" | tr '\t' ' '
}

init_log() {
  [ -f "$LOG" ] || printf "tag\trps\trows_s\tp50_ms\tp95_ms\tp99_ms\tfail\ttotal\tcommits\tfsyncs\tfsync_s\tlog_bytes\tlog_write_req\tlog_writes\tbatch_factor\tbytes_per_write\tlog_waits\tbinlog_use\tbinlog_disk_use\n" > "$LOG"
}

run_ghz() {  # $1=tag
  local tag="${1:?}"
  local f="${tag}.json"
  local rc
  echo "  워밍업 ${WARM_SEC}s (c=$WARM_C)"
  if ! rsh_loader "$GHZ --insecure --call ExerciseService.SavePoseDataBatch --metadata-file /tmp/meta.json \
       --data-file /tmp/r4_payload.json -c $WARM_C --connections 1 -z ${WARM_SEC}s $APP_PRIV:6565 >/dev/null 2>&1"; then
    echo "  ✗ 워밍업 ghz 실패 — $tag 버린다" >&2; return 1
  fi
  reset_rows "워밍업 직후, $tag"

  local c0 c1 t0 t1
  c0=$(counters); t0=$(date +%s)
  rsh_loader "$GHZ --insecure --call ExerciseService.SavePoseDataBatch --metadata-file /tmp/meta.json \
       --data-file /tmp/r4_payload.json -c $C --connections 1 -n $N_REQ -O json -o /tmp/$f $APP_PRIV:6565 >/dev/null 2>&1" \
    || echo "  ⚠️ ghz non-zero 종료 — 리포트로 판정" >&2
  t1=$(date +%s); c1=$(counters)

  scp "${SCP_OPTS[@]}" -q "ec2-user@$LOADER_PUB:/tmp/$f" "$OUT/$f" \
    || { echo "  ✗ 회수 실패 ($tag)" >&2; return 1; }
  reset_rows "본판 직후, $tag"

  "${PYTHON_BIN:-python}" - "$OUT/$f" "$tag" "$LOG" "$c0" "$c1" "$((t1-t0))" "$ROWS_PER_REQ" <<'PY'
import json, sys
f, tag, log, c0, c1, secs, rows_per_req = sys.argv[1:8]
try:
    j = json.load(open(f, encoding='utf-8'))
except Exception as e:
    print(f"  ✗ JSON 파싱 실패 ({f}): {e}", file=sys.stderr); raise SystemExit(2)

d = {x['percentage']: round(x['latency'] / 1e6) for x in (j.get('latencyDistribution') or [])}
sc = j.get('statusCodeDistribution') or {}
tot, rps = j.get('count', 0), round(j.get('rps', 0), 1)
ok = sc.get('OK', 0); fail = tot - ok
if tot <= 0: print(f"  ✗ 요청수 0 ({f})", file=sys.stderr); raise SystemExit(3)
if ok == 0: print(f"  ✗ 성공 0 ({f}, {tot}건 전부 실패)", file=sys.stderr); raise SystemExit(4)
if rps <= 0: print(f"  ✗ RPS 0 ({f})", file=sys.stderr); raise SystemExit(5)

def delta(i):
    try: return int(c1.split()[i]) - int(c0.split()[i])
    except (IndexError, ValueError): return -1

commits, fsyncs, log_written, log_write_req, log_writes, log_waits, binlog_use, binlog_disk = \
    delta(0), delta(1), delta(2), delta(3), delta(4), delta(5), delta(6), delta(7)
secs = int(secs) or 1
rows_sec = round(rps * float(rows_per_req), 1)
batch_factor = round(log_write_req / log_writes, 3) if log_writes > 0 else -1
bytes_per_write = round(log_written / log_writes, 1) if log_writes > 0 else -1

open(log, 'a').write(
    f"{tag}\t{rps}\t{rows_sec}\t{d.get(50,-1)}\t{d.get(95,-1)}\t{d.get(99,-1)}\t"
    f"{fail}\t{tot}\t{commits}\t{fsyncs}\t{round(fsyncs/secs,1)}\t{log_written}\t"
    f"{log_write_req}\t{log_writes}\t{batch_factor}\t{bytes_per_write}\t{log_waits}\t{binlog_use}\t{binlog_disk}\n")
print(f"  {tag}: RPS={rps} rows/s={rows_sec} p50={d.get(50)}ms p99={d.get(99)}ms fail={fail}/{tot}")
print(f"    batch_factor={batch_factor} bytes/write={bytes_per_write} log_waits={log_waits} binlog_disk_use={binlog_disk}")
PY
  rc=$?
  [ $rc -ne 0 ] && return 1
  return 0
}

echo "=== R4 재현 — DB=$DB_PUB App=$APP_PUB Loader=$LOADER_PUB ==="
assert_mysql_reachable
assert_sessions_exist
init_log

echo "=== 메타·페이로드 준비 ==="
rsh_loader "echo '{\"Authorization\": \"Bearer $TOKEN\"}' > /tmp/meta.json"
if ! rsh_loader "test -s /tmp/r4_payload.json"; then
  rsh_loader "cd /root/init 2>/dev/null || cd ~/init; python3 loadtest/ghz/gen_batch_multi.py \
    --sessions $SESS_LO-$SESS_HI --reps $REPS --out /tmp/r4_payload.json" \
    || die "페이로드 생성 실패"
fi
echo "  준비 완료"

echo "──────── 버림판 (A: flush=1 sync=1) ────────"
set_durability 1 1
run_ghz "discard_A" || true
sed -i "/^discard_A\t/d" "$LOG" 2>/dev/null
echo "  (버림판 제외)"

declare -A DUR=([A]="1 1" [B]="2 0" [C]="1 0" [D]="2 1")
FAILED=()
i=0
for arm in "${ORDER[@]}"; do
  i=$(( i + 1 ))
  tag="${arm}_r$(( (i-1)/4 + 1 ))"
  echo "──────── [$i/${#ORDER[@]}] $tag (팔 $arm) ────────"
  read -r f s <<< "${DUR[$arm]}"
  set_durability "$f" "$s"
  run_ghz "$tag" || FAILED+=("$tag")
done

echo; echo "=== 결과 ==="; cat "$LOG"
if [ ${#FAILED[@]} -gt 0 ]; then
  echo "🔴 ${#FAILED[@]}판 실패: ${FAILED[*]}" >&2
  exit 1
fi
echo "✅ ${#ORDER[@]}판 전부 유효."
