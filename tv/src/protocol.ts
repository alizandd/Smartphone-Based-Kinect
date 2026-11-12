// MSP decoding (docs/06 §4–5). Golden vectors: protocol/golden/tracking_frame_v1.json, _v2.json.
// v2 adds head orientation to a 20-byte player header (v1: 12 bytes). Both are accepted.

export const MSG_TRACKING_FRAME = 0x01;
export const FLAG_PREDICTED = 1;
export const FLAG_DEGRADED = 2;
export const FLAG_UNCALIBRATED = 4;

/** Canonical joint indices (docs/06 §3). */
export const J = {
  PELVIS: 0, SPINE_MID: 1, NECK: 2, HEAD: 3, NOSE: 4,
  LEFT_SHOULDER: 5, LEFT_ELBOW: 6, LEFT_WRIST: 7, LEFT_HAND: 8,
  RIGHT_SHOULDER: 9, RIGHT_ELBOW: 10, RIGHT_WRIST: 11, RIGHT_HAND: 12,
  LEFT_HIP: 13, LEFT_KNEE: 14, LEFT_ANKLE: 15, LEFT_HEEL: 16, LEFT_TOE: 17,
  RIGHT_HIP: 18, RIGHT_KNEE: 19, RIGHT_ANKLE: 20, RIGHT_HEEL: 21, RIGHT_TOE: 22,
  LEFT_EYE: 23, RIGHT_EYE: 24,
} as const;

export const BONES: ReadonlyArray<[number, number]> = [
  [J.PELVIS, J.SPINE_MID], [J.SPINE_MID, J.NECK], [J.NECK, J.HEAD],
  [J.NECK, J.LEFT_SHOULDER], [J.LEFT_SHOULDER, J.LEFT_ELBOW], [J.LEFT_ELBOW, J.LEFT_WRIST], [J.LEFT_WRIST, J.LEFT_HAND],
  [J.NECK, J.RIGHT_SHOULDER], [J.RIGHT_SHOULDER, J.RIGHT_ELBOW], [J.RIGHT_ELBOW, J.RIGHT_WRIST], [J.RIGHT_WRIST, J.RIGHT_HAND],
  [J.PELVIS, J.LEFT_HIP], [J.LEFT_HIP, J.LEFT_KNEE], [J.LEFT_KNEE, J.LEFT_ANKLE], [J.LEFT_ANKLE, J.LEFT_HEEL], [J.LEFT_HEEL, J.LEFT_TOE],
  [J.PELVIS, J.RIGHT_HIP], [J.RIGHT_HIP, J.RIGHT_KNEE], [J.RIGHT_KNEE, J.RIGHT_ANKLE], [J.RIGHT_ANKLE, J.RIGHT_HEEL], [J.RIGHT_HEEL, J.RIGHT_TOE],
];

export interface PlayerFrame {
  playerId: number;
  trackingState: number;       // 0 lost, 1 inferred, 2 tracked
  confidence: number;          // 0..255
  zConfidence: number;         // 0..255
  facingYawCentideg: number;
  rootMm: [number, number, number];
  /** Head orientation in degrees (v2+); null when absent or not estimated. */
  head: { yawDeg: number; pitchDeg: number; rollDeg: number; confidence: number } | null;
  jointsMm: Int16Array;        // 3 × jointCount, Play Space millimetres
  jointConf: Uint8Array;
  jointState: Uint8Array;
}

export interface TrackingFrame {
  version: number;
  flags: number;
  frameSeq: number;
  tCaptureUs: number;          // phone clock
  sendDelayUs: number;
  jointCount: number;
  players: PlayerFrame[];
}

