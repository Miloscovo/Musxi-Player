import { useEffect, useMemo, useRef, useState } from 'react';
import { native } from '../native/client';
import type { LibraryState, PlaybackOrder, AudioQuality } from '../native/library';
export function useLibrary() {
  const [state, setState] = useState<LibraryState | null>(null);
  const [error, setError] = useState(''); const [pending, setPending] = useState(false); const [page, setPage] = useState(1);
  const runtime = useRef({ active: false, pending: false, page: 1, state: null as LibraryState | null,
    controller: new AbortController(), task: null as Promise<boolean> | null });
  const actions = useMemo(() => {
    const r = runtime.current;
    function changePage(next: number) { r.page = next; setPage(next); }
    function refresh(): Promise<boolean> {
      if (!r.active) return Promise.resolve(false);
      if (r.task) return r.task;
      const requestedPage = r.page; const signal = r.controller.signal;
      const task = (async () => {
        try {
          const value = await native.library.getState(requestedPage, signal);
          if (r.active && !signal.aborted && requestedPage === r.page) {
            if (r.state && value.playlistId !== r.state.playlistId && r.page !== 1) changePage(1);
            else { r.state = value; setState(value); return true; }
          }
        } catch (e) { if (r.active && !signal.aborted) setError(String(e)); }
        return false;
      })();
      const pendingTask = task.finally(() => { if (r.task === pendingTask) r.task = null; });
      r.task = pendingTask; return pendingTask;
    }
    async function loadNextPage() {
      if (r.task) await r.task;
      if (!r.active || !r.state || r.page * 50 >= r.state.trackCount) return false;
      const previousPage = r.page; changePage(previousPage + 1);
      if (await refresh()) return true;
      if (r.page === previousPage + 1) changePage(previousPage);
      return false;
    }
    async function run(action: (signal: AbortSignal) => Promise<unknown>) {
      if (!r.active || r.pending) return;
      r.pending = true; setPending(true); setError('');
      const signal = r.controller.signal;
      try { await action(signal); await refresh(); }
      catch (e) { if (r.active && !signal.aborted) setError(e instanceof Error ? e.message : String(e)); }
      finally { if (r.active && !signal.aborted) { r.pending = false; setPending(false); } }
    }
    async function playback(action: (signal: AbortSignal) => Promise<unknown>) {
      if (!r.active) return;
      const signal = r.controller.signal;
      try { await action(signal); await refresh(); }
      catch (e) { if (r.active && !signal.aborted) setError(e instanceof Error ? e.message : String(e)); }
    }
    return { refresh, loadNextPage,
      search: (query: string, p = 1) => run(signal => native.library.search(query, p, signal)),
      open: (id: string) => { changePage(1); return run(signal => native.library.open(id, signal)); },
      play: (source: 'search' | 'library' | 'queue' | 'local', id: string) => playback(signal => native.library.play(source, id, signal)),
      playPlaylist: (id: string) => run(signal => native.library.playPlaylist(id, signal)),
      setPlaybackOrder: (order: PlaybackOrder) => run(signal => native.library.setPlaybackOrder(order, signal)),
      qualities: (id: string) => run(signal => native.library.qualities(id, signal)),
      setQuality: (id: string, quality: AudioQuality) => run(signal => native.library.setQuality(id, quality, signal)),
      queueNext: (source: 'search' | 'library' | 'local', id: string) => run(signal => native.library.queueNext(source, id, signal)),
      queueRemove: (id: string) => run(signal => native.library.queueRemove(id, signal)),
      queueClear: () => run(signal => native.library.queueClear(signal)),
      importLocal: () => run(signal => native.library.importLocal(signal)),
      skip: (delta: -1 | 1) => playback(signal => native.library.skip(delta, signal)),
      menu: (id: string) => run(signal => native.library.menu(id, signal)),
      favorite: (id: string, enabled: boolean) => run(signal => native.library.favorite(id, enabled, signal)),
      add: (id: string, playlistId: string) => run(signal => native.library.add(id, playlistId, signal)),
      login: () => run(signal => native.library.login(signal)), logout: () => run(signal => native.library.logout(signal)),
      sync: () => run(signal => native.library.sync(signal)), cancel: () => run(signal => native.library.cancel(signal)) };
  }, []);
  useEffect(() => {
    const r = runtime.current; r.active = true; r.pending = false; r.task = null;
    r.controller = new AbortController(); setPending(false);
    void actions.refresh(); const timer = setInterval(() => void actions.refresh(), 800);
    function stop() { r.active = false; clearInterval(timer); r.controller.abort(); }
    window.addEventListener('pagehide', stop);
    return () => { stop(); window.removeEventListener('pagehide', stop); };
  }, [actions]);
  return { state, error, pending, page, ...actions };
}
