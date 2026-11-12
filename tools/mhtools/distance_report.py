"""Compare the phone's distance estimate with tape-measured floor marks.

    python -m mhtools.distance_report sessions/X --marks marks.csv

marks.csv columns: start_s,end_s,true_m
  start_s/end_s  seconds since the first frame of the session (stand still on the mark in between)
  true_m         tape-measured horizontal distance from the phone lens to the mark (feet centre)
"""
from __future__ import annotations

import argparse
import csv

from .common import Session, fmt, fnum, percentile


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("session")
    ap.add_argument("--marks", required=True)
    a = ap.parse_args()
    s = Session(a.session)
    t0 = s.t0_us
    rows = [(fnum(r["t_capture_us"]) - t0, fnum(r["distance_m"])) for r in s.frames]
    print("| true (m) | estimate p50 (m) | error (cm) | spread p05–p95 (cm) | frames |")
    print("|---|---|---|---|---|")
    errs = []
    with open(a.marks, newline="") as f:
        for m in csv.DictReader(f):
            lo, hi, true = float(m["start_s"]) * 1e6, float(m["end_s"]) * 1e6, float(m["true_m"])
            d = [v for t, v in rows if lo <= t <= hi and v == v]
            med = percentile(d, 0.5)
            err = (med - true) * 100
            errs.append(abs(err))
            print(f"| {true:.2f} | {fmt(med, 3)} | {fmt(err)} | {fmt((percentile(d, 0.05) - med) * 100)} … {fmt((percentile(d, 0.95) - med) * 100)} | {len(d)} |")
    if errs:
        worst = max(errs)
        print(f"\nWorst absolute error: {worst:.1f} cm → {'PASS' if worst <= 10 else 'FAIL'} (target ≤ 10 cm at 2–3 m)")


if __name__ == "__main__":
    main()
