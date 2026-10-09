import type { Theme } from '../native/window.ts';

export type AppearanceMode = 'light' | 'dark' | 'system';
export type AppearanceTone = 'green' | 'blue' | 'pink' | 'red';
export interface Appearance { mode: AppearanceMode; transparent: boolean; tone: AppearanceTone }

export function readAppearance(storage: Pick<Storage, 'getItem'>): Appearance {
  const legacy = storage.getItem('musxi-theme');
  const mode = storage.getItem('musxi-appearance-mode');
  const transparent = storage.getItem('musxi-transparent');
  const tone = storage.getItem('musxi-tone');
  return {
    mode: mode === 'light' || mode === 'dark' || mode === 'system' ? mode
      : legacy === 'dark' || legacy === 'glass' ? 'dark' : 'light',
    transparent: transparent === 'true' || (transparent === null && (legacy === 'glass' || legacy === 'glass-light')),
    tone: tone === 'blue' || tone === 'pink' || tone === 'red' ? tone : 'green',
  };
}

export function resolveTheme(appearance: Pick<Appearance, 'mode' | 'transparent'>, systemDark: boolean): Theme {
  const dark = appearance.mode === 'system' ? systemDark : appearance.mode === 'dark';
  return appearance.transparent ? (dark ? 'glass' : 'glass-light') : (dark ? 'dark' : 'light');
}
