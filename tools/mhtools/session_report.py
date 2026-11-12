"""Phase 0 performance report for one session folder.

    python -m mhtools.session_report sessions/20261112-190400 [--md report.md]

Covers: pose/camera FPS, inference time, phone pipeline latency, capture→TV receive/render
latency (from tv_stats.csv), thermal trend, exposure/ISO, tracking and gesture counts, and checks
the go/no-go thresholds from docs/README.md §3 that this data can answer.
"""
from __future__ import annotations

import argparse
import collections
import math
from pathlib import Path

from .common import Session, fmt, fnum, percentile, summary


def build_report(s: Session) -> tuple[str, dict]:
    fr = s.frames
    lines: list[str] = []
    out: dict = {}
    dur_s = (fnum(fr[-1]["t_capture_us"]) - fnum(fr[0]["t_capture_us"])) / 1e6 if len(fr) > 1 else 0.0
    out["duration_min"] = dur_s / 60
    m = s.meta
    lines.append(f"# Session report: {s.path.name}")
    lines.append("")
    lines.append(f"- Device: {m.get('device_model', '?')} (SDK {m.get('android_sdk', '?')}), model **{m.get('model', '?')}** on {m.get('delegate', '?')}, exposure {m.get('exposure_mode', '?')}")
    cam = m.get("camera", {})
    lines.append(f"- Camera: fps ranges {cam.get('fps_ranges', '?')}, chosen {cam.get('chosen_fps', '?')} fps, timestamp source REALTIME: {cam.get('timestamp_source_realtime', '?')}")
    lines.append(f"- Duration: {dur_s / 60:.1f} min, {len(fr)} pose results")
    lines.append("")

    # --- throughput: pose rate computed from capture timestamps (robust to HUD sampling) ---
    t = [fnum(r["t_capture_us"]) for r in fr]
    per_sec = collections.Counter(int((x - t[0]) // 1e6) for x in t) if t else collections.Counter()
    rates = [per_sec[k] for k in sorted(per_sec)][1:-1] or list(per_sec.values())
    pose = summary(rates)
    out["pose_fps_p50"] = pose["p50"]
    out["pose_fps_p05"] = percentile(rates, 0.05)
    lines.append("## Throughput")
    lines.append(f"- Pose results per second: median {fmt(pose['p50'])}, 5th percentile {fmt(out['pose_fps_p05'])}, min {fmt(min(rates) if rates else math.nan)}")
    cam_fps = summary(fnum(r["cam_fps"]) for r in fr)
    lines.append(f"- Camera frames per second (HUD meter): median {fmt(cam_fps['p50'])}")

    # --- phone latency ---
    inf = summary(fnum(r["inference_ms"]) for r in fr)
    c2a = summary(fnum(r["capture_to_arrival_ms"]) for r in fr)
    send = summary((fnum(r["t_send_us"]) - fnum(r["t_capture_us"])) / 1000 for r in fr if fnum(r["t_send_us"]) > 0)
    out.update(inference_p50=inf["p50"], camera_pipeline_p50=c2a["p50"], capture_to_send_p50=send["p50"])
    lines.append("")
    lines.append("## Phone latency (ms)")
    lines.append("| Stage | p50 | p95 | p99 |")
    lines.append("|---|---|---|---|")
    lines.append(f"| Sensor timestamp → app (camera pipeline) | {fmt(c2a['p50'])} | {fmt(c2a['p95'])} | {fmt(c2a['p99'])} |")
    lines.append(f"| App arrival → pose result (inference + queue) | {fmt(inf['p50'])} | {fmt(inf['p95'])} | {fmt(inf['p99'])} |")
    if fr and "t_submit_us" in fr[0]:
        pre = summary((fnum(r["t_submit_us"]) - fnum(r["t_arrival_us"])) / 1000 for r in fr)
        mp = summary((fnum(r["t_result_us"]) - fnum(r["t_submit_us"])) / 1000 for r in fr)
        call = summary(fnum(r["detect_call_ms"]) for r in fr)
        out.update(preprocess_p50=pre["p50"], mediapipe_p50=mp["p50"], detect_call_p50=call["p50"])
        lines.append(f"| ↳ app preprocessing (bitmap, luma) | {fmt(pre['p50'])} | {fmt(pre['p95'])} | {fmt(pre['p99'])} |")
        lines.append(f"| ↳ MediaPipe submit → result | {fmt(mp['p50'])} | {fmt(mp['p95'])} | {fmt(mp['p99'])} |")
        lines.append(f"| ↳ of which inside detectAsync() call | {fmt(call['p50'])} | {fmt(call['p95'])} | {fmt(call['p99'])} |")
    lines.append(f"| Capture → frame sent | {fmt(send['p50'])} | {fmt(send['p95'])} | {fmt(send['p99'])} |")
    if not cam.get("timestamp_source_realtime", True):
        lines.append("\n> Camera timestamp source is not REALTIME: capture-relative numbers are not comparable to app clocks.")

    # --- TV latency ---
    rx = [(fnum(r["tv_receive_us"]) - fnum(r["t_capture_us"])) / 1000 for r in s.tv_stats]
    rd = [(fnum(r["tv_render_us"]) - fnum(r["t_capture_us"])) / 1000 for r in s.tv_stats if fnum(r["tv_render_us"]) > 0]
    rtt = [fnum(r["rtt_us"]) / 1000 for r in s.tv_stats]
    srx, srd = summary(rx), summary(rd)
    out.update(capture_to_tv_rx_p50=srx["p50"], capture_to_tv_rx_p95=srx["p95"], capture_to_tv_draw_p50=srd["p50"])
    lines.append("")
    lines.append("## Capture → TV (ms, from tv_stats.csv)")
    if s.tv_stats:
        lines.append("| Stage | p50 | p95 | p99 | max |")
        lines.append("|---|---|---|---|---|")
        lines.append(f"| Capture → TV receive | {fmt(srx['p50'])} | {fmt(srx['p95'])} | {fmt(srx['p99'])} | {fmt(srx['max'])} |")
        lines.append(f"| Capture → TV draw call | {fmt(srd['p50'])} | {fmt(srd['p95'])} | {fmt(srd['p99'])} | {fmt(srd['max'])} |")
        lines.append(f"\nClock-sync RTT p50 {fmt(percentile(rtt, 0.5), 2)} ms. Frames drawn: {len(rd)}/{len(rx)}. "
                     "Panel/display latency is not included: measure it with the 240 fps camera (runbook §5).")
    else:
        lines.append("No TV statistics (no TV connected during this session).")

    # --- thermal ---
    lines.append("")
    lines.append("## Thermal")
    by_min: dict[int, list[float]] = collections.defaultdict(list)
    for r in fr:
        h = fnum(r["thermal_headroom"])
        if math.isfinite(h):
            by_min[int((fnum(r["t_capture_us"]) - t[0]) // 60e6)].append(h)
    if by_min:
        lines.append("| Minute | headroom (max) | pose fps (median) |")
        lines.append("|---|---|---|")
        for mnt in sorted(by_min):
            secs = [per_sec[k] for k in range(mnt * 60, mnt * 60 + 60) if k in per_sec]
            lines.append(f"| {mnt} | {fmt(max(by_min[mnt]), 2)} | {fmt(percentile(secs, 0.5))} |")
        statuses = collections.Counter(r["thermal_status"] for r in fr)
        lines.append(f"\nThermal status counts: {dict(statuses)}")
        out["thermal_headroom_max"] = max(max(v) for v in by_min.values())
    else:
        lines.append("Thermal headroom not reported by this device.")

    # --- exposure ---
    exp = summary(fnum(r["exposure_us"]) / 1000 for r in fr if fnum(r["exposure_us"]) > 0)
    iso = summary(fnum(r["iso"]) for r in fr if fnum(r["iso"]) > 0)
    lum = summary(fnum(r["person_luma"]) for r in fr if fnum(r["person_luma"]) >= 0)
    lines.append("")
    lines.append("## Exposure")
    lines.append(f"- Exposure time p50 {fmt(exp['p50'], 2)} ms (p95 {fmt(exp['p95'], 2)}), ISO p50 {fmt(iso['p50'], 0)}, person luma p50 {fmt(lum['p50'], 0)}")

    # --- tracking + events ---
    track = collections.Counter(r["tracking"] for r in fr)
    total = sum(track.values()) or 1
    lost = (track.get("0", 0)) / total
    out["tracking_lost_ratio"] = lost
    starts = collections.Counter(e["id"] for e in s.events if e.get("type") == "gesture" and e.get("phase") == "start")
    lines.append("")
    lines.append("## Tracking and events")
    lines.append(f"- Frames with no tracked player: {lost * 100:.1f}%")
    lines.append(f"- Gesture starts: {dict(starts) or 'none'}")
    probes = sum(1 for e in s.events if e.get("type") == "probe")
    if probes:
        lines.append(f"- Latency probes fired: {probes}")
    cal = [e for e in s.events if e.get("type") == "calibration_done"]
    if cal:
        c = cal[-1]
        lines.append(f"- Last calibration: distance {fmt(c['distance_m'], 2)} m, camera height {fmt(c['camera_height_m'], 2)} m, pitch {fmt(c['pitch_deg'])}°")

    # --- go/no-go checks this data can answer ---
    lines.append("")
    lines.append("## Go/no-go checks (docs/README.md §3)")
    checks = [
        ("Sustained pose rate ≥ 30 fps (5th percentile)", out["pose_fps_p05"], lambda v: v >= 30, "fps"),
        ("Capture → TV receive median ≤ 60 ms", out["capture_to_tv_rx_p50"], lambda v: v <= 60, "ms"),
        ("Capture → TV receive p95 ≤ 100 ms", out["capture_to_tv_rx_p95"], lambda v: v <= 100, "ms"),
    ]
    if dur_s < 30 * 60:
        lines.append(f"- Note: session is {dur_s / 60:.1f} min; the sustained-rate check needs ≥ 30 min.")
    for name, v, ok, unit in checks:
        verdict = "n/a" if not math.isfinite(v) else ("PASS" if ok(v) else "FAIL")
        lines.append(f"- {name}: {fmt(v)} {unit} → **{verdict}**")
    return "\n".join(lines) + "\n", out


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("session")
    ap.add_argument("--md", help="also write the report to this Markdown file")
    a = ap.parse_args()
    text, _ = build_report(Session(a.session))
    print(text)
    if a.md:
        Path(a.md).write_text(text)


if __name__ == "__main__":
    main()
