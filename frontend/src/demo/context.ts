import type { CefTransport } from '../native/transport.ts';
import type { Track, PlaybackOrder, AudioQuality } from '../native/library.ts';
import { NativeError } from '../native/types.ts';
import { createDemoLibrary, createDemoPlayer, demoPlaylistSongs, demoSongs } from './data.ts';

export function isDemoMode(host: CefTransport = globalThis as CefTransport, search = globalThis.location?.search || '') {
  const query = new URLSearchParams(search);
  return !host.cefQuery && query.has('demo') && !query.has('smoke') && !query.has('tray');
}

// Exercise the real client/parsers/subscriptions with an in-memory bridge. No audio, files or network.
export function createDemoTransport(): CefTransport {
  const library = createDemoLibrary();
  let player = { ...createDemoPlayer() };
  const songs = demoSongs();
  const lists = new Map(library.playlists.map(list => [list.id, demoPlaylistSongs(list.id)]));
  const listeners = new Map<number, (reply: string) => void>();
  let requestId = 0, operationId = 0;
  let timer: ReturnType<typeof setInterval> | undefined;
  let lastTick = Date.now();
  const queue = () => library.queue!;
  const fail = (message: string, code = 409): never => { throw new NativeError(code, message); };
  const track = (id: string) => songs.find(song => song.id === id) || fail('演示歌曲不存在', 404);
  const currentRows = () => library.playlistId === 'musxi:local'
    ? songs.filter(song => song.id.startsWith('local:')) : lists.get(library.playlistId) || [];
  function emit(event = 'player.stateChanged') {
    const reply = JSON.stringify({ version: 1, event, state: player });
    for (const listener of listeners.values()) listener(reply);
    if (player.playing && listeners.size && !timer) {
      lastTick = Date.now();
      timer = setInterval(() => {
        const now = Date.now(); player.positionMs = Math.min(player.durationMs, player.positionMs + now - lastTick); lastTick = now;
        if (player.positionMs >= player.durationMs) {
          if (library.playbackOrder === 'repeat-one') player.positionMs = 0;
          else if (queue().length) skip(1);
          else { player.playing = false; player.requestedPlaying = false; player.phase = 'ended'; }
        }
        emit('player.positionChanged');
      }, 250);
    } else if ((!player.playing || !listeners.size) && timer) { clearInterval(timer); timer = undefined; }
  }
  function enqueue(song: Track) { if (!queue().some(row => row.id === song.id)) queue().push({ ...song }); }
  function play(song: Track) {
    enqueue(song); library.queueCurrentId = song.id;
    player = { ...player, trackId: song.id, opened: true, playing: true, requestedPlaying: true, phase: 'playing', positionMs: 0, durationMs: song.duration };
    library.now = { name: song.name, artist: song.artist, cover: song.cover, liked: lists.get('demo-favorites')!.some(row => row.id === song.id), canFavorite: !song.id.startsWith('local:') };
    library.qualities = { id: song.id, current: '128', options: [] };
    lastTick = Date.now(); emit('player.trackChanged');
  }
  function skip(delta: number) {
    if (!queue().length) return;
    const index = queue().findIndex(row => row.id === player.trackId);
    const next = library.playbackOrder === 'random' && queue().length > 1
      ? (Math.max(0, index) + 1 + Math.floor(Math.random() * (queue().length - 1))) % queue().length
      : (index + delta + queue().length) % queue().length;
    play(queue()[next]);
  }
  function online(id: string) {
    if (!library.connected) fail('请先登录演示账号');
    const song = track(id);
    if (id.startsWith('local:')) fail('本地歌曲无法收藏和添加到歌单');
    return song;
  }
  function run(command: string, p: Record<string, any>): unknown {
    if (command.startsWith('window.')) return { enabled: false, maximized: false };
    if (command === 'player.getState') return player;
    if (command.startsWith('player.')) {
      if (!player.opened && command !== 'player.setVolume') fail('请先选择演示歌曲');
      switch (command) {
        case 'player.pause': player.playing = false; player.requestedPlaying = false; player.phase = 'paused'; break;
        case 'player.resume': player.playing = true; player.requestedPlaying = true; player.phase = 'playing'; lastTick = Date.now(); break;
        case 'player.seek': if (!Number.isInteger(p.positionMs) || p.positionMs < 0) fail('无效播放位置', 400); player.positionMs = Math.min(p.positionMs, player.durationMs); lastTick = Date.now(); break;
        case 'player.setVolume': if (!Number.isInteger(p.volumePercent) || p.volumePercent < 0 || p.volumePercent > 100) fail('无效音量', 400); player.volumePercent = p.volumePercent; break;
        default: fail('不支持的演示命令', 404);
      }
      emit(); return player;
    }
    if (command === 'library.getState') {
      const rows = currentRows(); library.trackCount = rows.length;
      library.tracks = rows.slice((Math.max(1, p.page || 1) - 1) * 50, Math.max(1, p.page || 1) * 50);
      for (const list of [...library.playlists, ...library.localPlaylists!]) list.count = lists.get(list.id)?.length || 0;
      return library;
    }
    if (command === 'library.image') return '';
    switch (command) {
      case 'library.search': {
        const keyword = String(p.keywords).trim().toLocaleLowerCase();
        library.keywords = p.keywords; library.searchPage = p.page;
        const matches = songs.filter(song => !song.id.startsWith('local:') && keyword && [song.name, song.artist, song.album || ''].some(field => field.toLocaleLowerCase().includes(keyword)));
        library.search = p.page === 1 ? matches : []; library.searchTotal = matches.length; break;
      }
      case 'library.open':
        if (p.id !== 'musxi:local' && !lists.has(p.id)) fail('演示歌单不存在', 404);
        library.playlistId = p.id; library.playlistName = [...library.playlists, ...library.localPlaylists!].find(list => list.id === p.id)?.name || '本地歌曲整合'; break;
      case 'library.play': {
        const rows = p.source === 'queue' ? queue() : p.source === 'search' ? library.search : p.source === 'local' ? songs.filter(song => song.id.startsWith('local:')) : currentRows();
        const song = rows.find(row => row.id === p.id) || fail('歌曲不在当前列表中', 404); play(song); break;
      }
      case 'library.playPlaylist': {
        const rows = p.id === 'musxi:local' ? songs.filter(song => song.id.startsWith('local:')) : lists.get(p.id);
        const available = rows || fail('演示歌单不存在');
        if (!available.length) return fail('演示歌单为空'); available.forEach(enqueue); play(available[0]); break;
      }
      case 'library.importLocal':
        if (!lists.has('local-folder:demo')) {
          const rows = demoSongs().slice(0, 3).map((song, index) => ({ ...song, id: `local:${index}`, name: `${song.name}.flac` }));
          songs.push(...rows); lists.set('local-folder:demo', rows);
          library.localPlaylists!.push({ id: 'local-folder:demo', name: '演示音乐文件夹', artist: '', cover: '', duration: 0, count: rows.length, editable: false });
        }
        library.notice = '已添加虚拟演示文件夹，不读取真实文件。'; break;
      case 'library.queueNext': {
        const song = track(p.id); if (song.id === player.trackId) fail('歌曲正在播放');
        library.queue = queue().filter(row => row.id !== p.id);
        queue().splice(queue().findIndex(row => row.id === player.trackId) + 1, 0, { ...song }); break;
      }
      case 'library.queueRemove': library.queue = queue().filter(row => row.id !== p.id); break;
      case 'library.queueClear': library.queue = []; break;
      case 'library.skip': skip(p.delta); break;
      case 'library.setPlaybackOrder':
        if (!['sequential', 'random', 'repeat-one'].includes(p.order)) fail('无效播放模式', 400);
        library.playbackOrder = p.order as PlaybackOrder; break;
      case 'library.qualities':
        if (p.id !== player.trackId) fail('当前歌曲已改变'); online(p.id);
        library.qualities = { id: p.id, current: library.qualities?.current || '128', options: [{ id: '128', name: '标准音质' }, { id: '320', name: '高品质' }, { id: 'flac', name: '无损音质' }] }; break;
      case 'library.setQuality':
        if (p.id !== player.trackId || !library.qualities?.options.some(option => option.id === p.quality)) fail('请先获取当前歌曲的演示音质');
        (library.qualities || fail('请先获取演示音质')).current = p.quality as AudioQuality; break;
      case 'library.menu':
        online(p.id); library.menu = { id: p.id, liked: lists.get('demo-favorites')!.some(row => row.id === p.id), canFavorite: true, playlists: library.playlists.filter(list => list.editable) }; break;
      case 'library.favorite': {
        const song = online(p.id); const rows = lists.get('demo-favorites')!.filter(row => row.id !== p.id);
        if (p.enabled) rows.push({ ...song }); lists.set('demo-favorites', rows);
        library.menu.liked = !!p.enabled; if (player.trackId === p.id) library.now.liked = !!p.enabled; break;
      }
      case 'library.add': {
        const song = online(p.id); const list = library.playlists.find(list => list.id === p.playlistId && list.editable) || fail('此歌单不可编辑');
        const rows = lists.get(list.id)!; if (!rows.some(row => row.id === p.id)) rows.push({ ...song }); break;
      }
      case 'library.login': library.connected = true; library.notice = '已登录虚拟演示账号，不连接真实服务。'; break;
      case 'library.logout': library.connected = false; library.notice = '已退出虚拟演示账号。'; break;
      case 'library.sync': library.notice = '演示数据无需同步。'; break;
      case 'library.cancel': break;
      default: fail('不支持的演示命令', 404);
    }
    const id = String(++operationId);
    library.operation = { id, status: 'completed', kind: ['library.favorite', 'library.add'].includes(command) ? 'song_update' : command, error: '' };
    return { id };
  }
  return {
    cefQuery(query) {
      const id = ++requestId;
      try {
        const { version, command, params } = JSON.parse(query.request);
        if (version !== 1) fail('无效协议版本', 400);
        if (command === 'player.subscribe' && query.persistent) { listeners.set(id, query.onSuccess); emit(); }
        else query.onSuccess(JSON.stringify({ version: 1, result: run(command, params || {}) }));
      } catch (error) { query.onFailure(error instanceof NativeError ? error.code : 400, error instanceof Error ? error.message : '演示请求失败'); }
      return id;
    },
    cefQueryCancel(id) { listeners.delete(id); emit(); }
  };
}
