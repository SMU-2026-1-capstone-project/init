"""부하 프레임 생성 + **rep 이 생기는가**를 먼저 확정한다 (동거 용량 rig, P6).

## 이 스크립트가 있는 이유

동거 실험은 «AI 만 바쁜» 판이면 성립하지 않는다. rep 이 완성돼야 `SavePoseDataBatch` 가
나가고 그래야 Spring·MySQL 이 실제로 일한다(설계 §8 체크리스트). 그런데 **합성 인체로
그린 스쿼트가 rep 판정을 통과하는지는 아무도 확인한 적이 없다.**

- 기존 rig 이 단언한 것은 «검출되는가» 뿐이다 (`assert_detectable`)
- rep 은 그보다 세다 — 무릎 각도가 **155° 이상(서있음)과 95° 이하(앉음)를 둘 다** 지나야
  상태기계가 한 바퀴 돈다 (`squat_analyzer._phase_from_angles`)

**검출은 되는데 각도가 안 벌어지면** 부하는 도는데 rep 이 0 이고, 표에는 «동거를 쟀다» 로
남는다. #196 이 정확히 그 형태였다(HTTP 200 인데 리포트 전부 0). 그래서 프레임을 만들기
전에 **각도부터 재고, 못 지나면 생성 자체를 거부한다.**

## 쓰는 법

    # ai-server 이미지 안에서 (의존성을 운영과 같게)
    docker cp gen_frames.py     shadowfit-ai:/tmp/
    docker cp synthetic_body.py shadowfit-ai:/tmp/
    docker exec shadowfit-ai python /tmp/gen_frames.py --out /tmp/frames.json

    # 로컬 검증만 (프레임 안 씀)
    python gen_frames.py --check-only

산출물 `frames.json` 은 **base64 JPEG 배열 한 벌**이다. 부하기는 이걸 순환시켜 쏘므로
부하 중에 인코딩 비용을 안 문다 — 부하기가 CPU 를 쓰면 그게 또 경합 변수가 된다.
"""

import argparse
import base64
import json
import sys

sys.path.insert(0, "/app")          # ai-server 이미지 안
sys.path.insert(0, ".")             # 사본과 같은 디렉터리
sys.path.insert(0, "/tmp")          # docker cp 로 올린 경우

import cv2                                                          # noqa: E402
from synthetic_body import squat_cycle                              # noqa: E402

# rep 상태기계의 문턱. 🔴 **여기에 임의로 정한 숫자는 없다** — 코드가 쓰는 값을 그대로 쓴다.
#
# ⚠️ **`squat_analyzer` 안에 문턱이 두 벌이다** (2026-08-15 확인):
#   · `analyze_squat_frames(bottom_threshold=100.0, standing_threshold=150.0)` — **rep 을 세는 것은 이쪽**
#   · `_phase_from_angles()` 의 95 / 155 — 국면 «이름표» 를 붙이는 쪽
# 초판이 뒤엣것을 골랐다. rep 이 생기는지를 보는 게이트이므로 **앞엣것이 맞다.**
# (판정은 안 바뀐다 — 최대 124.30° 라 150 에도 못 닿는다. 그래도 잰 자를 틀리게 적으면 안 된다.)
STAND_DEG = 150.0     # 이상이면 «서있음» (standing_threshold)
BOTTOM_DEG = 100.0    # 이하면 «앉음»   (bottom_threshold)


def knee_angles(frames):
    """각 프레임의 무릎 각도. → [(각도|None, 사유)]

    🔴 **«검출 실패» 와 «각도 계산 실패» 를 절대 같은 칸에 넣지 않는다.** 초판이 둘을 모두
       None 으로 뭉갰다가 «합성 인체는 검출이 아예 안 된다» 는 오진을 만들었다 — 실제로는
       검출이 되고 있었고 각도 함수의 시그니처를 내가 틀리게 알고 있었다. 이 rig 이 막으려는
       것이 정확히 그런 종류라, rig 자신이 그러면 안 된다.

    각도는 **`squat_analyzer` 가 실제로 쓰는 경로**(`_extract_raw_metrics`)를 그대로 부른다.
    별도로 계산하면 rep 판정과 다른 값을 보게 된다.
    """
    from app.core.mediapipe_detector import PoseDetector
    from app.core.squat_analyzer import _extract_raw_metrics

    det = PoseDetector()
    out = []
    for f in frames:
        lm = det.detect(f)
        if not lm:
            out.append((None, "검출실패"))
            continue
        try:
            out.append((_extract_raw_metrics(lm).knee_angle, ""))
        except Exception as e:  # noqa: BLE001 — 사유를 그대로 보여준다
            out.append((None, f"각도계산실패: {type(e).__name__} {e}"))
    return out


