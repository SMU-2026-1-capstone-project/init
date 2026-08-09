#!/usr/bin/env python3
"""
Prometheus query_range 결과(JSON)를 SVG 꺾은선으로 그린다. 의존성 없음.

## 왜 matplotlib 이 아닌가

이 결과를 재현하는 사람이 pip install 없이 바로 다시 그릴 수 있어야 한다.
그리고 SVG 는 GitHub 에서 그대로 렌더된다 — 캡처 이미지와 달리 **원본 수치에서
다시 만들어진다는 게 파일 자체로 보인다.**

## 사용

    python plot.py --from 10:30:48 --to 10:41:48   # 스윕 v2 구간 (이 폴더의 커밋본)
    python plot.py                                 # 창 전체 — ⚠️ 아래를 읽을 것
    python plot.py --dir other/metrics --from ...

시각은 **UTC** 이며 `HH:MM` · `HH:MM:SS` · epoch 초를 받는다. 날짜는 데이터의 첫
샘플에서 가져오므로 자정을 넘는 창은 epoch 로 줘야 한다.

## ⚠️ 구간을 지정하지 않으면 y축이 다른 실험에 지배된다 (이슈 #140)

`metrics/` 의 query_range 창은 **09:52~10:43(51분)** 인데 그 안에 서로 다른 실험이
섞여 있다 — v1 단발(10:12·10:14) · R=25 대조군(10:24·10:26) · 본 스윕 v2(10:31~10:41).

구간 없이 그리면 축이 **전 구간 min/max** 로 잡힌다. 예를 들어 커넥션 획득 대기의
전 구간 최대 7.281초는 **10:24 의 R=25 대조군** 값이고, 스윕 구간의 최대는 0.96초다.
그러면 정작 보려는 구간이 그래프 아래 13% 에 눌려 사실상 안 보인다.

이건 가상의 위험이 아니라 **실제로 오독을 낳았다** — 결과 문서 초판이 그 7.28초를
스윕의 값으로 인용했다가 철회했다(README §5 ④). 그림도 같은 축을 쓰고 있었으므로
**그림을 봐도 그 오독이 교정되지 않았다.** 그래서 이제 렌더된 SVG 는 부제에 **자기가
어느 구간인지**를 적는다.

## ⚠️ 스크레이프 해상도가 판 길이보다 성기다

스크레이프 간격이 **15초**인데 판 하나가 **9.6~9.8초**다. 판별로 잘라 보면 샘플이
0~1개인 판이 나온다. 이건 그리기 문제가 아니라 **데이터에 없는 것**이고 plot.py 가
채워줄 수 없다.

대신 부제에 `샘플 31/44` 처럼 **실제 개수와 «빈틈 없이 찍혔다면 몇 개일지»** 를 나란히
적는다. 뒤엣값은 관측된 스크레이프 간격에서 유도한 것이지 «이만큼은 있어야 한다» 는
기준선이 아니다 — 차이의 대부분은 백엔드 재시작으로 빠진 스크레이프다.

⚠️ **«샘플 몇 개 미만이면 못 믿는다» 같은 임계는 걸지 않았다.** 그 숫자를 정할 근거가
없다. 대신 «선이 그려지는가» 라는 사실만 표시한다 — 꺾은선은 점 2개부터 그려지므로,
점이 1개인 계열은 부제에 «선이 아니라 점이다» 로 뜬다. 판단은 보는 사람이 한다.

⚠️ 그래프에 보이는 톱니·끊김은 대부분 **백엔드 재시작**이다. 스윕이 풀 크기를
바꿀 때마다 컨테이너를 새로 띄우므로 그 구간 지표가 사라진다. 고장이 아니다.
"""
import argparse
import datetime
import json
import os
import glob
import re

W, H = 960, 300
PAD_L, PAD_R, PAD_T, PAD_B = 70, 190, 48, 40
COLORS = ["#2563eb", "#dc2626", "#059669", "#d97706", "#7c3aed", "#0891b2"]

# 꺾은선은 점 2개부터 그려진다. 이건 임의 기준이 아니라 렌더러의 사실이라, 점이 1개인
# 계열은 «선이 없는 그림» 이 된다 — 그 사실만 적는다. «몇 개부터 적은가» 같은 임계는
# 근거가 없어 걸지 않고, 대신 샘플 수와 «스크레이프 간격으로 채워졌을 때의 개수» 를
# 나란히 보여줘서 판단은 보는 사람이 하게 한다.
MIN_POINTS_FOR_LINE = 2


