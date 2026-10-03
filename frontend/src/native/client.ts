import { createDemoTransport, isDemoMode } from '../demo/context.ts';
import { request, type CefTransport } from './transport.ts';
import { parsePlayerState, type RequestOptions, type NativeCommands } from './types.ts';
import { subscribe, type PlayerEvent } from './events.ts';
import type { NativeError } from './types.ts';
import { createLibraryClient } from './library.ts';
import { createWindowClient } from './window.ts';

export function createNativeClient(host: CefTransport) {
  if (isDemoMode(host)) host = createDemoTransport();
  async function command<K extends keyof NativeCommands>(name: K, params: NativeCommands[K]['params'], options?: RequestOptions) {
    return parsePlayerState(await request(host, name, params, options));
  }
  return Object.freeze({ window: createWindowClient(host), library: createLibraryClient(host), player: Object.freeze({
    pause: (options?: RequestOptions) => command('player.pause', {}, options),
    resume: (options?: RequestOptions) => command('player.resume', {}, options),
    seek: (positionMs: number, options?: RequestOptions) => command('player.seek', { positionMs }, options),
    setVolume: (volumePercent: number, options?: RequestOptions) => command('player.setVolume', { volumePercent }, options),
    subscribe: (listener: (event: PlayerEvent) => void, onError: (error: NativeError) => void) => subscribe(host, listener, onError),
    async getState(options?: RequestOptions) {
      const command: keyof NativeCommands = 'player.getState';
      return parsePlayerState(await request(host, command, {}, options));
    }
  }) });
}
export const native = createNativeClient(globalThis as CefTransport);
