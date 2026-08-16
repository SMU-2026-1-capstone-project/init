"""AI 프레임 경로 단계별 프로파일 (#P6 후속 — 서비스 경로 12.6ms 의 내역).

라운드 B 실측: 프레임당 27.6ms CPU 중 순수 추론이 15.0ms → 나머지 12.6ms 의 내역이 미측정.
이 스크립트가 그 내역을 단계별로 쪼갠다.

⚠️ 로컬(i3-6100 2물리코어)이라 **절대 ms 는 c7i 로 못 옮긴다.** 옮길 수 있는 것은 **비율**이다.
   그래서 모든 표에 «전체 대비 %» 를 같이 낸다.

측정 대상은 app/api/endpoints/pose.py::detect_pose 의 실제 단계들이다:
  ① Pydantic 요청 파싱(base64 문자열 ~14KB)
  ② base64_to_image  = b64decode + np.frombuffer + cv2.imdecode
  ③ cv2.cvtColor BGR→RGB
  ④ MediaPipe process()            ← «순수 추론» 에 해당
  ⑤ 랜드마크 33개 → Landmark(Pydantic) 변환
  ⑥ analyzer.process_frame
  ⑦ _landmarks_to_json (json.dumps 33개)
  ⑧ PoseResponse 생성 + 직렬화
"""
import base64
import json
import os
import statistics as st
import sys
import time

sys.path.insert(0, os.path.abspath("."))
os.environ.setdefault("POSE_DETECTOR_POOL_SIZE", "4")

import cv2
import numpy as np

from app.config import settings
from app.core.analyzer_registry import get_analyzer
from app.core.mediapipe_detector import PoseDetector, get_pool
from app.grpc.session_state import get_registry
from app.models.pose import Landmark, PoseRequest, PoseResponse
from app.utils.image_utils import base64_to_image

FRAMES = json.load(open(sys.argv[1]))["frames"]
N = int(sys.argv[2]) if len(sys.argv) > 2 else 60
SESSION_ID = 990001

print(f"프레임 {len(FRAMES)}장 · 반복 {N} · model_complexity={settings.POSE_MODEL_COMPLEXITY}")
print(f"base64 길이 평균 {int(st.mean(len(f) for f in FRAMES)):,} 자")

det = PoseDetector()
img0 = base64_to_image(FRAMES[0])
print(f"디코드 후 해상도 {img0.shape}")

ref = [[90.0, 90.0, 90.0, 90.0] for _ in range(60)]
state = get_registry().create(SESSION_ID, 1, ref, "squat")
analyzer = get_analyzer("squat")

# 워밍업 — 첫 추론에서 모델 지연 할당(98.5MB)이 일어난다. 그걸 재면 안 된다.
for i in range(5):
    det.detect(cv2.cvtColor(base64_to_image(FRAMES[i]), cv2.COLOR_BGR2RGB))

timings = {k: [] for k in
           ("①요청파싱", "②base64→img", "③cvtColor", "④MediaPipe", "⑤랜드마크변환",
            "⑥analyzer", "⑦json.dumps", "⑧응답생성")}

perf = time.perf_counter
for i in range(N):
    b64 = FRAMES[i % len(FRAMES)]
    raw = {"image": b64, "exercise_type": "squat", "session_id": SESSION_ID}

    t = perf(); req = PoseRequest(**raw);                       timings["①요청파싱"].append(perf() - t)
    t = perf(); bgr = base64_to_image(req.image);               timings["②base64→img"].append(perf() - t)
    t = perf(); rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB);     timings["③cvtColor"].append(perf() - t)

    # ④·⑤ 를 가르기 위해 detect() 를 열어서 잰다 (detect() = process() + 랜드마크 루프)
    t = perf(); results = det._pose.process(rgb);               timings["④MediaPipe"].append(perf() - t)
    t = perf()
    lms = [Landmark(index=j, x=lm.x, y=lm.y, z=lm.z, visibility=lm.visibility)
           for j, lm in enumerate(results.pose_landmarks.landmark)] if results.pose_landmarks else []
    timings["⑤랜드마크변환"].append(perf() - t)

    if not lms:
        continue

    t = perf(); angles, smoothed, rep = analyzer.process_frame(state, lms)
    timings["⑥analyzer"].append(perf() - t)

    t = perf()
    js = json.dumps([{"index": lm.index, "x": lm.x, "y": lm.y, "z": lm.z,
                      "visibility": lm.visibility} for lm in lms])
    timings["⑦json.dumps"].append(perf() - t)

    t = perf()
    resp = PoseResponse(success=True, landmarks=lms, angles=angles, rep_count=state.rep_count)
    _ = resp.model_dump_json()
    timings["⑧응답생성"].append(perf() - t)

print()
rows = []
for k, v in timings.items():
    if not v:
        continue
    rows.append((k, st.mean(v) * 1000, st.median(v) * 1000, len(v)))
total = sum(r[1] for r in rows)

print(f"{'단계':<16}{'평균ms':>9}{'중앙ms':>9}{'전체대비':>9}  n")
print("-" * 55)
for k, mean, med, n in rows:
    print(f"{k:<16}{mean:>9.2f}{med:>9.2f}{mean/total*100:>8.1f}%  {n}")
print("-" * 55)
print(f"{'합계':<16}{total:>9.2f}")

infer = dict((r[0], r[1]) for r in rows)["④MediaPipe"]
print()
print(f"추론 {infer:.2f}ms = 전체의 {infer/total*100:.1f}%")
print(f"추론 밖 {total-infer:.2f}ms = 전체의 {(total-infer)/total*100:.1f}%  "
      f"(추론 대비 {(total-infer)/infer*100:.0f}%)")

get_registry().remove(SESSION_ID) if hasattr(get_registry(), "remove") else None
