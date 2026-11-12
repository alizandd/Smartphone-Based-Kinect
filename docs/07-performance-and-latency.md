# 07 — Performance and Latency Analysis

Covers deliverable **9**: realistic targets for the Galaxy S23 Ultra, a full latency budget, and what needs optimisation.

> All per-stage numbers are *estimates* for a Snapdragon 8 Gen 2 phone and a mid-range 2020+ Samsung TV, except where marked as physics. Phase 0 replaces them with measured values using the per-stage timestamps described in §5.

---

## 1. Targets: realistic or not?

| Requirement (from the brief) | Assessment on S23 Ultra | Status |
|---|---|---|
| Camera capture 30–60 FPS | 60 fps at 1280×960 is expected to be available; the 4:3 60 fps combination must be verified | **Realistic** (verify 4:3 @ 60) |
| Pose estimation ≥ 30 FPS | MediaPipe Full on GPU at ~8–15 ms/frame leaves a large margin | **Realistic** |
| Pose estimation at 60 FPS, sustained | Feasible for the first 10–20 min; sustaining it for 45+ min needs thermal management and possibly NPU offload | **Needs optimisation** |
| Tracking-to-TV latency < 100 ms (capture → data on the TV) | Typical estimate ~45–55 ms | **Realistic** |
| Full motion-to-photon < 100 ms (player moves → TV pixels change) | Typical estimate ~110–130 ms; the TV's display path alone can be 20–100 ms and is outside our control | **Unlikely on most TVs; 100–150 ms is the realistic target** |
| Stable tracking in fast motion | Limited by motion blur and lighting, not compute | **Needs optimisation** (exposure control, temporal classifier, possibly fine-tuning) |
| Low CPU and battery use | Estimated 2.5–4.5 W total → ~15–25% battery per hour | **Realistic only when plugged in for long sessions** |
| Minimal overheating | Camera + GPU + Wi-Fi + charging in a passively cooled phone | **Needs optimisation** (adaptive quality ladder, §4) |

---

## 2. Latency budget

### 2.1 Skeleton path (continuous control: avatar, hand cursor)

| # | Stage | Best (ms) | Typical (ms) | Bad case (ms)¹ | Notes |
|---|---|---|---|---|---|
| 1 | Motion → sampled by a frame (half frame interval on average) | 0 | 8 | 17 | **Physics.** 60 fps; doubles at 30 fps |
| 2 | Sensor readout + ISP + delivery to the app | 15 | 25 | 40 | Android camera pipelines are usually 1–3 frames deep. Measure as app-arrival time − `SENSOR_TIMESTAMP` |
| 3 | Pose inference (MediaPipe Full, GPU) | 8 | 12 | 18 | Thermal state dependent |
| 4 | Geometry, filtering, recognition, encoding | 1 | 2 | 3 | CPU, preallocated |
| 5 | Effective filter lag | 0 | 5 | 15 | One Euro adapts: little lag on fast motion, more when slow (where it is not noticed) |
| 6 | Wi-Fi + TCP | 2 | 5 | 30 | With low-latency Wi-Fi lock; without it, spikes of 100+ ms |
| 7 | TV: JS event loop, decode, clock conversion | 1 | 3 | 15 | GC pauses on older TVs dominate the bad case |
| 8 | Wait for the next render frame (60 Hz) | 0 | 8 | 17 | **Physics.** Plus 8–20 ms in `interpolated` mode, 0 in `latest` mode |
| 9 | Render + browser compositor | 17 | 25 | 33 | 1–2 frames of browser pipelining |
| 10 | TV picture processing + panel scan-out | 10 | 20 | 60+ | Game Mode on video inputs is ~10–20 ms; the web-app graphics plane is **unmeasured** (top risk) |
| | **Capture → data available on TV (2–7)** | **27** | **52** | **121** | Brief's "< 100 ms" target: met typically |
| | **Motion-to-photon (1–10)** | **54** | **113** | **248** | |

¹ Each stage's bad case is roughly its own 95th percentile. Stages rarely hit their bad case together, so the summed bad case overstates the true end-to-end p95.

### 2.2 Event path (discrete: "punch detected")

Events carry an extra **decision delay**: a recogniser needs enough of the motion to be confident.

| Stage | Typical |
|---|---|
| Motion onset → frame sampled + camera + inference + logic (stages 1–4) | ~47 ms |
| Decision delay (state machine needs N frames over threshold; classifier fires around peak velocity) | ~30–80 ms (jump: take-off is detectable within 2–4 frames; punch: ~60–100 ms after onset) |
| Network + TV receive + render + display (stages 6–10) | ~60 ms |
| **Motion onset → visible game reaction** | **~140–190 ms** |

