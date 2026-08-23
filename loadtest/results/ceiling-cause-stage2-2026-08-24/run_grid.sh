#!/usr/bin/env bash
# §8 격자 — 팔 F/Z/C 13판. 설계: docs/decisions/per-process-ceiling-cause.md §8
#
# 🔴 팔은 «실행 단위» 로 걸린다(판마다가 아니다) — run_arms.py 의 --null-handler /
#    --gil-probe 는 전역이다. 그래서 라틴 방격을 이 셸이 돌린다(§12 판과 같은 구조).
set -euo pipefail

ROOT=${ROOT:-/root/init}
OUT=${OUT:-/root/cc2-out}
SESS=${SESS:-160}; POOL=${POOL:-201}; DUR=${DUR:-90}; FPS=${FPS:-3.0}
FLOOR=${FLOOR:-5}          # 🔴 부하 «전» 바닥 (축 5 계약 1)
PROBE=${PROBE:-0.001}      # GIL 프로브 주기

# 버림 1 + F Z C C Z F F Z C C Z F — 위치 합 셋 다 26
PLAN=(F F Z C C Z F F Z C C Z F)

mkdir -p "$OUT"
cd "$ROOT"

# 🔴 박스 보정을 먼저 남긴다 (#498 축 0 — 🔴 #255 아니다, 그건 닫힌 부하기 결함). 판정에 안 써도 무조건 기록한다 —
#    이 값이 없어서 예전 라운드의 비재현을 사후에 못 갈랐다.
python3 loadtest/calibrate_box.py --tsv "$OUT/calib.tsv" || echo "🔴 보정 실패(계속한다)"

for i in "${!PLAN[@]}"; do
  ARM=${PLAN[$i]}
  N=$i                                   # 0 = 버림
  TAG="t${N}_${ARM}"
  EXTRA=()
  case "$ARM" in
    F) EXTRA=(--gil-probe "$PROBE" --floor-sec "$FLOOR") ;;
    Z) EXTRA=(--null-handler --gil-probe "$PROBE" --floor-sec "$FLOOR") ;;
    C) EXTRA=() ;;                       # 프로브 끔 — F 와 «프로브만» 다르다
    *) echo "🔴 모르는 팔: $ARM"; exit 1 ;;
  esac
  echo "──── [$((i+1))/${#PLAN[@]}] 팔 $ARM  tag=$TAG $([ "$N" = 0 ] && echo '(버림)') ────"
  python3 loadtest/results/frame-path-overhead-2026-08-23/run_arms.py \
    --sessions "$SESS" --pool "$POOL" --dur "$DUR" --fps "$FPS" \
    --plan B --discard 0 --http-port 8100 --grpc-port 8685 \
    --tag "$TAG" --out "$OUT" "${EXTRA[@]}" 2>&1 | tee "$OUT/log_${TAG}.txt"
done

echo "──── 게이트 ────"
python3 - "$OUT" <<'PY'
import glob, json, sys, os
d = sys.argv[1]; bad = []
for f in sorted(glob.glob(d + "/arms_t*.json")):
    j = json.load(open(f)); tag = os.path.basename(f)
    for r in j["results"]:
        if r.get("error"): bad.append(f"{tag}: error={r['error']}")
        if (r.get("outcomes") or {}).get("nolease"): bad.append(f"{tag}: nolease={r['outcomes']['nolease']}")
        fp = r.get("frame_path") or {}
        if j.get("gil_probe") and not (fp.get("gil_lag") or {}).get("n"):
            bad.append(f"{tag}: 🔴 프로브 켰는데 gil_lag 표본 0")
        fl = r.get("gil_floor")
        if j.get("floor_sec") and fl and fl.get("requests"):
            bad.append(f"{tag}: 🔴 바닥 구간에 요청 {fl['requests']} — 무부하가 아니었다")
print("🔴 " + "\n🔴 ".join(bad) if bad else "🟢 게이트 통과 — 에러 0 · nolease 0 · 프로브·바닥 정상")
PY
