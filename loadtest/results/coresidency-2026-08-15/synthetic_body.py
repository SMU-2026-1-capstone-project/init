"""합성 인체 — `loadtest/measure_ai_concurrency.py:55-96` 의 **사본이다.**

## 왜 사본인가 (import 가 아니라)

원본 rig 은 **import 만 해도 측정이 통째로 돌아가는 스크립트**다(`__main__` 가드가 없고
모듈 최상위에서 검출기를 만들고 스윕을 돈다). 그래서 재사용하려면 함수를 옮겨야 하는데,
옮기면 그 rig 의 **단일 파일 이식성**이 깨진다 — 08-09·08-11·08-14 세 라운드가 전부
`docker cp loadtest/measure_ai_concurrency.py shadowfit-ai:/tmp/m.py` **한 파일만** 복사해서
돌렸다. 검증된 rig 을 리팩터로 깨뜨리는 것보다 사본이 낫다고 판단했다.

## 🔴 그래서 드리프트가 위험하다 — `probe.sh` G0 가 막는다

두 벌이 갈라지면 **두 측정이 서로 다른 인체를 재면서 같은 이름을 쓰게 된다.** 이 repo 가
반복해 밟은 함정이라 그냥 두지 않는다. `probe.sh` 의 G0 가 원본에서 같은 구간을 떼어
**본문을 글자 단위로 대조**하고, 다르면 게이트에서 멈춘다.

⚠️ 이 파일은 **그리기만** 한다. 검출·측정은 rig 의 몫이다.
"""

import cv2
import numpy as np

def figure(h=480, w=640, squat=0.0, x_shift=0.0, scale=1.0,
           skin=(180, 150, 130), bg=(230, 230, 230)):
    """사람 비율(머리≈키/7.5)로 그린 인체. squat 0=서있음 1=앉음.

    x_shift/scale 은 «다른 사람» 을 만들기 위한 것 — 위치·크기가 크게 다르면
    직전 프레임 기준 트래킹이 깨진다.
    """
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
    cv2.line(img, (cx, top + 2 * head_r), (cx, hip_y), skin, int(H * 0.11))
    cv2.line(img, (cx - int(H * 0.09), sh_y), (cx + int(H * 0.09), sh_y), skin, int(H * 0.05))
    for s in (-1, 1):
        sx = cx + s * int(H * 0.09)
        cv2.line(img, (sx, sh_y), (sx + s * int(H * 0.04), sh_y + int(H * 0.16)), skin, int(H * 0.045))
        cv2.line(img, (sx + s * int(H * 0.04), sh_y + int(H * 0.16)),
                 (sx + s * int(H * 0.02), sh_y + int(H * 0.30)), skin, int(H * 0.04))
        hx = cx + s * int(H * 0.05)
        cv2.line(img, (hx, hip_y), (hx + knee_x, knee_y), skin, int(H * 0.06))
        cv2.line(img, (hx + knee_x, knee_y), (hx, bot), skin, int(H * 0.05))
    return cv2.cvtColor(img, cv2.COLOR_BGR2RGB)


def squat_cycle(n=20, **kw):
    """스쿼트 1회를 n 프레임으로 — **서 있는 자세에서 시작**한다(0 → 1 → 0).

    ⚠️ 순서가 중요하다. 처음에 squat=1.0(완전히 앉음)으로 시작하면 검출이 실패한다.
    트래킹이 없는 콜드 상태에서는 사람 탐지기가 그 자세를 못 잡고, 쉬운 자세로 트래킹이
    잡힌 뒤에는 **같은 이미지도** 잡는다(2026-08-09 확인: 첫 프레임 X, 마지막 프레임 O).
    실제 세션도 서 있는 상태로 시작하므로 이 순서가 실물에 더 가깝다.
    """
    return [figure(squat=1 - abs(1 - 2 * i / (n - 1)), **kw) for i in range(n)]
