import { native } from './client';
import type { CefTransport } from './transport';
import { request } from './transport';
import type { PlayerEvent } from './events';
import { createElement } from 'react';
import { createRoot } from 'react-dom/client';
import LibraryView from '../views/LibraryView';
import MessageAlerts from '../components/MessageAlerts';
import QueuePanel from '../components/QueuePanel';
import PlayerActions from '../components/PlayerActions';
import { AccountDialog } from '../components/AccountDialog';
import type { useLibrary } from '../composables/useLibrary';
import { verifyDesktopWindow } from './desktopSmoke';
import { observeLyricDragRegions } from '../composables/lyrics';
import { verifyUpdatesUI } from './updatesSmoke';

async function waitFor(check: () => boolean, message = 'Event/UI update timed out') {
  const end = Date.now() + 3000;
  while (!check()) {
    if (Date.now() > end) throw new Error(message);
    await new Promise(resolve => setTimeout(resolve, 20));
  }
}

function checkMenuDragRegions(open: boolean) {
  for (const region of Array.from(document.querySelectorAll('.window-bar,.library-content>header,.sidebar'))) {
    if (getComputedStyle(region).getPropertyValue('-webkit-app-region').trim() !== (open ? 'no-drag' : 'drag'))
      throw new Error(`Menu ${open ? 'blocks outside clicks' : 'did not restore window dragging'}`);
  }
}

