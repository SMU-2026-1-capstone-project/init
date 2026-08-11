"""M2 — `PoseDetector` 1개가 실제로 메모리를 얼마나 쓰는가

[`docs/decisions/session-detector-ownership.md §8`](../docs/decisions/session-detector-ownership.md)
의 조건 4번(«세션 수 상한»)에 **근거가 비어 있다.** ㄴ 안은 검출기 개수 = 세션 수이므로,
«세션 몇 개까지 받을 것인가» 가 곧 메모리 문제다. 그런데 인스턴스 1개의 크기를 잰 적이 없다.

박스에 컨테이너가 7개고 OOM 전적이 있다([[feedback_ec2_container_memory_cap]]) — 추측으로
상한을 박으면 [[feedback_no_arbitrary_threshold_values]] 위반이고, 그 숫자가 또 근거 없는
기준값으로 남는다.

── 재는 것 ─────────────────────────────────────────────────────────────────────
  [1] 생성 직후 증가분      — `mp_pose.Pose(...)` 가 그래프를 세울 때
  [2] 첫 추론 후 증가분     — MediaPipe 는 지연 할당한다. 여기서 더 붙는다
  [3] `close()` 후 회수분   — 🔴 **안 돌아오면 «정리» 만으로는 부족하다는 뜻**이다
                              (그러면 세션 회전이 잦을수록 누적된다)

── 왜 psutil 을 안 쓰나 ────────────────────────────────────────────────────────
  venv 에 없다. 설치하면 requirements.txt 와 어긋나 드리프트가 생긴다.
  RSS 는 OS 에서 직접 읽으면 되므로 의존을 안 늘린다(Windows: psapi · Linux: /proc/self/statm).

⚠️ RSS 는 **할당자가 OS 에 돌려줬는지** 까지 반영한다. 파이썬/glibc 가 free 후에도 안 돌려주면
   [3] 이 0 으로 보일 수 있다 — «누수» 와 «반환 안 함» 은 다르다. 값이 0 이면 그 구분은 미해결로 남긴다.
"""

import ctypes
import os
import sys

for _root in ("/app", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ai-server")):
    if os.path.isdir(os.path.join(_root, "app")):
        sys.path.insert(0, _root)
        break

import numpy as np  # noqa: E402
import cv2  # noqa: E402

from app.core.mediapipe_detector import PoseDetector  # noqa: E402
from app.config import settings  # noqa: E402


def rss_mb():
    """현재 프로세스 RSS(MB). psutil 없이.

    🔴 초판이 여기서 틀렸다: `GetCurrentProcess()` 의 `restype` 을 안 잡아서 ctypes 가 반환을
       int 로 다뤘고, 64비트에서 핸들이 잘려 호출이 **조용히 실패**했다(반환 0, 구조체는 0인 채).
       그래서 모든 RSS 가 0.0 MB 로 찍혔다. **반환값을 검사하지 않은 것이 진짜 결함**이다 —
       지금은 실패하면 중단한다.
    """
    if sys.platform == "win32":
        class PMC(ctypes.Structure):
            _fields_ = [("cb", ctypes.c_uint32), ("PageFaultCount", ctypes.c_uint32),
                        ("PeakWorkingSetSize", ctypes.c_size_t), ("WorkingSetSize", ctypes.c_size_t),
                        ("QuotaPeakPagedPoolUsage", ctypes.c_size_t), ("QuotaPagedPoolUsage", ctypes.c_size_t),
                        ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t), ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                        ("PagefileUsage", ctypes.c_size_t), ("PeakPagefileUsage", ctypes.c_size_t)]
        k32 = ctypes.windll.kernel32
        k32.GetCurrentProcess.restype = ctypes.c_void_p
        k32.K32GetProcessMemoryInfo.argtypes = [ctypes.c_void_p, ctypes.POINTER(PMC), ctypes.c_uint32]
        c = PMC()
        c.cb = ctypes.sizeof(PMC)
        if not k32.K32GetProcessMemoryInfo(k32.GetCurrentProcess(), ctypes.byref(c), c.cb):
            raise SystemExit("🔴 중단: RSS 조회 실패. 0 을 «측정값» 으로 보고하면 안 된다.")
        return c.WorkingSetSize / 1024 / 1024
    with open("/proc/self/statm") as f:                      # Linux
        return int(f.read().split()[1]) * os.sysconf("SC_PAGE_SIZE") / 1024 / 1024


