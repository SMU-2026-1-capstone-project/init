# -*- coding: utf-8 -*-
"""0단계 — anyio 스레드풀 상한이 «걸리는가» 를 로컬에서 본다.

mediapipe 는 안 쓴다. 재는 것은 앱이 아니라 **anyio 의 거동**이다:
동기(`def`) 핸들러를 N개 동시에 때렸을 때 limiter 의 `tasks_waiting` 이 0 을 넘는가.

🔴 이 프로브가 답하는 것은 «상한이 걸리는 구조인가» 이지 «AI 서버에서 걸렸는가» 가 아니다.
   후자는 1단계(EC2)가 답한다.
"""
import asyncio, threading, time, json, sys
import anyio.to_thread
from fastapi import FastAPI
import uvicorn

HOLD = 0.10          # 핸들러가 무는 시간 — R10-a 의 핸들러 total 106.9ms 에 맞췄다
SAMPLES = []         # (t, total, borrowed, waiting)

app = FastAPI()

@app.get("/work")
def work():          # 🔴 def — ai-server 의 detect_pose 와 같은 모양이다
    time.sleep(HOLD)
    return {"ok": True}

async def _sampler():
    """limiter 는 RunVar 라 **이벤트 루프 안에서** 읽어야 한다."""
    lim = anyio.to_thread.current_default_thread_limiter()
    while True:
        st = lim.statistics()
        SAMPLES.append((time.perf_counter(), lim.total_tokens,
                        st.borrowed_tokens, st.tasks_waiting))
        await asyncio.sleep(0.02)

@app.on_event("startup")
async def _start():
    asyncio.create_task(_sampler())

def serve():
    uvicorn.run(app, host="127.0.0.1", port=8123, log_level="error")

if __name__ == "__main__":
    threading.Thread(target=serve, daemon=True).start()
    time.sleep(2.5)
    import urllib.request
    def client(stop):
        while not stop.is_set():
            try: urllib.request.urlopen("http://127.0.0.1:8123/work", timeout=10).read()
            except Exception: pass
    print(f"{'동시':>5}{'total':>7}{'borrowed p50':>14}{'borrowed max':>14}{'waiting p50':>13}{'waiting max':>13}")
    for N in (20, 40, 60, 80):
        SAMPLES.clear()
        stop = threading.Event()
        ts = [threading.Thread(target=client, args=(stop,), daemon=True) for _ in range(N)]
        for t in ts: t.start()
        time.sleep(6)
        snap = list(SAMPLES); stop.set()
        for t in ts: t.join(timeout=2)
        mid = snap[len(snap)//4:]           # 앞 1/4 는 램프라 버린다
        if not mid: print(f"{N:>5}  (표본 없음)"); continue
        b = sorted(x[2] for x in mid); w = sorted(x[3] for x in mid)
        print(f"{N:>5}{mid[0][1]:>7.0f}{b[len(b)//2]:>14}{max(b):>14}{w[len(w)//2]:>13}{max(w):>13}")
        time.sleep(1)
