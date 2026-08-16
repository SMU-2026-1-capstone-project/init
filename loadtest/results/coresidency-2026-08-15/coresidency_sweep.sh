#!/bin/bash
# 동거 용량 본 측정 스윕 (P6) — 설계: docs/decisions/ai-coresidency-capacity.md
#
# 🔴 **`probe.sh` 가 통과해 있어야 한다.** 게이트 없이 이것만 돌리면 «세션이 안 열린 채
#    전 프레임이 거절됐는데 표는 정상» 인 판이 나온다.
#
# 팔 (설계 §3 · 팔 A 는 2026-08-16 에 **(a)안으로 재정의**됐다 — #222):
#   A  AI + Spring + MySQL, 캡 없음, **ghz 부하 없음**  — 유휴 동거 기준선
#   B  A 와 같은 구성 + **ghz 부하**                     — 옆이 «일할 때» 의 비용 (#223)
#   C  B + CPU 캡                                        — 캡이 무엇을 지키는지 (#212)
#   D  C + 관측 스택                                     — 선택. ARMS 에서 빼면 판이 25% 준다
#
# 🔴 **A 와 B 는 구성이 같다.** 갈리는 것은 ghz 부하 하나뿐이다. 초판의 «A = AI 단독» 은
#    성립할 수 없었다 — 부하기가 세션을 Spring 으로 열기 때문에 Spring 을 내리면 판이
#    통째로 setup_fail 이다(#222).
#    대가는 설계 §3-1 에 적혀 있다: A↔156 의 차이가 «서비스 경로 오버헤드» 만이 아니라
#    **«+ 유휴 동거 비용»** 이 되어 둘이 안 갈린다.
#
# 🔴 **팔 B 도 메모리 캡은 건다.** «캡 없음» 을 곧이곧대로 만들면 첫 세션에서 RuntimeError 라
#    팔이 아예 안 돈다(#214). 이 팔이 흔드는 것은 **CPU 캡** 하나다.
#
# 사용 (부하기 박스에서):
#   HOST=10.0.0.5 TOKEN=<AI_PUBLIC_TOKEN> \
#   GHZ_RPS=<정한 값> GHZ_DATA=/tmp/batch_multi.json GHZ_TOKEN=<INTERNAL_API_TOKEN> \
#     bash coresidency_sweep.sh
#
# 從 부하(ghz)는 팔 B·C·D 에만 걸린다. 팔 A 는 «옆이 유휴» 가 정의다.
#
# 무인 실행 전 축소 리허설:  LEVELS="5 10" DUR=20 REPEATS=1 bash coresidency_sweep.sh

set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-$HERE}

