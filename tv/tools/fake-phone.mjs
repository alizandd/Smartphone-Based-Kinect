// Fake phone sensor for developing the TV receiver without a phone.
// Speaks MSP v1 like the Android app: welcome, pong, 60 Hz binary frames, gesture + probe events,
// and prints the tv_stats latency the TV reports back.
//   npm run fake-phone            then open http://localhost:8080/?phone=127.0.0.1
import { WebSocketServer } from 'ws';

const PORT = Number(process.env.PORT ?? 47800);
const J = 25;
const t0 = process.hrtime.bigint();
const nowUs = () => Number((process.hrtime.bigint() - t0) / 1000n) + 1_000_000_000;

// Standing pose in Play Space metres (x player's right, y up, z away from camera).
const H = 1.75;
const base = [
  [0, 0.53], [0, 0.67], [0, 0.82], [0, 0.93], [0, 0.925],
  [-0.13, 0.818], [-0.15, 0.63], [-0.16, 0.485], [-0.165, 0.44],
  [0.13, 0.818], [0.15, 0.63], [0.16, 0.485], [0.165, 0.44],
  [-0.095, 0.53], [-0.09, 0.285], [-0.085, 0.039], [-0.085, 0.0], [-0.085, 0.0],
  [0.095, 0.53], [0.09, 0.285], [0.085, 0.039], [0.085, 0.0], [0.085, 0.0],
  [-0.018, 0.936], [0.018, 0.936],
].map(([x, y]) => [x * H, y * H, 0]);

function pose(t) {
  const cycle = t % 6;
  const sway = 0.25 * Math.sin(t * 0.7);
  const jump = cycle > 2 && cycle < 2.45 ? 0.25 * Math.sin(Math.PI * (cycle - 2) / 0.45) : 0;
  const punch = cycle > 4 && cycle < 4.15 ? (cycle - 4) / 0.15 : 0;
  return base.map(([x, y, z], j) => {
    let px = x + sway, py = y + jump, pz = z;
    if (j === 11 || j === 12) { py = 0.818 * H; pz = -0.6 * punch; px = 0.13 * H + sway; }
    if (j === 10) { py = 0.8 * H; pz = -0.3 * punch; }
    return [px, py, pz];
  });
}

function encode(seq, tCapture, joints, flags = 0) {
  const buf = Buffer.alloc(24 + 20 + 8 * J);
  let o = 0;
  buf.writeUInt8(1, o++); buf.writeUInt8(2, o++); buf.writeUInt16LE(flags, o); o += 2;
  buf.writeUInt32LE(seq >>> 0, o); o += 4;
  buf.writeUInt32LE(tCapture % 4294967296, o); buf.writeUInt32LE(Math.floor(tCapture / 4294967296), o + 4); o += 8;
  buf.writeUInt32LE(12000, o); o += 4;
  buf.writeUInt8(1, o++); buf.writeUInt8(J, o++); buf.writeUInt16LE(0, o); o += 2;
  const pel = joints[0];
  buf.writeUInt8(1, o++); buf.writeUInt8(2, o++); buf.writeUInt8(240, o++); buf.writeUInt8(128, o++);
  buf.writeInt16LE(0, o); o += 2;
  buf.writeInt16LE(Math.round(pel[0] * 1000), o); buf.writeInt16LE(0, o + 2); buf.writeInt16LE(Math.round(pel[2] * 1000), o + 4); o += 6;
  const t = tCapture / 1e6;   // head looks left/right and nods slowly
  buf.writeInt16LE(Math.round(4000 * Math.sin(t * 0.9)), o); buf.writeInt16LE(Math.round(1500 * Math.sin(t * 1.7)), o + 2);
  buf.writeInt16LE(Math.round(800 * Math.sin(t * 0.5)), o + 4); buf.writeUInt8(220, o + 6); buf.writeUInt8(0, o + 7); o += 8;
  for (const [x, y, z] of joints) {
    buf.writeInt16LE(Math.round(x * 1000), o); buf.writeInt16LE(Math.round(y * 1000), o + 2); buf.writeInt16LE(Math.round(z * 1000), o + 4);
    buf.writeUInt8(240, o + 6); buf.writeUInt8(2, o + 7); o += 8;
  }
  return buf;
}

