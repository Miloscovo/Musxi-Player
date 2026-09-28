import { useEffect, useState } from 'react';
import { native } from '../native/client';
export function useCoverImage(url: string) {
  const [result, setResult] = useState({ url: '', image: '' });
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined; let attempts = 0;
    async function load() {
      let image = '';
      try { image = await native.library.image(url, controller.signal); } catch { /* Keep placeholder. */ }
      if (controller.signal.aborted) return;
      if (image) setResult({ url, image });
      else if (++attempts < 8) timer = setTimeout(load, 1500);
    }
    if (url) void load();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [url]);
  return result.url === url ? result.image : '';
}
