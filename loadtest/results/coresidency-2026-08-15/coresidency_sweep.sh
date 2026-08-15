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
LEVELS=${LEVELS:-"20 40 80 160"}    # 동시 세션 ramp
DUR=${DUR:-90}                      # 판당 측정 구간(초). 앞뒤 5초는 부하기가 버린다
REPEATS=${REPEATS:-3}               # 본판. 버림판 1은 팔마다 별도로 돈다
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

LOG="$OUT/coresidency.tsv"
[ -f "$LOG" ] || printf "arm\tround\tsessions\treq\trps\tdetect_pct\tp50\tp95\tp99\tnolease\tnopose\tsetup_fail\n" > "$LOG"

# 🔴 ghz 결과를 본 표에 섞지 않는다. 「AI 가 몇 세션을 먹었나」와 「옆에 얼마가 걸렸나」는
#    다른 축이고, 뭉치면 둘 다 나빠진다(README 「세 결과를 뭉치지 않는다」와 같은 규약).
GHZ_LOG="$OUT/ghz.tsv"
[ -f "$GHZ_LOG" ] || printf "tag\tarm\tsessions\ttarget_rps\tachieved_rps\tcount\tok\tfail\n" > "$GHZ_LOG"

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
  got=$($SSH "docker exec -i $MYSQL_CONTAINER mysql -ushadowfit -pshadowfit shadowfit -N \
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
  $SSH "docker exec -i $MYSQL_CONTAINER mysql -ushadowfit -pshadowfit shadowfit \
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
  note "從 부하 시작 — ${GHZ_RPS} req/s 고정 · c=$GHZ_CONC · ${span}s (AI 창 ${DUR}s 를 앞뒤 ${GHZ_PAD}s 로 감싼다)"
  sleep "$GHZ_PAD"
}

stop_ghz() {  # $1 = 태그
  if ! arm_uses_ghz "$CUR_ARM"; then
    printf "%s\t%s\t%s\t-\t-\t-\t-\t-\n" "$1" "$CUR_ARM" "$CUR_N" >> "$GHZ_LOG"
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
    open(log, "a", encoding='utf-8').write(f"{tag}\t{arm}\t{n}\t{target}\tFAIL\t-\t-\t-\n")
    raise SystemExit(0)
sc = j.get("statusCodeDistribution") or {}
count = j.get("count", 0); ok = sc.get("OK", 0); fail = count - ok
rps = round(j.get("rps", 0), 1)
open(log, "a", encoding='utf-8').write(f"{tag}\t{arm}\t{n}\t{target}\t{rps}\t{count}\t{ok}\t{fail}\n")
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
reset_between() {
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

# ── 한 판 ────────────────────────────────────────────────────────────────
run_one() {  # $1=팔 $2=라운드 $3=세션수
  local arm=$1 round=$2 n=$3 tag="${arm}_${round}_${n}"
  CUR_ARM=$arm; CUR_N=$n          # start_ghz·stop_ghz 가 읽는다
  echo; echo "──────── $tag ────────"
  start_stats "$tag"
  start_ghz "$tag"                # 팔 A 는 그냥 지나간다(정의상 유휴)
  python "$HERE/load_ai.py" --base "$BASE" --ai "$AI" --token "$TOKEN" \
      --frames "$HERE/frames.json" --sessions "$n" --dur "$DUR" \
      --out "$OUT/req_$tag.tsv" --label "$tag"
  local rc=$?
  stop_ghz "$tag"
  stop_stats "$tag"
  if [ $rc -ne 0 ] || [ ! -f "$OUT/req_${tag}_summary.tsv" ]; then
    printf "%s\t%s\t%s\tFAIL\t-\t-\t-\t-\t-\t-\t-\t-\n" "$arm" "$round" "$n" >> "$LOG"
    return 1
  fi
  tail -1 "$OUT/req_${tag}_summary.tsv" \
    | awk -v a="$arm" -v r="$round" -v n="$n" -F'\t' \
      '{printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n", a,r,n,$5,$6,$7,$8,$9,$10,$11,$12,$13}' >> "$LOG"
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

for arm in $ARMS; do
  step "팔 $arm"
  apply_arm "$arm" || exit 1
  sleep 20
  # 버림판 — 첫 판은 컨테이너 워밍업·JIT·버퍼풀을 가장 크게 탄다. **표에 안 넣는다.**
  note "버림판 (표에 안 들어간다)"
  run_one "$arm" "discard" "$(echo "$LEVELS" | awk '{print $1}')" >/dev/null 2>&1
  reset_between
  for r in $(seq 1 "$REPEATS"); do
    for n in $LEVELS; do
      run_one "$arm" "r$r" "$n" || true
      reset_between
    done
  done
done

echo; echo "════ 요약 ════"; cat "$LOG"
echo
echo "🔴 판정은 사람이 한다. 특히 볼 것:"
echo "   · nolease > 0  → 풀 자리 없음(용량). detect_pct 하락과 **다른 축**이다"
echo "   · nopose  > 0  → 검출이 깨짐(품질). #164 가 고친 지표가 다시 무너진 것일 수 있다"
echo "   · setup_fail>0 → 그 판의 «동시 세션 수» 는 목표값이 아니다. 값을 그대로 쓰지 말 것"
