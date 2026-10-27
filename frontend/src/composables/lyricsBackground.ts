export const lyricsBackgroundKey = 'musxi-lyrics-background';
export type LyricsBackground = 'main' | 'cover';
export function readLyricsBackground(storage: Pick<Storage, 'getItem'>): LyricsBackground {
  return storage.getItem(lyricsBackgroundKey) === 'cover' ? 'cover' : 'main';
}
