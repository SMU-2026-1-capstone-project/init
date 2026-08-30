"""frames.json(리사이즈+저품질 재인코딩본)이 여전히 production 스트리밍 경로에서 rep 을
완성하는지 확인 — measure_r.py 와 같은 재현이지만 입력이 영상이 아니라 이미 만든 JSON."""
import base64
import json
import sys
from pathlib import Path

import cv2
import numpy as np

AI_SERVER = Path(__file__).resolve().parent.parent.parent.parent / "ai-server"
if str(AI_SERVER) not in sys.path:
    sys.path.insert(0, str(AI_SERVER))

from app.core.mediapipe_detector import get_detector
from app.core.squat_analyzer import StreamingSquatAnalyzer, _frame_visibility_score
from app.grpc.session_state import PerRepFrame, SessionState

with open(sys.argv[1]) as fp:
    blob = json.load(fp)
frames_b64, meta = blob["frames"], blob.get("meta", {})
print(f"프레임 {len(frames_b64)}장, meta={meta}")

analyzer = StreamingSquatAnalyzer("squat")
state = SessionState(session_id=999_999, exercise_id=1, exercise_type="squat")
detector = get_detector()

detected = 0
rep_sizes = []
for i, b64 in enumerate(frames_b64):
    buf = np.frombuffer(base64.b64decode(b64), dtype=np.uint8)
    bgr = cv2.imdecode(buf, cv2.IMREAD_COLOR)
    rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
    landmarks = detector.detect(rgb)
    if not landmarks:
        continue
    detected += 1
    angles, smoothed_knee_angle, rep_event = analyzer.process_frame(state, landmarks)
    if angles is not None:
        state.current_rep_frames.append(
            PerRepFrame(timestamp_sec=i / 10.0, joint_coordinates="",
                        angles=angles, smoothed_knee_angle=smoothed_knee_angle)
        )
        if rep_event is not None:
            rep_sizes.append(len(state.current_rep_frames))
            state.current_rep_frames.clear()

print(f"검출 {detected}/{len(frames_b64)}, 완성된 rep {len(rep_sizes)}, R 분포 {rep_sizes}")
