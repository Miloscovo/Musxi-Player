import { ref, onMounted } from 'vue';
import { native } from '../native/client';
import type { Theme, WindowState } from '../native/window';
export function useWindow() {
  const state = ref<WindowState>({ enabled: false, maximized: false });
  const error = ref('');
  const saved = localStorage.getItem('musxi-theme');
  const theme = ref<Theme>(saved === 'dark' || saved === 'glass' ? saved : 'light');
  document.documentElement.dataset.theme = theme.value;
  async function invoke(action: () => Promise<WindowState>) {
    try { state.value = await action(); error.value = ''; }
    catch (e) { error.value = e instanceof Error ? e.message : '窗口操作失败'; }
  }
  async function changeTheme() {
    theme.value = theme.value === 'light' ? 'dark' : theme.value === 'dark' ? 'glass' : 'light';
    document.documentElement.dataset.theme = theme.value;
    localStorage.setItem('musxi-theme', theme.value);
    await invoke(() => native.window.setTheme(theme.value));
  }
  onMounted(() => invoke(() => native.window.setTheme(theme.value)));
  return { state, error, theme, changeTheme,
    minimize: () => invoke(native.window.minimize), maximize: () => invoke(native.window.maximize),
    close: () => invoke(native.window.close) };
}
