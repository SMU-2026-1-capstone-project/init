"""#164 ㄴ(검출기 풀) 구현 검증 — 손실이 사라졌는가, 그리고 CPU 는 어떻게 되는가

같은 부하(4세션 · 3fps · 인터리브)를 두 경로로 흘려 비교한다:
  현행  `get_detector()`            — 스레드 로컬 (고치기 전)
  풀    `lease_detector(session_id)` — 세션 전용 (#164 ㄴ)

⚠️ 로컬엔 cgroup 이 없어 메모리 상한을 유도할 수 없으므로 POSE_DETECTOR_POOL_SIZE 를 준다.
   (한도도 설정도 없으면 기동을 거부하는 것이 의도된 동작이다.)

🔴 «프레임당 CPU» 를 같이 재는 이유:
   구현 전에 «트래킹이 유지되면 재탐지가 줄어 CPU 가 덜 든다» 고 **추정했는데 틀렸다.**
   실제로는 **늘어난다** — 검출 «실패» 는 조기 반환이라 싸고, «성공» 은 33개 랜드마크를
   끝까지 만들어서 비싸다. 즉 현행이 싸 보였던 것은 효율이 아니라 **일을 안 해서**였다.
   추정을 그대로 두면 나중에 틀린 근거로 인용되므로 이 스크립트가 매번 같이 찍는다.
"""
import asyncio, os, statistics, sys, time
sys.path.insert(0, os.path.abspath("ai-server"))
os.environ["POSE_DETECTOR_POOL_SIZE"] = "8"
from starlette.concurrency import run_in_threadpool
from app.core.mediapipe_detector import get_detector, lease_detector, get_pool
import numpy as np, cv2
src = open("loadtest/measure_thread_collision.py", encoding="utf-8").read()
g = {"cv2": cv2, "np": np}
exec(src[src.index("def figure("):src.index("def frames_of")], g)
figure, PERSONS = g["figure"], g["PERSONS"]
frames_of = lambda i: [figure(squat=(j % 4) / 3.0, **PERSONS[i]) for j in range(4)]
N, F, FPS = 4, 40, 3

async def arm(use_pool):
    if use_pool:
        for i in range(N):
            assert get_pool().acquire(i)
    ok = tot = 0; times = []
    def work(sid, fr):
        nonlocal ok, tot
        t0 = time.perf_counter()
        if use_pool:
            with lease_detector(sid) as d:
                r = d.detect(fr)
        else:
            r = get_detector().detect(fr)
        times.append((time.perf_counter() - t0) * 1000)
        tot += 1; ok += 1 if r else 0
    async def sess(sid):
        f = frames_of(sid)
        for k in range(F):
            t0 = time.perf_counter()
            await run_in_threadpool(work, sid, f[k % 4])
            lag = 1/FPS - (time.perf_counter() - t0)
            if lag > 0: await asyncio.sleep(lag)
    await asyncio.gather(*(sess(i) for i in range(N)))
    if use_pool:
        for i in range(N): get_pool().release(i)
    return ok/tot, statistics.mean(times)

async def main():
    R = 3
    await arm(False)                                   # 버림판
    old, new = [], []
    for r in range(R):                                 # 판마다 순서를 뒤집는다
        for pool in ((False, True) if r % 2 == 0 else (True, False)):
            (new if pool else old).append(await arm(pool))

    def med(rs, i):
        v = sorted(x[i] for x in rs)
        return v[len(v) // 2], v[0], v[-1]

    od, od0, od1 = med(old, 0); oms, om0, om1 = med(old, 1)
    nd, nd0, nd1 = med(new, 0); nms, nm0, nm1 = med(new, 1)
    print(f"
  {R}판 중앙값 (괄호 = 최소~최대)")
    print(f"  현행(스레드 로컬) : 검출 {od:6.1%} ({od0:.1%}~{od1:.1%}) · 프레임당 {oms:6.1f} ms ({om0:.1f}~{om1:.1f})")
    print(f"  풀(세션 전용)     : 검출 {nd:6.1%} ({nd0:.1%}~{nd1:.1%}) · 프레임당 {nms:6.1f} ms ({nm0:.1f}~{nm1:.1f})")
    print(f"
  검출률        {nd - od:+.1%}p        ← 안정적")
    print(f"  프레임당 CPU  {nms / oms - 1:+.1%}")
    print("  ⚠️ 프레임당 CPU 는 단판 관측에서 +3.1% / +41.0% / +68.1% 로 크게 흔들렸다.")
    print("     동시 부하 스케줄링 노이즈와 «검출 성공/실패 비율이 다르면 평균의 의미가 달라지는»")
    print("     문제가 섞여 있다(실패는 조기 반환이라 싸다). **이 rig 로는 결론이 안 난다.**")
    print(f"  유효 프레임/초 {(nd / nms) / (od / oms):.2f}배 (검출률÷시간) — 1.8~3.1배 관측. 방향만 신뢰")


asyncio.run(main())
