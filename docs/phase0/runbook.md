# Phase 0 Runbook: Build, Run, Measure

How to run the 3-week proof of concept from [docs/README.md §3](../README.md#3-recommended-first-step-a-3-week-proof-of-concept) and turn sessions into go/no-go numbers. Platform order: **Android TV first**, Tizen later.

## 0. What you need

| Item | Notes |
|---|---|
| Galaxy S23 Ultra (USB debugging on) + tripod + USB-C cable | Phone sensor |
| Android TV or Google TV device (USB/ADB debugging on) | Ideally one low-end (2020-era TV SoC) and one recent |
| Mac with JDK 21, Android SDK (platform 34), Node 18+, Python 3 | Python 3.10–3.12 only for the bake-off |
| A second phone or action camera that records 240 fps | Photon-to-photon latency |
| Tape measure + floor tape; a light meter app | Distance and lighting conditions |
| A phone flashlight (or LED) | Clock-sync flash and latency probe |

## 1. Build

```bash
cd tv && npm install && npm run build && cd ..
```

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

```bash
android/gradlew -p android :core:test :app:assembleDebug :tvapp:assembleDebug
```

- The first app build downloads the three MediaPipe pose models (~50 MB) into `android/app/src/main/assets/`. They are not committed.
- Gradle uses `repo1.maven.org` explicitly because `repo.maven.apache.org` does not resolve on some networks.
- APKs: `android/app/build/outputs/apk/debug/app-debug.apk` (phone), `android/tvapp/build/outputs/apk/debug/tvapp-debug.apk` (TV).

Install (TV over network ADB: enable it in the TV's developer options, then `adb connect <tv-ip>:5555`):

```bash
adb -s <phone-serial> install -r android/app/build/outputs/apk/debug/app-debug.apk
```

```bash
adb -s <tv-ip>:5555 install -r android/tvapp/build/outputs/apk/debug/tvapp-debug.apk
```

Without a TV: `npm run dev` in `tv/`, open `http://localhost:8080/?phone=<phone-ip>` in desktop Chrome. Without a phone: `npm run fake-phone` and open `http://localhost:8080/?phone=127.0.0.1`.

## 2. Setup

1. Phone in **landscape** on the tripod, ~1.0 m high, rear camera facing the play area, tilted slightly down. Player 2.5–3.0 m away.
2. Phone and TV on the **same Wi-Fi** (5 GHz preferred; TV on Ethernet if possible). Guest networks with client isolation will not work.
3. Open **MotionHub Sensor** on the phone. The HUD shows `ws://<ip>:47800/msp/v1`. Tap **Height** and enter the player's height.
4. Open **MotionHub TV** on the TV. It finds the phone via NSD and connects automatically; otherwise type the IP shown on the phone.
5. Press **red** (or Menu) on the TV remote, or **Calibrate** on the phone. The player stands still, whole body visible, until the TV shows "Calibrated ✓".
6. Tap **Dim screen** on the phone for long runs.

Phone controls: `Exposure` cycles AUTO → 8 ms → 4 ms → 2 ms (fixed exposure with automatic ISO), `Model` cycles lite → full → heavy, `Probe` enables flash detection, `Record` saves video + landmarks + IMU.

## 3. Sessions and files

Every app launch creates a session folder on the phone. Pull it with:

```bash
adb -s <phone-serial> pull /sdcard/Android/data/dev.motionhub.sensor/files/sessions ./sessions
```

| File | Content |
|---|---|
| `meta.json` | Device, camera facts (fps ranges, timestamp source, ISO range), intrinsics, gravity, calibration |
| `frames.csv` | One row per pose result: timing, exposure, ISO, thermal headroom, luma, distance |
| `events.jsonl` | Gestures, probes, calibration, recording markers |
| `tv_stats.csv` | Per-frame TV receive and draw times, converted to the phone clock |
| `rec_<n>/video.mp4`, `landmarks.jsonl`, `imu.csv` | Only while recording |

## 4. Experiments (week by week)

Start **every recording** with: Probe ON → flash the light at the camera once → 3 s standing still → script. The flash lets `sync_video` align video time with the phone clock.

### Week 1: device facts and endurance

| # | Do | Answer with |
|---|---|---|
| 1.1 | Launch once; read `meta.json` → `camera.fps_ranges`, `chosen_fps`, `timestamp_source_realtime` | Assumptions A1, A2 |
| 1.2 | Each model × exposure mode for 2 min each, player moving | `session_report` (inference, FPS, exposure) → A3 |
| 1.3 | 45 min soak: Full model, 4 ms, TV connected, phone plugged in, screen dimmed, 23–25 °C room | `session_report` thermal table → risk R5 |
| 1.4 | Distance: stand still 10 s on each tape mark (2.0 / 2.5 / 3.0 m); note start/end seconds | `distance_report` → A11 |

### Week 2: gestures and the model bake-off

1. Record the movement script from [01 §4.4](../01-feasibility-and-kinect-comparison.md#44-movement-script) (at least: 20 jumps, 20 squats, 20 side steps each way, 20 punches per hand split jab/hook, 2 min idle) at **3 lighting levels** (~50 / ~150 / ~400 lux) × **2 exposures** (AUTO, 4 ms), with 3+ people.
2. Label onsets by watching `video.mp4` (VLC frame stepping is enough): `labels.csv` with `t_s,gesture` in video seconds, plus `idle_start`/`idle_end`.
3. Score live detections, then run the bake-off and score each backend with the same recogniser (commands in §5).

### Week 3: TV path and latency

| # | Do | Answer with |
|---|---|---|
| 3.1 | 10 min of play with the TV connected on 2–3 routers / rooms | `session_report` capture→TV section |
| 3.2 | Photon-to-photon: film player + TV at 240 fps; count frames from the start of a fast arm move to the start of the on-screen move. 20 trials per TV, TV in Game Mode and not | Spreadsheet; median × 4.17 ms |
| 3.3 | Probe path: Probe ON, flash the light in front of the camera while filming camera area + TV at 240 fps; the TV flashes white | Camera + network + TV latency without pose inference |
| 3.4 | Pairing: on each router, cold-start both apps 5 times | Time to connected, failures |

## 5. Analysis commands

All run from the repo root. The reports need only Python 3.

```bash
cd tools && python3 -m mhtools.session_report ../sessions/<session> --md ../sessions/<session>/report.md
```

```bash
cd tools && python3 -m mhtools.distance_report ../sessions/<session> --marks marks.csv
```

```bash
cd tools && python3 -m mhtools.sync_video ../sessions/<session>/rec_1
```

```bash
cd tools && python3 -m mhtools.score_events --events ../sessions/<session>/events.jsonl --labels labels.csv --offset-us <offset_us> --collapse punch_l,punch_r=punch
```

Bake-off (Python 3.10–3.12 virtualenv):

```bash
cd tools && python3.12 -m venv .venv && .venv/bin/pip install -r requirements-bakeoff.txt
```

```bash
cd tools && .venv/bin/python -m mhtools.bakeoff ../sessions/<session>/rec_1 --offset-us <offset_us>
```

```bash
cd tools && python3 -m mhtools.compare_landmarks ../sessions/<session>/rec_1/bakeoff/*.jsonl --labels labels.csv --offset-us <offset_us> --static 1 3
```

Replay any landmark file through the phone's exact tracking code (needs a calibration in `meta.json`, or a clip that starts with ~2 s of standing still):

```bash
android/gradlew -p android :core:run --args="--landmarks $PWD/sessions/<session>/rec_1/bakeoff/rtmpose_halpe26.jsonl --meta $PWD/sessions/<session>/meta.json --out $PWD/sessions/<session>/rec_1/replay_rtmpose"
```

Then score `replay_*/events.jsonl` with `score_events` exactly like the live events.

## 6. Go / no-go sheet

| Metric | Target | Source | Result |
|---|---|---|---|
| Sustained pose rate over 30 min | ≥ 30 fps, no thermal shutdown | 1.3 `session_report` | |
| Jump / squat recall | ≥ 95%, < 1 FP / 5 min idle | Week 2 `score_events` | |
| Punch F1 (any punch, side correct) | ≥ 0.90 | Week 2 `score_events` | |
| Straight-punch-only F1 | ≥ 0.80 | Week 2 `score_events` (jab labels only) | |
| Capture → TV receive | median ≤ 60 ms, p95 ≤ 100 ms | 3.1 `session_report` | |
| Motion-to-photon | median ≤ 150 ms | 3.2 | |
| Player distance error | ≤ 10 cm at 2–3 m | 1.4 `distance_report` | |
| Bake-off winner | per docs/03 §5.3 weights | Week 2 `compare_landmarks` + scores | |

## 7. Known PoC limitations

- Open-mode server: any device on the LAN can connect. No QR pairing token yet.
- One player. Thresholds in `GestureEngine` are starting values; tune them on the Week 2 data via the replay CLI.
- Squat uses the 3D knee angle only when 3D landmarks exist (MediaPipe); with 2D-only backends it relies on pelvis drop.
- The phone allocates one bitmap per frame (GC churn). Acceptable for measurement; fix before production.
- The bake-off measures accuracy on a desktop. RTMPose phone speed (NPU/QNN) is a separate task if it wins on accuracy.
- Tizen: `tv/dist` already includes a `config.xml`, but packaging and Tizen measurements come after Android TV.
