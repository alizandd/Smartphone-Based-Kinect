"""Run pose backends on a recorded video and write landmarks.jsonl per backend (docs/03 §5.3).

    pip install -r requirements-bakeoff.txt
    python -m mhtools.bakeoff sessions/X/rec_1 --backends mediapipe_full,rtmpose_halpe26 [--offset-us N]

Output: <rec>/bakeoff/<backend>.jsonl in the same schema as the phone's landmarks.jsonl
(BlazePose 33-point layout; missing points null), timestamped in the phone clock when
--offset-us is given (from sync_video.py), else in video microseconds. Feed each file to the
Kotlin replay CLI to get events with the *same* recogniser the phone runs:

    android/gradlew -p android :core:run --args="--landmarks <file> --meta <session>/meta.json --out <dir>"

Desktop runs measure accuracy, not phone speed; on-device speed comes from frames.csv.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MP_MODELS = ROOT / "android/app/src/main/assets"

# Halpe-26 index → BlazePose-33 index
HALPE26_TO_BP = {0: 0, 1: 2, 2: 5, 3: 7, 4: 8, 5: 11, 6: 12, 7: 13, 8: 14, 9: 15, 10: 16, 11: 23, 12: 24,
                 13: 25, 14: 26, 15: 27, 16: 28, 20: 31, 21: 32, 24: 29, 25: 30}
COCO17_TO_BP = {0: 0, 1: 2, 2: 5, 3: 7, 4: 8, 5: 11, 6: 12, 7: 13, 8: 14, 9: 15, 10: 16, 11: 23, 12: 24,
                13: 25, 14: 26, 15: 27, 16: 28}


def frames(video: Path):
    import cv2
    cap = cv2.VideoCapture(str(video))
    while True:
        ok, bgr = cap.read()
        if not ok:
            return
        yield cap.get(cv2.CAP_PROP_POS_MSEC) / 1000.0, bgr


def line(t_us: int, w: int, h: int, backend: str, lm, world=None) -> str:
    return json.dumps({"t_us": t_us, "w": w, "h": h, "backend": backend, "lm": lm, "world": world}, separators=(",", ":"))


def run_mediapipe(video: Path, variant: str, offset_us: float, out):
    import mediapipe as mp
    from mediapipe.tasks.python import BaseOptions
    from mediapipe.tasks.python.vision import PoseLandmarker, PoseLandmarkerOptions, RunningMode
    model = MP_MODELS / f"pose_landmarker_{variant}.task"
    if not model.exists():
        raise SystemExit(f"{model} missing: build the Android app once (it downloads the models)")
    opts = PoseLandmarkerOptions(base_options=BaseOptions(model_asset_path=str(model)), running_mode=RunningMode.VIDEO, num_poses=1)
    name = f"mediapipe_{variant}"
    with PoseLandmarker.create_from_options(opts) as det:
        last = -1
        for t, bgr in frames(video):
            h, w = bgr.shape[:2]
            ms = max(last + 1, int(t * 1000)); last = ms
            img = mp.Image(image_format=mp.ImageFormat.SRGB, data=bgr[:, :, ::-1].copy())
            r = det.detect_for_video(img, ms)
            t_us = int(t * 1e6 + offset_us)
            if not r.pose_landmarks:
                out.write(line(t_us, w, h, name, None) + "\n"); continue
            lm = [[round(p.x, 5), round(p.y, 5), round(p.z, 5), round(p.visibility or 0, 4)] for p in r.pose_landmarks[0]]
            world = [[round(p.x, 5), round(p.y, 5), round(p.z, 5)] for p in r.pose_world_landmarks[0]] if r.pose_world_landmarks else None
            out.write(line(t_us, w, h, name, lm, world) + "\n")


def run_rtmpose(video: Path, layout: str, offset_us: float, out):
    import numpy as np
    import rtmlib
    if layout == "halpe26":
        model, mapping = rtmlib.BodyWithFeet(mode="balanced", backend="onnxruntime", device="cpu"), HALPE26_TO_BP
    else:
        model, mapping = rtmlib.Body(mode="balanced", backend="onnxruntime", device="cpu"), COCO17_TO_BP
    name = f"rtmpose_{layout}"
    for t, bgr in frames(video):
        h, w = bgr.shape[:2]
        kpts, scores = model(bgr)
        t_us = int(t * 1e6 + offset_us)
        if kpts is None or len(kpts) == 0:
            out.write(line(t_us, w, h, name, None) + "\n"); continue
        # largest person by keypoint bounding-box area
        areas = [(np.ptp(k[:, 0]) * np.ptp(k[:, 1])) for k in kpts]
        i = int(np.argmax(areas))
        lm = [None] * 33
        for src, dst in mapping.items():
            x, y = kpts[i][src]
            lm[dst] = [round(float(x) / w, 5), round(float(y) / h, 5), 0.0, round(float(min(1.0, scores[i][src])), 4)]
        out.write(line(t_us, w, h, name, lm) + "\n")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("recording")
    ap.add_argument("--backends", default="mediapipe_full,rtmpose_halpe26")
    ap.add_argument("--offset-us", type=float, default=0.0)
    a = ap.parse_args()
    rec = Path(a.recording)
    video = rec / "video.mp4"
    if not video.exists():
        raise SystemExit(f"{video} not found")
    outdir = rec / "bakeoff"; outdir.mkdir(exist_ok=True)
    for b in a.backends.split(","):
        b = b.strip()
        path = outdir / f"{b}.jsonl"
        print(f"running {b} → {path}", file=sys.stderr)
        with path.open("w") as out:
            if b.startswith("mediapipe_"):
                run_mediapipe(video, b.split("_", 1)[1], a.offset_us, out)
            elif b.startswith("rtmpose_"):
                run_rtmpose(video, b.split("_", 1)[1], a.offset_us, out)
            else:
                raise SystemExit(f"unknown backend {b}")


if __name__ == "__main__":
    main()
