"""R6 rig — 스레드 vs 프로세스 확장성. 「왜 16 vCPU 중 8.7 만 쓰나」를 가른다.

가설(GIL): 파이썬 구간이 프레임당 3.17ms 라 **1코어어치**가 포화하고 거기서 전체가 막힌다.
            → 프로세스로 쪼개면 각자 자기 GIL 을 가지므로 처리량과 CPU 가 **같이** 오른다.
반증      : 프로세스가 스레드와 같으면 GIL 이 아니다 (캐시·메모리대역·HT 쪽).

**판정선** — 프로세스 2워커에서
    처리량 1.7~2배  **그리고**  CPU 8.7 → 14~16 vCPU   → GIL
    둘 중 하나라도 안 오르면                              → GIL 아님

⚠️ 이 rig 은 `Pose.process()` **만** 때린다. FastAPI·Pydantic 구간은 안 본다.
   근거: 프로파일 §1 에서 우리 서비스 코드의 GIL 구간은 3.17ms 중 ~0.8ms 뿐이고 나머지는
   MediaPipe 래퍼 내부라, 래퍼를 때리는 이 rig 이 그 구간을 덮는다. **다만 가정이다** —
   GIL 비용이 실은 FastAPI 쪽이면 이 rig 은 그걸 못 보고 「GIL 아님」으로 틀리게 답한다.
   그 경우 2층(uvicorn 1워커 vs 2워커 + 실제 HTTP 부하)이 필요하다.

사용:
    # 대상 박스에서 (도커·MySQL·Spring 불필요 — venv + frames.json 만)
    python scaling_rig.py FRAMES.json [--dur 15] [--levels 1,2,4,8,16] [--rounds 3] [--out r6.tsv]

    # 컨테이너 안에서 돌릴 때 (운영과 같은 이미지·같은 MediaPipe 버전)
    docker cp scaling_rig.py shadowfit-ai:/tmp/ && docker cp frames.json shadowfit-ai:/tmp/
    docker exec shadowfit-ai python /tmp/scaling_rig.py /tmp/frames.json

🔴 **박스에 다른 부하가 없어야 한다.** CPU 를 /proc/stat 으로 걷으므로 이웃 컨테이너가 섞인다.
"""
import argparse
import json
import multiprocessing as mp
import os
import statistics as st
import sys
import threading
import time

# 🔴 fork 금지. MediaPipe·TFLite 가 스레드를 띄운 뒤 fork 하면 자식이 조용히 데드락한다.
#    리눅스 기본이 fork 라 이걸 안 박으면 EC2 에서 «멈춘 채로 아무 로그도 없음» 이 된다.
try:
    mp.set_start_method("spawn")
except RuntimeError:
    pass


# ── CPU 샘플러 — /proc 직독 (#250 부하기 샘플러와 같은 방식·같은 스케일) ──────────────
#
# 스케일은 `docker stats` 와 맞춘다: **100% = 1 vCPU**. 16 vCPU 박스면 1600% 가 포화다.
# ⚠️ #255 의 교훈 — 프로세스가 사라진 뒤 스냅숏을 찍으면 델타가 음수가 된다.
#    그래서 종료 스냅숏은 **워커가 아직 살아 있는 동안** 찍는다(아래 done 배리어).

def _stat_busy_total():
    """(busy, total) jiffies. /proc 이 없으면 None (윈도우 스모크용)."""
    try:
        with open("/proc/stat") as f:
            parts = f.readline().split()[1:]
    except OSError:
        return None
    v = [int(x) for x in parts]
    total = sum(v)
    idle = v[3] + v[4]                       # idle + iowait
    return total - idle, total


def _pids_jiffies(pids):
    """주어진 PID 들의 utime+stime 합(jiffies). /proc 없으면 None."""
    total = 0
    got = False
    for p in pids:
        try:
            with open(f"/proc/{p}/stat") as f:
                raw = f.read()
        except OSError:
            continue
        # comm 에 공백·괄호가 들어갈 수 있어 ')' 뒤부터 센다 (#250 과 같은 처리)
        tail = raw.rsplit(") ", 1)[-1].split()
        total += int(tail[11]) + int(tail[12])
        got = True
    return total if got else None


HZ = os.sysconf("SC_CLK_TCK") if hasattr(os, "sysconf") else 100
NCPU = os.cpu_count() or 1


