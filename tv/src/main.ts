import { nowUs, RateMeter, Rolling } from './clock';
import { ConnState, MspConnection } from './connection';
import { hostPhones, localIp, PhoneCandidate, platformName, probeSubnet, subnetPrefix } from './discovery';
import { BodyMsg, CalibrationDoneMsg, FaceMsg, GestureMsg, HandsMsg, ProbeMsg, SetupStatusMsg, TrackingFrame } from './protocol';
import { HudState, Renderer } from './render';

// ---------------------------------------------------------------- state

const platform = platformName();
const renderer = new Renderer(document.getElementById('view') as HTMLCanvasElement);
const panel = document.getElementById('connect')!;
const ipInput = document.getElementById('ip') as HTMLInputElement;
const statusEl = document.getElementById('status')!;
const btnConnect = document.getElementById('btnConnect') as HTMLButtonElement;
const btnScan = document.getElementById('btnScan') as HTMLButtonElement;

let latest: TrackingFrame | null = null;
let latestReceiveUs = 0;
let drawnSeq = -1;
let lastSeq = -1;
let dropped = 0;
let state: ConnState = 'idle';
let sensorName = '';
let lastFrameAt = 0;
const recvRate = new RateMeter();
const renderRate = new RateMeter();
const captureToReceiveMs = new Rolling(600);
const captureToRenderMs = new Rolling(600);
const eventLatencyMs = new Rolling(100);
let probeLatencyMs = NaN;
let thermal: { level?: number; hands_face?: string; resolution?: string; heat_guard?: boolean } = {};
const stats = new Map<number, number[]>();       // frameSeq → [seq, tCapture, rxPhone, renderPhone]
const hud: HudState = { lines: [], events: [], setup: '', flashUntil: 0, cameraDistanceM: 2.5, hands: null, handsAt: 0, face: null, faceAt: 0, body: null, bodyAt: 0 };
const faceRate = new RateMeter();
const handsRate = new RateMeter();
const handsAgeMs = new Rolling(60);

const conn = new MspConnection({
  onState(s, detail) {
    state = s;
    if (s === 'connected') { panel.classList.add('hidden'); try { localStorage.setItem('mh.phone', conn.target); } catch { /* ignore */ } }
    statusEl.textContent = `${s} ${detail ?? ''}`;
  },
  onFrame(f, rx) {
    recvRate.tick();
    lastFrameAt = performance.now();
    if (lastSeq >= 0 && f.frameSeq > lastSeq + 1) dropped += f.frameSeq - lastSeq - 1;
    lastSeq = f.frameSeq;
    latest = f; latestReceiveUs = rx;
    if (conn.clock.ready) {
      const rxPhone = conn.clock.toPhone(rx);
      captureToReceiveMs.add((rxPhone - f.tCaptureUs) / 1000);
      const sample = [f.frameSeq, f.tCaptureUs, rxPhone, -1];
      stats.set(f.frameSeq, sample);
      conn.reportFrame(sample);
    }
  },
  onMessage(msg, rx) {
    switch (msg.type) {
      case 'welcome': sensorName = (msg as { device_model: string }).device_model; break;
      case 'gesture': {
        const g = msg as GestureMsg;
        if (g.phase === 'start') hud.events.push({ text: g.id.replace('_', ' ').toUpperCase(), at: performance.now() });
        if (hud.events.length > 20) hud.events.shift();
        if (conn.clock.ready) eventLatencyMs.add((conn.clock.toPhone(rx) - g.t_source_us) / 1000);
        break;
      }
      case 'sensor': {
        const m = msg as unknown as { event: string; detail?: typeof thermal };
        if (m.event === 'thermal' && m.detail) {
          thermal = m.detail;
          const lvl = m.detail.level ?? 0;
          const msgs = ['', `Phone warm: hands/face ${m.detail.hands_face}, ${m.detail.resolution}`,
            `Phone HOT: body 15 fps, hands/face ${m.detail.hands_face}, ${m.detail.resolution}`,
            'Phone CRITICAL: hands/face paused. Let it cool (no charger, no case) or add a fan.'];
          const PREFIX = 'Phone ';
          if (lvl > 0) hud.setup = msgs[Math.min(lvl, 3)];
          else if (hud.setup.startsWith(PREFIX)) hud.setup = '';
        }
        break;
      }
      case 'body': {
        hud.body = msg as BodyMsg;
        hud.bodyAt = performance.now();
        break;
      }
      case 'face': {
        hud.face = msg as FaceMsg;
        hud.faceAt = performance.now();
        faceRate.tick();
        break;
      }
      case 'hands': {
        hud.hands = msg as HandsMsg;
        hud.handsAt = performance.now();
        handsRate.tick();
        if (conn.clock.ready) handsAgeMs.add((conn.clock.toPhone(rx) - hud.hands.t_source_us) / 1000);
        break;
      }
      case 'probe': {
        const p = msg as ProbeMsg;
        hud.flashUntil = performance.now() + 100;    // full-screen white for ~6 frames
        if (conn.clock.ready) probeLatencyMs = (conn.clock.toPhone(rx) - p.t_source_us) / 1000;
        break;
      }
      case 'setup_status': {
        const s = msg as SetupStatusMsg;
        hud.setup = `Calibration: ${s.stage.replace('_', ' ')} ${Math.round(s.progress * 100)}% · ${hint(s.hint)}`;
        break;
      }
      case 'calibration_done': {
        const c = msg as CalibrationDoneMsg;
        hud.cameraDistanceM = c.distance_m;
        hud.setup = `Calibrated ✓ distance ${c.distance_m.toFixed(2)} m · camera height ${c.camera_height_m.toFixed(2)} m · tilt ${c.pitch_deg.toFixed(1)}°`;
        setTimeout(() => { if (hud.setup.startsWith('Calibrated')) hud.setup = ''; }, 6000);
        break;
      }
    }
  },
}, platform);

