"""Compare foreground (screen on, app visible) vs background (screen off / app hidden) rows of one session.

    python -m mhtools.compare_modes ../sessions/sessions/<session>
"""
from __future__ import annotations

import sys

from .common import Session, fmt, fnum, percentile


def main() -> None:
    s = Session(sys.argv[1])
    groups = {"foreground (app visible)": [], "background (screen off / app hidden)": []}
    for r in s.frames:
        if None in r.values():
            continue  # incomplete last row (file pulled while being written)
        size = r.get("image_size") or ""
        groups["background (screen off / app hidden)" if size.endswith("-background") else "foreground (app visible)"].append(r)
    for name, g in groups.items():
        if not g:
            print(f"\n== {name}: no rows"); continue
        t = [fnum(r["t_capture_us"]) for r in g]
        secs: dict[int, int] = {}
        for x in t:
            secs[int(x // 1e6)] = secs.get(int(x // 1e6), 0) + 1
        rates = sorted(secs.values())[1:-1] or list(secs.values())
        sent = [r for r in g if fnum(r["t_send_us"]) > 0]
        mp = [(fnum(r["t_result_us"]) - fnum(r["t_submit_us"])) / 1000 for r in g]
        print(f"\n== {name}: {len(g)} pose results over ~{len(secs)} s")
        print(f"  pose results/s: median {fmt(percentile(rates, .5), 0)}, 10th pct {fmt(percentile(rates, .1), 0)}")
        print(f"  camera pipeline p50 {fmt(percentile([fnum(r['capture_to_arrival_ms']) for r in g], .5))} ms; "
              f"MediaPipe p50 {fmt(percentile(mp, .5))} p95 {fmt(percentile(mp, .95))} ms")
        print(f"  capture→send p50 {fmt(percentile([(fnum(r['t_send_us']) - fnum(r['t_capture_us'])) / 1000 for r in sent], .5))} ms; "
              f"frames with a skeleton {len(sent)}/{len(g)}")
        print(f"  key joints visible p50 {fmt(percentile([fnum(r['key_joints_visible']) for r in g], .5), 0)}; "
              f"exposure p50 {fmt(percentile([fnum(r['exposure_us']) / 1000 for r in g], .5))} ms")
        print(f"  thermal headroom max {fmt(max(fnum(r['thermal_headroom']) for r in g), 2)}; statuses {sorted(set(r['thermal_status'] for r in g))}")
        lo, hi = min(t), max(t)
        tv = [r for r in s.tv_stats if lo <= fnum(r["t_capture_us"]) <= hi]
        if tv:
            rx = [(fnum(r["tv_receive_us"]) - fnum(r["t_capture_us"])) / 1000 for r in tv]
            print(f"  TV capture→receive p50 {fmt(percentile(rx, .5))} p95 {fmt(percentile(rx, .95))} max {fmt(max(rx), 0)} ms; "
                  f"clock RTT p50 {fmt(percentile([fnum(r['rtt_us']) / 1000 for r in tv], .5))} ms; samples {len(tv)}")
        else:
            print("  TV: no samples in this period")


if __name__ == "__main__":
    main()
