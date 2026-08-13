#!/bin/bash
# EC2 무인 측정 러너 — 단계를 순서대로 돌리고 결과를 S3 로 계속 올린다.
#
# 설계 원칙 세 개. 전부 «밤을 통째로 잃지 않는다» 로 수렴한다:
#
#   ① set -e 를 쓰지 않는다. 한 단계가 죽어도 다음 단계는 돈다. 실패는 숫자가 아니라
#      단계 표의 FAIL 로 남는다 («재봤더니 0» 과 «재지 못했다» 는 다르다 — _rig.sh 의 규약)
#   ② 주기적으로 S3 에 올린다. 5판째 죽어도 4판은 건진다. 최종 업로드만 믿지 않는다
#   ③ 각 단계에 상한 시간을 건다. 무한정 기다리다 아침을 맞는 것이 이 rig 의 실패 이력이다
#
# 사용:
#   S3_BASE=s3://버킷/프리픽스 nohup bash loadtest/aws/run_all.sh > /root/run_all.log 2>&1 &
#
# ⚠️ nohup 없이 & 만 붙이면 SSH 가 끊길 때 같이 죽는다.

set -uo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
RIG=$ROOT/loadtest/results/online-ddl-2026-08-09

# ── 설정 ─────────────────────────────────────────────────────────────────
S3_BASE=${S3_BASE:?S3_BASE 가 필요하다 — 예: s3://my-bucket/shadowfit}
RUN_ID=${RUN_ID:-ec2-$(date +%Y%m%d-%H%M%S)}
OUTDIR=${OUTDIR:-$ROOT/loadtest/results/online-ddl-$RUN_ID}
S3_DEST="${S3_BASE%/}/$RUN_ID"

PHASES=${PHASES:-"preflight rehearsal ddl ridealong collect"}
SYNC_SEC=${SYNC_SEC:-300}
AUTO_SHUTDOWN=${AUTO_SHUTDOWN:-0}

PW=${PW:-1234}
DB_NAME=${DB_NAME:-shadowfit}
CONTAINER=${CONTAINER:-shadowfit-mysql}

REHEARSAL_SESSIONS=${REHEARSAL_SESSIONS:-134}

# 🔴 기본 5,400s(90분)는 팔 B 로컬 실측 2,360s 대비 여유가 2.3배뿐이다. EBS 가 로컬 NVMe
#    보다 느려 팔 B 가 늘어나면 **writer 가 DDL 도중 먼저 죽어 max_stall·p50 이 통째로
#    구멍난다.** 측정 자체는 계속 도는데 지표만 못 쓰게 되는, 제일 나쁜 실패 모양이다.
export WRITER_MAX_SEC=${WRITER_MAX_SEC:-14400}   # 4시간

TIMEOUT_REHEARSAL=${TIMEOUT_REHEARSAL:-3600}     # 1시간 (예상 ~15분)
TIMEOUT_DDL=${TIMEOUT_DDL:-43200}                # 12시간 (로컬 추정 5.9시간 × 2)
TIMEOUT_RIDEALONG=${TIMEOUT_RIDEALONG:-900}

export PW DB_NAME CONTAINER

mkdir -p "$OUTDIR" || { echo "🔴 $OUTDIR 를 못 만든다" >&2; exit 1; }
PHASE_LOG=$OUTDIR/phases.tsv
[ -f "$PHASE_LOG" ] || printf "phase\tstatus\tseconds\tstarted_at\n" > "$PHASE_LOG"

say() { echo; echo "════════ $* ════════"; }
note() { echo "  $*"; }

RUN_T0=$(date +%s)

# ── IMDS ─────────────────────────────────────────────────────────────────
#
# 🔴 IMDSv2 가 요구되는 인스턴스(요즘 AMI 기본값)에서는 토큰 없이 부르면 401 이다.
#    토큰을 먼저 받고, 실패하면 v1 로 떨어진다. 이걸 안 하면 매니페스트의 인스턴스
#    타입·AZ 가 통째로 빈칸이 되는데 — **그게 바로 요금을 나중에 못 뽑는 이유가 된다.**
IMDS_TOKEN=""
imds_init() {
  IMDS_TOKEN=$(curl -sf --max-time 3 -X PUT "http://169.254.169.254/latest/api/token" \
    -H "X-aws-ec2-metadata-token-ttl-seconds: 21600" 2>/dev/null)
}
imds() {  # $1 = 경로. 못 읽으면 빈 문자열
  if [ -n "$IMDS_TOKEN" ]; then
    curl -sf --max-time 3 -H "X-aws-ec2-metadata-token: $IMDS_TOKEN" \
      "http://169.254.169.254/latest/meta-data/$1" 2>/dev/null
  else
    curl -sf --max-time 3 "http://169.254.169.254/latest/meta-data/$1" 2>/dev/null
  fi
}
imds_init

# ── S3 ───────────────────────────────────────────────────────────────────
sync_s3() {  # 조용히. 실패해도 측정은 계속한다 — 다음 주기에 다시 시도한다
  aws s3 sync "$OUTDIR" "$S3_DEST" --only-show-errors 2>&1 | head -5
}

# 🔴 출력을 **반드시** 파일로 뺀다. 안 그러면 이 백그라운드 루프가 호출자의 stdout 을
#    물고 놓지 않아서, 본체가 다 끝나도 파이프가 안 닫힌다(`... | tail` 이 영영 안 끝난다).
#    로컬 스모크에서 실제로 걸렸다 — 측정은 다 됐는데 명령이 안 끝나는 것처럼 보인다.
start_syncer() {
  ( while :; do sleep "$SYNC_SEC"; sync_s3; done ) >> "$OUTDIR/_syncer.log" 2>&1 &
  SYNC_PID=$!
  note "S3 주기 동기화 시작 — ${SYNC_SEC}초마다 → $S3_DEST"
}
# 서브셸만 죽이면 그 안의 `sleep` 이 살아남아 물려받은 fd 를 계속 잡고 있다. 자식까지 끊는다.
stop_syncer() {
  [ -n "${SYNC_PID:-}" ] || return 0
  pkill -P "$SYNC_PID" 2>/dev/null
  kill "$SYNC_PID" 2>/dev/null
  SYNC_PID=""
}
trap 'stop_syncer' EXIT

# ── 단계 실행기 ──────────────────────────────────────────────────────────
#
# 여기가 «격리» 다. 단계가 죽어도 rc 만 표에 적고 다음으로 넘어간다.
#
# ⚠️ 상한 시간은 **여기서 걸지 않는다.** `timeout` 은 프로그램을 실행하는 명령이라
#    쉘 함수에 못 씌운다(씌운 것처럼 보이고 조용히 안 걸린다). 그래서 워치독은 각 단계
#    안에서 **실제로 오래 도는 외부 명령**(probe.sh·ddl_sweep.sh·docker exec)에 직접 건다.
run_phase() {  # $1=이름 $2...=명령
  local name=$1; shift
  local t0 t1 rc started
  started=$(date -Is)
  say "$name"
  t0=$(date +%s)
  "$@"
  rc=$?
  t1=$(date +%s)

  local status="OK"
  case $rc in
    0)   status="OK" ;;
    124) status="TIMEOUT" ;;
    *)   status="FAIL($rc)" ;;
  esac
  printf "%s\t%s\t%s\t%s\n" "$name" "$status" "$((t1-t0))" "$started" >> "$PHASE_LOG"
  note "→ $name : $status ($((t1-t0))초)"
  sync_s3 >/dev/null 2>&1
  return $rc
}

