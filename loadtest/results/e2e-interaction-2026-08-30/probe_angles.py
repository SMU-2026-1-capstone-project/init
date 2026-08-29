"""빠른 반복용 — squat_cycle 의 각 프레임 무릎각을 찍는다. gen_frames.py:knee_angles() 사본."""
import sys

sys.path.insert(0, "/app")
sys.path.insert(0, "/tmp")

from synthetic_body_e2e import squat_cycle
from app.core.mediapipe_detector import PoseDetector
from app.core.squat_analyzer import _extract_raw_metrics

limb_scale = float(sys.argv[1]) if len(sys.argv) > 1 else 1.0
n = int(sys.argv[2]) if len(sys.argv) > 2 else 30
joint_markers = (sys.argv[3] == "1") if len(sys.argv) > 3 else False

frames = squat_cycle(n=n, limb_scale=limb_scale, joint_markers=joint_markers)
det = PoseDetector()
angles = []
for i, f in enumerate(frames):
    lm = det.detect(f)
    if not lm:
        print(f"[{i:02d}] 검출실패")
        continue
    try:
        a = _extract_raw_metrics(lm).knee_angle
        angles.append(a)
        print(f"[{i:02d}] {a:7.2f}")
    except Exception as e:
        print(f"[{i:02d}] 각도계산실패 {type(e).__name__} {e}")

if angles:
    print(f"limb_scale={limb_scale} n={n} -> min={min(angles):.1f} max={max(angles):.1f} (검출 {len(angles)}/{len(frames)})")
else:
    print("검출 0")
