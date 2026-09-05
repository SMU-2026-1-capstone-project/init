#!/usr/bin/env bash
# HikariCP 풀 사이징 재실험 — 10~20 사이 좁히기 (택틱 B, 2대 또는 3대 구성)
#
# 🟢 2026-09-05 — DB_SSH 를 설정하면 3대(DB·App 분리, App bare jar+systemd) 경로로 동작한다.
#    docs/decisions/pool-sizing-10-20-experiment-design.md §10-2가 이미 이 설계(systemd
#    Environment= sed 치환 + assert_pool 재사용)를 2026-09-04 EC2 라운드(§11)에서 검증했으나,
#    그 실행에 쓰인 스크립트 사본은 커밋되지 못했다(동시 세션 git 인덱스 경합으로 2대 버전이
#    되살아났다, 커밋 a303daf4) — 아래는 §10-2 설계를 그대로 다시 구현한 것이다.
#    DB_SSH 가 비어 있으면(기본) 기존 2대(docker-compose override) 경로 그대로 동작 — 동작 불변.
#
# 설계: docs/decisions/pool-sizing-10-20-experiment-design.md
# 배경: 2026-09-04 1차 시도(4대: DB·App 분리+Loader+Obs)가 실전에서 막혔다 — 그 스윕이
#       물려받은 `/tmp/batch_n1.json`(평문 JSON, 2026-08 초 관례) 페이로드가 지금 부하기가
#       실제로 만드는 `/root/batch_multi.json`(Go 템플릿, gen_batch_multi.py, 2026-08-17
#       이후 관례)와 세대가 달랐고, App 박스의 인증 토큰 배선도 run_all.sh 안에만 있어
#       이 경로엔 없었다. 이 스크립트는 그 둘을 **현재 P6 관례**(bootstrap.sh ROLE=p6-target/
#       p6-loader, docker-compose.yml)에 맞춰 다시 짠 것이다.
#
# 구성 변경: 4대(DB·App 분리+Loader+Obs) → **2대(p6-target+p6-loader)**.
#   - p6-target 은 MySQL+Spring 이 **같은 박스**에 docker-compose 로 뜬다 — DB·App을 분리할
#     방법이 현재 bootstrap.sh 에 없다(p6-target ROLE 자체가 그렇게 설계돼 있다).
#   - Obs(mysqld_exporter 원격) 없이, **actuator(9090)의 hikaricp_connections_* 지표를
#     대상 박스 안에서 curl** 하는 것으로 대체한다 — coresidency_sweep.sh 의 snap_side 가
#     이미 이 패턴을 쓰고 있고(SIDE_RE 가 hikaricp_connections 를 포함), 검증됐다.
#   - Q2(왜 그런 결과가 나오는지, 예: MySQL 내부 락 경합)는 대상 박스 안에서 SHOW GLOBAL
#     STATUS 를 같이 걷어(§4 SIDE_MYSQL_VARS) 부분적으로 답할 수 있다 — 별도 Obs 박스가
#     없어도 이 정도는 회수된다.
#
# 측정 방식은 원 설계(c=100 닫힌 루프, `-n 15000`)를 그대로 쓴다 — 2026-08-09 4점 baseline
# (pool 2/5/10/20 → 69%/69%/100%/100%)과 **같은 조건이라야 같은 곡선에 얹을 수 있다.**
# P6 rig 의 `--rps` 열린 루프(가정 P1 배수)는 다른 질문(가정 부하에서 여유가 얼마인가)에
# 맞는 방식이라 여기선 안 쓴다 — 페이로드 파일과 인증만 P6 관례를 빌리고, 부하 방식은
# 원 설계를 유지한다.
#
# 🟢 2026-09-04 EC2 라운드에서 실전 검증됨 — pool override(SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE
# env override)가 실제로 HikariCP 에 반영됨을 확인(assert_pool 이 재기동 직후
# hikaricp_connections_max=15 를 그대로 읽었다, 버림판). 아래는 그 확인 전 남아 있던 미검증 목록이다:
#   - 재기동 후 health 대기 루프의 상한(90초)이 이 박스(m6i.xlarge 급)에서 충분한지 — 버림판+r1-p10
#     전환에서 각 27초 안에 끝남, 여유 있음
#   - ghz 의 `--data-file`(batch_multi.json, 다세션 템플릿)이 `-c 100 -n 15000` 닫힌 루프와
#     조합됐을 때 세션 분포가 기존 4점(2026-08-09, 다른 페이로드 생성기)과 **동등한 성격인지**
#     (다세션이라는 점은 같지만 생성기가 다르므로 완전한 등가성은 아니다 — 결과 문서에 명시할 것)
#
# 필요한 환경변수:
#   TARGET_HOST   대상(App) 사설 IP — ghz 가 이 주소의 6565 를 친다
#   TARGET_SSH    대상으로 붙는 ssh 명령 전체 (예: "ssh -i k.pem root@10.0.0.5")
#   TARGET_REPO_DIR  대상의 저장소 경로 (기본 /root/init) — 2대 경로에서만 쓴다(3대 App 은 bare jar)
#   DB_SSH        (3대 전용) DB 박스로 붙는 ssh 명령 전체. 비어 있으면 2대 경로(TARGET_SSH 가
#                 DB·App 둘 다 처리)로 동작 — 기본값, 동작 불변
#   GHZ_TOKEN     대상 .env 의 INTERNAL_API_TOKEN (부트스트랩이 생성·출력한 값)
#   GHZ_DATA      부하기의 batch_multi.json 경로 (기본 /root/batch_multi.json)
#   GHZ_BIN       ghz 바이너리 경로 (기본 /usr/local/bin/ghz)
#   OUT           결과 디렉터리

