import type { CSSProperties } from 'react';
import type { Appearance, AppearanceMode, AppearanceTone } from '../composables/appearance';
import { Button } from './ui/button';

const modes: { id: AppearanceMode; label: string; path: string }[] = [
  { id: 'light', label: '浅色', path: 'M12 3V1m0 22v-2M3 12H1m22 0h-2M4.2 4.2 2.8 2.8m18.4 18.4-1.4-1.4M4.2 19.8l-1.4 1.4M21.2 2.8l-1.4 1.4M17 12a5 5 0 1 1-10 0 5 5 0 0 1 10 0Z' },
  { id: 'dark', label: '深色', path: 'M20.7 13.5A9 9 0 0 1 10.5 3.3 9 9 0 1 0 20.7 13.5Z' },
  { id: 'system', label: '跟随系统', path: 'M4 3h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Zm8 14v4m-4 0h8' },
];
const tones: { id: AppearanceTone; label: string; color: string }[] = [
  { id: 'green', label: '绿色', color: '#087f6d' },
  { id: 'blue', label: '蓝色', color: '#3171c4' },
  { id: 'pink', label: '粉色', color: '#ff69b4' },
  { id: 'red', label: '红色', color: '#c23e43' },
];

export default function AppearanceSettings({ appearance, onAppearance, transparency, onTransparency }: {
  appearance: Appearance; onAppearance: (next: Appearance) => void;
  transparency: number; onTransparency: (value: number) => void;
}) {
  return <section className="settings-content" aria-label="外观设置">
    <div className="appearance-mode">
      <h3 id="appearance-mode-label">外观模式</h3>
      <div className="appearance-segments" role="group" aria-labelledby="appearance-mode-label">
        {modes.map(mode => <Button key={mode.id} variant="unstyled" className="appearance-segment"
          aria-pressed={appearance.mode === mode.id} onClick={() => onAppearance({ ...appearance, mode: mode.id })}>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d={mode.path}/></svg>{mode.label}
        </Button>)}
      </div>
    </div>
    <div className="appearance-tone appearance-mode">
      <h3 id="appearance-tone-label">色调</h3>
      <div className="appearance-segments" role="group" aria-labelledby="appearance-tone-label">
        {tones.map(tone => <Button key={tone.id} variant="unstyled" className="appearance-segment"
          aria-pressed={appearance.tone === tone.id} onClick={() => onAppearance({ ...appearance, tone: tone.id })}>
          <span className="appearance-tone-dot" style={{ background: tone.color }} aria-hidden="true"/>{tone.label}
        </Button>)}
      </div>
    </div>
    <div className="appearance-transparency">
      <div className="appearance-setting-row">
        <div><h3 id="transparency-label">透明效果</h3><p id="transparency-description">让窗口背景呈现柔和的半透明效果</p></div>
        <Button variant="unstyled" className="appearance-switch" role="switch" aria-checked={appearance.transparent}
          aria-labelledby="transparency-label" aria-describedby="transparency-description"
          onClick={() => onAppearance({ ...appearance, transparent: !appearance.transparent })}><span/></Button>
      </div>
      {appearance.transparent && <div className="appearance-level">
        <div className="appearance-setting-row"><label htmlFor="appearance-transparency">透明程度</label><output htmlFor="appearance-transparency">{transparency}%</output></div>
        <input id="appearance-transparency" className="appearance-slider" type="range" min="0" max="100" step="1"
          value={transparency} aria-valuetext={`${transparency}%透明`} style={{ '--level': `${transparency}%` } as CSSProperties}
          onChange={event => onTransparency(Number(event.currentTarget.value))}/>
        <div className="appearance-slider-labels" aria-hidden="true"><span>不透明</span><span>更透明</span></div>
      </div>}
    </div>
  </section>;
}
