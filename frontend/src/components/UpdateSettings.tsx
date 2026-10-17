import { useState } from 'react';
import type { AppUpdates } from '../composables/useAppUpdates';
import { formatBytes, formatDate, formatTime, noteLines, shortCommit, updateSummary } from '../composables/appUpdates';
import { isDemoMode } from '../demo/context';
import { demoUpdateScenarios, type DemoUpdateScenario } from '../demo/updates';
import { native } from '../native/client';
import { Button } from './ui/button';

export function ReleaseNotes({ notes }: { notes: string }) {
  const lines = noteLines(notes);
  if (!lines.length) return <p className="update-notes-empty">此版本没有填写发布说明。</p>;
  return <div className="update-notes">{lines.map((line, index) => line.kind === 'gap' ? <div key={index} className="update-notes-gap"/> :
    line.kind === 'heading' ? <h4 key={index}>{line.text}</h4> :
    <p key={index} className={line.kind === 'item' ? 'update-notes-item' : undefined}>{line.text}</p>)}</div>;
}

export default function UpdateSettings({ updates }: { updates: AppUpdates }) {
  const { state, busy } = updates;
  const [notesOpen, setNotesOpen] = useState(false);
  const [scenario, setScenario] = useState<DemoUpdateScenario>('available');
  const demo = isDemoMode() && <div className="playback-setting update-demo">
    <label htmlFor="update-demo-scenario">演示状态</label>
    <select id="update-demo-scenario" value={scenario} onChange={async e => {
      const next = e.currentTarget.value as DemoUpdateScenario; setScenario(next);
      await native.updates.demoScenario(next); await updates.refresh();
    }}>{Object.entries(demoUpdateScenarios).map(([id, label]) => <option key={id} value={id}>{label}</option>)}</select>
  </div>;
  if (updates.unsupported) return <section className="settings-content" aria-label="关于与更新">
    <p className="update-unsupported">当前播放器版本不支持检查更新。</p>{demo}</section>;
  if (!state) return <section className="settings-content" aria-label="关于与更新">
    {updates.loadError ? <p className="playback-settings-error" role="alert">读取更新状态失败：{updates.loadError}</p> :
      <p className="playback-settings-loading">正在读取版本信息…</p>}{demo}</section>;
  const summary = updateSummary(state), latest = state.latest, checking = state.checkStatus === 'checking';
  const throttled = !checking && !state.canCheck && state.retryAt > 0;
  const ignored = !!latest && state.preferences.ignoredReleaseId === latest.releaseId;
  return <section className="settings-content update-settings" aria-label="关于与更新" aria-busy={checking || !!busy}>
    <div className="update-current">
      <h3>Musxi Player {state.current.version}</h3>
      <p>{state.current.releaseBuild ? '正式构建' : '开发构建'} · Windows {state.current.architecture} · 源码 {shortCommit(state.current.commit)}</p>
    </div>
    <div className="update-status" data-tone={summary.tone} role="status" aria-live="polite">
      <div><strong>{summary.title}</strong>{summary.detail && <p>{summary.detail}</p>}</div>
      <Button variant="secondary" disabled={checking || throttled || !!busy} onClick={() => void updates.check()}
        title={throttled ? `可在 ${formatTime(state.retryAt)} 后再次检查` : undefined}>
        {checking ? '检查中…' : state.checkStatus === 'failed' ? '重试' : '检查更新'}</Button>
    </div>
    {latest && <div className="update-release">
      <div className="update-release-head">
        <div><h3>{latest.version}{latest.prerelease && <span className="update-badge">预发布</span>}</h3>
          <p>{[formatDate(latest.publishedAt) && `发布于 ${formatDate(latest.publishedAt)}`, latest.assetName && `${latest.assetName} · ${formatBytes(latest.bytes)}`].filter(Boolean).join(' · ')}</p></div>
      </div>
      <div className="update-actions">
        {latest.downloadAvailable && <Button className="update-primary" disabled={!!busy}
          onClick={() => void updates.openDownload(latest.releaseId, latest.assetId)}>下载安装包</Button>}
        <Button variant="secondary" disabled={!!busy} onClick={() => void updates.openNotes(latest.releaseId)}>在浏览器查看发布说明</Button>
        <Button variant="secondary" aria-expanded={notesOpen} onClick={() => setNotesOpen(open => !open)}>{notesOpen ? '收起说明' : '展开说明'}</Button>
      </div>
      {latest.downloadAvailable && <p className="update-hint">安装包会在浏览器中下载，请退出播放器后再运行安装程序。</p>}
      {notesOpen && <ReleaseNotes notes={latest.notes}/>}
      {updates.canIgnore && latest.downloadAvailable && <p className="update-ignore">{ignored ? <>已设置不再提醒此版本。
        <Button variant="unstyled" className="update-link" disabled={!!busy} onClick={() => void updates.ignore('')}>恢复提醒</Button></> :
        <Button variant="unstyled" className="update-link" disabled={!!busy} onClick={() => void updates.ignore(latest.releaseId)}>不再提醒此版本</Button>}</p>}
    </div>}
    <div className="appearance-setting-row update-auto">
      <div><h3 id="update-auto-label">启动时自动检查更新</h3>
        <p id="update-auto-description">{state.current.releaseBuild ? '每天最多检查一次 GitHub 官方发布，包含预发布版本' : '开发构建不会自动检查'}</p></div>
      <Button variant="unstyled" className="appearance-switch" role="switch" aria-checked={state.current.releaseBuild && state.preferences.autoCheck}
        aria-labelledby="update-auto-label" aria-describedby="update-auto-description" disabled={!state.current.releaseBuild || !!busy}
        onClick={() => void updates.setAutoCheck(!state.preferences.autoCheck)}><span/></Button>
    </div>
    <p className="update-source">更新来源：GitHub {state.source}{state.includesPrereleases ? '（含预发布）' : ''}</p>
    {demo}
  </section>;
}