def figure(h=480, w=640, squat=0.0, x_shift=0.0, scale=1.0,
           skin=(180, 150, 130), bg=(230, 230, 230)):
    img = np.full((h, w, 3), bg, np.uint8)
    cx = int(w // 2 + w * x_shift)
    top = int(h * 0.08)
    H = int(h * 0.87 * scale)
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
        hx = cx + s * int(H * 0.035)
        cv2.line(img, (hx, hip_y), (hx + s * knee_x, knee_y), skin, int(H * 0.065))
        cv2.line(img, (hx + s * knee_x, knee_y), (hx + s * int(knee_x * 0.3), top + H), skin, int(H * 0.055))
    return img


# 격자 탐색으로 «단독 100%» 를 확인한 조합에서, 서로 최대한 멀리 떨어진 8개를 골랐다.
# 멀리 떨어져야 «다른 사람» 이 되고, 그래야 섞였을 때 트래킹이 실제로 깨진다.

# 검증된 조합(단독 100%) — measure_thread_collision.py PERSONS[4] 와 동일
def frame(squat=0.0):
    return figure(squat=squat, x_shift=-0.14, scale=0.74)


N = 12
f = frame()

print("=== M2 — PoseDetector 1개의 실제 메모리 ===")
print(f"    model_complexity={settings.POSE_MODEL_COMPLEXITY} · 인스턴스 {N}개 · RSS 기준")
print()

# 워밍업 — 첫 인스턴스는 모델 파일 로드·라이브러리 초기화가 섞여 «1개당» 이 아니다
_w = PoseDetector()
for _ in range(4):
    _w.detect(f)                                   # 콜드 프레임은 트래킹이 없어 실패할 수 있다
assert _w.detect(f) is not None, "🔴 중단: 픽스처가 검출되지 않는다 — 지연 할당이 안 일어난다"
_w.close()
base = rss_mb()
if base <= 0:
    raise SystemExit("🔴 중단: 기준 RSS 가 0 이다. 리더가 고장났다.")
print(f"[기준] 워밍업 후 RSS = {base:.1f} MB  (모델 로드·라이브러리 초기화 제외한 바닥)")
print()

print(f"| {'개':>3} | {'생성 후 RSS':>11} | {'추론 후 RSS':>11} | {'생성 증가':>9} | {'추론 증가':>9} | {'누적/개':>9} |")
print("|" + "|".join("-" * w for w in (5, 13, 13, 11, 11, 11)) + "|")

dets, prev = [], base
for i in range(1, N + 1):
    d = PoseDetector()
    dets.append(d)
    r_make = rss_mb()
    for _ in range(3):
        ok = d.detect(f)                           # 워밍업 겸 지연 할당 유발
    r_run = rss_mb()
    print(f"| {i:>3} | {r_make:>11.1f} | {r_run:>11.1f} | {r_make - prev:>9.1f} | "
          f"{r_run - r_make:>9.1f} | {(r_run - base) / i:>9.1f} |")
    if ok is None:
        print("   🔴 검출 실패 — 지연 할당이 안 일어났을 수 있다")
    prev = r_run

peak = rss_mb()
per = (peak - base) / N
print()
print(f"[1+2] 인스턴스당 = **{per:.1f} MB**  (총 {peak - base:.1f} MB / {N}개)")

for d in dets:
    d.close()
dets.clear()
import gc  # noqa: E402
gc.collect()
after = rss_mb()
print(f"[3]   close()+gc 후 RSS = {after:.1f} MB → 회수 {peak - after:.1f} MB "
      f"({(peak - after) / max(0.001, peak - base):.0%})")
print()

print("## 세션 상한의 메모리 쪽 근거")
for n in (12, 24, 48, 67):
    print(f"   세션 {n:>3}개 → 검출기 메모리 약 {per * n:>7.0f} MB")
print()
print("  ⚠️ 이건 «검출기만» 이다. 프레임 버퍼·파이썬 힙·다른 컨테이너는 별도다.")
print("  ⚠️ 회수율이 낮으면 «정리» 만으로 부족하다 — 세션 회전이 잦을수록 누적된다.")
print("     단 RSS 는 할당자가 OS 에 안 돌려줘도 안 줄어든다. «누수» 와 «반환 안 함» 은 여기서 안 갈린다.")
