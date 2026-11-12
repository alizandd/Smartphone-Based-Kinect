"""Score detected gestures against hand labels: precision, recall, F1, latency, idle false positives.

    python -m mhtools.score_events --events sessions/X/events.jsonl --labels labels.csv \
        [--offset-us N] [--collapse punch_l,punch_r=punch] [--before-ms 150] [--after-ms 300]

labels.csv columns:  t_s,gesture[,note]
  t_s      motion onset in seconds on the label timeline (video time if you labelled the video)
  gesture  jump | squat | step_l | step_r | lean_l | lean_r | punch_l | punch_r | idle_start | idle_end
           idle_start/idle_end bracket distractor periods used for the false-positive rate.
--offset-us converts label time to the phone clock: t_phone_us = t_s * 1e6 + offset
(sync_video.py prints this offset from the flash at the start of each recording).
"""
from __future__ import annotations

import argparse
import csv
import math
from collections import defaultdict
from pathlib import Path

from .common import fmt, percentile, read_jsonl


def load_labels(path: Path, offset_us: float) -> list[tuple[float, str]]:
    with path.open(newline="") as f:
        return [(float(r["t_s"]) * 1e6 + offset_us, r["gesture"].strip()) for r in csv.DictReader(f)]


def load_detections(path: Path) -> list[tuple[float, str]]:
    return [(float(e["t_source_us"]), e["id"]) for e in read_jsonl(path)
            if e.get("type") == "gesture" and e.get("phase") == "start"]


def collapse_map(spec: list[str]) -> dict[str, str]:
    m: dict[str, str] = {}
    for s in spec:
        src, dst = s.split("=")
        for name in src.split(","):
            m[name.strip()] = dst.strip()
    return m


def score(labels, detections, before_us=150_000, after_us=300_000, collapse=None) -> dict:
    collapse = collapse or {}
    c = lambda g: collapse.get(g, g)
    idle = []
    start = None
    gl = []
    for t, g in sorted(labels):
        if g == "idle_start":
            start = t
        elif g == "idle_end" and start is not None:
            idle.append((start, t)); start = None
        else:
            gl.append((t, c(g)))
    dets = sorted((t, c(g)) for t, g in detections)
    classes = sorted({g for _, g in gl} | {g for _, g in dets})
    res = {}
    for cls in classes:
        L = [t for t, g in gl if g == cls]
        D = [t for t, g in dets if g == cls]
        used = [False] * len(D)
        lat = []
        tp = 0
        for lt in L:
            best, bi = None, -1
            for i, dt in enumerate(D):
                if used[i] or dt < lt - before_us or dt > lt + after_us:
                    continue
                if best is None or abs(dt - lt) < abs(best - lt):
                    best, bi = dt, i
            if bi >= 0:
                used[bi] = True; tp += 1; lat.append((best - lt) / 1000)
        fp_all = [D[i] for i in range(len(D)) if not used[i]]
        fp_idle = sum(1 for t in fp_all if any(a <= t <= b for a, b in idle))
        fn = len(L) - tp
        p = tp / (tp + len(fp_all)) if tp + len(fp_all) else math.nan
        r = tp / len(L) if L else math.nan
        f1 = 2 * p * r / (p + r) if p == p and r == r and p + r else math.nan
        res[cls] = dict(labels=len(L), detections=len(D), tp=tp, fp=len(fp_all), fn=fn, precision=p, recall=r, f1=f1,
                        latency_p50_ms=percentile(lat, 0.5), latency_p95_ms=percentile(lat, 0.95), fp_in_idle=fp_idle)
    idle_min = sum(b - a for a, b in idle) / 60e6
    res["_idle_minutes"] = idle_min
    return res


def render(res: dict) -> str:
    rows = ["| Gesture | labels | detected | TP | FP | FN | precision | recall | F1 | latency p50 / p95 ms | FP in idle |",
            "|---|---|---|---|---|---|---|---|---|---|---|"]
    for cls, r in res.items():
        if cls.startswith("_"):
            continue
        rows.append(f"| {cls} | {r['labels']} | {r['detections']} | {r['tp']} | {r['fp']} | {r['fn']} | {fmt(r['precision'], 2)} | "
                    f"{fmt(r['recall'], 2)} | **{fmt(r['f1'], 2)}** | {fmt(r['latency_p50_ms'], 0)} / {fmt(r['latency_p95_ms'], 0)} | {r['fp_in_idle']} |")
    idle = res.get("_idle_minutes", 0)
    if idle:
        total_fp = sum(r["fp_in_idle"] for k, r in res.items() if not k.startswith("_"))
        rows.append(f"\nIdle time labelled: {idle:.1f} min → {total_fp / idle * 5:.2f} false positives per 5 min (target < 1).")
    return "\n".join(rows)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--events", required=True)
    ap.add_argument("--labels", required=True)
    ap.add_argument("--offset-us", type=float, default=0.0)
    ap.add_argument("--before-ms", type=float, default=150)
    ap.add_argument("--after-ms", type=float, default=300)
    ap.add_argument("--collapse", action="append", default=[], help="e.g. punch_l,punch_r=punch")
    a = ap.parse_args()
    res = score(load_labels(Path(a.labels), a.offset_us), load_detections(Path(a.events)),
                a.before_ms * 1000, a.after_ms * 1000, collapse_map(a.collapse))
    print(render(res))


if __name__ == "__main__":
    main()
