import { useEffect, useState } from 'react';
import { lyricsBackgroundKey, readLyricsBackground, type LyricsBackground } from './lyricsBackground';

export function useLyricsBackground(): [LyricsBackground, (value: LyricsBackground) => void] {
  const [value, setValue] = useState(() => readLyricsBackground(localStorage));
  useEffect(() => {
    const sync = () => setValue(readLyricsBackground(localStorage));
    window.addEventListener(lyricsBackgroundKey, sync);
    return () => window.removeEventListener(lyricsBackgroundKey, sync);
  }, []);
  return [value, next => {
    localStorage.setItem(lyricsBackgroundKey, next); setValue(next); window.dispatchEvent(new Event(lyricsBackgroundKey));
  }];
}
