export interface LyricsDisplay { alignment: 'left' | 'center'; fontSize: number }
export const lyricsDisplayKey = 'musxi-lyrics-display';
export function readLyricsDisplay(storage: Pick<Storage, 'getItem'>): LyricsDisplay {
  try {
    const data = JSON.parse(storage.getItem(lyricsDisplayKey) || '{}');
    return { alignment: data?.alignment === 'left' ? 'left' : 'center', fontSize: Number.isInteger(data?.fontSize) && data.fontSize >= 16 && data.fontSize <= 32 ? data.fontSize : 20 };
  } catch { return { alignment: 'center', fontSize: 20 }; }
}