HOST=${HOST:?측정 대상 호스트 IP 가 필요하다}
BASE=${BASE:-http://$HOST:8080}
AI=${AI:-http://$HOST:8000}
TOKEN=${TOKEN:?AI_PUBLIC_TOKEN}
SSH=${SSH:-ssh root@$HOST}          # 팔 전환은 대상 박스에서 compose 를 다시 세운다
REPO_DIR=${REPO_DIR:-/root/init}

ARMS=${ARMS:-"A B C"}               # D 를 넣으려면 "A B C D"
# 동시 세션 ramp — 2026-08-17 확정(설계 §5-3 ⑥). 08-16 격자 `20 40 60 80 120 160` 은
# 천장을 **「80 초과 120 미만」 구간으로만** 짚었다 — 80 은 목표의 100%, 120 은 88% 라 그
# 사이에 관측점이 없다. 6레벨로 넓힌 이유가 「천장이 40~80 이면 구간으로밖에 못 적는다」
# 였는데 천장이 한 칸 위에 있어 **같은 문제가 반복됐다.** 90·100 을 넣어 점으로 짚는다.
# **최고 160 은 고정** — GHZ_RPS 유도가 «160세션 × 0.12» 라 최고 레벨이 바뀌면 從 부하 값도
# 같이 바뀐다. 대가는 판이 팔당 6 → 8 로 느는 것(라운드 +40분).
LEVELS=${LEVELS:-"20 40 60 80 90 100 120 160"}
DUR=${DUR:-90}                      # 판당 측정 구간(초). 앞뒤 5초는 부하기가 버린다
REPEATS=${REPEATS:-3}               # 본판. 버림판 1은 팔마다 별도로 돈다

# ── 레벨 순서 치환 — #252 (2026-08-17) ───────────────────────────────────
#
# 🔴 08-16 두 라운드는 **아홉 블록 전부 오름차순**이었다. 블록 하나가 12~17분이고 그 사이
#    처리량이 흐르면(1라운드 분당 0.09~0.35%) **뒤에 오는 레벨이 체계적으로 유리**해진다 —
#    그리고 뒤에 오는 것이 하필 plateau 를 정의하는 상위 레벨이다.
#
# 🔴 **「라운드마다 반전」으로는 안 고쳐진다.** 오름/내림/오름(3판)이면 레벨의 «평균 위치» 가
#    여전히 레벨에 **완전 비례**한다(상관 r = 1.00). 실제로 계산해 보고 골랐다:
#        반전 **r=1.000** · shift1 0.580 · **shift2 0.361** · shift3 0.411 · shift4 0.907 · shift6 0.316
#    (레벨 «값» 20·40·60·80·90·100·120·160 과 «블록 안 평균 위치» 의 상관. 8레벨 × 3판)
#    그래서 `rotate_arms` 와 같은 방식의 **치환**을 쓴다.
# ⚠️ 최적 shift 는 (레벨 수 · 반복 수 · 레벨 «값» 간격)에 딸린 값이다. 2 는 **8레벨 × 3판**
#    에서 고른 값이고(6 이 0.316 으로 사실상 같다 — 8 기준 거울상이다), 격자를 바꾸면 다시
#    계산해야 한다. 그리고 **3판으로는 8자리를 완전 균형 못 잡는다** — 이 치환은 상관을
#    «없애는» 것이 아니라 «1.000 → 0.361 로 줄이는» 것이다. 결과에 그대로 적는다.
LEVEL_SHIFT=${LEVEL_SHIFT:-2}
STATS_SEC=${STATS_SEC:-5}           # docker stats 폴링 간격 — 관측이 대상을 흔드는 비용

# ── 從 부하: Spring·MySQL (ghz SavePoseDataBatch) — #223 ─────────────────
#
# 팔 B·C·D 는 «옆이 일하는 상태» 여야 한다. 그 부하는 rep 에서 나오지 않으므로(합성 인체로는
# rep 이 0 — §2) **기존 ghz rig 의 경로를 그대로** 건다.
#
# 🔴 **요청/초 고정**(2026-08-16 사용자 결정). 부하기의 동시성(`-c`)이 아니라 **rate** 를
#    고정한다 — 세션 수를 흔드는 실험에서 「옆의 부하」까지 같이 흔들리면 조작 변수가 둘이 된다.
# 🔴 **`GHZ_RPS` 에 기본값을 두지 않는다.** 이 값은 «생겨난» 값이 아니라 «정한» 값이라
#    (README §2), 근거 없이 숫자를 박으면 그게 출처 없는 기준값이 된다. 부르는 쪽이 정하고,
#    무엇을 근거로 정했는지를 결과의 조건 칸에 적는다.
GHZ_BIN=${GHZ_BIN:-/home/ec2-user/go/bin/ghz}
GHZ_DATA=${GHZ_DATA:-}                       # gen_batch_multi.py 산출물 (부하기 로컬 경로)
GHZ_TOKEN=${GHZ_TOKEN:-}                     # INTERNAL_API_TOKEN — Spring gRPC 메타데이터
GHZ_RPS=${GHZ_RPS:-}                         # 요청/초. 기본값 없음(위)
GHZ_CONC=${GHZ_CONC:-50}                     # ghz 기본값. rate 를 못 따라가면 **이것이 상한이 된다**
GHZ_TARGET=${GHZ_TARGET:-$HOST:6565}
GHZ_PAD=${GHZ_PAD:-5}                        # AI 측정 창을 ghz 창 «안쪽» 에 두기 위한 앞뒤 여유(초)
MYSQL_CONTAINER=${MYSQL_CONTAINER:-shadowfit-mysql}
# 🔴 자격증명을 박아두지 않는다. 로컬 dev 는 shadowfit/shadowfit 이지만 **측정 박스는 다르다** —
#    `bootstrap.sh` 가 rig 기본 PW(1234)로 세운다. 박아두면 시드 확인이 «세션이 없다» 가
#    아니라 «못 물었다» 로 떨어지고, 그건 라운드를 멈추게 한다(2026-08-16 EC2 에서 실제로 멈췄다).
#    비밀번호는 `MYSQL_PWD` 로 넘긴다 — argv 에 안 실리고 mysql 의 경고도 안 난다.
MYSQL_USER=${MYSQL_USER:-shadowfit}
MYSQL_PW=${MYSQL_PW:-shadowfit}

# 🔴 결과 디렉터리를 스윕이 만든다. 부르는 쪽이 만들어 줄 거라 믿으면, 안 만들어졌을 때
#    «표가 안 써지는데 판은 도는» 상태가 된다 — #203 이 정확히 그 사고였다(백업 rig).
mkdir -p "$OUT" || { echo "🔴 결과 디렉터리를 못 만든다: $OUT" >&2; exit 1; }

LOG="$OUT/coresidency.tsv"
# 🔴 `warm_lo`·`warm_hi` 는 그 판의 **정상 상태 구간을 epoch 로** 적은 것이다. 이게 없으면
#    `stats_*.tsv`(epoch)를 요청 표(t0 기준 상대초)와 같은 축에 못 올려, 포화 구간의
#    컨테이너별 CPU 를 사후에 못 자른다 — Q2 의 답이 거기에 있다(2026-08-16 설계 대조).
[ -f "$LOG" ] || printf "arm\tround\tsessions\treq\trps\tdetect_pct\tp50\tp95\tp99\tnolease\tnopose\tsetup_fail\twarm_lo\twarm_hi\n" > "$LOG"

# 🔴 ghz 결과를 본 표에 섞지 않는다. 「AI 가 몇 세션을 먹었나」와 「옆에 얼마가 걸렸나」는
#    다른 축이고, 뭉치면 둘 다 나빠진다(README 「세 결과를 뭉치지 않는다」와 같은 규약).
# 🔴 **지연 백분위를 같이 남긴다** (#254, 2026-08-17). 가설 H3 의 반증 조건이 «팔 C 에서
#    Spring p99·MySQL 지표가 팔 B 와 차이 없다» 인데, 08-16 두 라운드 모두 이 표에 **지연
#    열이 없어** 판정할 자료가 아예 안 생겼다. ghz JSON 은 이미 `latencyDistribution` 을
#    담고 있었고 이 스윕이 그 파일을 이미 파싱하고 있었다 — **꺼내 적기만 하면 됐다.**
#    새 열은 **끝에만** 붙인다: `run_all.sh` 의 `cores_assert_ghz` 가 $1~$8 을 위치로 읽는다.
GHZ_LOG="$OUT/ghz.tsv"
[ -f "$GHZ_LOG" ] || printf "tag\tarm\tsessions\ttarget_rps\tachieved_rps\tcount\tok\tfail\tp50_ms\tp95_ms\tp99_ms\n" > "$GHZ_LOG"

step() { echo; echo "════ $* ════"; }
note() { echo "     $*"; }

# ── 팔 전환 ──────────────────────────────────────────────────────────────
# compose 파일을 바꿔 끼우는 것이 아니라 **override 를 얹는다** — 원본 compose 를 실험이
# 고치면 다음 라운드가 «무엇을 쟀는지» 를 잃는다.
apply_arm() {
  local arm=$1
  note "팔 $arm 구성"
  case $arm in
    # 🔴 A 는 «AI 단독» 이 아니다 ((a)안, 2026-08-16 사용자 결정 — #222). 컨테이너 구성은
    #    B 와 **똑같이** 세우고, A 가 흔드는 것은 «옆이 일하는가»(ghz 부하) 하나다.
    #    Spring 을 내리면 부하기가 세션을 못 연다(`load_ai.py:107`) — 그게 초판의 결함이었다.
    A) $SSH "cd $REPO_DIR && rm -f docker-compose.cap.yml; \
             docker compose --profile obs stop prometheus grafana mysqld-exporter 2>/dev/null; \
             docker compose up -d mysql shadowfit-backend shadowfit-ai" ;;
    B) $SSH "cd $REPO_DIR && rm -f docker-compose.cap.yml; \
             docker compose --profile obs stop prometheus grafana mysqld-exporter 2>/dev/null; \
             docker compose up -d mysql shadowfit-backend shadowfit-ai" ;;
    C) $SSH "cd $REPO_DIR && cat > docker-compose.cap.yml <<'YML'
