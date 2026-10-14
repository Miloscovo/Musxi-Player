import { useEffect, useMemo, useState } from 'react';
import { native } from '../native/client';
import type { DesktopColor, DesktopLyricsState } from '../native/desktopLyrics';

const initial: DesktopLyricsState = { enabled: false, visible: false, locked: false, dual: true, panel: false, hover: false, fontSize: 30, color: 'theme' };

// Native owns the state and pushes every change, including tray actions.
export function useDesktopLyrics() {
  const [state, setState] = useState(initial);
  const [error, setError] = useState('');
  useEffect(() => {
    let retry: ReturnType<typeof setTimeout> | undefined; let stop = () => {};
    const connect = () => {
      // Older hosts and the browser demo have no desktop window; stay disabled.
      stop = native.desktopLyrics.subscribe(setState, e => { if (e.code !== 503 && e.code !== 404) retry = setTimeout(connect, 1500); });
    };
    connect();
    return () => { clearTimeout(retry); stop(); };
  }, []);
  const actions = useMemo(() => {
    const run = async (action: () => Promise<DesktopLyricsState>) => {
      try { setState(await action()); setError(''); }
      catch (e) { setError(e instanceof Error ? e.message : '桌面歌词操作失败'); }
    };
    const d = native.desktopLyrics;
    return {
      setVisible: (value: boolean) => run(() => d.setVisible(value)),
      setLocked: (value: boolean) => run(() => d.setLocked(value)),
      setDual: (value: boolean) => run(() => d.setDual(value)),
      setPanel: (value: boolean) => run(() => d.setPanel(value)),
      setFontSize: (value: number) => run(() => d.setFontSize(value)),
      setColor: (value: DesktopColor) => run(() => d.setColor(value)),
    };
  }, []);
  return { state, error, ...actions };
}
