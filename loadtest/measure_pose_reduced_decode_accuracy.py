"""JPEG 축소 디코드(레버 ②)가 검출 정확도에 주는 영향 — pose-frame-base64-cost.md §7 의 남은 갭.

§7 은 축소 디코드가 속도를 2.2~2.4배(reduced/4) 올린다는 것만 쟀고, "실제 사람이 있는
프레임에서 정확도에 주는 영향은 범위 밖"이라고 명시적으로 남겨뒀다. 이 스크립트가 그 갭을 잰다.

── 왜 이 합성 인체를 쓰는가 ──────────────────────────────────────────────────────
  `measure_ai_concurrency.py` 의 `figure()`/`squat_cycle()` 을 그대로 가져온다 — 그 스크립트가
  이미 "6개 변형 전부 검출됨, 무릎 visibility 0.91~0.94"로 검증해둔 생성기다. 새로 만들지 않고
  검증된 것을 재사용한다(같은 소스로 비교해야 "생성기가 다름"이 교란변수로 안 섞인다).
  base64-cost 측정의 그라디언트+노이즈 합성과 다른 이미지다 — 그건 NO_POSE 를 의도한 것이고,
  이건 반대로 **반드시 검출돼야** 각도 비교가 가능하다.

── 무엇을 재는가 ────────────────────────────────────────────────────────────────
  peak 해상도(pose-frame-base64-cost.md §1 과 같은 4032x3024, quality 40)로 합성 인체를
  그린 뒤, JPEG 인코드 → 모드별(full/reduced 2/4/8) 디코드 → detect() → extract_angles("squat")
  까지 프로덕션 함수 그대로 태운다. **무릎 각도(왼쪽, angles[0])** 가 이 앱이 실제로 스쿼트
  깊이 판정에 쓰는 값이다([[project_squat_first]]) — visibility 점수보다 이게 "판정이 흔들리는가"
  에 더 직접적인 답이다.

  squat_cycle 은 0(서있음)→1(완전히 앉음)→0 순서로 프레임을 만든다(콜드 상태에서 어려운
  자세부터 주면 검출 자체가 실패하는 것을 measure_ai_concurrency.py 가 이미 확인해뒀다).
  같은 이유로 **판마다 한 바퀴 워밍업 후 두 번째 바퀴를 측정한다**(assert_detectable 과 같은 패턴).
  모드별로 detector 를 새로 만든다 — 해상도가 바뀌면 이전 트래킹 상태가 오염 변수가 된다.

── 비교 방법 ─────────────────────────────────────────────────────────────────────
  각 모드의 무릎 각도 시퀀스를 **full(무보정 원본)** 시퀀스와 같은 squat 위상끼리 대조해
  각도 차이(deg)를 낸다. detection 실패는 별도로 센다 — "각도가 달라졌다"와 "아예 못 찾았다"는
  다른 실패 모드다.
"""

import base64
import statistics
import sys
import os

import cv2
import numpy as np

AI_SERVER_DIR = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ai-server"))
sys.path.insert(0, AI_SERVER_DIR)

from app.core.mediapipe_detector import PoseDetector  # noqa: E402
from app.core.angle_calculator import extract_angles  # noqa: E402

PEAK_W, PEAK_H = 4032, 3024
JPEG_QUALITY = 40
N_CYCLE = 11  # squat: 0, .2, .4, .6, .8, 1.0, .8, .6, .4, .2, 0

MODES = [
    ("full", cv2.IMREAD_COLOR),
    ("reduced/2", cv2.IMREAD_REDUCED_COLOR_2),
    ("reduced/4", cv2.IMREAD_REDUCED_COLOR_4),
    ("reduced/8", cv2.IMREAD_REDUCED_COLOR_8),
]


# measure_ai_concurrency.py 의 figure()/squat_cycle() 그대로 — 새 생성기를 만들지 않는다(§ 위).
def figure(h=PEAK_H, w=PEAK_W, squat=0.0, skin=(180, 150, 130), bg=(230, 230, 230)):
    img = np.full((h, w, 3), bg, np.uint8)
    cx = w // 2
    top = int(h * 0.08)
    H = int(h * 0.87)
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


