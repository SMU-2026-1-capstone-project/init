#!/usr/bin/env python3
"""박스 보정 — 「이 박스가 초당 얼마나 일하는가」를 앱과 무관하게 잰다.

## 왜 이게 있나

[#255](https://github.com/Shadowfit/init/issues/255): 같은 구성이 라운드를 건너 처리량 +17.7% 인데 **AI CPU 는 같았다**
(869.3% → 869.0%). 즉 **같은 CPU 를 쓰면서 일을 덜 했다.**

`docker stats` 의 `CPU %` 는 **시간** 측정이다 — 「몇 초 점유했나」이지 **일의 양이 아니다.**
물리 호스트의 유효 클럭(터보·이웃 소음)이 다르면 **같은 CPU% 로 다른 양의 일**을 한다.
관측된 서명이 정확히 그 모양이다.

이 스크립트는 그 «일의 양» 을 **고정 작업으로** 잰다. 설계:
`docs/decisions/round-to-round-nonreproducibility.md` §2·§3(축 0).

## 두 축을 낸다

- **cpu** — 의존성 0. 고정 반복 루프. **어느 박스에서든 돈다**(MySQL 전용 박스 포함)
- **infer** — mediapipe 로 1스레드 N프레임. R6 의 1워커 판과 같은 것이라 **그 값과 비교된다**.
  venv·frames.json 이 없으면 **사유를 적고 건너뛴다**

🔴 **판정에 안 쓰더라도 무조건 기록한다.** 이 값이 없어서 P6 1·2라운드의 비재현을 사후에
못 가른다 — 그 박스들은 사라졌다.

## 쓰는 법

    python3 loadtest/calibrate_box.py                  # 사람이 읽는 출력
    python3 loadtest/calibrate_box.py --tsv out.tsv    # 라운드 산출물로
    CALIB_FRAMES=<frames.json> CALIB_PY=<venv python> python3 loadtest/calibrate_box.py

⚠️ **cpu 축은 인터프리터 속도를 잰다** — 파이썬 버전이 다르면 값이 달라진다. 그래서 버전을
같이 기록하고, **비교는 같은 버전끼리만** 해야 한다.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import platform
import subprocess
import sys
import time

# 🔴 고정 반복 수다. **바꾸면 과거 값과 비교가 끊긴다** — 바꿔야 하면 새 이름의 축으로 낼 것.
CPU_ITERS = 3_000_000
INFER_FRAMES = 100          # R6 의 워커당 100프레임과 같다


def box_facts() -> dict:
    f = {
        "ncpu": os.cpu_count(),
        "python": platform.python_version(),
        "machine": platform.machine(),
        "system": platform.system(),
    }
    try:  # 물리 코어·모델명 — 리눅스에서만
        with open("/proc/cpuinfo", encoding="utf-8") as fh:
            txt = fh.read()
        for line in txt.splitlines():
            if line.startswith("model name") and "model" not in f:
                f["model"] = line.split(":", 1)[1].strip()
        f["physical"] = len({
            l.split(":")[1].strip() for l in txt.splitlines() if l.startswith("core id")
        }) or None
    except OSError:
        pass
    try:
        f["commit"] = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            capture_output=True, text=True, timeout=10,
        ).stdout.strip() or None
    except Exception:  # noqa: BLE001
        f["commit"] = None
    return f


def calib_cpu() -> dict:
    """의존성 없는 고정 작업. 순수 파이썬이라 **인터프리터 속도**를 잰다.

    같은 파이썬 버전끼리만 비교할 것(파일 머리 ⚠️).
    """
    t0 = time.perf_counter()
    acc = 0.0
    for i in range(CPU_ITERS):
        acc += math.sqrt(i % 977) * 1.0000001
    dt = time.perf_counter() - t0
    return {"axis": "cpu", "iters": CPU_ITERS, "sec": round(dt, 4),
            "rate": round(CPU_ITERS / dt, 1), "unit": "iter/s", "checksum": round(acc, 3)}


def calib_infer(frames_path: str) -> dict:
    """mediapipe 1스레드 N프레임. **모델 로드·워밍업은 계측에서 뺀다**(R6 과 같은 규칙)."""
    import base64

    import cv2                      # noqa: F401  (mediapipe 가 요구한다)
    import mediapipe as mp
    import numpy as np

    frames = json.load(open(frames_path, encoding="utf-8"))["frames"]
    imgs = []
    for b64 in frames:
        raw = base64.b64decode(b64.split(",", 1)[-1])
        img = cv2.imdecode(np.frombuffer(raw, np.uint8), cv2.IMREAD_COLOR)
        imgs.append(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))

    pose = mp.solutions.pose.Pose(model_complexity=1, static_image_mode=False)
    pose.process(imgs[0])           # 워밍업 — 첫 추론이 지연 할당을 문다
    t0 = time.perf_counter()
    for i in range(INFER_FRAMES):
        pose.process(imgs[i % len(imgs)])
    dt = time.perf_counter() - t0
    pose.close()
    return {"axis": "infer", "frames": INFER_FRAMES, "sec": round(dt, 4),
            "rate": round(INFER_FRAMES / dt, 2), "unit": "fps",
            "mediapipe": mp.__version__}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tsv", default="", help="이 경로에 TSV 로 덧붙인다(헤더는 없을 때만)")
    ap.add_argument("--frames", default=os.environ.get("CALIB_FRAMES", ""),
                    help="frames.json. 없으면 infer 축을 건너뛴다")
    ap.add_argument("--json", action="store_true", help="JSON 한 줄로 출력")
    a = ap.parse_args()

    facts = box_facts()
    out = [calib_cpu()]

    frames = a.frames or os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "results", "coresidency-2026-08-15", "frames.json")
    if os.path.isfile(frames):
        try:
            out.append(calib_infer(frames))
        except Exception as e:  # noqa: BLE001
            # 🔴 값이 아니라 «사유» 를 남긴다. 이 축이 비는 것은 흔한 일이고(MySQL 전용 박스),
            #    비었다는 사실 자체가 조건이다.
            out.append({"axis": "infer", "error": repr(e)[:200]})
    else:
        out.append({"axis": "infer", "error": f"frames.json 없음: {frames}"})

    rec = {"ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), **facts, "axes": out}

    if a.json:
        print(json.dumps(rec, ensure_ascii=False))
    else:
        print("── 박스 보정 ──")
        print(f"  박스   : {facts.get('ncpu')} vCPU"
              f"{' · 물리 ' + str(facts['physical']) if facts.get('physical') else ''}"
              f" · {facts.get('model', '?')}")
        print(f"  스택   : python {facts['python']} · 커밋 {facts.get('commit')}")
        for x in out:
            if "error" in x:
                print(f"  {x['axis']:6} : 🔴 건너뜀 — {x['error']}")
            else:
                print(f"  {x['axis']:6} : {x['rate']:>10,.1f} {x['unit']}  ({x['sec']}s)")
        print("  🔴 판정에 안 쓰더라도 결과 디렉터리에 남길 것 (#255 축 0)")

    if a.tsv:
        head = not os.path.exists(a.tsv) or os.path.getsize(a.tsv) == 0
        with open(a.tsv, "a", encoding="utf-8", newline="") as f:
            if head:
                f.write("ts\tcommit\tncpu\tphysical\tpython\tmodel\taxis\trate\tunit\tsec\tnote\n")
            for x in out:
                f.write("\t".join(str(v) for v in [
                    rec["ts"], facts.get("commit"), facts.get("ncpu"), facts.get("physical"),
                    facts["python"], facts.get("model", ""), x["axis"],
                    x.get("rate", ""), x.get("unit", ""), x.get("sec", ""),
                    x.get("error", x.get("mediapipe", "")),
                ]) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
