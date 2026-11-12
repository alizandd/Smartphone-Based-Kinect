"""Find the sync flash in a recorded video and print the offset to the phone clock.

    python -m mhtools.sync_video sessions/X/rec_1     (needs: pip install opencv-python)

At the start of every recording, enable "Probe" on the phone and flash a light (another phone's
torch) at the camera. The phone logs the flash as a `probe` event (phone clock); this script finds
the same flash in video.mp4 (video clock). Prints `offset_us` so that
    t_phone_us = t_video_s * 1e6 + offset_us
Use it with score_events.py --offset-us and bakeoff.py --offset-us.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from .common import read_jsonl


def find_flash(video: Path, jump: float = 60, min_luma: float = 170) -> float | None:
    import cv2  # optional dependency
    cap = cv2.VideoCapture(str(video))
    baseline = None
    while True:
        ok, frame = cap.read()
        if not ok:
            return None
        t = cap.get(cv2.CAP_PROP_POS_MSEC) / 1000.0
        h, w = frame.shape[:2]
        roi = cv2.cvtColor(frame[int(h * .4):int(h * .6), int(w * .4):int(w * .6)], cv2.COLOR_BGR2GRAY)
        luma = float(roi.mean())
        if baseline is not None and luma - baseline > jump and luma > min_luma:
            return t
        baseline = luma if baseline is None else 0.9 * baseline + 0.1 * luma


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("recording", help="rec_<n> folder inside a session")
    a = ap.parse_args()
    rec = Path(a.recording)
    session = rec.parent
    events = list(read_jsonl(session / "events.jsonl"))
    start = next((e["t_us"] for e in events if e.get("type") == "marker" and e.get("event") == "record_start" and e.get("rec") == rec.name), None)
    probes = [e["t_source_us"] for e in events if e.get("type") == "probe" and (start is None or e["t_source_us"] >= start)]
    if not probes:
        raise SystemExit("No probe event after this recording started: was Probe enabled and a light flashed?")
    t_video = find_flash(rec / "video.mp4")
    if t_video is None:
        raise SystemExit("No flash found in the video")
    offset = probes[0] - t_video * 1e6
    print(json.dumps({"recording": rec.name, "flash_video_s": round(t_video, 4), "flash_phone_us": probes[0], "offset_us": round(offset)}))


if __name__ == "__main__":
    main()
