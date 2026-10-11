import { useEffect, useState } from 'react';
import { native } from '../native/client';
import type { Lyrics } from '../native/library';
import { lyricsSource } from './lyrics';

// Lyrics for one track; polls while the Core reads files in the background.
export function useLyrics(trackId: string, enabled: boolean) {
  const [result, setResult] = useState<{ id: string; lyrics: Lyrics | null; failed: boolean }>({ id: '', lyrics: null, failed: false });
  useEffect(() => {
    if (!trackId || !enabled) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined; let attempts = 0;
    async function load() {
      try {
        const lyrics = await native.library.lyrics(lyricsSource(trackId), trackId, controller.signal);
        if (controller.signal.aborted || lyrics.id !== trackId) return;
        if (lyrics.status === 'ready') { setResult({ id: trackId, lyrics, failed: false }); return; }
      } catch {
        if (controller.signal.aborted) return;
      }
      // A pending lookup normally settles within a few ticks; give up after ~10 s.
      if (++attempts < 40) timer = setTimeout(load, 250);
      else setResult({ id: trackId, lyrics: null, failed: true });
    }
    void load();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [trackId, enabled]);
  const current = result.id === trackId ? result : { lyrics: null, failed: false };
  return { lyrics: current.lyrics, failed: current.failed, loading: !!trackId && !current.lyrics && !current.failed };
}
