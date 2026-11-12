import { DEFAULT_PORT, PATH } from './connection';

export interface PhoneCandidate { name: string; host: string; port: number }

/**
 * Bridge injected by the Android TV shell (tvapp module). It discovers phones via NSD
 * (_motionhub._tcp) and pushes them with window.motionhubOnPhones(json).
 */
interface MotionHubHost {
  getPhones(): string;          // JSON PhoneCandidate[]
  getLocalIp(): string;
  platform(): string;           // "androidtv"
}

declare global {
  interface Window {
    MotionHubHost?: MotionHubHost;
    motionhubOnPhones?: (json: string) => void;
    webapis?: { network?: { getIp(): string; getSubnetMask?(): string } };
  }
}

export function platformName(): string {
  if (window.MotionHubHost) return window.MotionHubHost.platform();
  if (window.webapis?.network) return 'tizen';
  return 'browser';
}

export function hostPhones(): PhoneCandidate[] {
  try { return window.MotionHubHost ? JSON.parse(window.MotionHubHost.getPhones()) : []; } catch { return []; }
}

export function localIp(): string | null {
  try {
    if (window.MotionHubHost) return window.MotionHubHost.getLocalIp() || null;
    if (window.webapis?.network) return window.webapis.network.getIp() || null;
  } catch { /* not available */ }
  return null;
}

/**
 * Subnet probe (docs/02 §6.1 strategy B): open WebSocket probes to every host of a /24
 * and resolve with the first one that answers with an MSP `welcome`.
 */
export function probeSubnet(prefix: string, opts: { concurrency?: number; timeoutMs?: number; onProgress?: (done: number) => void } = {}): Promise<string | null> {
  const concurrency = opts.concurrency ?? 24;
  const timeoutMs = opts.timeoutMs ?? 700;
  const hosts: string[] = [];
  for (let i = 1; i < 255; i++) hosts.push(`${prefix}.${i}`);
  let next = 0; let done = 0; let found: string | null = null;

  return new Promise((resolve) => {
    const tryOne = (): void => {
      if (found) return;
      if (next >= hosts.length) { if (done >= hosts.length) resolve(null); return; }
      const host = hosts[next++];
      let settled = false;
      let ws: WebSocket;
      const finish = (ok: boolean) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        try { ws.close(); } catch { /* ignore */ }
        done++;
        opts.onProgress?.(done);
        if (ok && !found) { found = host; resolve(host); return; }
        tryOne();
      };
      try { ws = new WebSocket(`ws://${host}:${DEFAULT_PORT}${PATH}`); } catch { done++; tryOne(); return; }
      const timer = setTimeout(() => finish(false), timeoutMs);
      ws.onmessage = (e) => {
        try { finish(typeof e.data === 'string' && JSON.parse(e.data).type === 'welcome'); } catch { finish(false); }
      };
      ws.onerror = () => finish(false);
      ws.onclose = () => finish(false);
    };
    for (let i = 0; i < concurrency; i++) tryOne();
  });
}

export function subnetPrefix(ip: string): string | null {
  const m = /^(\d+\.\d+\.\d+)\.\d+$/.exec(ip.trim());
  return m ? m[1] : null;
}
