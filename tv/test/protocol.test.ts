import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { decodeTrackingFrame } from '../src/protocol';
import { ClockSync } from '../src/clock';

const golden = JSON.parse(readFileSync(new URL('../../protocol/golden/tracking_frame_v1.json', import.meta.url), 'utf8'));
const goldenV2 = JSON.parse(readFileSync(new URL('../../protocol/golden/tracking_frame_v2.json', import.meta.url), 'utf8'));

function hexToBuffer(hex: string): ArrayBuffer {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
  return out.buffer;
}

describe('MSP v1 TrackingFrame', () => {
  it('decodes the golden vector exactly', () => {
    const f = decodeTrackingFrame(hexToBuffer(golden.hex))!;
    const g = golden.frame;
    expect(f).not.toBeNull();
    expect(f.version).toBe(g.version);
    expect(f.flags).toBe(g.flags);
    expect(f.frameSeq).toBe(g.frame_seq);
    expect(f.tCaptureUs).toBe(g.t_capture_us);
    expect(f.sendDelayUs).toBe(g.send_delay_us);
    expect(f.jointCount).toBe(g.joint_count);
    const p = f.players[0], gp = g.players[0];
    expect(p.playerId).toBe(gp.player_id);
    expect(p.trackingState).toBe(gp.tracking_state);
    expect(p.confidence).toBe(gp.confidence);
    expect(p.zConfidence).toBe(gp.z_confidence);
    expect(p.facingYawCentideg).toBe(gp.facing_yaw_centideg);
    expect(p.rootMm).toEqual(gp.root_mm);
    gp.joints.forEach((j: { x: number; y: number; z: number; conf: number; state: number }, i: number) => {
      expect([p.jointsMm[3 * i], p.jointsMm[3 * i + 1], p.jointsMm[3 * i + 2]]).toEqual([j.x, j.y, j.z]);
      expect(p.jointConf[i]).toBe(j.conf);
      expect(p.jointState[i]).toBe(j.state);
    });
  });

  it('decodes the v2 golden vector including head orientation', () => {
    const f = decodeTrackingFrame(hexToBuffer(goldenV2.hex))!;
    const gp = goldenV2.frame.players[0];
    const p = f.players[0];
    expect(f.version).toBe(2);
    expect(p.head).not.toBeNull();
    expect(p.head!.yawDeg).toBeCloseTo(gp.head_yaw_centideg / 100);
    expect(p.head!.pitchDeg).toBeCloseTo(gp.head_pitch_centideg / 100);
    expect(p.head!.rollDeg).toBeCloseTo(gp.head_roll_centideg / 100);
    expect(p.head!.confidence).toBeCloseTo(gp.head_confidence / 255);
    gp.joints.forEach((j: { x: number; y: number; z: number }, i: number) => {
      expect([p.jointsMm[3 * i], p.jointsMm[3 * i + 1], p.jointsMm[3 * i + 2]]).toEqual([j.x, j.y, j.z]);
    });
  });

  it('v1 frames have no head', () => {
    expect(decodeTrackingFrame(hexToBuffer(golden.hex))!.players[0].head).toBeNull();
  });

  it('rejects truncated or foreign messages', () => {
    const buf = hexToBuffer(golden.hex);
    expect(decodeTrackingFrame(buf.slice(0, 100))).toBeNull();
    const other = new Uint8Array(buf.slice(0)); other[0] = 0x02;
    expect(decodeTrackingFrame(other.buffer)).toBeNull();
  });
});

describe('ClockSync', () => {
  it('recovers a fixed offset and prefers the lowest-RTT sample', () => {
    const c = new ClockSync();
    const offset = 5_000_000;           // phone clock is 5 s ahead
    c.addSample(1000, 1000 + offset + 40_000, 1000 + offset + 41_000, 1000 + 90_000);   // asymmetric, slow
    c.addSample(2000, 2000 + offset + 1_500, 2000 + offset + 1_600, 2000 + 3_100);       // fast, symmetric
    expect(c.rttUs).toBe(3_000);
    expect(Math.abs(c.offsetUs - offset)).toBeLessThan(100);
    expect(c.toPhone(10_000)).toBeCloseTo(10_000 + c.offsetUs);
  });
});
