import { useEffect, useState } from 'react';
import { native } from '../native/client';
import type { Lyrics } from '../native/library';
import { lyricsSource, reuseLyrics } from './lyrics';

interface Track { id: string; lyrics: Lyrics | null; title: string; cover: string; failed: boolean }

// Lyrics and title for the playing song. One request loop: fast while the Core
// is still reading or translating, then slow so a match saved from the main
// window also reaches the floating lyrics.
export function useDesktopTrack(id: string, active = true) {
  const [track, setTrack] = useState<Track>({ id: '', lyrics: null, title: '', cover: '', failed: false });
  useEffect(() => {
    if (!id || !active) return;
    const controller = new AbortController(); const { signal } = controller;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const update = (change: (current: Track) => Partial<Track>) => setTrack(current => {
      const base = current.id === id ? current : { id, lyrics: null, title: '', cover: '', failed: false };
      const next = { ...base, ...change(base) };
      return next.lyrics === base.lyrics && next.title === base.title && next.cover === base.cover && next.failed === base.failed ? base : next;
    });
    void native.library.getState(1, signal).then(library => {
      if (!signal.aborted) update(() => ({ title: [library.now.name, library.now.artist].filter(Boolean).join(' · '), cover: library.now.cover }));
    }, () => {});
    const load = async () => {
      let delay = 4000;
      try {
        const value = await native.library.lyrics(lyricsSource(id), id, signal);
        if (signal.aborted || value.id !== id) return;
        if (value.status === 'ready') update(current => ({ lyrics: reuseLyrics(current.lyrics, value), failed: false }));
        delay = value.status === 'pending' ? 250 : value.translationPending ? 500 : 4000;
      } catch {
        if (signal.aborted) return;
        update(current => ({ failed: !current.lyrics }));
      }
      timer = setTimeout(() => void load(), delay);
    };
    void load();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [id, active]);
  return track.id === id && id ? track : { id, lyrics: null, title: '', cover: '', failed: false };
}