/** Decodes a binary TrackingFrame. Returns null for other binary message types. */
export function decodeTrackingFrame(buf: ArrayBuffer): TrackingFrame | null {
  const dv = new DataView(buf);
  if (dv.byteLength < 24 || dv.getUint8(0) !== MSG_TRACKING_FRAME) return null;
  const version = dv.getUint8(1);
  const flags = dv.getUint16(2, true);
  const frameSeq = dv.getUint32(4, true);
  // u64 without BigInt (older TV engines): exact up to 2^53 µs.
  const tCaptureUs = dv.getUint32(8, true) + dv.getUint32(12, true) * 4294967296;
  const sendDelayUs = dv.getUint32(16, true);
  const playerCount = dv.getUint8(20);
  const jointCount = dv.getUint8(21);
  const players: PlayerFrame[] = [];
  let o = 24;
  const playerHeader = version >= 2 ? 20 : 12;
  const need = 24 + playerCount * (playerHeader + 8 * jointCount);
  if (dv.byteLength < need) return null;
  for (let p = 0; p < playerCount; p++) {
    const jointsMm = new Int16Array(jointCount * 3);
    const jointConf = new Uint8Array(jointCount);
    const jointState = new Uint8Array(jointCount);
    const player: PlayerFrame = {
      playerId: dv.getUint8(o),
      trackingState: dv.getUint8(o + 1),
      confidence: dv.getUint8(o + 2),
      zConfidence: dv.getUint8(o + 3),
      facingYawCentideg: dv.getInt16(o + 4, true),
      rootMm: [dv.getInt16(o + 6, true), dv.getInt16(o + 8, true), dv.getInt16(o + 10, true)],
      head: null,
      jointsMm, jointConf, jointState,
    };
    if (version >= 2) {
      const conf = dv.getUint8(o + 18);
      if (conf > 0) {
        player.head = {
          yawDeg: dv.getInt16(o + 12, true) / 100,
          pitchDeg: dv.getInt16(o + 14, true) / 100,
          rollDeg: dv.getInt16(o + 16, true) / 100,
          confidence: conf / 255,
        };
      }
    }
    o += playerHeader;
    for (let j = 0; j < jointCount; j++) {
      jointsMm[3 * j] = dv.getInt16(o, true);
      jointsMm[3 * j + 1] = dv.getInt16(o + 2, true);
      jointsMm[3 * j + 2] = dv.getInt16(o + 4, true);
      jointConf[j] = dv.getUint8(o + 6);
      jointState[j] = dv.getUint8(o + 7) & 0x03;
      o += 8;
    }
    players.push(player);
  }
  return { version, flags, frameSeq, tCaptureUs, sendDelayUs, jointCount, players };
}

// ---- JSON messages (Phase 0 subset, protocol/README.md) ----

export interface GestureMsg {
  type: 'gesture'; id: string; phase: string; player_id: number; event_seq: number;
  t_source_us: number; confidence: number; attributes: Record<string, number>;
}
export interface ProbeMsg { type: 'probe'; t_source_us: number; luma: number }
export interface PongMsg { type: 'pong'; t0: number; t1: number; t2: number }
export interface WelcomeMsg { type: 'welcome'; session_id: string; device_model: string; sensor_version: string }
export interface SetupStatusMsg { type: 'setup_status'; stage: string; progress: number; hint: string }
export interface CalibrationDoneMsg {
  type: 'calibration_done'; stature_m: number; camera_height_m: number; distance_m: number; pitch_deg: number; roll_deg: number;
}
export interface WristInfo { up_down_deg: number; left_right_deg: number; flex_deg: number; deviation_deg: number; palm: string }
export interface HandInfo { side: 'l' | 'r'; shape: string; raw_shape: string; conf: number; box: [number, number, number]; pts: number[]; wrist?: WristInfo }
export interface FaceMsg {
  type: 'face'; t_source_us: number; found: boolean; expression: string; box: [number, number, number];
  scores?: Record<string, number>;
}
export interface HandsMsg { type: 'hands'; t_source_us: number; age_us: number; hands: HandInfo[] }
/** MediaPipe hand connections (21 points). */
export const HAND_BONES: ReadonlyArray<[number, number]> = [
  [0, 1], [1, 2], [2, 3], [3, 4], [0, 5], [5, 6], [6, 7], [7, 8], [5, 9], [9, 10], [10, 11], [11, 12],
  [9, 13], [13, 14], [14, 15], [15, 16], [13, 17], [0, 17], [17, 18], [18, 19], [19, 20],
];
export interface BodyMsg { type: 'body'; t_source_us: number; angles: Record<string, number>; speeds: Record<string, number> }
export type ServerMsg = BodyMsg | FaceMsg | HandsMsg | GestureMsg | ProbeMsg | PongMsg | WelcomeMsg | SetupStatusMsg | CalibrationDoneMsg | { type: string };
