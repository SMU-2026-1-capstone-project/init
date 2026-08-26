"""AI 서버 동시 세션 한계 — 프레임당 추론 비용과 동시성 스케일

배경: load-test-strategy.md §1 이 "부하는 AI 에 걸린다" 를 핵심 전제로 두고,
ai-load-budget.md 가 "프레임당 20~50ms CPU, 4코어 ≈ 동시 6~8세션" 을 적어뒀다.
그런데 그 6~8 은 **추정이지 실측이 아니다.** DAU 1,000 이 만드는 피크 동시 ~67세션과
10배 차이라 이 프로젝트에서 가장 큰 용량 갭인데, 근거가 추정 한 줄이다.

── 이 환경에서 무엇을 잴 수 있고 없는가 ────────────────────────────────────────
  이 장비는 i3-6100(2물리코어 + HT)이라 «동시 N세션» 같은 절대값은 못 낸다.
  대신 환경에 안 묶이는 양을 재서 유도한다:

      동시 세션 한계 = 코어 수 ÷ (프레임당 CPU × fps)

  프레임당 CPU 는 고정 크기 CNN 의 연산량이라 코어 수와 무관하다 — 여기서 재도 옮겨 쓸 수 있다.

── 왜 합성 이미지인가 ──────────────────────────────────────────────────────────
  ai-server 어디에도 사람이 찍힌 픽스처가 없다. 빈 이미지를 넣으면 MediaPipe 가 사람을
  못 찾고 pose.py:64 에서 조기 반환해, 재는 것이 «추론» 이 아니라 «탐지 실패» 가 된다.
  그래서 사람 비율로 그린 합성 인체를 쓴다. 사전 프로브에서 6개 변형 전부 검출됐고
  무릎 visibility 0.91~0.94 로 임계(0.5)에서 충분히 떨어져 있음을 확인했다.

  ⚠️ 그래도 매 측정 전에 검출 성공을 **단언**한다. 실패하면 중단한다 — 조용히 다른 경로를
  재는 것이 오늘만 세 번 나왔다(#139·#140·#153).

  합성이어도 프레임당 비용은 신뢰할 만하다: 랜드마크 모델은 고정 해상도 CNN 이라
  연산량이 사진 «내용» 과 무관하다. 내용에 걸리는 것은 검출 성공 여부뿐이고, 그건 단언한다.

── 재는 것 ─────────────────────────────────────────────────────────────────────
  [A] 단일 스레드 · 같은 사람 연속      → 트래킹이 유지될 때의 프레임당 비용 (바닥값)
  [B] 단일 스레드 · 다른 사람 번갈아    → 트래킹이 깨질 때의 비용
  [C] 동시 스레드 1·2·4·8              → 처리량 포화 지점

  [B] 가 왜 필요한가: mediapipe_detector.py:107 이 검출기를 **스레드 로컬**로 둔다.
  그런데 FastAPI 는 def 핸들러를 스레드풀에서 돌리므로(pose.py:52), 트래킹 상태가
  «세션별» 이 아니라 «스레드별» 이다. 동시 세션이 여러 개면 같은 스레드에 서로 다른
  사람의 프레임이 번갈아 들어오고, static_image_mode=False 인 BlazePose 는 직전 프레임
  기준으로 트래킹하므로 매번 깨져 무거운 탐지기가 반복 실행될 수 있다.
  사실이면 «단일 세션에서 재고 코어 수로 나누는» 계산이 실제보다 낙관적이 된다.
"""

import statistics
import sys
import threading
import time

import cv2
import numpy as np

sys.path.insert(0, "/app")
from app.core.mediapipe_detector import get_detector  # noqa: E402
from app.config import settings  # noqa: E402


# ── 합성 프레임 ────────────────────────────────────────────────────────────────
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


