#!/bin/bash
# 4차 실측 공통부 — ③ 커밋 횟수 스윕과 ④ 내구성 완화 pool 스윕이 같이 쓴다.
#
# ─────────────────────────────────────────────────────────────────────────
# 왜 공통 파일을 만들었나 (지난 라운드의 대가)
#
# 3차는 sweep.sh 와 conn_sweep.sh 가 각자 복사본을 갖고 있었고, 그 결과:
#   #141 호스트 키 검증 결함 — 이슈는 sweep.sh 만 지목했는데 conn_sweep.sh 에도
#        같은 패턴이 있어 **두 번 고쳤다**
#   #146 실패 삼킴       — 같은 계열의 결함이 다른 자리에서 다시 나왔다
#
# 두 스크립트가 «같은 규약» 을 지켜야 하는데 복사본이면 한쪽만 고쳐진다. 규약을 한 군데
# 두고 스윕 스크립트는 «무엇을 재는가» 만 갖게 한다.
# ─────────────────────────────────────────────────────────────────────────
#
# 설계 문서: docs/decisions/commit-count-and-mysql-metrics.md
#
# 필요한 환경변수 (호출부에서 export):
#   PEM         키페어 경로
#   DB_PUB      DB 인스턴스 공인 IP (ssh 대상)
#   APP_PUB     App 인스턴스 공인 IP
#   LOADER_PUB  부하기 인스턴스 공인 IP
#   OBS_PUB     obs 인스턴스 공인 IP
#   DB_PRIV     DB 사설 IP (app·obs 가 붙는 주소)
#   APP_PRIV    App 사설 IP (부하기가 쏘는 주소)
#   OUT         결과 디렉터리

set -uo pipefail

: "${PEM:?PEM 미설정}" "${DB_PUB:?}" "${APP_PUB:?}" "${LOADER_PUB:?}" "${OBS_PUB:?}"
: "${DB_PRIV:?}" "${APP_PRIV:?}" "${OUT:?}"
mkdir -p "$OUT"

FAILED=()   # 유효 데이터를 못 낸 판. 끝에서 요약하고 exit 1 의 근거가 된다.

# ── 호스트 키 (이슈 #141) ────────────────────────────────────────────────
# 시작할 때 한 번 학습(ssh-keyscan)하고 그 뒤로는 엄격 검증한다. `StrictHostKeyChecking=no`
# + `UserKnownHostsFile=/dev/null` 조합은 학습한 키를 버려 매 접속이 «처음 보는 호스트» 가
# 되므로 검증이 사실상 없다 — 그 조합을 쓰지 않는다.
#
# ⚠️ 첫 학습 순간은 여전히 TOFU 다. 그 창까지 없애려면 인스턴스 콘솔 출력의 지문과 대조한다:
#      aws ec2 get-console-output --instance-id <id> --output text | grep -A2 'SSH HOST KEY'
#    아래 학습 단계가 출력하는 지문과 눈으로 맞추면 된다. 부팅 로그를 기다려야 해서
#    자동화하지 않았다.
KNOWN="$OUT/known_hosts"
touch "$KNOWN"; chmod 600 "$KNOWN" 2>/dev/null
SSH_OPTS=(-i "$PEM" -o "UserKnownHostsFile=$KNOWN" -o StrictHostKeyChecking=yes
          -o ConnectTimeout=10 -o LogLevel=ERROR)
# scp 용은 따로 둔다. 처음엔 `${SSH_OPTS[@]:1}` 로 «-i 만 떼어내려» 했는데, 그건 `-i` 만
# 빼고 **PEM 경로는 남긴다** — scp 가 그 경로를 «소스 파일» 로 읽어 인자가 3개가 되고,
# 그러면 목적지가 디렉터리여야 해서 "No such file or directory" 로 죽었다. 원격 파일이
# 안 온 것처럼 보였지만 실제로는 **명령 자체가 성립하지 않았다.**
SCP_OPTS=(-i "$PEM" -o "UserKnownHostsFile=$KNOWN" -o StrictHostKeyChecking=yes -o LogLevel=ERROR)

