import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { native } from '../native/client';
import { NativeError } from '../native/types';
import type { UpdateState } from '../native/updates';
import { formatTime, pollDelay, shouldNotify } from './appUpdates';

// App-level update state shared by the dock notice and Settings. The native
// side schedules automatic checks; this hook only reads state and forwards
// user actions, so mounting Settings never starts a network request.
export function useAppUpdates() {
  const [state, setState] = useState<UpdateState | null>(null);
  const [unsupported, setUnsupported] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [busy, setBusy] = useState('');
  const [message, setMessage] = useState({ text: '', error: false, token: '0' });
  const [dismissed, setDismissed] = useState<ReadonlySet<string>>(new Set());
  const runtime = useRef({ active: false, unsupported: false, actionPending: false, started: 0, timer: 0, state: null as UpdateState | null });
  const notify = useCallback((text: string, error = false) =>
    setMessage(previous => ({ text, error, token: String(Number(previous.token) + 1) })), []);
  const accept = useCallback((next: UpdateState) => {
    runtime.current.state = next; setState(next); setLoadError('');
  }, []);
  const refresh = useCallback(async () => {
    try { accept(await native.updates.getState()); runtime.current.unsupported = false; setUnsupported(false); return true; }
    catch (e) {
      // An older backend answers unknown commands with 404: hide the feature, do not retry.
      if (e instanceof NativeError && e.code === 404) { runtime.current.unsupported = true; setUnsupported(true); }
      else setLoadError(e instanceof Error ? e.message : '读取更新状态失败');
      return false;
    }
  }, [accept]);
  const schedule = useCallback(() => {
    const r = runtime.current; window.clearTimeout(r.timer);
    if (!r.active || r.unsupported) return;
    r.timer = window.setTimeout(async () => {
      if (!r.active) return;
      if (!r.actionPending) await refresh();
      schedule();
    }, pollDelay(r.state, Date.now() - r.started));
  }, [refresh]);
  useEffect(() => {
    const r = runtime.current; r.active = true; r.started = Date.now();
    void refresh().then(() => schedule());
    return () => { r.active = false; window.clearTimeout(r.timer); };
  }, [refresh, schedule]);

  const actions = useMemo(() => {
    async function run(name: string, action: () => Promise<void>) {
      if (runtime.current.actionPending) return;
      runtime.current.actionPending = true;
      setBusy(name);
      try { await action(); }
      catch (e) {
        const code = e instanceof NativeError ? e.code : 0;
        if (code === 429) {
          await refresh();
          const retry = runtime.current.state?.retryAt ?? 0;
          notify(retry > Date.now() / 1000 ? `检查过于频繁，请在 ${formatTime(retry)} 后重试` : '检查过于频繁，请稍后重试', true);
        } else if (code === 409) {
          await refresh();
          const msg = e instanceof NativeError ? e.message : '';
          if (msg === 'shutting_down') notify('播放器正在退出', true);
          else if (msg === 'release_changed') notify('版本信息已更新，请重新确认', true);
          else if (msg === 'download_unavailable') notify('当前环境不支持应用内下载', true);
          else if (msg === 'download_busy') notify('下载任务进行中', true);
          else if (msg === 'download_not_ready') notify('安装包尚未准备就绪', true);
          else notify('版本信息已更新，请重新确认', true);
        } else if (code === 404) notify('当前播放器版本不支持此操作', true);
        else if (e instanceof NativeError && e.message === 'browser_open_failed') notify('无法打开系统浏览器', true);
        else if (e instanceof NativeError && e.message === 'storage_error') { await refresh(); notify(name === 'startDownload' ? '无法创建下载文件，请检查磁盘空间和写入权限' : '设置未能保存，请重试', true); }
        else if (e instanceof NativeError && e.message === 'installer_start_failed') { await refresh(); notify('无法启动安装程序，请稍后重试', true); }
        else notify(e instanceof Error && e.message ? `操作失败：${e.message}` : '操作失败', true);
      } finally { runtime.current.actionPending = false; setBusy(''); schedule(); }
    }
    return {
      check: () => run('check', async () => { accept(await native.updates.check()); }),
      setAutoCheck: (enabled: boolean) => run('preferences', async () => { accept(await native.updates.setPreferences(enabled)); }),
      ignore: (releaseId: string) => run('ignore', async () => { accept(await native.updates.ignoreRelease(releaseId)); }),
      openDownload: (releaseId: string, assetId: string) => run('download', async () => {
        await native.updates.openDownload(releaseId, assetId); notify('已在浏览器中打开下载');
      }),
      openNotes: (releaseId: string) => run('notes', async () => {
        await native.updates.openReleaseNotes(releaseId); notify('已在浏览器中打开发布说明');
      }),
      dismiss: (releaseId: string) => setDismissed(previous => new Set(previous).add(releaseId)),
      // Phase 2: in-app download actions
      startDownload: (releaseId: string, assetId: string) => run('startDownload', async () => {
        accept(await native.updates.startDownload(releaseId, assetId));
      }),
      cancelDownload: () => run('cancelDownload', async () => {
        accept(await native.updates.cancelDownload());
      }),
      install: (releaseId: string, assetId: string) => run('install', async () => {
        accept(await native.updates.install(releaseId, assetId));
        notify('正在启动安装程序，播放器即将退出');
      })
    };
  }, [accept, notify, refresh, schedule]);

  const refreshState = useCallback(async () => {
    const ok = await refresh(); schedule(); return ok;
  }, [refresh, schedule]);

  return { state, refresh: refreshState, unsupported, loadError, busy, message, notify: shouldNotify(state, dismissed),
    // The backend advertises persistent ignore by including the preference field.
    canIgnore: state?.preferences.ignoredReleaseId !== undefined, ...actions };
}
export type AppUpdates = ReturnType<typeof useAppUpdates>;
