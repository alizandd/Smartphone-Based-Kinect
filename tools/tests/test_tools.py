import json
import tempfile
import unittest
from pathlib import Path

from mhtools.common import Session, percentile
from mhtools.score_events import score
from mhtools.session_report import build_report

HEADER = ("frame_seq,t_capture_us,t_arrival_us,t_result_us,t_send_us,capture_to_arrival_ms,inference_ms,exposure_us,iso,"
          "cam_fps,pose_fps,thermal_headroom,thermal_status,center_luma,person_luma,key_joints_visible,tracking,distance_m,"
          "calibrated,model,delegate,exposure_mode,clients")


class ToolsTest(unittest.TestCase):
    def test_percentile(self):
        self.assertEqual(percentile([1, 2, 3, 4, 5], 0.5), 3)
        self.assertAlmostEqual(percentile(range(101), 0.95), 95)

    def test_score_matches_within_window_and_counts_idle_fp(self):
        labels = [(1_000_000, "jump"), (3_000_000, "jump"), (5_000_000, "punch_r"),
                  (10_000_000, "idle_start"), (70_000_000, "idle_end")]
        dets = [(1_080_000, "jump"), (3_900_000, "jump"), (5_060_000, "punch_l"), (20_000_000, "jump")]
        r = score(labels, dets)
        self.assertEqual(r["jump"]["tp"], 1)
        self.assertEqual(r["jump"]["fn"], 1)
        self.assertEqual(r["jump"]["fp"], 2)
        self.assertEqual(r["jump"]["fp_in_idle"], 1)
        self.assertAlmostEqual(r["jump"]["latency_p50_ms"], 80)
        self.assertEqual(r["punch_r"]["fn"], 1)                 # wrong side does not count…
        merged = score(labels, dets, collapse={"punch_l": "punch", "punch_r": "punch"})
        self.assertEqual(merged["punch"]["tp"], 1)              # …unless sides are collapsed
        self.assertAlmostEqual(r["_idle_minutes"], 1.0)

    def test_session_report_on_synthetic_session(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)
            rows = [HEADER]
            for i in range(60 * 70):                            # 70 s at 60 fps
                t = 1_000_000_000 + i * 16_667
                rows.append(f"{i},{t},{t + 25000},{t + 37000},{t + 38000},25,12,4000,1200,60,60,0.40,0,120,110,13,2,2.5,1,full,GPU,4ms,1")
            (p / "frames.csv").write_text("\n".join(rows) + "\n")
            (p / "tv_stats.csv").write_text("frame_seq,t_capture_us,tv_receive_us,tv_render_us,offset_us,rtt_us\n" +
                                            "".join(f"{i},{1_000_000_000 + i},{1_000_000_000 + i + 45000},{1_000_000_000 + i + 70000},0,3000\n" for i in range(100)))
            (p / "events.jsonl").write_text(json.dumps({"type": "gesture", "id": "jump", "phase": "start", "t_source_us": 1}) + "\n")
            (p / "meta.json").write_text(json.dumps({"device_model": "SM-S918B", "camera": {"timestamp_source_realtime": True}}))
            text, out = build_report(Session(p))
            self.assertAlmostEqual(out["pose_fps_p50"], 60, delta=1)
            self.assertAlmostEqual(out["capture_to_tv_rx_p50"], 45, delta=0.1)
            self.assertIn("PASS", text)
            self.assertIn("jump", text)


if __name__ == "__main__":
    unittest.main()