# ── 단계 정의 ────────────────────────────────────────────────────────────

phase_preflight() {
  local ok=0

  # 🔴 S3 쓰기를 **제일 먼저** 확인한다. 8시간 돌고 업로드에서 막히는 것이 최악이다.
  echo "preflight $(date -Is)" > "$OUTDIR/_write_test.txt"
  if aws s3 cp "$OUTDIR/_write_test.txt" "$S3_DEST/_write_test.txt" --only-show-errors; then
    note "✅ S3 쓰기 가능 — $S3_DEST"
  else
    note "🔴 S3 에 못 쓴다 — 인스턴스 프로파일 권한을 볼 것. 여기서 멈춘다"
    ok=1
  fi

  docker exec "$CONTAINER" mysqladmin ping -h localhost --silent >/dev/null 2>&1 \
    && note "✅ MySQL 응답" || { note "🔴 MySQL 무응답"; ok=1; }

  docker image inspect percona/percona-toolkit >/dev/null 2>&1 \
    && note "✅ percona-toolkit 이미지" || { note "🔴 percona-toolkit 이미지 없음 — 팔 B 4판이 전부 실패한다"; ok=1; }

  local free; free=$(df -BG --output=avail "$ROOT" | tail -1 | tr -dc '0-9')
  note "디스크 여유 ${free}GB (팔 B 는 사본을 만든다 + binlog 가 B판당 ~445MB 쌓인다)"
  [ "${free:-0}" -ge 20 ] || { note "🔴 20GB 미만"; ok=1; }

  note "WRITER_MAX_SEC=$WRITER_MAX_SEC (기본 5400 에서 상향됨)"

  # 🔴 요금 태그는 **살아 있을 때만** 붙일 수 있다. 지금 경고하면 고칠 수 있고,
  #    끄고 나서 알면 이 라운드의 실제 청구액은 영영 못 가른다. 이 repo 에 지금까지
  #    EC2 요금 기록이 한 줄도 없는 이유가 그것이다.
  local tags; tags=$(imds "tags/instance" | tr '\n' ' ')
  if echo "$tags" | grep -qi "Project"; then
    note "✅ 요금 태그 있음 — Cost Explorer 에서 이 측정만 뽑을 수 있다"
  elif [ -z "$tags" ]; then
    note "⚠️ 인스턴스 태그를 못 읽었다 — 태그가 없거나 «메타데이터의 태그 허용» 이 꺼져 있다."
    note "   지금 붙일 것: aws ec2 create-tags --resources \$(imds instance-id) --tags Key=Project,Value=shadowfit-measure"
  else
    note "⚠️ Project 태그가 없다 (현재: $tags) — 요금을 이 측정에 귀속시킬 수 없다"
  fi

  return $ok
}

