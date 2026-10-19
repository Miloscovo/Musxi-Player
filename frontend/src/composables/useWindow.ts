import { useCallback, useLayoutEffect, useReducer, useState } from 'react';
import { native } from '../native/client';
import type { WindowState } from '../native/window';
import { resolveTheme, type Appearance } from './appearance.ts';
import { readVisualSettings, reduceVisual, writeVisualSettings } from './appearancePreview';
export function useWindow() {
  const [state, setState] = useState<WindowState>({ enabled: false, maximized: false });
  const [error, setError] = useState('');
  const [visual, dispatch] = useReducer(reduceVisual, undefined, () => ({ saved:readVisualSettings(localStorage), preview:null }));
  const { appearance, transparency, wallpaperEnabled, playerTransparent } = visual.preview ?? visual.saved;
  const [systemDark, setSystemDark] = useState(() => window.matchMedia('(prefers-color-scheme: dark)').matches);
  const theme = resolveTheme(appearance, systemDark);
  const invoke = useCallback(async (action: () => Promise<WindowState>) => {
    try { setState(await action()); setError(''); return true; }
    catch (e) { setError(e instanceof Error ? e.message : '窗口操作失败'); return false; }
  }, []);
  useLayoutEffect(() => {
    const media = window.matchMedia('(prefers-color-scheme: dark)');
    const changed = () => setSystemDark(media.matches);
    changed();
    media.addEventListener('change', changed);
    return () => media.removeEventListener('change', changed);
  }, []);
  useLayoutEffect(() => {
    document.documentElement.dataset.theme = theme;
    void invoke(() => native.window.setTheme(theme));
  }, [theme, invoke]);
  useLayoutEffect(() => {
    document.documentElement.dataset.tone = appearance.tone;
  }, [appearance]);
  useLayoutEffect(() => {
    if (!visual.preview) try { writeVisualSettings(localStorage,visual.saved,systemDark); }
    catch { setError('无法保存外观设置，请检查存储空间'); }
  }, [visual,systemDark]);
  useLayoutEffect(() => {
    const glass = theme === 'glass' || theme === 'glass-light';
    document.documentElement.style.setProperty('--background-opacity', String(glass ? 1 - transparency / 100 : 1));
    document.documentElement.style.setProperty('--player-background-opacity',String(playerTransparent && glass ? 1-transparency/100 : 1));
  }, [theme, transparency,playerTransparent]);
  return { state, error, theme, appearance, setAppearance: (value:Appearance) => dispatch({ type:'appearance',value }),
    transparency, setTransparency: (value:number) => dispatch({ type:'transparency',value }),
    wallpaperEnabled, playerTransparent, preview:!!visual.preview,
    setWallpaperEnabled:(value:boolean) => dispatch({ type:'wallpaper',value }),
    setPlayerTransparent:(value:boolean) => dispatch({ type:'player',value }),
    beginPreview:() => dispatch({ type:'begin' }), cancelPreview:() => dispatch({ type:'cancel' }),
    applyPreview:() => dispatch({ type:'apply' }), resetWallpaper:() => dispatch({ type:'resetWallpaper' }),
    minimize: () => invoke(native.window.minimize), maximize: () => invoke(native.window.maximize),
    minimizeToTray: () => invoke(native.window.minimizeToTray),
    setCompact: (compact: boolean, height: number) => invoke(() => native.window.setCompact(compact, height)),
    close: () => invoke(native.window.close) };
}
