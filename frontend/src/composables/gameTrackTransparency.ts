import { readVisualSettings } from './appearancePreview.ts';

export const gameTrackTransparencyKey = 'musxi-game-track-transparency';

export function readGameTrackTransparency(storage: Pick<Storage, 'getItem' | 'setItem'>): number {
  let initial = 0;
  try {
    const saved = storage.getItem(gameTrackTransparencyKey);
    const value = saved?.trim() ? Number(saved) : NaN;
    if (Number.isFinite(value) && value >= 0 && value <= 100) return value;
    const { appearance, transparency } = readVisualSettings(storage);
    initial = appearance.transparent ? transparency : 0;
    storage.setItem(gameTrackTransparencyKey, String(initial));
  } catch { /* Keep the inherited appearance if storage cannot persist it. */ }
  return initial;
}