def squat_levels(n=N_CYCLE):
    return [1 - abs(1 - 2 * i / (n - 1)) for i in range(n)]


def encode_frames(levels):
    """RGB 합성 프레임 → JPEG bytes (peak 해상도·quality, base64-cost.md §1 과 동일 조건)."""
    out = []
    for squat in levels:
        rgb = figure(squat=squat)
        bgr = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
        ok, buf = cv2.imencode(".jpg", bgr, [cv2.IMWRITE_JPEG_QUALITY, JPEG_QUALITY])
        assert ok
        out.append(buf.tobytes())
    return out


def run_mode(jpeg_frames, flag):
    """한 바퀴 워밍업 + 한 바퀴 측정. 반환: [(detected, knee_angle_or_None, knee_vis_or_None), ...] (측정 바퀴)."""
    det = PoseDetector()
    try:
        def pass_once():
            rows = []
            for jb in jpeg_frames:
                arr = np.frombuffer(jb, dtype=np.uint8)
                bgr = cv2.imdecode(arr, flag)
                rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
                lms = det.detect(rgb)
                if not lms:
                    rows.append((False, None, None))
                    continue
                angles = extract_angles(lms, "squat")
                vis = statistics.mean(lm.visibility for lm in lms if lm.index in (25, 26))
                rows.append((True, angles[0], vis))  # angles[0] = 왼쪽 무릎 각도
            return rows

        pass_once()          # 워밍업(콜드 실패 배제)
        return pass_once()   # 측정
    finally:
        det.close()


def main():
    levels = squat_levels()
    jpeg_frames = encode_frames(levels)
    print(f"squat_cycle n={len(levels)}, peak={PEAK_W}x{PEAK_H} quality={JPEG_QUALITY}\n")

    all_rows = {}
    for name, flag in MODES:
        rows = run_mode(jpeg_frames, flag)
        all_rows[name] = rows
        n_det = sum(1 for d, _, _ in rows if d)
        vis_vals = [v for _, _, v in rows if v is not None]
        print(f"[{name:10s}] 검출 {n_det}/{len(rows)}  "
              f"무릎visibility 평균={statistics.mean(vis_vals):.3f}" if vis_vals else
              f"[{name:10s}] 검출 {n_det}/{len(rows)}  visibility 없음(전부 미검출)")

    print("\n== full 대비 무릎 각도 차이(deg) — squat 위상별 ==")
    header = "squat  " + "  ".join(f"{n:>10s}" for n, _ in MODES)
    print(header)
    base_rows = all_rows["full"]
    for i, squat in enumerate(levels):
        cells = []
        base_ok, base_angle, _ = base_rows[i]
        for name, _ in MODES:
            ok, angle, _ = all_rows[name][i]
            if not ok:
                cells.append("MISS")
            elif not base_ok:
                cells.append("(base miss)")
            else:
                cells.append(f"{angle - base_angle:+.2f}")
        print(f"{squat:5.2f}  " + "  ".join(f"{c:>10s}" for c in cells))

    print("\n== 모드별 요약 (full 대비 |각도차| 평균/최대, 검출 실패 건수) ==")
    for name, _ in MODES:
        diffs = []
        misses = 0
        for i in range(len(levels)):
            base_ok, base_angle, _ = base_rows[i]
            ok, angle, _ = all_rows[name][i]
            if not ok:
                misses += 1
                continue
            if base_ok:
                diffs.append(abs(angle - base_angle))
        if diffs:
            print(f"  {name:10s} 평균={statistics.mean(diffs):5.2f}deg  최대={max(diffs):5.2f}deg  검출실패={misses}/{len(levels)}")
        else:
            print(f"  {name:10s} 비교 가능한 프레임 없음  검출실패={misses}/{len(levels)}")


if __name__ == "__main__":
    main()