# ── 워커 ──────────────────────────────────────────────────────────────────────

def worker(frames, dur, start_bar, done_bar, out):
    """배리어에서 출발해 dur 초 동안 추론한다. 반환 = (프레임수, 걸린초).

    🔴 모델 로드·워밍업은 **배리어 앞**이다. 이걸 계측에 넣어서 «1워커 8.2fps · 2워커 28.1fps»
       라는 불가능한 값을 한 번 냈다(프로파일 §3).
    """
    import cv2
    import mediapipe as mp_

    pose = mp_.solutions.pose.Pose(static_image_mode=False, model_complexity=1,
                                   min_detection_confidence=0.5, min_tracking_confidence=0.5)
    import base64
    import numpy as np
    imgs = []
    for f in frames[:10]:
        b = base64.b64decode(f.split(",", 1)[-1])
        im = cv2.imdecode(np.frombuffer(b, np.uint8), cv2.IMREAD_COLOR)
        imgs.append(cv2.cvtColor(im, cv2.COLOR_BGR2RGB))
    for i in range(5):
        pose.process(imgs[i % len(imgs)])

    start_bar.wait()
    t0 = time.perf_counter()
    deadline = t0 + dur
    n = 0
    while time.perf_counter() < deadline:
        pose.process(imgs[n % len(imgs)])
        n += 1
    dt = time.perf_counter() - t0
    out.put((n, dt))
    done_bar.wait()                     # 🔴 부모가 CPU 스냅숏을 찍을 때까지 살아 있는다 (#255)
    pose.close()


