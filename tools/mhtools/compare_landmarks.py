"""Compare landmark files from different backends on the same clip (docs/03 §5.3).

    python -m mhtools.compare_landmarks A.jsonl B.jsonl [...] [--labels labels.csv --offset-us N]
                                       [--static START_S END_S]

Reports per backend:
  - detection rate of wrists and ankles (visibility ≥ 0.5) over all frames, and inside ±250 ms of
    each labelled punch/kick (the fast-motion case that decides the bake-off);
  - static jitter: std-dev of wrist/ankle pixel position over a still window (--static, seconds on
    the label timeline).
"""
from __future__ import annotations

import argparse
import csv
import math
import statistics
from pathlib import Path

from .common import fmt, read_jsonl

WRISTS, ANKLES = (15, 16), (27, 28)
FAST = {"punch_l", "punch_r", "kick_l", "kick_r", "front_kick_l", "front_kick_r", "side_kick_l", "side_kick_r"}


def load(path: Path):
    return [(r["t_us"], r["w"], r["h"], r["lm"]) for r in read_jsonl(path)]


def visible(lm, i):
    return lm is not None and lm[i] is not None and (len(lm[i]) < 4 or lm[i][3] >= 0.5)


def rate(rows, ids, windows=None):
    hit = n = 0
    for t, _, _, lm in rows:
        if windows is not None and not any(a <= t <= b for a, b in windows):
            continue
        for i in ids:
            n += 1; hit += visible(lm, i)
    return hit / n if n else math.nan


def jitter_px(rows, ids, lo, hi):
    out = []
    for i in ids:
        xs = [(lm[i][0] * w, lm[i][1] * h) for t, w, h, lm in rows if lo <= t <= hi and visible(lm, i)]
        if len(xs) > 5:
            out.append(math.hypot(statistics.pstdev(x for x, _ in xs), statistics.pstdev(y for _, y in xs)))
    return statistics.mean(out) if out else math.nan


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="+")
    ap.add_argument("--labels")
    ap.add_argument("--offset-us", type=float, default=0.0)
    ap.add_argument("--static", nargs=2, type=float, metavar=("START_S", "END_S"))
    a = ap.parse_args()
    windows = None
    if a.labels:
        with open(a.labels, newline="") as f:
            ts = [float(r["t_s"]) * 1e6 + a.offset_us for r in csv.DictReader(f) if r["gesture"].strip() in FAST]
        windows = [(t - 250_000, t + 250_000) for t in ts]
    print("| backend | frames w/ person | wrists all | ankles all | wrists in fast moves | ankles in fast moves | static jitter px |")
    print("|---|---|---|---|---|---|---|")
    for fpath in a.files:
        rows = load(Path(fpath))
        name = Path(fpath).stem
        person = sum(1 for r in rows if r[3] is not None) / len(rows) if rows else math.nan
        st = jitter_px(rows, WRISTS + ANKLES, a.static[0] * 1e6 + a.offset_us, a.static[1] * 1e6 + a.offset_us) if a.static else math.nan
        fw = rate(rows, WRISTS, windows) if windows else math.nan
        fa = rate(rows, ANKLES, windows) if windows else math.nan
        print(f"| {name} | {fmt(person * 100)}% | {fmt(rate(rows, WRISTS) * 100)}% | {fmt(rate(rows, ANKLES) * 100)}% | "
              f"{fmt(fw * 100)}% | {fmt(fa * 100)}% | {fmt(st, 2)} |")


if __name__ == "__main__":
    main()