function hint(code: string): string {
  const map: Record<string, string> = {
    step_back: 'step back until your whole body is visible',
    full_body_not_visible: 'make sure your whole body is visible',
    stand_still: 'stand still, arms relaxed',
    check_phone_angle: 'check the phone angle',
    done: 'done',
    timeout: 'timed out, try again',
  };
  return map[code] ?? code;
}

// ---------------------------------------------------------------- render loop

function frame(): void {
  const now = performance.now();
  const PAUSED = 'Sensor paused (no frames for 0.5 s)';
  if (state === 'connected' && now - lastFrameAt > 500 && lastFrameAt > 0) hud.setup = hud.setup || PAUSED;
  else if (hud.setup === PAUSED) hud.setup = '';
  const f = latest;
  updateHudLines();
  renderer.draw(f, hud, now);
  if (f && f.frameSeq !== drawnSeq) {
    drawnSeq = f.frameSeq;
    renderRate.tick(now);
    if (conn.clock.ready) {
      const renderPhone = conn.clock.toPhone(nowUs());
      captureToRenderMs.add((renderPhone - f.tCaptureUs) / 1000);
      const s = stats.get(f.frameSeq);
      if (s) s[3] = renderPhone;
    }
    if (stats.size > 300) for (const k of stats.keys()) { if (k < f.frameSeq - 200) stats.delete(k); else break; }
  }
  requestAnimationFrame(frame);
}

function thermalNote(): string {
  if (thermal.heat_guard === false) return ' (heat guard off)';
  return (thermal.level ?? 0) > 0 ? ` (phone ${['', 'warm', 'hot', 'critical'][Math.min(thermal.level ?? 0, 3)]}: ${thermal.hands_face})` : '';
}

function fmt(v: number, d = 1): string { return isFinite(v) ? v.toFixed(d) : '–'; }

function updateHudLines(): void {
  const p = latest?.players[0];
  hud.lines = [
    `${platform} · ${state}${sensorName ? ' · ' + sensorName : ''}`,
    `rx ${recvRate.rate} fps · drawn ${renderRate.rate} fps · gaps ${dropped}`,
    `capture→rx  p50 ${fmt(captureToReceiveMs.p(0.5))}  p95 ${fmt(captureToReceiveMs.p(0.95))} ms`,
    `capture→draw p50 ${fmt(captureToRenderMs.p(0.5))}  p95 ${fmt(captureToRenderMs.p(0.95))} ms`,
    `event age at rx p50 ${fmt(eventLatencyMs.p(0.5))} ms · probe ${fmt(probeLatencyMs)} ms`,
    `clock rtt ${fmt(conn.clock.rttUs / 1000, 2)} ms · offset ${fmt(conn.clock.offsetUs / 1000, 0)} ms`,
    p ? `player conf ${(p.confidence / 2.55).toFixed(0)}% · body yaw ${(p.facingYawCentideg / 100).toFixed(0)}°` : 'no player',
    p && p.head ? `head yaw ${p.head.yawDeg.toFixed(0)}° pitch ${p.head.pitchDeg.toFixed(0)}° roll ${p.head.rollDeg.toFixed(0)}°` : 'head –',
    p ? `root x ${(p.rootMm[0] / 1000).toFixed(2)} z ${(p.rootMm[2] / 1000).toFixed(2)} m` : '',
    `send delay on phone ${latest ? fmt(latest.sendDelayUs / 1000) : '–'} ms`,
    hud.hands ? `hands ${handsRate.rate} fps · age p50 ${fmt(handsAgeMs.p(0.5))} ms${thermalNote()}` : `hands: none received${thermalNote()}`,
    hud.face ? `face ${faceRate.rate} fps · ${hud.face.found ? hud.face.expression : 'not found'}${thermalNote()}` : `face: none received${thermalNote()}`,
    'keys: C/red = calibrate · Back = reconnect panel',
  ];
  void latestReceiveUs;
}

