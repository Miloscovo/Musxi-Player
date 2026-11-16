import { useEffect, useMemo, useRef, useState } from 'react';
import { readLyricsDisplay } from './lyricsDisplay';
import { native } from '../native/client';
import { initialFullscreenAppOverlayState, type FullscreenAppOverlayState, type CompatibilityWindow } from '../native/fullscreenAppOverlay';

export function useFullscreenAppOverlay() {
  const [state, setState] = useState(initialFullscreenAppOverlayState);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [windows, setWindows] = useState<CompatibilityWindow[]>([]);
  const mounted = useRef(false);
  useEffect(() => {
    mounted.current = true;
    let retry: ReturnType<typeof setTimeout> | undefined; let stop = () => {};
    const connect = () => {
      stop();
      stop = native.fullscreenAppOverlay.subscribe(value => {
        if (mounted.current) { setState(value); setError(''); }
      }, e => {
        if (!mounted.current) return;
        if (e.code === 404 || e.code === 503) setState(initialFullscreenAppOverlayState);
        else {
          setError(e.message);
          if (e.code === 408 || e.code >= 500) retry = setTimeout(connect, 1500);
        }
      });
    };
    connect();
    return () => { mounted.current = false; clearTimeout(retry); stop(); };
  }, []);
  const actions = useMemo(() => {
    async function run(action: () => Promise<FullscreenAppOverlayState | void>) {
      setBusy(true);
      try { const next = await action(); if (mounted.current) { if (next) setState(next); setError(''); } }
      catch (e) { if (mounted.current) setError(e instanceof Error ? e.message : '全屏应用悬浮组件操作失败'); }
      finally { if (mounted.current) setBusy(false); }
    }
    const overlay = native.fullscreenAppOverlay;
    async function loadWindows() {
      const next = await overlay.listCompatibilityWindows(); if (mounted.current) setWindows(next);
    }
    return {
      setTopmost: (value: boolean) => run(() => overlay.setTopmost(value)),
      setLyricsMode: (value: 'single' | 'multi') => run(() => overlay.setLyricsMode(value)),
      setLyricsFontSize: (value: number) => run(() => overlay.setLyricsFontSize(value)),
      setEnabled: (value: boolean) => run(() => overlay.setEnabled(value)),
      setVisible: (value: boolean) => run(() => overlay.setVisible(value)),
      setLyricsVisible: (value: boolean) => run(() => overlay.setLyricsVisible(value)),
      setTrackVisible: (value: boolean) => run(() => overlay.setTrackVisible(value)),
      setEditing: (value: boolean) => run(() => overlay.setEditing(value)),
      setHideOnHover: (value: boolean) => run(() => overlay.setHideOnHover(value)),
      setWindowCompatibility: (value: boolean) => run(async () => {
        const next = await overlay.setWindowCompatibility(value);
        if (mounted.current) setState(next);
        if (next.windowCompatibility) await loadWindows(); else setWindows([]);
        return next;
      }),
      refreshWindows: () => run(loadWindows),
      applyWindowCompatibility: (id: string) => run(async () => {
        const next = await overlay.applyWindowCompatibility(id); if (mounted.current) setWindows([]); return next;
      }),
      restoreWindowCompatibility: () => run(async () => {
        const next = await overlay.restoreWindowCompatibility(); if (mounted.current) setState(next);
        if (next.windowCompatibility) await loadWindows(); return next;
      }),
    };
  }, []);
  useEffect(() => {
    if (state.available && state.lyricsFontSize === 0) void actions.setLyricsFontSize(readLyricsDisplay(localStorage).fontSize);
  }, [state.available, state.lyricsFontSize, actions]);
  return { state, error, busy, windows, ...actions };
}

export type FullscreenAppOverlayController = ReturnType<typeof useFullscreenAppOverlay>;
