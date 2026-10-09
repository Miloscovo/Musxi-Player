import { AccountDialog, PlatformIcon } from '../components/AccountDialog';
import { platformNames, type MusicPlatform } from '../native/library';
import { isDemoMode } from '../demo/context.ts';
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import type { useLibrary } from '../composables/useLibrary';
import type { Theme } from '../native/window';
import { safeImage, type Track } from '../native/library';
import CoverImage from '../components/CoverImage';
import { Button } from '../components/ui/button';
import { SidebarProvider, Sidebar, SidebarRail } from '../components/ui/sidebar';
import { Tooltip, TooltipTrigger, TooltipProvider, TooltipContent } from '../components/ui/tooltip';
import { ContextMenu, ContextMenuTrigger, ContextMenuContent, ContextMenuItem,
  ContextMenuSub, ContextMenuSubTrigger, ContextMenuSubContent } from '../components/ui/context-menu';

import AppearanceSettings from '../components/AppearanceSettings';
import PlaybackSettings from '../components/PlaybackSettings';
import type { Appearance } from '../composables/appearance';
type LibrarySection = 'created' | 'saved' | 'local';
const librarySections: { id: LibrarySection; label: string; icon: string }[] = [
  { id: 'created', label: '自建歌单', icon: 'M12 8v8M8 12h8M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Z' },
  { id: 'saved', label: '收藏歌单', icon: 'm12 3 2.8 5.7 6.3.9-4.6 4.5 1.1 6.3-5.6-3-5.6 3 1.1-6.3L3 9.6l6.3-.9L12 3Z' },
  { id: 'local', label: '本地音乐', icon: 'M3 7V5a2 2 0 0 1 2-2h5l2 3h7a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z' },
];
function appendUnique(previous: Track[], incoming?: Track[]) {
  if (!incoming?.length) return previous;
  const indices = new Map(previous.map((track,index) => [track.id,index])); const rows = [...previous];
  for (const track of incoming) { const index=indices.get(track.id); if(index!==undefined)rows[index]=track;else {indices.set(track.id,rows.length);rows.push(track);} }
  return rows;
}
export default function LibraryView({ library: lib, theme, appearance, onAppearance, transparency = 50, onTransparency }: {
  library: ReturnType<typeof useLibrary>; theme: Theme; appearance?: Appearance; onAppearance?: (next: Appearance) => void;
  transparency?: number; onTransparency?: (value: number) => void;
}) {
  const { state, error, pending, page } = lib;
  const [playlistRows, setPlaylistRows] = useState<Track[]>([]); const [searchRows, setSearchRows] = useState<Track[]>([]);
  const [view, setView] = useState<'library' | 'search' | 'settings' | 'recent'>('library');
  const [query, setQuery] = useState(''); const [showTracks, setShowTracks] = useState(false);
  const [librarySection, setLibrarySection] = useState<LibrarySection>('created');
  const [settingsSection, setSettingsSection] = useState<'appearance' | 'playback'>('appearance');
  const [playlistQuery, setPlaylistQuery] = useState('');
  const content = useRef<HTMLDivElement>(null);
  const songScroll = useRef<HTMLDivElement>(null);
  const songList = useRef<HTMLDivElement>(null);
  const [listOffset, setListOffset] = useState(0);
  const previousPlaylist = useRef<string | undefined>(undefined); const previousKeywords = useRef<string | undefined>(undefined);
  const loadingMore = useRef(false); const [scrollbarVisible, setScrollbarVisible] = useState(false);
  const [account, setAccount] = useState(false); const [menuId, setMenuId] = useState('');
  const [accountPlatform, setAccountPlatform] = useState<MusicPlatform | null>(null);
  const isLocal = view === 'library' && librarySection === 'local';
  const visiblePlaylists = state?.playlists.filter(list => librarySection === 'created' ? list.editable
    : librarySection === 'saved' ? !list.editable : false).sort((a,b)=>librarySection==='created' ? Number(!!b.favorite)-Number(!!a.favorite) : 0) ?? [];
  const playlistFilter = playlistQuery.trim().toLocaleLowerCase();
  const filteredPlaylistRows = useMemo(() => playlistFilter
    ? playlistRows.filter(song => [song.name, song.artist, song.album || '']
      .some(value => value.toLocaleLowerCase().includes(playlistFilter)))
    : playlistRows, [playlistRows, playlistFilter]);
  const rows = view === 'recent' ? (state?.recent || []).filter(song=>!playlistFilter || [song.name,song.artist,song.album || ''].some(value=>value.toLocaleLowerCase().includes(playlistFilter))) : view === 'search' ? searchRows : isLocal && state?.playlistId !== 'musxi:local' && !state?.playlistId.startsWith('local-folder:') ? [] : filteredPlaylistRows;
  const busy = pending || !!state?.busy;
  const tracksVisible = view === 'recent' || view === 'search' || (view === 'library' && showTracks);
  const playSource = view === 'recent' ? 'recent' : view === 'search' ? 'search' : isLocal ? 'local' : 'library';
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
  useEffect(checkLoadMore, [rows.length, playlistRows.length, busy, playlistFilter, view, showTracks, isLocal]);
  async function open(id: string) { resetScroll(); setPlaylistQuery(''); await lib.open(id); setShowTracks(true); }
  async function searchSongs() {
    resetScroll();
    if (view === 'recent' || (view === 'library' && showTracks)) return;
    setSearchRows([]); setView('search'); setShowTracks(true); await lib.search(query);
  }
  async function menu(id: string) { setMenuId(id); await lib.menu(id); }
  const time = (ms: number) => `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`;

  const sidebar = <aside className="sidebar" aria-label="主导航">
      <div className="sidebar-accounts">{(['netease','qq','kugou'] as MusicPlatform[]).map(platform => {
        const entry = state?.accounts?.find(a => a.platform === platform);
        const connected = entry?.connected ?? (platform === 'kugou' && !!state?.connected);
        const avatar = connected ? safeImage(entry?.avatar || (platform === 'kugou' ? state?.avatar || '' : '')) : '';
        const label = platform === 'qq' ? 'QQ音乐' : platformNames[platform];
        return <div className="sidebar-account" key={platform}><PlatformIcon platform={platform}/><strong>{label}</strong>
          <Button variant="unstyled" className="platform-avatar" aria-label={`${label}账号管理`} title={connected ? entry?.user || '已登录' : '未登录'} onClick={() => {
            setAccountPlatform(platform); setAccount(true); if (!connected) void lib.login(platform);
          }}>{avatar ? <img src={avatar} alt={`${label}用户头像`}/> : <span aria-hidden="true"/>}</Button>
        </div>;
      })}</div>
      <Button variant="unstyled" className={view === 'search' ? 'selected' : ''} onClick={() => { resetScroll(); setView('search'); setShowTracks(false); }}>
        <svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><circle cx="10" cy="10" r="6.5" fill="none" stroke="currentColor" strokeWidth="1.8"/><path d="m15 15 6 6" stroke="currentColor" strokeWidth="1.8"/></svg> 发现</Button>
      <Button variant="unstyled" className={view === 'library' ? 'selected' : ''} onClick={() => { resetScroll(); setView('library'); setShowTracks(false); }}>
        <svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M10 18V5l10-2v13" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round"/><circle cx="7" cy="18" r="3" fill="currentColor"/><circle cx="17" cy="16" r="3" fill="currentColor"/></svg> 音乐库</Button>
      <Button variant="unstyled" className={view === 'recent' ? 'selected' : ''} aria-pressed={view === 'recent'} onClick={() => { resetScroll(); setPlaylistQuery(''); setView('recent'); setShowTracks(false); }}>
        <svg className="icon" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l4 2"/></svg> 最近播放</Button>
      <Button variant="unstyled" className={`settings-nav${view === 'settings' ? ' selected' : ''}`} aria-label="设置" onClick={() => { resetScroll(); setView('settings'); setShowTracks(false); }}>
        <svg className="icon" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round"><path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.09a2 2 0 0 1 1 1.73v.5a2 2 0 0 1-1 1.73l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.38a2 2 0 0 0-.73-2.73l-.15-.09a2 2 0 0 1-1-1.73v-.5a2 2 0 0 1 1-1.73l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/></svg> 设置</Button>
    </aside>;
  return <SidebarProvider asChild><section className="library-shell" data-theme={theme}>
    <Sidebar asChild>{sidebar}</Sidebar>
    <SidebarRail/>
    <div ref={content} className={`library-content${scrollbarVisible ? ' scrollbar-visible' : ''}`} onScroll={checkLoadMore}>
      {<header className={view === 'library' ? (showTracks ? 'playlist-header' : 'library-home-header') : ''}><div className="page-heading">
        {view === 'library' && showTracks && <Button variant="unstyled" className="back-button" aria-label="返回歌单" onClick={() => { resetScroll(); setShowTracks(false); }}><svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="m15 5-7 7 7 7" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg></Button>}
        <h1 className={view === 'recent' ? 'recent-title' : view === 'search' ? 'discovery-title' : view === 'settings' ? 'settings-title' : ''}>{view === 'recent' ? '最近播放' : view === 'settings' ? '设置' : view === 'search' ? '发现' : showTracks ? (isLocal && state?.playlistId === 'musxi:local' ? '本地歌曲整合' : state?.playlistName) : '音乐库'}</h1>
      </div><div className="header-actions">
        {view === 'recent' && <Button variant="ghost" className="playlist-play-button" disabled={pending || !state?.recent?.length} onClick={()=>void lib.recentClear()}>清空记录</Button>}
        {view === 'library' && showTracks && <Button variant="ghost" className="playlist-play-button" aria-label="播放歌单" title="播放歌单"
          disabled={busy || !state?.trackCount || (!isLocal && !state?.connected)} onClick={() => state && void lib.playPlaylist(state.playlistId)}>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8.5 5.5v13L19 12z"/></svg><span>播放</span>
        </Button>}
        {(view === 'recent' || view === 'search' || showTracks) && <form className="collection-search" onSubmit={event => { event.preventDefault(); void searchSongs(); }}>
          <Button variant="unstyled" type="submit" disabled={busy || !(view !== 'search' ? playlistQuery : query).trim()} aria-label="搜索"><svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.8" cy="10.8" r="6.6" fill="none" stroke="currentColor" strokeWidth="2"/><path d="m16 16 5 5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/></svg></Button>
          <input value={view !== 'search' ? playlistQuery : query} onChange={event => {
            if (view !== 'search') { resetScroll(); setPlaylistQuery(event.currentTarget.value); }
            else setQuery(event.currentTarget.value);
          }} maxLength={120} placeholder={view !== 'search' ? '搜索此列表中的歌曲、歌手或专辑…' : '搜索歌曲、歌手或专辑…'} aria-label="搜索歌曲、歌手或专辑"/>
        </form>}
      </div></header>}
      {view !== 'recent' && isDemoMode() && <p className="demo-notice">演示模式：播放进度与导入文件夹均为模拟，不播放真实音频、不读取真实文件、不登录真实账号；刷新页面后重置。</p>}
      {view === 'settings' && <nav className="library-sections settings-sections" aria-label="设置分类">
        {([{ id: 'appearance', label: '外观', icon: 'M12 3a9 9 0 1 0 0 18h1a2 2 0 0 0 1.5-3.3l-.2-.2a1.5 1.5 0 0 1 1.1-2.5H18a3 3 0 0 0 3-3 9 9 0 0 0-9-9ZM8 8a.8.8 0 1 0 0-1.6.8.8 0 0 0 0 1.6Zm5-1a.8.8 0 1 0 0-1.6.8.8 0 0 0 0 1.6Zm4 4a.8.8 0 1 0 0-1.6.8.8 0 0 0 0 1.6ZM6 13a.8.8 0 1 0 0-1.6.8.8 0 0 0 0 1.6Z' },
          { id: 'playback', label: '播放', icon: 'M11 4 6 8H3v8h3l5 4V4Zm4 4a6 6 0 0 1 0 8m3-11a10 10 0 0 1 0 14' }] as const).map(section =>
          <Button key={section.id} variant="secondary" className="library-section" aria-pressed={settingsSection === section.id}
            onClick={() => { resetScroll();setSettingsSection(section.id); }}><svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><path d={section.icon}/></svg>{section.label}</Button>)}
      </nav>}
      {view === 'library' && !showTracks && <nav className="library-sections" aria-label="音乐库分类">
        {librarySections.map(section => <Button key={section.id} variant="secondary" className="library-section"
          aria-pressed={librarySection === section.id} onClick={() => {
            resetScroll(); setLibrarySection(section.id);
            setShowTracks(false);
            if(section.id === 'local') void lib.open('musxi:local');
          }}>
          <svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><path d={section.icon}/></svg>{section.label}
        </Button>)}
      </nav>}
      {isLocal && !showTracks && <div className="playlist-grid local-import-grid">
        <Button variant="ghost" className="playlist local-all" disabled={busy} onClick={() => void open('musxi:local')}>
          <CoverImage url=""/><div className="playlist-info"><strong>本地歌曲整合</strong>{state?.playlistId === 'musxi:local' && <span>{state.trackCount} 首</span>}</div>
        </Button>
        {state?.localPlaylists?.map(list => <Button key={list.id} variant="ghost" className="playlist" disabled={busy} onClick={() => void open(list.id)}>
          <CoverImage url={list.cover}/><div className="playlist-info"><strong>{list.name}</strong><span>{list.count} 首{list.platform && ` · ${platformNames[list.platform]}`}</span></div>
        </Button>)}
        <TooltipProvider delayDuration={300}>
        <Tooltip><TooltipTrigger asChild>
          <Button variant="ghost" className="playlist local-import" disabled={busy} onClick={lib.importLocal}>
            <span className="cover placeholder" aria-hidden="true"><svg className="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"><path d="M3 7V5a2 2 0 0 1 2-2h5l2 3h7a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z"/><path d="M12 10v6m-3-3h6"/></svg></span>
            <div className="playlist-info"><strong>{state?.busy ? '正在导入…' : '导入音乐文件夹'}</strong></div>
          </Button>
        </TooltipTrigger><TooltipContent side="bottom"><strong>支持的音乐格式</strong>
          <div>MP3、WAV、FLAC、AAC、M4A / MP4、OGG / OGA、Opus、WMA</div>
          <small>包含子文件夹，重复文件不会重复导入；具体编码须受当前解码器支持。</small>
        </TooltipContent></Tooltip>
      </TooltipProvider></div>}
      {view === 'settings' ? settingsSection === 'playback' ? <PlaybackSettings/> : <AppearanceSettings appearance={appearance ?? { mode: theme === 'dark' || theme === 'glass' ? 'dark' : 'light', transparent: theme === 'glass' || theme === 'glass-light', tone: 'green' }} onAppearance={onAppearance ?? (() => {})} transparency={transparency} onTransparency={onTransparency ?? (() => {})}/> : view === 'library' && !showTracks ? <>
        {!isLocal && <>
          <div className="playlist-grid">{librarySection === 'created' && state?.connected && <Button variant="ghost" className="playlist" disabled={busy || !state?.connected} onClick={() => open('musxi:cloud-favorites')}><div className="cover placeholder" aria-hidden="true"><svg className="playlist-heart size-11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8Z"/></svg></div><div className="playlist-info"><strong>云端收藏整合</strong><span>{state?.favoritesCount ?? (state?.playlistId === 'musxi:cloud-favorites' ? state.trackCount : 0)} 首</span></div></Button>}{visiblePlaylists.map(list => <Button variant="ghost" key={list.id} className="playlist" disabled={busy} onClick={() => open(list.id)}><CoverImage url={list.cover}/><div className="playlist-info"><strong>{list.name}</strong><span>{list.count} 首{list.platform && ` · ${platformNames[list.platform]}`}</span></div></Button>)}</div>
          {!state?.connected ? <div className="library-section-empty"><p>登录后查看你的音乐库。</p><Button onClick={() => {setAccountPlatform(null);setAccount(true);}}>扫码登录</Button></div>
            : librarySection === 'saved' && !visiblePlaylists.length && <p className="library-section-empty">{busy ? '正在加载…' : '暂无收藏歌单。'}</p>}
        </>}
      </> : view === 'search' && !state?.keywords && !busy ? <div className="discover-empty"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10" cy="10" r="6.5"/><path d="m15 15 6 6"/></svg><h2>发现好音乐</h2><p>在右上角搜索歌曲、歌手或专辑</p></div> : <>
        <div className="track-table" role="table" aria-label={view === 'recent' ? '最近播放' : view === 'library' ? state?.playlistName : '搜索结果'}>
          <div className="track-table-head track-row" role="row"><span role="columnheader" aria-label="序号"/><span aria-hidden="true"/><span role="columnheader">歌曲</span><span role="columnheader">歌手</span><span role="columnheader">专辑</span><span role="columnheader">时长</span><span aria-hidden="true"/></div>
          <div ref={songScroll} className={`song-scroll${scrollbarVisible ? ' scrollbar-visible' : ''}`} onScroll={checkLoadMore}>
          <div ref={songList} className="song-list" role="rowgroup" style={{ position: 'relative', height: virtualSongs.getTotalSize() }}>{virtualSongs.getVirtualItems().map(item => {
            const index = item.index; const song = rows[index];
            const songIsLocal=isLocal || (view==='recent' && song.id.startsWith('local:'));
            return <ContextMenu key={song.id} modal={false} onOpenChange={opened => {
              if (opened && !songIsLocal) void menu(song.id);
              else setMenuId(current => current === song.id ? '' : current);
            }}><ContextMenuTrigger asChild><div ref={virtualSongs.measureElement} data-index={index} aria-rowindex={index + 2} className={`song track-row${state?.now?.name === song.name && state?.now?.artist === song.artist ? ' active' : ''}`} role="row"
            style={{ position: 'absolute', top: 0, left: 0, width: '100%', transform: `translateY(${item.start - listOffset}px)` }}
            onDoubleClick={() => lib.play(playSource, song.id)}
            >
            <span className="track-index" role="cell">{index + 1}</span><CoverImage url={song.cover}/><strong className="track-name" role="cell"><span className="track-title">{song.name}</span>{view==='recent' && (songIsLocal || !song.platforms?.length) && <span className="song-platforms"><small className={`song-platform-tag ${song.platform || 'local'}`}>{songIsLocal?'本地':song.platform==='netease'?'网易云':song.platform==='qq'?'QQ':'酷狗'}</small></span>}{(view==='search' || view==='recent' || (view==='library' && state?.playlistId==='musxi:cloud-favorites')) && song.platforms ? <span className="song-platforms">{song.platforms.map(platform=><small key={platform} className={`song-platform-tag ${platform}`} title={`${platformNames[platform]}${view==='search'?'搜索结果':view==='recent'?'歌曲来源':'收藏歌曲'}`}>{platform==='netease'?'网易云':platform==='kugou'?'酷狗':'QQ'}</small>)}</span> : view==='search' && song.platform && <small className="song-platform">{platformNames[song.platform]}</small>}</strong>
            <span className="track-artist" role="cell">{song.artist}</span><span className="track-album" role="cell">{song.album || '—'}</span><span className="track-duration" role="cell">{time(song.duration)}</span>
            <div className="song-actions"><Button variant="unstyled" className="song-play" disabled={pending || (!songIsLocal && !state?.connected)} aria-label={`播放 ${song.name}`} onClick={() => lib.play(playSource, song.id)}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 4v16l13-8z"/></svg></Button><Button variant="unstyled" className="song-menu" disabled={busy} aria-label="歌曲操作" onClick={() => {if(!songIsLocal)void menu(song.id);}}>•••</Button></div>
          </div></ContextMenuTrigger><ContextMenuContent aria-label="歌曲操作">
            <ContextMenuItem disabled={pending || (!songIsLocal && !state?.connected)} onSelect={() => void lib.queueNext(playSource, song.id)}>下一首播放</ContextMenuItem>
            {songIsLocal ? <ContextMenuItem onSelect={() => void lib.play(playSource, song.id)}>播放</ContextMenuItem> : menuId === song.id && state?.menu.id === song.id ? <>
              {state.menu.providers ? <>
                {[false,true].map(liked => {
                  const providers=state.menu.providers!.filter(provider=>provider.liked===liked);
                  return !!providers.length && <ContextMenuSub key={String(liked)}><ContextMenuSubTrigger disabled={busy}>{liked?'取消收藏':'收藏到我喜欢'}</ContextMenuSubTrigger>
                    <ContextMenuSubContent aria-label={liked?'选择取消收藏的平台':'选择收藏平台'}>{providers.map(provider=>
                      <ContextMenuItem key={provider.platform} disabled={busy || !provider.canFavorite} onSelect={()=>void lib.favorite(song.id,!liked,provider.platform)}>{platformNames[provider.platform]}</ContextMenuItem>
                    )}</ContextMenuSubContent>
                  </ContextMenuSub>;
                })}
                <ContextMenuSub><ContextMenuSubTrigger disabled={busy || !state.menu.providers.some(provider=>provider.playlists.length)}>添加到歌单</ContextMenuSubTrigger>
                  <ContextMenuSubContent aria-label="选择歌单平台">{state.menu.providers.filter(provider=>provider.playlists.length).map(provider=>
                    <ContextMenuSub key={provider.platform}><ContextMenuSubTrigger disabled={busy}>{platformNames[provider.platform]}</ContextMenuSubTrigger>
                      <ContextMenuSubContent aria-label={`${platformNames[provider.platform]}歌单`}>{provider.playlists.map(list=>
                        <ContextMenuItem key={list.id} disabled={busy} onSelect={()=>void lib.add(song.id,list.id,provider.platform)}>{list.name}</ContextMenuItem>
                      )}</ContextMenuSubContent>
                    </ContextMenuSub>
                  )}</ContextMenuSubContent>
                </ContextMenuSub>
              </> : <><ContextMenuItem disabled={busy || !state.menu.canFavorite} onSelect={() => void lib.favorite(song.id, !state.menu.liked)}>
                {state.menu.liked ? '取消收藏' : '收藏到我喜欢'}
              </ContextMenuItem>
              <ContextMenuSub><ContextMenuSubTrigger disabled={busy || !state.menu.playlists.length}>添加到歌单</ContextMenuSubTrigger>
                <ContextMenuSubContent aria-label="选择歌单">{state.menu.playlists.map(list =>
                  <ContextMenuItem key={list.id} disabled={busy} onSelect={() => void lib.add(song.id, list.id)}>{list.name}</ContextMenuItem>
                )}</ContextMenuSubContent>
              </ContextMenuSub>
              </>}
            </> : <ContextMenuItem disabled>{error || '正在读取歌曲信息…'}</ContextMenuItem>}
            {view==='recent' && <ContextMenuItem onSelect={()=>void lib.recentRemove(song.id)}>移除记录</ContextMenuItem>}
          </ContextMenuContent></ContextMenu>;
          })}</div>
          {!rows.length && <p className="empty-tracks">{busy ? '正在加载…' : (view === 'library' || view === 'recent') && playlistFilter ? (view === 'library' && state && page * 50 < state.trackCount && !error ? '正在搜索歌单…' : '未找到匹配的歌曲') : '暂无歌曲'}</p>}
          </div>
        </div>
      </>}
    </div>
    {account && <AccountDialog key={accountPlatform || 'chooser'} open={account} onOpenChange={setAccount} initialPlatform={accountPlatform} library={lib}/>}
  </section></SidebarProvider>;
}
