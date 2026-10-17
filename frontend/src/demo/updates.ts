import { NativeError } from '../native/types.ts';
// In-memory updates backend for ?demo. Mirrors docs/app-updates.md, including
// throttling, stale results and opaque IDs; it never opens a browser.
export const demoUpdateScenarios = {
  available: '有新版', latest: '已是最高版本', 'failed-stale': '失败但保留旧结果', failed: '首次检查失败',
  'no-releases': '尚无发布', 'no-asset': '缺少安装包', development: '开发构建', checking: '检查中', legacy: '旧后端'
} as const;
export type DemoUpdateScenario = keyof typeof demoUpdateScenarios;
const notes = `## 新功能
- **桌面歌词**支持双语显示与字号调整
- 设置新增「关于与更新」

## 修复
1. 修复部分歌曲无法播放后卡住的问题
2. 修复 [歌词偏移](https://example.invalid) 保存失败

<!-- internal -->
> 安装前请先退出播放器。`;
export function createDemoUpdates(now = () => Math.floor(Date.now() / 1000)) {
  let scenario: DemoUpdateScenario = 'available';
  let autoCheck = true, ignored = '', checkingUntil = 0, retryAt = 0, attempt = 0, success = 0;
  const release = (version: string, asset = true) => ({ releaseId: '20', assetId: asset ? '120' : '', version, tag: `v${version}`, prerelease: true,
    publishedAt: '2026-10-16T08:00:00Z', notes, assetName: asset ? `MusxiPlayer-Setup-${version}.exe` : '', bytes: asset ? 224013707 : 0 });
  function state() {
    const t = now(), checking = scenario === 'checking' || t < checkingUntil;
    const dev = scenario === 'development', failed = scenario === 'failed' || scenario === 'failed-stale';
    const result = scenario === 'failed' ? 'not_checked' : dev ? 'development_build' : scenario === 'latest' ? 'no_newer_release' :
      scenario === 'no-releases' ? 'no_releases' : scenario === 'no-asset' ? 'no_compatible_asset' : scenario === 'checking' ? 'not_checked' : 'update_available';
    const latest = ['failed', 'no-releases', 'checking'].includes(scenario) ? null :
      { ...release(scenario === 'latest' ? '0.4.0' : '0.5.0', scenario !== 'no-asset'), downloadAvailable: result === 'update_available' };
    const lastSuccess = scenario === 'failed' || scenario === 'checking' ? 0 : success || t - 3 * 3600;
    return { current: { version: '0.4.0', commit: 'e7ccf87f2d5587c1fb650e5f21fc186a3500994f', releaseBuild: !dev, architecture: 'x64' },
      source: 'Miloscovo/Musxi-Player', includesPrereleases: true, preferences: { autoCheck, ignoredReleaseId: ignored },
      automaticCheckEnabled: autoCheck && !dev, checkStatus: checking ? 'checking' : failed ? 'failed' : lastSuccess ? 'succeeded' : 'idle',
      result, reason: !checking && failed ? 'network_error' : '', latest, lastAttemptAt: attempt || lastSuccess, lastSuccessAt: lastSuccess,
      stale: scenario === 'failed-stale', retryAt, canCheck: !checking && t >= retryAt };
  }
  function run(command: string, p: Record<string, unknown>): unknown {
    if (scenario === 'legacy' && command !== 'updates.demoScenario') throw new NativeError(404, 'Unknown command');
    const s = state();
    switch (command) {
      case 'updates.demoScenario':
        if (!(String(p.scenario) in demoUpdateScenarios)) throw new NativeError(400, 'invalid_params');
        scenario = p.scenario as DemoUpdateScenario; retryAt = checkingUntil = 0; return { scenario };
      case 'updates.getState': return s;
      case 'updates.check':
        if (s.checkStatus === 'checking') return s;
        if (now() < retryAt) throw new NativeError(429, 'check_throttled');
        attempt = now(); checkingUntil = attempt + 2; retryAt = attempt + 30;
        if (!['failed', 'failed-stale'].includes(scenario)) success = attempt + 2;
        return state();
      case 'updates.setPreferences':
        if (typeof p.autoCheck !== 'boolean') throw new NativeError(400, 'invalid_params');
        autoCheck = p.autoCheck; return state();
      case 'updates.ignoreRelease':
        if (typeof p.releaseId !== 'string') throw new NativeError(400, 'invalid_params');
        if (p.releaseId && p.releaseId !== s.latest?.releaseId) throw new NativeError(409, 'release_changed');
        ignored = p.releaseId; return state();
      case 'updates.openDownload': case 'updates.openReleaseNotes':
        if (p.releaseId !== s.latest?.releaseId || (command === 'updates.openDownload' && (p.assetId !== s.latest?.assetId || !s.latest?.downloadAvailable)))
          throw new NativeError(409, 'release_changed');
        return { opened: true };
      default: throw new NativeError(404, 'Unknown command');
    }
  }
  return { run, setScenario: (next: DemoUpdateScenario) => run('updates.demoScenario', { scenario: next }) };
}