// Invoked only by the real CEF --cef-smoke run (cloud startup is disabled).
export async function verifyWebPage() {
  const updateScenario = new URLSearchParams(location.search).get('updatesSmoke');
  if (updateScenario) { await verifyUpdatesUI(updateScenario); return; }
  if (new URLSearchParams(location.search).has('desktopSmoke')) { await verifyDesktopWindow(); return; }
  const library = await native.library.getState();
  if (!Array.isArray(library.playlists) || library.connected) throw new Error('Unexpected offline library state');
  await waitFor(() => !!document.querySelector('.library-shell'));
  if(Array.from(document.querySelectorAll('button')).some(button=>button.dataset.slot !== 'button' && button.dataset.slot !== 'sidebar-rail'))
    throw new Error('An application button did not use shadcn Button');
  if(document.querySelector('.page-heading button[data-sidebar],.navigation-toggle'))throw new Error('Header sidebar toggle was not removed');
  const desktopToggle=document.querySelector<HTMLButtonElement>('[aria-label="收起侧栏"]')!;
  const sidebarBounds=document.querySelector('.sidebar')!.getBoundingClientRect();
  const railBounds=desktopToggle.getBoundingClientRect();
  if(railBounds.left>sidebarBounds.right || railBounds.right<sidebarBounds.right || railBounds.height<sidebarBounds.height-1
    || Math.abs(railBounds.top-sidebarBounds.top)>1)
    throw new Error('Sidebar rail is not along the sidebar edge');
  if(document.elementFromPoint(railBounds.left+railBounds.width/2,railBounds.top+8)!==desktopToggle)
    throw new Error('Title bar blocks the top of the sidebar rail');
  const originalContentLeft=document.querySelector('.library-content')!.getBoundingClientRect().left;
  desktopToggle.click();
  await waitFor(() => !document.querySelector('.sidebar') && !!document.querySelector('[aria-label="展开侧栏"]'), 'Desktop sidebar did not fully collapse');
  if(document.querySelector('.library-content')!.getBoundingClientRect().left >= originalContentLeft)
    throw new Error('Collapsed sidebar still occupies page space');
  if(Math.abs(desktopToggle.getBoundingClientRect().left-document.querySelector('.library-shell')!.getBoundingClientRect().left)>1)
    throw new Error('Collapsed rail is not at the window left edge');
  const collapsedRailBounds=desktopToggle.getBoundingClientRect();
  if(collapsedRailBounds.width<24 || document.elementFromPoint(collapsedRailBounds.left+20,collapsedRailBounds.top+80)!==desktopToggle)
    throw new Error('Collapsed sidebar expansion hit area is too narrow or blocked');
  document.querySelector<HTMLButtonElement>('[aria-label="展开侧栏"]')!.click();
  await waitFor(() => !!document.querySelector('.sidebar'), 'Desktop sidebar did not expand');
  for (const target of Array.from(document.querySelectorAll('.sidebar button,.library-sections button svg,.play-controls button'))) {
    const event = new MouseEvent('contextmenu', { bubbles: true, cancelable: true, button: 2 });
    target.dispatchEvent(event);
    if (!event.defaultPrevented) throw new Error('A button still opens the browser context menu');
  }
  const sections = document.querySelectorAll<HTMLButtonElement>('.library-sections button');
  if (sections.length !== 3 || document.querySelector('.welcome')
    || Array.from(sections).some(button => button.dataset.slot !== 'button' && button.dataset.slot !== 'sidebar-rail')
    || sections[0].textContent !== '自建歌单' || sections[1].textContent !== '收藏歌单' || sections[2].textContent !== '本地音乐'
    || sections[0].getAttribute('aria-pressed') !== 'true')
    throw new Error('Music library navigation was not rendered with shadcn buttons');
  const sectionTop=sections[0].getBoundingClientRect().top;
  sections[2].click();
  await waitFor(() => sections[2].getAttribute('aria-pressed') === 'true'
    && !!document.querySelector('.local-import[data-slot=button]'), 'Local import button did not render');
  if(Math.abs(sections[0].getBoundingClientRect().top-sectionTop)>1 || document.querySelector('.track-table') || document.querySelector('.collection-search'))
    throw new Error('Local music home moved navigation or showed a song table/search');
  sections[1].click();
  await waitFor(() => sections[1].getAttribute('aria-pressed') === 'true');
  sections[0].click();
  await waitFor(() => sections[0].getAttribute('aria-pressed') === 'true');
  // Isolated UI fixture: exercise the actual virtual row/menu without account writes.
  const fixture = document.createElement('div');
  fixture.style.cssText = 'position:fixed;inset:40px;display:flex;z-index:60;background:var(--surface)';
  document.body.append(fixture);
  const root = createRoot(fixture); const actions: string[] = [];
  const song = { id: 'smoke-song', name: 'Menu check', artist: 'Fixture', cover: '', duration: 60000, count: 0, editable: false };
  const playlist = { ...song, id: 'smoke-playlist', name: '我喜欢', editable: true, count: 1, favorite:true };
  function checkPlaylistText(card: Element) {
    const cover=card.querySelector('.cover')!.getBoundingClientRect();
    const info=card.querySelector('.playlist-info')!.getBoundingClientRect();
    const name=card.querySelector('strong')!.getBoundingClientRect();
    const count=card.querySelector('.playlist-info span')!.getBoundingClientRect();
    if(Math.abs(info.left-cover.right-32)>1 || Math.abs(name.left-count.left)>1 || count.top<name.bottom
      || Math.abs((info.top+info.bottom)/2-(cover.top+cover.bottom)/2)>1)
      throw new Error('Playlist name/count are not left-aligned and centered beside the cover');
  }
  const fixtureLibrary: ReturnType<typeof useLibrary> = {
    state: { ...library, connected: true, playlists: [{...playlist,id:'smoke-other',name:'其他歌单',favorite:false},playlist], tracks: [song], trackCount: 1,
      playlistId: playlist.id, playlistName: playlist.name,
      menu: { id: song.id, liked: false, canFavorite: true, playlists: [playlist] } },
    error: '', pending: false, page: 1, open: async () => {},
    menu: async (id: string) => { actions.push(`menu:${id}`); },
    favorite: async (id: string, enabled: boolean) => { actions.push(`favorite:${id}:${enabled}`); },
    add: async (id: string, list: string) => { actions.push(`add:${id}:${list}`); },
    importLocal: async () => { actions.push('importLocal'); },
    removeLocalFolder: async id => { actions.push(`removeLocalFolder:${id}`); },
    removeLocalTrack: async (id,playlistId) => { actions.push(`removeLocalTrack:${id}:${playlistId}`); },
    playPlaylist: async id => { actions.push(`playlistPlay:${id}`); },
    setPlaybackOrder: async order => { actions.push(`order:${order}`); },
    qualities: async id => { actions.push(`qualities:${id}`); },
    setQuality: async (id,quality) => { actions.push(`quality:${id}:${quality}`); },
    setPlatform: async (id,platform) => { actions.push(`platform:${id}:${platform}`); },
    queueNext: async (source, id) => { actions.push(`next:${source}:${id}`); },
    queueRemove: async id => { actions.push(`remove:${id}`); },
    queueClear: async () => { actions.push('clearQueue'); },
    recentRemove: async id=>{actions.push(`recentRemove:${id}`);},
    recentClear: async ()=>{actions.push('recentClear');},
    loadNextPage: async () => false, refresh: async () => true,
    search: async () => {}, skip: async () => {}, login: async () => {}, logout: async () => {}, sync: async () => {}, cancel: async () => {},
    play: async (source, id) => { actions.push(`play:${id}`, `play:${source}:${id}`); },
  };
  try {
    root.render(createElement(LibraryView, { library: fixtureLibrary, theme: 'light' }));
    await waitFor(() => !!fixture.querySelector('.playlist'));
    const favoritesCard=fixture.querySelector<HTMLButtonElement>('.playlist')!;
    if(favoritesCard.querySelector('strong')?.textContent!=='云端收藏整合' || !favoritesCard.querySelector('svg path'))throw new Error('Cloud favorites entry is missing or not first');
    checkPlaylistText(favoritesCard);
    const cardNames=Array.from(fixture.querySelectorAll('.playlist strong')).map(e=>e.textContent);
    if(JSON.stringify(cardNames)!==JSON.stringify(['云端收藏整合','我喜欢','其他歌单']))throw new Error('Created playlist favorites ordering failed');
    if(favoritesCard.querySelector('svg')!.getBoundingClientRect().width!==44)throw new Error('Cloud favorites heart size is incorrect');
    fixtureLibrary.state={...fixtureLibrary.state!,connected:false};
    root.render(createElement(LibraryView,{library:fixtureLibrary,theme:'light'}));
    await waitFor(()=>!Array.from(fixture.querySelectorAll('.playlist strong')).some(e=>e.textContent==='云端收藏整合'));
    if(!fixture.querySelector('.library-section-empty')?.textContent?.includes('登录后'))throw new Error('Signed-out library prompt missing');
    fixtureLibrary.state={...fixtureLibrary.state!,connected:true};
    root.render(createElement(LibraryView,{library:fixtureLibrary,theme:'light'}));
    await waitFor(()=>fixture.querySelector('.playlist strong')?.textContent==='云端收藏整合');
    const playlistCard=Array.from(fixture.querySelectorAll<HTMLButtonElement>('.playlist')).find(card=>card.querySelector('strong')?.textContent===playlist.name)!;
    checkPlaylistText(playlistCard);
    playlistCard.click();
    await waitFor(() => !!fixture.querySelector('.song.track-row'));
    // Resizing a long playlist must retain row height and the current scroll anchor.
    const fixtureState = fixtureLibrary.state!;
    fixtureLibrary.state = { ...fixtureState, tracks: [{ ...song, id: 'remaining', name: 'Remaining favorite' }], trackCount: 1 };
    root.render(createElement(LibraryView, { library: fixtureLibrary, theme: 'light' }));
    await waitFor(() => fixture.querySelectorAll('.song.track-row').length === 1
      && fixture.querySelector('.track-title')?.textContent === 'Remaining favorite', 'Favorite removal retained a stale song row');
    fixtureLibrary.state = { ...fixtureState, tracks: Array.from({ length: 1000 }, (_, index) => ({ ...song, id: index ? `resize-${index}` : song.id })), trackCount: 1000 };
    root.render(createElement(LibraryView, { library: fixtureLibrary, theme: 'light' }));
    await waitFor(() => fixture.querySelector('.song-list')!.getBoundingClientRect().height > 50000, 'Long resize fixture did not load');
    const songViewport = fixture.querySelector<HTMLElement>('.song-scroll')!;
    songViewport.scrollTop = 10000;
    await new Promise(resolve => setTimeout(resolve, 80));
    const anchor = fixture.querySelector('.song.track-row')!.getAttribute('data-index');
    const rowHeight = fixture.querySelector('.song.track-row')!.getBoundingClientRect().height;
    const fixtureWidth = fixture.getBoundingClientRect().width;
    for (let step = 1; step <= 8; step++) {
      fixture.style.right = 'auto'; fixture.style.width = `${fixtureWidth - step * 3}px`;
      await new Promise(resolve => setTimeout(resolve, 32));
      if (fixture.querySelector('.song.track-row')!.getAttribute('data-index') !== anchor
        || fixture.querySelector('.song.track-row')!.getBoundingClientRect().height !== rowHeight
        || songViewport.scrollTop !== 10000)
        throw new Error('Width-only resize moved the playlist scroll anchor or changed row height');
    }
    fixture.style.width = ''; fixture.style.right = '40px';
    fixtureLibrary.state = fixtureState;
    root.render(null);
    await waitFor(() => !fixture.querySelector('.library-shell'));
    root.render(createElement(LibraryView, { library: fixtureLibrary, theme: 'light' }));
    await waitFor(() => !!fixture.querySelector('.playlist'));
    Array.from(fixture.querySelectorAll<HTMLButtonElement>('.playlist')).find(card => card.querySelector('strong')?.textContent === playlist.name)!.click();
    await waitFor(() => fixture.querySelectorAll('.song.track-row').length === 1, 'Single-song fixture did not restore');
    const playlistPlay = fixture.querySelector<HTMLButtonElement>('[aria-label="播放歌单"]')!;
    const previousTheme = document.documentElement.getAttribute('data-theme');
    const buttonBackground=document.createElement('span');buttonBackground.style.background='var(--side)';fixture.append(buttonBackground);
    try {
      for (const theme of ['dark', 'glass']) {
        document.documentElement.setAttribute('data-theme', theme);
        await new Promise(resolve=>setTimeout(resolve,250));
        const style = getComputedStyle(playlistPlay);
        if (style.backgroundColor !== getComputedStyle(buttonBackground).backgroundColor || style.color !== 'rgb(255, 255, 255)')
          throw new Error(`Playlist play button colors changed in ${theme}`);
      }
    } finally {
      buttonBackground.remove();
      if (previousTheme === null) document.documentElement.removeAttribute('data-theme');
      else document.documentElement.setAttribute('data-theme', previousTheme);
    }
    fixture.querySelector<HTMLButtonElement>('[aria-label="播放歌单"]')!.click();
    await waitFor(() => actions.includes(`playlistPlay:${playlist.id}`));
    const row = fixture.querySelector<HTMLElement>('.song.track-row')!;
    row.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
    if (!actions.includes(`play:${song.id}`)) throw new Error('Song double-click stopped playing');
    const rightClick = () => row.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, button: 2, clientX: 300, clientY: 250 }));
    rightClick();
    await waitFor(() => !!document.querySelector('[data-slot=context-menu-content]'));
    if (document.querySelector('[role=dialog][aria-label="歌曲操作"]') || !actions.includes(`menu:${song.id}`))
      throw new Error('Song context menu opened a dialog or skipped the existing action');
    document.querySelector<HTMLElement>('[data-slot=context-menu-item]')!.click();
    await waitFor(() => actions.includes(`next:library:${song.id}`) && !document.querySelector('[data-slot=context-menu-content]'));
    rightClick();
    await waitFor(() => !!document.querySelector('[data-slot=context-menu-content]'));
    Array.from(document.querySelectorAll<HTMLElement>('[data-slot=context-menu-item]')).find(item => item.textContent === '收藏到我喜欢')!.click();
    await waitFor(() => actions.includes(`favorite:${song.id}:true`) && !document.querySelector('[data-slot=context-menu-content]'));
    rightClick();
    await waitFor(() => !!document.querySelector('[data-slot=context-menu-sub-trigger]'));
    const submenu = document.querySelector<HTMLElement>('[data-slot=context-menu-sub-trigger]')!;
    submenu.focus(); submenu.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    await waitFor(() => !!document.querySelector('[data-slot=context-menu-sub-content]'));
    document.querySelector<HTMLElement>('[data-slot=context-menu-sub-content] [data-slot=context-menu-item]')!.click();
    await waitFor(() => actions.includes(`add:${song.id}:${playlist.id}`) && !document.querySelector('[data-slot=context-menu-content]'));
    rightClick();
    await waitFor(() => !!document.querySelector('[data-slot=context-menu-content]'));
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await waitFor(() => !document.querySelector('[data-slot=context-menu-content]'));
    rightClick();
    await waitFor(() => !!document.querySelector('[data-slot=context-menu-content]'));
    await new Promise(resolve => setTimeout(resolve, 25));
    checkMenuDragRegions(true);
    fixture.querySelector('.sidebar')!.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerType: 'mouse', button: 0 }));
    await waitFor(() => !document.querySelector('[data-slot=context-menu-content]'));
    checkMenuDragRegions(false);
    Array.from(fixture.querySelectorAll<HTMLButtonElement>('.sidebar > button')).find(button => button.textContent?.trim() === '音乐库')!.click();
    await waitFor(() => !!fixture.querySelector('[aria-label="音乐库分类"]'));
    fixture.querySelector<HTMLButtonElement>('[aria-label="音乐库分类"] button:last-child')!.click();
    await waitFor(() => !!fixture.querySelector('.local-import'), 'Fixture local import button did not render');
    const importButton=fixture.querySelector<HTMLButtonElement>('.local-import')!;
    await new Promise(resolve => setTimeout(resolve, 25));
    importButton.dispatchEvent(new PointerEvent('pointermove', { bubbles: true, pointerType: 'mouse' }));
    await waitFor(() => !!document.querySelector('[role=tooltip]'), 'Local format tooltip did not open');
    if(!document.querySelector('[role=tooltip]')?.textContent?.includes('MP3、WAV、FLAC、AAC'))throw new Error('Missing local music format tooltip');
    importButton.click();
    await waitFor(() => actions.includes('importLocal'), 'Import button did not dispatch');
    const localPlaylist={...playlist,id:'local-folder:1',name:'本地文件夹',editable:false};
    fixtureLibrary.state = { ...fixtureLibrary.state!, playlistId: 'musxi:local', playlistName: '本地音乐', localPlaylists:[localPlaylist], connected: false };
    root.render(createElement(LibraryView, { library: fixtureLibrary, theme: 'light' }));
    await waitFor(() => Array.from(fixture.querySelectorAll('.playlist')).some(button=>button.textContent?.includes(localPlaylist.name)), 'Local folder card did not render');
    checkPlaylistText(Array.from(fixture.querySelectorAll('.playlist')).find(button=>button.textContent?.includes(localPlaylist.name))!);
    if(fixture.querySelector('.track-table'))throw new Error('Local home still shows the song table');
    const localCards=fixture.querySelectorAll<HTMLButtonElement>('.local-import-grid .playlist');
    if(!localCards[0].classList.contains('local-all') || !localCards[localCards.length-2].classList.contains('local-import')
      || !localCards[localCards.length-1].classList.contains('local-remove'))
      throw new Error('Local all/import/remove card order is incorrect');
    const removeButton=fixture.querySelector<HTMLButtonElement>('.local-remove')!;
    if(Math.abs(removeButton.getBoundingClientRect().height-importButton.getBoundingClientRect().height)>1
      || Math.abs(removeButton.getBoundingClientRect().width-importButton.getBoundingClientRect().width)>1)throw new Error('Local remove card differs from import card size');
    removeButton.click();
    await waitFor(()=>!!document.querySelector('.local-folder-options button'));
    const removeOption=document.querySelector<HTMLButtonElement>('.local-folder-options button')!;
    if(!removeOption.textContent?.includes(localPlaylist.name) || removeOption.querySelector('svg'))throw new Error('Folder removal option should contain its name without an icon');
    if(removeButton.textContent!=='移除音乐文件夹' || document.querySelector('.local-folder-dialog h2')?.textContent!=='移除音乐文件夹')throw new Error('Folder removal title is incorrect');
    removeOption.click();await waitFor(()=>actions.includes(`removeLocalFolder:${localPlaylist.id}`));
    if(!document.querySelector('.local-folder-dialog'))throw new Error('Folder removal unexpectedly closed the dialog');
    fixtureLibrary.state={...fixtureLibrary.state!,localPlaylists:[]};
    root.render(createElement(LibraryView,{library:fixtureLibrary,theme:'light'}));
    await waitFor(()=>!!document.querySelector('.local-folder-options p'));
    document.querySelector('.local-folder-dialog')!.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));
    await waitFor(()=>!document.querySelector('.local-folder-dialog'));
    fixtureLibrary.state={...fixtureLibrary.state!,localPlaylists:[localPlaylist]};
    root.render(createElement(LibraryView,{library:fixtureLibrary,theme:'light'}));
    await waitFor(()=>fixture.querySelectorAll('.local-import-grid .playlist').length===localCards.length);
    localCards[0].click();
    await waitFor(() => !!fixture.querySelector('.song.track-row'), 'All-local playlist did not show songs');
    if(fixture.querySelector('h1')?.textContent !== '本地歌曲整合')throw new Error('All-local playlist title is incorrect');
    fixture.querySelector<HTMLElement>('.song.track-row')!.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
    if(!actions.includes(`play:local:${song.id}`))throw new Error('All-local song playback failed');
    fixture.querySelector<HTMLButtonElement>('.back-button')!.click();
    await waitFor(() => !!fixture.querySelector('.local-all'));
    Array.from(fixture.querySelectorAll<HTMLButtonElement>('.playlist')).find(button=>button.textContent?.includes(localPlaylist.name))!.click();
    fixtureLibrary.state={...fixtureLibrary.state!,playlistId:localPlaylist.id,playlistName:localPlaylist.name};
    root.render(createElement(LibraryView, { library: fixtureLibrary, theme: 'light' }));
    await waitFor(() => !!fixture.querySelector('.song.track-row'), 'Local song did not render');
    fixture.querySelector<HTMLElement>('.song.track-row')!.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
    if (!actions.includes(`play:local:${song.id}`)) throw new Error('Local double-click did not play offline');
    fixture.querySelector<HTMLElement>('.song.track-row')!.dispatchEvent(new MouseEvent('contextmenu',{bubbles:true,cancelable:true,button:2,clientX:500,clientY:300}));
    await waitFor(()=>!!document.querySelector('[data-slot=context-menu-content]'));
    const localMenuItems=Array.from(document.querySelectorAll<HTMLElement>('[data-slot=context-menu-content] [role=menuitem]'));
    const removeTrackItem=localMenuItems.find(item=>item.textContent==='移除');
    if(!removeTrackItem || localMenuItems.some(item=>item.textContent==='播放'))throw new Error('Local song menu did not replace Play with Remove');
    removeTrackItem.click();await waitFor(()=>actions.includes(`removeLocalTrack:${song.id}:${localPlaylist.id}`));
    await waitFor(()=>!document.querySelector('[data-slot=context-menu-content]'));
    const localSongState=fixtureLibrary.state!;
    fixtureLibrary.state={...localSongState,tracks:[],trackCount:0,localPlaylists:[{...localPlaylist,count:0}]};
    root.render(createElement(LibraryView,{library:fixtureLibrary,theme:'light'}));
    await waitFor(()=>!fixture.querySelector('.song.track-row'));
    fixtureLibrary.state=localSongState;root.render(createElement(LibraryView,{library:fixtureLibrary,theme:'light'}));
    await waitFor(()=>!!fixture.querySelector('.song.track-row'));
    const sidebarToggle=fixture.querySelector<HTMLButtonElement>('[data-slot=sidebar-rail]')!;
    sidebarToggle.click();
    await waitFor(() => !fixture.querySelector('[data-slot=sidebar]'), 'Sidebar did not completely collapse');
    if(document.querySelector('[data-slot=sheet-content],[data-slot=sheet-overlay]'))throw new Error('Sidebar still uses Sheet');
    sidebarToggle.click();
    await waitFor(() => !!fixture.querySelector('[data-slot=sidebar]'), 'Sidebar did not expand');
    fixture.querySelector<HTMLButtonElement>('.settings-nav')!.click();
    await waitFor(() => !!fixture.querySelector('.settings-content'), 'Sidebar settings navigation failed');
    fixtureLibrary.state={...fixtureLibrary.state!,connected:true,queue:[song],queueCurrentId:song.id};
    root.render(createElement('section', { className:'player', style:{position:'absolute',bottom:0,right:0,left:0} },
      createElement(QueuePanel,{library:fixtureLibrary})));
    await waitFor(() => !!fixture.querySelector('[aria-label="播放列表"]'));
    fixture.querySelector<HTMLButtonElement>('[aria-label="播放列表"]')!.click();
    await waitFor(() => !!document.querySelector('[data-slot=popover-content]'));
    const queuePanel=document.querySelector<HTMLElement>('[data-slot=popover-content]')!;
    if(queuePanel.dataset.side!=='top' || !queuePanel.querySelector('.queue-row.current') || queuePanel.getBoundingClientRect().height<300)
      throw new Error('Queue panel is not above the button or does not mark the current song');
    if(queuePanel.querySelectorAll('.queue-row.current .queue-play rect').length!==2
      || getComputedStyle(queuePanel.querySelector('.queue-play')!).color!==getComputedStyle(queuePanel).color)
      throw new Error('Queue playback icon differs from the player controls or uses a foreign theme color');
    queuePanel.querySelector<HTMLButtonElement>('.queue-play')!.click();
    await waitFor(() => actions.includes(`play:queue:${song.id}`));
    queuePanel.querySelector<HTMLButtonElement>(`[aria-label="从播放列表移除 ${song.name}"]`)!.click();
    await waitFor(() => actions.includes(`remove:${song.id}`));
    queuePanel.querySelector<HTMLButtonElement>('[aria-label="清空播放列表"]')!.click();
    await waitFor(() => actions.includes('clearQueue'));
    fixtureLibrary.state={...fixtureLibrary.state!,queue:[]};
    root.render(createElement('section', { className:'player', style:{position:'absolute',bottom:0,right:0,left:0} },
      createElement(QueuePanel,{library:fixtureLibrary})));
    await waitFor(() => !!document.querySelector('.queue-empty'));
    const clearQueue=document.querySelector<HTMLButtonElement>('[aria-label="清空播放列表"]')!;
    if(!clearQueue.disabled || getComputedStyle(clearQueue).cursor!=='default')
      throw new Error('Empty queue clear action displays a busy cursor');
    document.dispatchEvent(new KeyboardEvent('keydown', { key:'Escape',bubbles:true }));
    await waitFor(() => !document.querySelector('[data-slot=popover-content]'));
    const actionNotices: string[] = [];
    fixtureLibrary.state={...fixtureLibrary.state!,busy:false,accounts:[{platform:'kugou',name:'酷狗概念版',connected:true,user:'',error:''},{platform:'qq',name:'QQ 音乐',connected:false,user:'',error:''}],now:{...fixtureLibrary.state!.now,platform:'kugou',platforms:['kugou','qq'],liked:true,canFavorite:true},menu:{id:song.id,liked:true,canFavorite:true,playlists:[playlist]}};
    const renderPlayerActions = (id: string) => root.render(createElement('section', { className:'player', style:{position:'absolute',bottom:0,right:0,left:0} },
      createElement(PlayerActions,{library:fixtureLibrary,currentId:id,onNotice:text=>actionNotices.push(text),children:null})));
    renderPlayerActions(song.id);
    await waitFor(() => !!fixture.querySelector('.player-favorite'), 'Player actions did not render');
    const favorite=fixture.querySelector<HTMLButtonElement>('.player-favorite')!;
    if(getComputedStyle(favorite.querySelector('svg')!).fill!=='rgb(243, 75, 80)')throw new Error('Favorite heart is not filled red');
    favorite.click();
    await waitFor(()=>!!document.querySelector('[aria-label="收藏平台"][data-slot=popover-content]'));
    const favoriteMenu=document.querySelector<HTMLElement>('[aria-label="收藏平台"][data-slot=popover-content]')!;
    const favoriteOptions=favoriteMenu.querySelectorAll<HTMLButtonElement>('button');
    if(favoriteMenu.dataset.side!=='top' || favoriteOptions.length!==1 || favoriteOptions[0].getAttribute('aria-pressed')!=='true'
      || favoriteMenu.textContent?.includes('QQ') || favoriteMenu.textContent?.includes('网易云'))throw new Error('Favorite platform filtering, selected state or login gating failed');
    favoriteOptions[0].click();
    await waitFor(() => actions.includes(`favorite:${song.id}:false`));
    await waitFor(()=>!document.querySelector('[aria-label="收藏平台"][data-slot=popover-content]'));
    favorite.click();
    await waitFor(()=>!!document.querySelector('[aria-label="收藏平台"][data-slot=popover-content]'));
    await new Promise(resolve => setTimeout(resolve, 25));
    checkMenuDragRegions(true);
    document.querySelector('.window-bar')!.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerType: 'mouse', button: 0 }));
    document.querySelector<HTMLElement>('.window-bar')!.click();
    await waitFor(()=>!document.querySelector('[aria-label="收藏平台"][data-slot=popover-content]'));
    checkMenuDragRegions(false);
    await new Promise<void>(resolve=>requestAnimationFrame(()=>requestAnimationFrame(()=>resolve())));
    fixture.querySelector<HTMLButtonElement>('[aria-label="添加当前歌曲到歌单"]')!.click();
    await waitFor(() => !!document.querySelector('.player-action-menu'), 'Player add menu did not open');
    const addMenu=document.querySelector<HTMLElement>('.player-action-menu')!;
    if(getComputedStyle(addMenu).getPropertyValue('-webkit-app-region')!=='no-drag')
      throw new Error('Player menu does not exclude overlapping window drag regions');
    if(addMenu.dataset.side!=='top')throw new Error('Player add menu is not above its trigger');
    if(addMenu.getBoundingClientRect().bottom-addMenu.lastElementChild!.getBoundingClientRect().bottom>12)
      throw new Error('Player add menu has excess blank space below its contents');
    addMenu.querySelector<HTMLButtonElement>('button')!.click();
    await waitFor(() => actions.includes(`add:${song.id}:${playlist.id}`));
    await waitFor(() => !document.querySelector('.player-action-menu'), 'Player add menu did not close');
    await new Promise(resolve => setTimeout(resolve, 50));
    fixture.querySelector<HTMLButtonElement>('[aria-label^="播放顺序："]')!.click();
    await waitFor(() => !!document.querySelector('.player-action-menu'), 'Playback order menu did not open');
    const orderButtons=document.querySelectorAll<HTMLButtonElement>('.player-action-menu button');
    if(orderButtons.length!==3)throw new Error('Playback order options missing');
    if(document.querySelector('.player-action-menu')!.getBoundingClientRect().bottom-orderButtons[2].getBoundingClientRect().bottom>12)
      throw new Error('Playback order menu has excess blank space below its contents');
    orderButtons[2].click();
    await waitFor(() => actions.includes('order:repeat-one'), 'Playback order did not dispatch');
    await waitFor(() => !document.querySelector('.player-action-menu'));
    await new Promise(resolve=>setTimeout(resolve,50));
    fixtureLibrary.state={...fixtureLibrary.state!,qualities:{id:song.id,current:'128',options:[{id:'128',name:'标准 · 128 kbps'},{id:'320',name:'高品质 · 320 kbps'},{id:'netease:hires',name:'Hi-Res'}]}};
    renderPlayerActions(song.id);
    await new Promise(resolve=>setTimeout(resolve,25));
    fixture.querySelector<HTMLButtonElement>('[aria-label="音质选择"]')!.click();
    await waitFor(() => !!document.querySelector('.player-action-menu'));
    const qualityMenu=document.querySelector<HTMLElement>('.player-action-menu')!;
    if(qualityMenu.dataset.side!=='top' || qualityMenu.getBoundingClientRect().bottom-qualityMenu.lastElementChild!.getBoundingClientRect().bottom>12)
      throw new Error('Quality menu is not compact and above the trigger');
    qualityMenu.querySelectorAll<HTMLButtonElement>('button')[2].click();
    await waitFor(() => actions.includes(`quality:${song.id}:netease:hires`));
    await waitFor(() => !document.querySelector('.player-action-menu'));
    await new Promise(resolve=>setTimeout(resolve,50));
    renderPlayerActions('local:fixture');
    await waitFor(() => fixture.querySelector('.player-favorite')?.getAttribute('aria-label')==='收藏当前歌曲');
    await new Promise(resolve=>setTimeout(resolve,25));
    fixture.querySelector<HTMLButtonElement>('.player-favorite')!.click();
    fixture.querySelector<HTMLButtonElement>('[aria-label="添加当前歌曲到歌单"]')!.click();
    if(actionNotices.length!==2 || actionNotices.some(text=>text!=='本地歌曲无法收藏和添加到歌单') || document.querySelector('.player-action-menu'))
      throw new Error('Local player actions do not show the unsupported notice');
    fixture.querySelector<HTMLButtonElement>('[aria-label="音质选择"]')!.click();
    await waitFor(() => !!document.querySelector('.player-action-menu'));
    if(!document.querySelector('.player-action-menu')?.textContent?.includes('原始音质'))throw new Error('Local quality menu does not use original quality');
    document.querySelector<HTMLButtonElement>('.player-action-menu button')!.click();
    fixtureLibrary.state={...fixtureLibrary.state!,recent:[{...song,platform:'qq',platforms:['netease','kugou','qq']}]};
    root.render(createElement(LibraryView,{library:fixtureLibrary,theme:'light'}));
    await waitFor(()=>!!fixture.querySelector('.sidebar'));
    Array.from(fixture.querySelectorAll<HTMLButtonElement>('.sidebar button')).find(button=>button.textContent?.includes('最近播放'))!.click();
    await waitFor(()=>fixture.querySelectorAll('.song-platform-tag').length===3);
    if(JSON.stringify(Array.from(fixture.querySelectorAll('.song-platform-tag')).map(tag=>tag.textContent))!==JSON.stringify(['网易云','酷狗','QQ']))throw new Error('Recent history did not display all supported platforms');
    const avatar='data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aT9sAAAAASUVORK5CYII=';
    for(const platform of ['netease','qq','kugou'] as const) {
      fixtureLibrary.state={...fixtureLibrary.state!,avatar:'',accounts:[{platform,name:platform,connected:true,user:'Avatar fixture',error:'',avatar,membership:{name:'VIP fixture',expiresAt:1800000000000}}]};
      root.render(createElement(AccountDialog,{key:platform,open:true,onOpenChange:()=>{},initialPlatform:platform,library:fixtureLibrary}));
      await waitFor(()=>document.querySelector<HTMLImageElement>('.account-user-avatar img')?.src===avatar);
      const box=document.querySelector<HTMLElement>('.account-user-avatar')!.getBoundingClientRect();
      if(box.width!==80 || box.height!==80)throw new Error('Account avatar is not an 80px square');
      await waitFor(()=>document.querySelector('.account-membership')?.textContent?.includes('到期')===true);
      const membershipBox=document.querySelector('.account-membership')!.getBoundingClientRect();
      if(membershipBox.top<box.bottom || Math.abs((membershipBox.left+membershipBox.right)/2-(box.left+box.right)/2)>1)
        throw new Error('Account membership is not centered below its avatar');
      fixtureLibrary.state={...fixtureLibrary.state!,accounts:[{platform,name:platform,connected:true,user:'Avatar fixture',error:'',avatar:''}]};
      root.render(createElement(AccountDialog,{key:platform,open:true,onOpenChange:()=>{},initialPlatform:platform,library:fixtureLibrary}));
      await waitFor(()=>!!document.querySelector('.account-user-avatar') && !document.querySelector('.account-user-avatar img'));
      if(document.querySelector('.account-membership')?.textContent!=='会员状态暂不可用')throw new Error('Unknown membership became a nonmember');
    }
    const renderMessages = (text: string) => root.render(createElement('section', { className: 'player',
      style: { position: 'absolute', bottom: 0, left: 0, right: 0 } },
      createElement(MessageAlerts, { messages: { notice: { text } } })));
    renderMessages('播放队列为空');
    await waitFor(() => !!fixture.querySelector('[data-slot=alert]'), 'shadcn Alert did not display');
    const alertBounds=fixture.querySelector('[data-slot=alert]')!.getBoundingClientRect();
    const playerBounds=fixture.querySelector('.player')!.getBoundingClientRect();
    if(Math.abs(playerBounds.top-alertBounds.bottom-12)>1 || Math.abs(playerBounds.right-alertBounds.right-24)>1)
      throw new Error('Message is not above the player at the bottom right');
    await new Promise(resolve => setTimeout(resolve, 1500));
    renderMessages('播放队列为空');
    await new Promise(resolve => setTimeout(resolve, 1700));
    if(fixture.querySelector('[data-slot=alert]'))throw new Error('Message did not expire, or polling restarted its timer');
    renderMessages('导入完成');
    await waitFor(() => fixture.querySelector('[data-slot=alert-description]')?.textContent === '导入完成', 'New message did not display');
  } finally { root.unmount(); fixture.remove(); }
  document.querySelector<HTMLButtonElement>('.settings-nav')?.click();
  // 外观 / 播放 / 关于与更新 (the third tab appears once updates.getState answers).
  await waitFor(() => document.querySelectorAll('.settings-sections button').length === 3, 'Update settings tab is missing');
  const settingsTabs = document.querySelectorAll<HTMLButtonElement>('.settings-sections button');
  if (Array.from(settingsTabs).some(tab => Math.abs(tab.getBoundingClientRect().width-settingsTabs[0].getBoundingClientRect().width)>1
      || Math.abs(tab.getBoundingClientRect().top-settingsTabs[0].getBoundingClientRect().top)>1)
    || document.querySelector('.settings-content h2')) throw new Error('Settings tabs or removed subtitle are incorrect');
  // Smoke runs a development build with network disabled: no notice, no download, no automatic check.
  settingsTabs[2].click();
  await waitFor(() => !!document.querySelector('.update-settings .update-status'), 'Update settings did not load');
  const updates = await native.updates.getState();
  if (updates.current.releaseBuild || updates.result !== 'development_build' || updates.automaticCheckEnabled
    || document.querySelector('.update-notice') || document.querySelector('.update-primary')
    || !document.querySelector('.update-status')?.textContent?.includes('开发版本')
    || !document.querySelector('.update-current')?.textContent?.includes(updates.current.version))
    throw new Error('Development-build update settings are incorrect');
  settingsTabs[1].click();
  await waitFor(() => !!document.querySelector('#audio-output-device'), 'Playback settings did not load');
  if (document.querySelector('.settings-content h2') || document.querySelectorAll('#default-audio-quality option').length !== 4)
    throw new Error('Playback settings structure is incorrect');
  settingsTabs[0].click();
  // Tone buttons share .appearance-segment; only the mode group is under test here.
  const modeSegments = '[aria-labelledby=appearance-mode-label] .appearance-segment';
  await waitFor(() => document.querySelectorAll(modeSegments).length === 3);
  const cards = document.querySelectorAll<HTMLButtonElement>(modeSegments);
  const toggle = document.querySelector<HTMLButtonElement>('.appearance-switch')!;
  if (toggle.getAttribute('aria-checked') === 'true') {
    toggle.click();
    await waitFor(() => toggle.getAttribute('aria-checked') === 'false');
  }
  cards[0].click();
  await waitFor(() => document.documentElement.dataset.theme === 'light');
  if (document.querySelector('.appearance-slider')) throw new Error('Disabled transparency did not hide slider');
  toggle.click();
  await waitFor(() => document.documentElement.dataset.theme === 'glass-light' && cards[0].getAttribute('aria-pressed') === 'true');
  cards[1].click();
  await waitFor(() => document.documentElement.dataset.theme === 'glass' && cards[1].getAttribute('aria-pressed') === 'true');
  toggle.click();
  await waitFor(() => document.documentElement.dataset.theme === 'dark');
  cards[2].click();
  await waitFor(() => document.documentElement.dataset.theme === (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
    && localStorage.getItem('musxi-appearance-mode') === 'system');
  cards[0].click();
  await waitFor(() => document.documentElement.dataset.theme === 'light');
  const state = await native.player.getState();
  const windowState = await native.window.getState();
  if (windowState.enabled) {
    const settledWindowSize=async () => {
      const size=await request(window as unknown as CefTransport,'test.windowSize',{}) as { width:number; height:number };
      await waitFor(() => window.innerHeight===size.height && window.innerWidth===size.width, `CEF viewport did not match native ${size.width}x${size.height}`);
      return size;
    };
    const expandedSize=await settledWindowSize();
    const expandedHeight=expandedSize.height;
    const expandedWidth=expandedSize.width;
    const resizeTest=(stage:string,width:number,height:number)=>request(window as unknown as CefTransport,'test.resizeWindow',{stage,width,height});
    const resizeAlpha=async(x:number,y:number)=>Number(await request(window as unknown as CefTransport,'test.pixelAlpha',{x,y}));
    const checkResizeCorners=async()=>{
      const size=await request(window as unknown as CefTransport,'test.windowSize',{}) as {width:number;height:number};
      for(const [x,y] of [[0,0],[size.width-1,0],[0,size.height-1],[size.width-1,size.height-1]])
        if(await resizeAlpha(x,y)!==0)throw new Error('Interactive resize lost a window corner');
    };
    const resizeTheme=document.documentElement.dataset.theme;
    const resizeOpacity=document.documentElement.style.getPropertyValue('--background-opacity');
    const timeAxis=document.querySelector('.player-progress')!.getBoundingClientRect();
    try {
      await resizeTest('begin',Math.max(800,expandedWidth-64),Math.max(560,expandedHeight-40));
      await resizeTest('update',expandedWidth+64,expandedHeight+40);
      await new Promise(resolve=>setTimeout(resolve,80));
      if(window.innerWidth!==expandedWidth || window.innerHeight!==expandedHeight
        || document.querySelector('.player-progress')!.getBoundingClientRect().top!==timeAxis.top)
        throw new Error('Window contents reflowed during interactive resize');
      const background=await request(window as unknown as CefTransport,'test.pixelAlpha',{x:Math.floor(expandedWidth/2),y:20});
      if(await request(window as unknown as CefTransport,'test.pixelAlpha',{x:expandedWidth+16,y:20})!==background)
        throw new Error('Expanded resize area did not preserve the page background alpha');
      await checkResizeCorners();
      for(const [x,y] of [[expandedWidth-1,0],[expandedWidth-1,expandedHeight-1],[0,expandedHeight-1]])
        if(await resizeAlpha(x,y)!==255)throw new Error('Old viewport corners left a gap inside the enlarged window');
      await resizeTest('update',Math.max(800,expandedWidth-32),Math.max(560,expandedHeight-32));
      await checkResizeCorners();
      document.documentElement.dataset.theme='glass-light';
      document.documentElement.style.setProperty('--background-opacity','0.5');
      const glassDeadline=Date.now()+2000;
      while(Math.abs(await resizeAlpha(Math.floor(expandedWidth/2),20)-128)>1) {
        if(Date.now()>glassDeadline)throw new Error('Translucent resize frame did not arrive');
        await new Promise(resolve=>setTimeout(resolve,20));
      }
      await resizeTest('update',expandedWidth+64,expandedHeight+40);
      await checkResizeCorners();
      for(const [x,y] of [[expandedWidth-1,0],[expandedWidth-1,expandedHeight-1],[0,expandedHeight-1],[expandedWidth+16,20]])
        if(Math.abs(await resizeAlpha(x,y)-128)>1)throw new Error('Resize seam changed the translucent background opacity');
      await resizeTest('end',Math.max(800,expandedWidth-64),Math.max(560,expandedHeight-40));
      const finalSize=await settledWindowSize();
      if(finalSize.width===expandedWidth && finalSize.height===expandedHeight)throw new Error('Resize release did not apply the final layout');
    } finally {
      if(resizeTheme===undefined)delete document.documentElement.dataset.theme;else document.documentElement.dataset.theme=resizeTheme;
      if(resizeOpacity)document.documentElement.style.setProperty('--background-opacity',resizeOpacity);
      else document.documentElement.style.removeProperty('--background-opacity');
      try {await resizeTest('end',expandedWidth,expandedHeight);}
      catch {await resizeTest('begin',expandedWidth,expandedHeight);await resizeTest('end',expandedWidth,expandedHeight);}
      await settledWindowSize();
    }
    const playerDock=document.querySelector<HTMLElement>('.player')!;
    const appShell=document.querySelector<HTMLElement>('.app-shell')!;
    const shellStyle=appShell.getAttribute('style');
    try {
      for(const [width,height] of [[800,560],[900,560],[expandedWidth,expandedHeight]]) {
        appShell.style.width=`${width}px`;appShell.style.height=`${height}px`;appShell.style.margin='0';
        await new Promise(resolve=>requestAnimationFrame(()=>resolve(undefined)));
        if(getComputedStyle(playerDock).getPropertyValue('-webkit-app-region').trim()!=='no-drag')
          throw new Error('Resized player dock can be intercepted by native window dragging');
        for(const control of Array.from(playerDock.querySelectorAll<HTMLElement>('button,input'))) {
          if(!control.getClientRects().length || control.closest('[inert]'))continue;
          const bounds=control.getBoundingClientRect();
          if(bounds.width<=0 || bounds.height<=0)continue;
          const hit=document.elementFromPoint(bounds.left+bounds.width/2,bounds.top+bounds.height/2);
          if(!hit || !control.contains(hit))throw new Error(`Resized player control is blocked: ${control.getAttribute('aria-label')}`);
        }
      }
    } finally {
      if(shellStyle===null)appShell.removeAttribute('style');else appShell.setAttribute('style',shellStyle);
    }
    const settingsPage=document.querySelector('.settings-content');
    const rail=document.querySelector<HTMLElement>('.player-rail')!;
    const railBounds=rail.getBoundingClientRect();
    if(rail.children.length || getComputedStyle(rail).backgroundColor!=='rgba(0, 0, 0, 0)'
      || getComputedStyle(rail).cursor!=='n-resize' || railBounds.width!==window.innerWidth
      || document.elementFromPoint(railBounds.width/4,railBounds.top+8)!==rail)
      throw new Error('Player collapse rail is visible, blocked or not across the dock edge');
    const collapseWindow=async () => {
      document.querySelector<HTMLButtonElement>('[aria-label="收起主界面"]')!.click();
      await waitFor(() => !!document.querySelector('.compact-player') && window.innerHeight<200, `Window did not collapse (${window.innerHeight}px)`);
      if(document.querySelector('.window-bar') || getComputedStyle(document.querySelector('.app-body')!).display!=='none'
        || !document.querySelector('#playback') || document.querySelector('.settings-content')!==settingsPage)
        throw new Error('Compact mode did not preserve the page or hide window controls');
      const player=document.querySelector('.player')!.getBoundingClientRect();
      const expand=document.querySelector('[aria-label="展开主界面"]')!.getBoundingClientRect();
      if(player.bottom>window.innerHeight+1 || expand.top<0 || Math.abs(player.top-16)>1
        || expand.width!==window.innerWidth || getComputedStyle(rail).cursor!=='s-resize')
        throw new Error('Compact player or expand control is clipped');
      if(!(await native.window.getState()).compact)throw new Error('Native window did not enter compact mode');
    };
    await collapseWindow();
    document.querySelector<HTMLButtonElement>('[aria-label="播放列表"]')!.click();
    await waitFor(() => !document.querySelector('.compact-player') && window.innerHeight===expandedHeight
      && !!document.querySelector('[data-slot=popover-content]'), 'Queue did not expand the compact window');
    document.dispatchEvent(new KeyboardEvent('keydown', { key:'Escape', bubbles:true }));
    await waitFor(() => !document.querySelector('[data-slot=popover-content]'));
    if(window.innerWidth!==expandedWidth)throw new Error('Expanded window width changed');
    await collapseWindow();
    document.querySelector<HTMLButtonElement>('[aria-label="展开主界面"]')!.click();
    await waitFor(() => !document.querySelector('.compact-player') && window.innerHeight===expandedHeight, 'Window did not restore its original height');
    try { await native.window.setCompact(true,0);throw new Error('Invalid compact height accepted'); }
    catch(error) { if(!(error instanceof Error) || !error.message.includes('Invalid compact window parameters'))throw error; }
    document.querySelector<HTMLButtonElement>('.window-close')!.click();
    await waitFor(() => !!document.querySelector('[data-slot=dialog-content]'));
    const dialog = document.querySelector<HTMLElement>('[data-slot=dialog-content]')!;
    const buttons = dialog.querySelectorAll<HTMLButtonElement>('[data-slot=button]');
    if (!dialog.textContent?.includes('是否把 Musxi Player 最小化到托盘？')
      || buttons.length !== 2 || buttons[0].textContent !== '退出' || buttons[1].textContent !== '最小化'
      || document.activeElement !== buttons[1]) throw new Error('Incorrect shadcn close confirmation');
    dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await waitFor(() => !document.querySelector('[data-slot=dialog-content]'));
    toggle.click();
    await waitFor(() => document.documentElement.dataset.theme === 'glass-light');
    const slider = document.querySelector<HTMLInputElement>('.appearance-slider');
    if (!slider || slider.min !== '0' || slider.max !== '100') throw new Error('Missing transparency range');
    const saved = slider.value;
    const setRange = (value: number) => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(slider, String(value));
      slider.dispatchEvent(new Event('input', { bubbles: true }));
    };
    const pixel = (x: number, y: number) => request(window as unknown as CefTransport, 'test.pixelAlpha', { x, y });
    for (const themeCard of [cards[0], cards[1]]) {
      themeCard.click();
      await waitFor(() => document.documentElement.dataset.theme === (themeCard === cards[0] ? 'glass-light' : 'glass'));
      for (const value of [0, 50, 100]) {
        setRange(value);
        await waitFor(() => document.documentElement.style.getPropertyValue('--background-opacity') === String(1 - value / 100));
        const end = Date.now() + 3000; const expected = Math.round(255 * (1 - value / 100));
        while (Math.abs(Number(await pixel(window.innerWidth - 24, Math.round(window.innerHeight * .65))) - expected) > 1) {
          if (Date.now() > end) throw new Error(`Native background alpha did not reach ${expected}`);
          await new Promise(resolve => setTimeout(resolve, 30));
        }
        // Header and title bar must not compound the page's background alpha.
        for (const [x, y] of [[window.innerWidth - 60, 70], [window.innerWidth - 160, 16], [10, 16]]) {
          let alpha = Number(await pixel(x, y));
          while (Math.abs(alpha - expected) > 1 && Date.now() < end) {
            await new Promise(resolve => setTimeout(resolve, 30));
            alpha = Number(await pixel(x, y));
          }
          if (Math.abs(alpha - expected) > 1)
            throw new Error(`Header transparency differs at ${x},${y}: ${alpha}, expected ${expected}`);
        }
        // Selected controls stay opaque while the window background becomes transparent.
        const preview = themeCard.getBoundingClientRect();
        if (await pixel(Math.round(preview.left + 10), Math.round(preview.top + 10)) !== 255)
          throw new Error('Transparency faded controls');
      }
    }
    setRange(Number(saved));
    cards[0].click();
    await waitFor(() => document.documentElement.dataset.theme === 'glass-light');
    toggle.click();
    await waitFor(() => document.documentElement.dataset.theme === 'light' && !document.querySelector('.appearance-slider'));
    toggle.click();
    await waitFor(() => document.querySelector<HTMLInputElement>('.appearance-slider')?.value === saved);
    toggle.click();
    await waitFor(() => document.documentElement.dataset.theme === 'light');
    const maximized = await native.window.maximize();
    if (!maximized.maximized) throw new Error('Test window did not maximize');
    await waitFor(() => window.innerHeight>expandedHeight, 'Maximized window did not resize');
    const maximizedHeight=(await settledWindowSize()).height;
    await collapseWindow();
    document.querySelector<HTMLButtonElement>('[aria-label="展开主界面"]')!.click();
    await waitFor(() => !document.querySelector('.compact-player') && window.innerHeight===maximizedHeight, 'Window did not restore maximized height')
      .catch(async error => { throw new Error(`${error.message}: ${window.innerHeight}/${maximizedHeight}, ${JSON.stringify(await native.window.getState())}`); });
    if(!(await native.window.getState()).maximized)throw new Error('Compact mode did not restore maximized state');
    const restored = await native.window.maximize();
    if (restored.maximized) throw new Error('Test window did not restore');
    await waitFor(() => window.innerHeight===expandedHeight, 'Normal viewport did not finish restoring');
    await native.window.setTheme('glass-light');
    await native.window.setTheme('glass');
    await native.window.setTheme('light');
  }
  if (!document.querySelector('#playback') || !document.querySelector('#volume')?.textContent?.includes(`${state.volumePercent}%`))
    throw new Error('React state was not rendered');
  const prefix = 'musxi-reload:';
  if (!window.name.startsWith(prefix)) {
    const host = window as unknown as CefTransport;
    for (const [command, params, expected] of [
      ['player.pause', {}, 409], ['player.resume', {}, 409], ['player.seek', { positionMs: 10 }, 409],
      ['player.setVolume', { volumePercent: 101 }, 400], ['player.seek', { positionMs: -1 }, 400],
      ['player.seek', { positionMs: 1.5 }, 400], ['player.pause', { bad: true }, 400]
      ,['window.setTheme', { theme: 'invalid' }, 400], ['window.close', { bad: true }, 400], ['window.unknown', {}, 404]
      ,['library.play', { source: 'library', id: 'missing' }, 409],
      ['library.image', { url: 'https://example.com/private' }, 403],
      ['library.getState', { page: 0 }, 400], ['library.unknown', {}, 404]
    ] as const) {
      let rejected = false;
      try { await request(host, command, params); }
      catch (e) { if ((e as { code: number }).code !== expected) throw e; rejected = true; }
      if (!rejected) throw new Error('Invalid command accepted');
    }
    const events: PlayerEvent[] = [];
    let subscriptionError: Error | undefined;
    const stop = native.player.subscribe(event => events.push(event), e => { subscriptionError = e; });
    try {
      await waitFor(() => events.length > 0 || !!subscriptionError);
      if (subscriptionError) throw subscriptionError;
      const target = state.volumePercent === 37 ? 38 : 37;
      const slider = document.querySelector<HTMLInputElement>('input[aria-label="音量"]');
      if (!slider) throw new Error('Volume control is missing');
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(slider, String(target));
      slider.dispatchEvent(new Event('input', { bubbles: true }));
      if ((await native.player.getState()).volumePercent !== state.volumePercent)
        throw new Error('Range input submitted before commit');
      slider.dispatchEvent(new Event('change', { bubbles: true }));
      await waitFor(() => events.some(e => e.event === 'player.volumeChanged' && e.state.volumePercent === target));
      await waitFor(() => document.querySelector('#volume')?.textContent === `${target}%`);
      for(const [deltaY,expected] of [[120,target+5],[-120,target]]) {
        const wheel=new WheelEvent('wheel',{deltaY,bubbles:true,cancelable:true});
        slider.dispatchEvent(wheel);
        if(!wheel.defaultPrevented)throw new Error('Volume wheel did not prevent page scrolling');
        await waitFor(()=>document.querySelector('#volume')?.textContent===`${expected}%`, 'Volume wheel did not change native volume');
        await waitFor(()=>Number(slider.value)===expected, 'Volume wheel preview did not settle');
      }
      const mute=document.querySelector<HTMLButtonElement>('.volume-button');
      if(!mute)throw new Error('Mute button is missing');
      await waitFor(()=>!mute.disabled);mute.click();
      await waitFor(()=>document.querySelector('#volume')?.textContent==='0%' && mute.getAttribute('aria-pressed')==='true' && !mute.disabled);
      if(!mute.querySelector('path[d="m16 9 6 6m0-6-6 6"]'))throw new Error('Muted speaker cross is missing');
      mute.click();await waitFor(()=>document.querySelector('#volume')?.textContent===`${target}%` && mute.getAttribute('aria-pressed')==='false');
      stop();
      // A following query is processed after the cancellation IPC.
      await native.player.getState();
      const count = events.length;
      await native.player.setVolume(state.volumePercent);
      await waitFor(() => document.querySelector('#volume')?.textContent === `${state.volumePercent}%`);
      if (events.length !== count) throw new Error('Cancelled subscription received an event');
      await native.player.setVolume(target);
      await waitFor(() => document.querySelector('#volume')?.textContent === `${target}%`);
      window.name = prefix + JSON.stringify({ trackId: state.trackId, volumePercent: target,
        originalVolume: state.volumePercent, opened: state.opened });
    } catch (e) { await native.player.setVolume(state.volumePercent); throw e; }
    finally { stop(); }
    location.reload(); return;
  }
  const previous = JSON.parse(window.name.slice(prefix.length));
  window.name = '';
  if (previous.trackId !== state.trackId || previous.volumePercent !== state.volumePercent || previous.opened !== state.opened)
    throw new Error('Native state changed across reload');
  await native.player.setVolume(previous.originalVolume);
  await waitFor(() => document.querySelector('#volume')?.textContent === `${previous.originalVolume}%`);
  const lyricsToggle=document.querySelector<HTMLButtonElement>('.lyrics-toggle');
  if(!lyricsToggle)throw new Error('Lyrics toggle is missing');
  const playerTrack=document.querySelector<HTMLElement>('.player-track')!;
  const controls=document.querySelector<HTMLElement>('.play-controls')!;
  const controlsBefore=controls.getBoundingClientRect();
  lyricsToggle.click();
  await waitFor(()=>!!document.querySelector('.lyrics-page'));
  await new Promise(resolve => setTimeout(resolve, 220));
  const lyricsPage = document.querySelector<HTMLElement>('.lyrics-page')!;
  if (getComputedStyle(lyricsPage).getPropertyValue('-webkit-app-region').trim() !== 'drag')
    throw new Error('Lyrics page background does not allow dragging the window');
  if (getComputedStyle(lyricsPage.querySelector('.lyrics-track')!).getPropertyValue('-webkit-app-region').trim() !== 'drag')
    throw new Error('Lyrics cover and metadata block window dragging');
  const pageBounds = lyricsPage.getBoundingClientRect();
  const trackBounds = lyricsPage.querySelector('.lyrics-track')!.getBoundingClientRect();
  const lyricArea = lyricsPage.querySelector('.lyrics-lines,.lyrics-message')!;
  const lyricBounds = lyricArea.getBoundingClientRect();
  const coverBounds = lyricsPage.querySelector('.cover')!.getBoundingClientRect();
  const pageCenter = (pageBounds.top + pageBounds.bottom) / 2;
  const narrowLyrics = window.innerWidth <= 800;
  if (narrowLyrics ? trackBounds.bottom > lyricBounds.top :
    Math.abs((trackBounds.top + trackBounds.bottom) / 2 - pageCenter) > 1
      || Math.abs((lyricBounds.top + lyricBounds.bottom) / 2 - pageCenter) > 1)
    throw new Error('Lyrics layout did not center wide columns or stack narrow content');
  if (coverBounds.width !== (narrowLyrics ? 64 : 320) || coverBounds.height !== coverBounds.width
    || getComputedStyle(lyricsPage.querySelector('h2')!).fontSize !== '22px')
    throw new Error('Lyrics responsive cover size or fixed title size changed');
  if (window.innerWidth >= 1200 && coverBounds.left - pageBounds.left < 100)
    throw new Error('Lyrics cover shifted left instead of centering within its column');
  const lyricsClose=document.querySelector<HTMLButtonElement>('.lyrics-close')!;
  if(document.activeElement!==lyricsClose || !document.querySelector('.library-layer')?.hasAttribute('inert'))
    throw new Error('Lyrics page did not move focus or disable the covered library');
  if (getComputedStyle(document.querySelector('.library-layer')!).visibility !== 'hidden')
    throw new Error('Covered library controls still contribute CEF drag exclusion regions');
  const controlsAfter=controls.getBoundingClientRect();
  if(!playerTrack.hasAttribute('inert') || getComputedStyle(playerTrack).visibility!=='hidden' ||
    Math.abs(controlsBefore.x-controlsAfter.x)>1 || Math.abs(controlsBefore.y-controlsAfter.y)>1)
    throw new Error('Lyrics page did not hide track metadata while preserving player controls');
  for(const control of Array.from(document.querySelectorAll('.lyrics-close,.lyrics-track button,.lyrics-lines li.lyric-interactive,.lyrics-message button')))
    if(getComputedStyle(control).getPropertyValue('-webkit-app-region').trim()!=='no-drag')
      throw new Error('Lyrics controls overlap native draggable regions');
  const lyricsProbe = document.createElement('ol'); lyricsProbe.className = 'lyrics-lines';
  lyricsProbe.innerHTML = '<li><button>歌词拖动区域检查</button></li>'.repeat(20);
  lyricArea.replaceWith(lyricsProbe);
  const stopObserving = observeLyricDragRegions(lyricsProbe);
  try {
    const first = lyricsProbe.firstElementChild!;
    await waitFor(() => first.classList.contains('lyric-interactive'), 'Visible lyric row did not become interactive');
    if (getComputedStyle(lyricsProbe).getPropertyValue('-webkit-app-region').trim() !== 'drag'
      || getComputedStyle(first).getPropertyValue('-webkit-app-region').trim() !== 'no-drag')
      throw new Error('Lyrics blank space blocks dragging or lyric rows block interaction');
    lyricsProbe.scrollTop = 500;
    await waitFor(() => !first.classList.contains('lyric-interactive'), 'Offscreen lyric row still blocks dragging');
    const bounds = lyricsProbe.getBoundingClientRect();
    for (const row of Array.from(lyricsProbe.querySelectorAll('.lyric-interactive'))) {
      const rect = row.getBoundingClientRect();
      if (rect.top < bounds.top || rect.bottom > bounds.bottom)
        throw new Error('Clipped lyric row still blocks dragging');
    }
    lyricsProbe.scrollTop = 0;
    await waitFor(() => first.classList.contains('lyric-interactive'), 'Returning lyric row lost interaction');
  } finally { stopObserving(); lyricsProbe.replaceWith(lyricArea); }
  const menuOverlay = document.createElement('div'); menuOverlay.dataset.slot = 'popover-content';
  document.body.append(menuOverlay);
  if (getComputedStyle(lyricsPage).getPropertyValue('-webkit-app-region').trim() !== 'no-drag')
    throw new Error('Lyrics dragging blocks menu dismissal');
  menuOverlay.remove();
  const dialogOverlay = document.createElement('div'); dialogOverlay.setAttribute('role', 'dialog');
  document.body.append(dialogOverlay);
  if (getComputedStyle(lyricsPage).getPropertyValue('-webkit-app-region').trim() !== 'no-drag')
    throw new Error('Lyrics dragging blocks a modal dialog');
  dialogOverlay.remove();
  const handledEscape=new KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true});
  handledEscape.preventDefault();lyricsClose.dispatchEvent(handledEscape);
  if(!document.querySelector('.lyrics-page'))throw new Error('Handled Escape closed the lyrics page');
  lyricsClose.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));
  await waitFor(()=>!document.querySelector('.lyrics-page') && !document.querySelector('.library-layer')?.hasAttribute('inert'));
  if (getComputedStyle(document.querySelector('.library-layer')!).visibility !== 'visible')
    throw new Error('Closing lyrics did not restore library visibility');
  if(playerTrack.hasAttribute('inert') || getComputedStyle(playerTrack).visibility!=='visible')
    throw new Error('Closing lyrics did not restore player track metadata');
  const host = window as unknown as CefTransport;
  // test.complete intentionally has a test-only, non-v1 reply in the existing fixture.
  await new Promise<void>((resolve, reject) => host.cefQuery?.({
    request: JSON.stringify({ version: 1, command: 'test.complete', params: {} }), persistent: false,
    onSuccess: () => resolve(), onFailure: (_code, message) => reject(new Error(message))
  }));
}
