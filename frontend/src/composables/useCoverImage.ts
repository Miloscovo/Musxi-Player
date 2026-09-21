import { ref, watch, onUnmounted, type Ref } from 'vue';
import { native } from '../native/client';
export function useCoverImage(url: Ref<string>) {
  const image = ref(''); let timer: ReturnType<typeof setTimeout>; let controller: AbortController | undefined;
  watch(url, value => {
    clearTimeout(timer); controller?.abort(); image.value = '';
    const current = new AbortController(); controller = current; let attempts = 0;
    async function load() {
      try { const data = await native.library.image(value, current.signal); if (!current.signal.aborted) image.value = data; } catch { /* Keep placeholder. */ }
      if (!image.value && !current.signal.aborted && ++attempts < 8) timer = setTimeout(load, 1500);
    }
    if (value) void load();
  }, { immediate: true });
  onUnmounted(() => { controller?.abort(); clearTimeout(timer); });
  return image;
}