const wss = new WebSocketServer({ port: PORT, path: '/msp/v1' });
let seq = 0, eventSeq = 0;
const lat = [];
wss.on('connection', (ws, req) => {
  console.log('TV connected from', req.socket.remoteAddress);
  ws.send(JSON.stringify({ type: 'welcome', session_id: 'fake', sensor_version: 'fake-0.1', device_model: 'FakePhone', capabilities: { max_fps: 60 } }));
  ws.on('message', (data, isBinary) => {
    if (isBinary) return;
    const t1 = nowUs();
    const m = JSON.parse(data.toString());
    if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong', t0: m.t0, t1, t2: nowUs() }));
    if (m.type === 'tv_stats') for (const [, cap, rx, render] of m.samples) if (render > 0) lat.push([(rx - cap) / 1000, (render - cap) / 1000]);
    if (m.type === 'calibrate') {
      ws.send(JSON.stringify({ type: 'setup_status', stage: 'stand_still', progress: 0.5, hint: 'stand_still' }));
      setTimeout(() => ws.send(JSON.stringify({ type: 'calibration_done', stature_m: 1.75, camera_height_m: 1.0, distance_m: 2.5, pitch_deg: -8, roll_deg: 0.3 })), 1500);
    }
  });
});

// Scripted events fire once per 6 s cycle, at the matching moment of the synthetic motion.
const sentInCycle = new Map();   // cycle → Set of event names already sent
setInterval(() => {
  const tCap = nowUs();
  const t = tCap / 1e6;
  const frame = encode(++seq, tCap - 30000, pose(t));
  const cycle = Math.floor(t / 6);
  const phase = t % 6;
  if (!sentInCycle.has(cycle)) { sentInCycle.clear(); sentInCycle.set(cycle, new Set()); }
  const sent = sentInCycle.get(cycle);
  const due = [];
  if (phase > 2.05 && !sent.has('jump')) due.push(['jump', { type: 'gesture', id: 'jump', phase: 'start', player_id: 1, event_seq: ++eventSeq, t_source_us: tCap - 30000, confidence: 0.9, attributes: {} }]);
  if (phase > 4.05 && !sent.has('punch')) due.push(['punch', { type: 'gesture', id: 'punch_r', phase: 'start', player_id: 1, event_seq: ++eventSeq, t_source_us: tCap - 30000, confidence: 0.8, attributes: { speed_mps: 4 } }]);
  if (phase > 5.05 && !sent.has('probe')) due.push(['probe', { type: 'probe', t_source_us: tCap - 30000, luma: 220 }]);
  for (const [name] of due) sent.add(name);
  for (const ws of wss.clients) {
    if (ws.readyState !== 1) continue;
    if (ws.bufferedAmount === 0) ws.send(frame);
    for (const [, msg] of due) ws.send(JSON.stringify(msg));
  }
}, 1000 / 60);

setInterval(() => {
  if (!lat.length) return;
  const p = (a, q) => a.slice().sort((x, y) => x - y)[Math.floor((a.length - 1) * q)];
  const rx = lat.map((l) => l[0]), dr = lat.map((l) => l[1]);
  console.log(`tv_stats n=${lat.length}  capture→rx p50 ${p(rx, .5).toFixed(1)} ms  capture→draw p50 ${p(dr, .5).toFixed(1)} p95 ${p(dr, .95).toFixed(1)} ms  (fake capture age 30 ms)`);
  lat.length = 0;
}, 5000);

console.log(`fake phone on ws://0.0.0.0:${PORT}/msp/v1`);
