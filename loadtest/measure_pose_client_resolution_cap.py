"""레버 ① — 클라이언트가 애초에 작게 캡처(pictureSize 캡)했을 때의 속도·정확도.

`pose-frame-base64-cost.md` §6이 갈라둔 두 레버 중 ②(서버 단독 축소 디코드)는 §8에서
"빠르지만 각도 오차가 이 프로젝트의 판정 여지(3.5°, `feedback-argmax-rules.md`)를 넘는다"로
탈락했다. 이 스크립트는 ①을 잰다 — **"12MP로 찍고 줄여서 디코드"가 아니라 "애초에 그
해상도로 찍는다"**. DCT 축소 디코드는 이미 존재하는 12MP JPEG의 정보를 나중에 버리는
것이고, 캡처 캡은 처음부터 그 정보를 안 만드는 것이라 화질 특성이 다를 수 있다 — 그 차이가
실제로 정확도에 영향을 주는지가 이 실험의 질문이다.

── 방법 ─────────────────────────────────────────────────────────────────────────
  같은 `figure()`(measure_ai_concurrency.py 에서 검증된 합성 인체)를 목표 해상도에서
  **직접** 그린다(4032x3024 로 그린 뒤 축소하는 게 아니다 — 그러면 §8 의 reduced-decode와
  같은 실험이 된다). JPEG 인코드(quality 40) → **전체 디코드**(축소 플래그 없음, 이미
  작으므로 필요 없다) → detect() → extract_angles("squat"). 왼쪽 무릎 각도를 §1 의
  peak(4032x3024, 무보정) 기준과 대조한다.

  해상도 후보: peak(4032x3024, 기준선) · 1920x1440(≈2.8MP, "1080p급 4:3") ·
  1280x960(≈1.2MP) · 960x720(≈0.7MP) · 640x480(VGA, ≈0.3MP, 하한 참고용).

  속도는 라운드로빈(해상도끼리 반복마다 번갈아 — §7 의 교훈, 블록 실행은 호스트 경합에
  취약하다), 정확도는 §8 과 같은 워밍업 1바퀴 + 측정 1바퀴 구조.
"""

import base64
import json
import os
import statistics
import sys
import time

import cv2
import numpy as np

AI_SERVER_DIR = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ai-server"))
sys.path.insert(0, AI_SERVER_DIR)

from app.core.mediapipe_detector import PoseDetector  # noqa: E402
from app.core.angle_calculator import extract_angles  # noqa: E402

JPEG_QUALITY = 40
N_CYCLE = 11
N_SPEED_REPEAT = 15

# (이름, w, h) — 전부 4:3, figure() 가 비율 기반이라 그대로 맞는다
RESOLUTIONS = [
    ("peak(4032x3024)", 4032, 3024),
    ("1920x1440", 1920, 1440),
    ("1280x960", 1280, 960),
    ("960x720", 960, 720),
    ("640x480(VGA)", 640, 480),
]


def figure(h, w, squat=0.0, skin=(180, 150, 130), bg=(230, 230, 230)):
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


def encode_at(w, h, levels):
    frames = []
    for sq in levels:
        rgb = figure(h, w, squat=sq)
        bgr = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
        ok, buf = cv2.imencode(".jpg", bgr, [cv2.IMWRITE_JPEG_QUALITY, JPEG_QUALITY])
        assert ok
        frames.append(buf.tobytes())
    return frames


def describe(times_ms):
    s = sorted(times_ms)
    n = len(s)
    def pct(p):
        k = (n - 1) * p
        f, c = int(k), min(int(k) + 1, n - 1)
        return s[f] if f == c else s[f] + (s[c] - s[f]) * (k - f)
    return {"n": n, "mean_ms": round(statistics.mean(s), 3), "p50_ms": round(pct(0.5), 3)}


