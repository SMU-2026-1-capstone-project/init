"""② HTTP 프레임워크 비용 + ③ 스레드 vs 프로세스 확장성.

①(단계별)에서 «추론 밖» 이 1.86ms(5.3%)로 나왔다. 그런데 AWS 라운드 B 는 프레임당 27.6ms 인데
순수 추론이 15.0ms 다 — 12.6ms 가 설명이 안 된다. 후보 둘:
  (가) HTTP/ASGI 프레임워크 비용을 ①이 안 쟀다  → 여기 ②가 잰다
  (나) 동시 실행 경합(GIL 등)                  → 여기 ③이 잰다

③이 핵심이다. **같은 일을 N 스레드 vs N 프로세스로** 돌려서 처리량을 비교한다.
GIL 이 원인이면 프로세스가 스레드보다 빨라진다. 코어가 원인이면 둘이 같다.
"""
import json
import multiprocessing as mp
import os
import statistics as st
import sys
import threading
import time

sys.path.insert(0, os.path.abspath("."))
os.environ.setdefault("POSE_DETECTOR_POOL_SIZE", "8")

FRAMES_PATH = sys.argv[1]
MODE = sys.argv[2] if len(sys.argv) > 2 else "all"


def _worker_loop(frames, n, barrier=None, out=None):
    """프레임 n 장을 추론한다. 반환 = **루프만** 걸린 초.

    🔴 모델 로드·워밍업은 배리어 앞에 둔다. 앞선 판이 이걸 계측에 포함해서
       «1워커 8.2fps · 2워커 28.1fps» 같은 불가능한 수를 냈다.
    """
    import cv2
    from app.core.mediapipe_detector import PoseDetector
    from app.utils.image_utils import base64_to_image

    det = PoseDetector()
    imgs = [cv2.cvtColor(base64_to_image(f), cv2.COLOR_BGR2RGB) for f in frames[:10]]
    for i in range(5):                                   # 워밍업(모델 지연 할당)
        det._pose.process(imgs[i % len(imgs)])
    if barrier is not None:
        barrier.wait()                                   # 전 워커가 동시에 출발한다
    t0 = time.perf_counter()
    for i in range(n):
        det._pose.process(imgs[i % len(imgs)])
    dt = time.perf_counter() - t0
    if out is not None:
        out.put(dt)
    return dt


def e2e():
    """② TestClient 로 실제 라우팅·미들웨어·직렬화를 통과시킨다."""
    from fastapi.testclient import TestClient
    from app.main import app
    from app.grpc.session_state import get_registry

    from app.core.mediapipe_detector import get_pool

    frames = json.load(open(FRAMES_PATH))["frames"]
    sid = 990002
    get_registry().create(sid, 1, [[90.0] * 4 for _ in range(60)], "squat")
    assert get_pool().acquire(sid), "풀에 자리가 없다"      # 이게 없으면 추론이 통째로 스킵된다

    with TestClient(app) as c:
        hdr = {"Authorization": "Bearer " + os.environ.get("AI_PUBLIC_TOKEN", "b")}
        for i in range(5):                                # 워밍업
            c.post("/api/v1/pose", json={"image": frames[i], "exercise_type": "squat",
                                         "session_id": sid}, headers=hdr)
        lat = []
        for i in range(60):
            body = {"image": frames[i % len(frames)], "exercise_type": "squat", "session_id": sid}
            t = time.perf_counter()
            r = c.post("/api/v1/pose", json=body, headers=hdr)
            lat.append(time.perf_counter() - t)
            if r.status_code != 200:
                print("  🔴 status", r.status_code, r.text[:200]); break
    print(f"② 요청 전체(TestClient) 평균 {st.mean(lat)*1000:.2f}ms · 중앙 {st.median(lat)*1000:.2f}ms")
    return st.mean(lat) * 1000


def scaling():
    """③ 스레드 vs 프로세스 — 같은 총 프레임 수를 나눠 돌린다."""
    frames = json.load(open(FRAMES_PATH))["frames"]
    PER = 100
    print(f"\n③ 확장성 — 워커당 {PER}프레임 추론(모델 로드·워밍업 제외), 물리 2코어 박스")
    print(f"{'워커':>4} {'스레드 fps':>11} {'배수':>7} {'프로세스 fps':>13} {'배수':>7} {'프로세스/스레드':>14}")
    print("-" * 68)
    b_t = b_p = None
    for nw in (1, 2, 4):
        # 스레드
        bar = threading.Barrier(nw)
        res = []
        lock = threading.Lock()

        def run():
            dt = _worker_loop(frames, PER, bar)
            with lock:
                res.append(dt)

        ths = [threading.Thread(target=run) for _ in range(nw)]
        [t.start() for t in ths]; [t.join() for t in ths]
        fps_t = nw * PER / max(res)

        # 프로세스
        q = mp.Queue()
        pbar = mp.Barrier(nw)
        ps = [mp.Process(target=_worker_loop, args=(frames, PER, pbar, q)) for _ in range(nw)]
        [p.start() for p in ps]
        dts = [q.get() for _ in range(nw)]
        [p.join() for p in ps]
        fps_p = nw * PER / max(dts)

        if b_t is None:
            b_t, b_p = fps_t, fps_p
        print(f"{nw:>4} {fps_t:>11.1f} {fps_t/b_t:>6.2f}x {fps_p:>13.1f} {fps_p/b_p:>6.2f}x "
              f"{fps_p/fps_t:>13.2f}x")


if __name__ == "__main__":
    mp.freeze_support()
    if MODE in ("all", "e2e"):
        e2e()
    if MODE in ("all", "scaling"):
        scaling()
