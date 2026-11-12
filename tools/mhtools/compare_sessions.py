"""Side-by-side latency/throughput table for several sessions (e.g. an A/B matrix of settings).

    python -m mhtools.compare_sessions ../sessions/sessions/2026111* [--skip-s 5]

--skip-s drops the first seconds of each session (camera start-up, model warm-up).
"""
from __future__ import annotations

import argparse
import collections
from pathlib import Path

from .common import Session, fmt, fnum, percentile


def row(s: Session, skip_s: float) -> dict:
    fr = s.frames
    if not fr:
        return {}
    t0 = fnum(fr[0]["t_capture_us"])
    fr = [r for r in fr if fnum(r["t_capture_us"]) - t0 >= skip_s * 1e6]
    if not fr:
        return {}
    last = fr[-1]
    per_sec = collections.Counter(int((fnum(r["t_capture_us"]) - t0) // 1e6) for r in fr)
    rates = [per_sec[k] for k in sorted(per_sec)][1:-1] or list(per_sec.values())
    p50 = lambda xs: percentile(list(xs), 0.5)
    has_submit = "t_submit_us" in last
    return {
        "session": s.path.name,
        "config": f"{last.get('model')}/{last.get('delegate')}/{last.get('image_size', '?')}/{last.get('exposure_mode')}",
        "secs": (fnum(fr[-1]["t_capture_us"]) - fnum(fr[0]["t_capture_us"])) / 1e6,
        "pose_fps": p50(rates),
        "cam_pipe": p50(fnum(r["capture_to_arrival_ms"]) for r in fr),
        "pre": p50((fnum(r["t_submit_us"]) - fnum(r["t_arrival_us"])) / 1000 for r in fr) if has_submit else float("nan"),
        "call": p50(fnum(r["detect_call_ms"]) for r in fr) if has_submit else float("nan"),
        "mp": p50((fnum(r["t_result_us"]) - fnum(r["t_submit_us"])) / 1000 for r in fr) if has_submit else float("nan"),
        "send_p50": p50((fnum(r["t_send_us"]) - fnum(r["t_capture_us"])) / 1000 for r in fr if fnum(r["t_send_us"]) > 0),
        "send_p95": percentile([(fnum(r["t_send_us"]) - fnum(r["t_capture_us"])) / 1000 for r in fr if fnum(r["t_send_us"]) > 0], 0.95),
        "exp_ms": p50(fnum(r["exposure_us"]) / 1000 for r in fr if fnum(r["exposure_us"]) > 0),
        "thermal": max((fnum(r["thermal_headroom"]) for r in fr), default=float("nan")),
        "visible": p50(fnum(r["key_joints_visible"]) for r in fr),
    }


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("sessions", nargs="+")
    ap.add_argument("--skip-s", type=float, default=5.0)
    a = ap.parse_args()
    print("| session | model/delegate/image/exposure | s | pose fps | camera pipe | preprocess | detect call | MediaPipe | capture→send p50 / p95 | exposure | thermal max | key joints |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for p in a.sessions:
        if not (Path(p) / "frames.csv").exists():
            continue
        r = row(Session(p), a.skip_s)
        if not r:
            continue
        print(f"| {r['session']} | {r['config']} | {r['secs']:.0f} | {fmt(r['pose_fps'], 0)} | {fmt(r['cam_pipe'])} | {fmt(r['pre'])} | "
              f"{fmt(r['call'])} | {fmt(r['mp'])} | **{fmt(r['send_p50'])}** / {fmt(r['send_p95'])} | {fmt(r['exp_ms'])} | {fmt(r['thermal'], 2)} | {fmt(r['visible'], 0)} |")
    print("\nAll times in ms (medians unless marked). camera pipe = sensor timestamp → app; capture→send = full phone path.")


if __name__ == "__main__":
    main()
