import { useEffect, useRef, useState } from 'react';
import { native } from '../native/client';
import type { Lyrics } from '../native/library';
import { lyricsSource } from './lyrics';
import { loadLyricsRequest, refreshTranslation } from './lyricsRequest';
import type { TranslationWait } from './translationStatus';

interface Result { id: string; lyrics: Lyrics | null; failed: boolean; translation: TranslationWait }
const empty = (id: string): Result => ({ id, lyrics: null, failed: false, translation: {} });

// Lyrics for one track; polls while the Core reads files in the background.
export function useLyrics(trackId: string, enabled: boolean) {
  const [result, setResult] = useState<Result>(empty(''));
  const [attempt, setAttempt] = useState(0);
  const [translationAttempt, setTranslationAttempt] = useState(0);
  const lastTranslationAttempt=useRef(0);
  useEffect(() => {
    if (!trackId || !enabled) return;
    const controller = new AbortController();
    const retryTranslation=translationAttempt!==lastTranslationAttempt.current;
    lastTranslationAttempt.current=translationAttempt;
    // A manual check keeps the lyrics and any saved translation on screen.
    // Until the Core replies, show the check as pending so the old outcome is not
    // read as this check's answer and the button cannot be pressed twice.
    setResult(previous => {
      if (!retryTranslation || previous.id!==trackId || !previous.lyrics) return empty(trackId);
      const { translationLookup: lookup } = previous.lyrics;
      return {...previous,translation:{manual:true},lyrics:{...previous.lyrics,translationPending:true,
        ...(lookup?{translationLookup:{...lookup,status:'pending'}}:{})}};
    });
    const wait = (ms = 250) => new Promise<void>(resolve => {
      const done = () => { clearTimeout(timer); controller.signal.removeEventListener('abort', done); resolve(); };
      const timer = setTimeout(done, ms);
      controller.signal.addEventListener('abort', done, { once: true });
      if (controller.signal.aborted) done();
    });
    // retryTranslation goes with the first request only; polls are ordinary reads.
    let retryRequested=retryTranslation;
    const request = () => {
      const retry=retryRequested;retryRequested=false;
      return native.library.lyrics(lyricsSource(trackId), trackId, controller.signal, retry);
    };
    const show = (lyrics: Lyrics) => { if (!controller.signal.aborted) setResult(previous =>
      ({ id: trackId, lyrics, failed: false, translation: previous.id===trackId ? previous.translation : {} })); };
    const settle = (wait: TranslationWait['wait']) => { if (!controller.signal.aborted) setResult(previous =>
      previous.id===trackId && previous.lyrics ? {...previous,translation:{...previous.translation,wait}} : previous); };
    async function load() {
      try {
        const lyrics = await loadLyricsRequest(request, trackId, controller.signal, wait);
        if (controller.signal.aborted) return;
        show(lyrics);
        // The original is already on screen; a late translation replaces it in place.
        if (lyrics.translationPending) {
          const outcome = await refreshTranslation(request, trackId, controller.signal, () => wait(500), show);
          // 'stopped' without an abort means the Core restarted the song; stop waiting too.
          if (outcome !== 'settled') settle('timeout');
        }
      } catch {
        // Refresh failures keep what is shown; only a failed first load reports an error.
        if (!controller.signal.aborted) setResult(previous => previous.id === trackId && previous.lyrics ?
          {...previous,translation:{...previous.translation,wait:'error'}} : { ...empty(trackId), failed: true });
      }
    }
    void load();
    return () => { controller.abort(); };
  }, [trackId, enabled, attempt, translationAttempt]);
  const current = result.id === trackId ? result : empty(trackId);
  return { lyrics: current.lyrics, failed: current.failed, translation: current.translation,
    loading: !!trackId && !current.lyrics && !current.failed,
    retry: () => setAttempt(value => value + 1), retryTranslation: () => setTranslationAttempt(value => value + 1),
    // Ends the short reply to a manual check; the next check starts a new one.
    settleManual: () => setResult(previous => previous.translation.manual ? {...previous,translation:{...previous.translation,manual:false}} : previous) };
}
