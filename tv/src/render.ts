import { BodyMsg, BONES, FaceMsg, FLAG_UNCALIBRATED, HAND_BONES, HandsMsg, J, TrackingFrame } from './protocol';

export interface HudState {
  lines: string[];
  events: { text: string; at: number }[];
  setup: string;
  flashUntil: number;
  cameraDistanceM: number;    // distance from camera to the Play Space origin (from calibration)
  hands: HandsMsg | null;
  handsAt: number;
  face: FaceMsg | null;
  faceAt: number;
  body: BodyMsg | null;
  bodyAt: number;
}

const W = 1920;
const H = 1080;

/** Canvas 2D renderer in a fixed 1920×1080 logical space, scaled to the screen. */
export class Renderer {
  private ctx: CanvasRenderingContext2D;
  private scale = 1;

  constructor(private readonly canvas: HTMLCanvasElement) {
    const ctx = canvas.getContext('2d', { alpha: false, desynchronized: true } as CanvasRenderingContext2DSettings);
    if (!ctx) throw new Error('2D canvas unavailable');
    this.ctx = ctx;
    this.resize();
    window.addEventListener('resize', () => this.resize());
  }

  private resize(): void {
    const dpr = window.devicePixelRatio || 1;
    this.canvas.width = Math.round(window.innerWidth * dpr);
    this.canvas.height = Math.round(window.innerHeight * dpr);
    this.scale = Math.min(this.canvas.width / W, this.canvas.height / H);
  }

  draw(frame: TrackingFrame | null, hud: HudState, now: number): void {
    const c = this.ctx;
    c.setTransform(1, 0, 0, 1, 0, 0);
    if (now < hud.flashUntil) {
      c.fillStyle = '#fff';
      c.fillRect(0, 0, this.canvas.width, this.canvas.height);
      return;
    }
    c.fillStyle = '#0b0d12';
    c.fillRect(0, 0, this.canvas.width, this.canvas.height);
    const ox = (this.canvas.width - W * this.scale) / 2;
    const oy = (this.canvas.height - H * this.scale) / 2;
    c.setTransform(this.scale, 0, 0, this.scale, ox, oy);

    this.drawFrontView(frame);
    this.drawMiniMap(frame, hud.cameraDistanceM);
    this.drawHands(hud, now);
    this.drawFace(hud, now);
    this.drawBody(hud, now);
    this.drawHud(hud, now);
  }

  /** Mirror view: Play Space +x (player's right) is screen right, so the avatar mirrors the player. */
  private drawFrontView(frame: TrackingFrame | null): void {
    const c = this.ctx;
    const cx = 640, floorY = 1000, s = 380;
    c.strokeStyle = '#1f2533'; c.lineWidth = 1;
    for (let m = -1.5; m <= 1.5001; m += 0.5) { c.beginPath(); c.moveTo(cx + m * s, 120); c.lineTo(cx + m * s, floorY); c.stroke(); }
    for (let m = 0; m <= 2.25; m += 0.5) { c.beginPath(); c.moveTo(cx - 1.5 * s, floorY - m * s); c.lineTo(cx + 1.5 * s, floorY - m * s); c.stroke(); }
    c.strokeStyle = '#3a4459'; c.lineWidth = 3;
    c.beginPath(); c.moveTo(cx - 1.6 * s, floorY); c.lineTo(cx + 1.6 * s, floorY); c.stroke();
    c.fillStyle = '#5b6680'; c.font = '20px system-ui, sans-serif';
    c.fillText('front view (mirrored) · grid 0.5 m', cx - 1.5 * s, 100);

    const p = frame?.players[0];
    if (!p) {
      c.fillStyle = '#5b6680'; c.font = '36px system-ui, sans-serif';
      c.fillText(frame ? 'no player' : 'waiting for sensor…', cx - 160, 560);
      return;
    }
    const X = (j: number) => cx + (p.jointsMm[3 * j] / 1000) * s;
    const Y = (j: number) => floorY - (p.jointsMm[3 * j + 1] / 1000) * s;
    c.lineCap = 'round';
    for (const [a, b] of BONES) {
      const st = Math.min(p.jointState[a], p.jointState[b]);
      if (st === 0) continue;
      const left = isLeft(a) || isLeft(b);
      c.strokeStyle = st === 2 ? (left ? '#4fd1ff' : '#7cff9b') : '#8a7a40';
      c.lineWidth = st === 2 ? 10 : 6;
      c.beginPath(); c.moveTo(X(a), Y(a)); c.lineTo(X(b), Y(b)); c.stroke();
    }
    if (p.jointState[J.HEAD] > 0) {
      c.strokeStyle = '#e6e9ef'; c.lineWidth = 6;
      c.beginPath(); c.arc(X(J.HEAD), Y(J.HEAD), 0.1 * s, 0, Math.PI * 2); c.stroke();
      if (p.head) {
        // Mirror view: looking toward the player's left (+yaw) points screen-left; looking up points up.
        const len = 0.22 * s;
        const dx = -Math.sin(p.head.yawDeg * Math.PI / 180) * len;
        const dy = -Math.sin(p.head.pitchDeg * Math.PI / 180) * len;
        c.strokeStyle = '#ffb84d'; c.lineWidth = 5;
        c.beginPath(); c.moveTo(X(J.HEAD), Y(J.HEAD)); c.lineTo(X(J.HEAD) + dx, Y(J.HEAD) + dy); c.stroke();
      }
    }
    if (frame && frame.flags & FLAG_UNCALIBRATED) {
      c.fillStyle = '#ffb84d'; c.font = '24px system-ui, sans-serif';
      c.fillText('UNCALIBRATED: positions use default camera height (press C / red key to calibrate)', cx - 1.5 * s, 1050);
    }
  }

