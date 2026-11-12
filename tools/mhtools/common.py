"""Loading session folders pulled from the phone, and small statistics helpers."""
from __future__ import annotations

import csv
import json
import math
from pathlib import Path
from typing import Iterable, Iterator


def percentile(values: Iterable[float], q: float) -> float:
    s = sorted(v for v in values if v is not None and math.isfinite(v))
    if not s:
        return math.nan
    k = (len(s) - 1) * q
    lo, hi = math.floor(k), math.ceil(k)
    return s[lo] if lo == hi else s[lo] + (s[hi] - s[lo]) * (k - lo)


def summary(values: Iterable[float]) -> dict:
    v = [x for x in values if x is not None and math.isfinite(x)]
    return {
        "n": len(v),
        "p50": percentile(v, 0.5),
        "p95": percentile(v, 0.95),
        "p99": percentile(v, 0.99),
        "max": max(v) if v else math.nan,
    }


def fnum(s: str) -> float:
    try:
        return float(s)
    except (TypeError, ValueError):
        return math.nan


def read_csv(path: Path) -> list[dict]:
    if not path.exists():
        return []
    with path.open(newline="") as f:
        return list(csv.DictReader(f))


def read_jsonl(path: Path) -> Iterator[dict]:
    if not path.exists():
        return
    with path.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError:
                continue  # a line still being written when the file was pulled


class Session:
    """A session folder: meta.json, frames.csv, events.jsonl, tv_stats.csv, rec_*/."""

    def __init__(self, path: str | Path):
        self.path = Path(path)
        if not (self.path / "frames.csv").exists():
            raise SystemExit(f"{self.path} does not look like a session folder (frames.csv missing)")
        meta = self.path / "meta.json"
        self.meta = json.loads(meta.read_text()) if meta.exists() else {}
        self.frames = read_csv(self.path / "frames.csv")
        self.events = list(read_jsonl(self.path / "events.jsonl"))
        self.tv_stats = read_csv(self.path / "tv_stats.csv")

    @property
    def recordings(self) -> list[Path]:
        return sorted(p for p in self.path.glob("rec_*") if p.is_dir())

    @property
    def t0_us(self) -> float:
        return fnum(self.frames[0]["t_capture_us"]) if self.frames else 0.0


def fmt(v: float, digits: int = 1) -> str:
    return "–" if v is None or not math.isfinite(v) else f"{v:.{digits}f}"
