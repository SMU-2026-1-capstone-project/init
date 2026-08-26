"""POST /pose 프레임의 base64 인코딩 비용 — 페이로드 크기 + 디코드/추론 구간 실측

배경: `docs/tasks/33-outbox-fastapi-spring-nginx-grpc-open-items.md` §2 가 "base64 프레임
HTTP POST 비용 미측정"으로 적어둔 항목. `app/models/pose.py:49-58` 주석도 같은 갭을 자인한다 —
"실제 디바이스 촬영본의 크기 분포를 잰 적이 없다."

── 이 환경에서 무엇을 잴 수 있고 없는가 ────────────────────────────────────────
  measure_ai_concurrency.py 와 같은 제약: i3-6100(물리 2코어+HT), 이 절대 지연시간은
  «이 프로세스가 이 순간 이 박스에서 얼마나 걸렸나»이지 이식 가능한 절대값이 아니다.
  옮겨 쓸 수 있는 것은 (a) base64 인플레이션 배율(수학적 상수, 4/3), (b) decode 구간이
  infer 구간 대비 몇 배인가(같은 박스·같은 판 안의 **상대** 비교) 둘이다.

── 왜 peak 해상도이고 그 값의 근거 ──────────────────────────────────────────────
  exercise.tsx 의 takePictureAsync 는 pictureSize 를 지정하지 않는다 — 실제 캡처 해상도가
  기기 기본값에 맡겨져 있다는 뜻이고, 이게 이 측정이 답하려는 질문 자체다("얼마나 클 수
  있는가"). PEAK_W×PEAK_H(12MP, 4:3)는 **가정**이다 — iPhone/갤럭시 S 시리즈급 플래그십
  전면 카메라의 흔한 상한을 골랐을 뿐, 32MP+ 전면 카메라를 쓰는 일부 Android 기종은 이보다
  더 클 수 있다. "절대 상한"이 아니라 "전형적 상한"([[feedback_no_arbitrary_threshold_values]]
  의 구분 — 이 값을 threshold 로 코드에 넣을 근거는 아니다, 측정 조건일 뿐).

── 왜 합성 이미지이고 사람이 없어도 되는 이유 ───────────────────────────────────
  measure_ai_concurrency.py 는 "탐지 실패 시 다른(더 싼) 코드 경로를 탈 수 있다"고 경고하며
  사람 모양 합성 이미지를 쓴다. 이 측정은 그 경고를 다른 근거로 우회한다 — 코드를 직접
  추적해 mediapipe_detector.py:87 의 `self._pose.process(image_rgb)` 가 사람을 찾든 못
  찾든 **무조건 전체 추론을 돈다**(조기 반환 없음)는 것을 확인했다. 그래도 이 프레임은
  실제로 NO_POSE 로 반환된다 — «측정 대상 구간이 조기 반환으로 짧아지지 않는다»는 것이
  코드 추적으로 확인된 사실이지 추정이 아니라는 점을 여기 남긴다.

  이미지 자체는 그라디언트+구조적 노이즈 합성이다(순수 랜덤 노이즈나 단색은 JPEG 압축률을
  왜곡한다 — 전자는 실제 사진보다 압축이 덜 되고 후자는 훨씬 더 된다). 실제 기기 촬영본이
  아니므로 페이로드 크기는 근사치다([[project_synthetic_data_distribution_limit]]과 같은 한계).

── 재는 것 ─────────────────────────────────────────────────────────────────────
  [1] 페이로드 — raw JPEG vs base64 크기, 인플레이션 배율
  [2] 서버 구간별 시간 — `app/observability/frame_path.py` 계측을 그대로 쓴다(wait/decode/
      lease/infer/post/respond/total). decode 구간이 base64_to_image() 를 포함한다 —
      "base64 비용"이 이 구간 하나로 안 떨어지고(JPEG 디코드·색변환도 같이 잡힌다) 별도로
      base64.b64decode() 만 격리해 참고값을 낸다(§ 아래 STANDALONE_B64_DECODE).
  [3] DAU 1,000 피크 가정에서의 집계 — load-test-strategy.md §4.2 의 동접 공식을 그대로
      재사용(67.5세션), exercise.tsx:181 의 intervalMs=330(≈3.03fps)과 곱해 피크 총
      frame/s 를 낸 뒤 대역폭·CPU 코어 수로 환산한다. **전 동접 세션이 동시에 peak 크기를
      보낸다는 최악 가정**이지 실사용 분포가 아니다 — 실제 분포를 재려면 별도 실측(기기
      다양성)이 필요하다(이 스크립트의 범위 밖).
"""