# 축소 리허설. **여기서 실패하면 본 측정으로 넘어가지 않는다** — 그게 리허설의 존재 이유다.
phase_rehearsal() {
  local out=$OUTDIR/rehearsal
  mkdir -p "$out"
  note "SESSIONS=$REHEARSAL_SESSIONS — 경로 점검용. 이 판의 수치는 측정값이 아니다"
  OUT=$out SESSIONS=$REHEARSAL_SESSIONS \
    timeout --kill-after=60 "$TIMEOUT_REHEARSAL" bash "$RIG/probe.sh"      || return 1
  OUT=$out SESSIONS=$REHEARSAL_SESSIONS \
    timeout --kill-after=60 "$TIMEOUT_REHEARSAL" bash "$RIG/ddl_sweep.sh"  || return 1
  return 0
}

phase_ddl() {
  local out=$OUTDIR/ddl
  mkdir -p "$out"
  note "정판 — SESSIONS 기본값(13334 = 1,000만 행), 8판"
  # ⚠️ 상한에 걸려 스윕이 끊기면 writer 가 살아남을 수 있다. 다음 실행의 assert_no_writer
  #    가 그걸 잡아 시딩 전에 죽인다(#183 의 재발 방지 장치) — 조용히 오염되지는 않는다.
  OUT=$out timeout --kill-after=120 "$TIMEOUT_DDL" bash "$RIG/probe.sh"     || return 1
  OUT=$out timeout --kill-after=120 "$TIMEOUT_DDL" bash "$RIG/ddl_sweep.sh" || return 1
  return 0
}

