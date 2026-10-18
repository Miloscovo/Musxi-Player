import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties } from 'react';
import { useLyricsDisplay } from '../composables/useLyricsDisplay';
import LyricsMatchDialog from '../components/LyricsMatchDialog';
import CoverImage from '../components/CoverImage';
import { Button } from '../components/ui/button';
import { useLyrics } from '../composables/useLyrics';
import { activeLyricLine, observeLyricDragRegions } from '../composables/lyrics';
import { useLyricsTranslation } from '../composables/lyricsTranslation';
import { translationHint } from '../composables/translationStatus';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '../components/ui/tooltip';
import type { LibraryState } from '../native/library';

const origins: Record<string, string> = { 'lrc-file': '本地歌词文件', embedded: '内嵌歌词', online: '在线歌词', cache: '在线歌词', matched: '指定歌词' };

export default function LyricsView({ trackId, now, positionMs, canSeek, onSeek, onClose }: {
  trackId: string; now?: LibraryState['now']; positionMs: number; canSeek: boolean;
  onSeek: (positionMs: number) => void; onClose: () => void;
}) {
  const { lyrics, loading, failed, retry, retryTranslation, translation, settleManual } = useLyrics(trackId, true);
  const hint = translationHint(lyrics, translation);
  // The reply to a manual check fades out after a few seconds (see .transient).
  useEffect(() => { if (!hint.transient) return; const timer = setTimeout(settleManual, 4000); return () => clearTimeout(timer); },
    [hint.transient, hint.text]);
  const [matching,setMatching]=useState(false);
  const [showTranslation,setShowTranslation]=useLyricsTranslation();
  const [display] = useLyricsDisplay();
  useEffect(()=>{setMatching(false);},[trackId]);
  const list = useRef<HTMLOListElement>(null); const closeButton = useRef<HTMLButtonElement>(null);
  const userScroll = useRef(0);
  const synced = lyrics?.kind === 'synced';
  const lines = lyrics?.lines || [];
  const active = synced ? activeLyricLine(lines, positionMs) : -1;
  const translated = synced && lines.some(line => line.translation);
  const dual = translated && showTranslation;
  useEffect(() => { closeButton.current?.focus({ focusVisible: false } as FocusOptions); }, []);
  useEffect(() => {
    function keyboard(event: KeyboardEvent) {
      // Popovers and dialogs handle their own Escape first.
      if (event.key !== 'Escape' || event.defaultPrevented || document.querySelector('[role=dialog]')) return;
      event.preventDefault(); onClose();
    }
    window.addEventListener('keydown', keyboard);
    return () => window.removeEventListener('keydown', keyboard);
  }, [onClose]);
  useEffect(() => { userScroll.current = 0; list.current?.scrollTo({ top: 0 }); }, [trackId]);
  useLayoutEffect(() => {
    const container = list.current; const row = container?.children[Math.max(0, active)] as HTMLElement | undefined;
    // Leave the view alone for a few seconds after the user scrolls it.
    if (!container || !row || Date.now() - userScroll.current < 3000) return;
    container.scrollTo({ top: row.offsetTop - container.clientHeight / 2 + row.clientHeight / 2, behavior: 'smooth' });
    // Showing or hiding translations changes row heights; re-centre the current line.
  }, [active, lines, dual, display.alignment, display.fontSize]);
  const markScrolled = () => { userScroll.current = Date.now(); };
  const message = !trackId ? '尚未播放歌曲' : loading ? '正在加载歌词…' : failed ? '歌词加载失败，请检查网络后重试' :
    !lines.length ? (lyrics?.platform && lyrics.platform !== 'local' ? '这首歌在平台上暂无歌词' : '暂无歌词') : '';
  useLayoutEffect(() => {
    if (list.current) return observeLyricDragRegions(list.current);
  }, [lines, message]);
  return <section className="lyrics-page" aria-label="歌词" data-alignment={display.alignment} style={{ '--lyrics-font-size': `${display.fontSize}px` } as CSSProperties}>
    <Button variant="unstyled" ref={closeButton} className="lyrics-close" aria-label="收起歌词页" title="收起" onClick={onClose}>
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>
    </Button>
    <div className="lyrics-track">
      <CoverImage url={now?.cover || ''} />
      <div className="lyrics-title"><h2 title={now?.name}>{now?.name || '尚未选择歌曲'}</h2>
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
    {matching && <LyricsMatchDialog key={trackId} id={trackId} positionMs={positionMs} canSeek={canSeek} onSeek={onSeek} onClose={()=>setMatching(false)} onSaved={retry}/>}
  </section>;
}