services:
  shadowfit-ai:   { cpus: \"\${AI_CPUS:?팔 C 는 AI_CPUS 가 필요하다}\" }
  mysql:          { cpus: \"\${MYSQL_CPUS:?}\" }
  shadowfit-backend: { cpus: \"\${BACKEND_CPUS:?}\" }
YML
             docker compose --profile obs stop prometheus grafana mysqld-exporter 2>/dev/null; \
             docker compose -f docker-compose.yml -f docker-compose.cap.yml up -d mysql shadowfit-backend shadowfit-ai" ;;
    D) $SSH "cd $REPO_DIR && docker compose -f docker-compose.yml -f docker-compose.cap.yml --profile obs up -d" ;;
    *) echo "🔴 모르는 팔: $arm" >&2; return 1 ;;
  esac
}

# ── 從 부하 구동 ─────────────────────────────────────────────────────────
#
# 팔 A 는 «옆이 유휴» 가 정의이므로 여기를 안 탄다. 그 사실도 표에 남긴다 — 빈칸은
# «안 걸었다» 와 «못 걸었다» 를 구분해 주지 않는다(_rig.sh 의 FAIL≠0 규약).
arm_uses_ghz() { case $1 in A) return 1 ;; *) return 0 ;; esac; }

ghz_configured() {
  [ -n "$GHZ_RPS" ] && [ -n "$GHZ_DATA" ] && [ -n "$GHZ_TOKEN" ] \
    && [ -f "$GHZ_DATA" ] && [ -x "$GHZ_BIN" ]
}

# 🔴 페이로드가 쓰는 세션이 대상 DB 에 없으면 **전 요청이 FK 로 실패**한다. 그런데 ghz 는
#    요청 수·지연을 정상으로 찍으므로, 그 판은 «옆이 일하는 중» 으로 보이면서 실제로는
#    MySQL 이 롤백만 한다. P5 rig 이 같은 함정에 `assert_sessions_exist` 를 세운 이유다.
assert_ghz_payload_seeded() {
  local ids want got
  ids=$(python - "$GHZ_DATA" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding='utf-8'))
rows = d if isinstance(d, list) else [d]
s = sorted({r.get("sessionId") or r.get("session_id") for r in rows} - {None})
print(",".join(str(x) for x in s))
PY
  ) || { echo "  🔴 페이로드에서 세션 id 를 못 뽑았다: $GHZ_DATA" >&2; return 1; }
  [ -n "$ids" ] || { echo "  🔴 페이로드에 세션 id 가 없다: $GHZ_DATA" >&2; return 1; }
  want=$(echo "$ids" | tr ',' '\n' | grep -c .)
  got=$($SSH "docker exec -i -e MYSQL_PWD=$MYSQL_PW $MYSQL_CONTAINER mysql -u$MYSQL_USER shadowfit -N \
        -e \"SELECT COUNT(*) FROM exercise_sessions WHERE id IN ($ids);\"" 2>/dev/null \
        | tr -d '[:space:]')
  case "$got" in
    ''|*[!0-9]*) echo "  🔴 대상 DB 에 세션 수를 물어보지 못했다 — «없다» 가 아니라 «못 물었다» 다" >&2; return 1 ;;
  esac
  [ "$got" = "$want" ] \
    || { echo "  🔴 ghz 페이로드의 세션 $want 개 중 $got 개만 있다 — 전 요청이 FK 로 실패한다" >&2; return 1; }
  # 판 사이 정리(reset_rows)가 지울 범위. 여기서 한 번 정해 두고 그대로 쓴다.
  GHZ_SESS_LO=$(echo "$ids" | tr ',' '\n' | sort -n | head -1)
  GHZ_SESS_HI=$(echo "$ids" | tr ',' '\n' | sort -n | tail -1)
  note "從 부하 세션 시드 $got/$want 확인 (id $GHZ_SESS_LO~$GHZ_SESS_HI)"
}

# 🔴 ghz 는 판마다 행을 **쌓는다.** 안 지우면 뒤 판일수록 큰 테이블에 쓰게 되고, 그러면
#    「판 순서」가 「세션 수」와 같은 축에 겹친다 — 이 프로젝트가 라틴 방격까지 쓰며 막는 바로
#    그 오염이다(P5 rig 의 `reset_rows` 와 같은 역할).
reset_ghz_rows() {
  ghz_configured || return 0
  [ -n "${GHZ_SESS_LO:-}" ] || return 0
  $SSH "docker exec -i -e MYSQL_PWD=$MYSQL_PW $MYSQL_CONTAINER mysql -u$MYSQL_USER shadowfit \
        -e \"DELETE FROM pose_data WHERE session_id BETWEEN $GHZ_SESS_LO AND $GHZ_SESS_HI;\"" \
    >/dev/null 2>&1 \
    || echo "  ⚠️ 從 부하 행 정리 실패 — 다음 판이 «커진 테이블» 을 잰다. 결과에 적을 것" >&2
}

start_ghz() {  # $1 = 태그.  ghz 창이 AI 측정 창을 **감싸도록** 먼저 띄운다
  GHZ_PID=""
  arm_uses_ghz "$CUR_ARM" || return 0
  local span=$(( DUR + 2 * GHZ_PAD ))
  printf '{"authorization":"Bearer %s"}' "$GHZ_TOKEN" > "$OUT/_ghz_meta.json"
  "$GHZ_BIN" --insecure --call ExerciseService.SavePoseDataBatch \
      --metadata-file "$OUT/_ghz_meta.json" --data-file "$GHZ_DATA" \
      --rps "$GHZ_RPS" -c "$GHZ_CONC" -z "${span}s" \
      -O json -o "$OUT/ghz_$1.json" "$GHZ_TARGET" > "$OUT/ghz_$1.log" 2>&1 &
  GHZ_PID=$!
  echo "$GHZ_PID" > "/tmp/ghz_$1.pid"   # #250 — 부하기 샘플러가 이 PID 의 CPU 를 따로 센다
  note "從 부하 시작 — ${GHZ_RPS} req/s 고정 · c=$GHZ_CONC · ${span}s (AI 창 ${DUR}s 를 앞뒤 ${GHZ_PAD}s 로 감싼다)"
  sleep "$GHZ_PAD"
}