def load(path):
    """query_range JSON -> [(label, [(ts, val), ...]), ...]"""
    with open(path, encoding="utf-8") as fp:
        doc = json.load(fp)
    out = []
    for s in doc.get("data", {}).get("result", []):
        m = dict(s.get("metric") or {})
        m.pop("__name__", None)
        m.pop("application", None)
        m.pop("instance", None)
        m.pop("job", None)
        label = ",".join(f"{k}={v}" for k, v in sorted(m.items())) or "value"
        pts = []
        for ts, v in s.get("values", []):
            try:
                fv = float(v)
            except ValueError:
                continue
            pts.append((float(ts), fv))
        if pts:
            out.append((label, pts))
    return out


def clip(series, lo, hi):
    """[lo, hi] 밖의 점을 버린다. 남은 점이 없는 계열은 통째로 뺀다."""
    out = []
    for label, pts in series:
        kept = [(t, v) for t, v in pts if lo <= t <= hi]
        if kept:
            out.append((label, kept))
    return out


def scrape_step(series):
    """가장 흔한 샘플 간격(초). 재시작 공백에 끌려가지 않도록 최빈값을 쓴다."""
    diffs = {}
    for _, pts in series:
        for (a, _), (b, _) in zip(pts, pts[1:]):
            d = round(b - a)
            if d > 0:
                diffs[d] = diffs.get(d, 0) + 1
    if not diffs:
        return None
    return max(diffs, key=diffs.get)


def utc(ts):
    return datetime.datetime.fromtimestamp(ts, datetime.timezone.utc)


def parse_when(s, ref_ts):
    """'HH:MM' · 'HH:MM:SS' (UTC, ref_ts 의 날짜 기준) 또는 epoch 초 -> float"""
    s = s.strip()
    if re.fullmatch(r"\d+(\.\d+)?", s):
        return float(s)
    m = re.fullmatch(r"(\d{1,2}):(\d{2})(?::(\d{2}))?", s)
    if not m:
        raise SystemExit(f"시각을 못 읽었다: {s!r} — 'HH:MM', 'HH:MM:SS', epoch 초만 받는다")
    return utc(ref_ts).replace(
        hour=int(m.group(1)), minute=int(m.group(2)),
        second=int(m.group(3) or 0), microsecond=0,
    ).timestamp()


def esc(s):
    return (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))


def fmt(v):
    a = abs(v)
    if a >= 1e9: return f"{v/1e9:.1f}G"
    if a >= 1e6: return f"{v/1e6:.1f}M"
    if a >= 1e3: return f"{v/1e3:.1f}k"
    if a >= 1:   return f"{v:.1f}"
    return f"{v:.3f}"