import base64
import json
import os
import statistics
import subprocess
import sys
import time
import urllib.request

import cv2
import numpy as np

AI_SERVER_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ai-server")
AI_SERVER_DIR = os.path.normpath(AI_SERVER_DIR)
sys.path.insert(0, AI_SERVER_DIR)

from app.utils.image_utils import base64_to_image  # noqa: E402  프로덕션 함수 그대로 사용

RESULTS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results", "pose-frame-base64-cost-2026-08-26")

# ── 가정값 (사용자 confirm, 2026-08-26) ─────────────────────────────────────────
PEAK_W, PEAK_H = 4032, 3024          # 12MP 4:3 — 가정, 근거는 모듈 docstring 참고
JPEG_QUALITY = 40                     # exercise.tsx:194 quality=0.4 의 근사
DAU = 1000
SESSIONS_PER_DAY_PER_USER = 1.5       # load-test-strategy.md §4.2
PEAK_CONCENTRATION_P = 0.18           # load-test-strategy.md §4.2 가정 P1
SESSION_LENGTH_MIN = 15               # load-test-strategy.md §4.2
TICK_INTERVAL_MS = 330                # exercise.tsx:181
FPS_PER_SESSION = 1000.0 / TICK_INTERVAL_MS

N_STANDALONE_TIMING = 30
N_HTTP_WARMUP = 5
N_HTTP_MEASURED = 12

TOKEN = "measure-base64-cost-local-token"


