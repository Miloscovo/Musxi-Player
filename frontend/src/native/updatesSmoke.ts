import { native } from './client';
import { request, type CefTransport } from './transport';
import { formatDate, formatTime } from '../composables/appUpdates';

// Real app/hook/CEF/native flow, selected only by --cef-smoke plus a local fixture.
// Never replaces transport, app state, clocks or update actions with test doubles.
const host = window as unknown as CefTransport;
const require = (ok: unknown, why: string): void => { if (!ok) throw new Error(`Update UI: ${why}`); };
const text = (selector: string) => document.querySelector(selector)?.textContent ?? '';
const button = (scope: string, label: string) => Array.from(document.querySelectorAll<HTMLButtonElement>(`${scope} button`))
  .find(node => node.textContent?.trim() === label);
async function waitFor(probe: () => unknown | Promise<unknown>, why: string, timeout = 5000) {
  const until = performance.now() + timeout;
  while (!await probe()) {
    require(performance.now() < until, why);
    await new Promise(resolve => setTimeout(resolve, 50));
  }
}
async function click(node: HTMLButtonElement | null | undefined, why: string) {
  require(node?.isConnected && !node.disabled, `${why}: button missing or disabled`);
  node!.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'instant' });
  await waitFor(() => {
    const rect = node!.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0 && !node!.closest('[inert]') &&
      node!.contains(document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2));
  }, `${why}: button is covered or outside the viewport`);
  node!.click();
}
const queryButton = (selector: string) => document.querySelector<HTMLButtonElement>(selector);
const idle = () => waitFor(() => document.querySelector('.update-settings')?.getAttribute('aria-busy') === 'false', 'Action did not finish');
async function diagnostics() {
  const reply = await request(host, 'updates.fixtureDiagnostics', {}) as { fixtureMode: boolean; fetchCount: number; openedUrls: string[] };
  require(reply.fixtureMode === true && Array.isArray(reply.openedUrls), 'Not connected to a native fixture');
  return reply;
}
async function openUpdates() {
  await click(queryButton('.settings-nav'), 'Open settings');
  await waitFor(() => button('.settings-sections', '关于与更新'), 'Updates tab missing');
  await click(button('.settings-sections', '关于与更新'), 'Open updates tab');
  await waitFor(() => document.querySelector('.update-status'), 'Updates settings did not load');
}
const status = () => text('.update-status');
const downloadURL = (version: string) => `https://github.com/Miloscovo/Musxi-Player/releases/download/v${version}/MusxiPlayer-Setup-${version}.exe`;
async function expectOpen(node: HTMLButtonElement | null | undefined, url: string) {
  const before = (await diagnostics()).openedUrls.length;
  await click(node, 'Open download/notes');
  await waitFor(async () => (await diagnostics()).openedUrls.length === before + 1, 'Click did not record a URL');
  await idle();
  const after = (await diagnostics()).openedUrls;
  require(after[before] === url, `Unexpected URL: ${after[before]}`);
  require(!document.querySelector('[role=dialog]'), 'Download unexpectedly asked for a second confirmation');
}
async function finished() {
  await waitFor(async () => ['succeeded', 'failed'].includes((await native.updates.getState()).checkStatus), 'Native check did not finish', 15000);
}
async function hasNotice(version: string) {
  await waitFor(() => text('.update-notice').includes(version), `Missing notice for ${version}`, 15000);
}
function noticeBounds() {
  const notice = document.querySelector<HTMLElement>('.update-notice')!;
  require(notice, 'Missing notice');
  const card = notice.getBoundingClientRect(), dock = document.querySelector('.player')!.getBoundingClientRect();
  require(Math.abs(dock.top - card.bottom - 12) <= 1 && Math.abs(dock.right - card.right - 24) <= 1,
    'Notice is not above the right side of the player');
  require(card.left >= 0 && card.right <= innerWidth + 1 && card.top >= 0, 'Notice outside viewport');
  require(getComputedStyle(notice).opacity === '1', 'Notice dims its text');
}
async function verifyNotes() {
  await click(button('.update-actions', '展开说明'), 'Expand release notes');
  await waitFor(() => document.querySelector('.update-notes'), 'Release notes did not expand');
  const notes = document.querySelector('.update-notes')!;
  require(notes.textContent?.includes('更新说明') && notes.textContent.includes('普通链接'), 'Release notes lost readable text');
  require(!notes.querySelector('a,script,img,iframe') && !notes.textContent?.includes('##') &&
    !notes.textContent?.includes('https://example.invalid'), 'Release notes contain active HTML, links or Markdown');
  await click(button('.update-actions', '收起说明'), 'Collapse release notes');
  await waitFor(() => !document.querySelector('.update-notes'), 'Release notes stayed open');
}
async function verifyHiddenModes() {
  await click(queryButton('.lyrics-toggle'), 'Open lyrics');
  await waitFor(() => document.querySelector('.lyrics-page') && !document.querySelector('.update-notice'),
    'Notice stayed visible over lyrics');
  await click(queryButton('.lyrics-close'), 'Close lyrics');
  await hasNotice('0.5.0');
  await click(queryButton('.player-rail'), 'Collapse player');
  await waitFor(() => document.querySelector('.compact-player') && !document.querySelector('.update-notice'),
    'Notice stayed visible in compact mode');
  await waitFor(() => !queryButton('.player-rail')?.disabled, 'Collapse did not finish');
  await click(queryButton('.player-rail'), 'Expand player');
  await waitFor(() => !document.querySelector('.compact-player'), 'Player did not expand');
  await hasNotice('0.5.0');
}
async function verifyThemesAndWidth() {
  const size = await request(host, 'test.windowSize', {}) as { width: number; height: number };
  async function resize(width: number, height: number) {
    const current = await request(host, 'test.windowSize', {}) as { width: number; height: number };
    await request(host, 'test.resizeWindow', { stage: 'begin', ...current });
    await request(host, 'test.resizeWindow', { stage: 'end', width, height });
  }
  // Change appearance through the existing controls, so React and native theme stay in sync.
  for (const [index, mode] of [[0, 'light'], [1, 'dark']] as const) {
    await click(button('.settings-sections', '外观'), 'Open appearance');
    const transparency = queryButton('.appearance-transparency [role=switch]');
    if (transparency?.getAttribute('aria-checked') === 'true') await click(transparency, 'Disable transparency');
    await click(document.querySelectorAll<HTMLButtonElement>('[aria-labelledby=appearance-mode-label] button')[index], `Set ${mode} theme`);
    await waitFor(() => document.documentElement.dataset.theme === mode, `Theme did not become ${mode}`);
    await click(button('.settings-sections', '关于与更新'), 'Return to updates');
    await waitFor(() => document.querySelector('.update-settings'), 'Settings lost after theme change');
    noticeBounds();
    const styles = getComputedStyle(document.querySelector('.update-settings')!);
    require(styles.opacity === '1' && styles.color !== 'rgba(0, 0, 0, 0)', 'Unreadable update settings');
  }
  try {
    await resize(800, 560);
    await waitFor(() => Math.abs(innerWidth - 800) <= 1, 'Narrow window did not settle');
    noticeBounds();
    const content = document.querySelector<HTMLElement>('.library-content')!;
    require(content.scrollWidth <= content.clientWidth + 1, 'Narrow settings overflow horizontally');
    await click(button('.update-actions', '展开说明'), 'Narrow window notes');
    await click(button('.update-actions', '收起说明'), 'Narrow window collapse notes');
  } finally {
    await resize(size.width, size.height);
    await waitFor(() => Math.abs(innerWidth - size.width) <= 1, 'Window did not restore');
  }
}

