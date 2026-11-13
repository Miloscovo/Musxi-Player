import { useEffect, useRef } from 'react';
import { wallpaperOrigin } from '../native/dynamicWallpaper';
import type { RunningWallpaper, DynamicWallpaperController } from '../composables/useDynamicWallpaper';
import type { WallpaperPlayback } from '../native/dynamicWallpaper';
function WallpaperFrame({ value, running, playback, onStatus }: { value: RunningWallpaper; running: boolean; playback: WallpaperPlayback; onStatus: DynamicWallpaperController['status'] }) {
  const frame = useRef<HTMLIFrameElement>(null), latest = useRef({ running, playback, onStatus }); latest.current = { running, playback, onStatus };
  const send = (data: object) => frame.current?.contentWindow?.postMessage({ channel: 'musxi-wallpaper', ...data }, wallpaperOrigin);
  useEffect(() => {
    const message = (event: MessageEvent) => {
      if (event.origin !== wallpaperOrigin || event.source !== frame.current?.contentWindow || event.data?.channel !== 'musxi-wallpaper') return;
      const status = event.data.status;
      if (status === 'loaded') { send({ action: 'init', type: value.type, file: value.file, ...latest.current.playback }); send({ action: 'playback', running: latest.current.running }); }
      latest.current.onStatus(value.url, status, event.data.reason);
    };
    window.addEventListener('message', message);
    return () => window.removeEventListener('message', message);
  }, [value.url]);
  useEffect(() => {
    if (!running || value.ready) return;
    const timer = setTimeout(() => latest.current.onStatus(value.url, 'failed', 'timeout'), 60000);
    return () => clearTimeout(timer);
  }, [value.url, running, value.ready]);
  useEffect(() => { send({ action: 'playback', running }); }, [running]);
  useEffect(() => { send({ action: 'settings', fps: playback.fps, quality: playback.quality }); }, [playback.fps, playback.quality]);
  return <iframe ref={frame} src={value.url} title="动态壁纸" tabIndex={-1} sandbox="allow-scripts allow-same-origin" allow="autoplay"
    style={{ opacity: value.ready ? 1 : 0 }} />;
}
export default function DynamicWallpaperLayer({ wallpaper, compact }: { wallpaper: DynamicWallpaperController; compact: boolean }) {
  const running = wallpaper.visible && !compact;
  return <div className="dynamic-wallpaper-layer" aria-hidden="true" hidden={compact}>
    {[wallpaper.current, wallpaper.preview].filter((value): value is RunningWallpaper => !!value).map(value =>
      <WallpaperFrame key={value.url} value={value} playback={wallpaper.settings} running={running && (value === wallpaper.preview || !wallpaper.preview)} onStatus={wallpaper.status} />)}
  </div>;
}