stop_ghz() {  # $1 = 태그
  if ! arm_uses_ghz "$CUR_ARM"; then
    printf "%s\t%s\t%s\t-\t-\t-\t-\t-\t-\t-\t-\n" "$1" "$CUR_ARM" "$CUR_N" >> "$GHZ_LOG"
    return 0
  fi
  [ -n "${GHZ_PID:-}" ] || return 0
  local i
  for i in $(seq 1 $(( GHZ_PAD + 20 ))); do
    kill -0 "$GHZ_PID" 2>/dev/null || break
    sleep 1
  done
  # 살아남은 부하는 **다음 판을 오염시킨다** — DDL rig 에서 실제로 났던 사고와 같은 모양이다.
  if kill -0 "$GHZ_PID" 2>/dev/null; then
    echo "  ⚠️ ghz 가 창을 넘겼다 — KILL 한다 (다음 판 오염 방지)" >&2
    kill "$GHZ_PID" 2>/dev/null
  fi
  wait "$GHZ_PID" 2>/dev/null

  # 🔴 「ghz 가 돌았다」와 「부하가 걸렸다」는 다르다. OK 가 0 이면 그 판의 «동거» 는 거짓이다.
  python - "$OUT/ghz_$1.json" "$1" "$CUR_ARM" "$CUR_N" "$GHZ_RPS" "$GHZ_LOG" <<'PY'
import json, sys
f, tag, arm, n, target, log = sys.argv[1:7]
try:
    j = json.load(open(f, encoding='utf-8'))
except Exception as e:
    print(f"  🔴 ghz 리포트를 못 읽었다 ({f}): {e} — 이 판은 «옆이 일했다» 를 단언할 수 없다")
    open(log, "a", encoding='utf-8').write(f"{tag}\t{arm}\t{n}\t{target}\tFAIL\t-\t-\t-\t-\t-\t-\n")
    raise SystemExit(0)
sc = j.get("statusCodeDistribution") or {}
count = j.get("count", 0); ok = sc.get("OK", 0); fail = count - ok
rps = round(j.get("rps", 0), 1)

# 🔴 지연 백분위 (#254). ghz 는 ns 로 준다 — ms 로 바꿔 적는다.
#    없는 백분위를 0 으로 채우지 않는다. «0ms» 와 «안 나왔다» 는 다른 사실이고,
#    이 rig 이 반복해서 당한 실패 모드가 «없는 값을 그럴듯한 값으로 채우기» 다.
lat = {}
for d in (j.get("latencyDistribution") or []):
    try:
        lat[int(round(float(d.get("percentage"))))] = float(d.get("latency"))
    except (TypeError, ValueError):
        pass
def ms(p):
    v = lat.get(p)
    return "-" if v is None else f"{v / 1e6:.1f}"
p50, p95, p99 = ms(50), ms(95), ms(99)
open(log, "a", encoding='utf-8').write(
    f"{tag}\t{arm}\t{n}\t{target}\t{rps}\t{count}\t{ok}\t{fail}\t{p50}\t{p95}\t{p99}\n")
if p50 == "-":
    print(f"  ⚠️ ghz 리포트에 latencyDistribution 이 없다 ({f}) — H3(캡이 옆을 지키는가)는 이 판에서 못 읽는다")
if ok == 0:
    print(f"  🔴 從 부하의 성공 응답이 0 이다 ({count}건 전부 실패) — 이 판은 «유휴 동거» 를 잰 것이다")
elif fail:
    print(f"  ⚠️ 從 부하 실패 {fail}/{count} — 사유를 {f} 에서 볼 것")
if rps and float(target) and rps < float(target):
    print(f"  ⚠️ 실측 {rps} req/s < 목표 {target} — 부하기가 rate 를 못 따라갔다(-c 가 상한이거나 서버가 느리다)")
    print("     조건 칸에는 **실측값**을 적는다. 「정한 값」이 실제로 걸린 값과 다르면 그건 다른 조건이다")
PY
}

# ── 팔이 실제로 갈리는가 ─────────────────────────────────────────────────
#
# 🔴 (a)안 이후 A 와 B 를 가르는 것은 **ghz 부하 하나**인데, 그 부하가 아직 이 스윕에 없다
#    (#223). 그 상태로 둘을 같이 돌리면 **같은 조건을 두 팔로 찍은 표**가 나온다 —
#    숫자는 다 나오고 `setup_fail` 도 0 이라 사후에는 «B 가 A 와 비슷하다 = 동거 비용이 작다»
#    로 읽힌다. 이 프로젝트가 반복해 밟은 «숫자는 나오는데 다른 것을 잰» 사고(#201·#202)라
#    사후 주의가 아니라 **시작 전에** 막는다.
assert_arms_distinguishable() {
  case " $ARMS " in *" A "*) ;; *) return 0 ;; esac
  case " $ARMS " in *" B "*) ;; *) return 0 ;; esac
  ghz_configured && return 0
  cat >&2 <<MSG

🔴 팔 A 와 B 가 같은 조건이다 — 라운드를 시작하지 않는다.
   (a)안 이후 두 팔의 구성은 동일하고 갈리는 것은 ghz(SavePoseDataBatch) 부하뿐인데,
   그 부하의 설정이 비어 있다. 이대로 돌면 같은 것을 두 번 재고, 표는 정상으로 보인다.

   지금 값:  GHZ_RPS='$GHZ_RPS'  GHZ_DATA='$GHZ_DATA'  GHZ_TOKEN=$([ -n "$GHZ_TOKEN" ] && echo 설정됨 || echo 비었음)
             GHZ_BIN='$GHZ_BIN' $([ -x "$GHZ_BIN" ] && echo '(실행 가능)' || echo '(없거나 실행 불가)')

   길은 둘이다:
     · 위 넷을 채워서 돈다        ← 설계가 의도한 라운드
     · ARMS="A" 로만 돈다         ← 유휴 동거 기준선 하나만 얻는다. B·C·D 는 전부 從 부하를
                                    쓰므로 «ghz 없는 라운드» 는 A 단독뿐이다
MSG
  return 1
}

# ── 판 사이 초기화 ───────────────────────────────────────────────────────
# 🔴 세션 상태는 AI **프로세스 메모리**에 있다(`session_state.py:243`). 재기동 없이 다음 판을
#    돌리면 앞 판의 세션이 검출기 풀 자리를 물고 있어 «nolease» 가 앞 판 탓으로 난다.
# 🔴 이 앱은 **회원당 활성 세션을 1개로 강제**한다 — 이미 진행 중이면 생성이 409 다
#    (`SESSION_ALREADY_IN_PROGRESS`). 판이 끝날 때 부하기가 세션을 닫지만, 닫지 못한 세션이
#    하나라도 남으면 **그 계정은 다음 판부터 영영 막힌다.** 그러면 «동시 세션 수» 가 목표값보다
#    조용히 작아지고, 표에는 setup_fail 로만 남는다.
#    2026-08-16 EC2 첫 실행에서 실제로 5세션 중 2개가 이렇게 빠졌다.
#    부하기 계정(cores%)만 건드린다 — 從 부하의 시드 세션(901~1900)은 이미 COMPLETED 다.
reset_sessions() {
  $SSH "docker exec -i -e MYSQL_PWD=$MYSQL_PW $MYSQL_CONTAINER mysql -u$MYSQL_USER shadowfit \
        -e \"UPDATE exercise_sessions s JOIN users u ON u.id = s.member_id \
             SET s.status='COMPLETED', s.end_time=NOW() \
             WHERE s.status='IN_PROGRESS' AND u.email LIKE 'cores%@shadowfit.local';\"" \
    >/dev/null 2>&1 \
    || echo "  ⚠️ 남은 IN_PROGRESS 세션을 못 걷었다 — 다음 판의 세션 수가 목표보다 작아진다" >&2
}

