import { ClockSync, nowUs } from './clock';
import { decodeTrackingFrame, PongMsg, ServerMsg, TrackingFrame } from './protocol';

export const DEFAULT_PORT = 47800;
export const PATH = '/msp/v1';

export type ConnState = 'idle' | 'connecting' | 'connected' | 'reconnecting';

export interface ConnectionEvents {
  onState(state: ConnState, detail?: string): void;
  onFrame(frame: TrackingFrame, receiveTvUs: number): void;
  onMessage(msg: ServerMsg, receiveTvUs: number): void;
}

/**
 * MSP WebSocket client: hello, clock sync (burst then 1 Hz), automatic reconnect with
 * exponential backoff, and periodic tv_stats reports back to the phone.
 */
export class MspConnection {
  readonly clock = new ClockSync();
  private ws: WebSocket | null = null;
  private url = '';
  private backoffMs = 250;
  private pingTimer: number | undefined;
  private statsTimer: number | undefined;
  private retryTimer: number | undefined;
  private stopped = true;
  private pendingStats: number[][] = [];
  private lastMessageAt = 0;
  private watchdog: number | undefined;
  /** Reconnect when nothing (no frame, no pong) arrives for this long: catches half-open sockets. */
  static readonly SILENCE_MS = 3000;

  constructor(private readonly ev: ConnectionEvents, private readonly platform: string) {}

  get connected(): boolean { return !!this.ws && this.ws.readyState === WebSocket.OPEN; }
  get target(): string { return this.url; }

  connect(host: string, port = DEFAULT_PORT): void {
    this.close();
    this.url = `ws://${host}:${port}${PATH}`;
    this.stopped = false;
    this.backoffMs = 250;
    this.open();
  }

  close(): void {
    this.stopped = true;
    clearTimeout(this.retryTimer);
    this.stopTimers();
    if (this.ws) { this.ws.onclose = null; this.ws.close(); this.ws = null; }
  }

  send(obj: object): void {
    if (this.connected) this.ws!.send(JSON.stringify(obj));
  }

  /** Queue a per-frame timing sample: [frameSeq, tCaptureUs, receivePhoneUs, renderPhoneUs]. */
  reportFrame(sample: number[]): void {
    this.pendingStats.push(sample);
    if (this.pendingStats.length > 600) this.pendingStats.shift();
  }

  private open(): void {
    this.ev.onState(this.backoffMs > 250 ? 'reconnecting' : 'connecting', this.url);
    const ws = new WebSocket(this.url);
    ws.binaryType = 'arraybuffer';
    this.ws = ws;
    ws.onopen = () => {
      this.backoffMs = 250;
      this.ev.onState('connected', this.url);
      ws.send(JSON.stringify({ type: 'hello', hub_version: 'poc-0.1', protocols: ['msp/1'], platform: this.platform, tv_name: navigator.userAgent }));
      this.lastMessageAt = performance.now();
      this.startTimers();
    };
    ws.onmessage = (e: MessageEvent) => {
      const t = nowUs();
      this.lastMessageAt = performance.now();
      if (typeof e.data === 'string') {
        let msg: ServerMsg;
        try { msg = JSON.parse(e.data); } catch { return; }
        if (msg.type === 'pong') {
          const p = msg as PongMsg;
          this.clock.addSample(p.t0, p.t1, p.t2, t);
        }
        this.ev.onMessage(msg, t);
      } else {
        const f = decodeTrackingFrame(e.data as ArrayBuffer);
        if (f) this.ev.onFrame(f, t);
      }
    };
    ws.onclose = () => {
      this.stopTimers();
      this.ws = null;
      if (this.stopped) return;
      this.ev.onState('reconnecting', `retry in ${this.backoffMs} ms`);
      this.retryTimer = setTimeout(() => this.open(), this.backoffMs) as unknown as number;
      this.backoffMs = Math.min(4000, this.backoffMs * 2);
    };
    ws.onerror = () => { /* onclose follows */ };
  }

  private startTimers(): void {
    let burst = 10;
    const ping = () => {
      this.send({ type: 'ping', t0: nowUs() });
      this.pingTimer = setTimeout(ping, burst-- > 0 ? 100 : 1000) as unknown as number;
    };
    ping();
    this.statsTimer = setInterval(() => this.flushStats(), 2000) as unknown as number;
    this.watchdog = setInterval(() => {
      if (this.ws && performance.now() - this.lastMessageAt > MspConnection.SILENCE_MS) {
        this.ev.onState('reconnecting', 'no data for 3 s');
        this.ws.close();          // onclose schedules the reconnect
      }
    }, 1000) as unknown as number;
  }

  private stopTimers(): void {
    clearTimeout(this.pingTimer);
    clearInterval(this.statsTimer);
    clearInterval(this.watchdog);
  }

  private flushStats(): void {
    if (!this.pendingStats.length || !this.clock.ready) return;
    this.send({
      type: 'tv_stats',
      samples: this.pendingStats.map((s) => s.map(Math.round)),
      clock: { offset_us: Math.round(this.clock.offsetUs), rtt_us: Math.round(this.clock.rttUs) },
    });
    this.pendingStats = [];
  }
}
