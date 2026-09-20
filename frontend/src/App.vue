<script setup lang="ts">
import { computed } from 'vue';
import { usePlayerState } from './composables/usePlayerState';
const { state, error, loading, refresh } = usePlayerState();
const status = computed(() => !state.value ? '正在连接播放器' : !state.value.opened ? '等待一首好歌' : state.value.playing ? '正在播放' : '已暂停');
const time = (ms: number) => `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`;
</script>

<template>
  <main>
    <header><span class="brand">♪ <strong>Musxi Player</strong></span><span class="badge">状态预览</span></header>
    <section class="hero">
      <p class="eyebrow">YOUR MUSIC, CONNECTED</p>
      <h1>音乐，始终相伴。</h1>
      <p>在原生播放器中选择歌曲，这里将同步显示播放状态。</p>
    </section>
    <section class="player" aria-label="播放器状态" :aria-busy="loading">
      <div class="art" aria-hidden="true">♫</div>
      <div class="track"><p class="eyebrow">NOW PLAYING</p><h2 id="playback">{{ status }}</h2>
        <p class="track-id" :title="state?.trackId">{{ state?.trackId || '尚未选择歌曲' }}</p></div>
      <dl v-if="state"><div><dt>播放进度</dt><dd id="position">{{ time(state.positionMs) }} / {{ time(state.durationMs) }}</dd></div>
        <div><dt>音量</dt><dd id="volume">{{ state.volumePercent }}%</dd></div></dl>
      <progress v-if="state" :value="state.positionMs" :max="state.durationMs || 1" aria-label="歌曲进度"></progress>
      <footer><p id="connection" role="status" :class="{ error }">{{ error || (state ? '已连接 · 本机播放器' : '正在读取播放状态…') }}</p>
        <button :disabled="loading" @click="refresh">刷新状态</button></footer>
    </section>
    <p class="note">播放控制仍在原生窗口中完成 · 此预览只读取状态</p>
  </main>
</template>
