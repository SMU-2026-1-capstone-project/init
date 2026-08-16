"""AI 프레임 부하기 — 동시 «세션» 을 실물 경로로 연다 (동거 용량 rig, P6).

## 왜 세션을 Spring 을 통해 여는가

프레임만 쏘면 **전부 거절된다.** `pose.py:69` 의 `lease_detector(session_id)` 가 세션 전용
검출기를 빌려주는데, 그 자리는 **gRPC `StartAnalysis` 가 있어야 생긴다**(#164 ㄴ 안).
그래서 부하기는 실물과 같은 순서를 밟는다:

    Spring 회원가입 → 로그인 → 온보딩 → 세션 생성 ──(Spring이 gRPC StartAnalysis)──> AI 풀에 자리
                                                    → 그 다음에야 프레임이 수락된다

🔴 **온보딩의 `preferredUrl` 을 빼면 세션 생성이 400 이다** — 세션이 회원의 선호 영상을
`reference_source` 로 물고 시작한다. `chain_check.sh` 가 같은 함정을 밟고 주석으로 남겼다.

## 이 부하기가 재지 않는 것

- **rep·적재·리포트** — 합성 인체는 무릎 각도가 87~124° 라 «서있음»(155°) 문턱을 못 넘어
  rep 이 안 생긴다(`gen_frames.py` 게이트, 2026-08-15 실측). 그래서 Spring·MySQL 부하는
  이 부하기가 **만들지 않고** 기존 ghz rig 이 따로 건다(README §2).
- **fps 스윕** — AI 에 유입 속도 상한이 있어(프레임 간격 300ms) 3fps 위로 올리면 재는 것이
  «용량» 이 아니라 «상한에 걸린 비율» 이 된다. 이 rig 이 흔드는 것은 **세션 수**다.

## 쓰는 법

    python load_ai.py --base http://SPRING:8080 --ai http://AI:8000 \\
        --token "$AI_PUBLIC_TOKEN" --frames frames.json \\
        --sessions 40 --dur 60 --out level_40.tsv

표준 출력에 요약 한 줄, `--out` 에 요청별 원본. **판정은 사람이 한다.**
"""

import argparse
import json
import random
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request

STOP = threading.Event()
LOCK = threading.Lock()
ROWS = []          # (t_rel, session_idx, ms, outcome)
SETUP_FAIL = []


def http(url, method="GET", body=None, headers=None, timeout=20):
    """(status, text). 예외를 상태로 바꿔 돌려준다 — 부하 중 예외로 스레드가 죽으면
    그 세션이 «조용히 빠진» 채 표만 정상으로 보인다."""
    data = json.dumps(body).encode() if body is not None else None
    h = {"Content-Type": "application/json"}
    h.update(headers or {})
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return 0, f"{type(e).__name__}: {e}"


def setup_account(base, idx, pw):
    """계정 하나. 이미 있으면 로그인만 한다(판을 반복해도 계정이 쌓이지 않게)."""
    email = f"cores{idx}@shadowfit.local"
    http(base + "/member/signup", "POST", {
        "username": f"cores{idx}", "email": email, "password": pw,
        "sex": "MALE", "role": "USER"})
    st, body = http(base + "/member/login", "POST", {"email": email, "password": pw})
    if st != 200:
        return None, f"login {st}: {body[:120]}"
    try:
        tok = json.loads(body)["accessToken"]
    except Exception:  # noqa: BLE001
        return None, f"login 응답에 accessToken 이 없다: {body[:120]}"
    # 🔴 preferredUrl 필수 — 없으면 세션 생성이 400 이다
    st, body = http(base + f"/member/onboarding/{email}", "PATCH", {
        "selectedPersona": "ADVANCED", "workoutLevel": "STARTER",
        "height": 180.0, "weight": 75.5,
        "preferredUrl": "https://www.youtube.com/watch?v=q6hBSSis_60",
    }, {"Authorization": "Bearer " + tok})
    if st != 200:
        return None, f"onboarding {st}: {body[:120]}"
    return tok, ""


def classify(status, text):
    """응답을 세 갈래로 나눈다. **뭉치면 용량 신호와 품질 신호가 섞인다.**"""
    if status != 200:
        return f"http{status}"
    try:
        j = json.loads(text)
    except Exception:  # noqa: BLE001
        return "badjson"
    if j.get("success"):
        return "ok"
    msg = j.get("message") or ""
    if "분석기가 없습니다" in msg:
        return "nolease"     # 🔴 용량 신호 — 풀에 자리가 없다(세션 미시작 또는 상한)
    if "감지할 수 없습니다" in msg:
        return "nopose"      # 🔴 품질 신호 — 검출이 깨졌다 (#164 가 고친 그 지표)
    return "fail:" + msg[:40]


