"""M1 — MediaPipe 검출기가 «스레드를 옮겨 다녀도» 되는가

[`docs/decisions/session-detector-ownership.md §3-2`](../docs/decisions/session-detector-ownership.md)
의 급소를 재는 rig. 이 결과에 따라 ㄴ 안(검출기를 세션에 붙이기)이 **살아남거나 탈락한다.**

── 질문 ────────────────────────────────────────────────────────────────────────
  `mediapipe_detector.py:105` 주석은 *"MediaPipe Pose 는 thread-safe 하지 않으므로
  호출 스레드마다 분리"* 라고 적는다. «thread-safe 하지 않다» 는 보통 «동시 호출이 안전하지
  않다» 는 뜻이고, 그렇다면 **순차로만 부르면 스레드가 바뀌어도 괜찮아야** 한다.
  그런데 그것을 확인한 적이 없다. MediaPipe 내부 그래프가 스레드 어피니티를 요구하면
  ㄴ 은 조용히 깨진다.

── 설계: 동시성을 «완전히» 뺀다 ────────────────────────────────────────────────
  호출을 전부 순차로 돌린다(한 번에 하나). 그러면 락도 경합도 없고, **변수는 «스레드가
  바뀌는가» 하나만 남는다.** 동시성을 섞으면 «옮겨서 깨진 것» 과 «겹쳐서 깨진 것» 이 안 갈린다.

  세션 4개의 프레임을 인터리브해서 순차 호출한다: s0f0, s1f0, s2f0, s3f0, s0f1, …
  (이게 실제로 스레드풀이 만드는 «섞임» 패턴이다.)

── 세 팔 ───────────────────────────────────────────────────────────────────────
  [A] 세션 전용 스레드 + 그 스레드의 검출기   = ㄷ 안. **기준선**
  [B] 회전하는 스레드 + 세션 전용 검출기      = ㄴ 안. **이게 A 와 같으면 ㄴ 이 산다**
  [C] 회전하는 스레드 + 스레드 로컬 검출기    = 현행. 낮게 나와야 정상(대조군)

  스레드 배정 패턴은 B·C 가 동일하다 — **검출기의 «소유» 만 다르다.** 그래야 대조가 성립한다.

── 판정 ────────────────────────────────────────────────────────────────────────
  B ≈ A  → 검출기는 스레드를 옮겨도 된다 → **ㄴ 유효**
  B ≈ C  → 옮기면 깨진다 → **ㄴ 탈락, ㄷ 로 확정**
  그 사이 → 부분 손상. 값을 그대로 보고할 것

🔴 **2026-08-11 현재 이 스크립트는 «한 번도 실행되지 않았다».**
   구문 검사만 통과했다. 오늘 하루에만 rig 가 두 번 틀렸고(픽스처 미검증 · anyio 워커 이름 중복,
   `results/thread-collision-2026-08-11/README.md §4`), 둘 다 «그럴듯한 결과» 를 냈다.
   **이 스크립트의 출력을 근거로 쓰기 전에 먼저 검산할 것** — 특히:
     · [전제 확인]이 4/4 통과하는가
     · A(기준선)가 96% 근처인가. 아니면 픽스처나 팔 구성이 틀린 것이다
     · «세션이 거친 스레드» 가 A 는 1, B·C 는 2 이상인가. 아니면 회전이 안 걸린 것이다

⚠️ 합성 인체다(#164 캐비엇 ③ 은 여기서도 안 닫힌다).
"""

import os
import statistics
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import cv2
import numpy as np

