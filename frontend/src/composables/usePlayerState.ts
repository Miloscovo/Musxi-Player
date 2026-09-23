import { onMounted, onUnmounted, readonly, shallowRef, ref, nextTick } from 'vue';
import { native } from '../native/client';
import type { PlayerState } from '../native/types';
import { verifyVuePage } from '../native/smoke';

export function usePlayerState() {
  const state = shallowRef<PlayerState | null>(null);
  const error = ref('');
  const loading = ref(false);
  const busy = ref(false);
  const connected = ref(false);
  let active = false;
  let retry: ReturnType<typeof setTimeout> | undefined;
  let unsubscribe: (() => void) | undefined;
  let commandController: AbortController | undefined;
  let epoch = 0;
  let controller: AbortController | undefined;
  let smokeStarted = false;
  async function refresh() {
    if (!active || loading.value) return;
    loading.value = true;
    const current = new AbortController(); controller = current;
    const started = epoch;
    try {
      const snapshot = await native.player.getState({ signal: current.signal });
      if (active && !current.signal.aborted && started === epoch) state.value = snapshot;
    } catch (e) {
      if (active && !current.signal.aborted) error.value = e instanceof Error ? e.message : '读取状态失败';
    } finally { if (active && controller === current) loading.value = false; }
  }
  function connect() {
    if (!active) return;
    unsubscribe?.();
    unsubscribe = native.player.subscribe(event => {
      if (!active) return;
      ++epoch; state.value = event.state; connected.value = true; error.value = '';
      if (!smokeStarted && new URLSearchParams(location.search).has('smoke')) {
        smokeStarted = true;
        void nextTick().then(verifyVuePage).catch(e => { error.value = String(e); });
      }
    }, e => {
      if (!active) return;
      connected.value = false; error.value = e.message;
      retry = setTimeout(connect, 1500);
    });
  }
  async function execute(action: (options: { signal: AbortSignal }) => Promise<PlayerState>) {
    if (!active || !connected.value) return;
    commandController?.abort();
    busy.value = true; error.value = '';
    const current = new AbortController(); commandController = current;
    try {
      await action({ signal: current.signal });
      if (active && !current.signal.aborted) await refresh();
    } catch (e) {
      if (active && !current.signal.aborted) error.value = e instanceof Error ? e.message : '操作失败';
    } finally { if (active && commandController === current) busy.value = false; }
  }
  function stop() {
    active = false; clearTimeout(retry); unsubscribe?.(); controller?.abort(); commandController?.abort();
  }
  onMounted(() => {
    active = true; void refresh(); connect();
    window.addEventListener('pagehide', stop);
  });
  onUnmounted(() => { stop(); window.removeEventListener('pagehide', stop); });
  return { state: readonly(state), error: readonly(error), loading: readonly(loading),
    busy: readonly(busy), connected: readonly(connected), refresh,
    pause: () => execute(options => native.player.pause(options)),
    resume: () => execute(options => native.player.resume(options)),
    seek: (positionMs: number) => execute(options => native.player.seek(positionMs, options)),
    setVolume: (volumePercent: number) => execute(options => native.player.setVolume(volumePercent, options)) };
}