# 從 항목 — 인프라가 살아 있을 때만 값이 생긴다. AWS-RIDE-ALONG.md §1 참고.
phase_ridealong() {
  local out=$OUTDIR/ridealong
  mkdir -p "$out"
  # 워치독을 명령에 직접 건다 (run_phase 주석 참고). 從 항목이 매달려서 라운드를 잡아먹지 않게.
  local q="timeout $TIMEOUT_RIDEALONG docker exec -i $CONTAINER mysql -uroot -p$PW $DB_NAME"

  # R1 — worst-section. 2026-08-08 에 정확히 이걸 안 돌리고 인프라를 삭제했다.
  #      ⚠️ 백엔드(Flyway)가 안 돌았으면 테이블 자체가 없다. 그때는 «해당 없음» 이 정답이고,
  #         없는 것을 «0» 으로 적으면 안 된다.
  {
    echo "# R1 worst-section — $(date -Is)"
    if $q -N -e "SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema='$DB_NAME' AND table_name='reports';" 2>/dev/null | grep -q '^1$'; then
      $q -e "SELECT 'reports 전체' k, COUNT(*) v FROM reports
             UNION ALL SELECT 'detailed_analysis 채워진 행', COUNT(*) FROM reports WHERE detailed_analysis IS NOT NULL
             UNION ALL SELECT 'pose_data 전체', COUNT(*) FROM pose_data
             UNION ALL SELECT 'exercise_sessions', COUNT(*) FROM exercise_sessions;" 2>&1
    else
      echo "해당 없음 — reports 테이블이 없다(백엔드/Flyway 미실행). 0 이 아니라 «측정 대상 부재» 다."
    fi
  } > "$out/R1_worst_section.txt" 2>&1

  # R2 — MySQL 지표. pool-cliff 초판이 «병목이 백엔드 CPU 로 이동» 을 철회한 사유가
  #      바로 이 지표의 부재였다. 이번엔 처음부터 걷는다.
  $q -e "SHOW GLOBAL STATUS;"    > "$out/R2_global_status.txt"    2>&1
  $q -e "SHOW GLOBAL VARIABLES;" > "$out/R2_global_variables.txt" 2>&1
  $q -e "SELECT DIGEST_TEXT, COUNT_STAR, SUM_TIMER_WAIT/1e12 sum_s, SUM_ROWS_EXAMINED
         FROM performance_schema.events_statements_summary_by_digest
         ORDER BY SUM_TIMER_WAIT DESC LIMIT 20;" > "$out/R2_top_digest.txt" 2>&1

  # R3 — 3-way 조인. reports/sessions/users 시딩이 선행이라 이번 라운드 범위 밖이다.
  echo "미실행 — reports·exercise_sessions·users 시딩이 선행 조건. AWS-RIDE-ALONG.md §1 從-R3" \
    > "$out/R3_hash_join.SKIPPED.txt"

  note "R1·R2 수집, R3 은 미실행(사유 기록)"
  return 0
}

# 조건 기록. «조건 없는 수치는 인용 불가» 라 이 파일이 없으면 측정도 반쪽이다.
phase_collect() {
  local m=$OUTDIR/MANIFEST.txt
  {
    echo "# 측정 조건 — $RUN_ID"
    echo "생성          : $(date -Is)"
    echo "커밋          : $(git -C "$ROOT" rev-parse HEAD 2>/dev/null) ($(git -C "$ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null))"
    echo "인스턴스 타입 : $(imds instance-type)"
    echo "인스턴스 ID   : $(imds instance-id)"
    echo "AZ / 리전     : $(imds placement/availability-zone) / $(imds placement/region)"
    echo "vCPU / RAM    : $(nproc) / $(awk '/MemTotal/ {printf "%.0fGB", $2/1048576}' /proc/meminfo 2>/dev/null)"
    echo "커널          : $(uname -r)"
    echo "디스크        : $(df -h "$ROOT" | awk 'NR==2 {print $2, "여유", $4}')"
    echo "MySQL         : $(docker exec "$CONTAINER" mysql -uroot -p"$PW" -N -e 'SELECT VERSION();' 2>/dev/null | tr -d '\r')"
    echo "버퍼풀        : $(docker exec "$CONTAINER" mysql -uroot -p"$PW" -N -e "SELECT @@innodb_buffer_pool_size;" 2>/dev/null | tr -d '\r')"
    echo "WRITER_MAX_SEC: $WRITER_MAX_SEC"
    # 🔴 #198 — 이 한 줄이 없어서 08-12 라운드를 회수할 때 버킷 이름을 사람에게 물어야 했다.
    #    러너 로그(`/root/run_all.log`)에도 찍히지만 그건 $OUTDIR 밖이라 S3 로 안 올라가고
    #    인스턴스와 함께 죽는다. **살아남는 파일에 적어야 한다.**
    echo "S3 결과       : $S3_DEST"
    echo
    echo "# 단계"
    cat "$PHASE_LOG"
    echo
    echo "⚠️ 절대 소요 시간을 «운영에서 N분» 으로 인용 금지 — 하드웨어 종속(설계 §5)"
    echo "⚠️ rehearsal/ 의 수치는 측정값이 아니다 — 경로 점검용 축소 판"
  } > "$m"
  cat "$m"
  return 0
}

# ── 실행 ─────────────────────────────────────────────────────────────────
say "무인 측정 — $RUN_ID"
note "결과   : $OUTDIR"
note "S3     : $S3_DEST"
note "단계   : $PHASES"
note "자동정지: $([ "$AUTO_SHUTDOWN" = "1" ] && echo "켜짐" || echo "꺼짐")"