die() {  # 다음 판을 오염시키는 실패에만 쓴다
  echo "" >&2
  echo "🔴 스윕 중단 — $*" >&2
  echo "   $LOG 의 기존 행은 유효하지만 **이 지점 이후는 측정되지 않았다.**" >&2
  echo "   남은 행만 보고 판정하지 말 것." >&2
  exit 1
}

learn_host() {  # $1 = 호스트, $2 = 이름표
  local h=$1 tag=$2 keys
  if ssh-keygen -F "$h" -f "$KNOWN" >/dev/null 2>&1; then
    echo "  $tag ($h): 이미 학습된 키를 쓴다"; return 0
  fi
  keys=$(ssh-keyscan -T 10 -H "$h" 2>/dev/null)
  # 빈 값을 넘기면 known_hosts 가 비어 «엄격 검증» 이 껍데기가 된다.
  if [ -z "$keys" ] || ! ssh-keygen -lf - <<< "$keys" >/dev/null 2>&1; then
    die "$tag ($h) 의 호스트 키를 못 읽었다 — 인스턴스가 안 떴거나 22 가 막혔다"
  fi
  printf '%s\n' "$keys" >> "$KNOWN"
  echo "  $tag ($h) 지문:"; ssh-keygen -lf - <<< "$keys" | sed 's/^/    /'
}

learn_all_hosts() {
  echo "=== 호스트 키 학습 (이슈 #141) ==="
  learn_host "$DB_PUB" "db"; learn_host "$APP_PUB" "app"
  learn_host "$LOADER_PUB" "loader"; learn_host "$OBS_PUB" "obs"
  echo "  → 이후 접속은 StrictHostKeyChecking=yes 로 검증한다."
  echo "     세션 도중 키가 바뀌면 접속이 거부되고 스윕이 멈춘다 — 그게 의도다."
  echo "     (IP 를 재사용해 옛 키가 남아 거부되면: rm $KNOWN)"
  echo
}

rsh() { ssh "${SSH_OPTS[@]}" "ec2-user@$1" "${@:2}"; }

# ── MySQL ────────────────────────────────────────────────────────────────
mysql_q() {  # $1 = SQL. stdout 으로 결과(탭 구분, 헤더 없음)
  rsh "$DB_PUB" "sudo docker exec sf-mysql mysql -ushadowfit -pshadowfit shadowfit -N -e \"$1\"" 2>/dev/null
}

# 🔴 카운터는 Prometheus 가 아니라 MySQL 에서 직접 읽는다.
#
# 3차의 결함 중 하나가 «스크레이프 간격(15초) > 판 길이(9.6초)» 라 게이지를 판별로 귀속할 수
# 없었던 것이다. 이번엔 스크레이프를 5초로 줄이고 판을 60초 이상으로 잡아 그 문제를 없앴지만,
# **카운터 델타는 스크레이프 격자에 맞춰 반올림될 이유가 없다** — 판 시작·끝에 SHOW GLOBAL
# STATUS 를 직접 찍으면 정확한 횟수가 나온다.
#
# 그래서 역할을 나눈다: **정확한 횟수는 여기서, 시간에 따른 모양은 Prometheus 에서.**
# Prometheus 를 안 쓰는 게 아니라, 판정에 쓰는 수를 격자에 의존시키지 않는 것이다.
counters() {  # stdout: "handler_commit fsyncs log_written"
  mysql_q "SELECT
      MAX(CASE WHEN VARIABLE_NAME='HANDLER_COMMIT' THEN VARIABLE_VALUE END),
      MAX(CASE WHEN VARIABLE_NAME='INNODB_OS_LOG_FSYNCS' THEN VARIABLE_VALUE END),
      MAX(CASE WHEN VARIABLE_NAME='INNODB_OS_LOG_WRITTEN' THEN VARIABLE_VALUE END)
    FROM performance_schema.global_status
    WHERE VARIABLE_NAME IN ('HANDLER_COMMIT','INNODB_OS_LOG_FSYNCS','INNODB_OS_LOG_WRITTEN');" \
  | tr '\t' ' '
}

