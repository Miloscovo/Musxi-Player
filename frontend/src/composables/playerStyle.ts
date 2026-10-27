export const playerStyleKey = 'musxi-lyrics-player-style';
export type PlayerStyle = 'vinyl' | 'square' | 'none';
export function readPlayerStyle(storage: Pick<Storage, 'getItem'>): PlayerStyle {
  const value = storage.getItem(playerStyleKey);
  return value === 'square' || value === 'none' ? value : 'vinyl';
}
