# 01 — Feasibility Assessment and Kinect Comparison

Covers deliverables **1** (feasibility) and **10** (Kinect comparison, limitations, benchmark, acceptance criteria).

---

## 1. The sensors, side by side

| Property | Xbox 360 Kinect (v1) | Galaxy S23 Ultra (proposed setup) |
|---|---|---|
| Depth sensing | Structured-light IR projector + IR camera. Measured depth per pixel. | None (no ToF/LiDAR on S23 Ultra). Depth is computed from geometry or inferred by a network. |
| Depth map | 640×480 @ 30 Hz (skeleton pipeline works at lower internal resolution) | n/a |
| Colour camera | 640×480 @ 30 Hz | Main 200 MP sensor, binned to 12 MP; analysis stream ~1280×960 @ 60 Hz |
| Field of view | ~57° H × 43° V | Main lens (24 mm equiv.), 4:3 frame: **~72° H × 57° V**. Ultra-wide (13 mm equiv.), 4:3: ~106° H × 90° V |
| Operating range | ~0.8–4.0 m; full body from ~1.8 m | Full body from ~2.0 m (main, 4:3, landscape); no hard maximum, resolution-limited |
| Depth precision | Quantisation ~ a few mm at 1 m, growing quadratically to ~2–4 cm at 4 m | Absolute player distance ±5–10 cm (geometry, *estimate*); joint-relative depth ±5–15 cm (*estimate*) |
| Skeleton | 20 joints, 2 fully tracked players, up to 6 detected | 33 landmarks (MediaPipe) incl. heels, toes, face points; 1 player robust, 2 feasible later |
| Skeleton rate | 30 Hz | 30–60 Hz |
| Illumination | Own IR illumination: works in darkness; fails in direct sunlight | Needs ambient light; degrades below ~100 lux, badly below ~50 lux for fast motion (*estimate*) |
| Compute | Dedicated SoC + Xbox CPU share | Snapdragon 8 Gen 2: CPU, Adreno 740 GPU, Hexagon NPU, shared with camera and Wi-Fi in a passively cooled phone |

### Field-of-view arithmetic (physics)

The frame aspect ratio matters more than expected. A 16:9 video stream is a vertical crop of the 4:3 sensor:

| Stream | Vertical FOV | Vertical coverage at 2.5 m | at 3.0 m | Horizontal coverage at 2.5 m |
|---|---|---|---|---|
| Main, landscape 16:9 | ~44° | 2.03 m | 2.43 m | 3.6 m |
| **Main, landscape 4:3** | ~57° | **2.71 m** | **3.25 m** | **3.6 m** |
| Main, portrait 16:9 | ~72° | 3.63 m | 4.36 m | 2.0 m |
| Ultra-wide, landscape 4:3 | ~90° | 5.0 m | 6.0 m | 6.6 m |

A 1.75 m player with arms raised reaches ~2.3 m, and a jump adds 0.3–0.5 m, so the frame must cover **~2.7 m vertically**. Landscape 16:9 at 2.5 m does not fit (2.03 m). **Recommendation: landscape, 4:3 stream, main lens, player at 2.5–3.0 m, phone at ~1.0 m height pitched slightly down.** Ultra-wide is the fallback for small rooms (2.0 m), at the cost of lower light sensitivity and more lens distortion. Whether the S23 Ultra exposes a 60 fps 4:3 analysis stream through CameraX/Camera2 must be checked in Phase 0 (60 fps "high speed" sessions are often 16:9 only).

Pixel scale at 2.5 m (1280×960, main lens): focal length ≈ 887 px, so **1 px ≈ 2.8 mm**, and a 1.75 m player is ~620 px tall. The pose network, however, runs on a 256×256 crop of the person, so its effective resolution is ~7 mm per input pixel. That, not the camera, is the precision limit.

---

## 2. Capability-by-capability assessment

### A. Full-body tracking

