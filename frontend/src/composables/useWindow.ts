import { useCallback, useLayoutEffect, useState } from 'react';
import { native } from '../native/client';
import type { WindowState } from '../native/window';
import { readAppearance, resolveTheme } from './appearance.ts';
export function useWindow() {
  const [state, setState] = useState<WindowState>({ enabled: false, maximized: false });
  const [error, setError] = useState('');
  const [transparency, updateTransparency] = useState(() => {
    const saved = localStorage.getItem('musxi-transparency');
    const value = saved === null ? 50 : Number(saved);
    return Number.isFinite(value) ? Math.max(0, Math.min(100, value)) : 50;
  });
  const [appearance, setAppearance] = useState(() => readAppearance(localStorage));
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
    localStorage.setItem('musxi-theme', theme);
    void invoke(() => native.window.setTheme(theme));
  }, [theme, invoke]);
  useLayoutEffect(() => {
    localStorage.setItem('musxi-appearance-mode', appearance.mode);
    localStorage.setItem('musxi-transparent', String(appearance.transparent));
    localStorage.setItem('musxi-tone', appearance.tone);
    document.documentElement.dataset.tone = appearance.tone;
  }, [appearance]);
  useLayoutEffect(() => {
    const glass = theme === 'glass' || theme === 'glass-light';
    document.documentElement.style.setProperty('--background-opacity', String(glass ? 1 - transparency / 100 : 1));
    localStorage.setItem('musxi-transparency', String(transparency));
  }, [theme, transparency]);
  return { state, error, theme, appearance, setAppearance, transparency, setTransparency: updateTransparency,
    minimize: () => invoke(native.window.minimize), maximize: () => invoke(native.window.maximize),
    minimizeToTray: () => invoke(native.window.minimizeToTray),
    close: () => invoke(native.window.close) };
}