for _root in ("/app", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ai-server")):
    if os.path.isdir(os.path.join(_root, "app")):
        sys.path.insert(0, _root)
        break

from app.core.mediapipe_detector import PoseDetector  # noqa: E402
from app.config import settings  # noqa: E402


# ── 픽스처 (measure_thread_collision.py 와 동일 — 검증된 조합) ─────────────────
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


PERSONS = [
    dict(x_shift=-0.30, scale=0.58), dict(x_shift=+0.32, scale=0.90),
    dict(x_shift=-0.06, scale=0.90), dict(x_shift=+0.26, scale=0.58),
]
N_SESS = len(PERSONS)
N_FRAMES = 40


def frames_of(i, n=4):
    return [figure(squat=(j % n) / (n - 1.0), **PERSONS[i]) for j in range(n)]


def verify_standalone():
    print("[전제 확인] 각 «사람» 을 전용 검출기로 단독 실행")
    bad = []
    for i in range(N_SESS):
        d = PoseDetector()
        fr = frames_of(i) * 3
        for f in fr:
            d.detect(f)
        ok = sum(1 for f in fr if d.detect(f))
        d.close()
        print(f"   사람{i} x={PERSONS[i]['x_shift']:+.2f} s={PERSONS[i]['scale']:.2f}"
              f" → {ok}/{len(fr)} {'O' if ok == len(fr) else 'X'}")
        if ok < len(fr):
            bad.append(i)
    if bad:
        raise SystemExit(f"🔴 중단: 사람 {bad} 이 단독으로도 검출 안 된다.")
    print()


# ── 팔 ─────────────────────────────────────────────────────────────────────────
_tl = threading.local()


def run_arm(kind, n_threads):
    """kind: 'A' 세션전용스레드 · 'B' 회전스레드+세션검출기 · 'C' 회전스레드+스레드검출기

    호출은 **전부 순차**다. 동시성이 없으므로 락이 필요 없고, 변수는 «스레드가 바뀌는가» 뿐이다.
    """
    execs = [ThreadPoolExecutor(max_workers=1, thread_name_prefix=f"w{j}") for j in range(n_threads)]
    per_session = {i: PoseDetector() for i in range(N_SESS)} if kind == 'B' else None
    frames = {i: frames_of(i) for i in range(N_SESS)}
    seen_thread = {i: set() for i in range(N_SESS)}          # 세션이 거쳐간 스레드
    ok = 0
    total = 0
    times = []

    def work(sid, frame):
        if kind == 'B':
            det = per_session[sid]
        else:
            det = getattr(_tl, "d", None)
            if det is None:
                det = PoseDetector()
                _tl.d = det
        t0 = time.perf_counter()
        r = det.detect(frame)
        return (r is not None), (time.perf_counter() - t0) * 1000, threading.get_ident()

    # 워밍업 — 각 팔의 첫 호출은 검출기 생성이 섞인다
    for i in range(N_SESS):
        for k in range(4):
            e = execs[i % n_threads] if kind == 'A' else execs[(i + k) % n_threads]
            e.submit(work, i, frames[i][k % 4]).result()

    for k in range(N_FRAMES):
        for i in range(N_SESS):                              # 인터리브 = 실제 섞임 패턴
            e = execs[i % n_threads] if kind == 'A' else execs[(i + k) % n_threads]
            good, ms, tid = e.submit(work, i, frames[i][k % 4]).result()
            seen_thread[i].add(tid)
            ok += 1 if good else 0
            total += 1
            times.append(ms)

    for e in execs:
        e.shutdown(wait=True)
    if per_session:
        for d in per_session.values():
            d.close()
    return ok / total, statistics.mean(times), {i: len(s) for i, s in seen_thread.items()}


print("=== M1 — 검출기가 스레드를 옮겨 다녀도 되는가 ===")
print(f"    model_complexity={settings.POSE_MODEL_COMPLEXITY} · 세션 {N_SESS} × {N_FRAMES}프레임 "
      f"· **완전 순차**(동시성 없음) · 스레드 {N_SESS}")
print()
verify_standalone()

LABEL = {
    'A': ('세션 전용 스레드', 'ㄷ 안 = 기준선'),
    'B': ('회전 스레드 + 세션 검출기', 'ㄴ 안 ← 이게 급소'),
    'C': ('회전 스레드 + 스레드 검출기', '현행 = 대조군'),
}
print(f"| {'팔':<3} | {'구성':<28} | {'의미':<20} | {'검출률':>7} | {'프레임당ms':>10} | {'세션이 거친 스레드':>18} |")
print("|" + "|".join("-" * w for w in (5, 30, 22, 9, 12, 20)) + "|")

res = {}
for kind in ('A', 'B', 'C'):
    rate, ms, seen = run_arm(kind, N_SESS)
    res[kind] = rate
    name, mean = LABEL[kind]
    print(f"| {kind:<3} | {name:<28} | {mean:<20} | {rate:>6.1%} | {ms:>10.1f} "
          f"| {str(sorted(seen.values())):>18} |")

print()
print("## 판정")
a, b, c = res['A'], res['B'], res['C']
gap_ab, gap_bc = a - b, b - c
print(f"  A(기준) {a:.1%} · B(ㄴ) {b:.1%} · C(현행) {c:.1%}")
print(f"  B 와 A 의 차 : {gap_ab:+.1%}p   ← 0 에 가까우면 «옮겨도 된다»")
print(f"  B 와 C 의 차 : {gap_bc:+.1%}p   ← 0 에 가까우면 «옮기면 깨진다»")
print()
if abs(gap_ab) < 0.05:
    print("  ⇒ **ㄴ 유효.** 검출기는 순차 호출이면 스레드를 옮겨도 상태가 보존된다.")
elif abs(gap_bc) < 0.05:
    print("  ⇒ **ㄴ 탈락.** 검출기를 옮기면 현행과 다를 바 없다 → ㄷ 로 확정.")
else:
    print("  ⇒ **부분 손상.** 값을 그대로 보고할 것. 임계로 자르지 말 것.")
print()
print("  ⚠️ 5%p 는 판정 편의를 위한 선이지 근거 있는 임계가 아니다. 위 세 수치를 직접 볼 것.")
print("  ⚠️ 합성 인체다(#164 캐비엇 ③).")
