import type { Lyrics, TranslationLookupReason } from '../native/library.ts';

// How the page describes a song's translation. "wait" is this page's own view of
// the last lookup: it stopped waiting (timeout) or the poll itself failed (error);
// "manual" marks a lookup the user asked for, so its quiet outcomes get a reply.
export interface TranslationHint {
  text: string;
  // retry/recheck: the button repeats a lookup; the page may show it as text or an icon.
  action: 'retry' | 'recheck' | '';
  pending: boolean;
  // A short reply to a manual check that fades out by itself.
  transient: boolean;
}
export interface TranslationWait { wait?: 'timeout' | 'error' | ''; manual?: boolean }

const failures: Partial<Record<TranslationLookupReason, string>> = {
  network: '网络连接失败，未取得翻译', timeout: '翻译查询超时', platform_error: '平台请求失败，未取得翻译',
  invalid_data: '翻译数据无法使用', unsupported_format: '暂不支持该版本的翻译格式', version_mismatch: '翻译与当前歌词版本不一致',
};
const hint = (text: string, action: TranslationHint['action'] = '', pending = false, transient = false): TranslationHint =>
  ({ text, action, pending, transient });

export function translationHint(lyrics: Lyrics | null, { wait = '', manual = false }: TranslationWait = {}): TranslationHint {
  if (!lyrics || lyrics.status !== 'ready' || !lyrics.lines.length) return hint('');
  const synced = lyrics.kind === 'synced';
  const translated = synced && lyrics.lines.some(line => line.translation);
  const lookup = lyrics.translationLookup;
  const pending = !wait && (lookup ? lookup.status === 'pending' : !!lyrics.translationPending);
  if (!lookup) {
    // Older Core: the v0.4 rules, including its platform list for retries.
    if (pending) return hint(translated ? '' : '正在获取翻译…', '', true);
    if (translated) return hint('');
    if (wait) return hint(wait === 'timeout' ? '翻译查询较慢，可稍后重试' : '获取翻译失败', 'retry');
    if (synced && lyrics.translation === 'none') return hint('该版本暂无翻译');
    const retryable = lyrics.platform === 'netease' || lyrics.platform === 'qq' || !!lyrics.translationRetryable;
    return hint('', lyrics.translation === 'unknown' && retryable ? 'retry' : '');
  }
  const refresh = lookup.canRefresh;
  // While checking, only the recheck icon stays (disabled); a text button beside "正在获取" is noise.
  if (pending) return hint(translated ? '正在更新翻译…' : '正在获取翻译…', translated && refresh ? 'recheck' : '', true);
  if (wait) return hint(wait === 'timeout' ? '翻译查询较慢，可稍后重试' : '获取翻译失败',
    refresh ? 'retry' : '');
  if (lookup.status === 'unsupported')
    // Unbound local lyrics say nothing: the page already offers choosing lyrics.
    return hint(lookup.reason === 'not_synced' ? '当前歌词暂不支持逐行翻译' : '');
  if (translated) {
    if (lookup.status === 'failed') return hint('翻译更新失败，继续显示已保存翻译', refresh ? 'retry' : '');
    if (manual && lookup.status === 'succeeded')
      return hint(lookup.reason === 'not_provided' ? '平台本次未提供新翻译' : '已重新检查翻译', refresh ? 'recheck' : '', false, true);
    return hint('', refresh ? 'recheck' : '');
  }
  if (!synced) return hint('');
  if (lookup.status === 'failed')
    return hint(failures[lookup.reason!] || '获取翻译失败', refresh ? lookup.reason === 'version_mismatch' ? 'recheck' : 'retry' : '');
  // none: the platform's answer (or a saved one); unknown: not checked yet.
  if (lyrics.translation === 'unknown') return hint('', refresh ? 'retry' : '');
  return hint('该版本暂无翻译', refresh ? 'recheck' : '');
}
