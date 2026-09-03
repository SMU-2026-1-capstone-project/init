"""실기기(스톡) 촬영본으로 레버 ①(해상도 캡)·②(축소 디코드)를 재검증 — pose-frame-base64-cost.md
§9 결론("합성 렌더링이라 절대 수치·순위 신뢰도 낮음, 실기기 촬영본 검증이 진짜 다음 단계")의 갭.

── 소재 ──────────────────────────────────────────────────────────────────────────
  `~/Downloads`의 스쿼트 스톡 영상 9개(Pexels류, 사용자 확인 완료 — 실제 스쿼트 동작).
  이 앱 자신의 캡처 파이프라인(전면 카메라 셀피, expo-camera)으로 찍은 게 아니라는 한계는
  분명하다 — 그래도 §9가 지목한 진짜 문제(합성 스틱 피겨의 해상도별 안티앨리어싱 인공물)는
  피한다: 이건 실제 카메라 광학·센서·JPEG 압축 특성을 가진 진짜 사진이다.

── §9(레버①)와 달라지는 점 ─────────────────────────────────────────────────────────
  §9는 4032x3024(12MP) 피크를 가정하고 절대 해상도(1920x1440 등)로 캡을 걸었다. 이 영상들의
  네이티브 해상도는 그보다 작다(0.7~8.8MP, 세로/가로 혼재) — "12MP에서 캡"이 아니라 "각 영상의
  네이티브 해상도에서 캡"이 된다. 그래서 절대 해상도 대신 §9와 같은 **상대 화소수 축소율**
  (native 대비 1x/4.4x/9.9x/17.6x/39.7x, §9의 12MP→1920x1440→1280x960→960x720→640x480과 같은
  비율)을 씀 — 메커니즘(다운샘플이 각도를 얼마나 흔드는가)은 같은 조건으로 비교된다.
  다운샘플은 `cv2.resize(..., INTER_AREA)`(사진 축소의 표준 리샘플러) — 벡터 재렌더링이 아니라
  "이미 찍힌 사진을 축소"이므로 엄밀히는 "캡처 캡" 그 자체보다 §7/§8의 축소 디코드에 더 가까운
  근사다(카메라가 애초에 그 해상도로 찍었을 때의 센서·데모자이킹 특성까지는 재현 못 함) — 그래도
  §8이 이미 확인한 것과 같은 종류의 질문(다운샘플이 무릎 각도를 얼마나 흔드는가)에는 답이 된다.

── §7/§8(레버②)과 같은 방법 ────────────────────────────────────────────────────────
  네이티브 해상도로 JPEG 인코드(quality 40, 이 앱 가정과 동일) → full/reduced(2/4/8) 디코드 →
  detect() → extract_angles("squat")[0](왼쪽 무릎 각도). full 대비 각도차를 비교.

── 프레임 표본 ───────────────────────────────────────────────────────────────────
  합성 squat_cycle(0→1→0, 위상 통제)과 달리 실촬영본은 위상을 못 만든다 — 대신 각 영상에서
  균등 간격으로 N_SAMPLES 프레임을 뽑는다(실제 rep 진행 중 자연스러운 각도 분포). 네이티브
  full-decode에서 검출 실패한 프레임은 비교 기준(앵커)이 없어 그 프레임 전체를 제외한다.

  모드/스케일마다 새 PoseDetector를 만들되(§7/§8/§9와 같은 이유 — 해상도가 바뀌면 이전
  트래킹 상태가 교란변수), 한 영상 내 표본 프레임은 그 detector로 순서대로 처리한다.
"""

import glob
import json
import os
import statistics
import sys

import cv2
import numpy as np

AI_SERVER_DIR = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ai-server"))
sys.path.insert(0, AI_SERVER_DIR)

from app.core.mediapipe_detector import PoseDetector  # noqa: E402
from app.core.angle_calculator import extract_angles  # noqa: E402

DOWNLOADS_DIR = os.path.join(os.path.expanduser("~"), "Downloads")
JPEG_QUALITY = 40
N_SAMPLES = 15  # 영상당 균등 표본 프레임 수

REDUCED_MODES = [
    ("full", cv2.IMREAD_COLOR),
    ("reduced/2", cv2.IMREAD_REDUCED_COLOR_2),
    ("reduced/4", cv2.IMREAD_REDUCED_COLOR_4),
    ("reduced/8", cv2.IMREAD_REDUCED_COLOR_8),
]

# §9와 같은 상대 화소수 축소율 (12MP peak -> 1920x1440/1280x960/960x720/640x480 비율 재사용)
RES_SCALES = [
    ("native(1x)", 1.0),
    ("MP/4.4(~1920x1440급)", 1.0 / (4.408 ** 0.5)),
    ("MP/9.9(~1280x960급)", 1.0 / (9.92 ** 0.5)),
    ("MP/17.6(~960x720급)", 1.0 / (17.63 ** 0.5)),
    ("MP/39.7(~640x480급)", 1.0 / (39.7 ** 0.5)),
]


def find_videos():
    return sorted(glob.glob(os.path.join(DOWNLOADS_DIR, "*fps*.mp4")))


def sample_frames(path, n=N_SAMPLES):
    cap = cv2.VideoCapture(path)
    if not cap.isOpened():
        return None, None, None
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    idxs = sorted(set(int(total * i / n) for i in range(n)))
    frames = []
    for idx in idxs:
        cap.set(cv2.CAP_PROP_POS_FRAMES, idx)
        ok, bgr = cap.read()
        if ok:
            frames.append(bgr)
    cap.release()
    return frames, w, h


def encode(bgr, quality=JPEG_QUALITY):
    ok, buf = cv2.imencode(".jpg", bgr, [cv2.IMWRITE_JPEG_QUALITY, quality])
    assert ok
    return buf.tobytes()