def render(series, title, out, window=None, step=None):
    """window=(x0, x1) 를 주면 축을 그 구간으로 고정한다.

    축을 데이터 min/max 가 아니라 «요청한 구간» 으로 잡는 것이 핵심이다. 그래야
    같은 창에 섞인 다른 실험의 최댓값이 이 그림의 y축을 정하지 못한다.
    """
    xs = [t for _, pts in series for t, _ in pts]
    ys = [v for _, pts in series for _, v in pts]
    if not xs:
        return False
    x0, x1 = window if window else (min(xs), max(xs))
    y0, y1 = min(ys), max(ys)
    if y1 == y0:
        y1 = y0 + 1
    if x1 == x0:
        x1 = x0 + 1
    pw, ph = W - PAD_L - PAD_R, H - PAD_T - PAD_B

    def px(t): return PAD_L + (t - x0) / (x1 - x0) * pw
    def py(v): return PAD_T + ph - (v - y0) / (y1 - y0) * ph

    # 부제 — 이 그림이 «어느 구간» 인지 그림 자체가 말하게 한다(이슈 #140).
    n = len(xs)
    head = "%s ~ %s UTC · " % (utc(x0).strftime("%H:%M:%S"), utc(x1).strftime("%H:%M:%S"))
    if step:
        # 빈틈 없이 찍혔다면 몇 개일지 — 관측된 간격에서 유도한 값이지 기준선이 아니다.
        # 실제가 이보다 적으면 그만큼 스크레이프가 빠진 것이다(대개 백엔드 재시작).
        full = len(series) * (int((x1 - x0) // step) + 1)
        sub = head + "샘플 %d/%d개 · 스크레이프 %ds" % (n, full, step)
    else:
        sub = head + "샘플 %d개" % n
    # 선이 없는 계열이 있는지 — 임계가 아니라 «그려지는가» 라는 사실이다.
    lineless = sum(1 for _, pts in series if len(pts) < MIN_POINTS_FOR_LINE)
    if lineless:
        sub += "  ⚠️ %d개 계열은 점 1개 — 선이 아니라 점이다" % lineless
    if window is None:
        sub += "  ⚠️ 구간 미지정 — y축이 창 전체에 지배된다"

    L = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" '
         'viewBox="0 0 %d %d" font-family="ui-sans-serif,system-ui,sans-serif">' % (W, H, W, H)]
    L.append('<rect width="%d" height="%d" fill="#ffffff"/>' % (W, H))
    L.append('<text x="%d" y="20" font-size="14" font-weight="600" fill="#111827">%s</text>'
             % (PAD_L, esc(title)))
    L.append('<text x="%d" y="36" font-size="11" fill="%s">%s</text>'
             % (PAD_L, "#b45309" if (lineless or window is None) else "#6b7280", esc(sub)))
    # y 격자 5칸
    for i in range(5):
        v = y0 + (y1 - y0) * i / 4
        y = py(v)
        L.append('<line x1="%d" y1="%.1f" x2="%.1f" y2="%.1f" stroke="#e5e7eb"/>'
                 % (PAD_L, y, PAD_L + pw, y))
        L.append('<text x="%d" y="%.1f" font-size="11" text-anchor="end" fill="#6b7280">%s</text>'
                 % (PAD_L - 8, y + 4, fmt(v)))
    # x 축 라벨 = UTC 시각 (판 경계와 대조하려면 절대 시각이어야 한다)
    for i in range(5):
        t = x0 + (x1 - x0) * i / 4
        L.append('<text x="%.1f" y="%d" font-size="11" text-anchor="middle" fill="#6b7280">%s</text>'
                 % (px(t), H - 14, utc(t).strftime("%H:%M:%S")))
    for i, (label, pts) in enumerate(series[:6]):
        c = COLORS[i % len(COLORS)]
        # 90초 이상 벌어지면 선을 끊는다 — 백엔드 재시작 구간을 잇지 않기 위해
        segs, cur = [], []
        prev = None
        for t, v in pts:
            if prev is not None and t - prev > 90:
                if len(cur) > 1: segs.append(cur)
                cur = []
            cur.append((t, v)); prev = t
        if len(cur) > 1: segs.append(cur)
        for seg in segs:
            d = " ".join(("M" if k == 0 else "L") + f"{px(t):.1f},{py(v):.1f}"
                         for k, (t, v) in enumerate(seg))
            L.append('<path d="%s" fill="none" stroke="%s" stroke-width="1.6"/>' % (d, c))
        # 점이 1개뿐인 구간은 선이 안 그려진다 — 있었다는 사실은 남겨야 한다
        for t, v in pts:
            L.append('<circle cx="%.1f" cy="%.1f" r="1.8" fill="%s"/>' % (px(t), py(v), c))
        ly = PAD_T + 6 + i * 17
        L.append('<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="%s" stroke-width="3"/>'
                 % (W - PAD_R + 10, ly, W - PAD_R + 32, ly, c))
        L.append('<text x="%d" y="%d" font-size="11" fill="#374151">%s</text>'
                 % (W - PAD_R + 38, ly + 4, esc(label[:26])))
    L.append("</svg>")
    with open(out, "w", encoding="utf-8") as fp:
        fp.write("\n".join(L))
    return True


TITLES = {
    "hikaricp_connections_pending": "커넥션 대기(pending) — 0 에 붙어 있으면 풀은 병목이 아니다",
    "hikaricp_connections_active": "활성 커넥션",
    "hikaricp_connections_idle": "유휴 커넥션",
    "hikaricp_connections_acquire_seconds_max": "커넥션 획득 대기 최대(초)",
    "hikaricp_connections_timeout_total": "커넥션 타임아웃 누적",
    "hikaricp_connections_max": "풀 상한(설정값)",
    "system_cpu_usage": "app 인스턴스 system CPU (0~1)",
    "process_cpu_usage": "백엔드 프로세스 CPU (0~1)",
    "jvm_memory_used_bytes": "JVM 메모리 사용",
    "shadowfit_pose_batch_frames_sum": "pose 배치 프레임 누적(수신/저장)",
}


def main():
    ap = argparse.ArgumentParser(
        description="query_range JSON -> SVG. 구간(--from/--to)을 주지 않으면 "
                    "y축이 창 전체에 지배된다(이슈 #140).")
    ap.add_argument("--dir", default=os.path.join(os.path.dirname(__file__), "metrics"))
    ap.add_argument("--from", dest="t_from", metavar="HH:MM[:SS]|EPOCH",
                    help="구간 시작 (UTC). 생략하면 데이터 시작")
    ap.add_argument("--to", dest="t_to", metavar="HH:MM[:SS]|EPOCH",
                    help="구간 끝 (UTC). 생략하면 데이터 끝")
    args = ap.parse_args()

    paths = sorted(glob.glob(os.path.join(args.dir, "m-*.json")))
    if not paths:
        raise SystemExit(f"{args.dir} 에 m-*.json 이 없다")

    loaded = [(os.path.basename(p)[2:-5], load(p)) for p in paths]
    loaded = [(n, s) for n, s in loaded if s]
    if not loaded:
        raise SystemExit("읽을 수 있는 계열이 하나도 없다")

    all_ts = [t for _, s in loaded for _, pts in s for t, _ in pts]
    data_lo, data_hi = min(all_ts), max(all_ts)
    lo = parse_when(args.t_from, data_lo) if args.t_from else data_lo
    hi = parse_when(args.t_to, data_lo) if args.t_to else data_hi
    if hi <= lo:
        raise SystemExit(f"--to 가 --from 보다 앞이거나 같다: {utc(lo)} ~ {utc(hi)}")
    if hi < data_lo or lo > data_hi:
        raise SystemExit(
            "요청한 구간이 데이터 창과 전혀 겹치지 않는다.\n"
            f"  요청 : {utc(lo)} ~ {utc(hi)}\n"
            f"  데이터: {utc(data_lo)} ~ {utc(data_hi)}\n"
            "  (epoch 로 줬다면 날짜가 맞는지 볼 것 — 초 단위라 1년쯤 어긋나도 형태는 멀쩡하다)")

    window = (lo, hi) if (args.t_from or args.t_to) else None
    print("데이터 창 : %s ~ %s UTC" % (utc(data_lo).strftime("%H:%M:%S"),
                                       utc(data_hi).strftime("%H:%M:%S")))
    if window:
        print("그릴 구간 : %s ~ %s UTC (%.1f분)"
              % (utc(lo).strftime("%H:%M:%S"), utc(hi).strftime("%H:%M:%S"), (hi - lo) / 60))
    else:
        print("⚠️ 구간 미지정 — 창 전체를 그린다. 이 창에 여러 실험이 섞여 있으면")
        print("   y축이 «다른 실험» 의 최댓값에 지배된다(이슈 #140). --from/--to 를 줄 것.")

    n = 0
    for name, series in loaded:
        step = scrape_step(series)
        picked = clip(series, lo, hi) if window else series
        if not picked:
            print(f"skip {name} (구간 안에 샘플 0개)")
            continue
        cnt = sum(len(p) for _, p in picked)
        out = os.path.join(args.dir, "..", f"{name}.svg")
        if render(picked, TITLES.get(name, name), out, window=window, step=step):
            lineless = sum(1 for _, p in picked if len(p) < MIN_POINTS_FOR_LINE)
            warn = f"  ⚠️ {lineless}개 계열은 점 1개" if lineless else ""
            print(f"wrote {os.path.basename(out)}  ({len(picked)} 계열, 샘플 {cnt}개){warn}")
            n += 1
    print(f"총 {n} 개")
    # 한 장도 못 그렸는데 exit 0 으로 끝나면, 디스크에 남은 «옛 SVG» 가 방금 만든
    # 것처럼 보인다. 아무것도 안 한 것과 성공을 구분한다.
    if n == 0:
        raise SystemExit("🔴 한 장도 그리지 못했다 — 기존 SVG 는 갱신되지 않은 채 남아 있다")


if __name__ == "__main__":
    main()