For comparison, Kinect v1 skeleton latency is commonly reported at around 100 ms before any game logic, rendering or TV display, so Kinect games also lived with event-to-photon times well above 100 ms. The benchmark in [01 §4](01-feasibility-and-kinect-comparison.md#4-benchmark-protocol) measures both systems the same way instead of relying on reported figures.

### 2.3 Latency reduction levers (in order of value / cost)

| Lever | Saves | Cost / risk |
|---|---|---|
| 60 fps instead of 30 fps capture | ~8 ms (sampling) + shorter decision windows | Power, heat |
| Wi-Fi low-latency lock + 5 GHz + TV on Ethernet | Removes most 50–200 ms spikes | None |
| `latest` instead of `interpolated` sampling on the TV | 8–20 ms | Slightly more visible jitter |
| Joint prediction (extrapolate by measured pipeline latency, ~30–50 ms) for cursor/avatar | Perceived 30–50 ms | Overshoot on direction changes; per-game opt-in |
| Act on gesture `start`, not `confirmed`; play anticipatory animation | 30–60 ms perceived | More false starts (games must handle `cancel`) |
| Game-design timing windows (rhythm games judge on `t_source_us`) | Removes latency from scoring | None |
| TV: low-latency canvas hint (`desynchronized`), minimal compositing layers, no DOM in the game loop | ~1 frame | Engine support varies by Tizen version |
| Ask users to enable the TV's Game Mode (if it affects the app plane; verify) | Up to tens of ms | UX friction |
| Reduce camera pipeline depth (fewer buffers, no preview stream at full resolution) | ~1 frame | Vendor-dependent |

---

## 3. Compute and power budget (phone)

| Component | Estimated power | Notes |
|---|---|---|
| Camera streaming (60 fps, 1280×960) | 1.0–1.5 W | Sensor + ISP |
| Pose inference (GPU, 60 fps) | 0.8–1.5 W | NPU (INT8) may cut this substantially; measured in the bake-off |
| CPU (tracking, networking) | 0.3–0.6 W | |
| Wi-Fi (low-latency mode, ~20 KB/s) | 0.2–0.5 W | Low-latency mode disables power save |
| Display | 0.2–0.4 W dimmed; 1+ W bright | Keep the phone screen dimmed during play |
| **Total** | **~2.5–4.5 W** | S23 Ultra battery ≈ 19 Wh → **~15–25% per hour** |

**Charging.** Charging adds heat to an already warm phone. Recommend a cable for long sessions, and investigate Samsung's "pause USB power delivery" gaming feature (powers the phone without charging the battery), including whether a non-game app can use it.

---

## 4. Thermal management ladder

Driven by `PowerManager.getThermalHeadroom()` forecasts, stepping down *before* the OS throttles:

| Level | Headroom forecast | Configuration | Effect on games |
|---|---|---|---|
| L0 | < 0.6 | 60 fps, Full model | Full quality |
| L1 | 0.6–0.75 | 45 fps, Full | Barely noticeable |
| L2 | 0.75–0.85 | 30 fps, Full | +8 ms sampling latency; fast punches less reliable |
| L3 | 0.85–0.95 | 30 fps, Lite model | Lower accuracy; `degraded` flag set; games may simplify |
| L4 | > 0.95 | Pause with a cooldown message | Game pauses |

Steps up again with hysteresis (no oscillation). Every change is sent as `sensor: thermal_degraded` so games and telemetry know.

**Soak-test target (Phase 0):** 45 minutes at L0 settings in a 23–25 °C room, plugged in, logging FPS, inference time, headroom, skin temperature (IR thermometer) and battery. Pass: never below L2 for more than 10% of the time, never L4.

---

## 5. Instrumentation (built in from Phase 0)

Every frame carries timestamps, so latency is measured continuously, not guessed:

| Timestamp | Where | Clock |
|---|---|---|
| `t_capture` | Camera `SENSOR_TIMESTAMP` | Phone |
| `t_app_arrival` | `ImageAnalysis` callback | Phone |
| `t_inference_done` | Pose result callback | Phone |
| `t_send` | Before the socket write | Phone |
| `t_receive` | WebSocket `onmessage` | TV, converted to phone clock |
| `t_render_submit` | After the draw call in `requestAnimationFrame` | TV, converted |

The TV debug HUD shows rolling p50/p95 for each segment, plus FPS on both devices and the thermal level.

**Photon-to-photon measurement** (what software timestamps cannot see: sensor exposure → pixels on the panel):

1. **Manual, from Phase 0:** a 240 fps camera films the player and the TV together; count frames between the hand starting to move and the on-screen hand starting to move. ~4 ms resolution.
2. **Automated rig, from Phase 2:** a microcontroller blinks an LED in front of the phone camera; a "light-detection" test mode on the phone emits an event when it sees the LED (bypassing pose inference); the TV draws a white square; a photodiode on the TV screen reports back to the microcontroller. Runs hundreds of trials unattended and isolates camera + network + TV latency from model latency.

---

## 6. TV-side performance

| Concern | Guidance |
|---|---|
| Frame budget | 16.7 ms at 60 fps on the **oldest supported model**; degrade to 30 fps rendering if needed (tracking stays at full rate) |
| Rendering | WebGL (PixiJS/Three.js), texture atlases, few draw calls, no per-frame DOM changes, no CSS animations under the canvas |
| Garbage collection | Decode binary frames into preallocated typed arrays; object pools for particles and entities. GC pauses are the main source of TV-side jitter |
| Memory | Lazy-load game bundles; unload the previous game's textures on switch |
| Profiling | Use Tizen Studio's remote web inspector on real hardware for each supported model year; emulators are not representative |
