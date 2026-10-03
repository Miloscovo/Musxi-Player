import { useEffect, useMemo, useRef, useState } from 'react';
import { native } from '../native/client';
import type { PlayerState } from '../native/types';
import { verifyWebPage } from '../native/smoke';
export function usePlayerState() {
  const [state, setState] = useState<PlayerState | null>(null);
  const [error, setError] = useState(''); const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false); const [connected, setConnected] = useState(false);
  const smokeStarted = useRef(false);
  const runtime = useRef({ active: false, loading: false, connected: false, epoch: 0,
    controller: undefined as AbortController | undefined, commandController: undefined as AbortController | undefined });
  const actions = useMemo(() => {
    const r = runtime.current;
    async function refresh() {
      if (!r.active || r.loading) return;
      r.loading = true; setLoading(true);
      const current = new AbortController(); r.controller = current; const started = r.epoch;
      try {
        const snapshot = await native.player.getState({ signal: current.signal });
        if (r.active && !current.signal.aborted && started === r.epoch) setState(snapshot);
      } catch (e) { if (r.active && !current.signal.aborted) setError(e instanceof Error ? e.message : '读取状态失败'); }
      finally { if (r.active && r.controller === current) { r.loading = false; setLoading(false); } }
    }
    async function execute(action: (options: { signal: AbortSignal }) => Promise<PlayerState>) {
      if (!r.active || !r.connected) return;
      r.commandController?.abort(); setBusy(true); setError('');
      const current = new AbortController(); r.commandController = current;
      try { await action({ signal: current.signal }); if (r.active && !current.signal.aborted) await refresh(); }
      catch (e) { if (r.active && !current.signal.aborted) setError(e instanceof Error ? e.message : '操作失败'); }
      finally { if (r.active && r.commandController === current) setBusy(false); }
    }
    return { refresh,
      pause: () => execute(options => native.player.pause(options)),
      resume: () => execute(options => native.player.resume(options)),
      seek: (positionMs: number) => execute(options => native.player.seek(positionMs, options)),
      setVolume: (volumePercent: number) => execute(options => native.player.setVolume(volumePercent, options)) };
  }, []);
  useEffect(() => {
    const r = runtime.current; r.active = true; r.loading = false; r.connected = false; ++r.epoch;
    setLoading(false); setBusy(false); setConnected(false);
    let retry: ReturnType<typeof setTimeout> | undefined; let unsubscribe: (() => void) | undefined;
    function connect() {
      if (!r.active) return;
      unsubscribe?.();
      unsubscribe = native.player.subscribe(event => {
        if (!r.active) return;
        ++r.epoch; r.connected = true; setState(event.state); setConnected(true); setError('');
      }, e => {
        if (!r.active) return;
        r.connected = false; setConnected(false); setError(e.message); retry = setTimeout(connect, 1500);
      });
    }
    function stop() {
      r.active = false; clearTimeout(retry); unsubscribe?.(); r.controller?.abort(); r.commandController?.abort();
    }
    void actions.refresh(); connect(); window.addEventListener('pagehide', stop);
    return () => { stop(); window.removeEventListener('pagehide', stop); };
  }, [actions]);
  useEffect(() => {
    if (connected && state && !smokeStarted.current && new URLSearchParams(location.search).has('smoke')) {
      smokeStarted.current = true; void verifyWebPage().catch(e => {
        setError(String(e)); console.error('CEF smoke failed:', String(e));
        void native.window.close(); // Test failures must exit instead of looking like a startup hang.
      });
    }
  }, [connected, state]);
  return { state, error, loading, busy, connected, ...actions };
}
