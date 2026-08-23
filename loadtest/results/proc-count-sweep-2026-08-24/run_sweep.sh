#!/usr/bin/env bash
# N 프로세스 스윕 — 「그래서 몇 개로 쪼개나」.
#
# 🔑 원인은 닫혔다(GIL, `ceiling-cause-stage2-2026-08-24`). 남은 것은 **처방의 크기**다.
#    지금 근거는 「2개면 1.30배」 하나뿐이고, 그 판이 **14.5 vCPU 에 두 번째 천장**을
#    보고 정체를 안 밝혔다(`frame-path-r10a-2026-08-23/README.md` §5).
#
# 🔴 **N 프로세스는 rig 도 N 개다.** 각 rig 이 자기 서버 하나를 몰고, 세션·풀을 N 등분한다.
#    합계 세션(160)과 합계 풀(~201)은 **N 과 무관하게 고정**이다 — 안 그러면 N 을 늘린 것이
#    아니라 부하를 늘린 것이 된다.
set -euo pipefail

ROOT=${ROOT:-/root/init}
OUT=${OUT:-/root/pcs-out}
SESS_TOTAL=${SESS_TOTAL:-160}; POOL_TOTAL=${POOL_TOTAL:-201}
DUR=${DUR:-90}; FPS=${FPS:-3.0}; PROBE=${PROBE:-0.001}; FLOOR=${FLOOR:-5}

# 팔 A=1 · B=2 · C=3 · D=4 프로세스. 버림 1 + 16판, 위치 합 넷 다 34(확인함).
declare -A NPROC=( [A]=1 [B]=2 [C]=3 [D]=4 )
PLAN=(A A B C D D C B A B A D C C D A B)

mkdir -p "$OUT"; cd "$ROOT"
python3 loadtest/calibrate_box.py --tsv "$OUT/calib.tsv" 2>/dev/null || true
ai-server/.venv/bin/python loadtest/calibrate_box.py --tsv "$OUT/calib.tsv" || echo "🔴 보정 실패(계속)"

for i in "${!PLAN[@]}"; do
  ARM=${PLAN[$i]}; N=${NPROC[$ARM]}; R=$i
  S=$(( SESS_TOTAL / N )); P=$(( POOL_TOTAL / N + 1 ))
  TAG="t${R}_${ARM}"
  echo "──── [$((i+1))/${#PLAN[@]}] 팔 $ARM = ${N}프로세스 × ${S}세션 × 풀${P}  tag=$TAG $([ "$R" = 0 ] && echo '(버림)') ────"
  PIDS=()
  for ((k=0; k<N; k++)); do
    python3 loadtest/results/frame-path-overhead-2026-08-23/run_arms.py \
      --sessions "$S" --pool "$P" --dur "$DUR" --fps "$FPS" --plan B --discard 0 \
      --http-port $((8100 + k)) --grpc-port $((8685 + k)) \
      --gil-probe "$PROBE" --floor-sec "$FLOOR" \
      --tag "${TAG}p${k}" --out "$OUT" > "$OUT/log_${TAG}p${k}.txt" 2>&1 &
    PIDS+=($!)
  done
  # 🔴 **전부 기다린다.** 하나라도 먼저 끝나면 나머지는 경합 없는 구간을 재게 된다.
  FAIL=0
  for pid in "${PIDS[@]}"; do wait "$pid" || FAIL=1; done
  [ "$FAIL" = 1 ] && echo "  🔴 이 판에 실패한 rig 이 있다 — 게이트가 잡는다"
done

echo "──── 게이트 ────"
python3 - "$OUT" <<'PY'
import glob, json, os, re, sys
from collections import defaultdict
d = sys.argv[1]; bad = []; rounds = defaultdict(list)
for f in sorted(glob.glob(d + "/arms_t*.json")):
    m = re.match(r"arms_t(\d+)_([A-D])p(\d+)\.json", os.path.basename(f))
    if not m: bad.append(f"이름 모양 이상: {f}"); continue
    rnd, arm, k = int(m[1]), m[2], int(m[3])
    j = json.load(open(f))
    for r in j["results"]:
        if r.get("error"): bad.append(f"t{rnd}_{arm}p{k}: error={r['error']}")
        if (r.get("outcomes") or {}).get("nolease"): bad.append(f"t{rnd}_{arm}p{k}: nolease")
        fl = r.get("gil_floor") or {}
        if fl.get("requests"): bad.append(f"t{rnd}_{arm}p{k}: 바닥에 요청 {fl['requests']}")
        if not ((r.get("frame_path") or {}).get("gil_lag") or {}).get("n"):
            bad.append(f"t{rnd}_{arm}p{k}: 🔴 프로브 표본 0")
        rounds[(rnd, arm)].append(r)
# 🔴 각 판의 rig 수가 팔이 요구한 N 과 같은가 — 하나 죽으면 「N 을 줄인 판」이 된다
want = {"A":1,"B":2,"C":3,"D":4}
for (rnd, arm), rs in sorted(rounds.items()):
    if len(rs) != want[arm]:
        bad.append(f"t{rnd}_{arm}: rig {len(rs)}개 — {want[arm]}개여야 한다")
print("🔴 " + "\n🔴 ".join(bad) if bad else
      f"🟢 게이트 통과 — {len(rounds)}판 · 에러 0 · nolease 0 · 프로브·바닥·rig 수 정상")
PY