def gate(rows):
    """rep 이 생길 수 있는가. (통과여부, 사유) — 판정 근거를 문장으로 남긴다."""
    angles = [a for a, _ in rows]
    got = [a for a in angles if a is not None]
    if not got:
        return False, "검출이 한 프레임도 안 됐다 — 재는 것이 «추론» 이 아니라 «탐지 실패» 가 된다"
    miss = len(angles) - len(got)
    lo, hi = min(got), max(got)
    reasons = []
    if hi < STAND_DEG:
        reasons.append(f"«서있음»(≥{STAND_DEG}°)에 못 닿는다 — 최대 {hi:.1f}°")
    if lo > BOTTOM_DEG:
        reasons.append(f"«앉음»(≤{BOTTOM_DEG}°)에 못 닿는다 — 최소 {lo:.1f}°")
    if reasons:
        return False, " / ".join(reasons) + f"  (검출 {len(got)}/{len(angles)})"
    return True, f"각도 {lo:.1f}° ~ {hi:.1f}° 로 두 문턱을 모두 지난다 (검출 {len(got)}/{len(angles)}, 실패 {miss})"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=30, help="스쿼트 1주기 프레임 수")
    ap.add_argument("--quality", type=int, default=80, help="JPEG 품질")
    ap.add_argument("--out", default="frames.json")
    ap.add_argument("--check-only", action="store_true", help="각도 게이트만 보고 파일은 안 쓴다")
    ap.add_argument("--require-reps", action="store_true",
                    help="rep 문턱을 못 넘으면 실패로 끝낸다. 기본은 «사실만 적고 계속» 이다 — "
                         "이 rig 의 부하 구성은 rep 에 의존하지 않는다(README §2)")
    a = ap.parse_args()

    frames = squat_cycle(n=a.n)
    print(f"합성 스쿼트 1주기 {len(frames)}프레임 — 무릎 각도를 잰다")

    rows = knee_angles(frames)
    angles = [a for a, _ in rows]
    for i, (ang, why_i) in enumerate(rows):
        bar = "?" if ang is None else ("서" if ang >= STAND_DEG else ("앉" if ang <= BOTTOM_DEG else "·"))
        print(f"  [{i:02d}] {(why_i if ang is None else f'{ang:7.2f}°')}  {bar}")

    ok, why = gate(rows)
    print()
    if ok:
        print(f"✅ rep 이 생길 수 있다 — {why}")
    else:
        print(f"🔴 **이 프레임으로는 rep 이 안 생긴다** — {why}")
        print("   → 그래서 이 rig 은 Spring·MySQL 부하를 **rep 에 기대지 않고 따로** 건다")
        print("     (README §2 · 설계 §8). AI 부하 자체는 유효하다 — 검출은 되고 있다.")
        if a.require_reps:
            sys.exit(1)

    # 🔴 검출은 별개다. 검출이 안 되면 재는 것이 «추론» 이 아니라 «탐지 실패» 라 부하가 무효다.
    det_ok = sum(1 for x in angles if x is not None)
    if det_ok < len(angles):
        print(f"🔴 검출 {det_ok}/{len(angles)} — 전 프레임 검출이 아니면 부하가 «탐지 실패» 를 잰다")
        sys.exit(1)

    if a.check_only:
        return
    b64 = []
    for f in frames:
        bgr = cv2.cvtColor(f, cv2.COLOR_RGB2BGR)
        okj, buf = cv2.imencode(".jpg", bgr, [cv2.IMWRITE_JPEG_QUALITY, a.quality])
        if not okj:
            print("🔴 JPEG 인코딩 실패"); sys.exit(1)
        b64.append(base64.b64encode(buf.tobytes()).decode())
    meta = {
        "n": len(b64),
        "knee_deg_min": round(min(x for x in angles if x is not None), 2),
        "knee_deg_max": round(max(x for x in angles if x is not None), 2),
        "detect_ok": sum(1 for x in angles if x is not None),
        "jpeg_quality": a.quality,
        "note": "합성 스쿼트 1주기. 실사진 아님 — 절대 성능값을 실사용으로 인용하지 말 것",
    }
    with open(a.out, "w") as fp:
        json.dump({"meta": meta, "frames": b64}, fp)
    kb = sum(len(x) for x in b64) / 1024
    print(f"   → {a.out}  ({len(b64)}프레임, base64 합계 {kb:.0f}KB, 프레임당 {kb/len(b64):.1f}KB)")


if __name__ == "__main__":
    main()
