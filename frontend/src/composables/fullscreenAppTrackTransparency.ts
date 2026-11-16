import { readVisualSettings } from './appearancePreview.ts';

export const fullscreenAppTrackTransparencyKey = 'musxi-fullscreen-app-track-transparency';

export function readFullscreenAppTrackTransparency(storage: Pick<Storage, 'getItem' | 'setItem'>): number {
  let initial = 0;
  try {
    const current = storage.getItem(fullscreenAppTrackTransparencyKey);
    const saved = current ?? storage.getItem('musxi-game-track-transparency');
    const value = saved?.trim() ? Number(saved) : NaN;
    if (Number.isFinite(value) && value >= 0 && value <= 100) {
      initial = value;
      if (current === null) storage.setItem(fullscreenAppTrackTransparencyKey, String(value));
      return value;
    }
    const { appearance, transparency } = readVisualSettings(storage);
    initial = appearance.transparent ? transparency : 0;
    storage.setItem(fullscreenAppTrackTransparencyKey, String(initial));
  } catch { /* Keep the inherited appearance if storage cannot persist it. */ }
  return initial;
}
