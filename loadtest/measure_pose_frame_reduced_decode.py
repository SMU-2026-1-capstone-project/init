"""JPEG 축소 디코드(DCT 도메인) — decode 비용을 줄이는 레버 ②의 실측.

배경: `docs/decisions/pose-frame-base64-cost.md` §0 이 "decode 구간이 비싼 진짜 이유는
base64 가 아니라 JPEG 압축 해제(12MP 원본 복원)"라고 결론냈다. 그 문서 §6 이 두 레버를
제시했는데 — ① 클라이언트가 애초에 작게 캡처(코드 변경 필요, 이 프로젝트 밖의 결정) —
② libjpeg 의 **DCT 도메인 축소 디코드**(서버 단독, 클라 무변경). 이 스크립트는 ②를 잰다.

`cv2.imdecode` 는 `IMREAD_REDUCED_COLOR_2/4/8` 플래그를 지원한다 — JPEG 를 풀 해상도로
디코드한 뒤 resize 하는 게 아니라, DCT 계수 단계에서 1/2·1/4·1/8 로 줄여서 디코드한다
(풀 디코드보다 근본적으로 싸다 — 줄인 만큼 IDCT 연산 자체가 줄어든다).

**주의 — 이 스크립트는 프로덕션 코드(`image_utils.py`)를 안 바꾼다.** 실험용 디코드
함수를 이 파일 안에 따로 둔다 — 측정 결과를 보고 채택할지는 아직 결정 사항이다.

── 무엇을 재는가 ────────────────────────────────────────────────────────────────
  base64_decode(고정) → cv2.imdecode(모드별) → cv2.cvtColor → PoseDetector.detect()
  네 모드: full(현재 프로덕션과 동일) · reduced/2 · reduced/4 · reduced/8

  detect() 까지 같이 재는 이유 — 축소 디코드가 decode 만 싸게 하는 게 아니라, 그 뒤
  MediaPipe 에 들어가는 이미지 자체가 작아지므로 추론 비용도 같이 줄어들 수 있다.
  `pose-frame-base64-cost.md` 의 measure_pose_frame_base64_cost.py 와 같은 이유로
  NO_POSE 합성 이미지를 쓴다 — mediapipe_detector.py:87 이 사람 유무와 무관하게
  전체 추론을 돌리므로(그 문서 §2 에서 코드로 확인) 이 비교에 영향 없다.

  detector 는 각 모드마다 **새로 만든다** — static_image_mode=False 라 내부에 이전
  프레임 트래킹 상태를 들고 있는데, 해상도가 모드마다 바뀌면 그 상태가 오염될 수
  있어서다(측정 대상이 아닌 변수를 섞지 않기 위함).
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

RESULTS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results", "pose-frame-base64-cost-2026-08-26")
FRAME_JSON = os.path.join(RESULTS_DIR, "result.json")

MODES = [
    ("full", cv2.IMREAD_COLOR),
    ("reduced/2", cv2.IMREAD_REDUCED_COLOR_2),
    ("reduced/4", cv2.IMREAD_REDUCED_COLOR_4),
    ("reduced/8", cv2.IMREAD_REDUCED_COLOR_8),
]

N_REPEAT = 20


def build_peak_frame(w, h):
    rng = np.random.default_rng(20260826)
    yy, xx = np.mgrid[0:h, 0:w]
    base = 80 + 60 * np.sin(xx / 220.0) * np.cos(yy / 260.0) + 40 * np.sin((xx + yy) / 90.0)
    img = np.stack([base, base * 0.9 + 15, base * 0.8 + 25], axis=-1)
    block_noise = rng.normal(0, 18, size=(h // 8 + 1, w // 8 + 1, 3))
    block_noise = cv2.resize(block_noise.astype(np.float32), (w, h), interpolation=cv2.INTER_CUBIC)
    fine_noise = rng.normal(0, 10, size=(h, w, 3))
    return np.clip(img + block_noise + fine_noise, 0, 255).astype(np.uint8)


def describe(times_ms):
    s = sorted(times_ms)
    n = len(s)
    def pct(p):
        k = (n - 1) * p
        f, c = int(k), min(int(k) + 1, n - 1)
        return s[f] if f == c else s[f] + (s[c] - s[f]) * (k - f)
    return {"n": n, "mean_ms": round(statistics.mean(s), 3), "p50_ms": round(pct(0.5), 3), "max_ms": round(max(s), 3)}


def main():
    # pose-frame-base64-cost.md 와 같은 peak 가정 — 그 결과 파일에서 원본 raw bytes 를
    # 재사용해 두 측정이 정확히 같은 JPEG 바이트에서 출발하게 한다(다른 합성이면 비교가 깨진다).
    if os.path.exists(FRAME_JSON):
        with open(FRAME_JSON, encoding="utf-8") as f:
            prior = json.load(f)
        w, h = map(int, prior["assumptions"]["peak_resolution"].split("x"))
        quality = prior["assumptions"]["jpeg_quality"]
    else:
        w, h, quality = 4032, 3024, 40

    img = build_peak_frame(w, h)
    ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, quality])
    assert ok
    jpeg_bytes = buf.tobytes()
    b64_str = base64.b64encode(jpeg_bytes).decode("ascii")
    print(f"기준 프레임: {w}x{h} quality={quality} raw={len(jpeg_bytes):,}B\n")

    # 모드별로 블록 반복하지 않고 라운드로빈으로 인터리브한다 — 호스트 경합(다른 동시
    # 세션)이 특정 구간에 몰리면 그 구간을 맡은 모드만 억울하게 느리게 나온다
    # ([[feedback_measure_design_needs_repeats]] 의 취지 — 순서를 판과 분리한다).
    detectors = {name: PoseDetector() for name, _ in MODES}
    raw_times = {name: {"decode": [], "cvt": [], "infer": []} for name, _ in MODES}
    shapes = {}
    try:
        for i in range(N_REPEAT):
            for name, flag in MODES:
                raw = base64.b64decode(b64_str)  # 고정 — 모드와 무관, 매 반복 동일 비용
                arr = np.frombuffer(raw, dtype=np.uint8)

                t0 = time.perf_counter()
                image_bgr = cv2.imdecode(arr, flag)
                raw_times[name]["decode"].append((time.perf_counter() - t0) * 1000.0)
                shapes[name] = image_bgr.shape

                t0 = time.perf_counter()
                image_rgb = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2RGB)
                raw_times[name]["cvt"].append((time.perf_counter() - t0) * 1000.0)

                t0 = time.perf_counter()
                detectors[name].detect(image_rgb)
                raw_times[name]["infer"].append((time.perf_counter() - t0) * 1000.0)
    finally:
        for d in detectors.values():
            d.close()

    results = {}
    for name, flag in MODES:
        shape = shapes[name]
        decode_times = raw_times[name]["decode"]
        cvt_times = raw_times[name]["cvt"]
        infer_times = raw_times[name]["infer"]
        decode_stat = describe(decode_times)
        cvt_stat = describe(cvt_times)
        infer_stat = describe(infer_times)
        chain_p50 = decode_stat["p50_ms"] + cvt_stat["p50_ms"] + infer_stat["p50_ms"]
        results[name] = {
            "resolution": f"{shape[1]}x{shape[0]}",
            "imdecode_ms": decode_stat, "cvtColor_ms": cvt_stat, "infer_ms": infer_stat,
            "decode_cvt_infer_chain_p50_ms": round(chain_p50, 3),
        }
        print(f"[{name:10s}] res={shape[1]}x{shape[0]:<6} "
              f"imdecode p50={decode_stat['p50_ms']:7.2f}ms  "
              f"cvtColor p50={cvt_stat['p50_ms']:6.2f}ms  "
              f"infer p50={infer_stat['p50_ms']:7.2f}ms  "
              f"chain p50={chain_p50:7.2f}ms")

    base_chain = results["full"]["decode_cvt_infer_chain_p50_ms"]
    print("\n== full 대비 배속 (decode+cvt+infer 체인 p50 기준) ==")
    for name, _ in MODES:
        c = results[name]["decode_cvt_infer_chain_p50_ms"]
        print(f"  {name:10s} {base_chain / c:.2f}x" if c else f"  {name:10s} n/a")

    out_path = os.path.join(RESULTS_DIR, "reduced_decode_result.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(f"\n저장: {out_path}")


if __name__ == "__main__":
    main()