| Requirement | Assessment | Confidence |
|---|---|---|
| Head, shoulders, elbows, wrists, hips, knees, ankles | Provided by all candidate models (MediaPipe: 33 points, MoveNet/RTMPose: 17, RTMPose-Halpe: 26 with feet) | High |
| Continuous tracking | MediaPipe's live-stream mode reuses the previous frame's region of interest and skips the person detector. Fast, but it can drift or lose the person in a fast motion and must re-detect (a visible hitch of 1–3 frames). | Medium: measure re-detection rate |
| Body orientation (yaw) | Reliable to roughly ±60° from shoulder/hip foreshortening and 3D landmarks. Near 90° (side-on) left/right limb swaps occur. Front vs back can flip; use face-landmark visibility to disambiguate. | Medium |
| Movement direction and velocity | In the image plane: good (differentiate filtered positions). In depth: coarse. | High (x/y), Low (z) |
| Partial occlusion, overlapping limbs | Networks "hallucinate" plausible occluded joints, with lower visibility scores. Plausible but not measured; arms crossing in front of the torso are a known weak case. | Medium-low |
| Stability in fast movement | Limited by motion blur and by temporal smoothing in the model's ROI tracker. **The single biggest unknown.** | Low until measured |

### B. Depth estimation: what is physically reliable

The S23 Ultra cannot *measure* depth for a moving subject from a static tripod. Every depth number is computed from assumptions. They differ a lot in how much to trust them:

| Quantity | Method | Physically grounded? | Expected accuracy (estimate) |
|---|---|---|---|
| Camera tilt and roll | Phone accelerometer (gravity vector) while static | **Yes, measured** | ±0.2–0.5° |
| Player distance (feet on the floor) | Ray from camera through the foot pixel, intersected with the floor plane. Needs camera height, from calibration. | **Yes, geometry** (given known camera height and a flat floor) | ±3–8 cm at 2.5 m |
| Player distance (any pose) | Apparent size of a rigid-ish segment (torso, shoulder width) vs that segment's calibrated metric length | Geometry + calibration | ±4–10 cm, worse when bending or rotating |
| Forward/backward stepping | Change of the two estimates above | Geometry | Steps ≥ 20–30 cm reliably detectable; ≤ 10 cm not |
| Player height | Entered by the user (best), or solved from known camera height | Measured / geometry | ±1 cm entered; ±3–5 cm solved |
| Joint depth relative to the pelvis (hand in front of body etc.) | Learned 3D landmarks (MediaPipe "world landmarks") | **No: learned prior** | Several cm to >10 cm error; biased toward "typical" poses |
| Dense scene depth | Monocular depth network (Depth Anything V2, MiDaS) | **No: learned, relative scale only** | Not metric, flickers frame to frame, does not give joint depth |
| 3D body position in the room | Pelvis position from floor-plane distance + 2D horizontal offset | Geometry | ±5–10 cm |