set_durability() {  # $1 = innodb_flush_log_at_trx_commit, $2 = sync_binlog
  mysql_q "SET GLOBAL innodb_flush_log_at_trx_commit=$1; SET GLOBAL sync_binlog=$2;" \
    || die "내구성 설정 변경 실패 (flush=$1 sync_binlog=$2)"
  local got
  got=$(mysql_q "SELECT @@innodb_flush_log_at_trx_commit, @@sync_binlog;" | tr '\t' ' ')
  # 🔴 «설정했다» 와 «설정됐다» 는 다르다. 이 실험은 이 두 값이 조작 변수 그 자체라,
  #    확인 없이 지나가면 «완화했다고 믿고 기본값을 잰» 판이 표에 들어간다.
  [ "$got" = "$1 $2" ] || die "내구성 설정이 반영되지 않았다 — 원한 값 '$1 $2', 실제 '$got'"
  echo "  내구성: flush=$1 sync_binlog=$2 (확인됨)"
}

reset_rows() {  # 판 사이 상태 초기화. 실패하면 다음 판이 «테이블이 커진 것» 을 잰다 → 중단
  mysql_q "DELETE FROM pose_data WHERE session_id BETWEEN $SESS_LO AND $SESS_HI;" \
    || die "pose_data 초기화 실패 ($1) — 행 수는 이 실험이 고정해야 하는 조건이다"
}

# 세션 시드 확인. 없으면 전 요청이 실패하는데 그건 «측정했더니 낮다» 처럼 보인다.
# 🔴 여기(공통부)에 둔다 — 예전엔 sessions_sweep.sh 에만 있어서 從 스크립트를 단독으로
#    돌리면 이 그물이 통째로 없었다(#273 ③). 복사본을 만들면 한쪽만 고쳐진다는 것이
#    이 파일이 존재하는 이유 그 자체다.
assert_sessions_exist() {
  local want=$(( SESS_HI - SESS_LO + 1 )) got
  got=$(mysql_q "SELECT COUNT(*) FROM exercise_sessions WHERE id BETWEEN $SESS_LO AND $SESS_HI;")
  [ "$got" = "$want" ] \
    || die "세션 시드가 부족하다 — $SESS_LO~$SESS_HI 중 '$got'/$want 개만 있다. FK 로 전 요청이 실패한다"
  echo "  세션 시드: $SESS_LO~$SESS_HI $want개 확인"
}

