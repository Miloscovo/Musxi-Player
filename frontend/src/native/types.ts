export interface PlayerState {
  readonly opened: boolean;
  readonly playing: boolean;
  readonly positionMs: number;
  readonly durationMs: number;
  readonly volumePercent: number;
  readonly trackId: string;
  readonly phase?: 'empty' | 'loading' | 'buffering' | 'seeking' | 'playing' | 'paused' | 'ended' | 'failed';
  readonly error?: string;
  readonly pending?: boolean;
  readonly requestedPlaying?: boolean;
}
export interface NativeCommands {
  'player.getState': { params: Record<string, never>; result: PlayerState };
  'player.pause': { params: Record<string, never>; result: PlayerState };
  'player.resume': { params: Record<string, never>; result: PlayerState };
  'player.seek': { params: { positionMs: number }; result: PlayerState };
  'player.setVolume': { params: { volumePercent: number }; result: PlayerState };
}
export interface NativeReply<T> { version: 1; result: T }
export interface RequestOptions { signal?: AbortSignal }
export class NativeError extends Error {
  readonly code: number;
  constructor(code: number, message: string) {
    super(message); this.name = 'NativeError'; this.code = code;
  }
}
export function parsePlayerState(value: unknown): PlayerState {
  if (!value || typeof value !== 'object') throw new NativeError(502, 'Invalid native state');
  const s = value as Record<string, unknown>;
  const milliseconds = (n: unknown) => typeof n === 'number' && Number.isInteger(n) && n >= 0 && n <= 0xffffffff;
  if (typeof s.opened !== 'boolean' || typeof s.playing !== 'boolean' ||
      !milliseconds(s.positionMs) || !milliseconds(s.durationMs) ||
      typeof s.volumePercent !== 'number' || !Number.isInteger(s.volumePercent) ||
      s.volumePercent < 0 || s.volumePercent > 100 || typeof s.trackId !== 'string')
    throw new NativeError(502, 'Invalid native state');
  if ((s.phase !== undefined && (typeof s.phase !== 'string' || !['empty', 'loading', 'buffering', 'seeking', 'playing', 'paused', 'ended', 'failed'].includes(s.phase))) ||
      (s.error !== undefined && typeof s.error !== 'string') ||
      (s.pending !== undefined && typeof s.pending !== 'boolean') ||
      (s.requestedPlaying !== undefined && typeof s.requestedPlaying !== 'boolean'))
    throw new NativeError(502, 'Invalid native operation state');
  return Object.freeze({ opened: s.opened, playing: s.playing,
    positionMs: s.positionMs as number, durationMs: s.durationMs as number,
    volumePercent: s.volumePercent, trackId: s.trackId,
    phase: s.phase as PlayerState['phase'], error: s.error as string | undefined,
    pending: s.pending as boolean | undefined, requestedPlaying: s.requestedPlaying as boolean | undefined });
}