# «다른 사람» — 위치·크기를 벌려 직전 프레임 기준 트래킹이 깨지게 한다.
# 자세(squat)는 0~0.5 로 좁게 둔다: 재려는 것은 «자세 난이도» 가 아니라 «트래킹 깨짐» 이라,
# 자세까지 어렵게 만들면 두 효과가 섞인다.
#
# ⚠️ 이 조합은 임의로 고른 것이 아니다. 1차 시도에서 4개 중 3개가 **단독으로도 검출 0/10** 이라
# [B] 의 실패를 트래킹 탓으로 오독할 뻔했다. x_shift×scale 격자를 쓸어 검출 지도를 만든 뒤
# (2026-08-09, x_shift=-0.30 은 전 구간 실패) 검출되는 칸에서만 골랐다.
# 아래 verify_standalone() 이 매 실행마다 이 전제를 다시 확인한다 — 확인 없이는
# [B] 의 실패를 «번갈아 넣은 탓» 이라고 말할 수 없다.
PERSONS = [
    dict(x_shift=-0.15, scale=1.00),
    dict(x_shift=+0.30, scale=0.75),
    dict(x_shift=+0.00, scale=0.85),
    dict(x_shift=+0.15, scale=0.65),
]


def detect_rate(det, frames):
    """(검출 성공 수, 무릎 visibility 평균)"""
    ok, vis = 0, []
    for f in frames:
        lms = det.detect(f)
        if lms:
            ok += 1
            vis.append(statistics.mean(lms[i].visibility for i in (25, 26)))
    return ok, (statistics.mean(vis) if vis else 0.0)


def assert_detectable(frames, label):
    """[A] 용 엄격 단언 — 같은 사람 연속에서는 100% 가 나와야 한다.

    콜드 상태의 첫 프레임은 트래킹이 없어 실패할 수 있으므로 워밍업 한 바퀴 뒤에 단언한다.
    (그 콜드 실패 자체는 별도 관측 항목으로 기록한다 — §첫 프레임)
    """
    det = get_detector()
    detect_rate(det, frames)                      # 워밍업
    ok, vis = detect_rate(det, frames)
    if ok < len(frames):
        raise SystemExit(
            f"🔴 중단: [{label}] {len(frames)}프레임 중 {len(frames)-ok}개 검출 실패. "
            f"이 상태로 재면 «탐지 실패» 비용을 «추론» 으로 보고하게 된다."
        )
    return vis


def verify_standalone():
    """[B] 의 전제 확인 — 각 «사람» 이 **단독으로는** 검출되는가.

    이게 통과해야 [B] 의 검출 실패를 «번갈아 넣은 탓» 으로 귀속할 수 있다.
    통과 못 하면 픽스처 탓이므로 중단한다.
    """
    from app.core.mediapipe_detector import PoseDetector
    print("[전제 확인] 각 «사람» 을 전용 검출기로 단독 실행")
    bad = []
    for i, p in enumerate(PERSONS):
        d = PoseDetector()
        frames = [figure(squat=(j % 2) * 0.5, **p) for j in range(20)]
        detect_rate(d, frames)                    # 워밍업
        ok, vis = detect_rate(d, frames)
        d.close()
        mark = "O" if ok == len(frames) else "X"
        print(f"   사람{i} x_shift={p['x_shift']:+.2f} scale={p['scale']:.2f}"
              f" → {ok}/{len(frames)} {mark}  vis={vis:.3f}")
        if ok < len(frames):
            bad.append(i)
    if bad:
        raise SystemExit(
            f"🔴 중단: 사람 {bad} 이 단독으로도 검출 안 된다. "
            f"이 상태에서 [B] 가 실패하면 트래킹 탓인지 픽스처 탓인지 가를 수 없다."
        )


def timed(frames, repeats):
    """프레임당 wall time(ms) 목록. 단일 스레드·무경합이면 ≈ CPU time."""
    det = get_detector()
    out = []
    for _ in range(repeats):
        for f in frames:
            t0 = time.perf_counter()
            det.detect(f)
            out.append((time.perf_counter() - t0) * 1000)
    return out


