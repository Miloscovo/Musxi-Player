import { useEffect, useState } from 'react';
import { lyricsDisplayKey, readLyricsDisplay, type LyricsDisplay } from './lyricsDisplay';

export function useLyricsDisplay(): [LyricsDisplay, (value: LyricsDisplay) => void] {
  const [value, setValue] = useState(() => readLyricsDisplay(localStorage));
  useEffect(() => {
    const sync = () => setValue(readLyricsDisplay(localStorage));
    window.addEventListener(lyricsDisplayKey, sync);
    return () => window.removeEventListener(lyricsDisplayKey, sync);
  }, []);
  return [value, next => {
    localStorage.setItem(lyricsDisplayKey, JSON.stringify(next));setValue(next);window.dispatchEvent(new Event(lyricsDisplayKey));
  }];
}
