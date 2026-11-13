import { request, type CefTransport } from './transport.ts';
import { NativeError } from './types.ts';
export const wallpaperOrigin = 'https://wallpaper.musxi.invalid';
export interface WallpaperPlayback { fps: 30 | 60; quality: 'standard' | 'high' }
export interface DynamicWallpaperState extends WallpaperPlayback { enabled: boolean; selected: string; directories: string[] }
export interface WallpaperProject { id: string; title: string; type: 'scene' | 'video'; preview: string; selected: boolean; source?: string }
export interface WallpaperSession { url: string; type: 'scene' | 'video'; file: string }
export interface WallpaperScanReport { directory: string; count: number; invalid: number; unsupported: number; missing: number; error: string }
export interface WallpaperListing { items: WallpaperProject[]; directories: string[]; reports?: WallpaperScanReport[] }
export function parseDynamicState(value: unknown): DynamicWallpaperState {
  const state = value as DynamicWallpaperState;
  if (!state || typeof state.enabled !== 'boolean' || typeof state.selected !== 'string' || !Array.isArray(state.directories)
    || !state.directories.every(value => typeof value === 'string')) throw new NativeError(502, '无效动态壁纸设置');
  if (state.fps !== undefined && state.fps !== 30 && state.fps !== 60
    || state.quality !== undefined && state.quality !== 'standard' && state.quality !== 'high') throw new NativeError(502, '无效壁纸播放设置');
  return { ...state, fps: state.fps ?? 60, quality: state.quality ?? 'standard' };
}
export function parseWallpaperListing(value: unknown): WallpaperListing {
  const list = value as WallpaperListing;
  if (!list || !Array.isArray(list.items) || !Array.isArray(list.directories) || !list.directories.every(v => typeof v === 'string')
    || !list.items.every(p => p && typeof p.id === 'string' && /^\d+$/.test(p.id) && typeof p.title === 'string' && (p.type === 'scene' || p.type === 'video')
      && typeof p.selected === 'boolean' && (p.source === undefined || typeof p.source === 'string')
      && (p.preview === '' || typeof p.preview === 'string' && new RegExp(`^https://wallpaper\\.musxi\\.invalid/thumbnail/${p.id}(\\?v=\\d+)?$`).test(p.preview)))
    || list.reports !== undefined && (!Array.isArray(list.reports) || !list.reports.every(r => r && typeof r.directory === 'string' && typeof r.error === 'string'
      && [r.count,r.invalid,r.unsupported,r.missing].every(n => Number.isSafeInteger(n) && n >= 0))))
    throw new NativeError(502, '无效壁纸列表');
  return list;
}
export function parseWallpaperSession(value: unknown): WallpaperSession {
  const session = value as WallpaperSession;
  if (!session || typeof session.url !== 'string' || !/^https:\/\/wallpaper\.musxi\.invalid\/session-\d+\/player\.html$/.test(session.url)
    || (session.type !== 'scene' && session.type !== 'video') || typeof session.file !== 'string') throw new NativeError(502, '无效壁纸会话');
  return session;
}
export function createDynamicWallpaperClient(host: CefTransport) {
  const call = (name: string, params = {}, timeout = 5000) => request(host, `dynamicWallpaper.${name}`, params, {}, timeout);
  return Object.freeze({
    getState: async () => parseDynamicState(await call('getState')),
    setEnabled: async (enabled: boolean) => parseDynamicState(await call('setEnabled', { enabled })),
    setPlayback: async (playback: WallpaperPlayback) => parseDynamicState(await call('setPlayback', playback)),
    scan: async () => parseWallpaperListing(await call('scan', {}, 120000)),
    addDirectory: async () => parseDynamicState(await call('addDirectory', {}, 24*60*60*1000)),
    removeDirectory: async (directory: string) => parseDynamicState(await call('removeDirectory', { directory })),
    open: async (id: string) => parseWallpaperSession(await call('open', { id })),
    apply: async (id: string) => parseDynamicState(await call('apply', { id })),
    release: async (url: string) => { await call('release', { url }); },
  });
}
