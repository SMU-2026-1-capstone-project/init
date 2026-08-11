"""#164 충돌 빈도 — FastAPI 스레드풀이 «실제로» 세션을 섞는가

[#164](https://github.com/Shadowfit/init/issues/164) 가 스스로 «⚠️ 확정하지 말 것» 으로 남긴
캐비엇 셋 중 **둘을 닫는 것**이 목표다:

  - "스레드풀 배정이 실제로 세션을 섞는지 확인하지 않았다"        ← [1] 충돌률
  - "45% 는 «매 프레임 사람을 바꾼» 최악의 경우다"                ← [2] 실부하에서의 검출률

세 번째(«합성 이미지로 쟀다»)는 여기서도 안 닫힌다. 실제 카메라 프레임이 필요하다.

── 재는 것 ─────────────────────────────────────────────────────────────────────
  [0] 프레임당 비용 — 아래 «예상 in-flight» 검산의 재료
  [1] 충돌률       — 각 호출에서 «이 스레드가 직전에 본 세션 ≠ 지금 세션» 비율 (공유 팔 전용)
  [2] 검출률       — 충돌이 «실제로» 프레임을 버리는가. 이게 진짜 피해다
  [3] 대조군       — 세션 전용 검출기(= #164 를 고친 상태)와의 차이

── 🔴 초판이 틀렸던 것 (2026-08-11, 폐기) ──────────────────────────────────────
  초판은 `person_params()` 로 사람을 «고르게 벌려» 생성했는데, `scale ≳ 0.92` 조합은
  인체가 프레임 밖으로 잘려 **단독으로도 0/20** 이었다. 그래서 «손실» 의 상당 부분이
  충돌이 아니라 픽스처였고, 대조군 검출률이 47~72% 로 나왔다(전용 검출기면 100% 여야 한다).

  measure_ai_concurrency.py 가 `verify_standalone()` 로 먼저 단언하는 이유가 이것이다.
  그 스크립트 주석: *"조용히 다른 경로를 재는 것이 오늘만 세 번 나왔다(#139·#140·#153)."*
  → **아래 PERSONS 는 격자 탐색으로 «단독 100%» 를 확인한 조합이고, 실행 때 다시 단언한다.**

── 왜 HTTP 를 안 태우나 ────────────────────────────────────────────────────────
  재려는 것은 «스레드풀의 배정 패턴» 이지 HTTP 가 아니다. Starlette 이 `def` 핸들러를 돌리는
  바로 그 경로(`run_in_threadpool` → anyio 기본 스레드 리미터)를 직접 쓴다.

── ⚠️ 이 값은 CPU 속도에 의존한다 ──────────────────────────────────────────────
  느린 CPU → 요청이 오래 물림 → in-flight↑ → 더 많은 스레드가 활성 → 배정이 더 섞인다.
  «코어 수와 무관» 이 **아니다.** 그래서 «예상 in-flight = 달성fps × 프레임당비용» 을 같이 찍어
  스레드 수가 말이 되는지 검산한다.

── 픽스처 ──────────────────────────────────────────────────────────────────────
  `figure()` 는 measure_ai_concurrency.py 에서 **복사**했다. 그 스크립트는 출력이 이미
  커밋·인용된 상태라 건드리지 않는다.
"""

import asyncio
import os
import statistics
import sys
import threading
import time
from collections import defaultdict

import cv2
import numpy as np

