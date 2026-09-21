import { NativeError } from './types.ts';
import { request, type CefTransport } from './transport.ts';
export interface Track { id: string; name: string; artist: string; cover: string; duration: number; count: number; editable: boolean }
export interface LibraryState {
  connected: boolean; busy: boolean; user: string; status: string; notice: string; qr: string; avatar: string;
  playlists: Track[]; tracks: Track[]; trackCount: number; playlistId: string; playlistName: string;
  search: Track[]; keywords: string; searchPage: number; searchMore: boolean; searchTotal: number;
  now: { name: string; artist: string; cover: string };
  menu: { id: string; liked: boolean; canFavorite: boolean; playlists: Track[] };
  operation: { id: string; status: 'idle' | 'pending' | 'completed' | 'failed' | 'cancelled'; kind: string; error: string };
}
export interface LibraryCommands {
  'library.search': { keywords: string; page: number };
  'library.open': { id: string };
  'library.play': { source: 'search' | 'library' | 'queue'; id: string };
  'library.skip': { delta: -1 | 1 };
  'library.menu': { id: string };
  'library.favorite': { id: string; enabled: boolean };
  'library.add': { id: string; playlistId: string };
  'library.login': Record<string, never>; 'library.logout': Record<string, never>;
  'library.sync': Record<string, never>; 'library.cancel': Record<string, never>;
}
const object = (value: unknown): Record<string, unknown> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new NativeError(502, 'Invalid library object');
  return value as Record<string, unknown>;
};
const fields = (value: Record<string, unknown>, names: string[], type: string) => {
  for (const name of names) if (typeof value[name] !== type || (type === 'number' && (!Number.isFinite(value[name]) || (value[name] as number) < 0))) throw new NativeError(502, `Invalid library field: ${name}`);
};
export function parseLibraryState(value: unknown): LibraryState {
  const v = object(value);
  fields(v, ['connected', 'busy', 'searchMore'], 'boolean');
  fields(v, ['user', 'status', 'notice', 'qr', 'avatar', 'playlistId', 'playlistName', 'keywords'], 'string');
  fields(v, ['trackCount', 'searchPage', 'searchTotal'], 'number');
  const rows = (value: unknown) => {
    if (!Array.isArray(value)) throw new NativeError(502, 'Invalid library rows');
    for (const row of value) {
      const r = object(row); fields(r, ['id', 'name', 'artist', 'cover'], 'string');
      fields(r, ['duration', 'count'], 'number'); fields(r, ['editable'], 'boolean');
    }
  };
  for (const key of ['playlists', 'tracks', 'search']) rows(v[key]);
  const now = object(v.now); fields(now, ['name', 'artist', 'cover'], 'string');
  const menu = object(v.menu); fields(menu, ['id'], 'string'); fields(menu, ['liked', 'canFavorite'], 'boolean'); rows(menu.playlists);
  const operation = object(v.operation); fields(operation, ['id', 'status', 'kind', 'error'], 'string');
  if (!['idle', 'pending', 'completed', 'failed', 'cancelled'].includes(operation.status as string)) throw new NativeError(502, 'Invalid operation status');
  return value as LibraryState;
}
export const safeImage = (value: string) => /^data:image\/(png|jpeg|webp);base64,[A-Za-z0-9+/=\r\n]+$/.test(value) ? value : '';
export function createLibraryClient(host: CefTransport) {
  async function dispatch<K extends keyof LibraryCommands>(command: K, params: LibraryCommands[K], signal?: AbortSignal) {
    const result = object(await request(host, command, params, { signal }));
    fields(result, ['id'], 'string'); return { id: result.id as string };
  }
  return Object.freeze({
    async getState(page = 1, signal?: AbortSignal) { return parseLibraryState(await request(host, 'library.getState', { page }, { signal })); },
    async image(url: string, signal?: AbortSignal) {
      const value = await request(host, 'library.image', { url }, { signal });
      if (typeof value !== 'string') throw new NativeError(502, 'Invalid image'); return safeImage(value);
    },
    search: (keywords: string, page: number, signal?: AbortSignal) => dispatch('library.search', { keywords, page }, signal),
    open: (id: string, signal?: AbortSignal) => dispatch('library.open', { id }, signal),
    play: (source: 'search' | 'library' | 'queue', id: string, signal?: AbortSignal) => dispatch('library.play', { source, id }, signal),
    skip: (delta: -1 | 1, signal?: AbortSignal) => dispatch('library.skip', { delta }, signal),
    menu: (id: string, signal?: AbortSignal) => dispatch('library.menu', { id }, signal),
    favorite: (id: string, enabled: boolean, signal?: AbortSignal) => dispatch('library.favorite', { id, enabled }, signal),
    add: (id: string, playlistId: string, signal?: AbortSignal) => dispatch('library.add', { id, playlistId }, signal),
    login: (signal?: AbortSignal) => dispatch('library.login', {}, signal),
    logout: (signal?: AbortSignal) => dispatch('library.logout', {}, signal),
    sync: (signal?: AbortSignal) => dispatch('library.sync', {}, signal),
    cancel: (signal?: AbortSignal) => dispatch('library.cancel', {}, signal)
  });
}
