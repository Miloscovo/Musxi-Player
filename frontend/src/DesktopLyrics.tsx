import { type ReactNode, type WheelEvent } from 'react';
import { native } from './native/client';
import { desktopFontRange } from './native/desktopLyrics';
import { useDesktopLyrics } from './composables/useDesktopLyrics';
import { useLyricsTranslation } from './composables/lyricsTranslation';
// Windowless CEF draws no native tooltips, so every control carries its own label.
function Tool({ label, tip = label, pressed, className = '', onClick, onWheel, children }: {
  label: string; tip?: string; pressed?: boolean; className?: string; onClick?: () => void;
  onWheel?: (event: WheelEvent) => void; children: ReactNode;
}) {
  return <span className="dl-tip-anchor">
    <button type="button" className={`dl-tool ${className}`} aria-label={label} aria-pressed={pressed} onClick={onClick} onWheel={onWheel}>{children}</button>
    <span className="dl-tip" role="tooltip">{tip}</span>
  </span>;
}
const Icon = ({ children }: { children: ReactNode }) => <svg viewBox="0 0 24 24" aria-hidden="true">{children}</svg>;
export default function FloatingLyricsControls({ playing, pause, resume }: {
  playing: boolean; pause: () => Promise<void>; resume: () => Promise<void>;
}) {
  const desktop = useDesktopLyrics(); const d = desktop.state;
  const [translation, setTranslation] = useLyricsTranslation();
  const resize = (delta: number) => {
    const next = Math.min(desktopFontRange.max, Math.max(desktopFontRange.min, d.fontSize + delta));
    if (next !== d.fontSize) void desktop.setFontSize(next);
  };
  const wheel = (event: WheelEvent) => { if (event.deltaY) resize(event.deltaY < 0 ? desktopFontRange.step : -desktopFontRange.step); };
  return <>
    {d.panel && <div className="dl-capsule" role="toolbar" aria-label="歌词悬浮窗控制">
      <Tool label="上一首" onClick={() => void native.library.skip(-1).catch(() => {})}><Icon><rect className="fill" x="5" y="5.5" width="2.6" height="13" rx="1.1"/><path className="fill" d="M19 6.4v11.2a1 1 0 0 1-1.5.9L9 13a1.2 1.2 0 0 1 0-2l8.5-5.5a1 1 0 0 1 1.5.9z"/></Icon></Tool>
      <Tool label={playing ? '暂停' : '播放'} className="dl-play" onClick={() => void (playing ? pause() : resume())}>
        {playing ? <Icon><rect className="fill" x="7.2" y="6" width="3.4" height="12" rx="1.3"/><rect className="fill" x="13.4" y="6" width="3.4" height="12" rx="1.3"/></Icon>
          : <Icon><path className="fill" d="M9 6.6v10.8a1 1 0 0 0 1.5.9l8.4-5.4a1.1 1.1 0 0 0 0-1.8l-8.4-5.4a1 1 0 0 0-1.5.9z"/></Icon>}
      </Tool>
      <Tool label="下一首" onClick={() => void native.library.skip(1).catch(() => {})}><Icon><rect className="fill" x="16.4" y="5.5" width="2.6" height="13" rx="1.1"/><path className="fill" d="M5 6.4v11.2a1 1 0 0 0 1.5.9L15 13a1.2 1.2 0 0 0 0-2L6.5 5.5A1 1 0 0 0 5 6.4z"/></Icon></Tool>
      <span className="dl-divider" aria-hidden="true" />
      <Tool label="显示翻译" tip={translation ? '隐藏翻译' : '显示翻译'} pressed={translation} onClick={() => setTranslation(!translation)}>
        <span className="dl-text-icon">译</span>
      </Tool>
      <span className="dl-tip-anchor"><span className="dl-size" role="group" aria-label={`字号 ${d.fontSize}`} onWheel={wheel}>
        <button type="button" className="dl-tool dl-size-step" aria-label="缩小字号" onClick={() => resize(-desktopFontRange.step)}>−</button>
        <span className="dl-size-value">Aa {d.fontSize}</span>
        <button type="button" className="dl-tool dl-size-step" aria-label="放大字号" onClick={() => resize(desktopFontRange.step)}>+</button>
      </span><span className="dl-tip" role="tooltip">字号 · 滚轮调整</span></span>
    </div>}
    {desktop.error && <small className="dl-error" role="alert">{desktop.error}</small>}
  </>;
}
