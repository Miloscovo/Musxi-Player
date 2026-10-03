import { request, type CefTransport } from './transport.ts';
import { parsePlayerState, type RequestOptions, type NativeCommands } from './types.ts';
import { subscribe, type PlayerEvent } from './events.ts';
import type { NativeError } from './types.ts';
import { createLibraryClient } from './library.ts';
import { createWindowClient } from './window.ts';
import { createDemoRuntime, isDemoMode, openDemoPlaylist, playDemo, searchDemo } from '../demo/context.ts';

export function createNativeClient(host: CefTransport) {
  const demo = isDemoMode() ? createDemoRuntime() : undefined;
  if (demo) {
    const demoState = () => demo.player;
    const windowState = { enabled: false, maximized: false } as const;
    return Object.freeze({
      window: Object.freeze({ getState: async () => windowState, minimize: async () => windowState, maximize: async () => windowState, close: async () => windowState, setTheme: async () => windowState }),
      library: Object.freeze({
        getState: async (page = 1) => ({ ...demo.library, tracks: page === 1 ? demo.library.tracks : [], search: demo.library.search }),
        image: async () => '',
        search: async (keywords: string) => { searchDemo(demo, keywords); return { id: 'demo-search' }; },
        open: async (id: string) => { openDemoPlaylist(demo, id); return { id }; },
        play: async (_source: 'search' | 'library' | 'queue', id: string) => { const song = [...demo.library.tracks, ...demo.library.search].find(item => item.id === id); if (song) playDemo(demo, song); return { id }; },
        skip: async () => ({ id: 'demo-skip' }), menu: async () => ({ id: 'demo-menu' }), favorite: async () => ({ id: 'demo-favorite' }), add: async () => ({ id: 'demo-add' }),
        login: async () => ({ id: 'demo-login' }), logout: async () => ({ id: 'demo-logout' }), sync: async () => ({ id: 'demo-sync' }), cancel: async () => ({ id: 'demo-cancel' }),
      }),
      player: Object.freeze({
        pause: async () => { demo.player = { ...demo.player, playing: false, requestedPlaying: false, phase: 'paused' }; return demoState(); },
        resume: async () => { demo.player = { ...demo.player, playing: true, requestedPlaying: true, phase: 'playing' }; return demoState(); },
        seek: async (positionMs: number) => { demo.player = { ...demo.player, positionMs: Math.max(0, Math.min(positionMs, demo.player.durationMs)) }; return demoState(); },
        setVolume: async (volumePercent: number) => { demo.player = { ...demo.player, volumePercent: Math.max(0, Math.min(100, volumePercent)) }; return demoState(); },
        getState: async () => demoState(), subscribe: () => () => {},
      }),
    });
  }
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