  /** Top-down map: camera at the top, distance increases downward. */
  private drawMiniMap(frame: TrackingFrame | null, originDistance: number): void {
    const c = this.ctx;
    const x0 = 1300, y0 = 70, w = 580, h = 520, s = h / 4.5, cx = x0 + w / 2;
    c.fillStyle = '#121620'; c.fillRect(x0, y0, w, h);
    c.fillStyle = 'rgba(80, 200, 120, 0.15)';
    c.fillRect(x0, y0 + 2.0 * s, w, 1.0 * s);
    c.fillStyle = '#5b6680'; c.font = '18px system-ui, sans-serif';
    c.fillText('top view · target zone 2.0–3.0 m', x0 + 10, y0 + 24);
    for (let m = 1; m <= 4; m++) { c.fillText(`${m} m`, x0 + 8, y0 + m * s + 6); }
    c.fillStyle = '#e6e9ef';
    c.beginPath(); c.moveTo(cx, y0 + 6); c.lineTo(cx - 14, y0 + 30); c.lineTo(cx + 14, y0 + 30); c.closePath(); c.fill();
    const p = frame?.players[0];
    if (!p) return;
    const dist = originDistance + p.rootMm[2] / 1000;
    // Seen from above with the camera at the top looking down the screen, the camera's left (= the
    // player's right, +x) is screen-right, so x maps directly.
    const mx = cx + (p.rootMm[0] / 1000) * s;
    const py = y0 + dist * s;
    c.fillStyle = '#7cff9b';
    c.beginPath(); c.arc(mx, py, 14, 0, Math.PI * 2); c.fill();
    const yaw = (p.facingYawCentideg / 100) * Math.PI / 180;
    c.strokeStyle = '#7cff9b'; c.lineWidth = 4;
    // yaw 0 = facing the camera (screen-up); positive = turned to the player's left (screen-left).
    c.beginPath(); c.moveTo(mx, py); c.lineTo(mx - Math.sin(yaw) * 40, py - Math.cos(yaw) * 40); c.stroke();
    if (p.head) {
      const hy = p.head.yawDeg * Math.PI / 180;
      c.strokeStyle = '#ffb84d'; c.lineWidth = 3;
      c.beginPath(); c.moveTo(mx, py); c.lineTo(mx - Math.sin(hy) * 60, py - Math.cos(hy) * 60); c.stroke();
    }
    c.fillStyle = '#e6e9ef'; c.font = '22px system-ui, sans-serif';
    c.fillText(`${dist.toFixed(2)} m`, mx + 22, py + 8);
  }

