#!/usr/bin/env bash
# HikariCP 풀 사이징 재실험 — 10~20 사이 좁히기 (택틱 B, 3대 구성 — DB·App 분리)
#
# 설계: docs/decisions/pool-sizing-10-20-experiment-design.md §10
# 배경: 2026-09-04 1차 시도(4대: DB·App 분리+Loader+Obs)가 페이로드 세대 불일치·토큰 배선
#       부재로 실전에서 막혔고, 그 뒤 재설계한 2대(p6-target+p6-loader)판은 **코드만 쓰고
#       실행 전**이었다. 이번 재검토에서 그 2대판이 2026-08-09 4점 baseline(DB·App 분리 ·
#       AI 없음)과 **아키텍처가 달라서**(p6-target 은 MySQL+Spring+AI 가 한 박스) 비교 가능성이
#       흔들린다는 게 드러나 — **DB·App 분리를 다시 살리는 3대 구성**으로 바꿨다(사용자 결정,
#       2026-09-04). Obs 박스는 여전히 안 띄운다(Q2 는 DB 박스에서 직접 SHOW GLOBAL STATUS).
#
# 구성: **3대 — DB(ROLE=db) · App(ROLE=app, bare jar) · Loader(ROLE=p6-loader)**.
#   - DB 는 MySQL 컨테이너 하나만 뜬 박스(기존 ROLE=db 그대로, 08-09 baseline 의 DB 박스와
#     같은 성격 — «별도 박스의 MySQL»).
#   - App 은 bootstrap.sh 신규 ROLE=app 이 systemd 유닛(shadowfit-app)으로 bare jar 를
#     띄운다 — 08-09 baseline 의 App 박스(t4g.medium, bootJar 직접 실행)와 같은 성격.
#     AI 는 안 띄운다(gRPC 채널이 지연 연결이라 기동엔 지장 없다, bootstrap.sh §app 주석).
#   - **풀 전환 방식이 2대판과 다르다** — docker-compose override 가 아니라 systemd 유닛의
#     `Environment=SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=` 줄을 sed 로 갈아 끼우고
#     `systemctl restart` 한다(apply_pool 참고).
#   - **MySQL 옆 지표는 이제 DB_SSH 로 딴 박스에서** 긁는다(snap_side 참고) — App 옆
#     actuator 지표는 그대로 TARGET_SSH.
#
# 측정 방식은 원 설계(c=100 닫힌 루프, `-n 15000`)를 그대로 쓴다 — 2026-08-09 4점 baseline
# (pool 2/5/10/20 → 69%/69%/100%/100%)과 **같은 조건이라야 같은 곡선에 얹을 수 있다.**
# P6 rig 의 `--rps` 열린 루프(가정 P1 배수)는 다른 질문(가정 부하에서 여유가 얼마인가)에
# 맞는 방식이라 여기선 안 쓴다 — 페이로드 파일과 인증만 P6 관례를 빌리고, 부하 방식은
# 원 설계를 유지한다.
#
# 🔴 미검증 — 다음에 실행하기 전 반드시 눈으로 한 번 더 볼 것:
#   - systemd Environment= sed 치환이 실제로 HikariCP 설정에 반영되는지 **실전에서 확인된
#     적이 없다**(Spring Boot relaxed binding 규칙상 되는 게 맞지만 이 프로젝트에서 이
#     프로퍼티를 env override 한 전례가 없다 — 코드로만 확인했다). `assert_pool()` 이 재기동
#     직후 `hikaricp_connections_max` 를 다시 읽어 스스로 검증하지만, 그 검증 로직 자체도
#     아직 안 돌려봤다
#   - 재기동 후 health 대기 루프의 상한(60초)이 원격 DB(네트워크 홉 추가) 조건에서 충분한지
#     미검증 — App 이 로컬 MySQL 이던 p6-target 전례와 다른 조건이다
#   - ghz 의 `--data-file`(batch_multi.json, 다세션 템플릿)이 `-c 100 -n 15000` 닫힌 루프와
#     조합됐을 때 세션 분포가 기존 4점(2026-08-09, 다른 페이로드 생성기)과 **동등한 성격인지**
#     (다세션이라는 점은 같지만 생성기가 다르므로 완전한 등가성은 아니다 — 결과 문서에 명시할 것)
#   - **이 3대 구성 자체가 오늘(2026-09-04) 처음 짜였다** — DB·App 이 실제로 분리된 채
#     끝까지 도는 것을 한 번도 본 적이 없다. 첫 실행은 사실상 리허설이다
#
# 필요한 환경변수:
#   TARGET_HOST   App 박스 사설 IP — ghz 가 이 주소의 6565 를 친다
#   TARGET_SSH    App 박스로 붙는 ssh 명령 전체 (예: "ssh -i k.pem root@10.0.0.5")
#   DB_SSH        DB 박스로 붙는 ssh 명령 전체 — MySQL 옆 지표(SHOW GLOBAL STATUS)를 여기서 긁는다
#   GHZ_TOKEN     App 박스 .env 의 INTERNAL_API_TOKEN (부트스트랩이 생성·출력한 값)
#   GHZ_DATA      부하기의 batch_multi.json 경로 (기본 /root/batch_multi.json)
#   GHZ_BIN       ghz 바이너리 경로 (기본 /usr/local/bin/ghz)
#   OUT           결과 디렉터리

