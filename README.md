# MotionHub: smartphone-based motion gaming (Phase 0)

A phone on a tripod tracks the player's body with its camera and on-device AI. The TV runs the games and receives only skeletons and gesture events, never video. Android TV / Google TV first, Samsung Tizen second.

| Folder | What |
|---|---|
| [docs/](docs/README.md) | Feasibility assessment and system design (start here) |
| [docs/phase0/runbook.md](docs/phase0/runbook.md) | How to build, run and measure the proof of concept |
| [protocol/](protocol/README.md) | MSP v1 wire format subset + golden test vector shared by both codebases |
| `android/core` | Pure-Kotlin tracking core: camera geometry, calibration, Play Space skeleton, gesture detectors, MSP codec, replay CLI |
| `android/app` | Phone sensor app: CameraX + MediaPipe Pose, WebSocket server, NSD advertising, HUD, session logging, recording |
| `android/tvapp` | Android TV shell: WebView hosting the receiver, NSD discovery of the phone, remote keys |
| [tv/](tv/) | Shared TV receiver (TypeScript): MSP client, clock sync, skeleton + top view + latency HUD. Also runs in a browser and, later, on Tizen |
| [tools/](tools/) | Python analysis: session report, gesture scoring, distance accuracy, video sync, pose-model bake-off |

## Quick start

```bash
cd tv && npm install && npm run build && cd ..
```

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) android/gradlew -p android :core:test :app:assembleDebug :tvapp:assembleDebug
```

Develop the TV side without hardware:

```bash
cd tv && npm run fake-phone
```

```bash
cd tv && npm run dev
```

Then open `http://localhost:8080/?phone=127.0.0.1`.

## Tests

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) android/gradlew -p android :core:test
```

```bash
cd tv && npm test
```

```bash
cd tools && python3 -m unittest discover -s tests -t .
```
