import { useEffect, useRef, useState } from 'react';
import { native } from '../native/client';
import type { DynamicWallpaperState, WallpaperProject, WallpaperSession, WallpaperPlayback, WallpaperScanReport } from '../native/dynamicWallpaper';
export interface RunningWallpaper extends WallpaperSession { project: WallpaperProject; ready: boolean; warning: boolean }
export function useDynamicWallpaper(compact = false) {
  const [settings, setSettings] = useState<DynamicWallpaperState>({ enabled: false, selected: '', directories: [], fps: 60, quality: 'standard' });
  const [loaded, setLoaded] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState('');
  const [items, setItems] = useState<WallpaperProject[]>([]);
  const [reports, setReports] = useState<WallpaperScanReport[]>([]), [suspended, setSuspended] = useState(false);
  const [retryTarget, setRetryTarget] = useState<{ project: WallpaperProject; restoring: boolean } | null>(null);
  const [current, setCurrent] = useState<RunningWallpaper | null>(null), [preview, setPreview] = useState<RunningWallpaper | null>(null);
  const [manager, setManager] = useState(false), [visible, setVisible] = useState(!document.hidden);
  const active = useRef(true), generation = useRef(0), locked = useRef(false);
  const sleeping = useRef(false);
  const values = useRef({ current, preview, settings }); values.current = { current, preview, settings };
  const failure = (e: unknown) => { if (active.current) setError(e instanceof Error ? e.message : '动态壁纸操作失败，请重试'); };
  const release = (value: WallpaperSession | null) => { if (value) void native.dynamicWallpaper.release(value.url).catch(() => {}); };
  useEffect(() => {
    active.current = true;
    void native.dynamicWallpaper.getState().then(value => { if (active.current) { setSettings(value); setLoaded(true); } }).catch(failure);
    const visibility = () => setVisible(!document.hidden);
    document.addEventListener('visibilitychange', visibility);
    return () => { active.current = false; generation.current++; release(values.current.current); release(values.current.preview); document.removeEventListener('visibilitychange', visibility); };
  }, []);
  async function open(project: WallpaperProject, restoring: boolean) {
    if(sleeping.current)return;
    const version = ++generation.current;
    let session: WallpaperSession;
    try { session = await native.dynamicWallpaper.open(project.id); }
    catch(error) { if(active.current && generation.current === version)setRetryTarget({project,restoring});throw error; }
    if (!active.current || sleeping.current || generation.current !== version || !values.current.settings.enabled) { release(session); return; }
    const next = { ...session, project, ready: false, warning: false };
    if (restoring) { release(values.current.current); setCurrent(next); }
    else { release(values.current.preview); setPreview(next); setManager(false); }
  }
  async function scan(restore = false) {
    const list = await native.dynamicWallpaper.scan();
    if (!active.current || !values.current.settings.enabled) return;
    setItems(list.items);
    setReports(list.reports ?? []);
    if (restore) {
      const selected = list.items.find(item => item.selected);
      if (selected) await open(selected, true);
      else if (values.current.settings.selected) setError('未找到已保存的动态壁纸，已使用原有背景。请检查资源目录。');
    }
  }
  useEffect(() => {
    if (!loaded || !settings.enabled) return;
    setBusy(true); locked.current = true;
    void scan(true).catch(failure).finally(() => { if (active.current) setBusy(false); locked.current = false; });
  }, [loaded, settings.enabled]);
  async function operation(action: () => Promise<void>) {
    if (locked.current) return;
    locked.current = true; setBusy(true); setError('');
    try { await action(); } catch (e) { failure(e); }
    finally { locked.current = false; if (active.current) setBusy(false); }
  }
  function cancel() { generation.current++; release(values.current.preview); setPreview(null); }
  useEffect(() => {
    if (!loaded || !settings.enabled || visible && !compact) return;
    const timer = setTimeout(() => {
      sleeping.current=true;
      generation.current++;release(values.current.current);release(values.current.preview);
      if(values.current.preview)setManager(true);
      setCurrent(null);setPreview(null);setSuspended(true);
    }, 60000);
    return () => clearTimeout(timer);
  }, [loaded, settings.enabled, visible, compact]);
  useEffect(() => {
    if(!suspended || !visible || compact || busy || !settings.enabled)return;
    sleeping.current=false;
    setSuspended(false);
    void operation(() => scan(true));
  }, [suspended, visible, compact, busy, settings.enabled]);
  function status(url: string, status: string, reason?: string) {
    const value = values.current.preview?.url === url ? values.current.preview : values.current.current?.url === url ? values.current.current : null;
    if (!value) return;
    const candidate = values.current.preview?.url === url;
    if (status === 'failed') {
      release(value); if (candidate) { setPreview(null); setManager(true); } else setCurrent(null);
      setRetryTarget({project:value.project,restoring:!candidate});
      const reasons: Record<string,string> = { missing:'壁纸资源已丢失，请检查目录后刷新', video:'视频加载或解码失败', scene:'场景加载或渲染失败，部分格式或特效可能不受支持', context:'图形渲染已中断', timeout:'壁纸加载超时' };
      setError(`${reasons[reason ?? ''] ?? '壁纸加载失败'}。已保留原有背景。`); return;
    }
    if (status === 'ready' || status === 'warning') {
      if(status === 'ready'){setError('');setRetryTarget(null);}
      const update = (old: RunningWallpaper | null) => old?.url === url ? { ...old, ready: old.ready || status === 'ready', warning: old.warning || status === 'warning' } : old;
      if (candidate) setPreview(update); else setCurrent(update);
    }
  }
  return {
    settings, loaded, busy, error, items, reports, current, preview, visible, manager, setManager, status, cancel, suspended,
    canRetry: !!retryTarget || !!settings.selected && !current,
    retry: () => operation(async () => { if(retryTarget)await open(retryTarget.project,retryTarget.restoring);else await scan(!values.current.current); }),
    showing: !!(preview?.ready || current?.ready),
    setEnabled: (enabled: boolean) => operation(async () => {
      const next = await native.dynamicWallpaper.setEnabled(enabled); generation.current++; setSettings(next);
      if (!enabled) { sleeping.current=false;setPreview(null); setCurrent(null); setItems([]); setReports([]); setManager(false); setSuspended(false); setRetryTarget(null); }
    }),
    setPlayback: (playback: WallpaperPlayback) => operation(async () => { setSettings(await native.dynamicWallpaper.setPlayback(playback)); }),
    refresh: () => operation(() => scan(!values.current.current && !values.current.preview)),
    addDirectory: () => operation(async () => { setSettings(await native.dynamicWallpaper.addDirectory()); await scan(!values.current.current); }),
    removeDirectory: (directory: string) => operation(async () => { setSettings(await native.dynamicWallpaper.removeDirectory(directory)); await scan(); }),
    startPreview: (project: WallpaperProject) => operation(() => open(project, false)),
    apply: () => operation(async () => {
      const value = values.current.preview; if (!value?.ready) return;
      setSettings(await native.dynamicWallpaper.apply(value.project.id));
      release(values.current.current); setCurrent(value); setPreview(null);
      setItems(old => old.map(item => ({ ...item, selected: item.id === value.project.id })));
    }),
  };
}
export type DynamicWallpaperController = ReturnType<typeof useDynamicWallpaper>;
