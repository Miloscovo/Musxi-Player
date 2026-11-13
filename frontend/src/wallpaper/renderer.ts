import { createScene, httpSource, type SceneInstance } from 'webwallgl';
import './renderer.css';

let scene: SceneInstance | undefined, video: HTMLVideoElement | undefined;
let initialized = false, running = true, failed = false;
const report = (status: string, reason?: string) => parent.postMessage({ channel: 'musxi-wallpaper', status, reason }, '*');
let fps = 60, quality = 'standard', sceneDpr = 0;
const renderDpr = () => quality === 'high' ? devicePixelRatio : Math.min(devicePixelRatio, 1, 1920 / Math.max(innerWidth, innerHeight, 1));
function updateDpr() { const dpr=renderDpr();if(scene && !failed && dpr!==sceneDpr){sceneDpr=dpr;scene.setRenderDpr(dpr);} }
function settings(data: { fps?: unknown; quality?: unknown }) {
  if(failed)return;
  const oldQuality = quality;
  fps = data.fps === 30 ? 30 : 60;quality = data.quality === 'high' ? 'high' : 'standard';
  scene?.setFps(fps);if(oldQuality !== quality)updateDpr();
}
function playback() {
  const active = running && !document.hidden;
  if (active && !failed) { scene?.resume(); if (video) void video.play().catch(() => fail('video')); }
  else { scene?.pause(); video?.pause(); }
}
function fail(reason = 'scene') {
  if (failed) return;
  failed = true; report('failed', reason); scene?.destroy({ releasePkgCache: true }); video?.pause();
}
window.addEventListener('message', event => {
  if (event.source !== parent || event.data?.channel !== 'musxi-wallpaper') return;
  if (event.data.action === 'playback') { running = event.data.running === true; playback(); return; }
  if (event.data.action === 'settings') { settings(event.data);return; }
  if (event.data.action !== 'init' || initialized) return;
  initialized = true;
  settings(event.data);
  const container = document.getElementById('wallpaper')!;
  const base = new URL('./assets/', location.href).href;
  if (event.data.type === 'video' && typeof event.data.file === 'string') {
    video = document.createElement('video'); video.muted = true; video.defaultMuted = true; video.loop = true; video.playsInline = true;
    video.addEventListener('volumechange', () => { if (video) { video.muted = true; video.volume = 0; } });
    video.addEventListener('error', () => fail(video?.error?.code === MediaError.MEDIA_ERR_NETWORK ? 'missing' : 'video'));
    video.addEventListener('loadeddata', () => { report('ready'); playback(); }, { once: true });
    const url = new URL(event.data.file.replaceAll('\\', '/'), base);
    if (!url.href.startsWith(base)) { fail('missing'); return; }
    video.src = url.href; container.append(video); video.load();
  } else if (event.data.type === 'scene') {
    try {
      const zeros = new Float32Array(64);
      sceneDpr=renderDpr();scene = createScene(container, {
        fit: 'cover', fps, renderDpr: sceneDpr, volume: 0, scripts: true,
        audio: { snapshot: () => ({ left: zeros, right: zeros }) }, media: null,
        onReady: () => { report('ready'); playback(); }, onError: error => fail(error.name === 'ContextLostError' ? 'context' : 'scene'),
        onDiagnostic: (_message, level) => { if (level === 'warn') report('warning'); },
      });
      void scene.load(httpSource(base)).catch(() => fail());
    } catch { fail(); }
  } else fail();
});
document.addEventListener('visibilitychange', playback);
window.addEventListener('resize', updateDpr);
window.addEventListener('pagehide', () => { scene?.destroy({ releasePkgCache: true }); video?.pause(); });
report('loaded');
