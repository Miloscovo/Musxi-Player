<script setup lang="ts">
import { computed, ref } from 'vue';
import { usePlayerState } from './composables/usePlayerState';
import LibraryView from './views/LibraryView.vue';
import { useLibrary } from './composables/useLibrary';
const library = useLibrary();
const currentTrack = computed(() => library.state.value?.now);
const { state, error, loading, busy, connected, refresh, pause, resume, seek, setVolume } = usePlayerState();
const seekDraft = ref<number | null>(null);
const volumeDraft = ref<number | null>(null);
const inputValue = (event: Event) => Number((event.target as HTMLInputElement).value);
async function seekChanged(event: Event) {
  try { await seek(inputValue(event)); } finally { seekDraft.value = null; }
}
async function volumeChanged(event: Event) {
  try { await setVolume(inputValue(event)); } finally { volumeDraft.value = null; }
}
const status = computed(() => !state.value ? '正在连接播放器' : !state.value.opened ? '等待一首好歌' : state.value.playing ? '正在播放' : '已暂停');
const time = (ms: number) => `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`;
</script>

<template>
  <main>
    <LibraryView :library="library" />
    <section class="player" aria-label="播放器状态" :aria-busy="loading">
      <div class="art" aria-hidden="true">♫</div>
      <div class="track"><p class="eyebrow">NOW PLAYING</p><h2 id="playback">{{ status }}</h2>
        <p class="track-id" :title="currentTrack?.name || state?.trackId">{{ currentTrack?.name || state?.trackId || '尚未选择歌曲' }}</p>
        <p>{{ currentTrack?.artist }}</p></div>
      <dl v-if="state"><div><dt>播放进度</dt><dd id="position">{{ time(state.positionMs) }} / {{ time(state.durationMs) }}</dd></div>
        <div><dt>音量</dt><dd id="volume">{{ state.volumePercent }}%</dd></div></dl>
      <div v-if="state" class="controls">
        <label>歌曲进度<input type="range" min="0" :max="Math.max(0, state.durationMs - 1)" :value="seekDraft ?? state.positionMs"
          :disabled="busy || !connected || !state.opened || !state.durationMs" @input="seekDraft = inputValue($event)" @change="seekChanged" aria-label="歌曲进度"></label>
        <div class="play-controls"><button :disabled="busy || !connected || !state.opened" @click="state.playing ? pause() : resume()">{{ state.playing ? '暂停' : '继续播放' }}</button>
          <label>音量<input type="range" min="0" max="100" :value="volumeDraft ?? state.volumePercent" :disabled="busy || !connected" @input="volumeDraft = inputValue($event)" @change="volumeChanged" aria-label="音量"></label></div>
      </div>
      <footer><p id="connection" role="status" :class="{ error }">{{ error || (connected ? '已连接 · 实时同步' : '正在连接播放器…') }}</p>
        <button :disabled="loading" @click="refresh">刷新状态</button></footer>
    </section>
    <p class="note">播放状态与本机播放器实时同步</p>
  </main>
</template>
