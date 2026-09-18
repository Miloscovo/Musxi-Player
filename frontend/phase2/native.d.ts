export interface PlayerState {
  opened: boolean;
  playing: boolean;
  positionMs: number;
  durationMs: number;
  volumePercent: number;
  trackId: string;
}
export interface NativeCommands {
  "player.getState": { params: Record<string, never>; result: PlayerState };
}
export interface NativeReply<T> { version: 1; result: T }
declare global {
  interface Window {
    musxiNative: {
      request<K extends keyof NativeCommands>(
        command: K, params?: NativeCommands[K]["params"]
      ): Promise<NativeReply<NativeCommands[K]["result"]>>;
      getState(): Promise<PlayerState>;
    };
  }
}
