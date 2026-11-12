# MSP v1: Phase 0 subset

The normative spec is [docs/06-protocol-and-coordinates.md](../docs/06-protocol-and-coordinates.md). This folder holds what both codebases test against.

- `golden/tracking_frame_v1.json` (236 bytes) and `golden/tracking_frame_v2.json` (244 bytes, adds head yaw/pitch/roll): one `TrackingFrame` (1 player, 25 joints) as field values plus the exact encoding. The Kotlin encoder (`android/core`) and the TypeScript decoder (`tv/src/protocol.ts`) both have tests against it. If either side changes the layout, its test fails.

## Phase 0 simplifications

| Topic | Phase 0 behaviour | Final design |
|---|---|---|
| Pairing | Phone server runs in **open mode** (no token). TV connects to a typed IP, or probes its /24 subnet | QR token + per-pair secret |
| Endpoint | `ws://<phone-ip>:47800/msp/v1` | same |
| Messages used | `hello`, `welcome`, `ping`/`pong`, `tv_stats`, `gesture`, `probe`, `sensor`, `calibration_done`, `setup_status` | full catalogue |

## JSON messages used in Phase 0

All timestamps are microseconds. `t_*_us` without a suffix are in the **phone clock** (`SystemClock.elapsedRealtimeNanos() / 1000`); `tv_*` are in the TV clock (`performance.now() * 1000`).

```jsonc
// TV → phone
{"type":"hello","hub_version":"poc-0.1","protocols":["msp/1"],"platform":"tizen|browser","tv_name":"..."}
{"type":"ping","t0":123.0}                                  // t0 = TV clock
{"type":"tv_stats","samples":[[frame_seq, t_capture_us, tv_receive_us_in_phone_clock, tv_render_us_in_phone_clock], ...],
 "clock":{"offset_us":..., "rtt_us":...}}

// phone → TV
{"type":"welcome","session_id":"...","sensor_version":"poc-0.1","device_model":"SM-S918B","capabilities":{"max_fps":60}}
{"type":"pong","t0":123.0,"t1":456,"t2":457}                // t1/t2 = phone clock
{"type":"gesture","id":"jump","phase":"start","player_id":1,"event_seq":7,"t_source_us":...,"confidence":0.9,"attributes":{...}}
{"type":"probe","t_source_us":...,"luma":212}              // latency probe: light flash seen by the camera
{"type":"sensor","event":"thermal|lighting|...","detail":{...}}
{"type":"setup_status","stage":"neutral_pose","progress":0.4,"hint":"stand_still"}
{"type":"calibration_done","camera_height_m":1.02,"distance_m":2.48,"pitch_deg":-7.9,"roll_deg":0.4}
```

Clock offset (computed on the TV): `offset = ((t1 − t0) + (t2 − t3)) / 2`, so `phone_time ≈ tv_time + offset`. The sample with the lowest RTT in the last 30 wins.