export async function verifyUpdatesUI(scenario: string) {
  const initial = await native.updates.getState();
  require(initial.current.commit === 'update-fixture', 'UI smoke requires local native input');
  await diagnostics();
  await waitFor(() => document.querySelector('.library-shell'), 'Application did not mount');
  if (scenario === 'available') {
    // Do not trigger check here: the startup timer must produce the card itself.
    await hasNotice('0.5.0');
    noticeBounds();
    require(text('.update-notice').includes('预发布'), 'Prerelease badge missing');
    // Open details by clicking the card; this mounts the actual shared settings view.
    await click(button('.update-notice', '查看'), 'Notice details');
    await waitFor(() => document.querySelector('.update-settings'), 'Notice did not open settings');
    require(text('.update-current').includes('0.4.0') && text('.update-current').includes('update-fixture'), 'Wrong current build label');
    const state = await native.updates.getState();
    require(text('.update-release-head').includes(formatDate(state.latest!.publishedAt)) &&
      text('.update-release-head').includes('121 KB') && text('.update-release-head').includes('预发布'), 'Release date/size/badge missing');
    require(status().includes(formatTime(state.lastSuccessAt)), 'Local last-check time missing');
    await verifyNotes();
    await verifyThemesAndWidth();
    await verifyHiddenModes();
    await expectOpen(queryButton('.update-notice-primary'), downloadURL('0.5.0'));
    await expectOpen(button('.update-actions', '在浏览器查看发布说明'), 'https://github.com/Miloscovo/Musxi-Player/releases/tag/v0.5.0');
    await click(button('.update-ignore', '不再提醒此版本'), 'Ignore release');
    await waitFor(() => !document.querySelector('.update-notice') && text('.update-ignore').includes('已设置不再提醒'), 'Ignore did not hide notice/update button');
    await idle();
    require((await native.updates.getState()).preferences.ignoredReleaseId === '20', 'Ignore not saved natively');
    await click(button('.settings-sections', '播放'), 'Leave updates');
    await click(button('.settings-sections', '关于与更新'), 'Reopen updates');
    require(!document.querySelector('.update-notice'), 'Navigating restored an ignored notice');
    await click(button('.update-ignore', '恢复提醒'), 'Restore notice');
    await hasNotice('0.5.0');
    await click(queryButton('.update-notice-close'), 'Dismiss notice');
    await waitFor(() => !document.querySelector('.update-notice'), 'Notice did not close');
    await expectOpen(queryButton('.update-primary'), downloadURL('0.5.0'));
    const toggle = queryButton('.update-auto [role=switch]')!;
    await click(toggle, 'Disable automatic checks');
    await waitFor(() => toggle.getAttribute('aria-checked') === 'false', 'Automatic switch did not turn off');
    await idle();
    require(!(await native.updates.getState()).preferences.autoCheck, 'Auto preference not saved');
    await click(toggle, 'Enable automatic checks');
    await waitFor(() => toggle.getAttribute('aria-checked') === 'true', 'Automatic switch did not turn on');
    await idle();
    await new Promise(resolve => setTimeout(resolve, 1200));
    require(!document.querySelector('.update-notice'), 'Dismissed notice reappeared on refresh');
    require((await diagnostics()).fetchCount === 1, 'UI actions started another check');
  } else {
    await openUpdates();
    if (scenario === 'development' || scenario === 'checking') {
      if (scenario === 'development') {
        const toggle = queryButton('.update-auto [role=switch]')!;
        require(status().includes('开发版本') && toggle.disabled && toggle.getAttribute('aria-checked') === 'false',
          'Development build shows automatic checking enabled');
      }
      await click(queryButton('.update-status button'), 'Manual check');
      await waitFor(() => status().includes('正在检查') && queryButton('.update-status button')?.disabled,
        'Checking UI did not disable the check button');
    }
    await finished();
    if (scenario === 'stale' || scenario === 'changed') {
      await hasNotice('0.5.0');
      if (scenario === 'stale') {
        // Wait for native throttling AND the real hook's next refresh. Do not advance clocks.
        await waitFor(() => !queryButton('.update-status button')?.disabled, 'Manual check never became available', 65000);
        await click(queryButton('.update-status button'), 'Check again after success');
        await waitFor(() => status().includes('连接超时'), 'Failure was not shown', 10000);
        const state = await native.updates.getState();
        require(state.stale && state.latest?.releaseId === '20' && state.checkStatus === 'failed', 'Failure lost native prior result');
        require(status().includes('上次成功检查') && !status().includes('未发现更高版本'), 'Failure presented as up-to-date');
        require(text('.update-release-head').includes('0.5.0') && queryButton('.update-primary'), 'Stale release/download disappeared');
        require(button('.update-status', '重试')?.disabled, 'Failed check bypassed cooldown');
        await expectOpen(queryButton('.update-primary'), downloadURL('0.5.0'));
      } else {
        await click(button('.update-ignore', '不再提醒此版本'), 'Ignore previous version');
        await waitFor(() => !document.querySelector('.update-notice'), 'Ignore did not hide previous card');
        await idle();
        await waitFor(async () => (await native.updates.getState()).canCheck, 'Native cooldown did not finish', 40000);
        // Another check finishes between UI polls. The UI still carries the old IDs.
        await native.updates.check();
        await finished();
        require((await native.updates.getState()).latest?.releaseId === '30', 'Native release did not change');
        require(text('.update-release-head').includes('0.5.0'), 'Race setup failed: UI already adopted new IDs');
        const before = (await diagnostics()).openedUrls.length;
        await click(queryButton('.update-primary'), 'Download with stale UI IDs');
        await waitFor(() => text('.message-alerts').includes('版本信息已更新，请重新确认'), '409 did not display a helpful message');
        await waitFor(() => text('.update-release-head').includes('0.6.0'), '409 did not refresh release details');
        await idle();
        require((await diagnostics()).openedUrls.length === before, '409 opened a stale URL');
        await hasNotice('0.6.0');
        require(button('.update-ignore', '不再提醒此版本'), 'Ignoring previous release also ignored new release');
        await expectOpen(queryButton('.update-primary'), downloadURL('0.6.0'));
      }
    } else {
      const expected: Record<string, string> = { current: '未发现更高版本', empty: '尚无可识别的发布版本',
        missing: '暂无 Windows x64 安装包', development: '开发版本', checking: '发现新版本', failed: '连接超时' };
      await waitFor(() => status().includes(expected[scenario]), `Wrong conclusion for ${scenario}`, 15000);
      const state = await native.updates.getState();
      require(!document.querySelector('.update-demo'), 'Demo transport leaked into native UI');
      if (scenario === 'checking') await hasNotice('0.5.0');
      else require(!document.querySelector('.update-notice') && !queryButton('.update-primary'), 'Unexpected upgrade offer');
      if (scenario === 'empty' || scenario === 'failed') require(!document.querySelector('.update-release'), 'UI invented a release');
      if (scenario === 'failed') require(button('.update-status', '重试')?.disabled && state.latest === null, 'Initial failure lacks cooldown/retry');
      if (scenario === 'current') require(status().includes(formatTime(state.lastSuccessAt)), 'No last-check time');
      if (scenario === 'missing' || scenario === 'development') {
        await expectOpen(button('.update-actions', '在浏览器查看发布说明'), 'https://github.com/Miloscovo/Musxi-Player/releases/tag/v0.5.0');
      }
    }
  }
  // Existing completion command replies with a bare {}, then closes the native app.
  await new Promise<void>((resolve, reject) => host.cefQuery?.({
    request: JSON.stringify({ version: 1, command: 'test.complete', params: {} }), persistent: false,
    onSuccess: () => resolve(), onFailure: (_code, message) => reject(new Error(message))
  }));
}
