import { request, type CefTransport } from './transport.ts';
import { parsePlayerState, type RequestOptions, type NativeCommands } from './types.ts';

export function createNativeClient(host: CefTransport) {
  return Object.freeze({ player: Object.freeze({
    async getState(options?: RequestOptions) {
      const command: keyof NativeCommands = 'player.getState';
      return parsePlayerState(await request(host, command, {}, options));
    }
  }) });
}
export const native = createNativeClient(globalThis as CefTransport);