start_syncer

for p in $PHASES; do
  case $p in
    preflight)
      run_phase preflight phase_preflight || {
        note "🔴 사전 확인 실패 — 여기서 멈춘다. 이 상태로 돌리면 «환경 결함» 이 «측정 결과» 로 찍힌다"
        break
      } ;;
    rehearsal)
      run_phase rehearsal phase_rehearsal || {
        note "🔴 리허설 실패 — 본 측정으로 넘어가지 않는다. 리허설의 존재 이유가 이것이다"
        break
      } ;;
    ddl)       run_phase ddl       phase_ddl ;;
    ridealong) run_phase ridealong phase_ridealong ;;
    collect)   run_phase collect   phase_collect ;;
    *)         note "알 수 없는 단계 '$p' — 건너뛴다" ;;
  esac
done

# ── 마무리 ───────────────────────────────────────────────────────────────
say "최종 업로드"
stop_syncer

# 매니페스트 안의 단계 표는 collect 가 돌던 «그 순간» 의 스냅샷이라 자기 행이 빠져 있다.
# 끝난 뒤의 최종본을 뒤에 붙인다 — 인용할 때 보는 파일이 반쪽이면 안 된다.
if [ -f "$OUTDIR/MANIFEST.txt" ]; then
  { echo; echo "# 단계 (최종)"; cat "$PHASE_LOG"; } >> "$OUTDIR/MANIFEST.txt"
fi

# ── 요금 ─────────────────────────────────────────────────────────────────
#
# 실제 청구액은 인스턴스 안에서 알 수 없다(단가를 모른다). 대신 **곱해야 할 것들**을
# 남긴다 — 타입 · 가동 시간 · 볼륨. 청구액은 나중에 Cost Explorer 에서 태그로 뽑아
# 이 칸에 채운다. 이 repo 에 EC2 요금 기록이 한 줄도 없어서 «AWS 실측 얼마 드나» 에
# 추정으로밖에 답할 수 없었다. 그 칸을 여기서 연다.
RUN_SEC=$(( $(date +%s) - RUN_T0 ))
UP_SEC=$(awk '{printf "%d", $1}' /proc/uptime 2>/dev/null || echo 0)
{
  echo
  echo "# 요금 (청구액은 나중에 채운다)"
  echo "인스턴스 타입   : $(imds instance-type)"
  echo "리전            : $(imds placement/region)"
  echo "태그            : $(imds tags/instance | tr '\n' ' ')"
  echo "인스턴스 가동   : $(awk -v s="$UP_SEC" 'BEGIN{printf "%.2f", s/3600}')시간 (부팅부터 지금까지 — 요금 대상은 이쪽)"
  echo "러너 소요       : $(awk -v s="$RUN_SEC" 'BEGIN{printf "%.2f", s/3600}')시간"
  echo "루트 볼륨       : $(lsblk -dno SIZE,TYPE 2>/dev/null | head -1)"
  echo "실제 청구액     : (미기입) — Cost Explorer 에서 태그 Project=shadowfit-measure 로 필터해 채울 것"
  echo
  echo "⚠️ 인스턴스를 끈 뒤에도 **볼륨이 남으면 요금이 계속 나간다.** 삭제까지 확인할 것"
} >> "$OUTDIR/MANIFEST.txt"

if sync_s3; then
  FINAL_OK=1; note "✅ $S3_DEST"
else
  FINAL_OK=0; note "🔴 최종 업로드 실패"
fi

say "단계 요약"
cat "$PHASE_LOG"

# 🔴 업로드가 실패했으면 **절대 안 끈다.** 인스턴스 안에만 있는 결과를 끄는 건
#    측정을 통째로 버리는 것과 같다. 사람이 와서 회수해야 한다.
if [ "$AUTO_SHUTDOWN" = "1" ] && [ "$FINAL_OK" = "1" ]; then
  note "60초 후 정지한다 (취소: pkill -f 'shutdown')"
  shutdown -h +1 "측정 종료 — run_all.sh"
elif [ "$AUTO_SHUTDOWN" = "1" ]; then
  note "⚠️ 자동 정지가 켜져 있지만 업로드가 실패해서 **끄지 않는다.** 결과가 이 인스턴스에만 있다"
else
  note "자동 정지 꺼짐 — 회수 확인 후 직접 정지할 것. AWS-RIDE-ALONG.md §5 체크리스트를 볼 것"
fi