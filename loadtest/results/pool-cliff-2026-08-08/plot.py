#!/usr/bin/env python3
"""
Prometheus query_range 결과(JSON)를 SVG 꺾은선으로 그린다. 의존성 없음.

## 왜 matplotlib 이 아닌가

이 결과를 재현하는 사람이 pip install 없이 바로 다시 그릴 수 있어야 한다.
그리고 SVG 는 GitHub 에서 그대로 렌더된다 — 캡처 이미지와 달리 **원본 수치에서
다시 만들어진다는 게 파일 자체로 보인다.**

## 사용

    python plot.py                     # metrics/*.json -> *.svg
    python plot.py --dir other/metrics

⚠️ 그래프에 보이는 톱니·끊김은 대부분 **백엔드 재시작**이다. 스윕이 풀 크기를
바꿀 때마다 컨테이너를 새로 띄우므로 그 구간 지표가 사라진다. 고장이 아니다.
"""
import argparse
import json
import os
import glob

W, H = 960, 300
PAD_L, PAD_R, PAD_T, PAD_B = 70, 190, 34, 40
COLORS = ["#2563eb", "#dc2626", "#059669", "#d97706", "#7c3aed", "#0891b2"]


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


def esc(s):
    return (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))


def fmt(v):
    a = abs(v)
    if a >= 1e9: return f"{v/1e9:.1f}G"
    if a >= 1e6: return f"{v/1e6:.1f}M"
    if a >= 1e3: return f"{v/1e3:.1f}k"
    if a >= 1:   return f"{v:.1f}"
    return f"{v:.3f}"


def render(series, title, out):
    xs = [t for _, pts in series for t, _ in pts]
    ys = [v for _, pts in series for _, v in pts]
    if not xs:
        return False
    x0, x1 = min(xs), max(xs)
    y0, y1 = min(ys), max(ys)
    if y1 == y0:
        y1 = y0 + 1
    if x1 == x0:
        x1 = x0 + 1
    pw, ph = W - PAD_L - PAD_R, H - PAD_T - PAD_B

    def px(t): return PAD_L + (t - x0) / (x1 - x0) * pw
    def py(v): return PAD_T + ph - (v - y0) / (y1 - y0) * ph

    L = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" '
         'viewBox="0 0 %d %d" font-family="ui-sans-serif,system-ui,sans-serif">' % (W, H, W, H)]
    L.append('<rect width="%d" height="%d" fill="#ffffff"/>' % (W, H))
    L.append('<text x="%d" y="20" font-size="14" font-weight="600" fill="#111827">%s</text>'
             % (PAD_L, esc(title)))
    # y 격자 5칸
    for i in range(5):
        v = y0 + (y1 - y0) * i / 4
        y = py(v)
        L.append('<line x1="%d" y1="%.1f" x2="%.1f" y2="%.1f" stroke="#e5e7eb"/>'
                 % (PAD_L, y, PAD_L + pw, y))
        L.append('<text x="%d" y="%.1f" font-size="11" text-anchor="end" fill="#6b7280">%s</text>'
                 % (PAD_L - 8, y + 4, fmt(v)))
    # x 축 라벨 = 경과 분
    for i in range(5):
        t = x0 + (x1 - x0) * i / 4
        L.append('<text x="%.1f" y="%d" font-size="11" text-anchor="middle" fill="#6b7280">+%dm</text>'
                 % (px(t), H - 14, round((t - x0) / 60)))
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
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=os.path.join(os.path.dirname(__file__), "metrics"))
    args = ap.parse_args()
    n = 0
    for path in sorted(glob.glob(os.path.join(args.dir, "m-*.json"))):
        name = os.path.basename(path)[2:-5]
        series = load(path)
        if not series:
            print(f"skip {name} (빈 결과)")
            continue
        out = os.path.join(args.dir, "..", f"{name}.svg")
        if render(series, TITLES.get(name, name), out):
            print(f"wrote {os.path.basename(out)}  ({len(series)} 계열)")
            n += 1
    print(f"총 {n} 개")


if __name__ == "__main__":
    main()