# ── 백엔드 ───────────────────────────────────────────────────────────────
# bootJar 를 직접 돌린다 — 도커 이미지를 안 쓰는 이유:
#   ① 이 rig 는 arm64(t4g)라 로컬(amd64)에서 만든 이미지를 못 쓴다. 박스에서 빌드하면
#      판마다가 아니라 준비 단계에서만 드는 비용이긴 하나, gradle 빌드 환경을 원격에
#      또 세팅해야 한다
#   ② jar 는 아키텍처 중립이라 로컬 빌드본을 그대로 올린다. 3차도 같은 방식이었다
#   ③ 판 사이 재기동이 컨테이너보다 빠르다 — 9판이라 누적이 유의하다
restart_backend() {  # $1 = pool
  local pool=$1 rc
  timeout 180 ssh "${SSH_OPTS[@]}" "ec2-user@$APP_PUB" "
    set -u
    source ~/env.sh || exit 10
    # 🔴 pkill -f 를 쓰지 않는다. 첫 판에서 \`pkill -f 'java .*app.jar'\` 가 **자기 자신을
    #    죽였다** — ssh 가 원격에서 띄우는 셸의 커맨드라인에 그 패턴 문자열이 그대로 들어
    #    있어서, pkill 이 그 셸을 매칭해 세션째 끊었다(ssh rc=255, «SSH 연결 실패» 로 보였다).
    #    스크립트가 자기 텍스트에 걸린 것이라 원인이 SSH 로 위장됐다.
    #    PID 파일로 «내가 띄운 그 프로세스» 만 정확히 지목한다.
    if [ -f ~/app.pid ]; then kill \$(cat ~/app.pid) 2>/dev/null; fi
    for i in \$(seq 1 30); do
      curl -sf localhost:9090/actuator/health >/dev/null 2>&1 || break
      sleep 1
    done
    export DB_HOST=$DB_PRIV SPRING_PROFILES_ACTIVE=prod TZ=Asia/Seoul
    export AI_SERVER_HOST=127.0.0.1 AI_SERVER_GRPC_PORT=8585 AI_SERVER_HTTP_PORT=8000
    nohup java -Xmx1500m -jar ~/app.jar \
      --spring.datasource.hikari.maximum-pool-size=$pool > ~/app.log 2>&1 &
    echo \$! > ~/app.pid
    up=0
    for i in \$(seq 1 60); do
      curl -sf localhost:9090/actuator/health >/dev/null 2>&1 && { up=1; break; }
      sleep 2
    done
    [ \$up -eq 1 ] || { echo '백엔드가 120초 안에 health 를 주지 않았다' >&2; exit 12; }
    # 🔴 «풀을 그 값으로 띄웠다» 를 확인한다. 이 실험(④)에서 pool 은 조작 변수 자체라,
    #    인자가 안 먹은 채로 도는 판이 표에 들어가면 «절벽이 없다» 로 읽힌다.
    #
    # ⚠️ /actuator/metrics 가 아니라 /actuator/prometheus 를 쓴다. 전자는 **401 이다** —
    #    이 프로젝트는 관리 포트에서 prometheus 스크레이프만 무인증으로 열어두고 나머지
    #    액추에이터는 막는다(application.yml [6]). 처음에 metrics 로 짰다가 빈 값을 받아
    #    «풀이 반영 안 됐다» 로 읽었는데, 실제로는 반영돼 있었고 **확인 경로가 막힌 것**이었다.
    got=\$(curl -s localhost:9090/actuator/prometheus 2>/dev/null \
           | grep '^hikaricp_connections_max' | head -1 | awk '{print \$2}' | cut -d. -f1)
    [ \"\$got\" = \"$pool\" ] || { echo \"풀 크기가 반영되지 않았다 — 원한 값 $pool, 실제 \$got\" >&2; exit 13; }
  " >/dev/null
  rc=$?
  case $rc in
    0)   return 0 ;;
    10)  echo "  ✗ env.sh 를 읽지 못했다" >&2 ;;
    13)  echo "  ✗ 풀 크기가 반영되지 않았다 (pool=$pool)" >&2 ;;
    12)  echo "  ✗ 헬스체크 실패 — 백엔드가 안 떴다 (pool=$pool)" >&2 ;;
    124) echo "  ✗ 재기동이 180초 timeout (pool=$pool)" >&2 ;;
    255) echo "  ✗ SSH 연결 실패 → $APP_PUB" >&2 ;;
    *)   echo "  ✗ 재기동 실패 rc=$rc (pool=$pool)" >&2 ;;
  esac
  return 1
}

# ── 부하 ─────────────────────────────────────────────────────────────────
# ⚠️ `-n`(고정 요청수)을 쓴다. 3차가 `-z`(고정 시간)로 바꿨다가 종료 시점 in-flight 강제
#    취소로 판당 fail≈100 을 만들었다. 대신 판이 짧아지는 문제는 `-n` 을 키워서 푼다
#    (설계 §3-4). 두 결함을 동시에 피하는 조합은 «-n 크게» 뿐이다.
# 🔴 `~` 를 쓰지 않는다. 이 대입은 **로컬에서** 평가되므로 `GHZ=~/go/bin/ghz` 는
#    로컬 홈(C:/Users/...)으로 전개돼 원격에 없는 경로가 된다 — 첫 실행에서 3판이 전부
#    «워밍업 ghz 실패» 로 죽었고, 메시지는 «백엔드 미기동 / 포트 차단?» 을 가리켜
#    엉뚱한 곳을 보게 만들었다. (restart_backend 안의 `~/app.jar` 등은 큰따옴표 안이라
#    로컬 전개가 안 되고 원격에서 풀린다 — 그래서 그쪽은 멀쩡했다.)
GHZ=/home/ec2-user/go/bin/ghz
WARM_C=3; WARM_SEC=30

# 스윕이 ghz 에 인자를 더 얹는 통로. 기본은 빈 값이라 기존 스윕은 그대로다.
#   예) GHZ_EXTRA="--connections 16"
# 🔴 **워밍업에도 같이 건다.** 커넥션 수처럼 «연결을 만드는 방식» 을 흔드는 인자를 본판에만
#    걸면, 워밍업이 만들어 둔 연결 상태 위에서 본판이 돌아 조작 변수가 반쯤만 적용된다.
GHZ_EXTRA=${GHZ_EXTRA:-}