def measure_accuracy(jpeg_frames_by_res, levels):
    """각 해상도에서 워밍업 1바퀴 + 측정 1바퀴 → (검출여부, 무릎각도) 리스트."""
    out = {}
    for name, _, _ in RESOLUTIONS:
        det = PoseDetector()
        def pass_once(frames):
            rows = []
            for jb in frames:
                arr = np.frombuffer(jb, dtype=np.uint8)
                bgr = cv2.imdecode(arr, cv2.IMREAD_COLOR)
                rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
                lms = det.detect(rgb)
                if not lms:
                    rows.append((False, None))
                    continue
                angles = extract_angles(lms, "squat")
                rows.append((True, angles[0]))
            return rows
        frames = jpeg_frames_by_res[name]
        pass_once(frames)
        out[name] = pass_once(frames)
        det.close()
    return out


def measure_speed(jpeg_frames_by_res):
    """해상도끼리 라운드로빈 — decode+cvtColor+infer 체인 시간(스쿼트 정점 프레임 하나로 반복)."""
    detectors = {name: PoseDetector() for name, _, _ in RESOLUTIONS}
    raw = {name: [] for name, _, _ in RESOLUTIONS}
    try:
        for _ in range(N_SPEED_REPEAT):
            for name, _, _ in RESOLUTIONS:
                jb = jpeg_frames_by_res[name][N_CYCLE // 2]  # squat=1.0 프레임
                arr = np.frombuffer(jb, dtype=np.uint8)
                t0 = time.perf_counter()
                bgr = cv2.imdecode(arr, cv2.IMREAD_COLOR)
                rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
                detectors[name].detect(rgb)
                raw[name].append((time.perf_counter() - t0) * 1000.0)
    finally:
        for d in detectors.values():
            d.close()
    return {name: describe(t) for name, t in raw.items()}


def main():
    levels = squat_levels()
    jpeg_frames_by_res = {}
    payload = {}
    for name, w, h in RESOLUTIONS:
        frames = encode_at(w, h, levels)
        jpeg_frames_by_res[name] = frames
        mid = frames[N_CYCLE // 2]
        payload[name] = {"raw_bytes": len(mid), "base64_bytes": len(base64.b64encode(mid))}

    print("== 페이로드 (squat=1.0 프레임 기준) ==")
    for name, _, _ in RESOLUTIONS:
        p = payload[name]
        print(f"  {name:18s} raw={p['raw_bytes']:>9,}B  base64={p['base64_bytes']:>9,}B")

    print("\n== 속도 — decode+cvtColor+infer 체인 (라운드로빈, p50) ==")
    speed = measure_speed(jpeg_frames_by_res)
    peak_p50 = speed["peak(4032x3024)"]["p50_ms"]
    for name, _, _ in RESOLUTIONS:
        p50 = speed[name]["p50_ms"]
        print(f"  {name:18s} p50={p50:8.2f}ms  peak 대비 {peak_p50/p50:.2f}x")

    print("\n== 정확도 — peak(4032x3024, 무보정) 대비 무릎 각도차 ==")
    acc = measure_accuracy(jpeg_frames_by_res, levels)
    base_rows = acc["peak(4032x3024)"]
    for name, _, _ in RESOLUTIONS:
        rows = acc[name]
        diffs, misses = [], 0
        for i in range(len(levels)):
            bok, ba = base_rows[i]
            ok, a = rows[i]
            if not ok:
                misses += 1
                continue
            if bok:
                diffs.append(abs(a - ba))
        if diffs:
            print(f"  {name:18s} 평균={statistics.mean(diffs):5.2f}deg  최대={max(diffs):5.2f}deg  검출실패={misses}/{len(levels)}")
        else:
            print(f"  {name:18s} 비교불가  검출실패={misses}/{len(levels)}")

    result = {"payload": payload, "speed": speed}
    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results", "pose-frame-base64-cost-2026-08-26")
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "client_resolution_cap_result.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(f"\n저장: {os.path.join(out_dir, 'client_resolution_cap_result.json')}")


if __name__ == "__main__":
    main()