def session_worker(idx, base, ai, tok, exercise_id, frames, fps, t0, token, attach_sec=20.0):
    st, body = http(base + "/exercises/sessions", "POST", {"exerciseId": exercise_id},
                    {"Authorization": "Bearer " + tok})
    try:
        sid = json.loads(body)["sessionId"]
    except Exception:  # noqa: BLE001
        with LOCK:
            SETUP_FAIL.append(f"[{idx}] 세션 생성 {st}: {body[:120]}")
        return

    # 🔴 세션을 «동시에» 여는 순간 자체가 부하다. 판마다 그 순간이 겹치면 첫 몇 초가
    #    다른 판과 다른 조건이 된다 — 시작을 흩어서 정상 상태를 재게 한다.
    time.sleep(random.uniform(0, 1.0 / fps))

    interval = 1.0 / fps
    i = 0
    hdr = {"Authorization": "Bearer " + token}

    # 🔴 **분석기가 붙기를 기다린다.** 세션 생성 응답은 즉시 오지만 `StartAnalysis` 는
    #    afterCommit + @Async 로 «그 뒤에» 나간다(ExerciseAnalysisService:210-217). 그래서
    #    생성 직후 프레임은 «분석기가 없습니다» 로 거절되고, 그 응답은 `classify()` 에서
    #    **nolease** 가 된다 — 즉 **기동 경합이 «풀에 자리 없음» 이라는 용량 신호로 위장한다.**
    #    2026-08-16 EC2 에서 실측: 대기 0s 거절 → 2s 성공.
    #    여기서 기다린 시간은 측정 구간 밖이다. 못 붙으면 그 세션은 setup_fail 이다.
    deadline = time.monotonic() + attach_sec
    while True:
        st, tx = http(ai + "/api/v1/pose", "POST",
                      {"image": frames[0], "exercise_type": "squat",
                       "session_id": sid, "timestamp_sec": 0.0}, hdr)
        if classify(st, tx) != "nolease":
            break
        if time.monotonic() >= deadline:
            with LOCK:
                SETUP_FAIL.append(f"[{idx}] 세션 {sid}: {attach_sec}s 안에 분석기가 안 붙었다")
            return
        time.sleep(0.5)   # AI 의 유입 간격 상한(300ms)보다 넉넉히
    while not STOP.is_set():
        due = time.monotonic()
        payload = {"image": frames[i % len(frames)], "exercise_type": "squat",
                   "session_id": sid, "timestamp_sec": i * interval}
        s = time.monotonic()
        status, text = http(ai + "/api/v1/pose", "POST", payload, hdr)
        ms = (time.monotonic() - s) * 1000.0
        with LOCK:
            ROWS.append((round(s - t0, 3), idx, round(ms, 2), classify(status, text)))
        i += 1
        # 3fps 를 «유지» 한다. 응답이 느려지면 간격이 밀리는데, 그때 따라잡으려 몰아 쏘면
        # 재는 것이 용량이 아니라 «몰아치기» 가 된다. 밀린 만큼은 버린다.
        lag = time.monotonic() - (due + interval)
        if lag < 0:
            time.sleep(-lag)

    http(base + f"/sessions/{sid}/end", "PATCH", None, {"Authorization": "Bearer " + tok})