Consequence for game design: **x/y are trustworthy, absolute player distance is trustworthy, joint-level z is a hint.** Game logic should consume z through recognised events and coarse zones ("player stepped forward"), never through precise thresholds on raw joint z. The full method comparison is in [03-tracking-technology.md §4](03-tracking-technology.md#4-depth-estimation-approaches).

### C. Motion recognition feasibility by movement

| Movement | Dominant axis | Expected reliability | Notes |
|---|---|---|---|
| Jump | Vertical (image plane) | **High** | Pelvis and both ankles rise; metric height from calibration |
| Squat / crouch | Vertical + knee angle | **High** | Knee and hip angles plus pelvis drop vs calibrated standing height |
| Side step left/right | Lateral | **High** | |
| Dodge / lean left/right | Lateral (torso tilt) | **High** | Shoulder-line and spine angle relative to gravity |
| Arm raise, hand position, swipes | Image plane | **High** | Basis for the slice game and menu pointing |
| Body rotation (twist) | Yaw | Medium | Reliable to ±60° |
| Hook / uppercut punch | Lateral / vertical + depth | Medium-high | Strong 2D signature |
| **Straight punch (jab/cross)** | **Depth** | **Medium-low** | Main gap. Cues: elbow-angle extension, wrist moving toward shoulder in 2D, 3D landmark z, fast wrist trajectory. Classifier must be trained on real data. |
| Side kick | Lateral | Medium-high | |
| **Front kick** | **Depth** | Medium-low | Same issue as straight punch; knee lift helps |
| Forward/back movement | Depth (whole body) | Medium | Whole-body distance is geometric and reliable for ≥ 20–30 cm steps |
| Sequences/combos | — | Same as the weakest component | Built on top of the event stream |

---

## 3. Limitations of smartphone tracking (honest list)

1. **No measured depth.** Joint z is inferred. Effects: straight punches and front kicks are harder, hands in front of the torso are ambiguous, and there is no depth-based segmentation of overlapping players.
2. **Lighting dependence.** No active illumination. Dark rooms, strong backlight (a window behind the player) and flickering lights all degrade tracking. Kinect v1 had the opposite failure (sunlight).
3. **Motion blur vs noise trade-off.** Short exposures freeze motion but need light. Indoors, either the image is noisy (high ISO) or fast limbs blur. Neither can be fixed fully in software.
4. **Thermal and battery budget.** A phone is a passively cooled device. Sustained 60 fps capture plus inference plus Wi-Fi plus charging will heat it. Expect to need adaptive quality (model size, frame rate) after 20–40 min (*estimate*).
5. **Placement variability.** Users will put the phone on a shelf, at an angle, too close. Kinect had a fixed, motorised, known mount. Calibration must work much harder (see [04-calibration.md](04-calibration.md)).
6. **Shared, interruptible device.** Calls, notifications and OS background limits can interrupt the camera. The app must survive and resume gracefully.
7. **Multiplayer.** Two players side by side are feasible. Players crossing in front of each other will cause identity swaps that Kinect's depth avoided.
8. **Wi-Fi.** A local wireless link adds jitter (power-save modes, interference) that Kinect's USB cable did not have.
9. **The TV.** Smart TV SoCs are weak, and the browser engine on older Tizen models is old. Game complexity is bounded by the TV, not the phone.

---

## 4. Benchmark protocol

### 4.1 Goal

Quantify, with the same player, movements and room, how the phone system compares to an Xbox 360 Kinect, and whether it meets the acceptance criteria in §5. Kinect v1 is a **comparison baseline, not ground truth**. Ground truth comes from video annotation (for events) and, where available, an optical motion-capture system (for joint positions).

### 4.2 Equipment

| Item | Purpose |
|---|---|
| Kinect for Windows v1 or Xbox 360 Kinect + adapter, Kinect SDK 1.8 on a Windows PC | Baseline skeleton + timestamps |
| Galaxy S23 Ultra on a tripod, mounted directly above or below the Kinect (≤ 15 cm apart, same yaw) | System under test |
| A second phone or action camera at 240 fps, filming from the side, framing both the player and the TV | Event ground truth and photon-to-photon latency |
| Lux meter (or a calibrated phone app) | Lighting conditions |
| Tape-measured floor marks at 1.5 / 2.0 / 2.5 / 3.0 / 3.5 m | Distance ground truth |
| Optional: optical mocap (OptiTrack/Vicon) or a lab partnership | Joint-position ground truth (MPJPE) |
| An LED flashed by the PC + a hand clap | Synchronising all clocks and videos |

### 4.3 Conditions matrix

- **Distance:** 2.0, 2.5, 3.0 m (and 1.5 / 3.5 m to find the limits).
- **Lighting:** ~50 lux (dim evening), ~150 lux (typical living room), ~400 lux (bright room), and a backlit case (window behind the player).
- **Players:** at least 6 people covering height 1.50–1.95 m, varied body types, clothing (loose/tight, dark/light, clothing that matches the background).
- **Room:** cluttered home background and a plain background.
- **Players per session:** 1 player; 2 players side by side; 2 players crossing.

### 4.4 Movement script

Each subject performs a fixed script, recorded simultaneously by both systems, with an audio metronome so reps are comparable:

| Block | Content | Reps |
|---|---|---|
| Static | Neutral stand 10 s; T-pose 5 s; arms forward 5 s | 1 |
| Vertical | Jumps (small/large), squats, crouch-and-hold | 20 each |
| Lateral | Side steps L/R, leans/dodges L/R | 20 each |
| Upper body | Jab L/R, cross, hook L/R, uppercut L/R, arm raise L/R/both, swipes in 4 directions | 20 each |
| Lower body | Front kick L/R, side kick L/R, knee raise | 20 each |
| Depth | Step forward/back 30 cm and 60 cm | 10 each |
| Rotation | Turn to ±45°, ±90°, 180° and back | 5 each |
| Idle / distractors | 2 min of natural non-gesture movement (scratching head, adjusting clothes, talking with hands, walking around) | 1 |
| Speed sweep | Punches at slow / medium / maximum speed | 10 each |
| Endurance | 30 min mixed routine (thermal / stability) | 1 |

### 4.5 Metrics

| Metric | Definition | Measured on |
|---|---|---|
| Event precision / recall / F1 | Per gesture class vs annotated ground truth; ±150 ms matching window | Both systems |
| Side accuracy | % of correctly detected events with correct left/right | Both |
| False positives per minute | Events fired during the idle/distractor block | Both |
| Detection latency | Time from annotated motion onset (240 fps video) to event received by the game | Both (Kinect: event fired by the same recogniser logic running on Kinect skeletons) |
| Motion-to-photon latency | Time from a visible motion in the 240 fps video to the corresponding change on the TV screen in the same video | Phone→TV system; Kinect→PC monitor for reference |
| Static jitter | Std. dev. of each joint position over the 10 s neutral stand (mm), per axis | Both |
| Bone-length stability | Coefficient of variation of upper-arm, forearm, thigh, shin lengths over the session | Both |
| Joint accuracy (MPJPE) | Mean per-joint position error vs mocap, after rigid alignment | Both (if mocap available) |
| Distance error | Estimated vs tape-measured player distance | Both |
| Tracking loss | % of frames with no valid skeleton; number of re-acquisition events | Both |
| Calibration success | % of first-attempt calibrations that pass, and time to pass | Phone system |
| Sustained performance | FPS, thermal headroom, battery %/h over 30 min | Phone |

The *same* recogniser code should run on both skeleton streams (after mapping Kinect's 20 joints to the canonical joint set). That isolates sensor quality from recogniser quality.

---

## 5. Acceptance criteria

Two levels: **MVP** (ship a first game) and **Kinect parity** (the marketing claim). Conditions: 150–400 lux, 2.0–3.0 m, single player, unless noted. *These targets are proposals to be confirmed after Phase 0 data.*

| Metric | MVP target | Kinect-parity target |
|---|---|---|
| Sustained pose rate (30 min, plugged in) | ≥ 30 fps | ≥ 30 fps, ≥ 60 fps for first 15 min |
| Jump / squat / side-step / dodge F1 | ≥ 0.95 | ≥ Kinect − 0.02 |
| Punch (any type, correct side) F1 | ≥ 0.90 | ≥ Kinect − 0.03 |
| Straight punch / front kick F1 | ≥ 0.80 | ≥ Kinect − 0.05 |
| Left/right side accuracy | ≥ 99% | ≥ 99% |
| False positives during idle | ≤ 1 per 5 min | ≤ Kinect |
| Capture → TV receive latency | median ≤ 60 ms, p95 ≤ 100 ms | same |
| Event detection latency (motion onset → event) | median ≤ 150 ms | ≤ Kinect + 20 ms |
| Motion-to-photon | median ≤ 150 ms | ≤ Kinect |
| Static jitter after filtering (x/y) | ≤ 10 mm | ≤ Kinect |
| Static jitter after filtering (z) | ≤ 30 mm | — (Kinect is expected to win) |
| Player distance error | ≤ 10 cm | ≤ 2× Kinect error |
| Tracking loss during the script | ≤ 1% of frames | ≤ Kinect |
| First-attempt calibration success | ≥ 90%, ≤ 45 s | ≥ 95%, ≤ 30 s |
| Low light (50 lux) | Clear warning; jump/squat still ≥ 0.85 F1 | Not targeted |

**Claim policy:** the product may claim "Kinect-like" only for gesture classes that meet the parity column in the published benchmark. Expect parity on vertical and lateral gestures, and expect to *not* claim parity on depth-axis gestures, low light, or crossing multiplayer.