set -uo pipefail

: "${TARGET_HOST:?TARGET_HOST 미설정}" "${TARGET_SSH:?TARGET_SSH 미설정}" "${GHZ_TOKEN:?GHZ_TOKEN 미설정}"
OUT="${OUT:?OUT 미설정}"
TARGET_REPO_DIR=${TARGET_REPO_DIR:-/root/init}
DB_SSH=${DB_SSH:-}
GHZ_DATA=${GHZ_DATA:-/root/batch_multi.json}
GHZ_BIN=${GHZ_BIN:-/usr/local/bin/ghz}
C=${C:-100}
N=${N:-15000}
ACTUATOR=${ACTUATOR:-http://127.0.0.1:9090/actuator/prometheus}
MYSQL_CONTAINER=${MYSQL_CONTAINER:-shadowfit-mysql}
MYSQL_PW=${MYSQL_PW:?MYSQL_PW 미설정 — 대상 .env 의 MYSQL_ROOT_PASSWORD}
# MySQL SHOW GLOBAL STATUS 는 DB 가 사는 박스에서 긁는다 — 3대는 DB_SSH, 2대는 TARGET_SSH(같은 박스).
MYSQL_SSH=${DB_SSH:-$TARGET_SSH}

mkdir -p "$OUT"
LOG="$OUT/pool_sizing.tsv"
SIDE_LOG="$OUT/pool_sizing_side.tsv"
[ -f "$LOG" ] || printf "tag\tpool\tround\tcount\tok\tfail\trps\tp50_ms\tp95_ms\tp99_ms\n" > "$LOG"
[ -f "$SIDE_LOG" ] || printf "tag\tpool\tphase\tepoch\tsource\tmetric\tvalue\n" > "$SIDE_LOG"

note() { echo "  $*"; }
die()  { echo "🔴 중단 — $*" >&2; exit 1; }

if [ -n "$DB_SSH" ]; then
  note "3대 모드 — App 은 systemd(shadowfit-app), DB 는 별도 박스($DB_SSH)"
  $TARGET_SSH "systemctl is-active --quiet shadowfit-app" \
    || die "대상 박스에 shadowfit-app 유닛이 없거나 안 살아있다 (bootstrap.sh ROLE=app 확인)"
  $DB_SSH "docker ps >/dev/null 2>&1" || die "DB 박스에 못 붙거나 docker 가 없다"
else
  $TARGET_SSH "test -d $TARGET_REPO_DIR && docker ps >/dev/null 2>&1" \
    || die "대상 박스에 못 붙거나 $TARGET_REPO_DIR / docker 가 없다"
fi
command -v "$GHZ_BIN" >/dev/null 2>&1 || [ -x "$GHZ_BIN" ] || die "ghz 를 못 찾았다: $GHZ_BIN"
[ -f "$GHZ_DATA" ] || die "페이로드가 없다: $GHZ_DATA (bootstrap.sh ROLE=p6-loader 가 만든다)"

# ── 풀 사이징 적용 ──────────────────────────────────────────────────────
# 3대: App 이 bare jar 라 docker-compose override 를 못 쓴다 — systemd 유닛의 Environment= 한
#      줄을 sed 로 갈아 끼우고 재기동한다(설계 §10-2). 2대: 기존 docker-compose override 그대로.
apply_pool() {  # $1 = pool 크기
  local pool=$1
  if [ -n "$DB_SSH" ]; then
    $TARGET_SSH "sed -i 's/^Environment=SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=.*/Environment=SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=$pool/' /etc/systemd/system/shadowfit-app.service \
      && systemctl daemon-reload && systemctl restart shadowfit-app" \
      || { echo "🔴 pool=$pool systemd 재기동 실패" >&2; return 1; }
  else
    # 🔴 heredoc 은 로컬에서 $pool 을 먼저 숫자로 치환한 뒤 원격으로 그대로 보낸다(따옴표
    #    이스케이프가 필요 없다 — YAML 정수 스칼라는 따옴표 없이도 유효하다. compose 환경변수는
    #    어차피 문자열로 변환된다). 원격 heredoc 은 <<'YML'(따옴표)로 걸어 원격 bash 가 내용을
    #    한 번 더 해석하지 않게 한다 — [[project_shell_edit_escape_hazard]]와 같은 함정 회피.
    $TARGET_SSH "cd $TARGET_REPO_DIR && cat > docker-compose.pool.yml <<'YML'
services:
  shadowfit-backend:
    environment:
      SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: $pool
YML
      docker compose -f docker-compose.yml -f docker-compose.pool.yml up -d mysql shadowfit-backend" \
      || { echo "🔴 pool=$pool 구성 명령 실패" >&2; return 1; }
  fi

  # health 대기 — 최대 90초. 재기동 직후엔 이전 커넥션이 남아 첫 응답이 502/000 일 수 있다.
  local i
  for i in $(seq 1 90); do
    $TARGET_SSH "curl -sf --max-time 3 http://127.0.0.1:9090/actuator/health >/dev/null 2>&1" && break
    sleep 1
  done
  $TARGET_SSH "curl -sf --max-time 3 http://127.0.0.1:9090/actuator/health >/dev/null 2>&1" \
    || { echo "🔴 pool=$pool 재기동 후 90초 안에 health 가 안 돌아왔다" >&2; return 1; }

  assert_pool "$pool"
}

# ── 실제로 그 pool 값이 물렸는지 확인 (#253 과 같은 원칙 — «단언했다» 로 안 끝낸다) ──
assert_pool() {  # $1 = 기대값
  local want=$1 got
  got=$($TARGET_SSH "curl -sf --max-time 5 '$ACTUATOR' 2>/dev/null | grep '^hikaricp_connections_max' | awk '{print \$NF}'" 2>/dev/null | tr -d '\r')
  if [ -z "$got" ]; then
    echo "🔴 pool=$want — hikaricp_connections_max 를 못 읽었다(actuator 스크레이프 실패)" >&2
    return 1
  fi
  # 값은 부동소수(예: "15.0")로 나올 수 있다 — 정수부만 비교
  got=${got%%.*}
  if [ "$got" != "$want" ]; then
    echo "🔴 pool=$want 기대했는데 실제 hikaricp_connections_max=$got — override 가 안 먹었다" >&2
    return 1
  fi
  note "pool=$want 확인됨 (hikaricp_connections_max=$got)"
}

# ── 옆 지표 스냅샷 — coresidency_sweep.sh 의 snap_side 와 같은 패턴, pool 스윕용으로 축소 ──
SIDE_RE='^hikaricp_connections'
SIDE_MYSQL_VARS="Threads_running Threads_connected Innodb_row_lock_waits Innodb_row_lock_time"

snap_side() {  # $1=태그 $2=pool $3=국면(pre|post)
  local tag=$1 pool=$2 phase=$3 now spring my vars
  now=$(date +%s)
  spring=$($TARGET_SSH "curl -sf --max-time 5 '$ACTUATOR'" 2>/dev/null | grep -Ev '^#' | grep -E "$SIDE_RE")
  if [ -n "$spring" ]; then
    printf '%s\n' "$spring" | awk -v t="$tag" -v p="$pool" -v ph="$phase" -v e="$now" \
      '{ v=$NF; name=$0; sub(/[ \t]+[^ \t]+$/, "", name); printf "%s\t%s\t%s\t%s\tspring\t%s\t%s\n", t, p, ph, e, name, v }' >> "$SIDE_LOG"
  else
    printf "%s\t%s\t%s\t%s\tspring\t_scrape\tFAIL\n" "$tag" "$pool" "$phase" "$now" >> "$SIDE_LOG"
  fi
  vars=$(printf "'%s'," $SIDE_MYSQL_VARS); vars=${vars%,}
  my=$($MYSQL_SSH "docker exec -i -e MYSQL_PWD=$MYSQL_PW $MYSQL_CONTAINER mysql -uroot -N \
        -e \"SHOW GLOBAL STATUS WHERE Variable_name IN ($vars);\"" 2>/dev/null | tr -d '\r')
  if [ -n "$my" ]; then
    printf '%s\n' "$my" | awk -F'\t' -v t="$tag" -v p="$pool" -v ph="$phase" -v e="$now" \
      'NF>=2 { printf "%s\t%s\t%s\t%s\tmysql\t%s\t%s\n", t, p, ph, e, $1, $2 }' >> "$SIDE_LOG"
  else
    printf "%s\t%s\t%s\t%s\tmysql\t_status\tFAIL\n" "$tag" "$pool" "$phase" "$now" >> "$SIDE_LOG"
  fi
}

timeout_gate() {  # $1=태그 $2=pool — pre/post timeout_total 차이가 0인지. >0 이면 경고만(중단 안 함).
  # 🔴 $6(metric)에는 프로메테우스 라벨({pool="HikariPool-1",} 등)이 그대로 붙어 있을 수 있다
  #    (coresidency_sweep.sh 의 snap_side 와 같은 원자료 형태) — 정확히 일치(==)가 아니라
  #    접두 매치(~)로 찾는다.
  local tag=$1 pool=$2 pre post
  pre=$(awk -F'\t' -v t="$tag" '$1==t && $6 ~ /^hikaricp_connections_timeout_total/ {print $NF; exit}' "$SIDE_LOG")
  post=$(awk -F'\t' -v t="${tag}_post" '$1==t && $6 ~ /^hikaricp_connections_timeout_total/ {print $NF; exit}' "$SIDE_LOG")
  if [ -n "$pre" ] && [ -n "$post" ]; then
    python3 - "$pre" "$post" "$pool" "$tag" <<'PY'
import sys
pre, post, pool, tag = float(sys.argv[1]), float(sys.argv[2]), sys.argv[3], sys.argv[4]
if post > pre:
    print(f"🔴 [{tag}] pool={pool} timeout_total {pre:.0f}→{post:.0f} — 가용성 게이트 위반. 이 수준은 판정에서 제외 대상이다")
PY
  fi
}

run_ghz() {  # $1=태그 $2=pool
  local tag=$1 pool=$2
  printf '{"authorization":"Bearer %s"}' "$GHZ_TOKEN" > "$OUT/_meta.json"
  "$GHZ_BIN" --insecure --call ExerciseService.SavePoseDataBatch \
    --metadata-file "$OUT/_meta.json" --data-file "$GHZ_DATA" \
    -c "$C" -n "$N" -O json -o "$OUT/$tag.json" "$TARGET_HOST:6565" > "$OUT/$tag.log" 2>&1
  local rc=$?
  if [ $rc -ne 0 ] || [ ! -s "$OUT/$tag.json" ]; then
    echo "🔴 [$tag] ghz 실행 실패 (rc=$rc)" >&2
    printf "%s\t%s\t-\tFAIL\t-\t-\t-\t-\t-\t-\n" "$tag" "$pool" >> "$LOG"
    return 1
  fi
  python3 - "$OUT/$tag.json" "$tag" "$pool" "$LOG" <<'PY'
import json, sys
f, tag, pool, log = sys.argv[1:5]
j = json.load(open(f, encoding='utf-8'))
sc = j.get("statusCodeDistribution") or {}
count = j.get("count", 0); ok = sc.get("OK", 0); fail = count - ok
rps = round(j.get("rps", 0), 1)
lat = {}
for d in (j.get("latencyDistribution") or []):
    try:
        lat[int(round(float(d.get("percentage"))))] = float(d.get("latency")) / 1e6  # ns -> ms
    except (TypeError, ValueError):
        pass
p50, p95, p99 = lat.get(50, ""), lat.get(95, ""), lat.get(99, "")
round_ = tag.split('-', 1)[0] if '-' in tag else "-"
with open(log, "a", encoding='utf-8') as fh:
    fh.write(f"{tag}\t{pool}\t{round_}\t{count}\t{ok}\t{fail}\t{rps}\t{p50}\t{p95}\t{p99}\n")
PY
}

run_one() {  # $1=태그 $2=pool
  local tag=$1 pool=$2
  echo; echo "──── $tag (pool=$pool) ────"
  apply_pool "$pool" || { note "🔴 $tag 스킵 — 기동 실패"; printf "%s\t%s\t-\tSKIP\t-\t-\t-\t-\t-\t-\n" "$tag" "$pool" >> "$LOG"; return 1; }
  snap_side "$tag" "$pool" pre
  run_ghz "$tag" "$pool"
  snap_side "${tag}_post" "$pool" post
  timeout_gate "$tag" "$pool"
}

# ── 버림판 — 결과에 안 넣는다(별도 로그로 우회) ──────────────────────────
echo "=== 버림판 (워밍업, pool=15) ==="
_real_log="$LOG"; LOG=/dev/null
apply_pool 15 && run_ghz discard 15
LOG="$_real_log"

# ── 라틴 방격 3라운드 × 5수준 ─────────────────────────────────────────────
run_round() {  # $1=라운드번호, $2.. = pool 순서
  local r=$1; shift
  for pool in "$@"; do
    run_one "r${r}-p${pool}" "$pool"
  done
}
run_round 1 10 12 15 17 20
run_round 2 15 17 20 10 12
run_round 3 20 10 12 15 17

echo
echo "=== 수준별 요약 (라운드 평균) ==="
python3 - "$LOG" <<'PY'
import sys, collections
rows = collections.defaultdict(list)
with open(sys.argv[1], encoding='utf-8') as f:
    next(f)
    for line in f:
        p = line.rstrip('\n').split('\t')
        if len(p) < 10 or p[3] in ('FAIL', 'SKIP'):
            continue
        pool = p[1]
        try:
            rows[pool].append((float(p[6]), float(p[9]) if p[9] else None))
        except ValueError:
            continue
print(f"{'pool':>4}  {'판수':>3}  {'rps 평균':>9}  {'rps 최소~최대':>17}  {'p99 평균(ms)':>12}")
for pool in ('10', '12', '15', '17', '20'):
    v = rows.get(pool) or []
    if not v:
        print(f"{pool:>4}  측정 없음"); continue
    rs = [x[0] for x in v]
    p99s = [x[1] for x in v if x[1] is not None]
    p99_str = f"{sum(p99s)/len(p99s):>12.1f}" if p99s else f"{'N/A':>12}"
    print(f"{pool:>4}  {len(v):>3}  {sum(rs)/len(rs):>9.0f}  {min(rs):>7.0f}~{max(rs):<8.0f}  {p99_str}")
print("\n⚠️ 판정은 평균이 아니라 반복 분포가 겹치는지로 한다(slo-baseline.md §5-1) — 위 표는 참고용 요약이다.")
print("⚠️ pool_sizing_side.tsv 의 timeout_total pre/post 차이(콘솔에 🔴로 이미 찍힘)가 있는 수준은 가용성 게이트 위반이다.")
PY