# 버림판 표시. 1 이면 이 판의 실패를 **집계에 넣지 않는다** — 버림판은 애초에 표 밖이다.
# 🔴 예전엔 버림판이 실패하면 행은 sed 로 지워지는데 FAILED 에는 남아, 본판 25개가 전부
#    멀쩡해도 스윕이 exit 1 로 끝나고 요약이 **표에 없는 태그**를 지목했다(#273 ①).
GHZ_DISCARD=${GHZ_DISCARD:-0}

# 실패를 집계할지 정한다. 버림판이면 행도 안 남긴다 — 어차피 호출부가 지운다.
note_fail() {  # $1=태그 $2=사유
  if [ "$GHZ_DISCARD" = "1" ]; then
    echo "  (버림판 실패 — 집계에 넣지 않는다: $1:$2)" >&2
    return 0
  fi
  fail_row "$1"; FAILED+=("$1:$2")
}

run_ghz() {  # $1=태그 $2=data-file $3=c $4=n  → TSV 행을 $LOG 에 남기고 "rps fail" 출력
  local tag=$1 data=$2 c=$3 n=$4
  local f="$tag.json" rc

  echo "  워밍업 ${WARM_SEC}s (c=$WARM_C)"
  if ! ssh "${SSH_OPTS[@]}" "ec2-user@$LOADER_PUB" "
      $GHZ --insecure --call ExerciseService.SavePoseDataBatch --metadata-file /tmp/meta.json \
           --data-file $data -c $WARM_C $GHZ_EXTRA -z ${WARM_SEC}s $APP_PRIV:6565 >/dev/null 2>&1"; then
    # 상태를 바꾸지 않은 실패다 — 이 판만 버리고 다음으로 간다.
    echo "  ✗ 워밍업 ghz 실패 — $tag 판을 버린다 (백엔드 미기동 / 포트 차단?)" >&2
    note_fail "$tag" "워밍업"; return 1
  fi
  reset_rows "워밍업 직후, $tag"

  local c0 c1 t0 t1
  c0=$(counters); t0=$(date +%s)
  # 스윕별 추가 관측 훅. 정의돼 있을 때만 불린다 — 기존 스윕은 영향이 없다.
  #   round_begin_hook <태그>
  #   round_end_hook   <태그> <t0_epoch> <t1_epoch>
  # 🔴 훅의 실패가 판을 죽이지 않게 한다(`|| true`). 추가 관측이 본 지표를 버리게 하면
  #    주객이 뒤집힌다 — 훅이 못 걷은 값은 그 스윕의 표에서 비어 있으면 된다.
  declare -F round_begin_hook >/dev/null && { round_begin_hook "$tag" || true; }
  ssh "${SSH_OPTS[@]}" "ec2-user@$LOADER_PUB" "
      $GHZ --insecure --call ExerciseService.SavePoseDataBatch --metadata-file /tmp/meta.json \
           --data-file $data -c $c $GHZ_EXTRA -n $n -O json -o /tmp/$f $APP_PRIV:6565 >/dev/null 2>&1" \
    || echo "  ⚠️ ghz 가 non-zero 로 끝났다 — 리포트 내용으로 판정한다" >&2
  t1=$(date +%s); c1=$(counters)
  declare -F round_end_hook >/dev/null && { round_end_hook "$tag" "$t0" "$t1" || true; }

  if ! scp "${SCP_OPTS[@]}" -q "ec2-user@$LOADER_PUB:/tmp/$f" "$OUT/$f"; then
    echo "  ✗ 결과 회수(scp) 실패 ($tag) — 원격 /tmp/$f 는 남아 있다" >&2
    note_fail "$tag" "회수"; return 1
  fi
  reset_rows "본판 직후, $tag"

  python - "$OUT/$f" "$tag" "$LOG" "$c0" "$c1" "$((t1-t0))" "$ROWS_PER_REQ" <<'PY'
import json, sys

f, tag, log, c0, c1, secs, rows_per_req = sys.argv[1:8]
try:
    j = json.load(open(f, encoding='utf-8'))
except Exception as e:
    # 🔴 `raise SystemExit` 는 종료 코드 0 이다(이슈 #146). 반드시 non-zero 로 죽는다.
    print(f"  ✗ ghz JSON 파싱 실패 ({f}): {e}", file=sys.stderr); raise SystemExit(2)

d = {x['percentage']: round(x['latency'] / 1e6) for x in (j.get('latencyDistribution') or [])}
sc = j.get('statusCodeDistribution') or {}
tot, rps = j.get('count', 0), round(j.get('rps', 0), 1)
ok = sc.get('OK', 0); fail = tot - ok

# 측정으로 성립하지 않는 리포트는 숫자로 내보내지 않는다.
if tot <= 0:
    print(f"  ✗ 요청 수가 0 이다 ({f}) — 부하가 실제로 걸리지 않았다", file=sys.stderr); raise SystemExit(3)
if ok == 0:
    print(f"  ✗ 성공 응답이 0 이다 ({f}, {tot}건 전부 실패) — RPS {rps} 는 처리량이 아니라 "
          f"«거절 속도» 다", file=sys.stderr); raise SystemExit(4)
if rps <= 0:
    print(f"  ✗ RPS 가 0 이다 ({f})", file=sys.stderr); raise SystemExit(5)

def delta(i):
    try:
        return int(c1.split()[i]) - int(c0.split()[i])
    except (IndexError, ValueError):
        return -1

commits, fsyncs, log_written = delta(0), delta(1), delta(2)
secs = int(secs) or 1
# rows/sec 이 ③의 1차 지표다 — 요청 수가 팔마다 다르므로 RPS 는 그대로 비교할 수 없다.
rows_sec = round(rps * float(rows_per_req), 1)

open(log, 'a').write(
    f"{tag}\t{rps}\t{rows_sec}\t{d.get(50,-1)}\t{d.get(95,-1)}\t{d.get(99,-1)}\t"
    f"{fail}\t{tot}\t{commits}\t{fsyncs}\t{round(fsyncs/secs,1)}\t{log_written}\n")
print(f"  {tag}: RPS={rps} rows/s={rows_sec} p50={d.get(50)}ms p99={d.get(99)}ms "
      f"fail={fail}/{tot} ({fail/tot:.2%})")
print(f"    커밋={commits} fsync={fsyncs} ({round(fsyncs/secs,1)}/s) "
      f"fsync/커밋={round(fsyncs/commits,2) if commits > 0 else 'n/a'}")
PY
  rc=$?
  if [ $rc -ne 0 ]; then note_fail "$tag" "파싱/내용"; return 1; fi
  return 0
}

