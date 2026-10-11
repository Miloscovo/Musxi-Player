import { useEffect, useLayoutEffect, useRef } from 'react';
import CoverImage from '../components/CoverImage';
import { Button } from '../components/ui/button';
import { useLyrics } from '../composables/useLyrics';
import { activeLyricLine } from '../composables/lyrics';
import type { LibraryState } from '../native/library';

const origins: Record<string, string> = { 'lrc-file': '本地歌词文件', embedded: '内嵌歌词', online: '在线歌词', cache: '在线歌词' };

export default function LyricsView({ trackId, now, positionMs, canSeek, onSeek, onClose }: {
  trackId: string; now?: LibraryState['now']; positionMs: number; canSeek: boolean;
  onSeek: (positionMs: number) => void; onClose: () => void;
}) {
  const { lyrics, loading, failed } = useLyrics(trackId, true);
  const list = useRef<HTMLOListElement>(null); const closeButton = useRef<HTMLButtonElement>(null);
  const userScroll = useRef(0);
  const synced = lyrics?.kind === 'synced';
  const lines = lyrics?.lines || [];
  const active = synced ? activeLyricLine(lines, positionMs) : -1;
  useEffect(() => { closeButton.current?.focus({ focusVisible: false } as FocusOptions); }, []);
  useEffect(() => {
    function keyboard(event: KeyboardEvent) {
      // Popovers and dialogs handle their own Escape first.
      if (event.key !== 'Escape' || document.querySelector('[role=dialog]')) return;
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
  }, [active, lines]);
  const markScrolled = () => { userScroll.current = Date.now(); };
  const message = !trackId ? '尚未播放歌曲' : loading ? '正在加载歌词…' : failed ? '歌词加载失败' : !lines.length ? '暂无歌词' : '';
  return <section className="lyrics-page" aria-label="歌词">
    <Button variant="unstyled" ref={closeButton} className="lyrics-close" aria-label="收起歌词页" title="收起" onClick={onClose}>
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>
    </Button>
    <div className="lyrics-track">
      <CoverImage url={now?.cover || ''} />
      <div className="lyrics-title"><h2 title={now?.name}>{now?.name || '尚未选择歌曲'}</h2>
        <p>{now?.artist}</p>
        {lyrics && origins[lyrics.origin] && <span className="lyrics-origin">{origins[lyrics.origin]}</span>}</div>
    </div>
    {message ? <p className="lyrics-message" role="status">{message}</p> :
      <ol ref={list} className={`lyrics-lines${synced ? ' synced' : ''}`} onWheel={markScrolled} onPointerDown={markScrolled} onKeyDown={markScrolled}>
        {lines.map((line, index) => <li key={index} aria-current={index === active ? 'true' : undefined}>
          {synced ? <Button variant="unstyled" disabled={!canSeek} onClick={() => { userScroll.current = 0; onSeek(line.timeMs); }}>
            {line.text || '♪'}</Button> : <span>{line.text || ' '}</span>}
        </li>)}
      </ol>}
  </section>;
}
