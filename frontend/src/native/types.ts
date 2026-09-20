export interface PlayerState {
  readonly opened: boolean;
  readonly playing: boolean;
  readonly positionMs: number;
  readonly durationMs: number;
  readonly volumePercent: number;
  readonly trackId: string;
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
  return Object.freeze({ opened: s.opened, playing: s.playing,
    positionMs: s.positionMs as number, durationMs: s.durationMs as number,
    volumePercent: s.volumePercent, trackId: s.trackId });
}
