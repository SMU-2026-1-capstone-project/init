#!/usr/bin/env python3
"""수집해둔 Prometheus 시계열을 판별 시간 창에 붙인다.

측정이 끝나고 **인프라를 지운 뒤에** 돌린 스크립트다. 그래서 이 파일 자체가 이번 라운드의
교훈이기도 하다 — 지표를 걷어두면 인스턴스가 없어도 질문을 하나 더 물을 수 있다.
반대로, 걷어두고 **안 보면** 걷지 않은 것과 같다(§2-5).

판 시간 창은 TSV 에 안 남겼다(이번 rig 의 구멍). ghz 리포트의 `date` + `total` 로 복원한다.

사용: python analyze_metrics.py
"""
import datetime
import json
import pathlib

HERE = pathlib.Path(__file__).parent


def series(name):
    j = json.loads((HERE / "metrics" / f"{name}.json").read_text(encoding="utf-8"))
    return {tuple(sorted((k, v) for k, v in r["metric"].items()
                         if k not in ("__name__", "job", "instance"))):
            [(float(t), float(v)) for t, v in r["values"]]
            for r in j["data"]["result"]}


def runs():
    out = {}
    for f in sorted((HERE / "ghz").glob("*.json")):
        j = json.loads(f.read_text(encoding="utf-8"))
        t0 = datetime.datetime.strptime(j["date"][:19], "%Y-%m-%dT%H:%M:%S") \
            .replace(tzinfo=datetime.timezone.utc).timestamp()
        out[f.stem] = (t0, t0 + j.get("total", 0) / 1e9)
    return dict(sorted(out.items(), key=lambda kv: kv[1][0]))


def main():
    R = runs()
    lock = list(series("mysql_global_status_innodb_row_lock_waits").values())[0]
    thr = list(series("mysql_global_status_threads_running").values())[0]
    bp = series("mysql_global_status_buffer_pool_pages")
    data = next(v for k, v in bp.items() if ("state", "data") in k)
    free = next(v for k, v in bp.items() if ("state", "free") in k)

    def win(s, t0, t1):
        return [(t, v) for t, v in s if t0 <= t <= t1]

    def near(s, t):
        return min(((abs(ts - t), v) for ts, v in s), default=(0, float("nan")))[1]

    print(f"{'판':16} {'시각':>8} {'락대기증가':>10} {'threads':>8} {'bp_data':>9} {'bp_free':>8}")
    for tag, (t0, t1) in R.items():
        w = win(lock, t0, t1)
        tw = win(thr, t0, t1)
        d = f"{w[-1][1] - w[0][1]:.0f}" if len(w) >= 2 else "샘플부족"
        m = f"{sum(v for _, v in tw) / len(tw):.1f}" if tw else "-"
        ts = datetime.datetime.fromtimestamp(t0, datetime.timezone.utc).strftime("%H:%M:%S")
        print(f"{tag:16} {ts:>8} {d:>10} {m:>8} {near(data, t0):>9.0f} {near(free, t0):>8.0f}")

    print("\n⚠️ 스크레이프 5초, 판 19~68초라 판당 4~13샘플이다. 카운터 증가량은 이 격자에")
    print("   반올림되므로 «자릿수» 로 읽고 «정확한 건수» 로 읽지 말 것.")
    print("   판정에 쓴 정확한 커밋·fsync 횟수는 MySQL 에서 직접 뜬 TSV 쪽이다(_rig.sh counters()).")


if __name__ == "__main__":
    main()