// ---------------------------------------------------------------- connect UI

function connectTo(host: string, port?: number): void {
  statusEl.textContent = `connecting to ${host}…`;
  conn.connect(host, port);
}

btnConnect.onclick = () => {
  const ip = ipInput.value.trim();
  if (ip) connectTo(ip); else void scan();
};
btnScan.onclick = () => void scan();
// Enter / "Go" on a TV remote or on-screen keyboard connects straight from the address field.
ipInput.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' || e.keyCode === 13) { e.preventDefault(); btnConnect.click(); }
});

async function scan(): Promise<void> {
  const fromHost = hostPhones();
  if (fromHost.length) { connectTo(fromHost[0].host, fromHost[0].port); return; }
  const ip = localIp() ?? ipInput.value.trim();
  const prefix = ip ? subnetPrefix(ip) : null;
  if (!prefix) { statusEl.textContent = 'Cannot determine this device\'s subnet: type the phone address instead.'; return; }
  statusEl.textContent = `searching ${prefix}.1–254…`;
  const found = await probeSubnet(prefix, { onProgress: (n) => { statusEl.textContent = `searching ${prefix}.x … ${n}/254`; } });
  if (found) connectTo(found); else statusEl.textContent = 'No sensor found. Is the phone app open and on the same Wi-Fi?';
}

// Android TV shell status line (e.g. Bluetooth fallback progress).
(window as unknown as { motionhubStatus: (t: string) => void }).motionhubStatus = (t: string) => {
  if (state === 'connected' && t) return;
  hud.setup = t;
  statusEl.textContent = t;
};

// Android TV shell pushes NSD results here.
window.motionhubOnPhones = (json: string) => {
  try {
    const phones = JSON.parse(json) as PhoneCandidate[];
    // Switch to a working candidate unless already connected (e.g. Wi-Fi blocked → Bluetooth).
    if (phones.length && state !== 'connected') connectTo(phones[0].host, phones[0].port);
    else if (phones.length) statusEl.textContent = `found ${phones.map((p) => p.name).join(', ')}`;
  } catch { /* ignore */ }
};

function requestCalibration(): void {
  conn.send({ type: 'calibrate' });
  hud.setup = 'Calibration requested…';
}

function togglePanel(): void {
  panel.classList.toggle('hidden');
  if (!panel.classList.contains('hidden')) ipInput.focus();
}

// Android TV shell maps remote keys (red / menu → calibrate, back → panel) to these commands.
(window as unknown as { motionhubCommand: (c: string) => void }).motionhubCommand = (c: string) => {
  if (c === 'calibrate') requestCalibration();
  else if (c === 'panel') togglePanel();
};

document.addEventListener('keydown', (e) => {
  const k = e.keyCode;
  if (k === 67 /* C */ || k === 403 /* Tizen red */) requestCalibration();
  else if (k === 10009 /* Tizen back */ || k === 27 /* Esc */) togglePanel();
});

// Tizen: register colour keys so they reach the app.
try {
  const tz = (window as unknown as { tizen?: { tvinputdevice?: { registerKey(k: string): void } } }).tizen;
  tz?.tvinputdevice?.registerKey('ColorF0Red');
} catch { /* not Tizen */ }

// ---------------------------------------------------------------- start

(function start() {
  const params = new URLSearchParams(location.search);
  const phone = params.get('phone');
  let saved: string | null = null;
  try { saved = localStorage.getItem('mh.phone'); } catch { /* ignore */ }
  const fromHost = hostPhones();
  if (phone) connectTo(phone);
  else if (fromHost.length) connectTo(fromHost[0].host, fromHost[0].port);
  else if (saved) {
    const m = /^ws:\/\/([^:/]+):(\d+)/.exec(saved);
    if (m) { ipInput.value = m[1]; connectTo(m[1], Number(m[2])); }
  } else ipInput.focus();
  requestAnimationFrame(frame);
})();
