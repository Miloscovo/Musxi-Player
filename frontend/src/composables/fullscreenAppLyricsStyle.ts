export const fullscreenAppLyricsStyleKey = 'musxi-fullscreen-app-lyrics-style';
export type FullscreenAppLyricsStyle = 'flat' | 'perspective';

export function readFullscreenAppLyricsStyle(storage: Pick<Storage, 'getItem'> & Partial<Pick<Storage, 'setItem'>>): FullscreenAppLyricsStyle {
  let style: FullscreenAppLyricsStyle = 'flat';
  try {
    const saved = storage.getItem(fullscreenAppLyricsStyleKey);
    const value = saved ?? storage.getItem('musxi-game-lyrics-style');
    style = value === 'perspective' ? 'perspective' : 'flat';
    if (saved === null && (value === 'flat' || value === 'perspective')) storage.setItem?.(fullscreenAppLyricsStyleKey, style);
  } catch { /* Keep the restored style when storage cannot persist the migration. */ }
  return style;
}