def stat(ms):
    s = sorted(ms)
    return dict(n=len(s), mean=statistics.mean(s), p50=s[len(s) // 2],
                p95=s[int(len(s) * 0.95)], mx=s[-1])


def line(label, st, extra=""):
    print(f"| {label:<26} | {st['n']:>5} | {st['mean']:>7.1f} | {st['p50']:>7.1f} "
          f"| {st['p95']:>7.1f} | {st['mx']:>7.1f} | {extra}")


HDR = f"| {'구성':<26} | {'n':>5} | {'평균ms':>7} | {'p50':>7} | {'p95':>7} | {'최대':>7} |"
SEP = "|" + "-" * 28 + "|" + "-" * 7 + "|" + "-" * 9 + "|" + "-" * 9 + "|" + "-" * 9 + "|" + "-" * 9 + "|"

import os  # noqa: E402


def host():
    """이 측정이 «어느 장비에서» 났는지 검출해서 결과에 박는다.

    초판은 `물리 2코어(i3-6100)+HT` 를 **문자열로 하드코딩**했다. 다른 장비에서 돌리면
    거짓 사양이 결과에 찍히고, 그 결과가 나중에 인용된다. 그래서 검출한다.

    cgroup 상한을 같이 보는 이유: `os.cpu_count()` 는 **호스트 논리 CPU 를 보지
    컨테이너 캡을 안 본다.** `docker --cpus` 가 걸려 있으면 조용히 틀린 코어 수로 나눈다.
    (같은 종류의 함정을 `--memory` 캡에서 이미 겪었다.)

    반환: (cpu 모델, 논리 코어, 물리 코어 or None, cgroup 상한 or None)
    """
    model, logical, physical = "unknown", os.cpu_count() or 1, None
    try:
        with open("/proc/cpuinfo") as f:
            lines = f.read().splitlines()
        for ln in lines:
            if ln.startswith("model name"):
                model = ln.split(":", 1)[1].strip()
                break
        # (physical id, core id) 쌍의 개수 = 물리 코어. HT 는 같은 쌍을 공유한다.
        pairs, cur = set(), {}
        for ln in lines:
            if ":" in ln:
                k, v = (x.strip() for x in ln.split(":", 1))
                if k in ("physical id", "core id"):
                    cur[k] = v
            elif not ln.strip():
                if len(cur) == 2:
                    pairs.add((cur["physical id"], cur["core id"]))
                cur = {}
        if len(cur) == 2:
            pairs.add((cur["physical id"], cur["core id"]))
        if pairs:
            physical = len(pairs)
    except OSError:
        pass

    quota = None
    try:                                              # cgroup v2
        with open("/sys/fs/cgroup/cpu.max") as f:
            q, p = f.read().split()
        if q != "max":
            quota = int(q) / int(p)
    except (OSError, ValueError):
        try:                                          # cgroup v1
            with open("/sys/fs/cgroup/cpu/cpu.cfs_quota_us") as f:
                q = int(f.read())
            with open("/sys/fs/cgroup/cpu/cpu.cfs_period_us") as f:
                p = int(f.read())
            if q > 0:
                quota = q / p
        except (OSError, ValueError):
            pass
    return model, logical, physical, quota



def main():
    CPU, LOGICAL, PHYSICAL, QUOTA = host()
    EFF = int(QUOTA) if QUOTA else LOGICAL            # 실제로 쓸 수 있는 논리 CPU

    print("=== AI 서버 프레임당 추론 비용 · 동시성 스케일 ===")
    print(f"    model_complexity={settings.POSE_MODEL_COMPLEXITY}")
    print(f"    CPU   : {CPU}")
    print(f"    코어  : 논리 {LOGICAL} · 물리 {PHYSICAL if PHYSICAL else '검출 실패'}"
          + (f" · cgroup 상한 {QUOTA:g}" if QUOTA else " · cgroup 상한 없음"))
    if QUOTA:
        print("    🔴 cgroup CPU 상한이 걸려 있다. «코어당 세션» 을 낼 거면 상한을 풀고 다시 재라.")
    if not PHYSICAL:
        print("    🔴 물리 코어를 못 셌다(비 x86?). «코어당 세션» 은 논리 코어 기준이 되어 과대평가된다.")
    print("    ⚠️ ms 는 이 CPU 에 묶인다. 옮겨 쓸 수 있는 것은 포화 곡선 «모양» 과 «물리 코어당 세션» 이다.")
    print()

    # ── 사전 단언 ──────────────────────────────────────────────────────────────────
    same = squat_cycle(20)
    v = assert_detectable(same, "같은 사람 연속")
    print(f"[검출 단언] 같은 사람 20프레임 전부 검출 · 무릎 visibility 평균 {v:.3f}")
    verify_standalone()
    alt = [figure(squat=(i % 2) * 0.5, **PERSONS[i % len(PERSONS)]) for i in range(20)]
    print()

    # warmup — 첫 호출은 그래프 초기화가 섞인다
    timed(same, 2)

    # ── [A] 단일 스레드 · 같은 사람 연속 ───────────────────────────────────────────
    print("## [A] 단일 스레드 — 트래킹이 유지될 때 (바닥값)")
    print(HDR); print(SEP)
    a = stat(timed(same, 8))
    line("같은 사람 연속", a)
    print()

    # ── [B] 단일 스레드 · 다른 사람 번갈아 ─────────────────────────────────────────
    print("## [B] 단일 스레드 — 트래킹이 깨질 때")
    print("     같은 검출기에 서로 다른 위치·크기의 인체를 번갈아 넣는다.")
    print("     FastAPI 스레드풀 + 스레드 로컬 검출기 조합에서 동시 세션이 만드는 상황이다.")
    print("     각 «사람» 은 위 전제 확인에서 단독 검출 100% 임이 확인됐다.")
    _d = get_detector()
    _ok, _vis = detect_rate(_d, alt)
    print(f"     ⇒ 번갈아 넣었을 때 검출률: {_ok}/{len(alt)}"
          f"{'  🔴 단독 100% 인데 여기서 떨어진다 = 번갈아 넣은 탓' if _ok < len(alt) else ''}")
    print(HDR); print(SEP)
    b = stat(timed(alt, 8))
    line("다른 사람 번갈아", b)
    print()
    ratio = b["mean"] / a["mean"]
    print(f"  ⇒ 트래킹 깨짐 비용: {ratio:.2f}x  (B/A)")
    print()

    # ── [C] 동시 스레드 스케일 ─────────────────────────────────────────────────────
    print("## [C] 동시 스레드 — 처리량 포화")
    print("     스레드마다 자기 검출기(스레드 로컬)를 갖고 같은 사람 연속 프레임을 돈다.")
    print("     = 세션이 스레드에 1:1로 붙는 «가장 유리한» 배치다. 실제 스레드풀은 이보다 나쁠 수 있다.")

    # 스윕을 장비에 맞춘다 — 포화점 «너머» 까지 가야 곡선이 닫힌다.
    # 초판은 (1,2,4,8) 고정이었다. 물리 8코어 박스면 포화가 8 밖이라 곡선이 안 닫힌다.
    SWEEP = sorted({1, 2, 4, 8, EFF, EFF * 2} | ({PHYSICAL} if PHYSICAL else set()))
    REPEATS = 3

    print(f"     스윕 {SWEEP} · {REPEATS}판 + 버림판 1 · 판마다 순서를 뒤집는다")
    print("     (오름차순 1판씩이면 «스레드 수» 와 «판 순서»(터보·써멀 드리프트)가 안 갈린다)")
    print()


    def throughput(nth):
        """nth 스레드 동시 실행 → (총 처리량 fps, 스레드당 평균 ms)."""
        per_thread = []
        lock = threading.Lock()
        frames = squat_cycle(20)
        REPS = 6

        def work():
            get_detector()          # 스레드 로컬 검출기 생성
            timed(frames, 1)        # 워밍업
            ms = timed(frames, REPS)
            with lock:
                per_thread.append(statistics.mean(ms))

        ths = [threading.Thread(target=work) for _ in range(nth)]
        t0 = time.perf_counter()
        for t in ths:
            t.start()
        for t in ths:
            t.join()
        wall = time.perf_counter() - t0
        return nth * len(frames) * REPS / wall, statistics.mean(per_thread)


    for _n in SWEEP:                # 버림판 — 결과에 넣지 않는다
        throughput(_n)

    runs = {n: [] for n in SWEEP}
    for r in range(REPEATS):
        for n in (SWEEP if r % 2 == 0 else list(reversed(SWEEP))):
            runs[n].append(throughput(n))

    print(f"| {'스레드':<8} | {'총 처리량(fps)':>14} | {'판별 최소~최대':>17} "
          f"| {'스레드당 평균ms':>16} | {'1스레드 대비':>12} |")
    print("|" + "-" * 10 + "|" + "-" * 16 + "|" + "-" * 19 + "|" + "-" * 18 + "|" + "-" * 14 + "|")

    med = {}
    for n in SWEEP:
        fpss = sorted(x[0] for x in runs[n])
        med[n] = fpss[len(fpss) // 2]                 # 판 간 중앙값
        print(f"| {n:<8} | {med[n]:>14.1f} | {fpss[0]:>7.1f}~{fpss[-1]:<9.1f} "
              f"| {statistics.mean(x[1] for x in runs[n]):>16.1f} "
              f"| {med[n] / med[SWEEP[0]]:>11.2f}x |")

    peak_n = max(med, key=med.get)
    peak = med[peak_n]

    print()
    print("## 유도")
    print(f"  프레임당 비용(트래킹 유지) : {a['mean']:.1f} ms")
    print(f"  프레임당 비용(트래킹 깨짐) : {b['mean']:.1f} ms")
    print(f"  측정된 포화 처리량         : {peak:.1f} fps (스레드 {peak_n})")
    print()
    print("  ⓐ 측정 기반 — 이 장비에서 «실제로 나온» 처리량을 클라 fps 로 나눈다")
    for fps_c in (3, 10):
        ses = peak / fps_c
        print(f"     클라 {fps_c:>2}fps → 동시 {ses:6.1f} 세션"
              + (f"   ⇒ 물리 코어당 {ses / PHYSICAL:.2f} 세션" if PHYSICAL else ""))

    if PHYSICAL:
        per_core = peak / 3 / PHYSICAL                # 실클라 3fps 기준(#143 상한)
        print()
        print("  ⓑ 다른 사양으로 옮길 때 — ⓐ 의 «코어당» 에 목표 물리 코어 수를 곱한다")
        for cores in (4, 8, 16, 32):
            print(f"     물리 {cores:>2}코어 · 클라 3fps → 동시 {per_core * cores:6.1f} 세션")
        # 67 = DAU 1,000 가정의 피크 동시 세션 (ai-load-budget.md / load-test-strategy.md §1)
        print(f"\n  ⇒ 가정한 피크 동시 67세션을 채우려면 물리 {67 / per_core:.1f}코어")
    print()
    print("  ⚠️ 위 유도는 «코어를 100% 추론에 쓸 수 있다» 는 상한이다. 실제로는 디코딩·HTTP·GIL,")
    print("     그리고 같은 박스에 사는 다른 컨테이너가 이 값을 깎는다. 하한이 아니라 상한으로 읽을 것.")
    print("  ⚠️ 코어 선형 가정도 상한이다 — 메모리 대역·L3 경합은 코어가 늘수록 나빠진다.")
    print("     그리고 [C] 가 보여주는 포화가 더 낮은 값을 만든다. 상한으로만 읽을 것.")


if __name__ == "__main__":
    main()