def detect_angle(det, bgr):
    rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
    lms = det.detect(rgb)
    if not lms:
        return False, None, None
    angles = extract_angles(lms, "squat")
    vis = statistics.mean(lm.visibility for lm in lms if lm.index in (25, 26))
    return True, angles[0], vis


def run_reduced_decode(native_jpeg_by_frame):
    """모드별(full/reduced2/4/8) 디코드 → 각도. 반환: {mode: [(ok, angle, vis), ...]}"""
    out = {}
    for name, flag in REDUCED_MODES:
        det = PoseDetector()
        rows = []
        try:
            for jb in native_jpeg_by_frame:
                arr = np.frombuffer(jb, dtype=np.uint8)
                bgr = cv2.imdecode(arr, flag)
                rows.append(detect_angle(det, bgr))
        finally:
            det.close()
        out[name] = rows
    return out


def run_resolution_scale(raw_frames):
    """스케일별(native/4.4/9.9/17.6/39.7) 리사이즈 → JPEG → 디코드 → 각도."""
    out = {}
    for name, scale in RES_SCALES:
        det = PoseDetector()
        rows = []
        try:
            for bgr in raw_frames:
                h, w = bgr.shape[:2]
                tw, th = max(1, round(w * scale)), max(1, round(h * scale))
                small = cv2.resize(bgr, (tw, th), interpolation=cv2.INTER_AREA)
                jb = encode(small)
                arr = np.frombuffer(jb, dtype=np.uint8)
                dec = cv2.imdecode(arr, cv2.IMREAD_COLOR)
                rows.append(detect_angle(det, dec))
        finally:
            det.close()
        out[name] = rows
    return out


def summarize(name_list, all_rows, base_key):
    base_rows = all_rows[base_key]
    summary = {}
    for name, _ in name_list:
        diffs, misses, n = [], 0, 0
        for i in range(len(base_rows)):
            base_ok, base_angle, _ = base_rows[i]
            if not base_ok:
                continue
            n += 1
            ok, angle, _ = all_rows[name][i]
            if not ok:
                misses += 1
                continue
            diffs.append(abs(angle - base_angle))
        summary[name] = {
            "n_anchored": n,
            "misses": misses,
            "mean_abs_diff_deg": round(statistics.mean(diffs), 2) if diffs else None,
            "max_abs_diff_deg": round(max(diffs), 2) if diffs else None,
        }
    return summary


def main():
    videos = find_videos()
    print(f"영상 {len(videos)}개 발견\n")

    all_reduced = {name: [] for name, _ in REDUCED_MODES}
    all_scale = {name: [] for name, _ in RES_SCALES}
    per_video = {}

    for path in videos:
        base = os.path.basename(path)
        raw_frames, w, h = sample_frames(path)
        if not raw_frames:
            print(f"[{base}] 열기 실패, 건너뜀")
            continue
        native_jpeg = [encode(f) for f in raw_frames]

        reduced_rows = run_reduced_decode(native_jpeg)
        scale_rows = run_resolution_scale(raw_frames)

        for name, _ in REDUCED_MODES:
            all_reduced[name].extend(reduced_rows[name])
        for name, _ in RES_SCALES:
            all_scale[name].extend(scale_rows[name])

        n_anchor = sum(1 for ok, _, _ in reduced_rows["full"] if ok)
        per_video[base] = {"w": w, "h": h, "n_sampled": len(raw_frames), "n_anchored": n_anchor}
        print(f"[{base:45s}] {w}x{h}  표본 {len(raw_frames)}  앵커(native full 검출) {n_anchor}")

    print(f"\n총 앵커 프레임(9개 영상 합산, native full 검출 성공): "
          f"{sum(1 for ok, _, _ in all_reduced['full'] if ok)}/{len(all_reduced['full'])}\n")

    print("== 레버②(축소 디코드) — full(네이티브 해상도 무보정 디코드) 대비 무릎 각도차 ==")
    reduced_summary = summarize(REDUCED_MODES, all_reduced, "full")
    for name, _ in REDUCED_MODES:
        s = reduced_summary[name]
        if s["mean_abs_diff_deg"] is None:
            print(f"  {name:12s} 비교 가능한 프레임 없음  검출실패={s['misses']}/{s['n_anchored']}")
        else:
            print(f"  {name:12s} 평균={s['mean_abs_diff_deg']:5.2f}deg  최대={s['max_abs_diff_deg']:5.2f}deg  "
                  f"검출실패={s['misses']}/{s['n_anchored']}")

    print("\n== 레버①(상대 해상도 축소, INTER_AREA 리샘플) — native(1x) 대비 무릎 각도차 ==")
    scale_summary = summarize(RES_SCALES, all_scale, "native(1x)")
    for name, _ in RES_SCALES:
        s = scale_summary[name]
        if s["mean_abs_diff_deg"] is None:
            print(f"  {name:22s} 비교 가능한 프레임 없음  검출실패={s['misses']}/{s['n_anchored']}")
        else:
            print(f"  {name:22s} 평균={s['mean_abs_diff_deg']:5.2f}deg  최대={s['max_abs_diff_deg']:5.2f}deg  "
                  f"검출실패={s['misses']}/{s['n_anchored']}")

    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results", "pose-real-footage-accuracy-2026-09-03")
    os.makedirs(out_dir, exist_ok=True)
    result = {
        "n_samples_per_video": N_SAMPLES,
        "jpeg_quality": JPEG_QUALITY,
        "per_video": per_video,
        "reduced_decode_summary": reduced_summary,
        "resolution_scale_summary": scale_summary,
    }
    out_path = os.path.join(out_dir, "result.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(f"\n저장: {out_path}")


if __name__ == "__main__":
    main()