def pct(v, p):
    if not v:
        return 0.0
    k = max(0, min(len(v) - 1, int(round((p / 100.0) * (len(v) - 1)))))
    return sorted(v)[k]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True, help="Spring 주소 (예: http://10.0.0.5:8080)")
    ap.add_argument("--ai", required=True, help="AI 주소 (예: http://10.0.0.5:8000)")
    ap.add_argument("--token", required=True, help="AI_PUBLIC_TOKEN")
    ap.add_argument("--frames", default="frames.json")
    ap.add_argument("--sessions", type=int, required=True)
    ap.add_argument("--dur", type=int, default=60, help="측정 구간(초)")
    # 분석기가 붙기를 기다리는 상한. 이 시간은 측정 구간 밖이다 — session_worker 주석 참고.
    ap.add_argument("--attach-sec", type=float, default=20.0, dest="attach_sec")
    ap.add_argument("--fps", type=float, default=3.0)
    ap.add_argument("--pw", default="P@ssw0rd!")
    ap.add_argument("--exercise-id", type=int, default=1)
    ap.add_argument("--out", default="")
    ap.add_argument("--label", default="")
    a = ap.parse_args()

    with open(a.frames) as fp:
        blob = json.load(fp)
    frames, meta = blob["frames"], blob.get("meta", {})
    print(f"프레임 {len(frames)}장 (무릎 {meta.get('knee_deg_min')}~{meta.get('knee_deg_max')}°, "
          f"검출 {meta.get('detect_ok')}/{meta.get('n')})")

    print(f"준비 — 계정 {a.sessions}개")
    toks = []
    for i in range(a.sessions):
        tok, err = setup_account(a.base, i, a.pw)
        if tok is None:
            print(f"🔴 계정 {i} 준비 실패 — {err}")
            sys.exit(1)
        toks.append(tok)

    print(f"측정 — 동시 세션 {a.sessions} · {a.fps}fps · {a.dur}초")
    t0 = time.monotonic()
    # 🔴 **벽시계 시각을 같이 잡는다** (2026-08-16, 설계 §4 대조에서 잡혔다).
    #    요청 표는 t0 기준 상대초인데 `docker stats` 샘플러는 epoch 로 찍는다
    #    (`coresidency_sweep.sh` start_stats). 둘을 잇는 축이 없으면 **포화 구간의
    #    컨테이너별 CPU 를 사후에 못 자른다** — 그게 바로 Q2(무엇이 먼저 뺏는가)의 답이고,
    #    從 R5(#229)의 «N=0 기동 직후 RSS» 구간도 같은 이유로 못 집는다.
    #    판 전체 평균으로 뭉개면 워밍업·램프 구간이 섞인다.
    epoch0 = time.time()
    ths = [threading.Thread(target=session_worker,
                            args=(i, a.base, a.ai, toks[i], a.exercise_id, frames, a.fps, t0, a.token,
                                  a.attach_sec),
                            daemon=True)
           for i in range(a.sessions)]
    for t in ths:
        t.start()
    time.sleep(a.dur)
    STOP.set()
    for t in ths:
        t.join(timeout=15)

    if SETUP_FAIL:
        print(f"🔴 세션 생성 실패 {len(SETUP_FAIL)}건 — 이 판의 «동시 세션 수» 는 목표값이 아니다")
        for m in SETUP_FAIL[:5]:
            print("   " + m)

    # 🔴 **앞뒤 5초는 버린다.** 세션이 붙고 빠지는 구간이라 정상 상태가 아니다.
    warm = [r for r in ROWS if 5.0 <= r[0] <= a.dur - 5.0]
    if not warm:
        print("🔴 정상 상태 구간에 요청이 없다 — 판 무효")
        sys.exit(1)
    ms = [r[2] for r in warm]
    kinds = {}
    for r in warm:
        kinds[r[3]] = kinds.get(r[3], 0) + 1
    span = max(r[0] for r in warm) - min(r[0] for r in warm) or 1.0
    ok = kinds.get("ok", 0)

    # 정상 상태 구간의 **절대** 경계. `docker stats` 를 이 구간으로 잘라야 귀속이 성립한다.
    warm_lo = epoch0 + min(r[0] for r in warm)
    warm_hi = epoch0 + max(r[0] for r in warm)

    print()
    print(f"  측정창(epoch) 시작 {epoch0:.1f} · 정상구간 {warm_lo:.1f} ~ {warm_hi:.1f}"
          f"  ← stats_*.tsv 를 이 구간으로 자를 것")
    print(f"  요청 {len(warm)} (정상구간 {span:.0f}s) · 처리 {len(warm)/span:.1f}/s · 검출성공 {ok/len(warm)*100:.1f}%")
    print(f"  지연 p50 {pct(ms,50):.0f}ms · p95 {pct(ms,95):.0f}ms · p99 {pct(ms,99):.0f}ms · 평균 {statistics.mean(ms):.0f}ms")
    print(f"  분류 {kinds}")
    if kinds.get("nolease"):
        print("  🔴 nolease 가 있다 — 풀에 자리가 없다. «용량» 이지 «품질» 이 아니다")
    if kinds.get("nopose"):
        print("  🔴 nopose 가 있다 — 검출이 깨졌다. #164 가 고친 지표가 다시 무너진 것일 수 있다")

    if a.out:
        with open(a.out, "w") as fp:
            # t_abs 는 **뒤에** 붙인다 — 앞에 끼우면 이 표를 읽는 눈·도구가 열을 하나씩 밀려 읽는다.
            fp.write("t_rel\tsession\tms\toutcome\tt_abs\n")
            for r in ROWS:
                fp.write(f"{r[0]}\t{r[1]}\t{r[2]}\t{r[3]}\t{epoch0 + r[0]:.3f}\n")
        summ = a.out.replace(".tsv", "") + "_summary.tsv"
        with open(summ, "w") as fp:
            # 🔴 새 열은 **끝에만** 붙인다. `coresidency_sweep.sh` 가 이 표의 $5~$13 을 위치로
            #    읽어 본 표에 옮기므로(run_one), 중간에 끼우면 표가 조용히 어긋난다.
            fp.write("label\tsessions\tfps\tdur\treq\trps\tdetect_pct\tp50\tp95\tp99\tnolease\tnopose\tsetup_fail\t"
                     "t0_epoch\twarm_lo_epoch\twarm_hi_epoch\n")
            fp.write(f"{a.label}\t{a.sessions}\t{a.fps}\t{a.dur}\t{len(warm)}\t{len(warm)/span:.2f}\t"
                     f"{ok/len(warm)*100:.2f}\t{pct(ms,50):.1f}\t{pct(ms,95):.1f}\t{pct(ms,99):.1f}\t"
                     f"{kinds.get('nolease',0)}\t{kinds.get('nopose',0)}\t{len(SETUP_FAIL)}\t"
                     f"{epoch0:.3f}\t{warm_lo:.3f}\t{warm_hi:.3f}\n")
        print(f"  → {a.out} · {summ}")


if __name__ == "__main__":
    main()
