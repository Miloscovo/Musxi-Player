import type { LibraryState, Track } from '../native/library.ts';
import type { PlayerState } from '../native/types.ts';

export function isCurrentSong(song: Pick<Track, 'id' | 'name' | 'artist' | 'platforms'>,
  playback: Pick<PlayerState, 'opened' | 'trackId'> | null | undefined, now?: LibraryState['now']) {
  if (!playback?.opened) return false;
  if (song.id === playback.trackId) return true;
  return !!now?.platform && now.platform !== 'local' && (song.platforms?.length ?? 0) > 1
    && !!song.platforms?.includes(now.platform) && song.name === now.name && song.artist === now.artist;
}
