import type { CSSProperties } from 'react';
import { Button } from './ui/button';
import { useLyricsDisplay } from '../composables/useLyricsDisplay';

export default function LyricsSettings() {
  const [settings, setSettings] = useLyricsDisplay();
  return <section className="settings-content lyrics-settings" aria-label="歌词设置">
    <div className="appearance-mode"><h3 id="lyrics-alignment-label">歌词对齐方式</h3>
      <div className="appearance-segments" role="group" aria-labelledby="lyrics-alignment-label">
        {(['left', 'center'] as const).map(alignment => <Button key={alignment} variant="unstyled" className="appearance-segment" aria-pressed={settings.alignment === alignment}
          onClick={() => setSettings({ ...settings, alignment })}>{alignment === 'left' ? '左对齐' : '居中对齐'}</Button>)}
      </div>
    </div>
    <div className="appearance-level">
      <div className="appearance-setting-row"><label htmlFor="lyrics-font-size">歌词字体大小</label><output htmlFor="lyrics-font-size">{settings.fontSize}px</output></div>
      <div className="lyrics-font-control"><span aria-hidden="true">A</span>
        <input id="lyrics-font-size" className="appearance-slider" type="range" min="16" max="32" step="1" value={settings.fontSize}
          style={{ '--level': `${(settings.fontSize - 16) / 16 * 100}%` } as CSSProperties} aria-valuetext={`${settings.fontSize}像素`}
          onChange={e => setSettings({ ...settings, fontSize: Number(e.currentTarget.value) })}/><span aria-hidden="true">A</span>
      </div>
    </div>
    <div className="lyrics-preview" aria-label="歌词预览" style={{ textAlign: settings.alignment, fontSize: settings.fontSize }}>
      <p>晚风轻轻吹过耳边</p><p className="current">让音乐陪你走远</p><p>每段旋律都值得留恋</p>
    </div>
  </section>;
}