  /** Zoomed finger view per hand (mirror layout: the player's right hand on the right). */
  private drawHands(hud: HudState, now: number): void {
    const h = hud.hands;
    const c = this.ctx;
    const size = 230, y0 = 760;
    if (h && now - hud.handsAt <= 500 && h.hands.length === 0) {
      // Hand tracking is on but no hand found: say so instead of showing nothing.
      c.fillStyle = 'rgba(18, 22, 32, 0.92)'; c.fillRect(60, y0, 2 * size + 30, 70);
      c.fillStyle = '#ffb84d'; c.font = '22px system-ui, sans-serif';
      c.fillText('Hands: none detected. Raise your hands toward the camera.', 76, y0 + 43);
      return;
    }
    if (!h || now - hud.handsAt > 500) return;
    for (const hand of h.hands) {
      // mirror view: player's left hand on the screen's left
      const x0 = hand.side === 'l' ? 60 : 60 + size + 30;
      c.fillStyle = '#121620'; c.fillRect(x0, y0, size, size);
      c.strokeStyle = '#2c3343'; c.lineWidth = 2; c.strokeRect(x0, y0, size, size);
      // The crop is in camera space (un-mirrored); flip x so the hand looks like the player's mirror image.
      const X = (i: number) => x0 + (1 - hand.pts[2 * i]) * size;
      const Y = (i: number) => y0 + hand.pts[2 * i + 1] * size;
      c.strokeStyle = '#ffb84d'; c.lineWidth = 4; c.lineCap = 'round';
      for (const [a, b] of HAND_BONES) { c.beginPath(); c.moveTo(X(a), Y(a)); c.lineTo(X(b), Y(b)); c.stroke(); }
      c.fillStyle = '#ffffff';
      for (let i = 0; i < 21; i++) { c.beginPath(); c.arc(X(i), Y(i), [4, 8, 12, 16, 20].includes(i) ? 6 : 4, 0, Math.PI * 2); c.fill(); }
      c.fillStyle = '#e6e9ef'; c.font = 'bold 26px system-ui, sans-serif';
      c.fillText(`${hand.side === 'l' ? 'LEFT' : 'RIGHT'} · ${hand.shape.replace('_', ' ').toUpperCase()}`, x0 + 8, y0 - 10);
      if (hand.wrist) {
        const w = hand.wrist;
        const ud = w.up_down_deg > 15 ? `↑${w.up_down_deg}°` : w.up_down_deg < -15 ? `↓${-w.up_down_deg}°` : '';
        const lr = w.left_right_deg > 15 ? `→${w.left_right_deg}°` : w.left_right_deg < -15 ? `←${-w.left_right_deg}°` : '';
        c.fillStyle = '#ffd34d'; c.font = '20px system-ui, sans-serif';
        c.fillText(`wrist ${ud} ${lr} · palm ${w.palm}`.replace(/ +/g, ' '), x0 + 8, y0 + size - 10);
      }
    }
  }

