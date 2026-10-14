import { useEffect, useState, type ReactNode, type WheelEvent } from 'react';
import { native } from './native/client';
import { desktopColors, desktopFontRange, type DesktopColor } from './native/desktopLyrics';
import { usePlayerState } from './composables/usePlayerState';
import { useDesktopLyrics } from './composables/useDesktopLyrics';
import { useDesktopTrack } from './composables/useDesktopTrack';
import { activeLyricLine } from './composables/lyrics';
import { readAppearance } from './composables/appearance';
import { reportDesktopPage } from './native/desktopSmoke';

const colorNames: Record<DesktopColor, string> = { theme: '主题色', light: '白色', dark: '深色' };

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
const lockIcon = (open: boolean) => <Icon><rect className="fill" x="5" y="10.5" width="14" height="10" rx="3"/>
  <path d={open ? 'M8.2 10.5V7.6a3.8 3.8 0 0 1 7.2-1.7' : 'M8.2 10.5V7.6a3.8 3.8 0 0 1 7.6 0v2.9'}/></Icon>;

// Floating lyrics: text only by default. Right click toggles the control capsule
// (native tracks it); a locked window shows only an unlock button on hover.
export default function DesktopLyrics() {
  const { state: player, connected, pause, resume } = usePlayerState();
  const desktop = useDesktopLyrics(); const d = desktop.state;
  const id = player?.opened ? player.trackId : '';
  const { lyrics, title, failed } = useDesktopTrack(id);
  const [colorsOpen, setColorsOpen] = useState(false);
  useEffect(() => {
    const tone = () => { document.documentElement.dataset.tone = readAppearance(localStorage).tone; };
    tone(); window.addEventListener('storage', tone);
    const stopReport = new URLSearchParams(location.search).has('desktopSmoke') ? reportDesktopPage() : undefined;
    return () => { window.removeEventListener('storage', tone); stopReport?.(); };
  }, []);
  useEffect(() => { if (!d.panel) setColorsOpen(false); }, [d.panel]);
  const lines = lyrics?.kind === 'synced' ? lyrics.lines : [];
  const index = activeLyricLine(lines, player?.positionMs ?? 0);
  const line = index >= 0 ? lines[index] : undefined;
  const status = !connected ? '正在连接播放器…' : !id ? 'Musxi Player' : !lyrics ? (failed ? '歌词暂不可用' : '正在获取歌词…')
    : lyrics.kind === 'synced' ? title : lyrics.kind === 'plain' ? '暂无同步歌词' : '暂无歌词';
  const playing = player?.requestedPlaying ?? player?.playing ?? false;
  const resize = (delta: number) => {
    const next = Math.min(desktopFontRange.max, Math.max(desktopFontRange.min, d.fontSize + delta));
    if (next !== d.fontSize) void desktop.setFontSize(next);
  };
  const wheel = (event: WheelEvent) => { if (event.deltaY) resize(event.deltaY < 0 ? desktopFontRange.step : -desktopFontRange.step); };
  return <main className="desktop-lyrics" aria-label="桌面歌词" data-color={d.color} data-panel={d.panel} data-locked={d.locked}
      data-hover={d.hover} style={{ '--dl-size': `${d.fontSize}px` } as React.CSSProperties}>
    {d.locked && d.hover && <button type="button" className="dl-unlock" onClick={() => void desktop.setLocked(false)}>{lockIcon(true)}解锁</button>}
    <div className="dl-lyrics">
      {line ? <>
        <p key={index} className="dl-line">{line.text || '♪'}</p>
        {d.dual && line.translation && <p key={`t${index}`} className="dl-line dl-translation">{line.translation}</p>}
      </> : <p className="dl-status">{status || 'Musxi Player'}</p>}
    </div>
    {d.panel && <div className="dl-capsule" role="toolbar" aria-label="桌面歌词控制">
      <Tool label="上一首" onClick={() => void native.library.skip(-1).catch(() => {})}><Icon><rect className="fill" x="5" y="5.5" width="2.6" height="13" rx="1.1"/><path className="fill" d="M19 6.4v11.2a1 1 0 0 1-1.5.9L9 13a1.2 1.2 0 0 1 0-2l8.5-5.5a1 1 0 0 1 1.5.9z"/></Icon></Tool>
      <Tool label={playing ? '暂停' : '播放'} className="dl-play" onClick={() => void (playing ? pause() : resume())}>
        {playing ? <Icon><rect className="fill" x="7.2" y="6" width="3.4" height="12" rx="1.3"/><rect className="fill" x="13.4" y="6" width="3.4" height="12" rx="1.3"/></Icon>
          : <Icon><path className="fill" d="M9 6.6v10.8a1 1 0 0 0 1.5.9l8.4-5.4a1.1 1.1 0 0 0 0-1.8l-8.4-5.4a1 1 0 0 0-1.5.9z"/></Icon>}
      </Tool>
      <Tool label="下一首" onClick={() => void native.library.skip(1).catch(() => {})}><Icon><rect className="fill" x="16.4" y="5.5" width="2.6" height="13" rx="1.1"/><path className="fill" d="M5 6.4v11.2a1 1 0 0 0 1.5.9L15 13a1.2 1.2 0 0 0 0-2L6.5 5.5A1 1 0 0 0 5 6.4z"/></Icon></Tool>
      <span className="dl-divider" aria-hidden="true" />
      <Tool label="显示翻译" tip={d.dual ? '隐藏翻译' : '显示翻译'} pressed={d.dual} onClick={() => void desktop.setDual(!d.dual)}>
        <span className="dl-text-icon">译</span>
      </Tool>
      <Tool label={`字号 ${d.fontSize}`} tip="字号 · 滚轮调整" className="dl-size" onWheel={wheel}>
        <span className="dl-size-step" role="button" aria-label="缩小字号" onClick={() => resize(-desktopFontRange.step)}>−</span>
        <span className="dl-size-value">Aa {d.fontSize}</span>
        <span className="dl-size-step" role="button" aria-label="放大字号" onClick={() => resize(desktopFontRange.step)}>+</span>
      </Tool>
      <span className="dl-color-anchor" onMouseEnter={() => setColorsOpen(true)} onMouseLeave={() => setColorsOpen(false)}>
        <button type="button" className="dl-tool" aria-label={`歌词颜色：${colorNames[d.color]}`} aria-haspopup="menu" aria-expanded={colorsOpen}
          onClick={() => setColorsOpen(open => !open)}><span className="dl-swatch" /></button>
        {colorsOpen && <span className="dl-colors" role="menu" aria-label="歌词颜色">
          {desktopColors.map(color => <button key={color} type="button" role="menuitemradio" aria-checked={d.color === color} data-swatch={color}
            onClick={() => { void desktop.setColor(color); setColorsOpen(false); }}><span className="dl-swatch" />{colorNames[color]}</button>)}
        </span>}
      </span>
      <Tool label="锁定桌面歌词" tip="锁定（鼠标穿透）" onClick={() => void desktop.setLocked(true)}>{lockIcon(false)}</Tool>
      <Tool label="关闭桌面歌词" onClick={() => void desktop.setVisible(false)}><Icon><path d="M7 7l10 10M17 7 7 17"/></Icon></Tool>
    </div>}
    {desktop.error && <small className="dl-error" role="alert">{desktop.error}</small>}
  </main>;
}
