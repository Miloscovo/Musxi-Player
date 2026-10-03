import { useCallback, useLayoutEffect, useState } from 'react';
import { native } from '../native/client';
import type { Theme, WindowState } from '../native/window';
export function useWindow() {
  const [state, setState] = useState<WindowState>({ enabled: false, maximized: false });
  const [error, setError] = useState('');
  const [transparency, updateTransparency] = useState(() => {
    const saved = localStorage.getItem('musxi-transparency');
    const value = saved === null ? 50 : Number(saved);
    return Number.isFinite(value) ? Math.max(0, Math.min(100, value)) : 50;
  });
  const [theme, updateTheme] = useState<Theme>(() => {
    const saved = localStorage.getItem('musxi-theme');
    return saved === 'dark' || saved === 'glass' || saved === 'glass-light' ? saved : 'light';
  });
  const invoke = useCallback(async (action: () => Promise<WindowState>) => {
    try { setState(await action()); setError(''); return true; }
    catch (e) { setError(e instanceof Error ? e.message : '窗口操作失败'); return false; }
  }, []);
  const setTheme = useCallback(async (next: Theme) => {
    updateTheme(next); document.documentElement.dataset.theme = next;
    localStorage.setItem('musxi-theme', next);
    await invoke(() => native.window.setTheme(next));
  }, [invoke]);
  useLayoutEffect(() => {
    document.documentElement.dataset.theme = theme;
    void invoke(() => native.window.setTheme(theme));
  }, []);
  useLayoutEffect(() => {
    const glass = theme === 'glass' || theme === 'glass-light';
    document.documentElement.style.setProperty('--background-opacity', String(glass ? 1 - transparency / 100 : 1));
    localStorage.setItem('musxi-transparency', String(transparency));
  }, [theme, transparency]);
  return { state, error, theme, setTheme, transparency, setTransparency: updateTransparency,
    minimize: () => invoke(native.window.minimize), maximize: () => invoke(native.window.maximize),
    minimizeToTray: () => invoke(native.window.minimizeToTray),
    close: () => invoke(native.window.close) };
}
