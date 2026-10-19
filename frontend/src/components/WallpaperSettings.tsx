import { Button } from './ui/button';
import type { WallpaperController } from '../composables/useWallpaper';

export default function WallpaperSettings({ wallpaper,transparent,enabled,onEnabled,playerTransparent,onPlayerTransparent }: {
  wallpaper:WallpaperController;transparent:boolean;enabled:boolean;onEnabled:(value:boolean)=>void;
  playerTransparent:boolean;onPlayerTransparent:(value:boolean)=>void;
}) {
  return <>
    <div className="appearance-wallpaper">
      <div className="appearance-setting-row">
        <div><h3 id="wallpaper-label">自定义静态壁纸</h3><p id="wallpaper-description">使用本地图片作为播放器背景</p></div>
        <Button variant="unstyled" className="appearance-switch" role="switch" aria-checked={enabled} aria-labelledby="wallpaper-label" aria-describedby="wallpaper-description"
          disabled={!transparent || !wallpaper.loaded || wallpaper.busy} onClick={()=>onEnabled(!enabled)}><span/></Button>
      </div>
      {!transparent && <p className="wallpaper-hint">预览时将临时开启透明效果</p>}
      <div className="wallpaper-manager">
        <div className="wallpaper-thumbnail">{wallpaper.thumbnail ? <img src={wallpaper.thumbnail.url} data-wallpaper-id={wallpaper.thumbnail.id} alt="当前选择的壁纸"/> : <span>尚未选择壁纸</span>}</div>
        <div className="wallpaper-actions">
          <Button variant="secondary" disabled={!wallpaper.loaded || wallpaper.busy || !wallpaper.thumbnail} onClick={wallpaper.preview}>预览效果</Button>
          <Button variant="secondary" disabled={!wallpaper.loaded || wallpaper.busy} onClick={()=>void wallpaper.choose()}>{wallpaper.busy?'正在处理…':wallpaper.thumbnail?'更换壁纸':'选择图片'}</Button>
          <Button variant="secondary" disabled={wallpaper.busy} onClick={()=>void wallpaper.reset()}>恢复默认</Button>
        </div>
      </div>
      {wallpaper.error && <p className="playback-settings-error" role="alert">{wallpaper.error}</p>}
    </div>
    <div className="appearance-wallpaper appearance-setting-row">
      <div><h3 id="player-transparency-label">播放栏透明</h3><p id="player-transparency-description">让播放栏背景与播放器自然融合</p></div>
      <Button variant="unstyled" className="appearance-switch" role="switch" aria-checked={playerTransparent} aria-labelledby="player-transparency-label" aria-describedby="player-transparency-description"
        disabled={!transparent || wallpaper.busy}
        onClick={()=>onPlayerTransparent(!playerTransparent)}><span/></Button>
    </div>
  </>;
}
