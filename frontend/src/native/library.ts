import { NativeError } from './types.ts';
import { request, type CefTransport } from './transport.ts';
export type MusicPlatform = 'kugou' | 'netease' | 'qq';
export const platformNames: Record<MusicPlatform,string> = {kugou:'酷狗概念版',netease:'网易云音乐',qq:'QQ 音乐'};
export interface Track { platform?: MusicPlatform | ''; platforms?:MusicPlatform[]; favorite?:boolean; id: string; name: string; artist: string; album?: string; cover: string; duration: number; count: number; editable: boolean }
export type PlaybackOrder = 'sequential' | 'random' | 'repeat-one';
export const audioQualities = ['128','320','flac','netease:hires','kugou:high'] as const;
export type AudioQuality = typeof audioQualities[number];
export type DefaultAudioQuality = '128' | '320' | 'flac' | 'best';
export interface PlaybackSettings { defaultQuality: DefaultAudioQuality; outputDevice: string; devices: { id: string; name: string }[] }
export function parsePlaybackSettings(value: unknown): PlaybackSettings {
  const v = object(value);
  if (!['128','320','flac','best'].includes(v.defaultQuality as string) || typeof v.outputDevice !== 'string' || v.outputDevice.length > 1024
    || !Array.isArray(v.devices) || v.devices.length > 256) throw new NativeError(502, 'Invalid playback settings');
  const ids = new Set<string>();
  for (const device of v.devices) {
    const row = object(device);
    if (typeof row.id !== 'string' || !row.id || row.id.length > 1024 || typeof row.name !== 'string' || !row.name || ids.has(row.id))
      throw new NativeError(502, 'Invalid output device');
    ids.add(row.id);
  }
  return value as PlaybackSettings;
}
export interface LibraryState {
  favoritesCount?: number;
  recent?:Track[];
  accounts?: {platform: MusicPlatform;name:string;connected:boolean;user:string;error:string;avatar?:string;membership?:{name:string;expiresAt?:number}}[];
  loginPlatform?: MusicPlatform;
  connected: boolean; busy: boolean; user: string; status: string; notice: string; qr: string; avatar: string;
  playlists: Track[]; localPlaylists?: Track[]; tracks: Track[]; trackCount: number; playlistId: string; playlistName: string;
  queue?: Track[]; queueCurrentId?: string;
  playbackOrder?: PlaybackOrder;
  qualities?: { id: string; current: string; options: { id: AudioQuality; name: string }[] };
  search: Track[]; keywords: string; searchPage: number; searchMore: boolean; searchTotal: number;
  now: { name: string; artist: string; cover: string; platform?: MusicPlatform | 'local' | ''; platforms?: MusicPlatform[]; liked?: boolean; canFavorite?: boolean };
  menu: { id: string; liked: boolean; canFavorite: boolean; playlists: Track[]; providers?: { platform: MusicPlatform; liked: boolean; canFavorite: boolean; playlists: Track[] }[] };
  operation: { id: string; status: 'idle' | 'pending' | 'completed' | 'failed' | 'cancelled'; kind: string; error: string };
}
export interface LibraryCommands {
  'library.search': { keywords: string; page: number };
  'library.open': { id: string };
  'library.play': { source: 'search' | 'library' | 'queue' | 'local' | 'recent'; id: string };
  'library.importLocal': Record<string, never>;
  'library.removeLocalFolder': { id: string };
  'library.removeLocalTrack': { id: string; playlistId: string };
  'library.playPlaylist': { id: string };
  'library.setPlaybackOrder': { order: PlaybackOrder };
  'library.qualities': { id: string };
  'library.setQuality': { id: string; quality: AudioQuality };
  'library.setPlatform': { id: string; platform: MusicPlatform };
  'library.queueNext': { source: 'search' | 'library' | 'local' | 'recent'; id: string };
  'library.queueRemove': { id: string };
  'library.queueClear': Record<string, never>;
  'library.recentRemove': {id:string};
  'library.recentClear': Record<string, never>;
  'library.skip': { delta: -1 | 1 };
  'library.menu': { id: string };
  'library.favorite': { id: string; enabled: boolean; platform?: MusicPlatform };
  'library.add': { id: string; playlistId: string; platform?: MusicPlatform };
  'library.login': {platform?:MusicPlatform}; 'library.logout': {platform?:MusicPlatform};
  'library.sync': Record<string, never>; 'library.cancel': Record<string, never>;
}
export type LyricsSource = 'search' | 'library' | 'queue' | 'local' | 'recent';
export interface LyricsLine { timeMs: number; text: string; translation?: string }
// unknown: not looked up yet (e.g. v0.4 cache); translationPending: the Core is fetching it.
export type LyricsTranslation = 'available' | 'none' | 'unknown';
export interface Lyrics {
  status: 'pending' | 'ready'; source: LyricsSource; id: string;
  platform: MusicPlatform | 'local' | ''; origin: 'lrc-file' | 'embedded' | 'online' | 'cache' | 'matched' | '';
  offsetMs?: number;
  kind: 'synced' | 'plain' | 'none'; lines: LyricsLine[];
  translation?: LyricsTranslation; translationPending?: boolean; translationRetryable?: boolean;
  translationLookup?: TranslationLookup;
}
// The latest translation check, separate from what is on screen: a song can keep
// a saved translation while its update is pending or has failed. Absent on older Cores.
export const translationLookupReasons = ['not_provided', 'network', 'timeout', 'platform_error', 'invalid_data',
  'unsupported_format', 'version_mismatch', 'not_bound', 'not_synced', 'no_lyrics', 'internal_error'] as const;