# 컨테이너면 /app, 로컬이면 저장소의 ai-server/ — 둘 다에서 돈다
for _root in ("/app", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ai-server")):
    if os.path.isdir(os.path.join(_root, "app")):
        sys.path.insert(0, _root)
        break

from starlette.concurrency import run_in_threadpool  # noqa: E402

from app.core.mediapipe_detector import PoseDetector, get_detector  # noqa: E402
from app.config import settings  # noqa: E402


# ── 픽스처 (measure_ai_concurrency.py 복사) ────────────────────────────────────
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
PERSONS = [
    dict(x_shift=-0.30, scale=0.58), dict(x_shift=+0.32, scale=0.90),
    dict(x_shift=-0.06, scale=0.90), dict(x_shift=+0.26, scale=0.58),
    dict(x_shift=-0.14, scale=0.74), dict(x_shift=+0.18, scale=0.82),
    dict(x_shift=+0.02, scale=0.66), dict(x_shift=+0.10, scale=0.90),
]


def frames_of(i, n=4):
    return [figure(squat=(j % n) / (n - 1.0), **PERSONS[i]) for j in range(n)]


def verify_standalone():
    """전제: 각 «사람» 이 **전용 검출기·단독** 으로 100% 검출되는가.

    통과 못 하면 중단한다. 이게 통과해야 뒤의 검출 실패를 «섞인 탓» 으로 귀속할 수 있다.
    """
    print("[전제 확인] 각 «사람» 을 전용 검출기로 단독 실행")
    bad = []
    for i in range(len(PERSONS)):
        d = PoseDetector()
        fr = frames_of(i) * 3
        for f in fr:
            d.detect(f)                                   # 워밍업
        ok = sum(1 for f in fr if d.detect(f))
        d.close()
        print(f"   사람{i} x={PERSONS[i]['x_shift']:+.2f} s={PERSONS[i]['scale']:.2f}"
              f" → {ok}/{len(fr)} {'O' if ok == len(fr) else 'X'}")
        if ok < len(fr):
            bad.append(i)
    if bad:
        raise SystemExit(
            f"🔴 중단: 사람 {bad} 이 단독으로도 검출 안 된다. "
            f"이 상태로 재면 «픽스처 실패» 를 «충돌 손실» 로 보고하게 된다(초판이 그랬다)."
        )
    print()


def frame_cost_ms():
    """[0] 프레임당 비용 — in-flight 검산의 재료."""
    d = PoseDetector()
    fr = frames_of(0) * 5
    for f in fr:
        d.detect(f)
    t = []
    for f in fr:
        t0 = time.perf_counter()
        d.detect(f)
        t.append((time.perf_counter() - t0) * 1000)
    d.close()
    return statistics.mean(t)


# ── 관측 ───────────────────────────────────────────────────────────────────────
class Recorder:
    def __init__(self):
        self.lock = threading.Lock()
        self.last_of_thread = {}
        self.calls = self.collisions = self.detected = 0
        self.per_session = defaultdict(lambda: [0, 0])
        self.threads = set()

    def record(self, sid, ok):
        # 🔴 `.name` 이 아니라 `get_ident()` 다 — anyio 워커 스레드는 **이름이 전부 같아서**
        #    (`AnyIO worker thread`) 이름으로 세면 여러 스레드가 하나로 뭉개지고 충돌률이
        #    과대측정된다. 2판이 여기서 걸렸다: «예상 in-flight 2.84 인데 스레드 1개» 라는
        #    물리적으로 불가능한 값이 나왔고, 그 검산이 버그를 잡았다.
        tname = threading.get_ident()
        with self.lock:
            prev = self.last_of_thread.get(tname)
            self.last_of_thread[tname] = sid
            self.threads.add(tname)
            self.calls += 1
            if prev is not None and prev != sid:
                self.collisions += 1
            self.detected += 1 if ok else 0
            p = self.per_session[sid]
            p[0] += 1
            p[1] += 1 if ok else 0


async def run_arm(n_sessions, n_frames, fps, shared):
    """shared=True → 현행(스레드 로컬 검출기) · False → 대조군(세션 전용)."""
    rec = Recorder()
    interval = (1.0 / fps) if fps else 0.0
    owned = None if shared else {i: PoseDetector() for i in range(n_sessions)}

    def work(sid, frame):
        det = get_detector() if shared else owned[sid]
        rec.record(sid, bool(det.detect(frame)))

    async def session(sid):
        fr = frames_of(sid)
        for j in range(n_frames):
            t0 = time.perf_counter()
            await run_in_threadpool(work, sid, fr[j % len(fr)])
            lag = interval - (time.perf_counter() - t0)
            if lag > 0:
                await asyncio.sleep(lag)        # 못 따라가면 sleep 이 사라진다 = 포화

    t0 = time.perf_counter()
    await asyncio.gather(*(session(i) for i in range(n_sessions)))
    wall = time.perf_counter() - t0
    if owned:
        for d in owned.values():
            d.close()
    return rec, wall


async def main():
    N_FRAMES = 40
    print("=== #164 충돌 빈도 — 스레드풀이 실제로 세션을 섞는가 ===")
    print(f"    model_complexity={settings.POSE_MODEL_COMPLEXITY} · 세션당 {N_FRAMES}프레임 · "
          f"Starlette run_in_threadpool · 논리 CPU {os.cpu_count()}")
    print("    ⚠️ 충돌률은 CPU 속도에 의존한다(느릴수록 in-flight↑ → 더 섞임).")
    print()

    verify_standalone()
    cost = frame_cost_ms()
    print(f"[0] 프레임당 비용(단독·전용 검출기): {cost:.1f} ms")
    print()

    await run_arm(2, 4, 0, True)                # 워밍업

    print(f"| {'세션':<5} | {'목표fps':>7} | {'검출기':<10} | {'호출':>5} | {'스레드':>6} "
          f"| {'예상inflight':>12} | {'충돌률':>7} | {'검출률':>7} | {'달성fps':>8} |")
    print("|" + "|".join("-" * w for w in (7, 9, 12, 7, 8, 14, 9, 9, 10)) + "|")

    out = {}
    # fps=3 은 실사용(#143 상한), fps=0 은 «상한까지 밀기» — 스레드가 실제로 벌어지는 조건
    for fps in (3, 0):
        for n in (2, 4, 8):
            for shared in (True, False):
                rec, wall = await run_arm(n, N_FRAMES, fps, shared)
                ach = rec.calls / wall
                coll = rec.collisions / max(1, rec.calls - len(rec.threads))
                det = rec.detected / max(1, rec.calls)
                out[(fps, n, shared)] = (coll, det, len(rec.threads), ach, rec)
                print(f"| {n:<5} | {(fps or '최대'):>7} | {'스레드로컬' if shared else '세션전용':<10} "
                      f"| {rec.calls:>5} | {len(rec.threads):>6} | {ach * cost / 1000:>12.2f} "
                      f"| {(f'{coll:.1%}' if shared else 'N/A'):>7} | {det:>6.1%} | {ach:>8.1f} |")
            print("|" + "|".join("-" * w for w in (7, 9, 12, 7, 8, 14, 9, 9, 10)) + "|")

    print()
    print("## 판정")
    for fps in (3, 0):
        for n in (2, 4, 8):
            c, d_sh, th, _, _ = out[(fps, n, True)]
            _, d_ow, _, _, _ = out[(fps, n, False)]
            print(f"  {(str(fps)+'fps') if fps else '최대  ':<7} {n}세션 · 스레드 {th} · 충돌률 {c:6.1%}"
                  f" · 검출률 {d_sh:6.1%} vs 전용 {d_ow:6.1%}  ⇒ 손실 {d_ow - d_sh:+.1%}p")
    print()
    print("  · «예상inflight» 와 «스레드» 가 어긋나면 배정 해석을 의심할 것 (초판이 여기서 걸렸다).")
    print("  · 충돌률이 높은데 손실이 0 이면 «섞이지만 트래킹이 버틴다» 는 뜻이다 — 그것도 결과다.")
    print("  ⚠️ 합성 인체다. 실제 카메라 프레임에서의 유지율은 여전히 미검증(#164 캐비엇 3).")


if __name__ == "__main__":
    asyncio.run(main())
