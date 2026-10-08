import type { LibraryState, Track } from '../native/library';
import type { PlayerState } from '../native/types';

const playlists: Track[] = [
  { id: 'demo-favorites', name: '我喜欢', artist: '本地演示歌单', album: '', cover: '', duration: 0, count: 4, editable: true, favorite:true },
  { id: 'demo-focus', name: '专注时刻', artist: '本地演示歌单', album: '', cover: '', duration: 0, count: 3, editable: true },
  { id: 'demo-discovery', name: '最近常听', artist: '本地演示歌单', album: '', cover: '', duration: 0, count: 3, editable: false },
];

const songs: Track[] = [
  { id: 'demo-1', name: 'A Walk', artist: 'Tycho', album: 'Dive', cover: '', duration: 312000, count: 0, editable: false },
  { id: 'demo-2', name: 'Intro', artist: 'The xx', album: 'xx', cover: '', duration: 127000, count: 0, editable: false },
  { id: 'demo-3', name: 'Sunset Lover', artist: 'Petit Biscuit', album: 'Presence', cover: '', duration: 237000, count: 0, editable: false },
  { id: 'demo-4', name: 'Weightless', artist: 'Marconi Union', album: 'Weightless', cover: '', duration: 486000, count: 0, editable: false },
  { id: 'demo-5', name: 'Awake', artist: 'Tycho', album: 'Awake', cover: '', duration: 295000, count: 0, editable: false },
  { id: 'demo-6', name: 'Open Eye Signal', artist: 'Jon Hopkins', album: 'Immunity', cover: '', duration: 478000, count: 0, editable: false },
  { id: 'demo-7', name: 'Kerala', artist: 'Bonobo', album: 'Migration', cover: '', duration: 213000, count: 0, editable: false },
  { id: 'demo-8', name: 'Aruarian Dance', artist: 'Nujabes', album: 'Samurai Champloo Music Record', cover: '', duration: 257000, count: 0, editable: false },
  { id: 'demo-9', name: 'First Breath After Coma', artist: 'Explosions in the Sky', album: 'The Earth Is Not a Cold Dead Place', cover: '', duration: 305000, count: 0, editable: false },
  { id: 'demo-10', name: 'Dayvan Cowboy', artist: 'Boards of Canada', album: 'The Campfire Headphase', cover: '', duration: 300000, count: 0, editable: false },
];

export function createDemoLibrary(): LibraryState {
  return {
    connected: true, busy: false, user: '本地演示', status: '演示媒体库', notice: '演示数据仅保存在此页面，不会登录账号或连接网络。', qr: '', avatar: '',
    playlists: playlists.map(list => ({ ...list })), localPlaylists: [], queue: [], queueCurrentId: '', playbackOrder: 'sequential', tracks: [], trackCount: 0, playlistId: '', playlistName: '', search: [], keywords: '', searchPage: 0,
    searchMore: false, searchTotal: 0, now: { name: '', artist: '', cover: '' },
    menu: { id: '', liked: false, canFavorite: false, playlists: [] }, operation: { id: '', status: 'idle', kind: '', error: '' },
  };
}

export function createDemoPlayer(): PlayerState {
  return { opened: false, playing: false, positionMs: 0, durationMs: 0, volumePercent: 64, trackId: '',
    phase: 'empty', pending: false, requestedPlaying: false };
}

export function demoSongs(): Track[] { return songs.map(song => ({ ...song })); }

export function demoPlaylistSongs(id: string): Track[] {
  const offsets: Record<string, number[]> = {
    'demo-favorites': [0, 2, 3, 8], 'demo-focus': [1, 3, 5], 'demo-discovery': [0, 4, 6],
  };
  return (offsets[id] || []).map(index => ({ ...songs[index] }));
}
