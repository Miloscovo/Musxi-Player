import { onMounted, onUnmounted, ref, shallowRef } from 'vue';
import { native } from '../native/client';
import type { LibraryState } from '../native/library';
export function useLibrary() {
  const state = shallowRef<LibraryState | null>(null);
  const error = ref(''); const pending = ref(false); const page = ref(1);
  let active = true, reading = false;
  let timer: ReturnType<typeof setInterval>;
  const controller = new AbortController();
  async function refresh() {
    if (!active || reading) return; reading = true;
    const requestedPage = page.value;
    try {
      const value = await native.library.getState(requestedPage, controller.signal);
      if (active && requestedPage === page.value) {
        if (state.value && value.playlistId !== state.value.playlistId && page.value !== 1) page.value = 1;
        else state.value = value;
      }
    } catch (e) { if (active) error.value = String(e); }
    finally { reading = false; }
  }
  async function run(action: (signal: AbortSignal) => Promise<unknown>) {
    if (pending.value) return; pending.value = true; error.value = '';
    try { await action(controller.signal); await refresh(); }
    catch (e) { if (active) error.value = e instanceof Error ? e.message : String(e); }
    finally { if (active) pending.value = false; }
  }
  async function playback(action: (signal: AbortSignal) => Promise<unknown>) {
    try { await action(controller.signal); await refresh(); }
    catch (e) { if (active) error.value = e instanceof Error ? e.message : String(e); }
  }
  function stop() { active = false; clearInterval(timer); controller.abort(); }
  onMounted(() => { void refresh(); timer = setInterval(() => void refresh(), 800); window.addEventListener('pagehide', stop); });
  onUnmounted(() => { stop(); window.removeEventListener('pagehide', stop); });
  return { state, error, pending, page, refresh,
    search: (query: string, p = 1) => run(signal => native.library.search(query, p, signal)),
    open: (id: string) => { page.value = 1; return run(signal => native.library.open(id, signal)); },
    play: (source: 'search' | 'library', id: string) => playback(signal => native.library.play(source, id, signal)),
    skip: (delta: -1 | 1) => playback(signal => native.library.skip(delta, signal)),
    menu: (id: string) => run(signal => native.library.menu(id, signal)),
    favorite: (id: string, enabled: boolean) => run(signal => native.library.favorite(id, enabled, signal)),
    add: (id: string, playlistId: string) => run(signal => native.library.add(id, playlistId, signal)),
    login: () => run(signal => native.library.login(signal)), logout: () => run(signal => native.library.logout(signal)),
    sync: () => run(signal => native.library.sync(signal)), cancel: () => run(signal => native.library.cancel(signal)) };
}