  /** Joint angles (left | right) and the fastest-moving limb. */
  private drawBody(hud: HudState, now: number): void {
    const b = hud.body;
    if (!b || now - hud.bodyAt > 600) return;
    const c = this.ctx;
    const x0 = 880, y0 = 470, w = 360;
    const a = b.angles;
    const rows: [string, string, string][] = [
      ['elbow', 'elbow_l', 'elbow_r'], ['knee', 'knee_l', 'knee_r'],
      ['shoulder ↑', 'shoulder_raise_l', 'shoulder_raise_r'], ['shoulder dir', 'shoulder_plane_l', 'shoulder_plane_r'],
      ['hip ↑', 'hip_raise_l', 'hip_raise_r'], ['hip dir', 'hip_plane_l', 'hip_plane_r'],
    ];
    c.fillStyle = 'rgba(18, 22, 32, 0.92)'; c.fillRect(x0, y0, w, 330);
    c.fillStyle = '#7cff9b'; c.font = 'bold 22px system-ui, sans-serif';
    c.fillText('BODY ANGLES', x0 + 14, y0 + 30);
    c.fillStyle = '#5b6680'; c.font = '17px system-ui, sans-serif';
    c.fillText('left', x0 + 190, y0 + 30); c.fillText('right', x0 + 275, y0 + 30);
    c.font = '20px ui-monospace, monospace';
    const deg = (v: number | undefined) => (v === undefined ? '–' : `${v}°`).padStart(5);
    rows.forEach(([label, l, r], i) => {
      const y = y0 + 62 + i * 30;
      c.fillStyle = '#9aa3b5'; c.fillText(label, x0 + 14, y);
      c.fillStyle = '#e6e9ef'; c.fillText(deg(a[l]), x0 + 175, y); c.fillText(deg(a[r]), x0 + 265, y);
    });
    const y = y0 + 62 + rows.length * 30;
    c.fillStyle = '#9aa3b5';
    c.fillText(`trunk fwd ${deg(a.trunk_forward)} side ${deg(a.trunk_side)}`, x0 + 14, y);
    c.fillText(`twist ${deg(a.trunk_twist)}`, x0 + 14, y + 30);
    const fastest = Object.entries(b.speeds).sort((p, q) => q[1] - p[1])[0];
    if (fastest) { c.fillStyle = fastest[1] > 1.5 ? '#ffd34d' : '#9aa3b5'; c.fillText(`fastest: ${fastest[0]} ${fastest[1].toFixed(1)} m/s`, x0 + 14, y + 60); }
  }

  /** Expression label + score bars (top-right of the front view). */
  private drawFace(hud: HudState, now: number): void {
    const f = hud.face;
    if (!f || now - hud.faceAt > 600) return;
    const c = this.ctx;
    const x0 = 880, y0 = 120, w = 360;
    c.fillStyle = 'rgba(18, 22, 32, 0.92)'; c.fillRect(x0, y0, w, 330);
    c.fillStyle = '#ff78c8'; c.font = 'bold 34px system-ui, sans-serif';
    c.fillText(f.found ? f.expression.replace('_', ' ').toUpperCase() : 'NO FACE', x0 + 14, y0 + 44);
    if (!f.scores) return;
    const rows: [string, string][] = [['smile', 'smile'], ['sad', 'sad'], ['surprise', 'surprise'], ['angry', 'angry'],
      ['jaw_open', 'mouth open'], ['blink_l', 'left eye shut'], ['blink_r', 'right eye shut']];
    c.font = '19px system-ui, sans-serif';
    rows.forEach(([k, label], i) => {
      const v = Math.max(0, Math.min(1, f.scores![k] ?? 0));
      const y = y0 + 78 + i * 35;
      c.fillStyle = '#9aa3b5'; c.fillText(label, x0 + 14, y + 6);
      c.fillStyle = '#2a3040'; c.fillRect(x0 + 150, y - 10, 190, 18);
      c.fillStyle = v > 0.5 ? '#ff78c8' : '#7a5a8a'; c.fillRect(x0 + 150, y - 10, 190 * v, 18);
    });
  }

  private drawHud(hud: HudState, now: number): void {
    const c = this.ctx;
    c.fillStyle = '#121620'; c.fillRect(1300, 610, 580, 450);
    c.fillStyle = '#cfd5e1'; c.font = '21px ui-monospace, monospace';
    hud.lines.forEach((l, i) => c.fillText(l, 1316, 645 + i * 30));
    if (hud.setup) {
      c.fillStyle = '#ffd34d'; c.font = '30px system-ui, sans-serif';
      c.fillText(hud.setup, 60, 60);
    }
    const recent = hud.events.filter((e) => now - e.at < 1200);
    recent.slice(-4).forEach((e, i) => {
      c.globalAlpha = Math.max(0, 1 - (now - e.at) / 1200);
      c.fillStyle = '#ffffff'; c.font = 'bold 64px system-ui, sans-serif';
      c.fillText(e.text, 380, 220 + i * 76);
    });
    c.globalAlpha = 1;
  }
}

function isLeft(j: number): boolean {
  return (j >= J.LEFT_SHOULDER && j <= J.LEFT_HAND) || (j >= J.LEFT_HIP && j <= J.LEFT_TOE) || j === J.LEFT_EYE;
}
