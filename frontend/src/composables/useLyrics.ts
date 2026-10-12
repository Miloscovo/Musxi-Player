import { useEffect, useState } from 'react';
import { native } from '../native/client';
import type { Lyrics } from '../native/library';
import { lyricsSource } from './lyrics';
import { loadLyricsRequest } from './lyricsRequest';

// Lyrics for one track; polls while the Core reads files in the background.
export function useLyrics(trackId: string, enabled: boolean) {
  const [result, setResult] = useState<{ id: string; lyrics: Lyrics | null; failed: boolean }>({ id: '', lyrics: null, failed: false });
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    if (!trackId || !enabled) return;
    const controller = new AbortController();
    setResult({ id: trackId, lyrics: null, failed: false });
    const wait = () => new Promise<void>(resolve => {
      const done = () => { clearTimeout(timer); controller.signal.removeEventListener('abort', done); resolve(); };
      const timer = setTimeout(done, 250);
      controller.signal.addEventListener('abort', done, { once: true });
      if (controller.signal.aborted) done();
    });
    async function load() {
      try {
        const lyrics = await loadLyricsRequest(
          () => native.library.lyrics(lyricsSource(trackId), trackId, controller.signal), trackId, controller.signal, wait);
        if (!controller.signal.aborted) setResult({ id: trackId, lyrics, failed: false });
      } catch {
        if (!controller.signal.aborted) setResult({ id: trackId, lyrics: null, failed: true });
      }
    }
    void load();
    return () => { controller.abort(); };
  }, [trackId, enabled, attempt]);
  const current = result.id === trackId ? result : { lyrics: null, failed: false };
  return { lyrics: current.lyrics, failed: current.failed, loading: !!trackId && !current.lyrics && !current.failed,
    retry: () => setAttempt(value => value + 1) };
}
