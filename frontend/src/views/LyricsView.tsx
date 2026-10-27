import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties, type SyntheticEvent } from 'react';
import { animate, motion, useIsPresent } from 'motion/react';
import { useLyricsDisplay } from '../composables/useLyricsDisplay';
import { useLyricsBackground } from '../composables/useLyricsBackground';
import { usePlayerStyle } from '../composables/usePlayerStyle';
import { sampleCoverColor } from '../composables/vinylColor';
import LyricsMatchDialog from '../components/LyricsMatchDialog';
import VinylCover from '../components/VinylCover';
import CoverImage from '../components/CoverImage';
import { Button } from '../components/ui/button';
import { useLyrics } from '../composables/useLyrics';
import { activeLyricLine, observeLyricDragRegions } from '../composables/lyrics';
import { useLyricsTranslation } from '../composables/lyricsTranslation';
import { translationHint } from '../composables/translationStatus';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '../components/ui/tooltip';
import type { LibraryState } from '../native/library';

const origins: Record<string, string> = { 'lrc-file': '本地歌词文件', embedded: '内嵌歌词', online: '在线歌词', cache: '在线歌词', matched: '指定歌词' };

export default function LyricsView({ trackId, now, positionMs, playing, canSeek, onSeek, onClose, onEntered, bottomOffset, onExpandPlayer, onPlayerCover }: {
  trackId: string; now?: LibraryState['now']; positionMs: number; playing: boolean; canSeek: boolean;
  onSeek: (positionMs: number) => void; onClose: () => void;
  onEntered: () => void;
  bottomOffset: number;
  onExpandPlayer: () => void;
  onPlayerCover: (covered: number) => void;
}) {
  const present = useIsPresent();
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  useLayoutEffect(() => { if (present && reduced) onEntered(); }, [present, reduced, onEntered]);
  const { lyrics, loading, failed, retry, retryTranslation, translation, settleManual } = useLyrics(trackId, true);
  const hint = translationHint(lyrics, translation);
  // The reply to a manual check fades out after a few seconds (see .transient).
  useEffect(() => { if (!hint.transient) return; const timer = setTimeout(settleManual, 4000); return () => clearTimeout(timer); },
    [hint.transient, hint.text]);
  const [matching,setMatching]=useState(false);
  const [showTranslation,setShowTranslation]=useLyricsTranslation();
  const [display] = useLyricsDisplay();
  const [background] = useLyricsBackground();
  const [playerStyle] = usePlayerStyle();
  const title = useRef<HTMLDivElement>(null);
  const [titleHeight, setTitleHeight] = useState(0);
  useLayoutEffect(() => {
    const element = title.current;
    if (!element) return;
    const measure = () => {
      const button = element.querySelector<HTMLElement>(':scope>button[data-variant=secondary]');
      const style = button && getComputedStyle(button);
      setTitleHeight(element.getBoundingClientRect().height - (button ? button.getBoundingClientRect().height + parseFloat(style!.marginTop) + parseFloat(style!.marginBottom) : 0));
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  const [coverColor, setCoverColor] = useState({ url: '', color: '', image: '' });
  function sampleCover(event: SyntheticEvent<HTMLDivElement>) {
    if (event.target instanceof HTMLImageElement)
      setCoverColor({ url: now?.cover || '', color: sampleCoverColor(event.target), image: event.target.currentSrc });
  }
  const backgroundImage = background === 'cover' && now?.cover && coverColor.url === now.cover ? coverColor.image : '';
  useEffect(()=>{setMatching(false);},[trackId]);
  const list = useRef<HTMLOListElement>(null); const closeButton = useRef<HTMLButtonElement>(null);
  const expandButton = useRef<HTMLButtonElement>(null);
  useLayoutEffect(() => { if(bottomOffset>0)expandButton.current?.focus({ preventScroll:true }); }, [bottomOffset]);
  const userScroll = useRef(0);
  const scrollAnimation = useRef<ReturnType<typeof animate> | null>(null);
  const synced = lyrics?.kind === 'synced';
  const lines = lyrics?.lines || [];
  const active = synced ? activeLyricLine(lines, positionMs) : -1;
  const translated = synced && lines.some(line => line.translation);
  const dual = translated && showTranslation;
  useEffect(() => { closeButton.current?.focus({ focusVisible: false } as FocusOptions); }, []);
  useEffect(() => {
    if (!present) return;
    function keyboard(event: KeyboardEvent) {
      // Popovers and dialogs handle their own Escape first.
      if (event.key !== 'Escape' || event.defaultPrevented || document.querySelector('[role=dialog]')) return;
      event.preventDefault(); onClose();
    }
    window.addEventListener('keydown', keyboard);
    return () => window.removeEventListener('keydown', keyboard);
  }, [onClose, present]);
  useLayoutEffect(() => {
    userScroll.current = 0;
    if (list.current) list.current.scrollTop = 0;
  }, [trackId]);
  useLayoutEffect(() => {
    const container = list.current; const row = container?.children[Math.max(0, active)] as HTMLElement | undefined;
    // Leave the view alone for a few seconds after the user scrolls it.
    if (!container || !row || Date.now() - userScroll.current < 3000) return;
    const top = row.offsetTop - container.clientHeight / 2 + row.clientHeight / 2;
    if (reduced) container.scrollTop = top;
    else scrollAnimation.current = animate(container.scrollTop, top, {
      duration: 0.48, ease: [0.22, 1, 0.36, 1], onUpdate: value => { container.scrollTop = value; },
    });
    return () => { scrollAnimation.current?.stop(); scrollAnimation.current = null; };
    // Showing or hiding translations changes row heights; re-centre the current line.
  }, [active, lines, dual, display.alignment, display.fontSize, reduced]);
  const markScrolled = () => { userScroll.current = Date.now(); scrollAnimation.current?.stop(); scrollAnimation.current = null; };
  const message = !trackId ? '尚未播放歌曲' : loading ? '正在加载歌词…' : failed ? '歌词加载失败，请检查网络后重试' :
    !lines.length ? (lyrics?.platform && lyrics.platform !== 'local' ? '这首歌在平台上暂无歌词' : '暂无歌词') : '';
  useLayoutEffect(() => {
    if (list.current) return observeLyricDragRegions(list.current);
  }, [lines, message]);
  return <motion.section className="lyrics-page" aria-label="歌词" inert={!present} aria-hidden={!present || undefined}
    initial={reduced ? false : { opacity: 0, y: 24 }} animate={{ opacity: 1, y: 0, bottom: -bottomOffset }} exit={{ opacity: 0, y: reduced ? 0 : 24 }}
    transition={{ duration: reduced ? 0 : 0.22, ease: [0.22, 1, 0.36, 1] }}
    onUpdate={latest => onPlayerCover(Math.max(0, -Number(latest.bottom || 0)))}
    onAnimationComplete={() => { if (present) onEntered(); }}
    data-alignment={display.alignment} data-player-style={playerStyle} data-cover-background={backgroundImage ? 'true' : undefined} style={{ '--lyrics-font-size': `${display.fontSize}px`,
      ...(display.colorMode === 'cover' && now?.cover && coverColor.url === now.cover && coverColor.color
        ? { '--vinyl-color': coverColor.color, '--lyrics-color': 'var(--lyric-vinyl)' } : {}) } as CSSProperties}>
    {backgroundImage && <div className="lyrics-cover-background" aria-hidden="true"><img src={backgroundImage} alt=""/></div>}
    <Button variant="unstyled" ref={closeButton} className="lyrics-close" aria-label="收起歌词页" title="收起" onClick={onClose}>
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>
    </Button>
    <div className="lyrics-track" style={{ '--compact-art-size': `${titleHeight}px` } as CSSProperties}>
      {playerStyle === 'vinyl' ? <VinylCover url={now?.cover || ''} playing={playing} onColor={(url,color,image)=>setCoverColor({url,color,image})} /> :
        <div className="lyrics-square-cover" hidden={playerStyle === 'none'} onLoadCapture={sampleCover}><CoverImage url={now?.cover || ''} animateLoading /></div>}
      <div ref={title} className="lyrics-title"><h2 title={now?.name}>{now?.name || '尚未选择歌曲'}</h2>
        <p>{now?.artist}</p>
        {lyrics && origins[lyrics.origin] && <span className="lyrics-origin">{origins[lyrics.origin]}</span>}
        {trackId.startsWith('local:') && <Button variant="secondary" size="sm" onClick={()=>setMatching(true)}>{lyrics?.kind==='none'?'搜索歌词':'选择 / 替换歌词'}</Button>}
        {(translated || hint.text || hint.action) && <div className="lyrics-translation">
          {translated && <Button variant="unstyled" className="lyrics-translation-toggle" role="switch" aria-checked={showTranslation}
            onClick={()=>setShowTranslation(!showTranslation)}><span aria-hidden="true"/>显示翻译</Button>}
          {hint.action==='recheck' && <TooltipProvider delayDuration={400}><Tooltip><TooltipTrigger asChild>
            <Button variant="unstyled" className="lyrics-translation-recheck" aria-label="重新检查翻译" disabled={hint.pending}
              data-pending={hint.pending || undefined} onClick={retryTranslation}>
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 12a8 8 0 1 1-2.34-5.66"/><path d="M20 4v4.5h-4.5"/></svg>
            </Button></TooltipTrigger><TooltipContent side="bottom" className="compact">重新检查翻译</TooltipContent></Tooltip></TooltipProvider>}
          {hint.text && <span className={`lyrics-translation-status${hint.transient?' transient':''}`} role="status">{hint.text}</span>}
          {hint.action==='retry' && <Button variant="unstyled" className="lyrics-translation-retry" disabled={hint.pending} onClick={retryTranslation}>
            {lyrics?.translation==='available'?'重试':'重试获取翻译'}</Button>}
        </div>}</div>
    </div>
    {message ? <div className="lyrics-message" role="status"><p>{message}</p>
      {failed && <Button variant="ghost" onClick={retry}>重试</Button>}</div> :
      <ol ref={list} className={`lyrics-lines${synced ? ' synced' : ''}${dual ? ' dual' : ''}`} onWheel={markScrolled} onPointerDown={markScrolled} onKeyDown={markScrolled}>
        {lines.map((line, index) => <li key={index} aria-current={index === active ? 'true' : undefined}>
          {synced ? <Button variant="unstyled" disabled={!canSeek} onClick={() => { userScroll.current = 0; onSeek(line.timeMs); }}>
            {line.text || '♪'}{dual && line.translation && <small>{line.translation}</small>}</Button> : <span>{line.text || ' '}</span>}
        </li>)}
      </ol>}
    {bottomOffset>0 && <Button variant="unstyled" ref={expandButton} className="lyrics-player-expand" aria-label="展开播放栏" title="展开播放栏" aria-expanded={false} onClick={onExpandPlayer}>
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 15 6-6 6 6"/></svg>
    </Button>}
    {matching && <LyricsMatchDialog key={trackId} id={trackId} positionMs={positionMs} canSeek={canSeek} onSeek={onSeek} onClose={()=>setMatching(false)} onSaved={retry}/>}
  </motion.section>;
}