reset_between() {
  reset_sessions          # 계정을 막고 있는 앞 판의 세션부터 푼다
  reset_ghz_rows          # 從 부하가 쌓은 행을 먼저 지운다 (재기동 대기와 겹쳐도 되는 일이다)
  $SSH "cd $REPO_DIR && docker compose restart shadowfit-ai >/dev/null 2>&1"
  sleep 15
}

# ── 지표 샘플러 ──────────────────────────────────────────────────────────
start_stats() {  # $1 = 태그
  $SSH "docker stats --no-stream --format '{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}' > /tmp/s_$1.tsv" >/dev/null 2>&1
  ( while :; do
      $SSH "docker stats --no-stream --format '{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}'" 2>/dev/null \
        | sed "s/^/$(date +%s)\t/" >> "$OUT/stats_$1.tsv"
      sleep "$STATS_SEC"
    done ) & echo $! > "/tmp/stats_$1.pid"
}
stop_stats() { [ -f "/tmp/stats_$1.pid" ] && kill "$(cat "/tmp/stats_$1.pid")" 2>/dev/null; rm -f "/tmp/stats_$1.pid"; }

# ── 부하기(러너 자신) 샘플러 — #250 ──────────────────────────────────────
#
# 🔴 **08-15·08-16 두 라운드가 대상 박스만 걷었다.** 그래서 「천장이 서버인가 부하기인가」가
#    안 갈렸고, 팔 A↔B 대조(= 동거 비용)가 통째로 판정 불가로 끝났다 — ghz 프로세스가
#    **부하기에서** 돌기 때문에 두 팔의 차이에 부하기 쪽 경합이 섞여 있다.
#
# 여기는 컨테이너가 아니므로 `docker stats` 가 아니라 `/proc` 를 직접 읽는다.
# **`mpstat`(sysstat) 을 안 쓴다** — 부트스트랩이 깔아주지 않아서, 없으면 이 샘플러가
# 조용히 빈 파일을 남긴다. 「걷은 줄 알았는데 안 걷혔다」가 정확히 이 이슈의 실패 모드다.
#
# 스케일은 **대상 쪽 `docker stats` 와 같다** — 100% = 1 vCPU. 부하기가 c7i.large(2 vCPU)
# 이므로 **200% 가 포화**다. 판정선이 그것이다.
LOADER_NCPU=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 1)
LOADER_HZ=$(getconf CLK_TCK 2>/dev/null || echo 100)

_cpu_busy_total() {   # 전체 busy·total jiffies 를 한 줄로
  awk '/^cpu /{idle=$5+$6; tot=0; for(i=2;i<=NF;i++) tot+=$i; print tot-idle, tot; exit}' /proc/stat
}
_pid_jiffies() {      # $1.. = PID 들 → utime+stime 합 (jiffies)
  local sum=0 p uv sv
  for p in "$@"; do
    [ -d "/proc/$p" ] || continue
    # utime·stime 은 14·15번째 필드인데 comm 에 공백·괄호가 들어갈 수 있어 **')' 뒤부터** 센다.
    # `-F') '` 로 자르고 마지막 조각을 쓰면 comm 이 무엇이든 안전하다.
    read -r uv sv <<<"$(awk -F') ' '{split($NF,f," "); print f[12], f[13]}' "/proc/$p/stat" 2>/dev/null)"
    [ -n "${uv:-}" ] && [ -n "${sv:-}" ] && sum=$((sum + uv + sv))
  done
  echo "$sum"
}
_proc_jiffies() {     # $1 = pgrep -f 패턴 → 매칭 프로세스들의 합
  # shellcheck disable=SC2046
  _pid_jiffies $(pgrep -f "$1" 2>/dev/null)
}
_ghz_jiffies() {      # $1 = 태그 → start_ghz 가 남긴 PID 하나. 팔 A 는 파일이 없어 0
  local f="/tmp/ghz_$1.pid"
  [ -f "$f" ] || { echo 0; return; }
  _pid_jiffies "$(cat "$f")"
}

start_loader_stats() {  # $1 = 태그
  local f="$OUT/loader_$1.tsv"
  printf 'epoch\tcpu_pct\tload_ai_pct\tghz_pct\tload1\tmem_used_mib\tmem_total_mib\n' > "$f"
  ( b0=""; t0=""; a0=0; g0=0
    read -r b0 t0 <<<"$(_cpu_busy_total)"
    a0=$(_proc_jiffies 'load_ai\.py'); g0=$(_ghz_jiffies "$1")
    while :; do
      sleep "$STATS_SEC"
      read -r b1 t1 <<<"$(_cpu_busy_total)"
      a1=$(_proc_jiffies 'load_ai\.py'); g1=$(_ghz_jiffies "$1")
      dt=$((t1 - t0)); db=$((b1 - b0))
      if [ "$dt" -gt 0 ]; then
        awk -v e="$(date +%s)" -v db="$db" -v dt="$dt" -v da="$((a1 - a0))" -v dg="$((g1 - g0))" \
            -v n="$LOADER_NCPU" -v hz="$LOADER_HZ" -v s="$STATS_SEC" \
            -v l1="$(awk '{print $1}' /proc/loadavg)" \
            -v mem="$(awk '/^MemTotal/{t=$2}/^MemAvailable/{a=$2}
                           END{ if (t=="" || a=="") printf "-1\t-1";      # 🔴 MemAvailable 이 없으면
                                else printf "%.1f\t%.1f",(t-a)/1024,t/1024 }' /proc/meminfo)" \
            'BEGIN{printf "%s\t%.1f\t%.1f\t%.1f\t%s\t%s\n", e, 100*db/dt*n, 100*da/(hz*s), 100*dg/(hz*s), l1, mem}' >> "$f"
      fi
      b0=$b1; t0=$t1; a0=$a1; g0=$g1
    done ) & echo $! > "/tmp/loader_$1.pid"
}
stop_loader_stats() { [ -f "/tmp/loader_$1.pid" ] && kill "$(cat "/tmp/loader_$1.pid")" 2>/dev/null; rm -f "/tmp/loader_$1.pid"; }

