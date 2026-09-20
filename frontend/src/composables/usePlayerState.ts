import { onMounted, onUnmounted, readonly, shallowRef, ref, nextTick } from 'vue';
import { native } from '../native/client';
import type { PlayerState } from '../native/types';
import { verifyVuePage } from '../native/smoke';

export function usePlayerState() {
  const state = shallowRef<PlayerState | null>(null);
  const error = ref('');
  const loading = ref(false);
  let active = false;
  let timer: ReturnType<typeof setInterval> | undefined;
  let controller: AbortController | undefined;
  let smokeStarted = false;
  async function refresh() {
    if (!active || loading.value) return;
    loading.value = true;
    const current = new AbortController(); controller = current;
    try {
      const snapshot = await native.player.getState({ signal: current.signal });
      if (active && !current.signal.aborted) { state.value = snapshot; error.value = ''; }
      if (active && !smokeStarted && new URLSearchParams(location.search).has('smoke')) {
        smokeStarted = true; await nextTick(); await verifyVuePage();
      }
    } catch (e) {
      if (active && !current.signal.aborted) error.value = e instanceof Error ? e.message : '读取状态失败';
    } finally { if (active && controller === current) loading.value = false; }
  }
  function stop() {
    active = false; clearInterval(timer); controller?.abort();
  }
  onMounted(() => {
    active = true; void refresh(); timer = setInterval(() => void refresh(), 500);
    window.addEventListener('pagehide', stop);
  });
  onUnmounted(() => { stop(); window.removeEventListener('pagehide', stop); });
  return { state: readonly(state), error: readonly(error), loading: readonly(loading), refresh };
}
