import { useState, type CSSProperties, type SyntheticEvent } from 'react';
import { Button } from './ui/button';
import CoverImage from './CoverImage';
import { sampleCoverColor } from '../composables/vinylColor';
import { useLyricsDisplay } from '../composables/useLyricsDisplay';

export default function LyricsSettings({ coverUrl = '' }: { coverUrl?: string }) {
  const [settings, setSettings] = useLyricsDisplay();
  const [sample, setSample] = useState({ url: '', color: '' });
  function sampleCover(event: SyntheticEvent<HTMLDivElement>) {
    if (event.target instanceof HTMLImageElement) setSample({ url: coverUrl, color: sampleCoverColor(event.target) });
  }
  const color = settings.colorMode === 'cover' && sample.url === coverUrl ? sample.color : '';
  return <section className="settings-content lyrics-settings" aria-label="歌词设置">
    <div className="appearance-mode"><h3 id="lyrics-alignment-label">歌词对齐方式</h3>
      <div className="appearance-segments" role="group" aria-labelledby="lyrics-alignment-label">
        {(['left', 'center'] as const).map(alignment => <Button key={alignment} variant="unstyled" className="appearance-segment" aria-pressed={settings.alignment === alignment}
          onClick={() => setSettings({ ...settings, alignment })}>{alignment === 'left' ? '左对齐' : '居中对齐'}</Button>)}
      </div>
    </div>
    <div className="appearance-mode"><h3 id="lyrics-color-label">歌词字体颜色</h3>
      <div className="appearance-segments" role="group" aria-labelledby="lyrics-color-label">
        {(['theme', 'cover'] as const).map(colorMode => <Button key={colorMode} variant="unstyled" className="appearance-segment" aria-pressed={settings.colorMode === colorMode}
          onClick={() => setSettings({ ...settings, colorMode })}>{colorMode === 'theme' ? '跟随主题色调' : '跟随歌曲封面'}</Button>)}
      </div>
    </div>
    <div className="appearance-level">
      <div className="appearance-setting-row"><label htmlFor="lyrics-font-size">歌词字体大小</label><output htmlFor="lyrics-font-size">{settings.fontSize}px</output></div>
      <input id="lyrics-font-size" className="appearance-slider" type="range" min="16" max="32" step="1" value={settings.fontSize}
        style={{ '--level': `${(settings.fontSize - 16) / 16 * 100}%` } as CSSProperties} aria-valuetext={`${settings.fontSize}像素`}
        onChange={e => setSettings({ ...settings, fontSize: Number(e.currentTarget.value) })}/>
    </div>
    {settings.colorMode === 'cover' && coverUrl && <div hidden aria-hidden="true" onLoadCapture={sampleCover}><CoverImage url={coverUrl}/></div>}
    <div className="lyrics-preview" aria-label="歌词预览" style={{ textAlign: settings.alignment, fontSize: settings.fontSize,
      ...(color ? { '--vinyl-color': color, '--lyrics-color': 'var(--lyric-vinyl)' } : {}) } as CSSProperties}>
      <p>晚风轻轻吹过耳边</p><p className="current">让音乐陪你走远</p><p>每段旋律都值得留恋</p>
    </div>
  </section>;
}
