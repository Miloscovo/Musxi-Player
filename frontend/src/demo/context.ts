import type { LibraryState, Track } from '../native/library';
import type { PlayerState } from '../native/types';
import { createDemoLibrary, createDemoPlayer, demoPlaylistSongs, demoSongs } from './data.ts';

export interface DemoRuntime {
  library: LibraryState;
  player: PlayerState;
  playlistTracks: Track[];
}

export function isDemoMode() { return new URLSearchParams(globalThis.location?.search || '').has('demo'); }

export function createDemoRuntime(): DemoRuntime {
  return { library: createDemoLibrary(), player: createDemoPlayer(), playlistTracks: demoSongs() };
}

export function openDemoPlaylist(runtime: DemoRuntime, id: string) {
  runtime.playlistTracks = demoPlaylistSongs(id);
  runtime.library = { ...runtime.library, playlistId: id, playlistName: runtime.library.playlists.find(list => list.id === id)?.name || '', tracks: runtime.playlistTracks, trackCount: runtime.playlistTracks.length };
}

export function searchDemo(runtime: DemoRuntime, keywords: string) {
  const value = keywords.trim().toLocaleLowerCase();
  const rows = value ? demoSongs().filter(song => [song.name, song.artist, song.album || ''].some(field => field.toLocaleLowerCase().includes(value))) : [];
  runtime.library = { ...runtime.library, keywords, search: rows, searchTotal: rows.length, searchMore: false, searchPage: 1 };
}

export function playDemo(runtime: DemoRuntime, song: Track) {
  runtime.player = { ...runtime.player, opened: true, playing: true, requestedPlaying: true, phase: 'playing', positionMs: 0, durationMs: song.duration, trackId: song.id };
  runtime.library = { ...runtime.library, now: { name: song.name, artist: song.artist, cover: song.cover } };
}
