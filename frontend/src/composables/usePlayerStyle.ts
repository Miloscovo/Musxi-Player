import { useEffect, useState } from 'react';
import { playerStyleKey, readPlayerStyle, type PlayerStyle } from './playerStyle';

export function usePlayerStyle(): [PlayerStyle, (value: PlayerStyle) => void] {
  const [value, setValue] = useState(() => readPlayerStyle(localStorage));
  useEffect(() => {
    const sync = () => setValue(readPlayerStyle(localStorage));
    window.addEventListener(playerStyleKey, sync);
    window.addEventListener('storage', sync);
    return () => { window.removeEventListener(playerStyleKey, sync); window.removeEventListener('storage', sync); };
  }, []);
  return [value, next => {
    localStorage.setItem(playerStyleKey, next); setValue(next); window.dispatchEvent(new Event(playerStyleKey));
  }];
}