# ── 옆(Spring·MySQL) 지표 스냅샷 — #254 ──────────────────────────────────
#
# 🔴 **H3 의 반증 조건이 이 표다.** *「캡을 걸면 총 용량은 줄지만 다른 서비스가 산다」* 는
#    «팔 C 에서 Spring p99·MySQL 지표가 팔 B 와 차이 없다» 로 반증되는데, 08-16 두 라운드
#    모두 **그 열이 아예 생성되지 않았다.** 설계 §4 는 세 소스(docker stats · mysqld_exporter ·
#    actuator)를 적었는데 rig 은 첫째만 걷고 있었다 — 그래서 「캡 축이 닫혔다」가 실제로는
#    «AI 처리량이 캡만큼 깎였다» 였고, #212 가 물은 «옆이 사는가» 는 세 라운드째 미답이다.
#
# 판당 **3회**로 묶는다. 관측이 관측 대상을 흔드는 비용(설계 §8)을 시계열로 늘리지 않는다.
#   · `pre` / `post`  — 카운터(요청 수·fsync·lock wait)는 **post − pre** 가 그 판의 «일» 이다
#   · `mid`           — 게이지(`Threads_running`·Hikari `pending`)는 **여기서만 뜻이 있다.**
#                       앞뒤 두 점은 부하가 없는 순간이라, 두 점만 찍으면 «포화 때 옆이
#                       어땠나» 가 통째로 안 남는다
#
# 🔴 **actuator 는 9090 이고, compose 가 그 포트를 127.0.0.1 에만 연다**(`application.yml` [6] ·
#    `docker-compose.yml:67`). 부하기에서 직접 못 긁는다 — **대상 박스 안에서** curl 해야 하고,
#    그래서 SG 에 포트를 더 열지 않아도 된다.
SIDE_LOG="$OUT/side.tsv"
[ -f "$SIDE_LOG" ] || printf "tag\tarm\tsessions\tphase\tepoch\tsource\tmetric\tvalue\n" > "$SIDE_LOG"

ACTUATOR=${ACTUATOR:-http://127.0.0.1:9090/actuator/prometheus}
# 전부 담지 않는다 — 판당 3회 × 78판이라 원문이면 표가 사람이 못 읽는 크기가 된다.
SIDE_RE=${SIDE_RE:-'^(hikaricp_connections|grpc_server|http_server_requests_seconds|shadowfit_|jvm_threads_live_threads|jvm_memory_used_bytes|process_cpu_usage|system_cpu_usage|tomcat_threads)'}
SIDE_MYSQL_VARS=${SIDE_MYSQL_VARS:-"Threads_running Threads_connected Queries Com_insert Innodb_rows_inserted Innodb_data_fsyncs Innodb_os_log_fsyncs Innodb_row_lock_waits Innodb_row_lock_time Innodb_buffer_pool_wait_free"}

snap_side() {  # $1 = 태그, $2 = 국면(pre|mid|post)
  local tag=$1 phase=$2 now spring my vars
  now=$(date +%s)

  # ── Spring (actuator) ──
  spring=$($SSH "curl -sf --max-time 5 '$ACTUATOR'" 2>/dev/null | grep -Ev '^#' | grep -E "$SIDE_RE")
  if [ -n "$spring" ]; then
    printf '%s\n' "$spring" \
      | awk -v t="$tag" -v a="$CUR_ARM" -v n="$CUR_N" -v p="$phase" -v e="$now" \
          '{ v = $NF; name = $0; sub(/[ \t]+[^ \t]+$/, "", name);
             printf "%s\t%s\t%s\t%s\t%s\tspring\t%s\t%s\n", t, a, n, p, e, name, v }' >> "$SIDE_LOG"
  else
    # 🔴 «못 걷었다» 를 빈칸으로 남기지 않는다. 빈칸은 «0» 으로도, «안 걸었다» 로도 읽힌다.
    printf "%s\t%s\t%s\t%s\t%s\tspring\t_scrape\tFAIL\n" "$tag" "$CUR_ARM" "$CUR_N" "$phase" "$now" >> "$SIDE_LOG"
    echo "  ⚠️ actuator 스크레이프 실패($phase) — H3 판정 열이 이 판에서 빈다 (#254)" >&2
  fi

  # ── MySQL (SHOW GLOBAL STATUS) ──
  vars=$(printf "'%s'," $SIDE_MYSQL_VARS); vars=${vars%,}
  my=$($SSH "docker exec -i -e MYSQL_PWD=$MYSQL_PW $MYSQL_CONTAINER mysql -u$MYSQL_USER shadowfit -N \
        -e \"SHOW GLOBAL STATUS WHERE Variable_name IN ($vars);\"" 2>/dev/null | tr -d '\r')
  if [ -n "$my" ]; then
    printf '%s\n' "$my" \
      | awk -F'\t' -v t="$tag" -v a="$CUR_ARM" -v n="$CUR_N" -v p="$phase" -v e="$now" \
          'NF >= 2 { printf "%s\t%s\t%s\t%s\t%s\tmysql\t%s\t%s\n", t, a, n, p, e, $1, $2 }' >> "$SIDE_LOG"
  else
    printf "%s\t%s\t%s\t%s\t%s\tmysql\t_status\tFAIL\n" "$tag" "$CUR_ARM" "$CUR_N" "$phase" "$now" >> "$SIDE_LOG"
    echo "  ⚠️ MySQL 상태 조회 실패($phase) — «없다» 가 아니라 «못 물었다» 다 (#254)" >&2
  fi
}

