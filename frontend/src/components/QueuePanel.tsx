import { useRef, useState, useEffect, type ComponentProps } from 'react';
import { AnimatePresence, motion, useIsPresent } from 'motion/react';
import type { useLibrary } from '../composables/useLibrary';
import { Button } from './ui/button';
import PlayerControl from './PlayerControl';
import { Popover, PopoverTrigger, PopoverContent } from './ui/popover';

function QueueSurface(props: ComponentProps<typeof motion.div>) {
  const present = useIsPresent();
  return <motion.div {...props} inert={!present}/>;
}

export default function QueuePanel({ library }: { library: ReturnType<typeof useLibrary> }) {
  const [open, setOpen] = useState(false);
  const currentRow = useRef<HTMLLIElement>(null);
  const tracks = library.state?.queue || [];
  const currentId = library.state?.queueCurrentId || '';
  useEffect(() => {
    if (open) currentRow.current?.scrollIntoView({ block: 'nearest' });
  }, [open, currentId]);
  return <Popover open={open} onOpenChange={setOpen}>
    <PopoverTrigger asChild><PlayerControl variant="unstyled" className="preview-control queue-control" aria-label="播放列表" title="播放列表">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 5h12M4 10h12M4 15h7M17 14v7l5-3.5z"/></svg>
    </PlayerControl></PopoverTrigger>
    <AnimatePresence initial={false}>
    {open && <PopoverContent forceMount asChild side="top" align="end" sideOffset={48} className="queue-fade" aria-label="播放列表" onOpenAutoFocus={event => event.preventDefault()}>
      <QueueSurface initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
        transition={{ duration: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 0.15 }}>
      <div className="queue-panel-header"><h2>播放列表 ({tracks.length})</h2>
        <Button variant="unstyled" className="queue-action" aria-label="清空播放列表" disabled={!tracks.length || library.pending} onClick={() => void library.queueClear()}>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M9 4h6M6 7v13h12V7"/></svg>
        </Button>
      </div>
      <ul className="queue-panel-list">{tracks.map(track => {
        const current = track.id === currentId;
        const cannotPlay = library.pending || (!track.id.startsWith('local:') && !library.state?.connected);
        const seconds = Math.floor(track.duration / 1000);
        return <li key={track.id} ref={current ? currentRow : undefined} className={`queue-row${current ? ' current' : ''}`}>
          <Button variant="unstyled" className="queue-song" disabled={cannotPlay} title={track.name} onClick={() => void library.play('queue', track.id)}>
            <strong>{track.name}</strong><span>{Math.floor(seconds / 60)}分{seconds % 60}秒</span>
          </Button>
          <Button variant="unstyled" className="queue-action queue-play" aria-label={`${current ? '重新播放' : '播放'} ${track.name}`} disabled={cannotPlay} onClick={() => void library.play('queue', track.id)}>
            <svg viewBox="0 0 24 24" aria-hidden="true">{current ? <><rect x="7" y="5" width="3.5" height="14" rx="1.2"/><rect x="13.5" y="5" width="3.5" height="14" rx="1.2"/></> : <path d="m7 4 14 8-14 8z"/>}</svg>
          </Button>
          <Button variant="unstyled" className="queue-action" aria-label={`从播放列表移除 ${track.name}`} disabled={library.pending} onClick={() => void library.queueRemove(track.id)}>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg>
          </Button>
        </li>;
      })}</ul>
      {!tracks.length && <p className="queue-empty">播放列表为空</p>}
      </QueueSurface>
    </PopoverContent>}
    </AnimatePresence>
  </Popover>;
}
