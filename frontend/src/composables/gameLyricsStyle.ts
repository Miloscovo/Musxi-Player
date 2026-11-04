export const gameLyricsStyleKey = 'musxi-game-lyrics-style';
export type GameLyricsStyle = 'flat' | 'perspective';

export function readGameLyricsStyle(storage: Pick<Storage, 'getItem'>): GameLyricsStyle {
  try { return storage.getItem(gameLyricsStyleKey) === 'perspective' ? 'perspective' : 'flat'; }
  catch { return 'flat'; }
}