# 실패를 숫자로 바꾸지 않는다. 0 은 «측정했더니 0», FAIL 은 «측정하지 못했다» 다.
fail_row() { printf "%s\tFAIL\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\n" "$1" >> "$LOG"; }

init_log() {
  [ -f "$LOG" ] || printf "tag\trps\trows_s\tp50_ms\tp95_ms\tp99_ms\tfail\ttotal\tcommits\tfsyncs\tfsync_s\tlog_bytes\n" > "$LOG"
}

# 🔴 «계속» 이 «성공» 을 뜻하지 않게 한다 (이슈 #146). 이 실험들의 결론은 전부 «비교» 라,
#    근거가 몇 점에서 몇 점으로 줄었는지가 보여야 한다.
finish() {  # $1 = 전체 판 수
  echo; echo "=== 결과 ==="; cat "$LOG"
  if [ ${#FAILED[@]} -gt 0 ]; then
    echo >&2
    echo "🔴 $1 판 중 ${#FAILED[@]}판이 유효 데이터를 내지 못했다: ${FAILED[*]}" >&2
    echo "   해당 판은 위 표에서 FAIL 행이다. 남은 $(( $1 - ${#FAILED[@]} ))판만으로" >&2
    echo "   결론을 쓰지 말 것 — 비교의 근거가 그만큼 줄었다." >&2
    exit 1
  fi
  echo; echo "✅ $1 판 전부 유효."
}