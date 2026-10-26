import { useEffect, useState } from 'react';
import { native } from '../native/client';
export function useCoverImage(url: string) {
  const [result, setResult] = useState({ url: '', image: '', loading: false });
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined; let attempts = 0;
    async function load() {
      let image = '';
      try {
        const candidate = await native.library.image(url, controller.signal);
        if (candidate && !controller.signal.aborted) {
          const decoded = new Image(); decoded.src = candidate;
          await decoded.decode();
          image = candidate;
        }
      } catch { /* Keep placeholder and retry. */ }
      if (controller.signal.aborted) return;
      if (image) setResult({ url, image, loading: false });
      else if (++attempts < 8) timer = setTimeout(load, 1500);
      else setResult({ url, image: '', loading: false });
    }
    if (url) { setResult({ url, image: '', loading: true }); void load(); }
    return () => { controller.abort(); clearTimeout(timer); };
  }, [url]);
  return { image: result.url === url ? result.image : '', loading: !!url && (result.url !== url || result.loading) };
}