# ── 한 판 ────────────────────────────────────────────────────────────────
run_one() {  # $1=팔 $2=라운드 $3=세션수
  # 🔴 `local a=$1 b=$2 t="${a}_${b}"` 로 한 줄에 쓰면 안 된다. bash 는 `local` 의 인자
  #    **단어를 전부 먼저 전개**한 뒤 대입하므로, `${round}` 는 아직 없는 상태에서 전개된다.
  #    `set -u` 아래에선 그게 «round: unbound variable» 로 즉사한다.
  #    (2026-08-16 EC2 첫 실행에서 판을 하나도 못 돌고 죽은 원인이 이것이다. 전역에 `arm` 이
  #     있어서 «arm» 이 아니라 «round» 만 이름에 찍혀 더 헷갈렸다.)
  local arm=$1 round=$2 n=$3
  local tag="${arm}_${round}_${n}"
  CUR_ARM=$arm; CUR_N=$n          # start_ghz·stop_ghz 가 읽는다
  echo; echo "──────── $tag ────────"
  start_stats "$tag"
  # #250 — 부하기 자신도 걷는다. 🔴 판정선은 «총 CPU 200%» 가 **아니다**: 2라운드 실측에서
  # 부하기 CPU 는 4 vCPU 중 0.68 만 쓰면서도 부하기를 키우자 결과가 +17.7% 움직였다.
  # CPU 는 «붙었나» 만 답하고 «부하기가 결과를 움직였나» 는 못 답한다(설계 §12-1).
  start_loader_stats "$tag"
  start_ghz "$tag"                # 팔 A 는 그냥 지나간다(정의상 유휴)
  snap_side "$tag" pre            # #254 — 카운터의 원점
  # 창 한가운데 한 번 더. 🔴 게이지(threads_running·hikari pending)는 **부하 중에만** 뜻이
  # 있다 — pre·post 두 점은 둘 다 유휴 순간이라 「포화 때 옆이 어땠나」가 안 남는다.
  ( sleep $(( DUR / 2 )); snap_side "$tag" mid ) &
  local side_pid=$!
  python "$HERE/load_ai.py" --base "$BASE" --ai "$AI" --token "$TOKEN" \
      --frames "$HERE/frames.json" --sessions "$n" --dur "$DUR" \
      --out "$OUT/req_$tag.tsv" --label "$tag"
  local rc=$?
  wait "$side_pid" 2>/dev/null    # mid 가 post 보다 먼저 쓰이게 — 같은 파일에 이어 쓴다
  snap_side "$tag" post
  stop_ghz "$tag"
  stop_loader_stats "$tag"
  stop_stats "$tag"
  if [ $rc -ne 0 ] || [ ! -f "$OUT/req_${tag}_summary.tsv" ]; then
    printf "%s\t%s\t%s\tFAIL\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\n" "$arm" "$round" "$n" >> "$LOG"
    return 1
  fi
  # $14·$15 = warm_lo_epoch·warm_hi_epoch (load_ai.py 가 **끝에** 붙인다 — 위치로 읽으므로)
  tail -1 "$OUT/req_${tag}_summary.tsv" \
    | awk -v a="$arm" -v r="$round" -v n="$n" -F'\t' \
      '{printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n", a,r,n,$5,$6,$7,$8,$9,$10,$11,$12,$13,$15,$16}' >> "$LOG"
}

# ── 실행 ─────────────────────────────────────────────────────────────────
[ -f "$HERE/frames.json" ] || { echo "🔴 frames.json 이 없다 — gen_frames.py 로 먼저 만든다"; exit 1; }
assert_arms_distinguishable || exit 1

# 從 부하를 쓰는 팔이 하나라도 있으면, 그 부하가 **실제로 걸릴 수 있는지**를 먼저 본다.
# 판이 다 돈 뒤에 «전부 FK 실패였다» 를 아는 것이 이 rig 에서 가장 비싼 실패다.
if echo " $ARMS " | grep -qE ' (B|C|D) '; then
  if ghz_configured; then
    assert_ghz_payload_seeded || exit 1
    note "從 부하: ${GHZ_RPS} req/s 고정 · c=$GHZ_CONC · $GHZ_TARGET · $(basename "$GHZ_DATA")"
  else
    echo "🔴 팔 B/C/D 가 있는데 從 부하 설정이 비었다 — 그 팔들은 «유휴 동거» 를 잰다." >&2
    echo "   GHZ_RPS·GHZ_DATA·GHZ_TOKEN·GHZ_BIN 을 채우거나, ARMS 에서 빼고 돌 것 (#223)" >&2
    exit 1
  fi
fi

