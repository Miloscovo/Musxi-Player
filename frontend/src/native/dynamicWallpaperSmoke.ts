import { native } from './client';
import { request, type CefTransport } from './transport';
import { wallpaperOrigin } from './dynamicWallpaper';
import { createElement } from 'react';
import { createRoot } from 'react-dom/client';
import AppearanceSettings from '../components/AppearanceSettings';
import LyricsView from '../views/LyricsView';
import { lyricsBackgroundKey } from '../composables/lyricsBackground';
import { playerStyleKey } from '../composables/playerStyle';
import { useDynamicWallpaper, type DynamicWallpaperController } from '../composables/useDynamicWallpaper';
import DynamicWallpaperLayer from '../components/DynamicWallpaperLayer';
async function waitFor(check: () => boolean, message: string, timeout = 15000) {
  const until = Date.now() + timeout;
  while (!check()) { if (Date.now() > until) throw new Error(message); await new Promise(resolve => setTimeout(resolve, 20)); }
}
const button = (scope: string, text: string) => Array.from(document.querySelectorAll<HTMLButtonElement>(`${scope} button`)).find(item => item.textContent?.trim() === text)!;
function verifyTransparency(page: HTMLElement) {
  const root = document.documentElement, theme = root.dataset.theme;
  const opacity = root.style.getPropertyValue('--background-opacity');
  const playerOpacity = root.style.getPropertyValue('--player-background-opacity');
  const badge = document.querySelector('.desktop-lyrics-toggle')!, pressed = badge.getAttribute('aria-pressed')!;
  const canvas = document.createElement('canvas');canvas.width = canvas.height = 1;
  const context = canvas.getContext('2d')!;
  const alpha = (style: CSSStyleDeclaration) => {
    context.clearRect(0,0,1,1);context.fillStyle = style.backgroundColor;context.fillRect(0,0,1,1);
    return context.getImageData(0,0,1,1).data[3];
  };
  try {
    badge.setAttribute('aria-pressed','true');
    for(const mode of ['glass-light','glass'])for(const value of [1,0.5,0]) {
      root.dataset.theme = mode;root.style.setProperty('--background-opacity',String(value));
      root.style.setProperty('--player-background-opacity',String(value));
      const mask = getComputedStyle(document.querySelector('.dynamic-wallpaper-layer')!,'::after');
      if(Math.abs(alpha(mask)-255*0.2*value)>1 || Math.abs(alpha(getComputedStyle(page))-255*0.45*value)>1)
        throw new Error(`${mode} dynamic wallpaper or lyrics background ignored transparency ${value}`);
      if(value===0 && [document.querySelector('.library-content')!,document.querySelector('.sidebar')!].some(element=>alpha(getComputedStyle(element))!==0))
        throw new Error(`${mode} retained a background at full transparency`);
      context.clearRect(0,0,1,1);context.fillStyle = getComputedStyle(badge.querySelector('text')!).fill;context.fillRect(0,0,1,1);
      if(context.getImageData(0,0,1,1).data[3]!==255)throw new Error(`${mode} active lyrics badge text faded with the player background`);
    }
  } finally {
    badge.setAttribute('aria-pressed',pressed);
    if(theme===undefined)delete root.dataset.theme;else root.dataset.theme = theme;
    if(opacity)root.style.setProperty('--background-opacity',opacity);else root.style.removeProperty('--background-opacity');
    if(playerOpacity)root.style.setProperty('--player-background-opacity',playerOpacity);else root.style.removeProperty('--player-background-opacity');
  }
}
async function verifyLyricsBackground() {
  const saved = [lyricsBackgroundKey, playerStyleKey].map(key => [key, localStorage.getItem(key)] as const);
  const fixture = document.createElement('div');
  fixture.className = 'has-dynamic-wallpaper';document.body.append(fixture);
  const root = createRoot(fixture);
  const canvas = document.createElement('canvas');canvas.width = canvas.height = 2;
  canvas.getContext('2d')!.fillRect(0, 0, 2, 2);
  const image = canvas.toDataURL();
  const render = (cover: string) => root.render(createElement('div', null,
    createElement(AppearanceSettings, {appearance:{mode:'light',tone:'green',transparent:false},onAppearance:()=>{},transparency:50,onTransparency:()=>{},dynamicWallpaperEnabled:true}),
    createElement(LyricsView, {trackId:'',now:{name:'Smoke',artist:'',cover},fallbackCover:image,positionMs:0,playing:false,canSeek:false,
      onSeek:()=>{},onClose:()=>{},onEntered:()=>{},bottomOffset:0,onExpandPlayer:()=>{},onPlayerCover:()=>{}})));
  const page = () => fixture.querySelector<HTMLElement>('.lyrics-page')!;
  const choice = (index: number) => fixture.querySelectorAll<HTMLButtonElement>('[aria-labelledby=lyrics-background-label] button')[index];
  try {
    localStorage.setItem(lyricsBackgroundKey, 'main');localStorage.setItem(playerStyleKey, 'square');
    render('smoke-cover');
    await waitFor(() => !!choice(1) && !!fixture.querySelector<HTMLImageElement>('.lyrics-square-cover img')?.complete, 'Lyrics fixture did not load');
    if(choice(0).disabled || choice(1).disabled)throw new Error('Dynamic wallpaper disabled lyrics background selection');
    choice(1).click();
    await waitFor(() => !!page().dataset.coverBackground && localStorage.getItem(lyricsBackgroundKey)==='cover', 'Cover background did not override dynamic wallpaper');
    if(getComputedStyle(page()).backgroundColor!=='rgb(19, 19, 19)')throw new Error('Dynamic wallpaper styles overrode the cover background');
    choice(0).click();
    await waitFor(() => !page().dataset.coverBackground && localStorage.getItem(lyricsBackgroundKey)==='main', 'Lyrics did not return to the main background');
    verifyTransparency(page());
    choice(1).click();
    await waitFor(() => !!page().dataset.coverBackground, 'Cover background could not be selected again');
    render('');
    await waitFor(() => !page().dataset.coverBackground, 'Missing cover did not fall back to the main background');
    if(localStorage.getItem(lyricsBackgroundKey)!=='cover')throw new Error('Missing cover erased the background preference');
  } finally {
    root.unmount();fixture.remove();
    for(const [key,value] of saved){if(value===null)localStorage.removeItem(key);else localStorage.setItem(key,value);window.dispatchEvent(new Event(key));}
  }
}
async function verifyWallpaperRecovery() {
  const fixture=document.createElement('div');document.body.append(fixture);
  const root=createRoot(fixture);let controller:DynamicWallpaperController|undefined,compact=false;
  function Harness(){controller=useDynamicWallpaper(compact);return createElement(DynamicWallpaperLayer,{wallpaper:controller,compact});}
  const render=()=>root.render(createElement(Harness));
  const frame=()=>fixture.querySelector('iframe');
  const timeout=window.setTimeout;
  let running:boolean|undefined;
  const playback=(event:MessageEvent)=>{if(event.origin===wallpaperOrigin && event.source===frame()?.contentWindow && event.data?.channel==='musxi-wallpaper-playback-test')running=event.data.running;};
  window.addEventListener('message',playback);
  try {
    render();await waitFor(()=>!!controller?.loaded,'Recovery controller did not load');
    await controller!.setEnabled(true);
    await waitFor(()=>!!controller?.items.length && !controller.busy,'Recovery fixtures did not scan');
    let rejected=false;
    try { await request(window as unknown as CefTransport,'dynamicWallpaper.setPlayback',{fps:120,quality:'high'}); } catch { rejected=true; }
    if(!rejected || (await native.dynamicWallpaper.getState()).fps!==60)throw new Error('Native bridge accepted an invalid FPS');
    await controller!.startPreview(controller!.items.find(item=>item.title==='Musxi Scene Smoke')!);
    await waitFor(()=>!!controller?.preview?.ready,'Recovery scene did not load');
    await controller!.apply();await waitFor(()=>!!controller?.current?.ready,'Recovery scene did not apply');
    running=undefined;
    await controller!.startPreview(controller!.items.find(item=>item.title==='Musxi Video Smoke')!);
    await waitFor(()=>running===false,'Original scene kept running while preview loaded');
    controller!.cancel();await waitFor(()=>running===true && !controller?.preview,'Cancel did not resume the original scene');
    controller!.status(controller!.current!.url,'failed','context');
    await waitFor(()=>!controller?.current && !!controller?.canRetry,'Context failure did not offer retry');
    if(!controller!.error.includes('图形渲染'))throw new Error('Context failure lost its reason');
    await controller!.retry();await waitFor(()=>!!controller?.current?.ready,'Manual retry did not rebuild the scene');
    if(controller!.error || controller!.canRetry)throw new Error('Successful retry retained an error');
    controller!.status(controller!.current!.url,'failed','timeout');
    await waitFor(()=>!controller?.current,'Failed current scene was not released');
    await controller!.refresh();await waitFor(()=>!!controller?.current?.ready,'Refresh did not recover the saved scene');
    const original=frame();let expire:(()=>void)|undefined;
    window.setTimeout=((handler:TimerHandler,delay?:number,...args:unknown[])=>{
      if(delay===60000 && typeof handler==='function')expire=()=>handler(...args);
      return timeout(handler,delay,...args);
    }) as typeof window.setTimeout;
    compact=true;render();await waitFor(()=>!!expire && !!fixture.querySelector('[hidden]'),'Hidden suspension was not scheduled');
    if(frame()!==original)throw new Error('Short hiding discarded the scene');
    compact=false;render();await waitFor(()=>!fixture.querySelector('[hidden]'),'Short hide did not resume');
    if(frame()!==original)throw new Error('Short hiding remounted the scene');
    expire=undefined;compact=true;render();await waitFor(()=>!!expire,'Long hiding timer missing');
    expire!();window.setTimeout=timeout;
    await waitFor(()=>!!controller?.suspended && !frame(),'Long hiding did not release resources');
    compact=false;render();await waitFor(()=>!!controller?.current?.ready && !controller.suspended,'Showing did not restore released resources');
    if(frame()===original)throw new Error('Released scene was not rebuilt');
  } finally {
    window.removeEventListener('message',playback);
    window.setTimeout=timeout;
    if(controller?.settings.enabled)await controller.setEnabled(false);
    root.unmount();fixture.remove();
  }
}
export async function verifyDynamicWallpaperUI() {
  const host = window as unknown as CefTransport;
  if ((await native.dynamicWallpaper.getState()).enabled || document.querySelector('.dynamic-wallpaper-layer')) throw new Error('Wallpaper engine started while disabled');
  await request(host, 'dynamicWallpaper.testFixtures', {});
  document.querySelector<HTMLButtonElement>('[aria-label="扩展"]')!.click();
  await waitFor(() => !!document.querySelector('.wallpaper-extension-card [role=switch]:not(:disabled)'), 'Wallpaper switch missing');
  const toggle = () => document.querySelector<HTMLButtonElement>('.wallpaper-extension [role=switch]')!;
  toggle().click();
  await waitFor(() => !button('.wallpaper-extension', '管理壁纸')?.disabled, 'Wallpaper discovery did not finish');
  button('.wallpaper-extension', '管理壁纸').click();
  const project = (title: string) => Array.from(document.querySelectorAll<HTMLButtonElement>('.wallpaper-project')).find(item => item.querySelector('strong')?.textContent === title);
  await waitFor(() => !!project('Musxi Scene Smoke') && !!project('Musxi Video Smoke'), 'Scene/video fixtures were not discovered');
  if(document.querySelector('.library-content>header') || document.querySelector('.wallpaper-extension [role=switch]')
    || !document.querySelector('.wallpaper-manager-heading .back-button[aria-label="返回扩展"]'))throw new Error('Wallpaper manager retained extension heading or toggle, or lost its back arrow');
  if(document.querySelector('[aria-label="壁纸类型"]') || Array.from(document.querySelectorAll('.wallpaper-project>span')).some(item=>item.hasAttribute('title') || !/^(Scene|Video)( · 已应用)?$/.test(item.textContent ?? '')))
    throw new Error('Wallpaper manager retained type filtering or card source information');
  button('.wallpaper-manager-heading','播放设置').click();
  await waitFor(()=>!!document.querySelector('.wallpaper-playback-dialog'),'Playback dialog did not open');
  const setting=(text:string)=>button('.wallpaper-playback-dialog',text);
  setting('30 FPS').click();
  await waitFor(()=>setting('30 FPS').getAttribute('aria-pressed')==='true' && !setting('30 FPS').disabled,'FPS setting was not saved');
  setting('高清').click();
  await waitFor(()=>setting('高清').getAttribute('aria-pressed')==='true' && !setting('高清').disabled,'Quality setting was not saved');
  const saved=await native.dynamicWallpaper.getState();if(saved.fps!==30 || saved.quality!=='high')throw new Error('Playback settings did not reach native storage');
  setting('恢复默认').click();
  await waitFor(()=>setting('60 FPS（默认）').getAttribute('aria-pressed')==='true' && setting('标准（默认）').getAttribute('aria-pressed')==='true','Playback defaults were not restored');
  setting('完成').click();
  await waitFor(()=>!document.querySelector('.wallpaper-playback-dialog'),'Playback dialog did not close');
  const preview = (title: string) => project(title)?.querySelector<HTMLImageElement>('.wallpaper-project-image img');
  for(const title of ['Musxi Scene Smoke','Musxi Video Smoke']) {
    project(title)!.scrollIntoView({block:'center'});
    await waitFor(() => !!preview(title)?.complete && preview(title)!.naturalWidth===160 && !preview(title)!.hidden, `${title} animated thumbnail was not decoded`);
  }
  project('Musxi Broken Smoke')!.scrollIntoView({block:'center'});
  await waitFor(() => preview('Musxi Broken Smoke')?.hidden===true, 'Broken thumbnail did not settle');
  if(project('Musxi Broken Smoke')!.querySelector('.wallpaper-project-image span')?.textContent!=='暂无预览')throw new Error('Missing thumbnail fallback');
  const brokenImage=preview('Musxi Broken Smoke');button('.wallpaper-library-toolbar','刷新').click();
  await waitFor(()=>preview('Musxi Broken Smoke')!==brokenImage && !button('.wallpaper-library-toolbar','刷新').disabled,'Refresh did not retry a failed thumbnail');
  let scriptIsolated = false;
  const scriptReport = (event: MessageEvent) => {
    if(event.origin===wallpaperOrigin && event.source===document.querySelector<HTMLIFrameElement>('.dynamic-wallpaper-layer iframe')?.contentWindow
      && event.data?.channel==='musxi-wallpaper-test')scriptIsolated=event.data.bridge===false && event.data.parentReadable===false;
  };
  window.addEventListener('message',scriptReport);
  try {
    project('Musxi Scene Smoke')!.click();
    await waitFor(() => project('Musxi Scene Smoke')?.getAttribute('aria-pressed') === 'true' && !button('.wallpaper-extension', '预览效果').disabled, 'Scene selection did not settle');
    button('.wallpaper-extension', '预览效果').click();
    await waitFor(() => !!document.querySelector('.has-dynamic-wallpaper'), 'Scene never produced a first frame');
    await waitFor(() => scriptIsolated, 'SceneScript did not run safely outside the player bridge');
    const canvas=document.createElement('canvas');canvas.width=canvas.height=1;const context=canvas.getContext('2d')!;
    context.fillStyle=getComputedStyle(document.querySelector('.library-content')!).backgroundColor;context.fillRect(0,0,1,1);
    if(context.getImageData(0,0,1,1).data[3]>200)throw new Error('Library background hides the dynamic wallpaper');
    if ((await native.dynamicWallpaper.getState()).selected) throw new Error('Preview committed before apply');
    const originalFrame = document.querySelector<HTMLIFrameElement>('.dynamic-wallpaper-layer iframe')!;
    let isolated = false; try { isolated = !originalFrame.contentWindow?.document; } catch { isolated = true; }
    if (!isolated) throw new Error('Wallpaper can share the player document');
    button('.wallpaper-preview-bar', '应用壁纸').click();
    await waitFor(() => !document.querySelector('.wallpaper-preview-bar'), 'Wallpaper apply did not finish');
    if (document.querySelector('.dynamic-wallpaper-layer iframe') !== originalFrame) throw new Error('Applying remounted the preview');
    if (!(await native.dynamicWallpaper.getState()).selected) throw new Error('Wallpaper selection was not saved');
    await verifyLyricsBackground();
    button('.wallpaper-extension', '管理壁纸').click();
    await waitFor(() => !!project('Musxi Video Smoke'), 'Wallpaper manager did not reopen');
    project('Musxi Broken Smoke')!.click();
    await waitFor(() => project('Musxi Broken Smoke')?.getAttribute('aria-pressed') === 'true', 'Broken fixture selection did not settle');
    button('.wallpaper-extension', '预览效果').click();
    await waitFor(() => !!project('Musxi Video Smoke') && !document.querySelector('.wallpaper-preview-bar') && !!button('.wallpaper-error','重试'), 'Broken preview did not return to manager with retry');
    if (document.querySelector('.dynamic-wallpaper-layer iframe') !== originalFrame || !document.querySelector('.has-dynamic-wallpaper')) throw new Error('Failed preview discarded the applied wallpaper');
    if(!button('.wallpaper-error','重试'))throw new Error('Failed preview did not offer retry');
    project('Musxi Video Smoke')!.click();
    await waitFor(() => project('Musxi Video Smoke')?.getAttribute('aria-pressed') === 'true', 'Video selection did not settle');
    button('.wallpaper-extension', '预览效果').click();
    await waitFor(() => document.querySelectorAll('.dynamic-wallpaper-layer iframe').length === 2
      && !button('.wallpaper-preview-bar', '应用壁纸')?.disabled, 'Video never became playable');
    button('.wallpaper-preview-bar', '取消').click();
    await waitFor(() => document.querySelectorAll('.dynamic-wallpaper-layer iframe').length === 1, 'Cancel did not release video');
    if (document.querySelector('.dynamic-wallpaper-layer iframe') !== originalFrame) throw new Error('Cancel replaced the applied wallpaper');
    document.querySelector<HTMLButtonElement>('[aria-label="返回扩展"]')!.click();
    await waitFor(() => !!document.querySelector('.wallpaper-extension-card [role=switch]') && !!document.querySelector('.extensions-title'), 'Back arrow did not restore the extension card');
    toggle().click();
    await waitFor(() => !document.querySelector('.dynamic-wallpaper-layer') && toggle().getAttribute('aria-checked') === 'false', 'Disabling left a renderer mounted');
    if (!(await native.dynamicWallpaper.getState()).selected) throw new Error('Disabling erased the saved choice');
    toggle().click();
    await waitFor(() => !!document.querySelector('.has-dynamic-wallpaper') && !toggle().disabled, 'Re-enabling did not restore the saved wallpaper');
    toggle().click();
    await waitFor(() => !document.querySelector('.dynamic-wallpaper-layer'), 'Restored wallpaper was not released');
  } finally { window.removeEventListener('message',scriptReport);await native.dynamicWallpaper.setEnabled(false); }
  await verifyWallpaperRecovery();
}
