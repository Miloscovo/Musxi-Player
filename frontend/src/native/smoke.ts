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
import type { useLibrary } from '../composables/useLibrary';

async function waitFor(check: () => boolean, message = 'Event/UI update timed out') {
  const end = Date.now() + 3000;
  while (!check()) {
    if (Date.now() > end) throw new Error(message);
    await new Promise(resolve => setTimeout(resolve, 20));
  }
}

// Invoked only by the real CEF --cef-smoke run (cloud startup is disabled).
export async function verifyWebPage() {
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
  const playlist = { ...song, id: 'smoke-playlist', name: '我喜欢', editable: true, count: 1 };
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
    state: { ...library, connected: true, playlists: [playlist], tracks: [song], trackCount: 1,
      playlistId: playlist.id, playlistName: playlist.name,
      menu: { id: song.id, liked: false, canFavorite: true, playlists: [playlist] } },
    error: '', pending: false, page: 1, open: async () => {},
    menu: async (id: string) => { actions.push(`menu:${id}`); },
    favorite: async (id: string, enabled: boolean) => { actions.push(`favorite:${id}:${enabled}`); },
    add: async (id: string, list: string) => { actions.push(`add:${id}:${list}`); },
    importLocal: async () => { actions.push('importLocal'); },
    playPlaylist: async id => { actions.push(`playlistPlay:${id}`); },
    setPlaybackOrder: async order => { actions.push(`order:${order}`); },
    qualities: async id => { actions.push(`qualities:${id}`); },
    setQuality: async (id,quality) => { actions.push(`quality:${id}:${quality}`); },
    queueNext: async (source, id) => { actions.push(`next:${source}:${id}`); },
    queueRemove: async id => { actions.push(`remove:${id}`); },
    queueClear: async () => { actions.push('clearQueue'); },
    loadNextPage: async () => false, refresh: async () => true,
    search: async () => {}, skip: async () => {}, login: async () => {}, logout: async () => {}, sync: async () => {}, cancel: async () => {},
    play: async (source, id) => { actions.push(`play:${id}`, `play:${source}:${id}`); },
  };
  try {
    root.render(createElement(LibraryView, { library: fixtureLibrary, theme: 'light', onTheme: async () => {} }));
    await waitFor(() => !!fixture.querySelector('.playlist'));
    checkPlaylistText(fixture.querySelector('.playlist')!);
    fixture.querySelector<HTMLButtonElement>('.playlist')!.click();
    await waitFor(() => !!fixture.querySelector('.song.track-row'));
    const playlistPlay = fixture.querySelector<HTMLButtonElement>('[aria-label="播放歌单"]')!;
    const previousTheme = document.documentElement.getAttribute('data-theme');
    try {
      for (const theme of ['dark', 'glass']) {
        document.documentElement.setAttribute('data-theme', theme);
        const style = getComputedStyle(playlistPlay);
        if (style.backgroundColor !== 'rgb(10, 124, 103)' || style.color !== 'rgb(255, 255, 255)')
          throw new Error(`Playlist play button colors changed in ${theme}`);
      }
    } finally {
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
    fixture.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerType: 'mouse', button: 0 }));
    await waitFor(() => !document.querySelector('[data-slot=context-menu-content]'));
    fixture.querySelector<HTMLButtonElement>('.sidebar button:nth-of-type(3)')!.click();
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
    root.render(createElement(LibraryView, { library: fixtureLibrary, theme: 'light', onTheme: async () => {} }));
    await waitFor(() => Array.from(fixture.querySelectorAll('.playlist')).some(button=>button.textContent?.includes(localPlaylist.name)), 'Local folder card did not render');
    checkPlaylistText(Array.from(fixture.querySelectorAll('.playlist')).find(button=>button.textContent?.includes(localPlaylist.name))!);
    if(fixture.querySelector('.track-table'))throw new Error('Local home still shows the song table');
    const localCards=fixture.querySelectorAll<HTMLButtonElement>('.local-import-grid .playlist');
    if(!localCards[0].classList.contains('local-all') || !localCards[localCards.length-1].classList.contains('local-import'))
      throw new Error('Local all/import cards are not first/last');
    localCards[0].click();
    await waitFor(() => !!fixture.querySelector('.song.track-row'), 'All-local playlist did not show songs');
    if(fixture.querySelector('h1')?.textContent !== '本地歌曲整合')throw new Error('All-local playlist title is incorrect');
    fixture.querySelector<HTMLElement>('.song.track-row')!.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
    if(!actions.includes(`play:local:${song.id}`))throw new Error('All-local song playback failed');
    fixture.querySelector<HTMLButtonElement>('.back-button')!.click();
    await waitFor(() => !!fixture.querySelector('.local-all'));
    Array.from(fixture.querySelectorAll<HTMLButtonElement>('.playlist')).find(button=>button.textContent?.includes(localPlaylist.name))!.click();
    fixtureLibrary.state={...fixtureLibrary.state!,playlistId:localPlaylist.id,playlistName:localPlaylist.name};
    root.render(createElement(LibraryView, { library: fixtureLibrary, theme: 'light', onTheme: async () => {} }));
    await waitFor(() => !!fixture.querySelector('.song.track-row'), 'Local song did not render');
    fixture.querySelector<HTMLElement>('.song.track-row')!.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
    if (!actions.includes(`play:local:${song.id}`)) throw new Error('Local double-click did not play offline');
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
    fixtureLibrary.state={...fixtureLibrary.state!,busy:false,now:{...fixtureLibrary.state!.now,liked:true,canFavorite:true},menu:{id:song.id,liked:true,canFavorite:true,playlists:[playlist]}};
    const renderPlayerActions = (id: string) => root.render(createElement('section', { className:'player', style:{position:'absolute',bottom:0,right:0,left:0} },
      createElement(PlayerActions,{library:fixtureLibrary,currentId:id,onNotice:text=>actionNotices.push(text),children:null})));
    renderPlayerActions(song.id);
    await waitFor(() => !!fixture.querySelector('.player-favorite'), 'Player actions did not render');
    const favorite=fixture.querySelector<HTMLButtonElement>('.player-favorite')!;
    if(getComputedStyle(favorite.querySelector('svg')!).fill!=='rgb(243, 75, 80)')throw new Error('Favorite heart is not filled red');
    favorite.click();
    await waitFor(() => actions.includes(`favorite:${song.id}:false`));
    fixture.querySelector<HTMLButtonElement>('[aria-label="添加当前歌曲到歌单"]')!.click();
    await waitFor(() => !!document.querySelector('.player-action-menu'), 'Player add menu did not open');
    const addMenu=document.querySelector<HTMLElement>('.player-action-menu')!;
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
    fixtureLibrary.state={...fixtureLibrary.state!,qualities:{id:song.id,current:'128',options:[{id:'128',name:'标准 · 128 kbps'},{id:'320',name:'高品质 · 320 kbps'}]}};
    renderPlayerActions(song.id);
    await new Promise(resolve=>setTimeout(resolve,25));
    fixture.querySelector<HTMLButtonElement>('[aria-label="音质选择"]')!.click();
    await waitFor(() => !!document.querySelector('.player-action-menu'));
    const qualityMenu=document.querySelector<HTMLElement>('.player-action-menu')!;
    if(qualityMenu.dataset.side!=='top' || qualityMenu.getBoundingClientRect().bottom-qualityMenu.lastElementChild!.getBoundingClientRect().bottom>12)
      throw new Error('Quality menu is not compact and above the trigger');
    qualityMenu.querySelectorAll<HTMLButtonElement>('button')[1].click();
    await waitFor(() => actions.includes(`quality:${song.id}:320`));
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
  await waitFor(() => document.querySelectorAll('.theme-option').length === 3);
  const cards = document.querySelectorAll<HTMLButtonElement>('.theme-option');
  // Color changes preserve transparency; normalize a persisted glass theme first.
  if (cards[2].getAttribute('aria-pressed') === 'true') {
    cards[2].click();
    await waitFor(() => cards[2].getAttribute('aria-pressed') === 'false');
  }
  cards[0].click();
  await waitFor(() => document.documentElement.dataset.theme === 'light');
  cards[2].click();
  await waitFor(() => document.documentElement.dataset.theme === 'glass-light' && cards[0].getAttribute('aria-pressed') === 'true' && cards[2].getAttribute('aria-pressed') === 'true');
  cards[1].click();
  await waitFor(() => document.documentElement.dataset.theme === 'glass' && cards[1].getAttribute('aria-pressed') === 'true');
  cards[2].click();
  await waitFor(() => document.documentElement.dataset.theme === 'dark');
  cards[0].click();
  await waitFor(() => document.documentElement.dataset.theme === 'light');
  const state = await native.player.getState();
  const windowState = await native.window.getState();
  if (windowState.enabled) {
    document.querySelector<HTMLButtonElement>('.window-close')!.click();
    await waitFor(() => !!document.querySelector('[data-slot=dialog-content]'));
    const dialog = document.querySelector<HTMLElement>('[data-slot=dialog-content]')!;
    const buttons = dialog.querySelectorAll<HTMLButtonElement>('[data-slot=button]');
    if (!dialog.textContent?.includes('是否把 Musxi Player 最小化到托盘？')
      || buttons.length !== 2 || buttons[0].textContent !== '退出' || buttons[1].textContent !== '最小化'
      || document.activeElement !== buttons[1]) throw new Error('Incorrect shadcn close confirmation');
    dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await waitFor(() => !document.querySelector('[data-slot=dialog-content]'));
    const slider = document.querySelector<HTMLInputElement>('.transparency-slider');
    if (!slider || slider.min !== '0' || slider.max !== '100') throw new Error('Missing transparency range');
    const saved = slider.value;
    cards[2].click();
    await waitFor(() => document.documentElement.dataset.theme === 'glass-light');
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
          if (Math.abs(Number(await pixel(x, y)) - expected) > 1)
            throw new Error('Header transparency differs from page background');
        }
        // Preview swatches are opaque controls even when the page background is not.
        const preview = document.querySelector('.theme-swatch')!.getBoundingClientRect();
        if (await pixel(Math.round(preview.left + 10), Math.round(preview.top + 40)) !== 255)
          throw new Error('Transparency faded controls');
      }
    }
    setRange(Number(saved));
    cards[0].click();
    await waitFor(() => document.documentElement.dataset.theme === 'glass-light');
    cards[2].click();
    await waitFor(() => document.documentElement.dataset.theme === 'light');
    const maximized = await native.window.maximize();
    if (!maximized.maximized) throw new Error('Test window did not maximize');
    const restored = await native.window.maximize();
    if (restored.maximized) throw new Error('Test window did not restore');
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
  const host = window as unknown as CefTransport;
  // test.complete intentionally has a test-only, non-v1 reply in the existing fixture.
  await new Promise<void>((resolve, reject) => host.cefQuery?.({
    request: JSON.stringify({ version: 1, command: 'test.complete', params: {} }), persistent: false,
    onSuccess: () => resolve(), onFailure: (_code, message) => reject(new Error(message))
  }));
}
