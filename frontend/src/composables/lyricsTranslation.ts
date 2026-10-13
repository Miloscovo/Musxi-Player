import { useEffect, useState } from 'react';

// Shared by the lyrics page and the match preview. Hiding translations is a
// display preference only; it never tells the Core that a song has none.
const key = 'musxi-lyrics-translation';
const event = 'musxi-lyrics-translation';
export function readLyricsTranslation(storage: Pick<Storage, 'getItem'>) { return storage.getItem(key) !== 'off'; }
export function writeLyricsTranslation(storage: Pick<Storage, 'setItem'>, shown: boolean) { storage.setItem(key, shown ? 'on' : 'off'); }

export function useLyricsTranslation(): [boolean, (shown: boolean) => void] {
  const [shown, setShown] = useState(() => { try { return readLyricsTranslation(localStorage); } catch { return true; } });
  useEffect(() => {
    const sync = () => { try { setShown(readLyricsTranslation(localStorage)); } catch { /* Keep current. */ } };
    window.addEventListener(event, sync);
    return () => window.removeEventListener(event, sync);
  }, []);
  return [shown, value => {
    setShown(value);
    try { writeLyricsTranslation(localStorage, value); } catch { /* Storage may be unavailable; keep it for this session. */ }
    window.dispatchEvent(new Event(event));
  }];
}
