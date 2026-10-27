import { AccountDialog, PlatformIcon } from '../components/AccountDialog';
import { platformNames, type MusicPlatform } from '../native/library';
import { isDemoMode } from '../demo/context.ts';
import { Fragment, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { animate } from 'motion/mini';
import { useVirtualizer } from '@tanstack/react-virtual';
import type { useLibrary } from '../composables/useLibrary';
import { isCurrentSong } from '../composables/songPlayback';
import { readPlaylistLoadingCount, rememberPlaylistLoadingCounts, readTrackLoadingCount, rememberTrackLoadingCount } from '../composables/playlistLoadingCounts';
import type { PlayerState } from '../native/types';
import type { Theme } from '../native/window';
import { safeImage, type Track } from '../native/library';
import CoverImage from '../components/CoverImage';
import { Button } from '../components/ui/button';
import PlayerControl from '../components/PlayerControl';
import { Dialog, DialogTrigger, DialogContent, DialogTitle, DialogDescription } from '../components/ui/dialog';
import { SidebarProvider, Sidebar, SidebarRail } from '../components/ui/sidebar';
import { Tooltip, TooltipTrigger, TooltipProvider, TooltipContent } from '../components/ui/tooltip';
import { ContextMenu, ContextMenuTrigger, ContextMenuContent, ContextMenuItem,
  ContextMenuSub, ContextMenuSubTrigger, ContextMenuSubContent } from '../components/ui/context-menu';

import AppearanceSettings from '../components/AppearanceSettings';
import PlaybackSettings from '../components/PlaybackSettings';
import ShortcutSettings from '../components/ShortcutSettings';
import LyricsSettings from '../components/LyricsSettings';
import type { ShortcutsController } from '../composables/useShortcuts';
import type { WallpaperController } from '../composables/useWallpaper';
import UpdateSettings from '../components/UpdateSettings';
import type { AppUpdates } from '../composables/useAppUpdates';
import type { Appearance } from '../composables/appearance';
type LibrarySection = 'created' | 'saved' | 'local';
const librarySections: { id: LibrarySection; label: string; icon: string }[] = [
  { id: 'created', label: '自建歌单', icon: 'M12 8v8M8 12h8M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Z' },
  { id: 'saved', label: '收藏歌单', icon: 'm12 3 2.8 5.7 6.3.9-4.6 4.5 1.1 6.3-5.6-3-5.6 3 1.1-6.3L3 9.6l6.3-.9L12 3Z' },
  { id: 'local', label: '本地音乐', icon: 'M3 7V5a2 2 0 0 1 2-2h5l2 3h7a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z' },
];
const trashIcon='M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7';
function appendUnique(previous: Track[], incoming?: Track[]) {
  if (!incoming?.length) return previous;
  const indices = new Map(previous.map((track,index) => [track.id,index])); const rows = [...previous];
  for (const track of incoming) { const index=indices.get(track.id); if(index!==undefined)rows[index]=track;else {indices.set(track.id,rows.length);rows.push(track);} }
  return rows;
}
export default function LibraryView({ library: lib, theme, appearance, onAppearance, transparency = 50, onTransparency, updates, showUpdates = 0, shortcuts,
  wallpaper,wallpaperEnabled,onWallpaperEnabled,playerTransparent,onPlayerTransparent,playback,onResume }: {
  library: ReturnType<typeof useLibrary>; theme: Theme; appearance?: Appearance; onAppearance?: (next: Appearance) => void;
  transparency?: number; onTransparency?: (value: number) => void;
  updates?: AppUpdates; showUpdates?: number;
  shortcuts: ShortcutsController;
  playback?: PlayerState | null; onResume?: () => void;
  wallpaper?:WallpaperController;wallpaperEnabled?:boolean;onWallpaperEnabled?:(value:boolean)=>void;
  playerTransparent?:boolean;onPlayerTransparent?:(value:boolean)=>void;
}) {
  const { state, error, pending, page } = lib;
  const playlistRows = state?.tracks || [];
  const [searchRows, setSearchRows] = useState<Track[]>([]);
  const [view, setView] = useState<'library' | 'search' | 'settings' | 'recent'>('library');
  const [query, setQuery] = useState(''); const [showTracks, setShowTracks] = useState(false);
  const [requestedPlaylist, setRequestedPlaylist] = useState('');
  const [requestedSearch, setRequestedSearch] = useState('');
  const searchOperationBefore = useRef('');
  const [skeletonPageRows, setSkeletonPageRows] = useState(8);
  const [librarySection, setLibrarySection] = useState<LibrarySection>('created');
  const [settingsSection, setSettingsSection] = useState<'appearance' | 'playback' | 'shortcuts' | 'lyrics' | 'updates'>('appearance');
  const [removeFoldersOpen, setRemoveFoldersOpen] = useState(false);
  useEffect(() => {
    // The dock notice asks for the update details page.
    if (showUpdates) navigate(() => { setView('settings'); setSettingsSection('updates'); setShowTracks(false); });
  }, [showUpdates]);
  const [playlistQuery, setPlaylistQuery] = useState('');
  const content = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const blurSearch = (event: PointerEvent) => {
      const search = content.current?.querySelector('.collection-search');
      const focused = document.activeElement;
      if (search && focused instanceof HTMLElement && search.contains(focused)
        && event.target instanceof Node && !search.contains(event.target)) focused.blur();
    };
    document.addEventListener('pointerdown', blurSearch, true);
    return () => document.removeEventListener('pointerdown', blurSearch, true);
  }, []);
  const pageScroll = useRef<HTMLDivElement>(null);
  const songScroll = useRef<HTMLDivElement>(null);
  const songList = useRef<HTMLDivElement>(null);
  const [listOffset, setListOffset] = useState(0);
  const previousPlaylist = useRef<string | undefined>(undefined); const previousKeywords = useRef<string | undefined>(undefined);
  const loadingMore = useRef(false); const [scrollbarVisible, setScrollbarVisible] = useState(false);
  const [account, setAccount] = useState(false); const [menuId, setMenuId] = useState('');
  const [accountPlatform, setAccountPlatform] = useState<MusicPlatform | null>(null);
  const isLocal = view === 'library' && librarySection === 'local';
  const busy = pending || !!state?.busy;
  const signedOut = !!state && !state.connected && !busy && !error && (['kugou','netease','qq'] as const).every(platform => {
    const account = state.accounts?.find(account => account.platform === platform);
    return account?.connected === false && !account.error;
  });
  const visiblePlaylists = state?.playlists.filter(list => librarySection === 'created' ? list.editable
    : librarySection === 'saved' ? !list.editable : false).sort((a,b)=>librarySection==='created' ? Number(!!b.favorite)-Number(!!a.favorite) : 0) ?? [];
  const playlistFilter = playlistQuery.trim().toLocaleLowerCase();
  const filteredPlaylistRows = useMemo(() => playlistFilter
    ? playlistRows.filter(song => [song.name, song.artist, song.album || '']
      .some(value => value.toLocaleLowerCase().includes(playlistFilter)))
    : playlistRows, [playlistRows, playlistFilter]);
  const searchSettled = state?.operation.kind === 'search' && state.operation.id !== searchOperationBefore.current
    && ['completed','failed','cancelled'].includes(state.operation.status);
  const searchPending = (!!requestedSearch && !searchSettled) || (state?.operation.kind === 'search' && state.operation.status === 'pending');
  const rows = view === 'recent' ? (state?.recent || []).filter(song=>!playlistFilter || [song.name,song.artist,song.album || ''].some(value=>value.toLocaleLowerCase().includes(playlistFilter))) : view === 'search' ? (requestedSearch && searchPending && state?.keywords !== requestedSearch ? [] : searchRows) :
    requestedPlaylist && busy && !error && state?.playlistId !== requestedPlaylist ? [] : isLocal && state?.playlistId !== 'musxi:local' && !state?.playlistId.startsWith('local-folder:') ? [] : filteredPlaylistRows;
  const songsLoading = view !== 'recent' && !error && (!state || (view === 'library' && busy) || (view === 'search' && searchPending));
  const trackSkeletonCount = view === 'library' ? Math.min(skeletonPageRows, readTrackLoadingCount(localStorage, state, requestedPlaylist || state?.playlistId || '')) : skeletonPageRows;
  useEffect(() => {
    if (state) rememberTrackLoadingCount(localStorage, state, pending, error);
    if (!busy) setRequestedPlaylist('');
  }, [state, pending, error, busy]);
  useEffect(() => { if (searchSettled || error) setRequestedSearch(''); }, [searchSettled, error]);
  const playlistSyncing = useRef(true);
  const [, setPlaylistReady] = useState(0);
  const starting = !!state && !state.connected && !state.accounts?.length &&
    (state.status === '登录后，把收藏带到这里。' || state.status === '正在连接本机接口…');
  if (error || (state && !state.connected && !busy && !starting)) playlistSyncing.current = false;
  else if (starting && !error) playlistSyncing.current = true;
  // shortcut: automatic sync is identified by status text, use a structured operation when the bridge exposes it.
  else if (state?.connected && busy && ((state.operation.kind === 'sync' && state.operation.status === 'pending') ||
    state.status.includes('正在同步收藏歌单') || state.status.includes('正在检查概念版 VIP 权益') ||
    (state.operation.status === 'idle' && !state.playlists.length))) playlistSyncing.current = true;
  useEffect(() => {
    if (!state?.connected || busy || error || !playlistSyncing.current) return;
    // shortcut: allow startup actions to settle over one polling interval; use an explicit readiness signal if the bridge adds one.
    const timer = setTimeout(() => { playlistSyncing.current = false; setPlaylistReady(value => value + 1); }, 800);
    return () => clearTimeout(timer);
  }, [state?.connected, busy, error]);
  const playlistsLoading = !error && (!state || playlistSyncing.current);
  const skeletonCount = playlistsLoading ? (visiblePlaylists.length ? visiblePlaylists.length + Number(librarySection === 'created')
    : readPlaylistLoadingCount(localStorage, starting ? null : state, librarySection)) : 6;
  useEffect(() => { if (state) rememberPlaylistLoadingCounts(localStorage, state, pending, error); }, [state, pending, error]);
  const tracksVisible = view === 'recent' || view === 'search' || (view === 'library' && showTracks);
  const fixedPageHeader = view === 'settings' || (view === 'library' && !showTracks);
  const PageBody = fixedPageHeader ? 'div' : Fragment;
  const navigationKey = JSON.stringify([view, view === 'settings' ? settingsSection :
    view === 'library' ? [librarySection, showTracks, showTracks ? state?.playlistId : ''] : '']);
  const [navigationRevision, setNavigationRevision] = useState(0);
  const previousNavigation = useRef(navigationKey);
  const transition = useRef<ReturnType<typeof animate> | null>(null);
  const navigationRequest = useRef(0);
  function pageTargets() {
    return content.current ? Array.from(content.current.children).filter(element => !element.matches('header,nav')) : [];
  }
  function navigate(update: () => void) {
    const request = ++navigationRequest.current;
    transition.current?.cancel();
    const commit = () => {
      if (request !== navigationRequest.current) return;
      update();
      setNavigationRevision(value => value + 1);
    };
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) { commit(); return; }
    transition.current = animate(pageTargets(), { opacity: [1, 0] }, { duration: 0.09, ease: 'easeOut' });
    void transition.current.then(commit);
  }
  useEffect(() => () => { ++navigationRequest.current; transition.current?.cancel(); }, []);
  useLayoutEffect(() => {
    if (previousNavigation.current === navigationKey && !navigationRevision) return;
    previousNavigation.current = navigationKey;
    transition.current?.cancel();
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    transition.current = animate(pageTargets(), { opacity: [0, 1] }, { duration: 0.09, ease: 'easeOut' });
    return () => transition.current?.cancel();
  }, [navigationKey, navigationRevision]);
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
    if (element && list) {
      const offset = list.getBoundingClientRect().top - element.getBoundingClientRect().top + element.scrollTop
        + parseFloat(getComputedStyle(list).paddingTop);
      setListOffset(offset);
      setSkeletonPageRows(Math.max(1, Math.floor((element.clientHeight - offset) / (window.innerWidth <= 1100 ? 70 : 61))));
    }
  }
  useLayoutEffect(measureListOffset);
  useLayoutEffect(() => {
    const element = content.current;
    if (!element) return;
    let compactRows = window.innerWidth <= 1100;
    const observer = new ResizeObserver(() => {
      const nextCompactRows = window.innerWidth <= 1100;
      // Width-only changes do not change row height; retain measured scroll positions.
      if (compactRows !== nextCompactRows) {
        compactRows = nextCompactRows;
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
  }, [state?.playlistId]);
  useLayoutEffect(() => {
    const changed = state?.keywords !== previousKeywords.current;
    previousKeywords.current = state?.keywords;
    setSearchRows(previous => appendUnique(changed ? [] : previous, state?.search));
  }, [state?.keywords, state?.search]);
  function resetScroll() { if (content.current) content.current.scrollTop = 0; if (pageScroll.current) pageScroll.current.scrollTop = 0; if (songScroll.current) songScroll.current.scrollTop = 0; setScrollbarVisible(false); }
  useEffect(() => {
    function updateScrollbarVisibility(event: PointerEvent) {
      const element = songScroll.current || pageScroll.current;
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
  async function open(id: string) {
    const request = ++navigationRequest.current;
    transition.current?.cancel();
    resetScroll(); setPlaylistQuery(''); setRequestedPlaylist(id); await lib.open(id);
    if (request === navigationRequest.current) navigate(() => setShowTracks(true));
  }
  async function searchSongs() {
    resetScroll();
    if (view === 'recent' || (view === 'library' && showTracks)) return;
    searchOperationBefore.current = state?.operation.id || '';
    setRequestedSearch(query.trim());
    navigate(() => { setSearchRows([]); setView('search'); setShowTracks(true); }); await lib.search(query);
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
      <Button variant="unstyled" className={view === 'search' ? 'selected' : ''} onClick={() => navigate(() => { resetScroll(); setView('search'); setShowTracks(false); })}>
        <svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><circle cx="10" cy="10" r="6.5" fill="none" stroke="currentColor" strokeWidth="1.8"/><path d="m15 15 6 6" stroke="currentColor" strokeWidth="1.8"/></svg> 发现</Button>
      <Button variant="unstyled" className={view === 'library' ? 'selected' : ''} onClick={() => navigate(() => { resetScroll(); setView('library'); setShowTracks(false); })}>
        <svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M10 18V5l10-2v13" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round"/><circle cx="7" cy="18" r="3" fill="currentColor"/><circle cx="17" cy="16" r="3" fill="currentColor"/></svg> 音乐库</Button>
      <Button variant="unstyled" className={view === 'recent' ? 'selected' : ''} aria-pressed={view === 'recent'} onClick={() => navigate(() => { resetScroll(); setPlaylistQuery(''); setView('recent'); setShowTracks(false); })}>
        <svg className="icon" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l4 2"/></svg> 最近播放</Button>
      <Button variant="unstyled" className={`settings-nav${view === 'settings' ? ' selected' : ''}`} aria-label="设置" onClick={() => navigate(() => { resetScroll(); setView('settings'); setShowTracks(false); })}>
        <svg className="icon" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round"><path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.09a2 2 0 0 1 1 1.73v.5a2 2 0 0 1-1 1.73l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.38a2 2 0 0 0-.73-2.73l-.15-.09a2 2 0 0 1-1-1.73v-.5a2 2 0 0 1 1-1.73l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/></svg> 设置</Button>
    </aside>;
  return <SidebarProvider asChild><section className="library-shell" data-theme={theme}>
    <Sidebar asChild>{sidebar}</Sidebar>
    <SidebarRail/>
    <div ref={content} className={`library-content${fixedPageHeader ? ' fixed-page-header' : ''}${scrollbarVisible ? ' scrollbar-visible' : ''}`} onScroll={checkLoadMore}>
      {<header className={view === 'library' ? (showTracks ? 'playlist-header' : 'library-home-header') : ''}><div className="page-heading">
        {view === 'library' && showTracks && <Button variant="unstyled" className="back-button" aria-label="返回歌单" onClick={() => navigate(() => { resetScroll(); setShowTracks(false); })}><svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="m15 5-7 7 7 7" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg></Button>}
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
          { id: 'playback', label: '播放', icon: 'M11 4 6 8H3v8h3l5 4V4Zm4 4a6 6 0 0 1 0 8m3-11a10 10 0 0 1 0 14' },
          { id: 'shortcuts', label: '快捷键', icon: 'M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2ZM6 8h.01M10 8h.01M14 8h.01M18 8h.01M6 12h.01M10 12h.01M14 12h.01M18 12h.01M7 16h10' },
          { id: 'lyrics', label: '歌词', icon: 'M6 3h12a3 3 0 0 1 3 3v12a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3ZM7 12h2m4 0h4M7 16h6m3 0h1' },
          ...(updates && !updates.unsupported ? [{ id: 'updates', label: '关于与更新', icon: 'M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20ZM12 11v6m0-10h.01' }] as const : [])] as const).map(section =>
          <Button key={section.id} variant="secondary" className="library-section" aria-pressed={settingsSection === section.id}
            onClick={() => navigate(() => { resetScroll();setSettingsSection(section.id); })}><svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><path d={section.icon}/></svg>{section.label}</Button>)}
      </nav>}
      {view === 'library' && !showTracks && <nav className="library-sections" aria-label="音乐库分类">
        {librarySections.map(section => <Button key={section.id} variant="secondary" className="library-section"
          aria-pressed={librarySection === section.id} onClick={() => navigate(() => {
            resetScroll(); setLibrarySection(section.id);
            setShowTracks(false);
            if(section.id === 'local') void lib.open('musxi:local');
          })}>
          <svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><path d={section.icon}/></svg>{section.label}
        </Button>)}
      </nav>}
      <PageBody {...(fixedPageHeader ? { ref: pageScroll, className: `page-scroll${scrollbarVisible ? ' scrollbar-visible' : ''}` } : {})}>
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
      </TooltipProvider>
        <Dialog open={removeFoldersOpen} onOpenChange={setRemoveFoldersOpen}>
          <DialogTrigger asChild><Button variant="ghost" className="playlist local-import local-remove" disabled={busy}>
            <span className="cover placeholder" aria-hidden="true"><svg className="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"><path d={trashIcon}/></svg></span>
            <div className="playlist-info"><strong>移除音乐文件夹</strong></div>
          </Button></DialogTrigger>
          <DialogContent className="local-folder-dialog">
            <DialogTitle>移除音乐文件夹</DialogTitle>
            <DialogDescription>点击文件夹名称，从音乐库移除。电脑上的音乐文件会保留。</DialogDescription>
            <div className="local-folder-options">
              {state?.localPlaylists?.length ? state.localPlaylists.map(list=><Button key={list.id} variant="secondary" disabled={busy}
                onClick={()=>void lib.removeLocalFolder(list.id)}>
                <span>{list.name}</span>
              </Button>) : <p>暂无已导入的音乐文件夹。</p>}
            </div>
            <Button variant="secondary" onClick={()=>setRemoveFoldersOpen(false)}>关闭</Button>
          </DialogContent>
        </Dialog>
      </div>}
      {view === 'settings' ? settingsSection === 'updates' && updates ? <UpdateSettings updates={updates}/> : settingsSection === 'shortcuts' ? <ShortcutSettings shortcuts={shortcuts}/> : settingsSection === 'lyrics' ? <LyricsSettings coverUrl={state?.now?.cover}/> : settingsSection === 'playback' ? <PlaybackSettings/> : <AppearanceSettings appearance={appearance ?? { mode: theme === 'dark' || theme === 'glass' ? 'dark' : 'light', transparent: theme === 'glass' || theme === 'glass-light', tone: 'green' }} onAppearance={onAppearance ?? (() => {})} transparency={transparency} onTransparency={onTransparency ?? (() => {})}
        wallpaper={wallpaper} wallpaperEnabled={wallpaperEnabled} onWallpaperEnabled={onWallpaperEnabled} playerTransparent={playerTransparent} onPlayerTransparent={onPlayerTransparent}/> : view === 'library' && !showTracks ? <>
        {!isLocal && <>
          {playlistsLoading ? <div className="playlist-grid" role="status" aria-label="正在加载歌单列表">
            {Array.from({ length: skeletonCount }, (_, index) => <div key={index} className="playlist playlist-skeleton" aria-hidden="true">
              <i className="cover"/><div className="playlist-info"><i/><i/></div>
            </div>)}
          </div> : <><div className="playlist-grid">{librarySection === 'created' && state?.connected && <Button variant="ghost" className="playlist" disabled={busy || !state?.connected} onClick={() => open('musxi:cloud-favorites')}><div className="cover placeholder" aria-hidden="true"><svg className="playlist-heart size-11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8Z"/></svg></div><div className="playlist-info"><strong>云端收藏整合</strong><span>{state?.favoritesCount ?? (state?.playlistId === 'musxi:cloud-favorites' ? state.trackCount : 0)} 首</span></div></Button>}{visiblePlaylists.map(list => <Button variant="ghost" key={list.id} className="playlist" disabled={busy} onClick={() => open(list.id)}><CoverImage url={list.cover} animateLoading/><div className="playlist-info"><strong>{list.name}</strong><span>{list.count} 首{list.platform && ` · ${platformNames[list.platform]}`}</span></div></Button>)}</div>
          {signedOut ? <div className="library-section-empty"><p>登录后查看你的音乐库。</p><Button onClick={() => {setAccountPlatform(null);setAccount(true);}}>扫码登录</Button></div>
            : state?.connected && librarySection === 'saved' && !visiblePlaylists.length && <p className="library-section-empty">暂无收藏歌单。</p>}
          </>}
        </>}
      </> : view === 'search' && !state?.keywords && !showTracks ? <div className="discover-empty"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10" cy="10" r="6.5"/><path d="m15 15 6 6"/></svg><h2>发现好音乐</h2><p>在右上角搜索歌曲、歌手或专辑</p></div> : <>
        <div className="track-table" role="table" aria-label={view === 'recent' ? '最近播放' : view === 'library' ? state?.playlistName : '搜索结果'}>
          <div className="track-table-head track-row" role="row"><span role="columnheader" aria-label="序号"/><span aria-hidden="true"/><span role="columnheader">歌曲</span><span role="columnheader">歌手</span><span role="columnheader">专辑</span><span role="columnheader">时长</span><span aria-hidden="true"/></div>
          <div ref={songScroll} className={`song-scroll${scrollbarVisible ? ' scrollbar-visible' : ''}`} onScroll={checkLoadMore}>
          <div ref={songList} className="song-list" role="rowgroup" style={{ position: 'relative', height: virtualSongs.getTotalSize() }}>{virtualSongs.getVirtualItems().map(item => {
            const index = item.index; const song = rows[index];
            const songIsLocal=isLocal || (view==='recent' && song.id.startsWith('local:'));
            const current = isCurrentSong(song, playback, state?.now);
            const playing = current && !!playback?.playing;
            const cannotPlay = pending || (!songIsLocal && !state?.connected);
            const playSong = () => {
              if (cannotPlay) return;
              if (current) { if (!playback?.playing && !playback?.pending) onResume?.(); }
              else void lib.play(playSource, song.id);
            };
            return <ContextMenu key={song.id} modal={false} onOpenChange={opened => {
              if (opened && !songIsLocal) void menu(song.id);
              else setMenuId(current => current === song.id ? '' : current);
            }}><ContextMenuTrigger asChild><div ref={virtualSongs.measureElement} data-index={index} aria-rowindex={index + 2} className={`song track-row${current ? ' active' : ''}`} role="row"
            style={{ position: 'absolute', top: 0, left: 0, width: '100%', transform: `translateY(${item.start - listOffset}px)` }}
            tabIndex={0} onKeyDown={event => {
              if (event.target === event.currentTarget && event.key === 'Enter') { event.preventDefault(); playSong(); }
            }}
            onClick={event => {
              if (event.defaultPrevented || event.button !== 0 || event.detail > 1
                || (event.target as Element).closest('button,a,input,select,[role=menuitem]')) return;
              playSong();
            }}
            >
            <span className="track-index" role="cell" data-current={current || undefined} data-playing={playing || undefined}>
              <span className="track-number">{index + 1}</span>
              <PlayerControl variant="unstyled" className="track-index-play" disabled={cannotPlay} aria-label={`播放 ${song.name}`} onClick={event => { if (event.detail <= 1) playSong(); }}>
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m8 5 11 7-11 7z"/></svg>
              </PlayerControl>
              {current && <span className="track-wave" role="img" aria-label={playing ? '正在播放' : '已暂停'}><i/><i/><i/><i/></span>}
            </span><CoverImage url={song.cover} animateLoading/><strong className="track-name" role="cell"><span className="track-title">{song.name}</span>{view==='recent' && (songIsLocal || !song.platforms?.length) && <span className="song-platforms"><small className={`song-platform-tag ${song.platform || 'local'}`}>{songIsLocal?'本地':song.platform==='netease'?'网易云':song.platform==='qq'?'QQ':'酷狗'}</small></span>}{(view==='search' || view==='recent' || (view==='library' && state?.playlistId==='musxi:cloud-favorites')) && song.platforms ? <span className="song-platforms">{song.platforms.map(platform=><small key={platform} className={`song-platform-tag ${platform}`} title={`${platformNames[platform]}${view==='search'?'搜索结果':view==='recent'?'歌曲来源':'收藏歌曲'}`}>{platform==='netease'?'网易云':platform==='kugou'?'酷狗':'QQ'}</small>)}</span> : view==='search' && song.platform && <small className="song-platform">{platformNames[song.platform]}</small>}</strong>
            <span className="track-artist" role="cell">{song.artist}</span><span className="track-album" role="cell">{song.album || '—'}</span><span className="track-duration" role="cell">{time(song.duration)}</span>
            <div className="song-actions"><PlayerControl variant="unstyled" className="song-play" disabled={pending || (!songIsLocal && !state?.connected)} aria-label={`播放 ${song.name}`} onClick={playSong}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 4v16l13-8z"/></svg></PlayerControl><Button variant="unstyled" className="song-menu" disabled={busy} aria-label="歌曲操作" onClick={() => {if(!songIsLocal)void menu(song.id);}}>•••</Button></div>
          </div></ContextMenuTrigger><ContextMenuContent aria-label="歌曲操作">
            <ContextMenuItem disabled={pending || (!songIsLocal && !state?.connected)} onSelect={() => void lib.queueNext(playSource, song.id)}>下一首播放</ContextMenuItem>
            {songIsLocal ? isLocal ? <ContextMenuItem disabled={busy} onSelect={() => void lib.removeLocalTrack(song.id,state!.playlistId)}>移除</ContextMenuItem>
              : <ContextMenuItem onSelect={() => void lib.play(playSource, song.id)}>播放</ContextMenuItem> : menuId === song.id && state?.menu.id === song.id ? <>
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
          {!rows.length && (songsLoading ? <div className="track-loading" role="status" aria-label="正在加载歌曲列表">
            {Array.from({ length: trackSkeletonCount }, (_, index) => <div key={index} className="song track-row track-skeleton" aria-hidden="true">
              <i className="track-index"/><i className="cover"/><i className="track-name"/><i className="track-artist"/>
              <i className="track-album"/><i className="track-duration"/><span className="song-actions"/>
            </div>)}
          </div> : <p className="empty-tracks">{(view === 'library' || view === 'recent') && playlistFilter ? (view === 'library' && state && page * 50 < state.trackCount && !error ? '正在搜索歌单…' : '未找到匹配的歌曲') : '暂无歌曲'}</p>)}
          </div>
        </div>
      </>}
      </PageBody>
    </div>
    {account && <AccountDialog key={accountPlatform || 'chooser'} open={account} onOpenChange={setAccount} initialPlatform={accountPlatform} library={lib}/>}
  </section></SidebarProvider>;
}
