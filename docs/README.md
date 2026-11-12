# MotionHub — Smartphone-Based Motion Gaming Platform

**Technical feasibility assessment and system design (pre-implementation)**

> Working name: **MotionHub**. "Kinect" is a Microsoft trademark and should not appear in the product name. Game concepts in this document are described by mechanic ("slice game", "boxing game"), and shipped titles should be original rather than clones of existing games.

| | |
|---|---|
| Status | Draft v0.1, design only (no implementation code) |
| Reference phone | Samsung Galaxy S23 Ultra (Snapdragon 8 Gen 2 for Galaxy, Adreno 740, Hexagon NPU) |
| Reference TV | **Android TV / Google TV first**; Samsung Tizen (2020+ models, Tizen 5.5+) second |
| Date | 2026-11-12 |

> **Platform order (decided 2026-11-12):** Android TV / Google TV ships first, Samsung Tizen follows. The hub and games stay TypeScript web code so both platforms share them. Android TV runs it in a thin Kotlin WebView shell that adds network discovery (NSD) and native sockets. Tizen-specific risks (no listening sockets, old browser engines, display path) move later and no longer block the MVP. Phase 0 code lives in `android/` (phone app + Android TV shell), `tv/` (shared receiver) and `tools/`; see [phase0/runbook.md](phase0/runbook.md).

---

## Document map