set -uo pipefail

: "${TARGET_HOST:?TARGET_HOST 미설정}" "${TARGET_SSH:?TARGET_SSH 미설정}" \
  "${DB_SSH:?DB_SSH 미설정 — DB 박스로 붙는 ssh 명령 전체}" "${GHZ_TOKEN:?GHZ_TOKEN 미설정}"
OUT="${OUT:?OUT 미설정}"
GHZ_DATA=${GHZ_DATA:-/root/batch_multi.json}
GHZ_BIN=${GHZ_BIN:-/usr/local/bin/ghz}
C=${C:-100}
N=${N:-15000}
ACTUATOR=${ACTUATOR:-http://127.0.0.1:9090/actuator/prometheus}
MYSQL_CONTAINER=${MYSQL_CONTAINER:-shadowfit-mysql}
MYSQL_PW=${MYSQL_PW:?MYSQL_PW 미설정 — DB 박스 .env 의 MYSQL_ROOT_PASSWORD}
APP_UNIT=${APP_UNIT:-shadowfit-app}

mkdir -p "$OUT"
LOG="$OUT/pool_sizing.tsv"
SIDE_LOG="$OUT/pool_sizing_side.tsv"
[ -f "$LOG" ] || printf "tag\tpool\tround\tcount\tok\tfail\trps\tp50_ms\tp95_ms\tp99_ms\tp50f_ms\tp95f_ms\tp99f_ms\n" > "$LOG"
[ -f "$SIDE_LOG" ] || printf "tag\tpool\tphase\tepoch\tsource\tmetric\tvalue\n" > "$SIDE_LOG"

note() { echo "  $*"; }
die()  { echo "🔴 중단 — $*" >&2; exit 1; }

$TARGET_SSH "systemctl is-enabled $APP_UNIT >/dev/null 2>&1" \
  || die "App 박스에 못 붙거나 systemd 유닛($APP_UNIT)이 없다 — bootstrap.sh ROLE=app 을 거쳤는지 확인"
$DB_SSH "docker ps --format '{{.Names}}' | grep -qx $MYSQL_CONTAINER" \
  || die "DB 박스에 못 붙거나 MySQL 컨테이너($MYSQL_CONTAINER)가 없다 — bootstrap.sh ROLE=db 를 거쳤는지 확인"
command -v "$GHZ_BIN" >/dev/null 2>&1 || [ -x "$GHZ_BIN" ] || die "ghz 를 못 찾았다: $GHZ_BIN"
[ -f "$GHZ_DATA" ] || die "페이로드가 없다: $GHZ_DATA (bootstrap.sh ROLE=p6-loader 가 만든다)"

# ── 풀 사이징 적용 — systemd 유닛의 Environment= 한 줄을 sed 로 갈아 끼우고 재기동한다 ──
# (docker-compose override 가 아니다 — App 은 bare jar 다, bootstrap.sh §app)
apply_pool() {  # $1 = pool 크기
  local pool=$1
  $TARGET_SSH "sed -i 's/^Environment=SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=.*/Environment=SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=$pool/' \
      /etc/systemd/system/$APP_UNIT.service && systemctl daemon-reload && systemctl restart $APP_UNIT" \
    || { echo "🔴 pool=$pool 재기동 명령 실패" >&2; return 1; }

  # health 대기 — 최대 60초(bootstrap.sh ROLE=app 기동 대기와 같은 상한). 원격 DB 라 첫 커넥션이
  # 풀 크기만큼 새로 트여야 해서 로컬 MySQL 이던 p6-target 전례보다 느릴 수 있다(§ 미검증 참고).
  local i
  for i in $(seq 1 60); do
    $TARGET_SSH "curl -sf --max-time 3 http://127.0.0.1:9090/actuator/health >/dev/null 2>&1" && break
    sleep 1
  done
  $TARGET_SSH "curl -sf --max-time 3 http://127.0.0.1:9090/actuator/health >/dev/null 2>&1" \
    || { echo "🔴 pool=$pool 재기동 후 60초 안에 health 가 안 돌아왔다" >&2; return 1; }

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
  my=$($DB_SSH "docker exec -i -e MYSQL_PWD=$MYSQL_PW $MYSQL_CONTAINER mysql -uroot -N \
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
    printf "%s\t%s\t-\tFAIL\t-\t-\t-\t-\t-\t-\t-\t-\t-\n" "$tag" "$pool" >> "$LOG"
    return 1
  fi
  python3 - "$OUT/$tag.json" "$tag" "$pool" "$LOG" <<'PY'
import json, sys
f, tag, pool, log = sys.argv[1:5]
j = json.load(open(f, encoding='utf-8'))
sc = j.get("statusCodeDistribution") or {}
count = j.get("count", 0); ok = sc.get("OK", 0); fail = count - ok
rps = round(j.get("rps", 0), 1)

# 🔴 ghz 의 latencyDistribution 은 status 를 안 가린다(OK·실패 응답이 섞인 전체 백분위) —
#    그래서 여기선 안 쓴다. details[] 는 요청 하나하나의 {latency(ns), status} 를 담고 있어
#    status 별로 나눠 직접 백분위(최근접-순위)를 계산한다.
def pct(sorted_ns, p):
    if not sorted_ns:
        return ""
    idx = min(len(sorted_ns) - 1, int(len(sorted_ns) * p / 100))
    return round(sorted_ns[idx] / 1e6, 3)  # ns -> ms

details = j.get("details") or []
ok_lat = sorted(d["latency"] for d in details if d.get("status") == "OK")
fail_lat = sorted(d["latency"] for d in details if d.get("status") != "OK")
p50, p95, p99 = pct(ok_lat, 50), pct(ok_lat, 95), pct(ok_lat, 99)
p50f, p95f, p99f = pct(fail_lat, 50), pct(fail_lat, 95), pct(fail_lat, 99)

round_ = tag.split('-', 1)[0] if '-' in tag else "-"
with open(log, "a", encoding='utf-8') as fh:
    fh.write(f"{tag}\t{pool}\t{round_}\t{count}\t{ok}\t{fail}\t{rps}\t{p50}\t{p95}\t{p99}\t{p50f}\t{p95f}\t{p99f}\n")
PY
}

run_one() {  # $1=태그 $2=pool
  local tag=$1 pool=$2
  echo; echo "──── $tag (pool=$pool) ────"
  apply_pool "$pool" || { note "🔴 $tag 스킵 — 기동 실패"; printf "%s\t%s\t-\tSKIP\t-\t-\t-\t-\t-\t-\t-\t-\t-\n" "$tag" "$pool" >> "$LOG"; return 1; }
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
        if len(p) < 13 or p[3] in ('FAIL', 'SKIP'):
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
