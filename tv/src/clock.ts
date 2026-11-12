// NTP-style clock sync (docs/06 §6). TV clock = performance.now() in µs.

export function nowUs(): number {
  return performance.now() * 1000;
}

interface Sample { offsetUs: number; rttUs: number }

/** Keeps the lowest-RTT offset estimate from the last [windowSize] ping/pong exchanges. */
export class ClockSync {
  private samples: Sample[] = [];
  constructor(private readonly windowSize = 30) {}

  /** t0, t3 in TV µs; t1, t2 in phone µs. */
  addSample(t0: number, t1: number, t2: number, t3: number): void {
    const offsetUs = ((t1 - t0) + (t2 - t3)) / 2;
    const rttUs = (t3 - t0) - (t2 - t1);
    this.samples.push({ offsetUs, rttUs });
    if (this.samples.length > this.windowSize) this.samples.shift();
  }

  get ready(): boolean { return this.samples.length > 0; }

  /** phone_time ≈ tv_time + offset */
  get offsetUs(): number { return this.best()?.offsetUs ?? NaN; }
  get rttUs(): number { return this.best()?.rttUs ?? NaN; }

  toPhone(tvUs: number): number { return tvUs + this.offsetUs; }

  private best(): Sample | undefined {
    let b: Sample | undefined;
    for (const s of this.samples) if (!b || s.rttUs < b.rttUs) b = s;
    return b;
  }
}

/** Rolling window with percentile queries. */
export class Rolling {
  private values: number[] = [];
  constructor(private readonly size = 300) {}
  add(v: number): void {
    if (!isFinite(v)) return;
    this.values.push(v);
    if (this.values.length > this.size) this.values.shift();
  }
  p(q: number): number {
    if (this.values.length === 0) return NaN;
    const s = this.values.slice().sort((a, b) => a - b);
    return s[Math.min(s.length - 1, Math.floor((s.length - 1) * q))];
  }
  get count(): number { return this.values.length; }
}

/** Events per second over the last second. */
export class RateMeter {
  private times: number[] = [];
  tick(now = performance.now()): void {
    this.times.push(now);
    while (this.times.length && now - this.times[0] > 1000) this.times.shift();
  }
  get rate(): number { return this.times.length; }
}
