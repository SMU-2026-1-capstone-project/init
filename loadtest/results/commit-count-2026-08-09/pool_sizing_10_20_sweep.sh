#!/bin/bash
# 커넥션 풀 사이징 재실험 — 10~20 사이 좁히기
#
# 설계: docs/decisions/pool-sizing-10-20-experiment-design.md
# 선행: pool_durability_sweep.sh(같은 rig), repeat_sweep.sh(라틴 방격 패턴 원본)
#
# 기존 4점(2026-08-09, 다세션·기본 내구성·c=100)은 이미 안다:
#   pool  2 →  69%   pool  5 →  69%   pool 10 → 100%   pool 20 → 100%(plateau)
# 10과 20이 둘 다 100%인데 그 사이는 안 재봤다. 이 스크립트가 그 사이 3점(12·15·17)을
# 채우고, 10·20은 재현판으로 다시 찍는다(재현 안 되는 경우가 실제로 있었다 — §0-1 버퍼풀 가설).
#
# 통제: 다세션 페이로드(락 경합 없음) · 기본 내구성(완화 안 함) · c=100 — 기존 4점과
#       같은 조건이라야 같은 곡선에 얹을 수 있다.
#
# 설계(§3 라틴 방격 + 버림판):
#   버림판 1개(discard, 결과에 안 넣음) → 3라운드 × 5수준, 라운드마다 순서 회전
#   R1  10 12 15 17 20
#   R2  15 17 20 10 12
#   R3  20 10 12 15 17
#   각 수준이 판 위치 1~5 에 정확히 한 번씩 온다.
#
# 판정: 평균 비교가 아니라 반복 분포가 겹치는지로 본다(§5, slo-baseline.md §5-1 규칙) —
#       이 스크립트는 원자료만 낸다. 판정은 별도 분석 단계.
#
# 가용성 게이트: hikaricp_connections_timeout_total > 0 이 한 번이라도 나오면 그 수준 즉시 탈락
#       (run_ghz 자체는 이 지표를 안 걷는다 — 각 판 실행 중 별도로 curl actuator 확인 필요.
#        아래 collect_hikari 가 그 역할)
#
# 사용: commit_sweep.sh 와 같은 환경변수 (PEM · DB_PUB · APP_PUB · LOADER_PUB · OBS_PUB ·
#       DB_PRIV · APP_PRIV · OUT)

set -uo pipefail
cd "$(dirname "$0")"

OUT="${OUT:?OUT 미설정}"
LOG="$OUT/pool_sizing.tsv"
SESS_LO=901; SESS_HI=1000
C=100
DATA=/tmp/batch_n1.json     # N=1 — 기존 4점과 같은 조건(요청당 1 rep)
ROWS_PER_REQ=5
N_REQ=15000

source ./_rig.sh

learn_all_hosts
init_log

# 이 스윕은 durability를 건드리지 않는다 — 시작 전 기본값임을 명시적으로 확인해둔다.
echo "=== 사전 확인 — 내구성 기본값(flush=1, sync_binlog=1) ==="
set_durability 1 1

# 각 판 직후 HikariCP timeout 지표를 걷어 pool_sizing.tsv 와 별도 파일에 남긴다.
# 판정선(slo-baseline.md §4-4): timeout_total > 0 이면 그 수준은 이유 불문 제외.
collect_hikari() {  # $1 = 태그
  local tag=$1 metrics
  metrics=$(ssh "${SSH_OPTS[@]}" "root@$APP_PUB" \
    "curl -fsS http://localhost:8080/actuator/prometheus 2>/dev/null | grep '^hikaricp_connections_timeout_total'" \
    2>/dev/null || echo "MISSING")
  echo -e "$tag\t$metrics" >> "$OUT/pool_sizing_hikari.tsv"
}

PLANS=()

# ── 버림판 — 결과에 안 넣는다 ──────────────────────────────────────────────
echo "--- 버림판 (워밍업, pool=15로 기동, 표에 안 들어감) ---"
if restart_backend 15; then
  _real_log="$LOG"; LOG=/dev/null
  run_ghz "discard" "$DATA" "$C" "$N_REQ" || echo "  (버림판 실패 — 무시하고 진행)"
  LOG="$_real_log"
  rm -f "$OUT/discard.json"
else
  echo "🔴 버림판 기동 실패 — 이후 판의 워밍업 없이 진행됨. 결과 해석 시 1라운드를 의심할 것."
fi

# ── 라틴 방격 3라운드 × 5수준 ────────────────────────────────────────────
run_round() {  # $1=라운드번호, $2.. = pool 순서
  local r=$1; shift
  for pool in "$@"; do
    local tag="r$r-p$pool"
    echo "--- $tag ---"
    PLANS+=("$tag")
    if restart_backend "$pool"; then
      run_ghz "$tag" "$DATA" "$C" "$N_REQ" || true
      collect_hikari "$tag"
    else
      fail_row "$tag"; FAILED+=("$tag:기동")
      echo -e "$tag\tSKIP(기동실패)" >> "$OUT/pool_sizing_hikari.tsv"
    fi
  done
}
run_round 1 10 12 15 17 20
run_round 2 15 17 20 10 12
run_round 3 20 10 12 15 17

finish ${#PLANS[@]}

echo
echo "=== 수준별 요약 (라운드 평균, timeout 게이트 포함) ==="
python - "$LOG" "$OUT/pool_sizing_hikari.tsv" <<'PY'
import sys, collections
rows = collections.defaultdict(list)
with open(sys.argv[1], encoding='utf-8') as f:
    next(f)
    for line in f:
        p = line.rstrip('\n').split('\t')
        if len(p) < 12 or p[1] == 'FAIL':
            continue
        pool = p[0].split('-p', 1)[1]
        rows[pool].append((float(p[2]), int(p[5])))  # rps, p99 — _rig.sh 의 컬럼 순서에 맞춰 확인 필요

timeouts = collections.defaultdict(list)
try:
    with open(sys.argv[2], encoding='utf-8') as f:
        for line in f:
            tag, val = line.rstrip('\n').split('\t', 1)
            pool = tag.split('-p', 1)[1] if '-p' in tag else '?'
            timeouts[pool].append(val)
except FileNotFoundError:
    pass

print(f"{'pool':>4}  {'판수':>3}  {'rps 평균':>9}  {'최소~최대':>15}  {'p99 평균':>9}  {'timeout>0 발견':>14}")
for pool in ('10', '12', '15', '17', '20'):
    v = rows.get(pool) or []
    if not v:
        print(f"{pool:>4}  측정 없음"); continue
    rs = [x[0] for x in v]; p99 = [x[1] for x in v]
    gate = any('timeout_total 0.0' not in t and t != 'MISSING' and t != 'SKIP(기동실패)' for t in timeouts.get(pool, []))
    print(f"{pool:>4}  {len(v):>3}  {sum(rs)/len(rs):>9.0f}  "
          f"{min(rs):>6.0f}~{max(rs):<8.0f}  {sum(p99)/len(p99):>9.0f}  {'🔴 확인필요' if gate else 'OK(추정)':>14}")
print("\n⚠️ timeout 게이트는 문자열 매칭 추정치다 — 실제 판정 전 pool_sizing_hikari.tsv 원본을 직접 확인할 것.")
print("⚠️ 판 사이 변동이 수준 사이 차이보다 크면(최소~최대 폭 확인) 「차이 있다」를 말할 수 없다 — §5 판정 규칙.")
PY
