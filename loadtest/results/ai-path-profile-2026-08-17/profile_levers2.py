"""④ 레버 재측정 — 팔을 «한 프레임씩 번갈아» 돈다.

앞선 판이 팔을 블록으로 돌아서 배경 부하(IDEA·Chrome 이 2코어를 100% 점유)가 팔에 통째로
얹혔다. 그래서 «320x240 이 640x480 보다 느리다» 같은 자기모순이 나왔다.

이 판은 프레임 하나마다 팔을 round-robin 으로 바꾼다 — 배경 부하가 팔에 **균등하게** 흩어진다.
지표는 평균이 아니라 **중앙값**이다(스파이크에 안 끌린다). 판마다 팔 순서를 회전한다.

⚠️ 절대 ms 는 여전히 인용 불가(2코어·경합 박스). **팔 사이 배수만** 읽는다.
"""
import json
import os
import statistics as st
import sys
import time

sys.path.insert(0, os.path.abspath("."))

import cv2
import mediapipe as mp

from app.utils.image_utils import base64_to_image

frames = json.load(open(sys.argv[1]))["frames"]
N = int(sys.argv[2]) if len(sys.argv) > 2 else 60
imgs = [cv2.cvtColor(base64_to_image(f), cv2.COLOR_BGR2RGB) for f in frames[:10]]
H, W = imgs[0].shape[:2]


def mk(complexity):
    return mp.solutions.pose.Pose(static_image_mode=False, model_complexity=complexity,
                                  min_detection_confidence=0.5, min_tracking_confidence=0.5)


def interleaved(arms, N, warm=8):
    """arms = [(라벨, pose, 이미지들)] — 프레임마다 팔을 바꿔 가며 잰다."""
    for label, pose, ims in arms:
        for i in range(warm):
            pose.process(ims[i % len(ims)])
    ts = {label: [] for label, _, _ in arms}
    for i in range(N):
        order = arms[i % len(arms):] + arms[:i % len(arms)]      # 판마다 순서 회전
        for label, pose, ims in order:
            im = ims[i % len(ims)]
            t = time.perf_counter()
            pose.process(im)
            ts[label].append(time.perf_counter() - t)
    return ts


def show(title, ts, base_label, extra=None):
    print(f"\n{title}")
    print(f"{'팔':>12}{'중앙ms':>9}{'p25':>8}{'p75':>8}{'기준 대비':>10}")
    print("-" * 48)
    med = {k: st.median(v) * 1000 for k, v in ts.items()}
    for k, v in ts.items():
        s = sorted(x * 1000 for x in v)
        p25, p75 = s[len(s) // 4], s[len(s) * 3 // 4]
        line = f"{k:>12}{med[k]:>9.1f}{p25:>8.1f}{p75:>8.1f}{med[k]/med[base_label]:>9.2f}x"
        if extra:
            line += extra.get(k, "")
        print(line)


# (가) model_complexity — 같은 이미지, 모델만 다르다
arms = [(f"complexity{c}", mk(c), imgs) for c in (0, 1, 2)]
show(f"(가) model_complexity — 원본 {W}x{H} · {N}프레임/팔 · 번갈아",
     interleaved(arms, N), "complexity1")
for _, p, _ in arms:
    p.close()

# (나) 해상도 — 같은 모델(1), 이미지만 다르다. 검출 성공 수도 같이 본다.
res = [(0.5, "320x240"), (1.0, f"{W}x{H}"), (1.5, "960x720")]
arms2, det = [], {}
for scale, label in res:
    ims = [cv2.resize(im, (int(W * scale), int(H * scale))) for im in imgs]
    pose = mk(1)
    arms2.append((label, pose, ims))
show(f"(나) 입력 해상도 — complexity=1 · {N}프레임/팔 · 번갈아",
     interleaved(arms2, N), f"{W}x{H}")
for label, pose, ims in arms2:
    ok = sum(1 for im in ims if pose.process(im).pose_landmarks is not None)
    print(f"     {label} 검출 {ok}/{len(ims)}")
    pose.close()