| # | Deliverable | Document |
|---|---|---|
| 1 | Technical feasibility assessment | [This page](#1-feasibility-verdict) + [01-feasibility-and-kinect-comparison.md](01-feasibility-and-kinect-comparison.md) |
| 2 | Recommended system architecture | [02-architecture.md](02-architecture.md) |
| 3 | Comparison of tracking technologies | [03-tracking-technology.md](03-tracking-technology.md) |
| 4 | Recommended AI model and processing pipeline | [03-tracking-technology.md §5–6](03-tracking-technology.md#5-recommendation) |
| 5 | Calibration algorithm design | [04-calibration.md](04-calibration.md) |
| 6 | Motion recognition architecture | [05-motion-recognition.md](05-motion-recognition.md) |
| 7 | Communication protocol | [06-protocol-and-coordinates.md](06-protocol-and-coordinates.md) |
| 8 | Coordinate system specification | [06-protocol-and-coordinates.md §2](06-protocol-and-coordinates.md#2-coordinate-systems) |
| 9 | Latency and performance analysis | [07-performance-and-latency.md](07-performance-and-latency.md) |
| 10 | Kinect comparison, limitations, benchmark | [01-feasibility-and-kinect-comparison.md](01-feasibility-and-kinect-comparison.md) |
| 11 | MVP roadmap | [08-roadmap-and-risks.md](08-roadmap-and-risks.md) |
| 12 | Main technical risks | [08-roadmap-and-risks.md §3](08-roadmap-and-risks.md#3-risk-register) |
| 13 | Recommended technology stack | [02-architecture.md §7](02-architecture.md#7-recommended-technology-stack) |

**How to read the numbers.** Every performance figure here falls into one of three classes, and the documents label which one applies:

- **Physics / geometry:** follows from optics, sampling or kinematics (for example "a 30 fps sensor cannot report an event sooner than its frame interval"). These are reliable.
- **Published:** from model cards, papers or vendor docs. These are a reasonable prior but were measured on other devices and data.
- **Estimate:** engineering judgement. Every estimate is something the Phase 0 proof of concept must measure before money is committed.

---

## 1. Feasibility verdict

**Short answer: feasible as a product, but not as a like-for-like Kinect replacement.** A modern phone running a modern pose network can match or beat the Xbox 360 Kinect on 2D skeleton quality, joint count and resolution. It is clearly weaker on anything that depends on true depth, on low light, and on overlapping multiple players. The platform will succeed if its games are designed around what the sensor measures well, as Kinect games were.

### What a phone RGB camera does well (expected parity or better than Kinect v1)

| Capability | Why |
|---|---|
| 2D skeleton in the image plane (left/right/up/down) | Modern pose networks trained on millions of images, 1080p+ input vs Kinect's 640×480 depth map. 33 landmarks vs Kinect v1's 20 joints, including heels, toes and face points. |
| Jump, squat/crouch, side-step, lean/dodge, arm raise, hand position | These move mostly within the image plane, which is the axis an RGB camera resolves best. |
| Distance to the player (absolute) | Solvable geometrically from IMU-measured camera tilt, lens intrinsics and the user's known height, to roughly ±5–10 cm at 2–3 m (*estimate, see [04-calibration.md](04-calibration.md)*). |
| Dance scoring, fitness repetition counting | These compare poses and rhythm, mostly in 2D, and tolerate small depth error. |
| Camera quality, field of view and frame rate | 60 fps capture is available (Kinect v1 skeleton: 30 Hz). The wide lens can cover a larger play area. |

### What it does poorly (structural gaps vs Kinect v1)

| Capability | Why | Mitigation |
|---|---|---|
| **Motion straight toward the camera** (jab punches, front kicks, stepping forward/back by small amounts) | Monocular depth is inferred from learned priors, not measured. A straight punch is mostly z-motion, the axis the camera sees worst. | Fuse several cues (foreshortening, elbow angle, 3D landmark z, wrist scale change), train a temporal classifier on real data, and design games so punches have a lateral or rising component or are scored as "a punch happened" rather than "how far forward". |
| **Low light** | Kinect v1 used its own infrared projector, so it worked in the dark. An RGB camera needs ambient light, and fast motion needs short exposures, which need even more light. | Lighting check in calibration, motion-blur-aware camera settings, honest in-app guidance. Cannot be fully solved in software. |
| **Fast motion** | Motion blur. At 8 m/s fist speed, a 1/60 s exposure smears the wrist across ~13 cm. | Force short exposure (≤ 4–8 ms), 60 fps capture, motion-blur augmentation when fine-tuning models, temporal classifiers that tolerate a dropped wrist. |
| **Occlusion and overlapping players** | Kinect segments bodies from depth discontinuities. RGB models must guess which limb belongs to whom. | MVP = 1 player. 2 players side by side is a Phase 5 target, not a launch promise. |
| **Metric 3D joint positions** | Model "world landmarks" are learned estimates with error on the order of several centimetres to 10+ cm (*published / estimate*), worst in depth. | Use 3D for orientation and coarse depth only; never let game logic depend on centimetre-level z. |

### Overall

| Area | Expected vs Kinect v1 |
|---|---|
| 2D skeleton accuracy and stability | Better |
| Jump / crouch / side-step / dodge detection | Parity or better |
| Punch and kick detection | Below Kinect for straight punches/kicks toward the camera; near parity for hooks, uppercuts and side kicks |
| Absolute player distance | Near parity (geometry-based) |
| Relative joint depth | Clearly worse |
| Low light | Clearly worse |
| Multiple players | Worse (Kinect v1: 2 tracked + 4 detected) |
| End-to-end latency | Comparable, *if* the TV display path is fast (unverified, top risk) |

None of these comparisons is proven until the benchmark in [01-feasibility-and-kinect-comparison.md §4](01-feasibility-and-kinect-comparison.md#4-benchmark-protocol) is run.

---

## 2. Top risks to retire first

Ranked by (probability × impact) and by how cheaply a proof of concept can retire them. Full register in [08-roadmap-and-risks.md](08-roadmap-and-risks.md#3-risk-register).

1. **Fast-motion tracking under ordinary indoor lighting.** Short exposures at living-room light levels (100–300 lux) raise noise. If the pose model loses wrists on punches, the boxing and slicing game families fail. *Retire by:* a recorded dataset of punches/kicks at 3 lighting levels × 2 exposure settings, scored offline.
2. **TV-side latency.** Unknown latency of the Android TV WebView render path, the TV's picture processing (Game Mode or not), JavaScript garbage-collection pauses and the 60 Hz render loop on low-end TV SoCs. *Retire by:* a photon-to-photon measurement with a 240 fps camera filming the player and the TV. Repeat on Tizen when that platform starts.
3. **Phone↔TV connectivity.** On Android TV this is low risk: the shell discovers the phone with NSD (`_motionhub._tcp`) and can open native sockets. On Tizen (later), web apps can open WebSocket *client* connections but cannot generally listen on a socket, which shapes pairing and rules out raw UDP. *Retire by:* NSD pairing on 3+ home networks now; the Tizen listening-socket check when Tizen work starts.
4. **Depth-axis gestures** (straight punch, front kick, forward/back step). *Retire by:* measuring the classifier's F1 score on those gestures specifically, in the same offline dataset.
5. **Sustained thermals** (30+ min of camera + inference + Wi-Fi while charging). *Retire by:* a 45-minute soak test logging FPS, thermal headroom and battery.

---

## 3. Recommended first step: a 3-week proof of concept

Do **not** start with the platform. Build a throwaway **Phase 0 PoC** that answers the five risks above with measurements, then decide.

| Week | Build | Measure |
|---|---|---|
| 1 | Android app: CameraX at 1080p60 with manual exposure cap, MediaPipe Pose Landmarker (Full, GPU), on-screen skeleton, **raw-video + IMU recorder** | FPS, inference time, thermal headroom over 45 min |
| 2 | Offline harness on the phone: replay recorded clips through MediaPipe *and* one challenger (RTMPose), simple rule-based detectors for jump / squat / side-step / punch | Per-gesture precision/recall per lighting level; distance estimate vs tape-measured marks |
| 3 | TV receiver (shared web code) in an Android TV WebView shell with NSD discovery: draws the skeleton at 60 Hz; phone-side WebSocket server | Network latency and jitter; photon-to-photon latency with a 240 fps camera |

**Go / no-go criteria** (all at ≥ 150 lux, 2.5 m, single player):

| Metric | Go if |
|---|---|
| Sustained pose rate over 30 min | ≥ 30 fps, no thermal shutdown |
| Jump and squat detection | Recall ≥ 95%, < 1 false positive / 5 min of idle movement |
| Punch detection (any punch, side correct) | F1 ≥ 0.90 |
| Straight-punch-only detection | F1 ≥ 0.80 (below this, boxing must be redesigned around hooks/uppercuts) |
| Capture-to-TV-receive latency | median ≤ 60 ms, p95 ≤ 100 ms |
| Motion-to-photon (player movement to TV pixels) | median ≤ 150 ms |
| Player distance error | ≤ 10 cm at 2–3 m |

If the PoC passes, proceed to the phased plan in [08-roadmap-and-risks.md](08-roadmap-and-risks.md). If it fails only on punches, proceed but change the game line-up. If it fails on TV latency in the WebView, try a native Android TV renderer for the receiver before anything else.
