"""§3.3 E2E 상호작용 실험 전용 합성 인체 — coresidency-2026-08-15/synthetic_body.py 의 변형.

원본은 probe.sh G0 가 measure_ai_concurrency.py 원본과 글자 단위로 대조하는 "봉인된 사본"이라
건드리지 않는다. 이 파일은 그 계약 밖의 **별도 실험 전용 사본**이다 — 원본이 만드는 각도
범위(85~124°)가 STANDING_THRESHOLD(150°)를 못 넘어 rep 이 영원히 완성되지 않는 문제
(squat_analyzer.py:359 `waiting_for_standing`→`ready` 전이 조건)를 풀려고
기하를 조정한다. 원본과의 "같은 이름 다른 인체" 드리프트 위험이 없도록 이름 자체를 바꿨다.

1차 시도: 해상도를 올리고(관절 좌표 해상도 ↑) 팔다리 두께를 줄여(관절 위치 추정 흐림 ↓)
MediaPipe 가 서 있는 자세를 더 곧게(더 높은 각도로) 읽게 한다.
"""

import cv2
import numpy as np


def figure(h=960, w=1280, squat=0.0, x_shift=0.0, scale=1.0,
           skin=(180, 150, 130), bg=(230, 230, 230), limb_scale=1.0, joint_markers=False):
    img = np.full((h, w, 3), bg, np.uint8)
    cx = int(w // 2 + w * x_shift)
    top = int(h * 0.08)
    H = int(h * 0.87 * scale)
    bot = top + H
    head_r = int(H / 15)
    hip_y = top + int(H * (0.52 + 0.18 * squat))
    knee_y = top + int(H * (0.75 + 0.05 * squat))
    knee_x = int(H * 0.10 * squat)
    sh_y = top + int(H * 0.26)

    cv2.circle(img, (cx, top + head_r), head_r, skin, -1)
    cv2.line(img, (cx, top + 2 * head_r), (cx, hip_y), skin, int(H * 0.11 * limb_scale))
    cv2.line(img, (cx - int(H * 0.09), sh_y), (cx + int(H * 0.09), sh_y), skin, int(H * 0.05 * limb_scale))
    joint_r = int(H * 0.035 * limb_scale)
    for s in (-1, 1):
        sx = cx + s * int(H * 0.09)
        cv2.line(img, (sx, sh_y), (sx + s * int(H * 0.04), sh_y + int(H * 0.16)), skin, int(H * 0.045 * limb_scale))
        cv2.line(img, (sx + s * int(H * 0.04), sh_y + int(H * 0.16)),
                 (sx + s * int(H * 0.02), sh_y + int(H * 0.30)), skin, int(H * 0.04 * limb_scale))
        hx = cx + s * int(H * 0.05)
        cv2.line(img, (hx, hip_y), (hx + knee_x, knee_y), skin, int(H * 0.06 * limb_scale))
        cv2.line(img, (hx + knee_x, knee_y), (hx, bot), skin, int(H * 0.05 * limb_scale))
        if joint_markers:
            # hip·knee·ankle 위치에 작은 원을 얹어 MediaPipe 가 관절을 더 명확히 잡도록 유도.
            cv2.circle(img, (hx, hip_y), joint_r, skin, -1)
            cv2.circle(img, (hx + knee_x, knee_y), joint_r, skin, -1)
            cv2.circle(img, (hx, bot), joint_r, skin, -1)
    return cv2.cvtColor(img, cv2.COLOR_BGR2RGB)


def squat_cycle(n=20, **kw):
    return [figure(squat=1 - abs(1 - 2 * i / (n - 1)), **kw) for i in range(n)]
