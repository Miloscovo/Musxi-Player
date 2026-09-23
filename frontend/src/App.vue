<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted } from 'vue';
import { useWindow } from './composables/useWindow';
import CoverImage from './components/CoverImage.vue';
import { usePlayerState } from './composables/usePlayerState';
import LibraryView from './views/LibraryView.vue';
import { useLibrary } from './composables/useLibrary';
const shell = useWindow();
const library = useLibrary();
const currentTrack = computed(() => library.state.value?.now);
const { state, error, loading, busy, connected, pause, resume, seek, setVolume } = usePlayerState();
const seekDraft = ref<number | null>(null);
const volumeDraft = ref<number | null>(null);
const inputValue = (event: Event) => Number((event.target as HTMLInputElement).value);
async function seekChanged(event: Event) {
  try { await seek(inputValue(event)); } finally { seekDraft.value = null; }
}
async function volumeChanged(event: Event) {
  try { await setVolume(inputValue(event)); } finally { volumeDraft.value = null; }
}
const wantsPlay = computed(() => state.value?.phase === 'failed' ? false : (state.value?.requestedPlaying ?? state.value?.playing ?? false));
const status = computed(() => state.value?.phase === 'failed' ?
  (/default output|output device/i.test(state.value.error || '') ? '输出设备已变化，重新播放将使用当前设备' : '播放失败，请重试') :
  state.value?.pending ? (state.value.phase === 'seeking' ? '正在跳转…' : '正在加载…') :
  !state.value ? '正在连接播放器' : !state.value.opened ? '等待一首好歌' : state.value.playing ? '正在播放' : '已暂停');
const time = (ms: number) => `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`;
function keyboard(event: KeyboardEvent) {
  if (event.ctrlKey || event.altKey || event.metaKey || event.repeat) return;
  const target = event.target as HTMLElement;
  if (target.closest('input, textarea, select, button, [contenteditable], [role=dialog]')) return;
  if (!state.value?.opened || !connected.value) return;
  if (event.code === 'Space') { event.preventDefault(); void (wantsPlay.value ? pause() : resume()); }
  if (event.code === 'ArrowLeft' || event.code === 'ArrowRight') {
    event.preventDefault(); void seek(Math.max(0, state.value.positionMs + (event.code === 'ArrowLeft' ? -5000 : 5000)));
  }
}
onMounted(() => window.addEventListener('keydown', keyboard));
onUnmounted(() => window.removeEventListener('keydown', keyboard));
</script>

<template>
  <main class="app-shell">
    <div v-if="shell.state.value.enabled" class="window-bar">
      <div class="window-buttons"><button aria-label="最小化" @click="shell.minimize">─</button><button :aria-label="shell.state.value.maximized ? '还原' : '最大化'" @click="shell.maximize">□</button><button class="window-close" aria-label="关闭" @click="shell.close">×</button></div>
    </div>
    <LibraryView :library="library" :theme="shell.theme.value" @theme="shell.changeTheme" />
    <section class="player" aria-label="播放器" :aria-busy="loading">
      <input v-if="state" class="seek-bar" type="range" min="0" :max="Math.max(0, state.durationMs - 1)" :value="seekDraft ?? state.positionMs"
        :title="time(seekDraft ?? state.positionMs)" :style="{ '--progress': `${state.durationMs ? (seekDraft ?? state.positionMs) / state.durationMs * 100 : 0}%` }"
        :disabled="!connected || !state.opened || !state.durationMs" @input="seekDraft = inputValue($event)" @change="seekChanged" aria-label="歌曲进度">
      <CoverImage :url="currentTrack?.cover || ''" />
      <div class="track"><h2 id="playback" :title="currentTrack?.name">{{ currentTrack?.name || '尚未选择歌曲' }}</h2><p>{{ currentTrack?.artist || status }}</p></div>
      <div class="play-controls" v-if="state">
        <button aria-label="上一首" @click="library.skip(-1)">⏮</button>
        <button class="play-button" :aria-label="wantsPlay ? '暂停' : '播放'" :disabled="!connected || !state.opened" @click="wantsPlay ? pause() : resume()">{{ wantsPlay ? 'Ⅱ' : '▶' }}</button>
        <button aria-label="下一首" @click="library.skip(1)">⏭</button>
        <label class="volume-control"><svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M3 9h4l5-4v14l-5-4H3z" fill="currentColor"/><path v-if="state.volumePercent" d="M16 8a6 6 0 0 1 0 8m3-11a10 10 0 0 1 0 14" fill="none" stroke="currentColor" stroke-width="1.5"/></svg><input type="range" min="0" max="100" :value="volumeDraft ?? state.volumePercent" :disabled="busy || !connected" @input="volumeDraft = inputValue($event)" @change="volumeChanged" aria-label="音量"><span id="volume" class="sr-only">{{ state.volumePercent }}%</span></label>
      </div>
      <span id="connection" class="connection" role="status">{{ error || (state?.phase === 'failed' || state?.pending ? status : '') || shell.error.value || (connected ? '' : '正在连接…') }}</span>
    </section>
  </main>
</template>
