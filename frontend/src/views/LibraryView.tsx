import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import type { useLibrary } from '../composables/useLibrary';
import type { Theme } from '../native/window';
import { safeImage, type Track } from '../native/library';
import CoverImage from '../components/CoverImage';
import { isDemoMode } from '../demo/context.ts';

type ThemeOption = 'light' | 'dark' | 'glass';
const themes: { id: ThemeOption; label: string; description: string }[] = [
  { id: 'light', label: '浅色', description: '明亮、清爽的白色界面' },
  { id: 'dark', label: '深色', description: '低亮度的深色界面' },
  { id: 'glass', label: '半透明', description: '与浅色或深色搭配使用' },
];
function appendUnique(previous: Track[], incoming?: Track[]) {
  if (!incoming?.length) return previous;
  const ids = new Set(previous.map(track => track.id)); const rows = [...previous];
  for (const track of incoming) if (!ids.has(track.id)) { ids.add(track.id); rows.push(track); }
  return rows;
}
export default function LibraryView({ library: lib, theme, onTheme, transparency = 50, onTransparency }: {
  library: ReturnType<typeof useLibrary>; theme: Theme; onTheme: (next: Theme) => Promise<void>;
  transparency?: number; onTransparency?: (value: number) => void;
}) {
  const { state, error, pending, page } = lib;
  const demoMode = isDemoMode();
  const [playlistRows, setPlaylistRows] = useState<Track[]>([]); const [searchRows, setSearchRows] = useState<Track[]>([]);
  const [view, setView] = useState<'library' | 'search' | 'settings'>('library');
  const [query, setQuery] = useState(''); const [showTracks, setShowTracks] = useState(false);
  const [playlistQuery, setPlaylistQuery] = useState('');
  const content = useRef<HTMLDivElement>(null);
  const songScroll = useRef<HTMLDivElement>(null);
  const songList = useRef<HTMLDivElement>(null);
  const [listOffset, setListOffset] = useState(0);
  const previousPlaylist = useRef<string | undefined>(undefined); const previousKeywords = useRef<string | undefined>(undefined);
  const loadingMore = useRef(false); const [scrollbarVisible, setScrollbarVisible] = useState(false);
  const [account, setAccount] = useState(false); const [menuId, setMenuId] = useState('');
  const [target, setTarget] = useState(''); const [confirmLogout, setConfirmLogout] = useState(false);
  const isDark = theme === 'dark' || theme === 'glass';
  const translucent = theme === 'glass' || theme === 'glass-light';
  const playlistFilter = playlistQuery.trim().toLocaleLowerCase();
  const filteredPlaylistRows = useMemo(() => playlistFilter
    ? playlistRows.filter(song => [song.name, song.artist, song.album || '']
      .some(value => value.toLocaleLowerCase().includes(playlistFilter)))
    : playlistRows, [playlistRows, playlistFilter]);
  const rows = view === 'search' ? searchRows : filteredPlaylistRows;
  const busy = pending || !!state?.busy;
  const tracksVisible = view !== 'settings' && (view === 'search' || showTracks);
  const virtualSongs = useVirtualizer({
    count: tracksVisible ? rows.length : 0,
    getScrollElement: () => songScroll.current,
    getItemKey: index => rows[index].id,
    estimateSize: () => window.innerWidth <= 1100 ? 70 : 61,
    scrollMargin: listOffset,
    overscan: 8,
    useFlushSync: false,
  });
  // Headers stay outside the song viewport; only the list padding offsets rows.
  function measureListOffset() {
    const element = songScroll.current; const list = songList.current;
    if (element && list) setListOffset(list.getBoundingClientRect().top
      - element.getBoundingClientRect().top + element.scrollTop
      + parseFloat(getComputedStyle(list).paddingTop));
  }
  useLayoutEffect(measureListOffset);
  useLayoutEffect(() => {
    const element = content.current;
    if (!element) return;
    let width = element.clientWidth;
    const observer = new ResizeObserver(() => {
      if (width !== element.clientWidth) {
        width = element.clientWidth;
        virtualSongs.measure();
      }
      measureListOffset();
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [virtualSongs]);
  function selectedTheme(id: ThemeOption) { return id === 'glass' ? translucent : id === 'dark' ? isDark : !isDark; }
  function chooseTheme(id: ThemeOption) {
    const dark = id === 'glass' ? isDark : id === 'dark';
    const glass = id === 'glass' ? !translucent : translucent;
    void onTheme(glass ? (dark ? 'glass' : 'glass-light') : (dark ? 'dark' : 'light'));
  }
  useLayoutEffect(() => {
    const changed = state?.playlistId !== previousPlaylist.current;
    previousPlaylist.current = state?.playlistId;
    if (changed) setPlaylistQuery('');
    setPlaylistRows(previous => appendUnique(changed ? [] : previous, state?.tracks));
  }, [state?.playlistId, state?.tracks]);
  useLayoutEffect(() => {
    const changed = state?.keywords !== previousKeywords.current;
    previousKeywords.current = state?.keywords;
    setSearchRows(previous => appendUnique(changed ? [] : previous, state?.search));
  }, [state?.keywords, state?.search]);
  function resetScroll() { if (content.current) content.current.scrollTop = 0; if (songScroll.current) songScroll.current.scrollTop = 0; setScrollbarVisible(false); }
  useEffect(() => {
    function updateScrollbarVisibility(event: PointerEvent) {
      const element = songScroll.current;
      if (!element) { setScrollbarVisible(false); return; }
      const bounds = element.getBoundingClientRect();
      const edgeWidth = Math.max(14, element.offsetWidth - element.clientWidth + 4);
      setScrollbarVisible(event.clientX >= bounds.right - edgeWidth && event.clientX <= window.innerWidth);
    }
    window.addEventListener('pointermove', updateScrollbarVisibility, { passive: true });
    return () => window.removeEventListener('pointermove', updateScrollbarVisibility);
  }, []);
  useLayoutEffect(resetScroll, [view, state?.playlistId, state?.keywords]);
  async function loadMore() {
    if (loadingMore.current || busy || !state || !content.current?.querySelector('.track-table')) return;
    if (view === 'library' && showTracks && page * 50 < state.trackCount) {
      loadingMore.current = true;
      try { await lib.loadNextPage(); } finally { loadingMore.current = false; }
    } else if (view === 'search' && state.searchMore && state.keywords) {
      loadingMore.current = true;
      try { await lib.search(state.keywords, state.searchPage + 1); } finally { loadingMore.current = false; }
    }
  }
  function checkLoadMore() {
    // A playlist filter must also search songs beyond the pages already shown.
    if (view === 'library' && showTracks && playlistFilter) { void loadMore(); return; }
    // Wait for accumulated rows to render before checking the scroll height.
    if (!rows.length && (view === 'search' ? state?.search.length : state?.tracks.length)) return;
    const element = songScroll.current;
    if (element && element.scrollHeight - element.scrollTop - element.clientHeight <= 140) void loadMore();
  }
  useEffect(checkLoadMore, [rows.length, playlistRows.length, busy, playlistFilter, view, showTracks]);
  async function open(id: string) { resetScroll(); setPlaylistQuery(''); await lib.open(id); setShowTracks(true); }
  async function searchSongs() {
    resetScroll();
    if (view === 'library' && showTracks) return;
    setSearchRows([]); setView('search'); setShowTracks(true); await lib.search(query);
  }
  async function menu(id: string) { setMenuId(id); setTarget(''); await lib.menu(id); }
  const time = (ms: number) => `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`;

  return <section className="library-shell" data-theme={theme}>
    <aside className="sidebar">
      <button className="avatar-button" aria-label="账号" onClick={() => setAccount(true)}>{state?.avatar ? <img src={safeImage(state.avatar)} alt="用户头像"/> : <span aria-hidden="true"/>}</button>
      <p className="eyebrow">MUSXI PLAYER</p>
      <button className={view === 'search' ? 'selected' : ''} onClick={() => { resetScroll(); setView('search'); setShowTracks(false); }}>
        <svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><circle cx="10" cy="10" r="6.5" fill="none" stroke="currentColor" strokeWidth="1.8"/><path d="m15 15 6 6" stroke="currentColor" strokeWidth="1.8"/></svg> 发现</button>
      <button className={view === 'library' ? 'selected' : ''} onClick={() => { resetScroll(); setView('library'); setShowTracks(false); }}>
        <svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M10 18V5l10-2v13" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round"/><circle cx="7" cy="18" r="3" fill="currentColor"/><circle cx="17" cy="16" r="3" fill="currentColor"/></svg> 音乐库</button>
      <button className={`settings-nav${view === 'settings' ? ' selected' : ''}`} aria-label="设置" onClick={() => { resetScroll(); setView('settings'); setShowTracks(false); }}>
        <svg className="icon" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round"><path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.09a2 2 0 0 1 1 1.73v.5a2 2 0 0 1-1 1.73l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.38a2 2 0 0 0-.73-2.73l-.15-.09a2 2 0 0 1-1-1.73v-.5a2 2 0 0 1 1-1.73l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/></svg> 设置</button>
    </aside>
    <div ref={content} className={`library-content${scrollbarVisible ? ' scrollbar-visible' : ''}`} onScroll={checkLoadMore}>
      <header className={view === 'library' ? (showTracks ? 'playlist-header' : 'library-home-header') : ''}><div className="page-heading">
        {view === 'library' && showTracks && <button className="back-button" aria-label="返回歌单" onClick={() => { resetScroll(); setShowTracks(false); }}><svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="m15 5-7 7 7 7" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg></button>}
        <h1 className={view === 'search' ? 'discovery-title' : view === 'settings' ? 'settings-title' : ''}>{view === 'settings' ? '设置' : view === 'search' ? '发现' : showTracks ? state?.playlistName : '音乐库'}</h1>
      </div><div className="header-actions">
        {(view === 'search' || showTracks) && <form className="collection-search" onSubmit={event => { event.preventDefault(); void searchSongs(); }}>
          <button type="submit" disabled={busy || !(view === 'library' ? playlistQuery : query).trim()} aria-label="搜索"><svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.8" cy="10.8" r="6.6" fill="none" stroke="currentColor" strokeWidth="2"/><path d="m16 16 5 5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/></svg></button>
          <input value={view === 'library' ? playlistQuery : query} onChange={event => {
            if (view === 'library') { resetScroll(); setPlaylistQuery(event.currentTarget.value); }
            else setQuery(event.currentTarget.value);
          }} maxLength={120} placeholder={view === 'library' ? '搜索此歌单中的歌曲、歌手或专辑…' : '搜索歌曲、歌手或专辑…'} aria-label="搜索歌曲、歌手或专辑"/>
        </form>}
        {view === 'library' && !showTracks && state?.connected && !demoMode && <button disabled={busy} onClick={lib.sync}>同步歌单</button>}
      </div></header>
      {demoMode && <p className="demo-notice" role="status">演示模式 · 示例数据仅在本机页面中使用，不连接账号、网络或真实音频设备</p>}
      {view === 'library' && !showTracks && <div className="welcome"><h2>{state?.connected ? `你好，${state.user}` : '把喜欢的音乐带到这里'}</h2><p>{state?.status}</p>{!state?.connected && <button onClick={() => setAccount(true)}>扫码登录</button>}</div>}
      {error && <p role="alert" className="error">{error}</p>}
      {state?.operation.status === 'failed' && <p role="alert" className="error">{state.operation.error}</p>}
      {state?.notice && <p role="status">{state.notice}</p>}
      {view === 'settings' ? <section className="settings-content" aria-label="外观设置"><h2>外观</h2><p>选择适合你的界面主题</p>
        <div className="theme-options">{themes.map(option => <div key={option.id} className="theme-card"><button className={`theme-option${selectedTheme(option.id) ? ' selected' : ''}`} aria-pressed={selectedTheme(option.id)} onClick={() => chooseTheme(option.id)}>
          <span className={`theme-swatch ${option.id}${option.id === 'glass' && !isDark ? ' glass-light' : ''}`} aria-hidden="true"><span/></span>
          <strong>{option.label}</strong><small>{option.description}</small>
        </button>{option.id === 'glass' && <input className="transparency-slider" type="range" min="0" max="100" step="1" value={transparency}
          aria-label="背景透明度" aria-valuetext={`${transparency}%透明`} title={`背景透明度 ${transparency}%`}
          onChange={event => onTransparency?.(Number(event.currentTarget.value))} />}</div>)}</div>
      </section> : view === 'library' && !showTracks ? <>
        <h2>我的收藏与歌单</h2><div className="playlist-grid">{state?.playlists.map(list => <button key={list.id} className="playlist" disabled={busy} onClick={() => open(list.id)}><CoverImage url={list.cover}/><strong>{list.name}</strong><span>{list.count} 首</span></button>)}</div>
        {state?.connected && !state.playlists.length && <p>暂无歌单，点击同步歌单刷新。</p>}
      </> : view === 'search' && !state?.keywords && !busy ? <div className="discover-empty"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10" cy="10" r="6.5"/><path d="m15 15 6 6"/></svg><h2>发现好音乐</h2><p>在右上角搜索歌曲、歌手或专辑</p></div> : <>
        <div className="track-table" role="table" aria-label={view === 'library' ? state?.playlistName : '搜索结果'}>
          <div className="track-table-head track-row" role="row"><span role="columnheader" aria-label="序号"/><span aria-hidden="true"/><span role="columnheader">歌曲</span><span role="columnheader">歌手</span><span role="columnheader">专辑</span><span role="columnheader">时长</span><span aria-hidden="true"/></div>
          <div ref={songScroll} className={`song-scroll${scrollbarVisible ? ' scrollbar-visible' : ''}`} onScroll={checkLoadMore}>
          <div ref={songList} className="song-list" role="rowgroup" style={{ position: 'relative', height: virtualSongs.getTotalSize() + 12 }}>{virtualSongs.getVirtualItems().map(item => {
            const index = item.index; const song = rows[index];
            return <div key={song.id} ref={virtualSongs.measureElement} data-index={index} aria-rowindex={index + 2} className={`song track-row${state?.now?.name === song.name && state?.now?.artist === song.artist ? ' active' : ''}`} role="row"
            style={{ position: 'absolute', top: 12, left: 0, width: '100%', transform: `translateY(${item.start - listOffset}px)` }}
            onDoubleClick={() => lib.play(view === 'search' ? 'search' : 'library', song.id)}
            onContextMenu={event => { event.preventDefault(); void menu(song.id); }}>
            <span className="track-index" role="cell">{index + 1}</span><CoverImage url={song.cover}/><strong className="track-name" role="cell">{song.name}</strong>
            <span className="track-artist" role="cell">{song.artist}</span><span className="track-album" role="cell">{song.album || '—'}</span><span className="track-duration" role="cell">{time(song.duration)}</span>
            <div className="song-actions"><button className="song-play" disabled={pending || !state?.connected} aria-label={`播放 ${song.name}`} onClick={() => lib.play(view === 'search' ? 'search' : 'library', song.id)}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 4v16l13-8z"/></svg></button><button className="song-menu" disabled={busy} aria-label="歌曲操作" onClick={() => menu(song.id)}>•••</button></div>
          </div>;
          })}</div>
          {!rows.length && <p className="empty-tracks">{busy ? '正在加载…' : view === 'library' && playlistFilter ? (state && page * 50 < state.trackCount && !error ? '正在搜索歌单…' : '未找到匹配的歌曲') : '暂无歌曲'}</p>}
          </div>
        </div>
      </>}
    </div>
    {account && <div className="modal-backdrop" onClick={event => { if (event.target === event.currentTarget) setAccount(false); }}><section className="modal" role="dialog" aria-modal="true" aria-label="账号">
      <button className="close" onClick={() => setAccount(false)}>关闭</button><h2>{state?.connected ? state.user : '酷狗概念版登录'}</h2>
      {!state?.connected ? <>{state?.qr && <img className="qr" src={safeImage(state.qr)} alt="登录二维码"/>}<p>{state?.status}</p><button disabled={busy} onClick={lib.login}>生成 / 刷新二维码</button></> :
        !confirmLogout ? <button onClick={() => setConfirmLogout(true)}>退出账号</button> : <p>退出将清除本机登录信息。<button disabled={busy} onClick={() => { void lib.logout(); setConfirmLogout(false); }}>确认退出</button></p>}
    </section></div>}
    {menuId && <div className="modal-backdrop" onClick={event => { if (event.target === event.currentTarget) setMenuId(''); }}><section className="modal" role="dialog" aria-modal="true" aria-label="歌曲操作">
      <button className="close" onClick={() => setMenuId('')}>关闭</button><h2>歌曲操作</h2>
      {state?.menu.id === menuId ? <>
        <button disabled={busy || !state.menu.canFavorite} onClick={() => { void lib.favorite(menuId, !state.menu.liked); setMenuId(''); }}>{state.menu.liked ? '取消收藏' : '收藏到我喜欢'}</button>
        <label>添加到歌单<select value={target} onChange={event => setTarget(event.currentTarget.value)}><option value="">选择歌单</option>{state.menu.playlists.map(list => <option key={list.id} value={list.id}>{list.name}</option>)}</select></label>
        <button disabled={busy || !target} onClick={() => { void lib.add(menuId, target); setMenuId(''); }}>添加</button>
      </> : <p>{error || '正在读取歌曲信息…'}</p>}
    </section></div>}
  </section>;
}