def build_peak_frame() -> np.ndarray:
    rng = np.random.default_rng(20260826)
    yy, xx = np.mgrid[0:PEAK_H, 0:PEAK_W]
    base = 80 + 60 * np.sin(xx / 220.0) * np.cos(yy / 260.0) + 40 * np.sin((xx + yy) / 90.0)
    img = np.stack([base, base * 0.9 + 15, base * 0.8 + 25], axis=-1)
    block_noise = rng.normal(0, 18, size=(PEAK_H // 8 + 1, PEAK_W // 8 + 1, 3))
    block_noise = cv2.resize(block_noise.astype(np.float32), (PEAK_W, PEAK_H), interpolation=cv2.INTER_CUBIC)
    fine_noise = rng.normal(0, 10, size=(PEAK_H, PEAK_W, 3))
    img = np.clip(img + block_noise + fine_noise, 0, 255).astype(np.uint8)
    return img


def describe(times_ms):
    s = sorted(times_ms)
    n = len(s)
    def pct(p):
        k = (n - 1) * p
        f, c = int(k), min(int(k) + 1, n - 1)
        return s[f] if f == c else s[f] + (s[c] - s[f]) * (k - f)
    return {"n": n, "mean_ms": round(statistics.mean(s), 3), "p50_ms": round(pct(0.5), 3),
            "p95_ms": round(pct(0.95), 3), "max_ms": round(max(s), 3)}


def time_repeated(fn, n):
    out = []
    for _ in range(n):
        t0 = time.perf_counter()
        fn()
        out.append((time.perf_counter() - t0) * 1000.0)
    return out


def run_http_probe(b64_image: str, port: int = 18321):
    env = os.environ.copy()
    env["AI_PUBLIC_TOKEN"] = TOKEN
    env["INTERNAL_API_TOKEN"] = "measure-base64-cost-internal-token"
    env["FRAME_PATH_METRICS"] = "true"
    proc = subprocess.Popen(
        [os.path.join(AI_SERVER_DIR, ".venv", "Scripts", "python.exe"), "-m", "uvicorn",
         "app.main:app", "--host", "127.0.0.1", "--port", str(port), "--log-level", "warning"],
        cwd=AI_SERVER_DIR, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT,
    )
    base = f"http://127.0.0.1:{port}"

    def wait_healthy(timeout_s=30):
        t0 = time.time()
        while time.time() - t0 < timeout_s:
            try:
                with urllib.request.urlopen(f"{base}/health", timeout=2) as r:
                    if r.status == 200:
                        return True
            except Exception:
                time.sleep(0.5)
        return False

    def post_pose():
        body = json.dumps({"image": b64_image, "exercise_type": "squat", "session_id": None}).encode()
        req = urllib.request.Request(f"{base}/api/v1/pose", data=body, method="POST",
                                      headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"})
        t0 = time.perf_counter()
        with urllib.request.urlopen(req, timeout=30) as r:
            r.read()
            status = r.status
        return status, (time.perf_counter() - t0) * 1000.0

    def get_json(path, method="GET"):
        req = urllib.request.Request(f"{base}{path}", method=method, headers={"Authorization": f"Bearer {TOKEN}"})
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.loads(r.read())

    try:
        if not wait_healthy():
            raise RuntimeError("서버가 안 떴다")
        for _ in range(N_HTTP_WARMUP):
            post_pose()
        get_json("/api/v1/diag/frame-path/reset", method="POST")
        client_times = [post_pose()[1] for _ in range(N_HTTP_MEASURED)]
        snap = get_json("/api/v1/diag/frame-path")
        return {"client_side_ms": describe(client_times), "frame_path_spans": snap.get("spans", {})}
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except Exception:
            proc.kill()


def main():
    os.makedirs(RESULTS_DIR, exist_ok=True)

    print("== [1] 페이로드 ==")
    img = build_peak_frame()
    ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, JPEG_QUALITY])
    assert ok
    jpeg_bytes = buf.tobytes()
    b64_str = base64.b64encode(jpeg_bytes).decode("ascii")
    raw_len, b64_len = len(jpeg_bytes), len(b64_str)
    print(f"해상도={PEAK_W}x{PEAK_H} quality={JPEG_QUALITY} raw={raw_len:,}B b64={b64_len:,}B "
          f"배율={b64_len/raw_len:.4f}x")

    print("\n== [2] standalone base64 decode 참고값 (frame_path 의 decode 구간과 별도 대조) ==")
    standalone_decode = describe(time_repeated(lambda: base64.b64decode(b64_str), N_STANDALONE_TIMING))
    print("base64.b64decode 만:", standalone_decode)

    print("\n== [3] 실서버 HTTP round-trip + frame_path 구간 ==")
    http_result = run_http_probe(b64_str)
    print("client-side:", http_result["client_side_ms"])
    for span, stat in http_result["frame_path_spans"].items():
        print(f"  {span:10s} p50={stat.get('p50_ms')}ms p95={stat.get('p95_ms')}ms")

    print("\n== [4] DAU 1,000 피크 집계 (최악 가정 — 전 동접이 peak 크기) ==")
    peak_sessions = DAU * SESSIONS_PER_DAY_PER_USER * PEAK_CONCENTRATION_P * (SESSION_LENGTH_MIN / 60)
    peak_fps_total = peak_sessions * FPS_PER_SESSION
    decode_p50 = http_result["frame_path_spans"].get("decode", {}).get("p50_ms", float("nan"))
    infer_p50 = http_result["frame_path_spans"].get("infer", {}).get("p50_ms", float("nan"))
    agg = {
        "peak_concurrent_sessions": round(peak_sessions, 2),
        "peak_fps_total": round(peak_fps_total, 2),
        "raw_bandwidth_MBps": round(peak_fps_total * raw_len / 1e6, 2),
        "base64_bandwidth_MBps": round(peak_fps_total * b64_len / 1e6, 2),
        "extra_bandwidth_from_base64_MBps": round(peak_fps_total * (b64_len - raw_len) / 1e6, 2),
        "decode_cpu_cores_worst_case": round(peak_fps_total * decode_p50 / 1000, 2),
        "infer_cpu_cores_worst_case": round(peak_fps_total * infer_p50 / 1000, 2),
    }
    print(json.dumps(agg, ensure_ascii=False, indent=2))

    result = {
        "assumptions": {
            "peak_resolution": f"{PEAK_W}x{PEAK_H}", "jpeg_quality": JPEG_QUALITY, "dau": DAU,
            "sessions_per_day_per_user": SESSIONS_PER_DAY_PER_USER, "peak_concentration_p": PEAK_CONCENTRATION_P,
            "session_length_min": SESSION_LENGTH_MIN, "tick_interval_ms": TICK_INTERVAL_MS,
        },
        "payload": {"raw_jpeg_bytes": raw_len, "base64_bytes": b64_len, "inflation_ratio": b64_len / raw_len},
        "standalone_base64_decode_ms": standalone_decode,
        "http_probe": http_result,
        "peak_aggregate": agg,
    }
    with open(os.path.join(RESULTS_DIR, "result.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(f"\n저장: {os.path.join(RESULTS_DIR, 'result.json')}")


if __name__ == "__main__":
    main()