def run_once(arm, frames, nw, dur):
    """한 판. 반환 = dict(fps, cpu_pct_box, cpu_pct_self, wall, per_worker_ms)."""
    if arm == "thread":
        start_bar, done_bar = threading.Barrier(nw + 1), threading.Barrier(nw + 1)
        q = __import__("queue").Queue()
        ws = [threading.Thread(target=worker, args=(frames, dur, start_bar, done_bar, q))
              for _ in range(nw)]
        pids = [os.getpid()]                       # 스레드는 전부 이 프로세스 안이다
    else:
        start_bar, done_bar = mp.Barrier(nw + 1), mp.Barrier(nw + 1)
        q = mp.Queue()
        ws = [mp.Process(target=worker, args=(frames, dur, start_bar, done_bar, q))
              for _ in range(nw)]
        pids = None                                # start 후에 채운다

    for w in ws:
        w.start()
    if arm == "process":
        pids = [w.pid for w in ws]

    start_bar.wait()                                # 전 워커가 동시에 출발
    b0, s0 = (_stat_busy_total() or (None, None))
    p0 = _pids_jiffies(pids)
    t0 = time.perf_counter()

    done_bar.wait()                                 # 워커가 «아직 살아 있는» 시점
    wall = time.perf_counter() - t0
    b1, s1 = (_stat_busy_total() or (None, None))
    p1 = _pids_jiffies(pids)

    res = [q.get() for _ in ws]
    for w in ws:
        w.join()

    frames_done = sum(r[0] for r in res)
    per_ms = st.mean(r[1] / r[0] * 1000 for r in res)
    cpu_box = (100.0 * (b1 - b0) / (s1 - s0) * NCPU) if b0 is not None and s1 != s0 else None
    cpu_self = (100.0 * (p1 - p0) / (HZ * wall)) if p0 is not None else None
    return {"fps": frames_done / wall, "wall": wall, "per_worker_ms": per_ms,
            "cpu_box": cpu_box, "cpu_self": cpu_self, "frames": frames_done}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("frames")
    ap.add_argument("--dur", type=float, default=15.0)
    ap.add_argument("--levels", default="1,2,4,8,16")
    ap.add_argument("--rounds", type=int, default=3)
    ap.add_argument("--out", default="r6_scaling.tsv")
    a = ap.parse_args()

    frames = json.load(open(a.frames))["frames"]
    levels = [int(x) for x in a.levels.split(",")]
    arms = ["thread", "process"]

    print(f"R6 — 스레드 vs 프로세스 · 레벨 {levels} · 판당 {a.dur}s · 본판 {a.rounds} + 버림 1")
    print(f"박스: {NCPU} vCPU · HZ={HZ} · /proc {'있음' if _stat_busy_total() else '없음(CPU 미수집)'}")
    print(f"⚠️ CPU 스케일 100% = 1 vCPU — 이 박스 포화선은 {NCPU * 100}% 다\n")

    fh = open(a.out, "w", encoding="utf-8")
    fh.write("arm\tround\tworkers\tfps\tcpu_box_pct\tcpu_self_pct\tper_worker_ms\twall_s\tframes\n")

    # 판 이름: discard 는 버림. 판마다 팔 순서를 뒤집는다 — 팔과 판 순서를 가르기 위해서다.
    rounds = ["discard"] + [f"r{i+1}" for i in range(a.rounds)]
    for ri, rnd in enumerate(rounds):
        order = arms if ri % 2 == 0 else arms[::-1]
        for arm in order:
            for nw in levels:
                r = run_once(arm, frames, nw, a.dur)
                cb = f"{r['cpu_box']:.0f}" if r["cpu_box"] is not None else "-"
                cs = f"{r['cpu_self']:.0f}" if r["cpu_self"] is not None else "-"
                fh.write(f"{arm}\t{rnd}\t{nw}\t{r['fps']:.2f}\t{cb}\t{cs}\t"
                         f"{r['per_worker_ms']:.2f}\t{r['wall']:.2f}\t{r['frames']}\n")
                fh.flush()
                tag = "(버림)" if rnd == "discard" else ""
                print(f"  {arm:>8} {rnd:>8} {nw:>3}워커  {r['fps']:>7.1f} fps  "
                      f"CPU박스 {cb:>5}%  CPU자신 {cs:>5}%  워커당 {r['per_worker_ms']:>6.1f}ms {tag}")
    fh.close()

    # ── 요약 (버림판 제외) ────────────────────────────────────────────────
    rows = [l.rstrip("\n").split("\t") for l in open(a.out, encoding="utf-8")][1:]
    rows = [r for r in rows if r[1] != "discard"]

    def med(arm, nw, col):
        v = [float(r[col]) for r in rows
             if r[0] == arm and int(r[2]) == nw and r[col] not in ("-", "")]
        return st.median(v) if v else None

    def fmt(v, suffix="", w=0):
        return f"{'-':>{w}}" if v is None else f"{v:>{w}.0f}{suffix}"

    # 🔴 «자기 1워커 대비 배수» 를 같이 낸다. 스모크에서 팔마다 1워커 기준선이 달랐고
    #    (스레드 28.7 vs 프로세스 18.3 fps), 그대로 두면 «프/스» 비율에 기준선 차이가 섞인다.
    #    GIL 의 서명은 «스레드는 일찍 평평해지고 프로세스는 계속 오른다» 라는 **곡선 모양**이지
    #    한 지점의 비율이 아니다.
    base_t, base_p = med("thread", levels[0], 3), med("process", levels[0], 3)
    print(f"\n{'워커':>4} {'스레드fps':>10} {'배수':>7} {'프로세스fps':>12} {'배수':>7} {'프/스':>7} "
          f"{'스CPU%':>8} {'프CPU%':>8}")
    print("-" * 74)
    for nw in levels:
        mt, mp_ = med("thread", nw, 3), med("process", nw, 3)
        if mt is None or mp_ is None:
            continue
        print(f"{nw:>4} {mt:>10.1f} {mt/base_t:>6.2f}x {mp_:>12.1f} {mp_/base_p:>6.2f}x "
              f"{mp_/mt:>6.2f}x {fmt(med('thread', nw, 4), '%', 7)} {fmt(med('process', nw, 4), '%', 7)}")

    print(f"\n판정선 — **곡선 모양**으로 읽는다(한 지점 비율이 아니다):")
    print(f"  GIL 이면   : 스레드 배수가 일찍 평평해지고, 프로세스 배수는 계속 오른다.")
    print(f"               그리고 프로세스 쪽 CPU% 가 스레드 쪽보다 **뚜렷이 높다**.")
    print(f"  GIL 아니면 : 두 배수 곡선이 같은 자리에서 같이 꺾인다 (= 코어·캐시·대역이 먼저 걸린다).")
    print(f"  ⚠️ 팔마다 1워커 기준선이 다를 수 있다 — 그래서 «프/스» 보다 «배수» 를 먼저 본다.")
    print(f"결과: {a.out}")


if __name__ == "__main__":
    main()