# 🔴 **팔을 블록으로 돌지 않는다** (2026-08-16, 설계 §5 대조에서 잡혔다).
#    초판은 «A 13판 → B 13판 → C 13판» 이었다. 그러면 팔 C 는 **항상 라운드의 마지막**이라
#    «캡 효과» 와 «시간이 흐르며 생긴 것»(EBS 버스트 크레딧 소진·테이블 성장·열)이 같은 축에
#    겹쳐 원리적으로 안 갈린다. 4차 풀 사이징 라운드가 «N 클수록 +32%» 를 냈다가 순서를
#    뒤집으니 «작을수록 +36%» 로 부호가 바뀐 것이 정확히 이 오염이었다
#    ([[feedback_measure_design_needs_repeats]]).
#
#    설계 §5 가 요구하는 것은 «각 팔이 판 순서 1·2·3 에 한 번씩» 이다. 라운드마다 팔 순서를
#    한 칸씩 돌린다 — 팔이 3개·REPEATS 3이면 그것이 라틴 방격이다:
#        r1: A B C  /  r2: B C A  /  r3: C A B
#    대가는 팔 전환이 3회 → 9회로 느는 것(판당 ~40초, 총 +4분 안팎)이다. 싸다.
rotate_arms() {  # $1 = 왼쪽으로 돌릴 칸 수
  local -a a=($ARMS)
  local k=$1 n=${#a[@]} i out=""
  for ((i = 0; i < n; i++)); do out="$out ${a[$(((i + k) % n))]}"; done
  echo "${out# }"
}

# 레벨도 같은 방식으로 돌린다 (#252). $1 = 라운드 번호(1부터).
rotate_levels() {
  local -a a=($LEVELS)
  local n=${#a[@]} k i out=""
  k=$(( (($1 - 1) * LEVEL_SHIFT) % n ))
  for ((i = 0; i < n; i++)); do out="$out ${a[$(((i + k) % n))]}"; done
  echo "${out# }"
}

NARMS=$(echo "$ARMS" | wc -w)
if [ "$((REPEATS % NARMS))" -ne 0 ]; then
  note "⚠️ REPEATS($REPEATS) 가 팔 수($NARMS)의 배수가 아니다 — 위치가 정확히 균형 잡히지 않는다."
  note "   순서 효과가 남을 수 있으니 결과에 그대로 적을 것 (방격이 아니라 «부분 균형» 이다)"
fi

# 버림판은 **팔당 1회**다(설계 §5). 팔이 처음 나오는 라운드에서만 돈다.
DISCARDED=""

# ── 앵커 판 — #251·2라운드 이후 승격 (2026-08-17) ────────────────────────
#
# 🔴 **라운드 «간» 절대값이 안 재현된다.** 같은 코드·같은 격자·같은 인스턴스 타입에서
#    천장 환산이 89.2 → 105.0세션(+17.7%)으로 움직였다(2라운드 §5). 그리고 1라운드에서는
#    라운드 «안» 에서도 팔 A 가 2시간에 +16% 흘렀다.
#
# 앵커는 그 흐름을 **보정 대상이 아니라 측정 대상**으로 만든다 — 동일 조건(기본 팔 B·80세션)
# 한 판을 라운드마다 같은 자리에서 찍는다. 시각은 `warm_lo` 에 있으니 사후에 «시간 ↔ 처리량»
# 을 직접 그릴 수 있다.
#
# 🔴 **첫 앵커가 워밍업을 뒤집어쓰면 앵커 계열 자체가 오염된다.** 그래서 첫 앵커 앞에서
#    앵커 팔의 **버림판을 먼저 돌리고**, 그 팔은 `DISCARDED` 에 넣어 블록에서 다시 안 버린다.
# ⚠️ 앵커는 본 격자에 안 들어간다 — 라운드 이름표가 `anc<N>` 이라 분석에서 골라낼 수 있다.
#    대신 판 수가 REPEATS+1 만큼 는다(3판 라운드면 4판 ≈ 11분, 팔 전환 포함).
ANCHOR=${ANCHOR:-1}                  # 0 이면 안 돈다
ANCHOR_ARM=${ANCHOR_ARM:-B}          # 從 부하까지 걸린 «일하는» 조건이 기준선으로 낫다
ANCHOR_LEVEL=${ANCHOR_LEVEL:-80}     # 포화 **직전** — 천장에 붙은 레벨은 天井이 흔들리면 같이 흔들린다
ANCHOR_SEQ=0

run_anchor() {
  [ "$ANCHOR" = "1" ] || return 0
  case " $ARMS " in
    *" $ANCHOR_ARM "*) ;;
    *) note "⚠️ 앵커 팔($ANCHOR_ARM)이 ARMS 에 없다 — 앵커를 건너뛴다. 시간 추세는 이 라운드에서 안 걷힌다"
       ANCHOR=0; return 0 ;;
  esac
  ANCHOR_SEQ=$((ANCHOR_SEQ + 1))
  step "앵커 판 anc$ANCHOR_SEQ — 팔 $ANCHOR_ARM · ${ANCHOR_LEVEL}세션 (시간 추세용 기준점)"
  apply_arm "$ANCHOR_ARM" || { note "⚠️ 앵커 팔 구성 실패 — 이 앵커 점이 빈다. 시간 추세는 그만큼 성기어진다"; return 1; }
  sleep 20
  reset_sessions
  case " $DISCARDED " in
    *" $ANCHOR_ARM "*) ;;
    *)  note "팔 $ANCHOR_ARM 버림판 (앵커 앞에서 먼저 — 첫 앵커가 워밍업을 타면 계열이 오염된다)"
        run_one "$ANCHOR_ARM" "discard" "$ANCHOR_LEVEL" >/dev/null 2>&1
        DISCARDED="$DISCARDED $ANCHOR_ARM"
        reset_between ;;
  esac
  run_one "$ANCHOR_ARM" "anc$ANCHOR_SEQ" "$ANCHOR_LEVEL" || true
  reset_between
}

for r in $(seq 1 "$REPEATS"); do
  run_anchor                        # 라운드 시작마다 하나 — 마지막 하나는 루프 뒤에
  step "라운드 r$r — 팔 순서 $(rotate_arms "$((r - 1))") · 레벨 순서 $(rotate_levels "$r")"
  for arm in $(rotate_arms "$((r - 1))"); do
    apply_arm "$arm" || exit 1
    sleep 20
    # 팔을 바꾸면 컨테이너가 재기동되므로 앞 팔의 세션이 IN_PROGRESS 로 남는다. 먼저 푼다.
    reset_sessions
    case " $DISCARDED " in
      *" $arm "*) ;;
      *)  # 버림판 — 그 팔의 첫 판은 컨테이너 워밍업·JIT·버퍼풀을 가장 크게 탄다. **표에 안 넣는다.**
          #    🔴 레벨은 **그 블록이 실제로 처음 도는 레벨**로 맞춘다(#252 치환 이후).
          #       `$LEVELS` 첫 값으로 고정하면 내림/치환 블록에서 «버림판만 다른 레벨» 이 된다.
          note "팔 $arm 버림판 (표에 안 들어간다)"
          run_one "$arm" "discard" "$(rotate_levels "$r" | awk '{print $1}')" >/dev/null 2>&1
          DISCARDED="$DISCARDED $arm"
          reset_between ;;
    esac
    # 🔴 레벨 순서는 라운드마다 치환된다 (#252). 오름차순 고정이면 블록 안 시간 추세가
    #    상위 레벨에 그대로 얹힌다 — 그리고 그 자리가 plateau 를 정의한다.
    for n in $(rotate_levels "$r"); do
      run_one "$arm" "r$r" "$n" || true
      reset_between
    done
  done
done

run_anchor                          # 끝 — 이것이 있어야 라운드 «전 구간» 을 감싼다

echo; echo "════ 요약 ════"; cat "$LOG"
echo
echo "🔴 판정은 사람이 한다. 특히 볼 것:"
echo "   · nolease > 0  → 풀 자리 없음(용량). detect_pct 하락과 **다른 축**이다"
echo "   · nopose  > 0  → 검출이 깨짐(품질). #164 가 고친 지표가 다시 무너진 것일 수 있다"
echo "   · setup_fail>0 → 그 판의 «동시 세션 수» 는 목표값이 아니다. 값을 그대로 쓰지 말 것"
echo "   · ghz.tsv 의 p50/p95/p99 → **H3 의 판정 열**(#254). 팔 B↔C 를 여기서 대조한다 —"
echo "     캡을 걸었더니 옆(Spring 적재)의 지연이 «안 나빠졌다» 면 캡이 옆을 지킨 것이다"
echo "   · side.tsv 의 phase=mid → 포화 순간의 옆 상태(Threads_running·Hikari pending)."
echo "     pre/post 는 카운터의 양 끝이다 — 게이지를 거기서 읽으면 «유휴» 를 읽게 된다"
echo "   · round=anc1..N → **앵커 판**. 본 격자가 아니라 «시간 축» 이다 — warm_lo 로 정렬해"
echo "     처리량이 라운드 내내 평평한지 본다. 흐르면 팔 간 대조에 그 추세가 섞여 있는 것이다"
echo "   · 레벨 순서는 라운드마다 치환된다(#252) — 같은 레벨이 블록 안 다른 자리에서 돈다"
