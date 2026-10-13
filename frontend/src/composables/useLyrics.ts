import { useEffect, useRef, useState } from 'react';
import { native } from '../native/client';
import type { Lyrics } from '../native/library';
import { lyricsSource } from './lyrics';
import { loadLyricsRequest, refreshTranslation } from './lyricsRequest';

// Lyrics for one track; polls while the Core reads files in the background.
export function useLyrics(trackId: string, enabled: boolean) {
  const [result, setResult] = useState<{ id: string; lyrics: Lyrics | null; failed: boolean }>({ id: '', lyrics: null, failed: false });
  const [attempt, setAttempt] = useState(0);
  const [translationAttempt, setTranslationAttempt] = useState(0);
  const lastTranslationAttempt=useRef(0);
  useEffect(() => {
    if (!trackId || !enabled) return;
    const controller = new AbortController();
    const retryTranslation=translationAttempt!==lastTranslationAttempt.current;
    lastTranslationAttempt.current=translationAttempt;
    setResult(previous => retryTranslation && previous.id===trackId && previous.lyrics ? previous : { id: trackId, lyrics: null, failed: false });
    const wait = (ms = 250) => new Promise<void>(resolve => {
      const done = () => { clearTimeout(timer); controller.signal.removeEventListener('abort', done); resolve(); };
      const timer = setTimeout(done, ms);
      controller.signal.addEventListener('abort', done, { once: true });
      if (controller.signal.aborted) done();
    });
    let retryRequested=retryTranslation;
    const request = () => {
      const retry=retryRequested;retryRequested=false;
      return native.library.lyrics(lyricsSource(trackId), trackId, controller.signal, retry);
    };
    async function load() {
      try {
        const lyrics = await loadLyricsRequest(request, trackId, controller.signal, wait);
        if (controller.signal.aborted) return;
        setResult({ id: trackId, lyrics, failed: false });
        // The original is already on screen; a late translation replaces it in place.
        if (lyrics.translationPending) {
          await refreshTranslation(request, trackId, controller.signal, () => wait(500),
            next => { if (!controller.signal.aborted) setResult({ id: trackId, lyrics: next, failed: false }); });
          if(!controller.signal.aborted)setResult(previous => previous.id===trackId && previous.lyrics ?
            {...previous,lyrics:{...previous.lyrics,translationPending:false}} : previous);
        }
      } catch {
        // Refresh failures keep what is shown; only a failed first load reports an error.
        if (!controller.signal.aborted) setResult(previous => previous.id === trackId && previous.lyrics ?
          {...previous,lyrics:{...previous.lyrics,translationPending:false}} : { id: trackId, lyrics: null, failed: true });
      }
    }
    void load();
    return () => { controller.abort(); };
  }, [trackId, enabled, attempt, translationAttempt]);
  const current = result.id === trackId ? result : { lyrics: null, failed: false };
  return { lyrics: current.lyrics, failed: current.failed, loading: !!trackId && !current.lyrics && !current.failed,
    retry: () => setAttempt(value => value + 1), retryTranslation: () => setTranslationAttempt(value => value + 1) };
}
