import type { UpdateState } from '../native/updates.ts';
// Pure presentation rules for app updates; useAppUpdates owns I/O and timers.
const reasons: Record<string, string> = {
  network_error: '网络连接失败', timeout: '连接超时', cancelled: '检查已取消',
  rate_limited: 'GitHub 暂时限制了访问', http_error: 'GitHub 返回了异常响应', invalid_data: '发布信息格式异常',
  response_too_large: '发布信息过大', release_list_incomplete: '发布列表不完整，无法确认最新版本',
  network_disabled: '当前运行模式已禁用网络检查', internal_error: '播放器内部错误'
};
export const reasonText = (reason: string) => reasons[reason] || '未知原因';

export type UpdateTone = 'neutral' | 'success' | 'available' | 'warning' | 'error';
export function updateSummary(s: UpdateState): { title: string; detail: string; tone: UpdateTone } {
  if (s.checkStatus === 'checking') return { title: '正在检查更新…', detail: '正在读取 GitHub 发布列表', tone: 'neutral' };
  const v = s.latest ? `${s.latest.version}${s.latest.prerelease ? '（预发布）' : ''}` : '';
  const last = s.lastSuccessAt ? `上次成功检查：${formatTime(s.lastSuccessAt)}` : '';
  if (s.checkStatus === 'failed') return { title: `检查失败：${reasonText(s.reason)}`,
    detail: s.lastSuccessAt ? `以下为上次成功检查的结果（${formatTime(s.lastSuccessAt)}）` : '请检查网络后重试', tone: 'error' };
  const stale = s.stale && last ? `${last}，结果可能已过期` : last;
  switch (s.result) {
    case 'update_available': return { title: `发现新版本 ${v}`, detail: stale, tone: 'available' };
    case 'no_newer_release': return { title: '未发现更高版本', detail: stale, tone: 'success' };
    case 'no_releases': return { title: '尚无可识别的发布版本', detail: stale, tone: 'neutral' };
    case 'no_compatible_asset': return { title: `新版本 ${v} 暂无 Windows x64 安装包`, detail: stale || '可先查看发布说明', tone: 'warning' };
    case 'development_build': return { title: '开发版本', detail: s.latest ? `官方最新发布为 ${v}。开发版本不提供升级提示和安装包下载。` :
      s.lastSuccessAt ? '开发版本不提供升级提示和安装包下载。' : '开发版本不自动检查，可手动查看官方最新发布。', tone: 'neutral' };
    default: return { title: '尚未检查更新', detail: s.automaticCheckEnabled ? '启动后会在后台自动检查' : '', tone: 'neutral' };
  }
}
/** Only an installable newer release, once per run, unless the user ignored that version. */
export function shouldNotify(s: UpdateState | null, dismissed: ReadonlySet<string>) {
  const r = s?.latest;
  return !!(s && r && s.current.releaseBuild && s.result === 'update_available' && r.downloadAvailable &&
    !dismissed.has(r.releaseId) && s.preferences.ignoredReleaseId !== r.releaseId);
}
/** Native schedules the automatic check about 5 s after start, so poll briefly at first. */
export function pollDelay(s: UpdateState | null, sinceStartMs: number) {
  if (s?.checkStatus === 'checking') return 400;
  return sinceStartMs < 20000 ? 1000 : 30000;
}
export const formatTime = (seconds: number) => new Date(seconds * 1000).toLocaleString('zh-CN',
  { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false });
export function formatDate(utc: string) {
  const date = new Date(utc);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' });
}
export const formatBytes = (bytes: number) => bytes >= 1048576 ? `${(bytes / 1048576).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
export const shortCommit = (commit: string) => commit === 'update-fixture' ? commit : /^[0-9a-f]{7,40}$/i.test(commit) ? commit.slice(0, 7) : '未知';

export type NoteLine = { kind: 'heading' | 'item' | 'text' | 'gap'; text: string };
/** Release notes are Markdown from GitHub; show readable plain text, never HTML or live links. */
export function noteLines(notes: string): NoteLine[] {
  const inline = (line: string) => line
    .replace(/!\[[^\]]*\]\([^)]*\)/g, '')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/(\*\*|__|~~|`)/g, '')
    .replace(/<[^>]+>/g, '').trim();
  const out: NoteLine[] = [];
  for (let raw of notes.replace(/<!--[\s\S]*?-->/g, '').split(/\r?\n/)) {
    raw = raw.replace(/^\s{0,3}(?:>\s?)+/, '');
    const heading = /^\s{0,3}#{1,6}\s+(.*)$/.exec(raw), item = /^\s*(?:[-*+]|\d+[.)])\s+(?:\[[ xX]\]\s+)?(.*)$/.exec(raw);
    const text = inline(heading?.[1] ?? item?.[1] ?? raw);
    if (/^\s*([-*_])\s*(\1\s*){2,}$/.test(raw)) continue;
    if (!text) { if (out.length && out[out.length - 1].kind !== 'gap') out.push({ kind: 'gap', text: '' }); continue; }
    out.push({ kind: heading ? 'heading' : item ? 'item' : 'text', text });
  }
  while (out.length && out[out.length - 1].kind === 'gap') out.pop();
  return out;
}
