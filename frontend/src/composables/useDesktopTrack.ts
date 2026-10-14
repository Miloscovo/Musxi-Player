import { useEffect, useState } from 'react';
import { native } from '../native/client';
import type { Lyrics } from '../native/library';
import { lyricsSource } from './lyrics';

interface Track { id: string; lyrics: Lyrics | null; title: string; failed: boolean }

// Lyrics and title for the playing song. One request loop: fast while the Core
// is still reading or translating, then slow so a match saved from the main
// window also reaches the floating lyrics.
export function useDesktopTrack(id: string) {
  const [track, setTrack] = useState<Track>({ id: '', lyrics: null, title: '', failed: false });
  useEffect(() => {
    if (!id) return;
    const controller = new AbortController(); const { signal } = controller;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const update = (change: (current: Track) => Partial<Track>) =>
      setTrack(current => { const base = current.id === id ? current : { id, lyrics: null, title: '', failed: false }; return { ...base, ...change(base) }; });
    void native.library.getState(1, signal).then(library => {
      if (!signal.aborted) update(() => ({ title: [library.now.name, library.now.artist].filter(Boolean).join(' · ') }));
    }, () => {});
    const load = async () => {
      let delay = 4000;
      try {
        const value = await native.library.lyrics(lyricsSource(id), id, signal);
        if (signal.aborted || value.id !== id) return;
        if (value.status === 'ready') update(() => ({ lyrics: value, failed: false }));
        delay = value.status === 'pending' ? 250 : value.translationPending ? 500 : 4000;
      } catch {
        if (signal.aborted) return;
        update(current => ({ failed: !current.lyrics }));
      }
      timer = setTimeout(() => void load(), delay);
    };
    void load();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [id]);
  return track.id === id && id ? track : { id, lyrics: null, title: '', failed: false };
}
