import { request, type CefTransport } from './transport.ts';
import { NativeError } from './types.ts';
export type Theme = 'light' | 'dark' | 'glass-light' | 'glass';
export interface WindowState { enabled: boolean; maximized: boolean }
export function createWindowClient(host: CefTransport) {
  async function call(command: string, params = {}): Promise<WindowState> {
    const value = await request(host, command, params) as Partial<WindowState> | null;
    if (!value || typeof value.enabled !== 'boolean' || typeof value.maximized !== 'boolean')
      throw new NativeError(502, 'Invalid window state');
    return value as WindowState;
  }
  return Object.freeze({
    getState: () => call('window.getState'),
    minimize: () => call('window.minimize'), maximize: () => call('window.maximize'),
    close: () => call('window.close'),
    setTheme: (theme: Theme) => call('window.setTheme', { theme })
  });
}
