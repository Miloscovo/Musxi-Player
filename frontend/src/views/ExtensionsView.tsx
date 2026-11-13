import { useState } from 'react';
import { Button } from '../components/ui/button';
import type { DynamicWallpaperController } from '../composables/useDynamicWallpaper';
import wallpaperIcon from '../assets/wallpaper-engine.png?inline';
import { Dialog, DialogTrigger, DialogContent, DialogTitle, DialogDescription } from '../components/ui/dialog';

export default function ExtensionsView({ wallpaper: wp }: { wallpaper: DynamicWallpaperController }) {
  const [query, setQuery] = useState(''), [selected, setSelected] = useState('');
  const [playbackOpen, setPlaybackOpen] = useState(false);
  const chosen = wp.items.find(item => item.id === selected) ?? wp.items.find(item => item.selected);
  const items = wp.items.filter(item => item.title.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const error = wp.error && <div className="wallpaper-error"><p role="alert">{wp.error}</p>
    {wp.canRetry && <Button variant="secondary" disabled={wp.busy} onClick={() => void wp.retry()}>重试</Button>}</div>;
  const toggle = <Button variant="unstyled" className="appearance-switch" role="switch" aria-checked={wp.settings.enabled}
    aria-label="Wallpaper 动态壁纸" disabled={!wp.loaded || wp.busy} onClick={() => void wp.setEnabled(!wp.settings.enabled)}><span/></Button>;
  return <section className="extensions-content wallpaper-extension" aria-label="扩展">
    {wp.manager && wp.settings.enabled ? <>
      <div className="wallpaper-manager-heading"><Button variant="unstyled" className="back-button" aria-label="返回扩展" onClick={() => wp.setManager(false)}>
        <svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="m15 5-7 7 7 7" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg></Button>
        <img src={wallpaperIcon} alt=""/><h2>Wallpaper 动态壁纸</h2>
        <Dialog open={playbackOpen} onOpenChange={setPlaybackOpen}><DialogTrigger asChild><Button variant="secondary">播放设置</Button></DialogTrigger>
          <DialogContent className="wallpaper-playback-dialog"><DialogTitle>播放设置</DialogTitle>
            <DialogDescription>自动保存并应用于全部动态壁纸。帧率和清晰度仅影响 Scene，Video 保持视频自身帧率。</DialogDescription>
            <div className="appearance-mode"><h3 id="wallpaper-fps-label">帧率上限</h3>
              <div className="appearance-segments" role="group" aria-labelledby="wallpaper-fps-label">
                {([30,60] as const).map(fps => <Button key={fps} variant="unstyled" className="appearance-segment" aria-pressed={wp.settings.fps === fps} disabled={wp.busy}
                  onClick={() => void wp.setPlayback({fps,quality:wp.settings.quality})}>{fps} FPS{fps === 60 ? '（默认）' : ''}</Button>)}
              </div></div>
            <div className="appearance-mode"><h3 id="wallpaper-quality-label">渲染清晰度</h3>
              <div className="appearance-segments" role="group" aria-labelledby="wallpaper-quality-label">
                {(['standard','high'] as const).map(quality => <Button key={quality} variant="unstyled" className="appearance-segment" aria-pressed={wp.settings.quality === quality} disabled={wp.busy}
                  onClick={() => void wp.setPlayback({fps:wp.settings.fps,quality})}>{quality === 'standard' ? '标准（默认）' : '高清'}</Button>)}
              </div><p>标准：最长边不超过 1920 像素，降低高分屏开销。高清：跟随屏幕分辨率，最长边不超过 4096 像素。</p></div>
            <p>最小化、托盘或迷你模式持续 60 秒后释放壁纸资源，恢复主窗口时重新加载。</p>
            {wp.error && <p role="alert">{wp.error}</p>}
            <div className="wallpaper-dialog-actions"><Button variant="secondary" disabled={wp.busy || wp.settings.fps === 60 && wp.settings.quality === 'standard'} onClick={() => void wp.setPlayback({fps:60,quality:'standard'})}>恢复默认</Button>
              <Button onClick={() => setPlaybackOpen(false)}>完成</Button></div>
          </DialogContent></Dialog></div>
      <div className="wallpaper-current" role="status"><span>当前壁纸：{wp.current?.project.title || wp.items.find(item => item.selected)?.title || '尚未应用'}</span>
        <span>{wp.busy ? '正在读取资源…' : wp.suspended ? '已释放，恢复窗口后加载' : wp.current ? wp.current.ready ? wp.visible ? '运行中' : '已暂停' : '正在加载…' : wp.error ? '播放失败' : '等待选择'}</span></div>
      {error}
      {wp.current?.warning && <p className="wallpaper-hint">当前壁纸部分特效可能不受支持。</p>}
      <div className="wallpaper-library-toolbar"><input type="search" aria-label="搜索壁纸" placeholder="搜索壁纸…" value={query} onChange={event => setQuery(event.target.value)}/>
        <Button variant="secondary" disabled={wp.busy} onClick={() => void wp.refresh()}>刷新</Button>
        <Button variant="secondary" disabled={wp.busy} onClick={() => void wp.addDirectory()}>添加目录</Button></div>
      <details className="wallpaper-directories"><summary><span className="wallpaper-result-count" role="status">{wp.busy ? '正在读取资源…' : `共 ${wp.items.length} 张壁纸${items.length !== wp.items.length ? `，显示 ${items.length} 张` : ''}`}</span>
        <span className="wallpaper-directories-label">资源目录{wp.settings.directories.length ? `（${wp.settings.directories.length} 个手动目录）` : ''}
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg></span></summary>
        <p>自动发现本机 Steam 库中的 Wallpaper Engine 资源。移除手动目录不会删除文件。</p>
        {wp.settings.directories.map(directory => <div key={directory}><span title={directory}>{directory}</span>
          <Button variant="ghost" disabled={wp.busy} aria-label={`移除目录 ${directory}`} onClick={() => void wp.removeDirectory(directory)}>移除</Button></div>)}
        {wp.reports.map(report => <div className="wallpaper-scan-report" key={report.directory}><span title={report.directory}>{report.directory}</span>
          <small>{report.error || `发现 ${report.count} 张壁纸`}{report.invalid ? ` · ${report.invalid} 个项目信息无效` : ''}{report.unsupported ? ` · ${report.unsupported} 个类型不支持` : ''}{report.missing ? ` · ${report.missing} 个资源不完整` : ''}</small></div>)}
      </details>
      <div className="wallpaper-project-grid" aria-label="壁纸列表" aria-busy={wp.busy}>
        {items.map(item => <Button key={item.id} variant="unstyled" className="wallpaper-project" aria-pressed={chosen?.id === item.id}
          onClick={() => setSelected(item.id)}>
          <div className="wallpaper-project-image"><span>暂无预览</span>{item.preview && <img key={item.preview} src={item.preview} loading="lazy" alt="" onError={event => { event.currentTarget.hidden = true; }}/>}</div>
          <strong title={item.title}>{item.title}</strong><span>{item.type === 'scene' ? 'Scene' : 'Video'}{item.selected ? ' · 已应用' : ''}</span>
        </Button>)}
      </div>
      {!items.length && <p className="extensions-empty" role="status">{wp.busy ? '正在扫描壁纸…' : query ? '没有匹配的壁纸' : '未发现 Scene 或 Video 壁纸，请添加壁纸项目或壁纸库目录。'}</p>}
      <div className="wallpaper-selection"><span>已选：{chosen?.title || '尚未选择'}</span>
        <Button disabled={!chosen || wp.busy} onClick={() => chosen && void wp.startPreview(chosen)}>预览效果</Button></div>
    </> : <div className="wallpaper-extension-card">
      <img className="wallpaper-extension-icon" src={wallpaperIcon} alt="Wallpaper Engine"/>
      <div><h2>Wallpaper 动态壁纸</h2><p>使用本机 Wallpaper Engine 场景与视频作为播放器背景</p></div>{toggle}
      <p className="wallpaper-extension-status" role="status">{!wp.loaded ? '正在读取设置…' : wp.busy ? '正在读取资源…' : !wp.settings.enabled ? '未启用' : wp.preview ? wp.preview.ready ? '正在预览壁纸' : '正在加载壁纸…' : wp.current?.ready ? '已启用 · 已应用壁纸' : '已启用 · 等待选择壁纸'}</p>
      <Button variant="secondary" disabled={!wp.loaded || !wp.settings.enabled || wp.busy} onClick={() => wp.setManager(true)}>管理壁纸</Button>
    </div>}
    {!(wp.manager && wp.settings.enabled) && error}
  </section>;
}
