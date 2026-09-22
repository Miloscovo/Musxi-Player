<script setup lang="ts">
import { computed, ref } from 'vue';
import { useLibrary } from '../composables/useLibrary';
import { safeImage } from '../native/library';
import CoverImage from '../components/CoverImage.vue';
const props = defineProps<{ library: ReturnType<typeof useLibrary>; theme: string }>();
const emit = defineEmits<{ theme: [] }>();
const lib = props.library;
const { state, error, pending, page } = lib;
const view = ref<'library' | 'search'>('library'); const query = ref(''); const showTracks = ref(false);
const account = ref(false); const menuId = ref(''); const target = ref(''); const confirmLogout = ref(false);
const rows = computed(() => view.value === 'search' ? state.value?.search || [] : state.value?.tracks || []);
const busy = computed(() => pending.value || state.value?.busy);
async function open(id: string) { await lib.open(id); showTracks.value = true; }
async function menu(id: string) { menuId.value = id; target.value = ''; await lib.menu(id); }
const time = (ms: number) => `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`;
</script>
<template>
  <section class="library-shell" :data-theme="theme">
    <aside class="sidebar">
      <button class="avatar-button" aria-label="账号" @click="account = true"><img v-if="state?.avatar" :src="safeImage(state.avatar)" alt="用户头像"><span v-else aria-hidden="true"></span></button>
      <p class="eyebrow">MUSXI PLAYER</p>
      <button :class="{ selected: view === 'search' }" @click="view = 'search'"><svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><circle cx="10" cy="10" r="6.5" fill="none" stroke="currentColor" stroke-width="1.8"/><path d="m15 15 6 6" stroke="currentColor" stroke-width="1.8"/></svg> 发现</button>
      <button :class="{ selected: view === 'library' }" @click="view = 'library'">♫ 音乐库</button>
    </aside>
    <div class="library-content">
      <header><h1>{{ view === 'search' ? '发现' : '音乐库' }}</h1><div class="toolbar">
        <button v-if="state?.connected" :disabled="busy" @click="lib.sync">同步歌单</button>
        <button aria-label="切换主题" :title="theme" @click="emit('theme')">{{ theme === 'light' ? '☀' : theme === 'dark' ? '☾' : '◈' }}</button></div></header>
      <form v-if="view === 'search'" class="search-form" @submit.prevent="lib.search(query)"><input v-model="query" maxlength="120" placeholder="搜索歌曲、歌手" aria-label="搜索歌曲"><button :disabled="busy || !query.trim()">搜索</button></form>
      <div v-else class="welcome"><h2>{{ state?.connected ? `你好，${state.user}` : '把喜欢的音乐带到这里' }}</h2><p>{{ state?.status }}</p><button v-if="!state?.connected" @click="account = true">扫码登录</button></div>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <p v-if="state?.operation.status === 'failed'" role="alert" class="error">{{ state.operation.error }}</p>
      <p v-if="state?.notice" role="status">{{ state.notice }}</p>
      <p v-if="state?.operation.status === 'pending'">正在处理… <button v-if="state.operation.kind === 'audio'" @click="lib.cancel">取消播放请求</button></p>
      <template v-if="view === 'library' && !showTracks">
        <h2>我的收藏与歌单</h2><div class="playlist-grid">
          <button v-for="list in state?.playlists" :key="list.id" class="playlist" :disabled="busy" @click="open(list.id)"><CoverImage :url="list.cover"/><strong>{{ list.name }}</strong><span>{{ list.count }} 首</span></button>
        </div><p v-if="state?.connected && !state.playlists.length">暂无歌单，点击同步歌单刷新。</p>
      </template>
      <template v-else>
        <div class="toolbar"><button v-if="view === 'library'" @click="showTracks = false">返回歌单</button><h2>{{ view === 'library' ? state?.playlistName : '搜索结果' }}</h2></div>
        <div class="song-list"><div v-for="song in rows" :key="song.id" class="song" @dblclick="lib.play(view, song.id)" @contextmenu.prevent="menu(song.id)">
          <CoverImage :url="song.cover"/><div class="song-title"><strong>{{ song.name }}</strong><span>{{ song.artist }}</span></div><span>{{ time(song.duration) }}</span>
          <button :disabled="pending || !state?.connected" :aria-label="`播放 ${song.name}`" @click="lib.play(view, song.id)">▶</button><button :disabled="busy" aria-label="歌曲操作" @click="menu(song.id)">•••</button>
        </div></div><p v-if="!rows.length">{{ busy ? '正在加载…' : '暂无歌曲' }}</p>
        <div class="pagination" v-if="view === 'search'"><button :disabled="busy || !state || state.searchPage <= 1" @click="lib.search(state!.keywords, state!.searchPage - 1)">上一页</button><span>{{ state?.searchPage || 1 }}</span><button :disabled="busy || !state?.searchMore" @click="lib.search(state!.keywords, state!.searchPage + 1)">下一页</button></div>
        <div class="pagination" v-else><button :disabled="page <= 1" @click="page--; lib.refresh()">上一页</button><span>{{ page }}</span><button :disabled="!state || page * 50 >= state.trackCount" @click="page++; lib.refresh()">下一页</button></div>
      </template>
    </div>
    <div v-if="account" class="modal-backdrop" @click.self="account = false"><section class="modal" role="dialog" aria-modal="true" aria-label="账号"><button class="close" @click="account = false">关闭</button><h2>{{ state?.connected ? state.user : '酷狗概念版登录' }}</h2>
      <template v-if="!state?.connected"><img v-if="state?.qr" class="qr" :src="safeImage(state.qr)" alt="登录二维码"><p>{{ state?.status }}</p><button :disabled="busy" @click="lib.login">生成 / 刷新二维码</button></template>
      <template v-else><button v-if="!confirmLogout" @click="confirmLogout = true">退出账号</button><p v-else>退出将清除本机登录信息。<button :disabled="busy" @click="lib.logout(); confirmLogout = false">确认退出</button></p></template></section></div>
    <div v-if="menuId" class="modal-backdrop" @click.self="menuId = ''"><section class="modal" role="dialog" aria-modal="true" aria-label="歌曲操作"><button class="close" @click="menuId = ''">关闭</button><h2>歌曲操作</h2>
      <template v-if="state?.menu.id === menuId"><button :disabled="busy || !state.menu.canFavorite" @click="lib.favorite(menuId, !state.menu.liked); menuId = ''">{{ state.menu.liked ? '取消收藏' : '收藏到我喜欢' }}</button>
        <label>添加到歌单<select v-model="target"><option value="">选择歌单</option><option v-for="list in state.menu.playlists" :key="list.id" :value="list.id">{{ list.name }}</option></select></label><button :disabled="busy || !target" @click="lib.add(menuId, target); menuId = ''">添加</button></template><p v-else>{{ error || '正在读取歌曲信息…' }}</p></section></div>
  </section>
</template>