export type TranslationLookupReason = typeof translationLookupReasons[number];
export interface TranslationLookup {
  status: 'idle' | 'pending' | 'succeeded' | 'failed' | 'unsupported';
  reason?: TranslationLookupReason; canRefresh: boolean;
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
  if(v.favoritesCount !== undefined)fields(v,['favoritesCount'],'number');
  const rows = (value: unknown) => {
    if (!Array.isArray(value)) throw new NativeError(502, 'Invalid library rows');
    for (const row of value) {
      const r = object(row); fields(r, ['id', 'name', 'artist', 'cover'], 'string');
      if (r.album !== undefined) fields(r, ['album'], 'string');
      if (r.favorite !== undefined) fields(r, ['favorite'], 'boolean');
      if(r.platform !== undefined && !['','kugou','netease','qq'].includes(r.platform as string))throw new NativeError(502,'Invalid music platform');
      if(r.platforms !== undefined && (!Array.isArray(r.platforms) || r.platforms.length>3 || new Set(r.platforms).size!==r.platforms.length || r.platforms.some(p=>!['kugou','netease','qq'].includes(p))))throw new NativeError(502,'Invalid music platforms');
      fields(r, ['duration', 'count'], 'number'); fields(r, ['editable'], 'boolean');
    }
  };
  for (const key of ['playlists', 'tracks', 'search']) rows(v[key]);
  if(v.localPlaylists !== undefined) rows(v.localPlaylists);
  if(v.queue !== undefined) rows(v.queue);
  if(v.recent !== undefined) {rows(v.recent);if((v.recent as unknown[]).length>500)throw new NativeError(502,'Invalid recent history');}
  if(v.queueCurrentId !== undefined) fields(v, ['queueCurrentId'], 'string');
  if(v.playbackOrder !== undefined && !['sequential', 'random', 'repeat-one'].includes(v.playbackOrder as string)) throw new NativeError(502, 'Invalid playback order');
  if(v.qualities !== undefined) {
    const quality=object(v.qualities);fields(quality,['id','current'],'string');
    if(!Array.isArray(quality.options))throw new NativeError(502,'Invalid audio qualities');
    for(const entry of quality.options) {
      const option=object(entry);fields(option,['id','name'],'string');
      if(!audioQualities.some(id=>id===option.id))throw new NativeError(502,'Invalid audio quality');
    }
  }
  if(v.accounts !== undefined) {
    if(!Array.isArray(v.accounts))throw new NativeError(502,'Invalid accounts');
    for(const value of v.accounts){const account=object(value);fields(account,['name','user','error'],'string');fields(account,['connected'],'boolean');if(account.avatar!==undefined)fields(account,['avatar'],'string');if(!['kugou','netease','qq'].includes(account.platform as string))throw new NativeError(502,'Invalid account platform');}
    for(const value of v.accounts){const account=object(value);if(account.membership!==undefined){const membership=object(account.membership);fields(membership,['name'],'string');if(membership.expiresAt!==undefined && (typeof membership.expiresAt!=='number' || !Number.isFinite(membership.expiresAt) || membership.expiresAt<=0 || membership.expiresAt>253402300799999))throw new NativeError(502,'Invalid membership expiry');}}
  }
  if(v.loginPlatform !== undefined && !['kugou','netease','qq'].includes(v.loginPlatform as string))throw new NativeError(502,'Invalid login platform');
  const now = object(v.now); fields(now, ['name', 'artist', 'cover'], 'string');
  if(now.platform !== undefined && !['','local','kugou','netease','qq'].includes(now.platform as string))throw new NativeError(502,'Invalid playback platform');
  if(now.platforms !== undefined && (!Array.isArray(now.platforms) || now.platforms.length>3 || new Set(now.platforms).size!==now.platforms.length || now.platforms.some(p=>!['kugou','netease','qq'].includes(p))))throw new NativeError(502,'Invalid playback platforms');
  if(now.liked !== undefined) fields(now, ['liked'], 'boolean');
  if(now.canFavorite !== undefined) fields(now, ['canFavorite'], 'boolean');
  const menu = object(v.menu); fields(menu, ['id'], 'string'); fields(menu, ['liked', 'canFavorite'], 'boolean'); rows(menu.playlists);
  if(menu.providers !== undefined) {
    if(!Array.isArray(menu.providers) || menu.providers.length>3)throw new NativeError(502,'Invalid song menu providers');
    const platforms=new Set();
    for(const entry of menu.providers) {
      const provider=object(entry);
      if(!['kugou','netease','qq'].includes(provider.platform as string) || platforms.has(provider.platform))throw new NativeError(502,'Invalid song menu platform');
      platforms.add(provider.platform);fields(provider,['liked','canFavorite'],'boolean');rows(provider.playlists);
    }
  }
  const operation = object(v.operation); fields(operation, ['id', 'status', 'kind', 'error'], 'string');
  if (!['idle', 'pending', 'completed', 'failed', 'cancelled'].includes(operation.status as string)) throw new NativeError(502, 'Invalid operation status');
  return value as LibraryState;
}
export function parseLyrics(value: unknown): Lyrics {
  const v = object(value); fields(v, ['id'], 'string');
  if (!['pending', 'ready'].includes(v.status as string)) throw new NativeError(502, 'Invalid lyrics status');
  if (!['search', 'library', 'queue', 'local', 'recent'].includes(v.source as string)) throw new NativeError(502, 'Invalid lyrics source');
  if (!['', 'local', 'kugou', 'netease', 'qq'].includes(v.platform as string)) throw new NativeError(502, 'Invalid lyrics platform');
  if (!['', 'lrc-file', 'embedded', 'online', 'cache', 'matched'].includes(v.origin as string)) throw new NativeError(502, 'Invalid lyrics origin');
  if (v.offsetMs !== undefined && (!Number.isInteger(v.offsetMs) || Math.abs(v.offsetMs as number)>600000)) throw new NativeError(502, 'Invalid lyrics offset');
  if (!['synced', 'plain', 'none'].includes(v.kind as string)) throw new NativeError(502, 'Invalid lyrics kind');
  if (!Array.isArray(v.lines) || v.lines.length > 5000) throw new NativeError(502, 'Invalid lyrics lines');
  for (const entry of v.lines) {
    const line = object(entry); fields(line, ['timeMs'], 'number'); fields(line, ['text'], 'string');
    if (line.translation !== undefined) fields(line, ['translation'], 'string');
  }
  if (v.translation !== undefined && !['available', 'none', 'unknown'].includes(v.translation as string)) throw new NativeError(502, 'Invalid lyrics translation');
  if (v.translationPending !== undefined) fields(v, ['translationPending'], 'boolean');
  if (v.translationRetryable !== undefined) fields(v, ['translationRetryable'], 'boolean');
  if (v.translationLookup !== undefined) {
    const lookup = object(v.translationLookup); fields(lookup, ['canRefresh'], 'boolean');
    if (!['idle', 'pending', 'succeeded', 'failed', 'unsupported'].includes(lookup.status as string)) throw new NativeError(502, 'Invalid translation lookup');
    if (lookup.reason !== undefined) {
      fields(lookup, ['reason'], 'string');
      // A reason added by a newer Core still reads as a failure, not a protocol error.
      if (!translationLookupReasons.includes(lookup.reason as TranslationLookupReason)) lookup.reason = 'internal_error';
    }
  }
  return value as Lyrics;
}
export const safeImage =(value: string) => /^data:image\/(png|jpeg|webp);base64,[A-Za-z0-9+/=\r\n]+$/.test(value) ? value : '';
export function createLibraryClient(host: CefTransport) {
  async function dispatch<K extends keyof LibraryCommands>(command: K, params: LibraryCommands[K], signal?: AbortSignal) {
    const result = object(await request(host, command, params, { signal }));
    fields(result, ['id'], 'string'); return { id: result.id as string };
  }
  return Object.freeze({
    async getPlaybackSettings(signal?: AbortSignal) { return parsePlaybackSettings(await request(host, 'library.getPlaybackSettings', {}, { signal })); },
    async setPlaybackSettings(settings: Pick<PlaybackSettings, 'defaultQuality' | 'outputDevice'>, signal?: AbortSignal) {
      return parsePlaybackSettings(await request(host, 'library.setPlaybackSettings', settings, { signal }));
    },
    async getState(page = 1, signal?: AbortSignal) { return parseLibraryState(await request(host, 'library.getState', { page }, { signal })); },
    async image(url: string, signal?: AbortSignal) {
      const value = await request(host, 'library.image', { url }, { signal });
      if (typeof value !== 'string') throw new NativeError(502, 'Invalid image'); return safeImage(value);
    },
    // Poll while status is 'pending'; discard replies whose id is no longer the current song.
    async lyrics(source: LyricsSource, id: string, signal?: AbortSignal, retryTranslation=false) { return parseLyrics(await request(host, 'library.lyrics', { source, id, ...(retryTranslation?{retryTranslation:true}:{}) }, { signal })); },
    search: (keywords: string, page: number, signal?: AbortSignal) => dispatch('library.search', { keywords, page }, signal),
    open: (id: string, signal?: AbortSignal) => dispatch('library.open', { id }, signal),
    play: (source: 'search' | 'library' | 'queue' | 'local' | 'recent', id: string, signal?: AbortSignal) => dispatch('library.play', { source, id }, signal),
    importLocal: (signal?: AbortSignal) => dispatch('library.importLocal', {}, signal),
    removeLocalFolder: (id: string, signal?: AbortSignal) => dispatch('library.removeLocalFolder', { id }, signal),
    removeLocalTrack: (id: string, playlistId: string, signal?: AbortSignal) => dispatch('library.removeLocalTrack', { id, playlistId }, signal),
    playPlaylist: (id: string, signal?: AbortSignal) => dispatch('library.playPlaylist', { id }, signal),
    setPlaybackOrder: (order: PlaybackOrder, signal?: AbortSignal) => dispatch('library.setPlaybackOrder', { order }, signal),
    qualities: (id: string, signal?: AbortSignal) => dispatch('library.qualities', { id }, signal),
    setQuality: (id: string, quality: AudioQuality, signal?: AbortSignal) => dispatch('library.setQuality', { id, quality }, signal),
    setPlatform: (id: string, platform: MusicPlatform, signal?: AbortSignal) => dispatch('library.setPlatform', { id, platform }, signal),
    queueNext: (source: 'search' | 'library' | 'local' | 'recent', id: string, signal?: AbortSignal) => dispatch('library.queueNext', { source, id }, signal),
    queueRemove: (id: string, signal?: AbortSignal) => dispatch('library.queueRemove', { id }, signal),
    queueClear: (signal?: AbortSignal) => dispatch('library.queueClear', {}, signal),
    recentRemove: (id:string,signal?:AbortSignal) => dispatch('library.recentRemove',{id},signal),
    recentClear: (signal?:AbortSignal) => dispatch('library.recentClear',{},signal),
    skip: (delta: -1 | 1, signal?: AbortSignal) => dispatch('library.skip', { delta }, signal),
    menu: (id: string, signal?: AbortSignal) => dispatch('library.menu', { id }, signal),
    favorite: (id: string, enabled: boolean, signal?: AbortSignal, platform?: MusicPlatform) => dispatch('library.favorite', { id, enabled, ...(platform ? {platform} : {}) }, signal),
    add: (id: string, playlistId: string, signal?: AbortSignal, platform?: MusicPlatform) => dispatch('library.add', { id, playlistId, ...(platform ? {platform} : {}) }, signal),
    login: (signal?: AbortSignal, platform?: MusicPlatform) => dispatch('library.login', platform ? {platform} : {}, signal),
    logout: (signal?: AbortSignal, platform?: MusicPlatform) => dispatch('library.logout', platform ? {platform} : {}, signal),
    sync: (signal?: AbortSignal) => dispatch('library.sync', {}, signal),
    cancel: (signal?: AbortSignal) => dispatch('library.cancel', {}, signal)
  });
}
